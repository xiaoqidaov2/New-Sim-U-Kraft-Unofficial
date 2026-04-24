package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC休息处理器
 * 处理NPC的休息状态：所有NPC（工作中的和空闲的）在晚上都会返回家中休息
 */
@SuppressWarnings("null")
public class NPCRestHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    // 休息时间配置（游戏刻）
    private static final int EVENING_START_TIME = 12000; // 傍晚开始时间（约18:00）
    private static final int EVENING_FORCE_TELEPORT_TIME = 14000; // 傍晚强制传送时间（约19:00），超过此时间未到家则传送
    private static final int MORNING_END_TIME = 0; // 早上结束时间（约6:00）
    private static final int MORNING_PREPARE_TIME = 2000; // 早上准备出发时间（约5:00，游戏刻2000）
    @SuppressWarnings("unused")
    private static final int REST_CHECK_INTERVAL = 100; // 检查间隔（5秒）
    private static final int MAX_GOING_TO_WORK_TIME = 6000; // 最长去工作时间（游戏刻5分钟）

    // 存储正在休息的NPC数据
    private static final Map<UUID, RestData> restingNPCs = new ConcurrentHashMap<>();
    // 存储NPC的休息子状态
    private static final Map<UUID, WorkSubState> npcSubStates = new ConcurrentHashMap<>();
    // 存储NPC的住宅位置
    private static final Map<UUID, BlockPos> npcHomePositions = new ConcurrentHashMap<>();
    // 存储NPC的寻路状态
    private static final Map<UUID, Boolean> npcPathfindingStatus = new ConcurrentHashMap<>();
    // 存储NPC休息前的工作状态（用于早上恢复）
    private static final Map<UUID, WorkStatus> npcPreviousWorkStatus = new ConcurrentHashMap<>();
    // 存储NPC休息前的工作内容（职业）
    private static final Map<UUID, String> npcPreviousJob = new ConcurrentHashMap<>();
    // 注意：建造任务现在统一从JSON文件恢复，不再使用内存Map存储
    // 存储NPC休息前的规划师任务ID（用于规划师恢复工作）
    private static final Map<UUID, UUID> npcPreviousPlanningTaskId = new ConcurrentHashMap<>();

    // 休息阶段常量
    private static final int REST_STAGE_IDLE = 0;
    private static final int REST_STAGE_GOING_HOME = 1;
    private static final int REST_STAGE_AT_HOME = 2;
    @SuppressWarnings("unused")
    private static final int REST_STAGE_GOING_TO_WORK = 4; // 新增：正在去工作
    private static final int REST_STAGE_WAKING_UP = 3;

    // 存储正在去工作的NPC数据
    private static final Map<UUID, GoingToWorkData> goingToWorkNPCs = new ConcurrentHashMap<>();

    /**
     * NPC休息数据类
     */
    private static class RestData {
        public final CustomEntity npc;
        @SuppressWarnings("unused")
        public final ServerLevel level;
        public BlockPos homePos;
        public int restStage;
        @SuppressWarnings("unused")
        public long restStartTime;
        public boolean hasArrivedHome;

        public RestData(CustomEntity npc, ServerLevel level) {
            this.npc = npc;
            this.level = level;
            this.restStage = REST_STAGE_IDLE;
            this.restStartTime = System.currentTimeMillis();
            this.hasArrivedHome = false;
        }
    }

    /**
     * NPC去工作数据类
     */
    private static class GoingToWorkData {
        public final CustomEntity npc;
        @SuppressWarnings("unused")
        public final ServerLevel level;
        public final BlockPos workPos;
        public final String job;
        public final WorkStatus previousWorkStatus;
        public long startTime; // 开始去工作的时间（游戏刻）

        public GoingToWorkData(CustomEntity npc, ServerLevel level, BlockPos workPos, String job, WorkStatus previousWorkStatus) {
            this.npc = npc;
            this.level = level;
            this.workPos = workPos;
            this.job = job;
            this.previousWorkStatus = previousWorkStatus;
            this.startTime = level.getDayTime() % 24000L;
        }
    }

    /**
     * 检查是否应该开始休息
     * 在晚上（12000-24000游戏刻）时返回true
     */
    public static boolean shouldStartResting(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        // 傍晚18:00到第二天早上6:00之间应该休息
        return dayTime >= EVENING_START_TIME || dayTime < MORNING_END_TIME;
    }

    /**
     * 检查是否应该结束休息
     * 在早上（0-12000游戏刻）时返回true
     */
    public static boolean shouldStopResting(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        // 早上6:00到晚上18:00之间应该工作
        return dayTime >= MORNING_END_TIME && dayTime < EVENING_START_TIME;
    }

    /**
     * 检查是否应该准备出发去工作（被雇佣的NPC提前出发）
     * 在早上5:00到6:00之间，被雇佣的NPC应该开始去工作
     */
    public static boolean shouldPrepareForWork(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        // 早上5:00到6:00之间准备出发
        return dayTime >= MORNING_PREPARE_TIME && dayTime < EVENING_START_TIME;
    }

    /**
     * 检查是否应该开始休息（带NPC参数版本，仅工业类NPC使用JSON配置）
     * 对于工业类NPC，使用JSON配置的工作结束时间
     * 对于其他类NPC，使用默认的傍晚时间
     */
    public static boolean shouldStartResting(ServerLevel level, CustomEntity npc) {
        if (npc == null) {
            return shouldStartResting(level);
        }
        
        // 检查是否是工业类NPC（通过检查是否能获取到工业建筑配置）
        String job = npc.getJob();
        if (job != null && !job.equals("unemployed")) {
            BlockPos workPos = getWorkplacePosition(npc, level.getServer(), job);
            if (workPos != null) {
                // 尝试从工业建筑配置中获取工作时间
                String buildingFileName = com.xiaoliang.simukraft.utils.IndustrialWorkHandler.getBuildingFileName(level, workPos);
                if (buildingFileName != null) {
                    com.xiaoliang.simukraft.building.IndustrialBuildingConfig config = 
                        com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfig(buildingFileName);
                    if (config != null) {
                        long dayTime = level.getDayTime() % 24000L;
                        int workEndTime = config.getWorkEndTime();
                        int workStartTime = config.getWorkStartTime();
                        // 工作结束时间后应该休息
                        return dayTime >= workEndTime || dayTime < workStartTime;
                    }
                }
            }
        }
        
        // 其他类NPC使用默认时间
        return shouldStartResting(level);
    }

    /**
     * 检查是否应该结束休息（带NPC参数版本，仅工业类NPC使用JSON配置）
     * 对于工业类NPC，使用JSON配置的工作开始时间
     * 对于其他类NPC，使用默认的早上时间
     */
    public static boolean shouldStopResting(ServerLevel level, CustomEntity npc) {
        if (npc == null) {
            return shouldStopResting(level);
        }
        
        // 检查是否是工业类NPC
        String job = npc.getJob();
        if (job != null && !job.equals("unemployed")) {
            BlockPos workPos = getWorkplacePosition(npc, level.getServer(), job);
            if (workPos != null) {
                // 尝试从工业建筑配置中获取工作时间
                String buildingFileName = com.xiaoliang.simukraft.utils.IndustrialWorkHandler.getBuildingFileName(level, workPos);
                if (buildingFileName != null) {
                    com.xiaoliang.simukraft.building.IndustrialBuildingConfig config = 
                        com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfig(buildingFileName);
                    if (config != null) {
                        long dayTime = level.getDayTime() % 24000L;
                        int workStartTime = config.getWorkStartTime();
                        int workEndTime = config.getWorkEndTime();
                        // 在工作时间内应该工作
                        return dayTime >= workStartTime && dayTime < workEndTime;
                    }
                }
            }
        }
        
        // 其他类NPC使用默认时间
        return shouldStopResting(level);
    }

    /**
     * 检查是否应该准备出发去工作（带NPC参数版本，仅工业类NPC使用JSON配置）
     * 对于工业类NPC，在工作开始时间前1000tick准备出发
     * 对于其他类NPC，使用默认的早上准备时间
     */
    public static boolean shouldPrepareForWork(ServerLevel level, CustomEntity npc) {
        if (npc == null) {
            return shouldPrepareForWork(level);
        }
        
        // 检查是否是工业类NPC
        String job = npc.getJob();
        if (job != null && !job.equals("unemployed")) {
            BlockPos workPos = getWorkplacePosition(npc, level.getServer(), job);
            if (workPos != null) {
                // 尝试从工业建筑配置中获取工作时间
                String buildingFileName = com.xiaoliang.simukraft.utils.IndustrialWorkHandler.getBuildingFileName(level, workPos);
                if (buildingFileName != null) {
                    com.xiaoliang.simukraft.building.IndustrialBuildingConfig config = 
                        com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfig(buildingFileName);
                    if (config != null) {
                        long dayTime = level.getDayTime() % 24000L;
                        int workStartTime = config.getWorkStartTime();
                        // 在工作开始时间前1000tick准备出发
                        int prepareTime = workStartTime - 1000;
                        if (prepareTime < 0) prepareTime += 24000;
                        
                        if (workStartTime >= 1000) {
                            // 工作时间在1000tick之后
                            return dayTime >= prepareTime && dayTime < workStartTime;
                        } else {
                            // 工作时间在1000tick之前（跨天）
                            return dayTime >= prepareTime || dayTime < workStartTime;
                        }
                    }
                }
            }
        }
        
        // 其他类NPC使用默认时间
        return shouldPrepareForWork(level);
    }

    /**
     * 判断NPC是否仍在休息工作流中。
     * 只要仍在回家、在家休息或去工作途中，就不允许其他链路直接改成工作中。
     */
    public static boolean isNpcInRestWorkflow(UUID npcUuid) {
        if (npcUuid == null) {
            return false;
        }
        return restingNPCs.containsKey(npcUuid) || goingToWorkNPCs.containsKey(npcUuid);
    }

    /**
     * 开始NPC休息流程
     * 所有NPC（工作中的和空闲的）都会进入休息状态
     */
    public static void startResting(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null) return;

        UUID npcUuid = npc.getUUID();

        // 检查NPC是否已经在休息
        if (restingNPCs.containsKey(npcUuid)) {
            return;
        }

        // 获取NPC的住宅位置
        BlockPos homePos = getNPCHomePosition(npc, level.getServer());
        if (homePos == null) {
            // 如果NPC没有住宅，使用NPC当前位置作为休息位置（原地休息）
            homePos = npc.blockPosition();
            LOGGER.info("[NPCRestHandler] NPC {} 没有住宅，将在当前位置休息: {}", npc.getFullName(), homePos);
        } else {
            LOGGER.info("[NPCRestHandler] NPC {} 找到住宅位置: {}", npc.getFullName(), homePos);
        }

        // 保存NPC休息前的职业，并推导早上应恢复的目标状态。
        WorkStatus currentWorkStatus = npc.getWorkStatus();
        String currentJob = npc.getJob();
        WorkStatus resumeWorkStatus = resolveResumeWorkStatus(npc, level.getServer(), currentJob, currentWorkStatus);
        npcPreviousWorkStatus.put(npcUuid, resumeWorkStatus);
        npcPreviousJob.put(npcUuid, currentJob);

        // 保存建筑师的建造任务 - 只保存到JSON，不再保存到内存Map
        // 优先使用JSON数据恢复，避免两个系统冲突
        if ("builder".equals(currentJob) && npc.getConstructionTask() != null) {
            // 保存到持久化存储（JSON文件）
            if (level.getServer() != null) {
                BuilderDailyWorkHandler.saveConstructionTask(level.getServer(), npc);
                LOGGER.info("[NPCRestHandler] NPC {} 保存建造任务到JSON: {}",
                    npc.getFullName(), npc.getConstructionTask().getBuildingName());
            }
        }

        // 保存规划师的任务ID
        if ("planner".equals(currentJob)) {
            com.xiaoliang.simukraft.planning.PlanningTask activeTask = com.xiaoliang.simukraft.planning.PlanningTaskManager.get(level).getActiveTaskByNpc(npcUuid);
            if (activeTask != null) {
                npcPreviousPlanningTaskId.put(npcUuid, activeTask.getTaskId());
                LOGGER.info("[NPCRestHandler] NPC {} 保存规划任务: {}", npc.getFullName(), activeTask.getTaskId());
            }
        }

        // 创建休息数据
        RestData restData = new RestData(npc, level);
        restData.homePos = homePos;
        restData.restStage = REST_STAGE_GOING_HOME;

        restingNPCs.put(npcUuid, restData);
        npcSubStates.put(npcUuid, WorkSubState.RESTING);
        npcHomePositions.put(npcUuid, homePos);

        // 设置NPC的子状态为休息中
        npc.setWorkSubState(WorkSubState.RESTING);
        npc.setStatusLabel("gui.npc.status.going_home");

        // 停止NPC的所有活动
        stopAllNPCActivities(npc);

        // 发送休息提示消息
        sendRestMessage(npc, level.getServer());

        LOGGER.info("NPC {} 开始休息流程，准备返回住宅: {}，原状态: {}，原职业: {}", 
            npc.getFullName(), homePos, resumeWorkStatus, currentJob);

        // 开始寻路回家
        startPathfindingToHome(npc, homePos);
    }

    /**
     * 停止NPC的所有活动，但允许移动（用于休息回家）
     */
    private static void stopAllNPCActivities(CustomEntity npc) {
        if (npc == null) return;

        // 设置isWorking为false，允许NPC移动
        npc.setWorking(false);

        // 恢复AI
        npc.setNoAi(false);

        // 停止导航
        npc.getNavigation().stop();

        // 清零速度
        npc.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO));

        // 清除目标
        npc.setTarget(null);
        npc.setLastHurtByMob(null);
        // 停止建造任务
        if (npc.getConstructionTask() != null) {
            npc.setConstructionTask(null);
        }

        LOGGER.debug("NPC {} 的所有活动已停止，已设置为可移动状态", npc.getFullName());
    }

    private static WorkStatus resolveResumeWorkStatus(CustomEntity npc, MinecraftServer server, String currentJob, WorkStatus currentWorkStatus) {
        if (npc == null || server == null || currentJob == null || currentJob.isBlank() || "unemployed".equals(currentJob)) {
            return currentWorkStatus;
        }

        if ("builder".equals(currentJob)) {
            boolean hasBuildBox = getBuilderWorkplace(server, npc.getUUID()) != null;
            boolean hasTask = npc.getConstructionTask() != null
                    && !npc.getConstructionTask().isCompleted()
                    && npc.getConstructionTask().hasNextBlock();
            return hasBuildBox && hasTask ? WorkStatus.WORKING : currentWorkStatus;
        }

        return getWorkplacePosition(npc, server, currentJob) != null ? WorkStatus.WORKING : currentWorkStatus;
    }

    /**
     * 结束NPC休息流程
     */
    public static void stopResting(CustomEntity npc, ServerLevel level) {
        if (npc == null) return;

        UUID npcUuid = npc.getUUID();
        RestData restData = restingNPCs.get(npcUuid);

        if (restData == null) {
            return;
        }

        // 获取NPC休息前的工作状态和职业
        WorkStatus previousWorkStatus = npcPreviousWorkStatus.getOrDefault(npcUuid, WorkStatus.IDLE);
        String previousJob = npcPreviousJob.getOrDefault(npcUuid, "unemployed");

        // 恢复NPC的正常活动（允许移动）
        npc.setNoAi(false);
        npc.setWorking(false); // 先设置为false，允许移动
        npc.getNavigation().stop();

        // 如果被雇佣的NPC，先寻路到工作岗位
        if (previousWorkStatus == WorkStatus.WORKING && !"unemployed".equals(previousJob)) {
            BlockPos workPos = getWorkplacePosition(npc, level.getServer(), previousJob);

            if (workPos != null) {
                // 检查距离
                double distance = npc.position().distanceTo(new net.minecraft.world.phys.Vec3(workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5));
                boolean preferImmediateTeleport = com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(previousJob)
                        || getIndustrialWorkplace(level.getServer(), npcUuid, previousJob) != null;

                if (distance > 10.0 || (preferImmediateTeleport && distance > 3.0)) {
                    // 距离太远，直接传送
                    LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，传送回工作岗位: {}",
                        npc.getFullName(), distance, workPos);
                    spawnTeleportParticles(npc);
                    npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);
                    spawnTeleportParticles(npc);

                    // 传送后直接恢复工作状态
                    restoreWorkStatus(npc, npcUuid, previousWorkStatus, previousJob, level);
                } else if (distance > 3.0) {
                    // 距离适中，需要寻路过去
                    LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，开始寻路去工作: {}",
                        npc.getFullName(), distance, workPos);

                    // 设置子状态为正在去工作
                    npc.setWorkSubState(WorkSubState.RESTING);
                    npcSubStates.put(npcUuid, WorkSubState.RESTING);

                    // 清除之前的状态标签（如"gui.npc.status.at_home"），设置新的状态标签
                    npc.setStatusLabel("gui.npc.status.going_to_work");

                    // 创建去工作数据
                    GoingToWorkData workData = new GoingToWorkData(npc, level, workPos, previousJob, previousWorkStatus);
                    goingToWorkNPCs.put(npcUuid, workData);

                    // 开始寻路到工作位置
                    startPathfindingToWork(npc, workPos);

                    // 发送起床提示消息
                    sendWakeUpMessage(npc, level.getServer());

                    // 清理休息数据（但保留工作状态相关数据，等到达后再清理）
                    restingNPCs.remove(npcUuid);
                    npcPathfindingStatus.remove(npcUuid);
                    npcSubStates.remove(npcUuid); // 关键修复：清理子状态数据

                    LOGGER.info("NPC {} 结束休息，正在前往工作岗位: {}，职业: {}",
                        npc.getFullName(), workPos, previousJob);
                    return; // 不立即恢复工作状态，等到达后再恢复
                } else {
                    // 已经在工作位置附近，直接恢复工作状态
                    LOGGER.info("[NPCRestHandler] NPC {} 已经在工作岗位附近({}格)，直接恢复工作",
                        npc.getFullName(), distance);
                    restoreWorkStatus(npc, npcUuid, previousWorkStatus, previousJob, level);
                }
            } else {
                LOGGER.warn("[NPCRestHandler] NPC {} 无法找到工作岗位位置", npc.getFullName());
                if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(previousJob)) {
                    LOGGER.warn("[NPCRestHandler] NPC {} 商业岗位恢复时暂未解析到工作点，先保留职业与工作状态，避免交互退化",
                        npc.getFullName());
                    restoreWorkStatus(npc, npcUuid, previousWorkStatus, previousJob, level);
                } else {
                    // 没有工作位置，设置为空闲
                    npc.setWorkStatus(WorkStatus.IDLE);
                    npc.setWorkSubState(WorkSubState.NONE);
                    npcSubStates.put(npcUuid, WorkSubState.NONE);
                    npc.setWorking(false);
                    // 清除状态标签
                    npc.setStatusLabel(null);
                }
            }
        } else {
            // 没有被雇佣，设置为空闲
            npc.setWorkStatus(WorkStatus.IDLE);
            npc.setWorkSubState(WorkSubState.NONE);
            npcSubStates.put(npcUuid, WorkSubState.NONE);
            npc.setWorking(false);
            // 清除状态标签
            npc.setStatusLabel(null);
        }

        // 发送起床提示消息
        sendWakeUpMessage(npc, level.getServer());

        LOGGER.info("NPC {} 结束休息，恢复原状态: {}，原职业: {}",
            npc.getFullName(), previousWorkStatus, previousJob);

        // 清理休息数据
        restingNPCs.remove(npcUuid);
        npcPathfindingStatus.remove(npcUuid);
        npcPreviousWorkStatus.remove(npcUuid);
        npcPreviousJob.remove(npcUuid);
        // 不再使用npcPreviousConstructionTask，建造任务统一从JSON恢复
    }

    /**
     * 恢复NPC的工作状态（带level参数的版本）
     */
    private static void restoreWorkStatus(CustomEntity npc, UUID npcUuid, WorkStatus previousWorkStatus, String previousJob, ServerLevel level) {
        // 先恢复职业，再恢复工作状态，避免商业/NPC交互判定在过夜后读到旧职业
        if (previousJob != null && !previousJob.isBlank()) {
            npc.setJob(previousJob);
        }

        // 恢复NPC的工作状态
        npc.setWorkStatus(previousWorkStatus);

        // 恢复NPC的工作子状态
        if (previousWorkStatus == WorkStatus.WORKING) {
            npc.setWorkSubState(WorkSubState.WORKING);
            npcSubStates.put(npcUuid, WorkSubState.WORKING);
            // 恢复isWorking为true，让NPC可以正常工作
            npc.setWorking(true);
            restoreJobSpecificWorkState(npc, npcUuid, previousJob, level);
        } else {
            npc.setWorkSubState(WorkSubState.NONE);
            npcSubStates.put(npcUuid, WorkSubState.NONE);
            npc.setWorking(false);
        }

        // 清除状态标签
        npc.setStatusLabel(null);

        // 恢复建筑师的建造任务 - 立即从JSON恢复，而不是依赖BuilderDailyWorkHandler
        // 修复：如果建筑师在天亮后才结束休息，startDailyWork已经执行过，需要立即恢复任务
        if ("builder".equals(previousJob) && level != null && level.getServer() != null) {
            // 清除可能存在的旧任务引用
            npc.setConstructionTask(null);
            // 立即从JSON恢复建造任务
            BlockPos buildBoxPos = getBuilderWorkplace(level.getServer(), npcUuid);
            if (buildBoxPos != null) {
                restoreBuilderTaskFromJson(level.getServer(), npc, buildBoxPos);
            }
        }

        // 恢复规划师的任务
        if ("planner".equals(previousJob)) {
            UUID savedTaskId = npcPreviousPlanningTaskId.get(npcUuid);
            if (savedTaskId != null) {
                LOGGER.info("[NPCRestHandler] NPC {} 恢复规划任务: {}", npc.getFullName(), savedTaskId);
                // PlanningTaskManager已经保存了任务状态，NPC恢复工作后会自动继续
            } else {
                LOGGER.warn("[NPCRestHandler] NPC {} 没有找到保存的规划任务", npc.getFullName());
            }
        }

        LOGGER.info("[NPCRestHandler] NPC {} 已恢复工作状态: {}，职业: {}",
            npc.getFullName(), previousWorkStatus, previousJob);

        // 清理数据 - 确保完全清理所有休息状态数据
        npcPreviousWorkStatus.remove(npcUuid);
        npcPreviousJob.remove(npcUuid);
        // 不再使用npcPreviousConstructionTask，建造任务统一从JSON恢复
        npcPreviousPlanningTaskId.remove(npcUuid);

        // 关键修复：清理休息状态数据，确保标签能正确更新
        restingNPCs.remove(npcUuid);
        npcSubStates.remove(npcUuid);
        npcPathfindingStatus.remove(npcUuid);

        LOGGER.info("[NPCRestHandler] NPC {} 休息状态数据已完全清理，标签将恢复正常显示", npc.getFullName());
    }

    private static void restoreJobSpecificWorkState(CustomEntity npc, UUID npcUuid, String previousJob, ServerLevel level) {
        if (npc == null || previousJob == null || previousJob.isBlank() || level == null || level.getServer() == null) {
            return;
        }

        BlockPos workPos = getWorkplacePosition(npc, level.getServer(), previousJob);
        if (workPos == null) {
            return;
        }

        if ("builder".equals(previousJob)) {
            // 建筑师使用单独的任务恢复逻辑，这里不重复处理。
            return;
        }

        if ("farmer".equals(previousJob)) {
            FarmerDailyWorkHandler.restoreFarmerWorkState(npc, workPos, level);
            return;
        }

        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(previousJob)) {
            String buildingFileName = CommercialWorkHandler.getBuildingFileName(level, workPos);
            if (buildingFileName != null) {
                CommercialWorkHandler.restoreNpcAfterRest(npc, level, workPos, buildingFileName);
            }
            return;
        }

        String industrialBuildingFileName = IndustrialWorkHandler.getBuildingFileName(level, workPos);
        if (industrialBuildingFileName != null) {
            IndustrialWorkHandler.restoreNpcAfterRest(npc, level, workPos, industrialBuildingFileName);
            return;
        }

        if ("warehouse_manager".equals(previousJob)) {
            npc.setWorking(true);
        }
    }

    /**
     * 传送NPC到工作岗位
     */
    @SuppressWarnings("unused")
    private static void teleportToWorkplace(CustomEntity npc, ServerLevel level, String job) {
        if (npc == null || level == null || job == null) return;

        // 根据职业获取工作位置
        BlockPos workPos = getWorkplacePosition(npc, level.getServer(), job);

        if (workPos != null) {
            // 检查距离，如果超过10格则传送
            double distance = npc.position().distanceTo(new net.minecraft.world.phys.Vec3(workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5));

            if (distance > 10.0) {
                LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，传送回工作岗位: {}", 
                    npc.getFullName(), distance, workPos);

                // 生成传送粒子效果
                spawnTeleportParticles(npc);

                // 传送NPC到工作岗位
                npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);

                // 在目的地生成传送粒子效果
                spawnTeleportParticles(npc);
            } else {
                LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位只有{}格，不需要传送", 
                    npc.getFullName(), distance);
            }
        } else {
            LOGGER.warn("[NPCRestHandler] NPC {} 无法找到工作岗位位置", npc.getFullName());
        }
    }

    /**
     * 获取NPC的工作岗位位置
     * 支持动态职业类型，不硬编码特定职业
     */
    private static BlockPos getWorkplacePosition(CustomEntity npc, MinecraftServer server, String job) {
        if (npc == null || server == null || job == null) return null;

        String npcName = npc.getFullName();
        if (npcName == null || npcName.isEmpty()) return null;

        UUID npcUuid = npc.getUUID();

        // 首先尝试从 IndustrialHiredData 查找（支持所有工业建筑职业）
        BlockPos industrialPos = getIndustrialWorkplace(server, npcUuid, job);
        if (industrialPos != null) {
            return industrialPos;
        }

        // 根据职业类型获取对应的工作位置（特殊职业）
        // 使用CommercialBuildingManager统一检查商业建筑职业
        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job)) {
            return getCommercialWorkplace(server, npcUuid);
        }
        
        return switch (job) {
            case "builder" -> getBuilderWorkplace(server, npcUuid);
            case "planner" -> getPlannerWorkplace(server, npcUuid);
            case "farmer" -> getFarmerWorkplace(server, npcUuid);
            case "warehouse_manager" -> getWarehouseManagerWorkplace(server, npcUuid);
            default -> null;
        };
    }

    /**
     * 从 IndustrialHiredData 获取工作位置
     * 支持任意职业类型的工业建筑
     */
    private static BlockPos getIndustrialWorkplace(MinecraftServer server, UUID npcUuid, String jobType) {
        Map<BlockPos, com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo> hiredEmployees = 
            com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (Map.Entry<BlockPos, com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo> entry : hiredEmployees.entrySet()) {
            com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid) && jobType.equals(hireInfo.getJobType())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取建筑师的工作位置（建筑盒位置）
     */
    private static BlockPos getBuilderWorkplace(MinecraftServer server, UUID npcUuid) {
        Map<BlockPos, UUID> hiredBuilders = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server);
        for (Map.Entry<BlockPos, UUID> entry : hiredBuilders.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取规划师的工作位置（建筑盒位置）
     */
    private static BlockPos getPlannerWorkplace(MinecraftServer server, UUID npcUuid) {
        Map<BlockPos, UUID> hiredPlanners = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(server);
        for (Map.Entry<BlockPos, UUID> entry : hiredPlanners.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取农民的工作位置
     */
    private static BlockPos getFarmerWorkplace(MinecraftServer server, UUID npcUuid) {
        // 先加载数据
        com.xiaoliang.simukraft.world.FarmlandHiredData.loadHiredFarmers(server);
        // 获取雇佣数据
        Map<BlockPos, UUID> hiredFarmers = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        for (Map.Entry<BlockPos, UUID> entry : hiredFarmers.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取商业建筑员工的工作位置
     */
    private static BlockPos getCommercialWorkplace(MinecraftServer server, UUID npcUuid) {
        var employment = com.xiaoliang.simukraft.employment.service.EmploymentServices.get(server)
                .findByNpc(npcUuid)
                .filter(assignment -> assignment.workBlockType() == com.xiaoliang.simukraft.employment.domain.WorkBlockType.COMMERCIAL_CONTROL_BOX)
                .map(com.xiaoliang.simukraft.employment.domain.EmploymentAssignment::workplacePos)
                .orElse(null);
        if (employment != null) {
            return employment;
        }

        Map<BlockPos, com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo> hiredEmployees = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        for (Map.Entry<BlockPos, com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo> entry : hiredEmployees.entrySet()) {
            if (entry.getValue().getNpcUuid().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取仓库管理员的工作位置
     */
    private static BlockPos getWarehouseManagerWorkplace(MinecraftServer server, UUID npcUuid) {
        Map<BlockPos, UUID> hiredManagers = com.xiaoliang.simukraft.world.LogisticsHiredData.getServerBoxHiredNpcs(server);
        for (Map.Entry<BlockPos, UUID> entry : hiredManagers.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 从JSON恢复建筑师的建造任务
     * 修复：建筑师在天亮后才结束休息时，需要立即恢复任务
     */
    private static void restoreBuilderTaskFromJson(MinecraftServer server, CustomEntity npc, BlockPos buildBoxPos) {
        if (server == null || npc == null || buildBoxPos == null) return;

        // 如果NPC已经有建造任务，不需要恢复
        if (npc.getConstructionTask() != null) {
            return;
        }

        // 从持久化存储中加载建造任务
        com.xiaoliang.simukraft.world.ConstructionTaskData.TaskInfo taskInfo =
            com.xiaoliang.simukraft.world.ConstructionTaskData.loadTask(server, npc.getUUID());
        if (taskInfo == null) {
            LOGGER.info("[NPCRestHandler] NPC {} 没有保存的建造任务", npc.getFullName());
            return;
        }

        try {
            // 验证taskInfo的必需字段
            if (taskInfo.buildingName == null || taskInfo.category == null || 
                taskInfo.startPos == null || taskInfo.buildBoxPos == null || 
                taskInfo.facing == null || taskInfo.displayName == null) {
                LOGGER.warn("[NPCRestHandler] 建造任务数据不完整，无法恢复 - NPC: {}",
                    npc.getFullName());
                return;
            }

            // 验证建筑盒是否还存在
            ServerLevel level = server.overworld();
            net.minecraft.world.level.block.state.BlockState buildBoxState = level.getBlockState(taskInfo.buildBoxPos);
            if (buildBoxState.isAir()) {
                LOGGER.warn("[NPCRestHandler] 建筑盒已不存在，移除建造任务 - NPC: {}",
                    npc.getFullName());
                com.xiaoliang.simukraft.world.ConstructionTaskData.removeTask(server, npc.getUUID());
                return;
            }

            // 重新创建建造任务
            com.xiaoliang.simukraft.building.ConstructionTask task = new com.xiaoliang.simukraft.building.ConstructionTask(
                taskInfo.buildingName,
                taskInfo.category,
                taskInfo.startPos,
                taskInfo.buildBoxPos,
                taskInfo.facing,
                taskInfo.displayName,
                taskInfo.cost,
                level
            );

            // 恢复建造进度
            task.setCurrentBlockIndex(taskInfo.currentBlockIndex);

            // 设置NPC的建造任务
            npc.setConstructionTask(task);

            LOGGER.info("[NPCRestHandler] 成功恢复建造任务 - NPC: {}, 建筑: {}, 进度: {}/{}",
                npc.getFullName(),
                taskInfo.displayName,
                taskInfo.currentBlockIndex,
                task.getTotalBlocks());

        } catch (Exception e) {
            LOGGER.error("[NPCRestHandler] 恢复建造任务失败 - NPC: {}",
                npc.getFullName(), e);
        }
    }

    /**
     * 更新所有NPC的休息状态（同步版本，在服务器tick中调用）
     */
    public static void updateRestStatus(ServerLevel level) {
        if (level == null) return;
        updateRestStatusInternal(level, NPCTaskScheduler.getNPCsInLevel(level));
    }

    /**
     * 异步更新NPC休息状态
     * 兼容旧调用点；涉及实体导航、传送和世界访问，必须切回主线程执行
     */
    public static void updateRestStatusAsync(ServerLevel level, List<CustomEntity> npcs) {
        if (level == null || npcs == null || npcs.isEmpty()) return;

        if (!level.getServer().isSameThread()) {
            enqueueMainThreadLevelTask(level, () -> updateRestStatusInternal(level, npcs),
                    "RestStatusMainThread-" + level.dimension().location());
            return;
        }

        updateRestStatusInternal(level, npcs);
    }

    private static void updateRestStatusInternal(ServerLevel level, List<CustomEntity> npcs) {
        if (level == null || npcs == null || npcs.isEmpty()) return;

        for (CustomEntity npc : npcs) {
            if (npc == null || !npc.isAlive()) continue;

            UUID npcUuid = npc.getUUID();
            RestData restData = restingNPCs.get(npcUuid);

            if (shouldStartResting(level, npc)) {
                // 应该开始休息 - 所有NPC都进入休息状态（优先级最高）

                // 首先检查是否正在去工作，如果是则取消
                GoingToWorkData workData = goingToWorkNPCs.get(npcUuid);
                if (workData != null) {
                    // 正在去工作的路上，但到休息时间了，取消去工作，改为回家休息
                    LOGGER.info("[NPCRestHandler] NPC {} 正在去工作，但到休息时间了，优先回家休息", npc.getFullName());
                    goingToWorkNPCs.remove(npcUuid);
                    enqueueMainThreadLevelTask(level, () -> npc.getNavigation().stop(), "StopNavigation-" + npcUuid);
                }

                if (restData == null && npc.getWorkStatus() == WorkStatus.WORKING) {
                    LOGGER.info("[NPCRestHandler] NPC {} 正在工作，但到休息时间了，停止工作并回家", npc.getFullName());
                    // 保存当前工作状态以便明天恢复
                    npcPreviousWorkStatus.put(npcUuid, WorkStatus.WORKING);
                    npcPreviousJob.put(npcUuid, npc.getJob());

                    // 如果是建筑师，保存建造任务到JSON而不是内存Map
                    if ("builder".equals(npc.getJob()) && npc.getConstructionTask() != null) {
                        if (npc.getServer() != null) {
                            BuilderDailyWorkHandler.saveConstructionTask(npc.getServer(), npc);
                            LOGGER.info("[NPCRestHandler] NPC {} 保存建造任务到JSON: {}",
                                npc.getFullName(), npc.getConstructionTask().getBuildingName());
                        }
                    }

                    // 在主线程中执行NPC状态修改
                    final UUID finalNpcUuid = npcUuid;
                    enqueueMainThreadLevelTask(level, () -> {
                        CustomEntity targetNpc = npc;
                        if (targetNpc != null && targetNpc.isAlive()) {
                            targetNpc.setWorking(false);
                            targetNpc.setConstructionTask(null);
                        }
                    }, "StopWork-" + finalNpcUuid);
                }

                if (restData == null) {
                    // NPC还没有开始休息，开始休息流程
                    LOGGER.info("[NPCRestHandler] 尝试让NPC {} 开始休息", npc.getFullName());
                    enqueueMainThreadLevelTask(level, () -> startResting(npc, level), "StartResting-" + npcUuid);
                } else {
                    // NPC已经在休息中，更新休息状态
                    updateRestingNPC(npc, restData, level);
                }
            } else if (shouldStopResting(level, npc)) {
                // 应该结束休息（工作开始时间）
                if (restData != null) {
                    enqueueMainThreadLevelTask(level, () -> stopResting(npc, level), "StopResting-" + npcUuid);
                }

                // 更新正在去工作的NPC（检查是否超时需要传送）
                GoingToWorkData workData = goingToWorkNPCs.get(npcUuid);
                if (workData != null) {
                    updateGoingToWorkStatus(npc, workData, level);
                }
            } else if (shouldPrepareForWork(level, npc)) {
                // 工作开始前，被雇佣的NPC提前出发去工作
                if (restData != null && restData.restStage == REST_STAGE_AT_HOME) {
                    // NPC已经在家休息，准备出发去工作
                    final RestData finalRestData = restData;
                    enqueueMainThreadLevelTask(level, () -> prepareNPCForWork(npc, level, finalRestData),
                            "PrepareForWork-" + npcUuid);
                }

                // 更新正在去工作的NPC
                GoingToWorkData workData = goingToWorkNPCs.get(npcUuid);
                if (workData != null) {
                    updateGoingToWorkStatus(npc, workData, level);
                }
            }
        }
    }

    private static void enqueueMainThreadLevelTask(ServerLevel level, Runnable task, String taskName) {
        if (level != null && level.getServer() != null && level.getServer().isSameThread()) {
            task.run();
            return;
        }
        NPCTaskScheduler.enqueueMainThreadTask(task, taskName);
    }

    /**
     * 更新正在休息的NPC状态
     */
    private static void updateRestingNPC(CustomEntity npc, RestData restData, ServerLevel level) {
        if (npc == null || restData == null) return;

        switch (restData.restStage) {
            case REST_STAGE_GOING_HOME:
                // 正在回家路上
                updateGoingHomeStatus(npc, restData, level);
                break;
            case REST_STAGE_AT_HOME:
                // 已经到家，保持静止
                updateAtHomeStatus(npc, restData);
                break;
            case REST_STAGE_WAKING_UP:
                // 正在醒来
                break;
            default:
                break;
        }
    }

    /**
     * 更新回家状态
     */
    private static void updateGoingHomeStatus(CustomEntity npc, RestData restData, ServerLevel level) {
        if (restData.hasArrivedHome) {
            restData.restStage = REST_STAGE_AT_HOME;
            return;
        }

        // 确保NPC可以移动（每次更新都检查）
        if (npc.isWorking()) {
            LOGGER.info("[NPCRestHandler] NPC {} isWorking=true，设置为false以允许移动", npc.getFullName());
            npc.setWorking(false);
            npc.setNoAi(false);
        }

        // 检查是否已到达住宅
        BlockPos currentPos = npc.blockPosition();
        BlockPos homePos = restData.homePos;

        if (homePos == null) return;

        double distance = Math.sqrt(
            Math.pow(currentPos.getX() - homePos.getX(), 2) +
            Math.pow(currentPos.getZ() - homePos.getZ(), 2)
        );

        // 如果距离超过50格，直接传送回家并添加粒子效果
        if (distance > 50.0) {
            LOGGER.info("[NPCRestHandler] NPC {} 距离家{}格，超过50格，直接传送回家", npc.getFullName(), distance);
            teleportNPCHomeWithEffects(npc, homePos);
            restData.hasArrivedHome = true;
            restData.restStage = REST_STAGE_AT_HOME;
            return;
        }

        // 检查是否超过强制传送时间（19:00还没到家则传送）
        long dayTime = level.getDayTime() % 24000L;
        if (dayTime >= EVENING_FORCE_TELEPORT_TIME) {
            LOGGER.info("[NPCRestHandler] NPC {} 超过19:00仍未到家（当前时间：{}），强制传送回家", 
                npc.getFullName(), dayTime);
            teleportNPCHomeWithEffects(npc, homePos);
            restData.hasArrivedHome = true;
            restData.restStage = REST_STAGE_AT_HOME;
            return;
        }

        // 如果距离小于3格，认为已到达
        if (distance < 3.0) {
            restData.hasArrivedHome = true;
            restData.restStage = REST_STAGE_AT_HOME;

            // 停止移动
            npc.getNavigation().stop();
            npc.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO));

            // 更新状态标签
            npc.setStatusLabel("gui.npc.status.at_home");

            LOGGER.info("NPC {} 已到达住宅，开始休息", npc.getFullName());
        } else {
            // 检查是否需要重新寻路
            Boolean isPathfinding = npcPathfindingStatus.get(npc.getUUID());
            if (isPathfinding == null || !isPathfinding) {
                // 如果距离很近（小于10格）但寻路失败，直接传送回家
                if (distance < 10.0) {
                    LOGGER.info("[NPCRestHandler] NPC {} 距离家{}格（小于10格）但寻路失败，直接传送回家", 
                        npc.getFullName(), distance);
                    teleportNPCHomeWithEffects(npc, homePos);
                    restData.hasArrivedHome = true;
                    restData.restStage = REST_STAGE_AT_HOME;
                    npc.getNavigation().stop();
                    npc.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO));
                    npc.setStatusLabel("gui.npc.status.at_home");
                } else {
                    // 重新启动寻路
                    LOGGER.info("[NPCRestHandler] NPC {} 重新启动寻路回家，当前距离: {}", npc.getFullName(), distance);
                    startPathfindingToHome(npc, homePos);
                }
            }
        }
    }

    /**
     * 传送NPC回家并添加粒子效果
     */
    private static void teleportNPCHomeWithEffects(CustomEntity npc, BlockPos homePos) {
        if (npc == null || homePos == null) return;

        // 在原地生成传送粒子效果
        spawnTeleportParticles(npc);

        // 传送NPC到家中
        npc.teleportTo(
            homePos.getX() + 0.5,
            homePos.getY() + 1,
            homePos.getZ() + 0.5
        );

        // 在目的地生成传送粒子效果
        spawnTeleportParticles(npc);

        LOGGER.info("[NPCRestHandler] NPC {} 已传送回家并添加粒子效果", npc.getFullName());
    }

    /**
     * 生成传送粒子效果
     */
    private static void spawnTeleportParticles(CustomEntity npc) {
        if (npc.level().isClientSide) return;

        net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) npc.level();
        net.minecraft.world.phys.Vec3 pos = npc.position();
        net.minecraft.util.RandomSource random = npc.getRandom();

        // 生成末影珍珠传送粒子效果
        for (int i = 0; i < 30; i++) {
            double x = pos.x + (random.nextDouble() - 0.5);
            double y = pos.y + random.nextDouble() * 2.0;
            double z = pos.z + (random.nextDouble() - 0.5);

            serverLevel.sendParticles(
                Objects.requireNonNull(net.minecraft.core.particles.ParticleTypes.PORTAL),
                x, y, z,
                1,
                0, 0, 0,
                0.5
            );
        }

        // 生成一些烟雾粒子
        for (int i = 0; i < 10; i++) {
            double x = pos.x + (random.nextDouble() - 0.5) * 0.5;
            double y = pos.y + random.nextDouble();
            double z = pos.z + (random.nextDouble() - 0.5) * 0.5;

            serverLevel.sendParticles(
                Objects.requireNonNull(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE),
                x, y, z,
                1,
                0, 0.1, 0,
                0.02
            );
        }
    }

    /**
     * 更新在家状态
     */
    private static void updateAtHomeStatus(CustomEntity npc, RestData restData) {
        // 确保NPC保持静止，禁用AI防止被攻击时击退或移动
        npc.setNoAi(true);
        npc.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO));

        // 偶尔改变朝向，让NPC看起来更自然
        if (npc.tickCount % 200 == 0) {
            npc.setYRot(npc.getYRot() + 45);
        }
    }

    /**
     * 准备NPC去工作（早上5点提前出发）
     */
    private static void prepareNPCForWork(CustomEntity npc, ServerLevel level, RestData restData) {
        if (npc == null || level == null || restData == null) return;

        UUID npcUuid = npc.getUUID();

        // 获取NPC的工作状态和职业
        WorkStatus previousWorkStatus = npcPreviousWorkStatus.getOrDefault(npcUuid, WorkStatus.IDLE);
        String previousJob = npcPreviousJob.getOrDefault(npcUuid, "unemployed");

        // 只有被雇佣的NPC才需要提前出发
        if (previousWorkStatus != WorkStatus.WORKING || "unemployed".equals(previousJob)) {
            return;
        }

        // 获取工作位置
        BlockPos workPos = getWorkplacePosition(npc, level.getServer(), previousJob);
        if (workPos == null) {
            LOGGER.warn("[NPCRestHandler] NPC {} 无法找到工作岗位位置，无法提前出发", npc.getFullName());
            return;
        }

        // 检查是否已经在去工作的路上
        if (goingToWorkNPCs.containsKey(npcUuid)) {
            return;
        }

        LOGGER.info("[NPCRestHandler] NPC {} 早上5点了，提前出发去工作岗位: {}，职业: {}",
            npc.getFullName(), workPos, previousJob);

        // 从休息数据中移除（不再处于休息状态）
        restingNPCs.remove(npcUuid);

        // 允许NPC移动
        npc.setNoAi(false);
        npc.setWorking(false);
        npc.getNavigation().stop();

        // 检查距离
        double distance = npc.position().distanceTo(new net.minecraft.world.phys.Vec3(workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5));

        if (distance > 10.0) {
            // 距离太远，直接传送
            LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，直接传送", npc.getFullName(), distance);
            spawnTeleportParticles(npc);
            npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);
            spawnTeleportParticles(npc);

            // 直接恢复工作状态
            restoreWorkStatus(npc, npcUuid, previousWorkStatus, previousJob, level);
        } else {
            // 距离适中，开始寻路
            LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，开始寻路去工作", npc.getFullName(), distance);

            // 设置子状态为正在去工作
            npc.setWorkSubState(WorkSubState.RESTING);
            npcSubStates.put(npcUuid, WorkSubState.RESTING);
            npc.setStatusLabel("gui.npc.status.going_to_work");

            // 创建去工作数据
            GoingToWorkData workData = new GoingToWorkData(npc, level, workPos, previousJob, previousWorkStatus);
            goingToWorkNPCs.put(npcUuid, workData);

            // 开始寻路到工作位置
            startPathfindingToWork(npc, workPos);

            // 使用开拓助手发送消息到城市群组
            Component npcName = npc.getCustomName() != null
                    ? npc.getCustomName()
                    : Component.literal(Objects.requireNonNull(npc.getFullName()));
            CityMessageUtils.sendToCityGroup(level.getServer(), npc.getCityId(),
                    Component.translatable("message.simukraft.npc.woke_up", npcName));
        }

        // 清理休息相关数据
        npcPathfindingStatus.remove(npcUuid);
    }

    /**
     * 更新去工作状态的NPC
     */
    private static void updateGoingToWorkStatus(CustomEntity npc, GoingToWorkData workData, ServerLevel level) {
        if (npc == null || workData == null || level == null) return;

        UUID npcUuid = npc.getUUID();
        BlockPos currentPos = npc.blockPosition();
        BlockPos workPos = workData.workPos;
        long currentDayTime = level.getDayTime() % 24000L;

        // 确保NPC可以移动
        if (npc.isWorking()) {
            npc.setWorking(false);
            npc.setNoAi(false);
        }

        // 确保状态标签正确显示为"gui.npc.status.going_to_work"
        if (!"gui.npc.status.going_to_work".equals(npc.getStatusLabel())) {
            npc.setStatusLabel("gui.npc.status.going_to_work");
            LOGGER.info("[NPCRestHandler] NPC {} status label updated to: gui.npc.status.going_to_work", npc.getFullName());
        }

        // 计算距离
        double distance = Math.sqrt(
            Math.pow(currentPos.getX() - workPos.getX(), 2) +
            Math.pow(currentPos.getZ() - workPos.getZ(), 2)
        );

        // 计算Y轴距离（高度差）
        double yDistance = Math.abs(currentPos.getY() - workPos.getY());

        // 判断是否已经到达工作位置
        // 所有职业都必须到达工作方块上方（水平距离<2格且高度差<3格）才能开始工作
        boolean hasArrived = distance < 2.0 && yDistance < 3.0;

        if (hasArrived) {
            LOGGER.info("[NPCRestHandler] NPC {} 已到达工作位置: {}，水平距离: {}格，高度差: {}格，恢复工作状态",
                npc.getFullName(), workPos, distance, yDistance);

            // 停止移动
            npc.getNavigation().stop();
            npc.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO));

            // 恢复工作状态
            restoreWorkStatus(npc, npcUuid, workData.previousWorkStatus, workData.job, level);

            // 清理去工作数据
            goingToWorkNPCs.remove(npcUuid);
            return;
        }

        // 检查是否超时（超过上班时间或者走了太久）
        long timeSpent = currentDayTime - workData.startTime;
        if (timeSpent < 0) {
            timeSpent += 24000; // 跨天处理
        }

        // 如果已经到上班时间(6:00)还没到达，或者已经走了超过5分钟，直接传送
        if (currentDayTime >= MORNING_END_TIME && currentDayTime < EVENING_START_TIME && timeSpent > MAX_GOING_TO_WORK_TIME) {
            LOGGER.info("[NPCRestHandler] NPC {} 去工作超时（已花费{}游戏刻），直接传送到工作位置: {}",
                npc.getFullName(), timeSpent, workPos);

            // 停止移动
            npc.getNavigation().stop();

            // 传送
            spawnTeleportParticles(npc);
            npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);
            spawnTeleportParticles(npc);

            // 恢复工作状态
            restoreWorkStatus(npc, npcUuid, workData.previousWorkStatus, workData.job, level);

            // 清理去工作数据
            goingToWorkNPCs.remove(npcUuid);
            return;
        }

        // 检查是否需要重新寻路
        PathNavigation navigation = npc.getNavigation();
        if (!navigation.isInProgress()) {
            // 寻路中断，重新启动
            LOGGER.info("[NPCRestHandler] NPC {} 寻路中断，重新寻路到工作位置，当前距离: {}格",
                npc.getFullName(), distance);
            startPathfindingToWork(npc, workPos);
        }
    }

    /**
     * 开始寻路到工作位置
     */
    private static void startPathfindingToWork(CustomEntity npc, BlockPos workPos) {
        if (npc == null || workPos == null) return;

        try {
            PathNavigation navigation = npc.getNavigation();

            // 设置目标位置（工作方块上方）
            Vec3 targetPos = new Vec3(
                workPos.getX() + 0.5,
                workPos.getY() + 1,
                workPos.getZ() + 0.5
            );

            // 开始寻路，速度提高到1.2让NPC走得更快
            boolean canNavigate = navigation.moveTo(targetPos.x, targetPos.y, targetPos.z, 1.2);

            if (canNavigate) {
                LOGGER.info("[NPCRestHandler] NPC {} 开始寻路到工作位置: {}", npc.getFullName(), workPos);
            } else {
                // 如果寻路失败，直接传送
                LOGGER.warn("[NPCRestHandler] NPC {} 寻路到工作位置失败，直接传送", npc.getFullName());
                npc.teleportTo(targetPos.x, targetPos.y, targetPos.z);
            }
        } catch (Exception e) {
            LOGGER.error("[NPCRestHandler] NPC {} 寻路到工作位置时发生错误", npc.getFullName(), e);
        }
    }

    /**
     * 开始寻路回家
     */
    private static void startPathfindingToHome(CustomEntity npc, BlockPos homePos) {
        if (npc == null || homePos == null) return;

        try {
            PathNavigation navigation = npc.getNavigation();

            // 设置目标位置（住宅门口）
            Vec3 targetPos = new Vec3(
                homePos.getX() + 0.5,
                homePos.getY() + 1,
                homePos.getZ() + 0.5
            );

            // 开始寻路，速度提高到1.2让NPC走得更快
            boolean canNavigate = navigation.moveTo(targetPos.x, targetPos.y, targetPos.z, 1.2);

            if (canNavigate) {
                npcPathfindingStatus.put(npc.getUUID(), true);
                LOGGER.debug("NPC {} 开始寻路回家: {}", npc.getFullName(), homePos);
            } else {
                // 如果寻路失败，使用带粒子效果的传送回家
                LOGGER.warn("NPC {} 寻路失败，使用传送回家", npc.getFullName());
                teleportNPCHomeWithEffects(npc, homePos);
                // 标记为已到家，更新状态
                UUID npcUuid = npc.getUUID();
                RestData restData = restingNPCs.get(npcUuid);
                if (restData != null) {
                    restData.hasArrivedHome = true;
                    restData.restStage = REST_STAGE_AT_HOME;
                }
                npcPathfindingStatus.put(npcUuid, false);
            }
        } catch (Exception e) {
            LOGGER.error("NPC {} 寻路回家时发生错误", npc.getFullName(), e);
        }
    }

    /**
     * 获取NPC的住宅位置
     */
    private static BlockPos getNPCHomePosition(CustomEntity npc, MinecraftServer server) {
        if (npc == null || server == null) return null;

        String npcName = npc.getFullName();
        if (npcName == null || npcName.isEmpty()) return null;

        // 从ResidentManager获取住宅位置
        String positionStr = ResidentManager.getNPCResidencePosition(server, npcName);
        if (positionStr == null || positionStr.isEmpty()) {
            return null;
        }

        // 解析位置字符串 (x, y, z)
        try {
            String[] parts = positionStr.replace("(", "").replace(")", "").split(",");
            if (parts.length == 3) {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                return new BlockPos(x, y, z);
            }
        } catch (Exception e) {
            LOGGER.error("解析NPC住宅位置失败: {}", positionStr, e);
        }

        return null;
    }

    /**
     * 发送休息提示消息，全服广播
     */
    private static void sendRestMessage(CustomEntity npc, MinecraftServer server) {
        // 已禁用休息播报
    }

    /**
     * 发送起床提示消息，全服广播
     */
    private static void sendWakeUpMessage(CustomEntity npc, MinecraftServer server) {
        // 已禁用起床播报
    }

    /**
     * 检查NPC是否正在休息
     */
    public static boolean isResting(UUID npcUuid) {
        return restingNPCs.containsKey(npcUuid);
    }

    /**
     * 检查NPC是否被锁定（正在休息中，不能被雇佣）
     */
    public static boolean isLocked(UUID npcUuid) {
        return restingNPCs.containsKey(npcUuid);
    }

    /**
     * 获取NPC的休息子状态
     */
    public static WorkSubState getNPCSubState(UUID npcUuid) {
        return npcSubStates.getOrDefault(npcUuid, WorkSubState.NONE);
    }

    /**
     * 获取NPC的当前状态标签（用于头顶显示）
     * @return 状态标签翻译键："gui.npc.status.going_home"、"gui.npc.status.at_home"、"gui.npc.status.going_to_work"、null表示没有特殊状态
     */
    public static String getNPCStatusLabel(UUID npcUuid) {
        RestData restData = restingNPCs.get(npcUuid);
        if (restData != null) {
            // 根据当前阶段返回对应的状态标签翻译键
            switch (restData.restStage) {
                case REST_STAGE_GOING_HOME:
                    return "gui.npc.status.going_home";
                case REST_STAGE_AT_HOME:
                    return "gui.npc.status.at_home";
                default:
                    return null;
            }
        }

        // 检查是否正在去工作
        GoingToWorkData workData = goingToWorkNPCs.get(npcUuid);
        if (workData != null) {
            return "gui.npc.status.going_to_work";
        }

        return null;
    }

    /**
     * 获取NPC的休息阶段
     */
    public static int getNPCRestStage(UUID npcUuid) {
        RestData restData = restingNPCs.get(npcUuid);
        return restData != null ? restData.restStage : REST_STAGE_IDLE;
    }

    /**
     * 强制停止所有NPC的休息（用于服务器关闭等情况）
     */
    public static void stopAllResting() {
        for (Map.Entry<UUID, RestData> entry : restingNPCs.entrySet()) {
            RestData restData = entry.getValue();
            if (restData != null && restData.npc != null) {
                restData.npc.setNoAi(false);
                restData.npc.getNavigation().stop();
            }
        }
        restingNPCs.clear();
        npcSubStates.clear();
        npcHomePositions.clear();
        npcPathfindingStatus.clear();
        npcPreviousWorkStatus.clear();
        npcPreviousJob.clear();
        // 不再使用npcPreviousConstructionTask，建造任务统一从JSON恢复

        // 清理去工作的NPC
        for (Map.Entry<UUID, GoingToWorkData> entry : goingToWorkNPCs.entrySet()) {
            GoingToWorkData workData = entry.getValue();
            if (workData != null && workData.npc != null) {
                workData.npc.setNoAi(false);
                workData.npc.getNavigation().stop();
            }
        }
        goingToWorkNPCs.clear();
    }

    /**
     * 服务器启动时加载所有NPC的工作状态
     * 从各个雇佣数据文件中恢复npcPreviousWorkStatus和npcPreviousJob
     */
    public static void onServerStart(MinecraftServer server) {
        if (server == null) return;

        LOGGER.info("[NPCRestHandler] 服务器启动，开始加载NPC工作状态...");

        // 清空现有数据
        npcPreviousWorkStatus.clear();
        npcPreviousJob.clear();

        // 从建筑盒雇佣数据加载
        loadWorkStatusFromHiredData(server, com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server), "builder");

        // 从工业控制箱雇佣数据加载（统一处理牧羊人和屠夫）
        loadWorkStatusFromIndustrialHiredData(server, com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server));

        // 从商业建筑雇佣数据加载（包含肉铺、水果店、面包店等）
        loadWorkStatusFromCommercialHiredData(server, com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server));

        // 从农田盒雇佣数据加载
        com.xiaoliang.simukraft.world.FarmlandHiredData.loadHiredFarmers(server);
        loadWorkStatusFromHiredData(server, com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers(), "farmer");

        LOGGER.info("[NPCRestHandler] NPC工作状态加载完成，共加载 {} 个NPC的工作状态", npcPreviousWorkStatus.size());
    }

    /**
     * 从雇佣数据加载工作状态
     */
    private static void loadWorkStatusFromHiredData(MinecraftServer server, Map<BlockPos, UUID> hiredData, String job) {
        for (Map.Entry<BlockPos, UUID> entry : hiredData.entrySet()) {
            UUID npcUuid = entry.getValue();
            npcPreviousWorkStatus.put(npcUuid, WorkStatus.WORKING);
            npcPreviousJob.put(npcUuid, job);
            LOGGER.debug("[NPCRestHandler] 加载NPC工作状态 - UUID: {}, 职业: {}", npcUuid, job);
        }
    }

    /**
     * 从商业建筑雇佣数据加载工作状态
     */
    private static void loadWorkStatusFromCommercialHiredData(MinecraftServer server, Map<BlockPos, com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo> hiredData) {
        for (Map.Entry<BlockPos, com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo> entry : hiredData.entrySet()) {
            com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null) {
                UUID npcUuid = hireInfo.getNpcUuid();
                String job = hireInfo.getJobType();
                npcPreviousWorkStatus.put(npcUuid, WorkStatus.WORKING);
                npcPreviousJob.put(npcUuid, job);
                LOGGER.debug("[NPCRestHandler] 加载NPC工作状态 - UUID: {}, 职业: {}", npcUuid, job);
            }
        }
    }

    /**
     * 从工业控制箱雇佣数据加载工作状态
     */
    private static void loadWorkStatusFromIndustrialHiredData(MinecraftServer server, Map<BlockPos, com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo> hiredData) {
        for (Map.Entry<BlockPos, com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo> entry : hiredData.entrySet()) {
            com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null) {
                UUID npcUuid = hireInfo.getNpcUuid();
                String job = hireInfo.getJobType();
                npcPreviousWorkStatus.put(npcUuid, WorkStatus.WORKING);
                npcPreviousJob.put(npcUuid, job);
                LOGGER.debug("[NPCRestHandler] 加载NPC工作状态 - UUID: {}, 职业: {}", npcUuid, job);
            }
        }
    }

    /**
     * 获取正在休息的NPC数量
     */
    public static int getRestingNPCCount() {
        return restingNPCs.size();
    }

    /**
     * 获取所有正在休息的NPC的UUID列表
     */
    public static Set<UUID> getRestingNPCUUIDs() {
        return new HashSet<>(restingNPCs.keySet());
    }
}
