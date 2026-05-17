package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.client.preview.SchematicNBTLoader;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC休息处理流
 * 处理NPC的休息状态：所有NPC（工作中的和空闲的）在晚上都会返回家中休息
 */
@SuppressWarnings("null")
public class NPCRestHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    // 休息时间配置（游戏刻）
    private static final int EVENING_START_TIME = 12000; // 傍晚开始时间（约18:00）
    private static final int EVENING_FORCE_TELEPORT_TIME = 13000; // 晚上19:00仍未到家则强制传送
    private static final int MORNING_END_TIME = 0; // 早上结束时间（约6:00）
    private static final int MORNING_PREPARE_TIME = 23000; // 早上准备出发时间（约5:00）
    private static final int MAX_GOING_TO_WORK_TIME = 6000; // 最长去工作时间（游戏刻5分钟）
    private static final int REST_START_SPREAD_TICKS = 600; // 将下班入口摊平到约30秒
    private static final int MAX_REST_STARTS_PER_UPDATE = 2;
    private static final int MAX_REST_STOPS_PER_UPDATE = 8;
    private static final int MAX_HOME_PATH_STARTS_PER_UPDATE = 1;
    private static final int BED_SEARCH_HORIZONTAL_RADIUS = 8;
    private static final int BED_SEARCH_VERTICAL_RADIUS = 4;
    private static final long BED_REPATH_INTERVAL_TICKS = 40L;
    private static final long BED_COOLDOWN_TICKS = 1000L; // simukraft: 上床冷却时间（5秒 = 100 ticks）

    // 存储正在休息的NPC数据
    private static final Map<UUID, RestData> restingNPCs = new ConcurrentHashMap<>();
    // 存储NPC上次上床时间（用于上床冷却）
    private static final Map<UUID, Long> npcLastBedTime = new ConcurrentHashMap<>();
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

    // 存储NPC被玩家唤醒的时间（防止立即重新开始休息）
    private static final Map<UUID, Long> npcManualWakeUpTime = new ConcurrentHashMap<>();

    // 玩家唤醒后的冷却时间（tick）
    private static final long MANUAL_WAKE_UP_COOLDOWN_TICKS = 100L; // 5秒

    // 休息阶段常量
    private static final int REST_STAGE_IDLE = 0;
    private static final int REST_STAGE_GOING_HOME = 1;
    private static final int REST_STAGE_AT_HOME = 2;
    private static final int REST_STAGE_SLEEPING = 3;
    private static final int REST_STAGE_WAKING_UP = 4;
    private static final int REST_STAGE_WAITING_FOR_BED = 6;

    private static final Map<UUID, GoingToWorkData> goingToWorkNPCs = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> pendingHomePathStartTicks = new ConcurrentHashMap<>();
    private static final Map<String, Optional<BlockPos>> homeTeleportNbtPosCache = new ConcurrentHashMap<>();
    private static final Map<String, Optional<BlockPos>> residentialControlBoxNbtPosCache = new ConcurrentHashMap<>();

    /**
     * NPC休息数据类
     */
    private static class RestData {
        public final CustomEntity npc;
        public BlockPos homePos;
        public int restStage;
        public boolean hasArrivedHome;
        public BlockPos bedPos;
        public long nextBedRetryTick;
        public long lastWakeUpTime; // simukraft: 记录上次被唤醒时间（防止立即重新睡觉）

        public RestData(CustomEntity npc, ServerLevel level) {
            this.npc = npc;
            this.restStage = REST_STAGE_IDLE;
            this.hasArrivedHome = false;
            this.bedPos = null;
            this.nextBedRetryTick = Long.MIN_VALUE;
            this.lastWakeUpTime = 0;
        }
    }

    /**
     * NPC去工作数据类
     */
    private static class GoingToWorkData {
        public final CustomEntity npc;
        public final BlockPos workPos;
        public final String job;
        public final WorkStatus previousWorkStatus;
        public long startTime; // 开始去工作的时间（游戏刻）

        public GoingToWorkData(CustomEntity npc, ServerLevel level, BlockPos workPos, String job, WorkStatus previousWorkStatus) {
            this.npc = npc;
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
        return dayTime >= MORNING_PREPARE_TIME || dayTime < MORNING_END_TIME;
    }

    /**
     * 检查是否应该开始休息（带NPC参数版本，支持工业类和商业类NPC）
     * 对于工业类NPC，使用JSON配置的工作结束时间
     * 对于商业类NPC，使用商业建筑配置的工作结束时间
     * 对于其他类NPC，使用默认的傍晚时间
     */
    public static boolean shouldStartResting(ServerLevel level, CustomEntity npc) {
        if (npc == null) {
            return shouldStartResting(level);
        }

        // 无房NPC不进入住宅休息工作流，避免夜间持续重复尝试回家
        if (getNPCHomePosition(npc, level.getServer()) == null) {
            return false;
        }

        // simukraft: 检查NPC是否刚刚被玩家唤醒（冷却期内不重新开始休息）
        UUID npcUuid = npc.getUUID();
        Long wakeUpTime = npcManualWakeUpTime.get(npcUuid);
        if (wakeUpTime != null) {
            long elapsedTicks = level.getGameTime() - wakeUpTime;
            if (elapsedTicks < MANUAL_WAKE_UP_COOLDOWN_TICKS) {
                return false; // 冷却期内不重新开始休息
            } else {
                // 冷却期已过，清除记录
                npcManualWakeUpTime.remove(npcUuid);
            }
        }

        // 检查是否是工业类NPC（通过检查是否能获取到工业建筑配置）
        String job = npc.getJob();
        if (job != null && !job.equals("unemployed")) {
            BlockPos workPos = getWorkplacePosition(npc, level.getServer(), job);
            if (workPos != null) {
                // 尝试从工业建筑配置中获取工作时间
                String buildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(level, workPos);
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

                // 尝试从商业建筑配置中获取工作时间
                if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job)) {
                    String commercialBuildingFileName = com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.getBuildingFileName(level, workPos);
                    if (commercialBuildingFileName != null) {
                        com.xiaoliang.simukraft.building.CommercialBuildingConfig config =
                            com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(commercialBuildingFileName);
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
        }

        // 其他类NPC使用默认时间
        return shouldStartResting(level);
    }

    /**
     * 检查是否应该结束休息（带NPC参数版本，支持工业类和商业类NPC）
     * 对于工业类NPC，使用JSON配置的工作开始时间
     * 对于商业类NPC，使用商业建筑配置的工作开始时间
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
                String buildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(level, workPos);
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

                // 尝试从商业建筑配置中获取工作时间
                if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job)) {
                    String commercialBuildingFileName = com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.getBuildingFileName(level, workPos);
                    if (commercialBuildingFileName != null) {
                        com.xiaoliang.simukraft.building.CommercialBuildingConfig config =
                            com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(commercialBuildingFileName);
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
        }

        // 其他类NPC使用默认时间
        return shouldStopResting(level);
    }

    /**
     * 检查是否应该准备出发去工作（带NPC参数版本，支持工业类和商业类NPC）
     * 对于工业类NPC，在工作开始时间前1000tick准备出发
     * 对于商业类NPC，使用商业建筑配置的工作开始时间
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
                String buildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(level, workPos);
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

                // 尝试从商业建筑配置中获取工作时间
                if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job)) {
                    String commercialBuildingFileName = com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.getBuildingFileName(level, workPos);
                    if (commercialBuildingFileName != null) {
                        com.xiaoliang.simukraft.building.CommercialBuildingConfig config =
                            com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(commercialBuildingFileName);
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
     * 恢复NPC睡觉状态（用于重新加载游戏后恢复正在睡觉的NPC）
     * @param npc NPC实体
     * @param level 服务器世界
     * @param bedPos 床位置（床头）
     */
    public static void restoreSleepingNPC(CustomEntity npc, ServerLevel level, BlockPos bedPos) {
        if (npc == null || level == null || bedPos == null) return;

        UUID npcUuid = npc.getUUID();

        // 如果NPC已经在休息列表中，不需要重复添加
        if (restingNPCs.containsKey(npcUuid)) {
            return;
        }

        // 获取NPC的住宅位置
        BlockPos homePos = getNPCHomePosition(npc, level.getServer());
        if (homePos == null) {
            homePos = bedPos;
        }

        // 创建休息数据，设置为睡觉状态
        RestData restData = new RestData(npc, level);
        restData.homePos = homePos;
        restData.bedPos = bedPos;
        restData.restStage = REST_STAGE_SLEEPING;
        restData.hasArrivedHome = true;

        restingNPCs.put(npcUuid, restData);
        npcSubStates.put(npcUuid, WorkSubState.RESTING);
        npcHomePositions.put(npcUuid, homePos);

        // 设置NPC状态
        npc.setWorkSubState(WorkSubState.RESTING);
        npc.setStatusLabel("gui.npc.status.at_home");
        npc.setNoAi(true);

        //LOGGER.info("[NPCRestHandler] NPC {} 重新加载后恢复睡觉状态，床位置: {}",
        //    npc.getFullName(), bedPos);
    }

    /**
     * 开始NPC休息流程
     * 所有NPC（工作中的和空闲的）都会进入休息状态
     */
    public static void startResting(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null) return;

        UUID npcUuid = npc.getUUID();

        // 已经在休息工作流中时，不允许重复进入回家流程
        if (restingNPCs.containsKey(npcUuid) || goingToWorkNPCs.containsKey(npcUuid)) {
            return;
        }

        // 已经处于工作中且不在夜间回家窗口时，不允许错误地重新拉回家
        if (npc.getWorkStatus() == WorkStatus.WORKING && !shouldStartResting(level)) {
            return;
        }

        // 获取NPC的住宅位置
        BlockPos homePos = getNPCHomePosition(npc, level.getServer());
        if (homePos == null) {
            if (ServerConfig.isDebugLogEnabled()) {
                LOGGER.info("[NPCRestHandler] NPC {} 没有住宅，跳过回家休息流程，避免误将当前位置或他人住宅当作家", npc.getFullName());
            }
            return;
        } else {
            //LOGGER.info("[NPCRestHandler] NPC {} 找到住宅位置: {}", npc.getFullName(), homePos);
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
                com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.saveConstructionTask(level.getServer(), npc);
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

        if (ServerConfig.isDebugLogEnabled()) {
            LOGGER.info("NPC {} 开始休息流程，准备返回住宅: {}，原状态: {}，原职业: {}",
                npc.getFullName(), homePos, resumeWorkStatus, currentJob);
        }

        // 开始寻路回家
        queuePathfindingToHome(npcUuid, level);
    }

    /**
     * 停止NPC的所有活动，但允许移动（用于休息回家）
     */
    private static void stopAllNPCActivities(CustomEntity npc) {
        if (npc == null) return;

        // 设置isWorking为false，允许NPC移动
        npc.setWorking(false);
        npc.stopAllMovement();

        // 恢复AI
        npc.setNoAi(false);

        // 清除目标
        npc.setTarget(null);
        npc.setLastHurtByMob(null);
        // 停止建造任务
        if (npc.getConstructionTask() != null) {
            npc.setConstructionTask(null);
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("NPC {} 的所有活动已停止，已设置为可移动状态", npc.getFullName());
        }
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

        // simukraft: 只停止睡觉，不传送。让后续逻辑决定传送到哪里
        stopSleepingIfNeeded(npc);
        npc.setWorking(false);

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
                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，传送回工作岗位: {}",
                            npc.getFullName(), distance, workPos);
                    }
                    spawnTeleportParticles(npc);
                    npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);
                    spawnTeleportParticles(npc);

                    // 传送后直接恢复工作状态
                    restoreWorkStatus(npc, npcUuid, previousWorkStatus, previousJob, level);
                } else if (distance > 3.0) {
                    // 距离适中，需要寻路过去
                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，开始寻路去工作: {}",
                            npc.getFullName(), distance, workPos);
                    }

                    // simukraft: 首先设置状态标签，让NPCBoundaryManager知道NPC要去工作了
                    npc.setStatusLabel("gui.npc.status.going_to_work");

                    // 清理休息数据（但保留工作状态相关数据，等到达后再清理）
                    // 这必须在开始寻路之前完成，防止休息界限阻挡NPC
                    clearRestWorkflowData(npcUuid, false);

                    // simukraft: 清除休息界限
                    com.xiaoliang.simukraft.entity.ai.NPCBoundaryManager boundaryManager = npc.getBoundaryManager();
                    if (boundaryManager != null) {
                        boundaryManager.clearRestrictedArea();
                    }

                    // 设置子状态为正在去工作
                    npc.setWorkSubState(WorkSubState.RESTING);
                    npcSubStates.put(npcUuid, WorkSubState.RESTING);

                    // 创建去工作数据
                    GoingToWorkData workData = new GoingToWorkData(npc, level, workPos, previousJob, previousWorkStatus);
                    goingToWorkNPCs.put(npcUuid, workData);

                    // 开始寻路到工作位置
                    startPathfindingToWork(npc, workPos);

                    // 发送起床提示消息
                    sendWakeUpMessage(npc, level.getServer());

                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("NPC {} 结束休息，正在前往工作岗位: {}，职业: {}",
                            npc.getFullName(), workPos, previousJob);
                    }
                    return; // 不立即恢复工作状态，等到达后再恢复
                } else {
                    // 已经在工作位置附近，直接恢复工作状态
                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("[NPCRestHandler] NPC {} 已经在工作岗位附近({}格)，直接恢复工作",
                            npc.getFullName(), distance);
                    }
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

        // simukraft: 清除所有边界限制（menglannnn: 休息结束，恢复正常移动）
        com.xiaoliang.simukraft.entity.ai.NPCBoundaryManager boundaryManager = npc.getBoundaryManager();
        if (boundaryManager != null) {
            boundaryManager.clearRestrictedArea();
        }

        // 发送起床提示消息
        sendWakeUpMessage(npc, level.getServer());

        if (ServerConfig.isDebugLogEnabled()) {
            LOGGER.info("NPC {} 结束休息，恢复原状态: {}，原职业: {}",
                npc.getFullName(), previousWorkStatus, previousJob);
        }

        // 清理休息数据
        clearRestWorkflowData(npcUuid, true);
    }

    /**
     * 恢复NPC的工作状态（带level参数的版本）
     */
    private static void restoreWorkStatus(CustomEntity npc, UUID npcUuid, WorkStatus previousWorkStatus, String previousJob, ServerLevel level) {
        stopSleepingIfNeeded(npc);

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

        // 恢复建筑师的建造任务 - 立即从JSON恢复，而不是依赖旧每日Handler
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

        if (ServerConfig.isDebugLogEnabled()) {
            LOGGER.info("[NPCRestHandler] NPC {} 已恢复工作状态: {}，职业: {}",
                npc.getFullName(), previousWorkStatus, previousJob);
        }

        // 清理数据 - 确保完全清理所有休息状态数据
        clearRestWorkflowData(npcUuid, true);

        if (ServerConfig.isDebugLogEnabled()) {
            LOGGER.info("[NPCRestHandler] NPC {} 休息状态数据已完全清理，标签将恢复正常显示", npc.getFullName());
        }
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
            com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService.INSTANCE.restoreWorkState(npc, npc.getUUID(), level);
            return;
        }

        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(previousJob)) {
            String buildingFileName = com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.getBuildingFileName(level, workPos);
            if (buildingFileName != null) {
                com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.restoreNpcAfterRest(npc, level, workPos, buildingFileName);
            }
            return;
        }

        String industrialBuildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(level, workPos);
        if (industrialBuildingFileName != null) {
            com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.restoreNpcAfterRest(npc, level, workPos, industrialBuildingFileName);
            return;
        }

        if ("warehouse_manager".equals(previousJob)) {
            com.xiaoliang.simukraft.job.jobs.warehousemanager.WarehouseManagerWorkService.INSTANCE.restoreWorkState(npc, npc.getUUID(), level);
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
                LOGGER.warn("[NPCRestHandler] 建筑盒已不存在，移除建造任务并解除雇佣 - NPC: {}",
                    npc.getFullName());
                com.xiaoliang.simukraft.world.ConstructionTaskData.removeTask(server, npc.getUUID());
                // 兜底：建筑盒被破坏后还残留的雇佣记录会让 builder 一直站在原地却没活干，
                // 这里同步解除 V2 + V1 的分配，避免每天 startDailyWork 反复尝试恢复。
                releaseStaleBuilderAssignment(server, npc, taskInfo.buildBoxPos);
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
     * 当建造任务恢复失败（建筑盒已不存在等不可恢复情况）时，把 V2/V1 的雇佣分配也清理掉，
     * 避免 startDailyWork / JobRuntimeService 每 tick 都尝试恢复一个永远恢复不了的任务。
     */
    private static void releaseStaleBuilderAssignment(MinecraftServer server, CustomEntity npc, BlockPos buildBoxPos) {
        if (server == null || npc == null) {
            return;
        }
        try {
            var result = com.xiaoliang.simukraft.employment.service.EmploymentServices.get(server)
                    .fireByNpc(new com.xiaoliang.simukraft.employment.service.EmploymentCommands.FireByNpcCommand(npc.getUUID()));
            if (result.success() && result.assignment() != null) {
                com.xiaoliang.simukraft.network.EmploymentCommandPacket.applyFireSideEffectsAndBroadcast(
                        server, result.assignment(), false
                );
            }
        } catch (Exception e) {
            LOGGER.warn("[NPCRestHandler] 清理建筑师 V2 雇佣记录失败 - NPC: {}", npc.getFullName(), e);
        }
        try {
            Map<BlockPos, UUID> hiredBuilders = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server);
            boolean changed = hiredBuilders.entrySet().removeIf(e ->
                    npc.getUUID().equals(e.getValue()) || (buildBoxPos != null && buildBoxPos.equals(e.getKey()))
            );
            if (changed) {
                com.xiaoliang.simukraft.world.BuildBoxHiredData.saveHiredBuilders(server, hiredBuilders);
            }
        } catch (Exception e) {
            LOGGER.warn("[NPCRestHandler] 清理建筑师 V1 雇佣记录失败 - NPC: {}", npc.getFullName(), e);
        }
        npc.resetToIdle();
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

        int startedRestCount = 0;
        int stoppedRestCount = 0;
        int homePathStartCount = 0;

        for (CustomEntity npc : npcs) {
            if (npc == null || !npc.isAlive()) continue;

            UUID npcUuid = npc.getUUID();
            if (pendingHomePathStartTicks.containsKey(npcUuid) && homePathStartCount < MAX_HOME_PATH_STARTS_PER_UPDATE) {
                if (startQueuedPathfindingToHome(npc, level)) {
                    homePathStartCount++;
                }
            }
            if (NPCFamilyManager.isNpcBusyWithFamily(npcUuid)) {
                continue;
            }
            RestData restData = restingNPCs.get(npcUuid);
            GoingToWorkData workData = goingToWorkNPCs.get(npcUuid);

            if (restData != null && shouldPrepareForWork(level, npc)) {
                if (restData.hasArrivedHome) {
                    enqueueMainThreadLevelTask(level, () -> prepareNPCForWork(npc, level, restData),
                            "PrepareForWork-" + npcUuid);
                } else {
                    updateRestingNPC(npc, restData, level);
                }

                if (workData != null) {
                    updateGoingToWorkStatus(npc, workData, level);
                }
                continue;
            }

            if (shouldStartResting(level, npc)) {
                // 应该开始休息 - 所有NPC都进入休息状态（优先级最高）
                if (restData == null) {
                    if (!isRestStartWindowOpen(level, npcUuid) || startedRestCount >= MAX_REST_STARTS_PER_UPDATE) {
                        continue;
                    }
                }

                // 首先检查是否正在去工作，如果是则取消
                if (workData != null) {
                    // 正在去工作的路上，但到休息时间了，取消去工作，改为回家休息
                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("[NPCRestHandler] NPC {} 正在去工作，但到休息时间了，优先回家休息", npc.getFullName());
                    }
                    goingToWorkNPCs.remove(npcUuid);
                    pendingHomePathStartTicks.remove(npcUuid);
                    enqueueMainThreadLevelTask(level, () -> npc.getNavigation().stop(), "StopNavigation-" + npcUuid);
                }

                if (restData == null && npc.getWorkStatus() == WorkStatus.WORKING) {
                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("[NPCRestHandler] NPC {} 正在工作，但到休息时间了，停止工作并回家", npc.getFullName());
                    }
                    // 保存当前工作状态以便明天恢复
                    npcPreviousWorkStatus.put(npcUuid, WorkStatus.WORKING);
                    npcPreviousJob.put(npcUuid, npc.getJob());

                    // 如果是建筑师，保存建造任务到JSON而不是内存Map
                    if ("builder".equals(npc.getJob()) && npc.getConstructionTask() != null) {
                        if (npc.getServer() != null) {
                            com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.saveConstructionTask(npc.getServer(), npc);
                            if (ServerConfig.isDebugLogEnabled()) {
                                LOGGER.info("[NPCRestHandler] NPC {} 保存建造任务到JSON: {}",
                                    npc.getFullName(), npc.getConstructionTask().getBuildingName());
                            }
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
                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("[NPCRestHandler] 尝试让NPC {} 开始休息", npc.getFullName());
                    }
                    enqueueMainThreadLevelTask(level, () -> startResting(npc, level), "StartResting-" + npcUuid);
                    startedRestCount++;
                } else {
                    // NPC已经在休息中，更新休息状态
                    updateRestingNPC(npc, restData, level);
                }
            } else if (shouldStopResting(level, npc)) {
                // 应该结束休息（工作开始时间）
                if (restData != null) {
                    if (stoppedRestCount >= MAX_REST_STOPS_PER_UPDATE) {
                        continue;
                    }
                    enqueueMainThreadLevelTask(level, () -> stopResting(npc, level), "StopResting-" + npcUuid);
                    stoppedRestCount++;
                }

                // 更新正在去工作的NPC（检查是否超时需要传送）
                if (workData != null) {
                    updateGoingToWorkStatus(npc, workData, level);
                }
            }
        }

        PerformanceMonitor.recordValue("rest.startsQueued", startedRestCount);
        PerformanceMonitor.recordValue("rest.stopsQueued", stoppedRestCount);
        PerformanceMonitor.recordValue("rest.homePathStarts", homePathStartCount);
        PerformanceMonitor.recordValue("rest.homePathPending", pendingHomePathStartTicks.size());
        PerformanceMonitor.recordValue("rest.active", restingNPCs.size());
        PerformanceMonitor.recordValue("rest.goingToWork", goingToWorkNPCs.size());
    }

    private static boolean isRestStartWindowOpen(ServerLevel level, UUID npcUuid) {
        if (level == null || npcUuid == null) {
            return true;
        }
        long timeOfDay = level.getDayTime() % 24000L;
        if (timeOfDay < EVENING_START_TIME || timeOfDay >= EVENING_START_TIME + REST_START_SPREAD_TICKS) {
            return true;
        }
        long offset = Math.floorMod(npcUuid.hashCode(), REST_START_SPREAD_TICKS);
        return timeOfDay - EVENING_START_TIME >= offset;
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
                // 已经到家，进一步找床并进入睡觉状态
                updateAtHomeStatus(npc, restData, level);
                break;
            case REST_STAGE_SLEEPING:
                updateSleepingStatus(npc, restData, level);
                break;
            case REST_STAGE_WAITING_FOR_BED:
                updateWaitingForBedStatus(npc, restData, level);
                break;
            case REST_STAGE_WAKING_UP:
                // 起床
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
            if (ServerConfig.isDebugLogEnabled()) {
                LOGGER.info("[NPCRestHandler] NPC {} isWorking=true，设置为false以允许移动", npc.getFullName());
            }
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
            if (ServerConfig.isDebugLogEnabled()) {
                LOGGER.info("[NPCRestHandler] NPC {} 距离家{}格，超过50格，直接传送回家", npc.getFullName(), distance);
            }
            teleportNPCHomeWithEffects(npc, homePos);
            markArrivedHome(npc, restData);
            return;
        }

        // 检查是否超过强制传送时间（19:00还没到家则传送）
        long dayTime = level.getDayTime() % 24000L;
        if (dayTime >= EVENING_FORCE_TELEPORT_TIME) {
            if (ServerConfig.isDebugLogEnabled()) {
                LOGGER.info("[NPCRestHandler] NPC {} 超过19:00仍未到家（当前时间：{}），强制传送回家",
                    npc.getFullName(), dayTime);
            }
            teleportNPCHomeWithEffects(npc, homePos);
            markArrivedHome(npc, restData);
            return;
        }

        // 如果距离小于3格，认为已到达
        if (distance < 3.0) {
            markArrivedHome(npc, restData);
            if (ServerConfig.isDebugLogEnabled()) {
                LOGGER.info("NPC {} 已到达住宅，开始休息", npc.getFullName());
            }
        } else {
            // 检查是否需要重新寻路
            Boolean isPathfinding = npcPathfindingStatus.get(npc.getUUID());
            if (isPathfinding == null || !isPathfinding) {
                // 如果距离很近（小于10格）但寻路失败，直接传送回家
                if (distance < 10.0) {
                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("[NPCRestHandler] NPC {} 距离家{}格（小于10格）但寻路失败，直接传送回家",
                            npc.getFullName(), distance);
                    }
                    teleportNPCHomeWithEffects(npc, homePos);
                    markArrivedHome(npc, restData);
                } else {
                    // 重新启动寻路
                    if (ServerConfig.isDebugLogEnabled()) {
                        LOGGER.info("[NPCRestHandler] NPC {} 重新启动寻路回家，当前距离: {}", npc.getFullName(), distance);
                    }
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
        ServerLevel level = npc.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        BlockPos teleportPos = resolveHomeTeleportPos(npc, level, homePos);

        // 在原地生成传送粒子效果
        spawnTeleportParticles(npc);

        // 传送NPC到家中
        npc.teleportTo(
            teleportPos.getX() + 0.5,
            teleportPos.getY(),
            teleportPos.getZ() + 0.5
        );

        // 在目的地生成传送粒子效果
        spawnTeleportParticles(npc);

        if (ServerConfig.isDebugLogEnabled()) {
            LOGGER.info("[NPCRestHandler] NPC {} 已传送回家并添加粒子效果", npc.getFullName());
        }
    }

    /**
     * 优先使用sk中的NBT传送点；未配置、解析失败或落点不安全时回退到控制盒附近。
     */
    private static BlockPos resolveHomeTeleportPos(CustomEntity npc, ServerLevel level, BlockPos homePos) {
        if (npc == null || level == null || level.getServer() == null) {
            return homePos.above();
        }

        BlockPos markedTeleportPos = resolveMarkedHomeTeleportPos(level, homePos);
        if (markedTeleportPos != null && canNpcStandAt(level, markedTeleportPos)) {
            return markedTeleportPos;
        }

        boolean teleportToAbove = com.xiaoliang.simukraft.building.ControlBoxDataManager
                .isResidentialHomeTeleportToAbove(level.getServer(), homePos);
        BlockPos preferredPos = teleportToAbove ? homePos.above() : homePos.below();
        if (canNpcStandAt(level, preferredPos)) {
            return preferredPos;
        }

        BlockPos fallbackPos = teleportToAbove ? homePos.below() : homePos.above();
        if (canNpcStandAt(level, fallbackPos)) {
            return fallbackPos;
        }

        return homePos.above();
    }

    private static BlockPos resolveMarkedHomeTeleportPos(ServerLevel level, BlockPos homePos) {
        com.xiaoliang.simukraft.building.PlacedBuildingManager.PlacedBuildingData placedBuilding =
                com.xiaoliang.simukraft.building.PlacedBuildingManager.getBuildingByControlBox(homePos);
        if (placedBuilding == null || placedBuilding.buildingName == null || placedBuilding.buildingName.isBlank()) {
            return null;
        }

        String buildingFileName = normalizeBuildingFileName(placedBuilding.buildingName);
        BlockPos teleportNbtPos = getCachedHomeTeleportNbtPos(buildingFileName);
        if (teleportNbtPos == null) {
            return null;
        }

        BlockPos cachedRelativePos = findPlacedRelativePosByOriginalNbtPos(placedBuilding, teleportNbtPos);
        if (cachedRelativePos != null) {
            return homePos.offset(cachedRelativePos);
        }

        BlockPos controlBoxInNBT = getCachedResidentialControlBoxNbtPos(buildingFileName);
        if (controlBoxInNBT == null) {
            controlBoxInNBT = BlockPos.ZERO;
        }

        return homePos.offset(teleportNbtPos.subtract(controlBoxInNBT));
    }

    private static BlockPos getCachedHomeTeleportNbtPos(String buildingFileName) {
        return homeTeleportNbtPosCache
                .computeIfAbsent(buildingFileName, key -> Optional.ofNullable(
                        com.xiaoliang.simukraft.building.ControlBoxDataManager.getHomeTeleportNbtPosFromSkFile(key, "residential")
                ))
                .orElse(null);
    }

    private static BlockPos getCachedResidentialControlBoxNbtPos(String buildingFileName) {
        return residentialControlBoxNbtPosCache
                .computeIfAbsent(buildingFileName, key -> Optional.ofNullable(findControlBoxInResidentialNBT(key)))
                .orElse(null);
    }

    private static BlockPos findPlacedRelativePosByOriginalNbtPos(
            com.xiaoliang.simukraft.building.PlacedBuildingManager.PlacedBuildingData placedBuilding,
            BlockPos originalNbtPos) {
        for (com.xiaoliang.simukraft.building.PlacedBuildingManager.BlockEntry entry : placedBuilding.blocks) {
            if (originalNbtPos.equals(entry.originalNbtPos)) {
                return entry.relativePos;
            }
        }
        return null;
    }

    private static String normalizeBuildingFileName(String buildingFileName) {
        if (buildingFileName.endsWith(".sk")) {
            return buildingFileName.substring(0, buildingFileName.length() - 3);
        }
        if (buildingFileName.endsWith(".nbt")) {
            return buildingFileName.substring(0, buildingFileName.length() - 4);
        }
        return buildingFileName;
    }

    private static BlockPos findControlBoxInResidentialNBT(String buildingFileName) {
        try {
            java.util.List<SchematicNBTLoader.SchematicBlock> blocks = loadResidentialSchematicBlocks(buildingFileName);
            if (blocks == null || blocks.isEmpty()) {
                return null;
            }

            for (SchematicNBTLoader.SchematicBlock block : blocks) {
                String blockId = ForgeRegistries.BLOCKS.getKey(block.blockState().getBlock()).toString();
                if (blockId.contains("control_box")) {
                    return block.pos();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[NPCRestHandler] 解析住宅NBT控制盒位置失败: {}, {}", buildingFileName, e.getMessage());
        }
        return null;
    }

    private static java.util.List<SchematicNBTLoader.SchematicBlock> loadResidentialSchematicBlocks(String buildingFileName) {
        String nbtFilePath = "simukraftbuilding/residential/" + buildingFileName + ".nbt";
        java.io.File nbtFile = new java.io.File(nbtFilePath);
        if (nbtFile.exists()) {
            return SchematicNBTLoader.loadSchematicBlocks(nbtFilePath);
        }

        String resourcePath = "assets/simukraft/building/residential/" + buildingFileName + ".nbt";
        java.io.InputStream is = SchematicNBTLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            return null;
        }
        return SchematicNBTLoader.loadSchematicBlocksFromStream(is);
    }

    private static void markArrivedHome(CustomEntity npc, RestData restData) {
        restData.hasArrivedHome = true;
        restData.restStage = REST_STAGE_AT_HOME;
        pendingHomePathStartTicks.remove(npc.getUUID());
        npcPathfindingStatus.put(npc.getUUID(), false);
        npc.stopAllMovement();
        npc.setStatusLabel("gui.npc.status.at_home");
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
        for (int i = 0; i < 12; i++) {
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
        for (int i = 0; i < 4; i++) {
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

    // simukraft: 界限解锁时间，23900 tick（约早上5:58），让NPC可以提前出发去工作
    private static final long BOUNDS_UNLOCK_TIME = 23900L;

    /**
     * 检查是否应该解锁界限（menglannnn: 23900 tick后允许NPC离开家去工作）
     */
    private static boolean shouldUnlockBounds(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        // 23900 tick后解锁，允许NPC前往工作岗位
        return dayTime >= BOUNDS_UNLOCK_TIME || dayTime < 1000L;
    }

    /**
     * 更新在家状态（限制在封闭空间内活动）
     */
    private static void updateAtHomeStatus(CustomEntity npc, RestData restData, ServerLevel level) {
        if (npc == null || restData == null || level == null) {
            return;
        }

        // simukraft: 获取住宅边界，限制NPC移动范围
        BuildingBounds bounds = getResidenceBuildingBounds(level, restData.homePos, npc);

        // simukraft: 23900 tick后解锁界限，不再重新启用限制
        if (!shouldUnlockBounds(level)) {
            // simukraft: 启用边界限制（menglannnn: 使用NPCBoundaryManager统一管理）
            com.xiaoliang.simukraft.entity.ai.NPCBoundaryManager boundaryManager = npc.getBoundaryManager();
            if (boundaryManager != null && bounds != null) {
                boundaryManager.setRestrictedArea(bounds.center, bounds.rangeX, bounds.rangeZ, restData.homePos);
            }
        }

        if (!isBedStillValid(level, restData.bedPos, npc)) {
            restData.bedPos = null;
        }

        long gameTime = level.getGameTime();
        if (restData.bedPos == null && gameTime >= restData.nextBedRetryTick) {
            // simukraft: 传入NPC参数，限制只在自己家范围内找床
            restData.bedPos = findNearbyBed(level, restData.homePos, npc);
            restData.nextBedRetryTick = gameTime + BED_REPATH_INTERVAL_TICKS;
            if (restData.bedPos != null) {
                //LOGGER.info("[NPCRestHandler] NPC {} 在住宅附近找到床位: {}", npc.getFullName(), restData.bedPos);
            }
        }

        // simukraft: NPC休息时自由移动或睡觉
        npc.setNoAi(false);
        npc.setWorking(false);

        long dayTime = level.getDayTime() % 24000L;
        boolean shouldBeSleepingTime = dayTime >= 12542L && dayTime < BOUNDS_UNLOCK_TIME;

        // 夜晚在家但未上床时，直接冻结移动，防止原版Goal和残留导航继续乱跑
        long timeSinceWakeUp = gameTime - restData.lastWakeUpTime;
        // simukraft: 随机冷却时间30秒~1分钟（600~1200 tick）
        long sleepCooldown = 600L + (long) (Math.random() * 600L);
        boolean canSleep = timeSinceWakeUp > sleepCooldown;

        // 如果有床且距离近，尝试睡觉（但唤醒后随机时间内不睡）
        if (canSleep && restData.bedPos != null) {
            double bedDistance = npc.position().distanceTo(Vec3.atCenterOf(restData.bedPos));
            if (bedDistance <= 2.2D) {
                tryStartSleeping(npc, restData.bedPos, level);
                if (npc.isSleeping()) {
                    restData.restStage = REST_STAGE_SLEEPING;
                    return;
                }
            }
        }

        // simukraft: 夜晚未真正躺床时，不允许使用自定义寻路闲逛，避免在住宅内乱跑
        if (shouldBeSleepingTime && (!canSleep || restData.bedPos != null)) {
            restData.restStage = REST_STAGE_WAITING_FOR_BED;
            updateWaitingForBedStatus(npc, restData, level);
            return;
        }

        // NPC自由移动：随机选择目标点（床或闲逛位置）
        // simukraft: 使用NPCBoundaryManager自动处理边界限制，这里只需要选择目标
        if (npc.getNavigation().isDone()) {
            BlockPos targetPos = chooseRestTarget(npc, restData, bounds, level);
            if (targetPos != null) {
                if (ServerConfig.isDebugLogEnabled()) {
                    LOGGER.info("[NPCRestHandler] NPC {} 开始休息闲逛，当前位置: {}，目标位置: {}，目的: 休息闲逛", npc.getFullName(), npc.blockPosition(), targetPos);
                }
                if (!npc.moveToWithNewPathfinder(targetPos, 0.8D)) {
                    LOGGER.warn("[NPCRestHandler] NPC {} 休息闲逛寻路失败，当前位置: {}，目标位置: {}，目的: 休息闲逛，改为直接传送", npc.getFullName(), npc.blockPosition(), targetPos);
                    npc.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
                    npc.stopNewPathfinder();
                }
            }
        }
    }

    /**
     * 选择休息时的目标点（床或闲逛位置，优先封闭空间但开放空间也允许）
     */
    private static BlockPos chooseRestTarget(CustomEntity npc, RestData restData, BuildingBounds bounds, ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        boolean shouldBeSleepingTime = dayTime >= 12542L && dayTime < BOUNDS_UNLOCK_TIME;
        if (shouldBeSleepingTime) {
            return null;
        }

        // 30%概率尝试去床上睡觉（如果床存在且在范围内）
        if (restData.bedPos != null && Math.random() < 0.5) {
            // 检查床是否在范围内
            if (bounds == null || isPosInHomeArea(restData.bedPos, bounds)) {
                BlockPos bedStandPos = findBedStandPos(level, restData.bedPos);
                if (bedStandPos != null) {
                    return bedStandPos;
                }
            }
        }

        // 否则在范围内随机闲逛
        if (bounds != null) {
            return findWanderPosInHomeArea(npc, bounds);
        }

        return null;
    }

    /**
     * 检查位置是否在住宅范围内（用于限制目标位置）
     */
    private static boolean isPosInHomeArea(BlockPos pos, BuildingBounds bounds) {
        if (pos == null || bounds == null) return false;
        return Math.abs(pos.getX() - bounds.center.getX()) <= bounds.rangeX &&
               Math.abs(pos.getY() - bounds.center.getY()) <= bounds.rangeY &&
               Math.abs(pos.getZ() - bounds.center.getZ()) <= bounds.rangeZ;
    }

    /**
     * 在住宅范围内找一个随机闲逛位置（menglannnn: 在建筑边界内自由移动）
     */
    private static BlockPos findWanderPosInHomeArea(CustomEntity npc, BuildingBounds bounds) {
        if (npc == null || bounds == null) return null;

        ServerLevel level = npc.level() instanceof ServerLevel ? (ServerLevel) npc.level() : null;
        if (level == null) return null;

        BlockPos currentPos = npc.blockPosition();

        // simukraft: 使用NPC当前Y坐标，避免跑到地下或屋顶
        int baseY = currentPos.getY();

        for (int i = 0; i < 30; i++) {
            // simukraft: 在范围内随机选择目标点
            int x = bounds.center.getX() + (int) ((Math.random() - 0.5) * bounds.rangeX * 1.8);
            int z = bounds.center.getZ() + (int) ((Math.random() - 0.5) * bounds.rangeZ * 1.8);
            // 在NPC当前Y坐标附近搜索（±2格）
            int y = baseY + (int) ((Math.random() - 0.5) * 4);

            BlockPos pos = new BlockPos(x, y, z);

            // 确保目标点在范围内、可以站立、距离当前位置足够远
            if (isPosInHomeArea(pos, bounds) && canNpcStandAt(level, pos) && currentPos.distSqr(pos) > 4) {
                return pos;
            }
        }

        return null;
    }

    private static void updateWaitingForBedStatus(CustomEntity npc, RestData restData, ServerLevel level) {
        if (npc == null || restData == null || level == null) {
            return;
        }

        npc.stopAllMovement();
        npc.setNoAi(true);
        npc.setWorking(false);
        npc.setStatusLabel("gui.npc.status.at_home");

        if (!isBedStillValid(level, restData.bedPos, npc)) {
            restData.bedPos = findNearbyBed(level, restData.homePos, npc);
            if (restData.bedPos == null) {
                return;
            }
        }

        double bedDistance = npc.position().distanceTo(Vec3.atCenterOf(restData.bedPos));
        if (bedDistance <= 2.2D) {
            npc.setNoAi(false);
            tryStartSleeping(npc, restData.bedPos, level);
            if (npc.isSleeping()) {
                restData.restStage = REST_STAGE_SLEEPING;
            }
        }
    }

    private static void updateSleepingStatus(CustomEntity npc, RestData restData, ServerLevel level) {
        if (npc == null || restData == null || level == null) {
            return;
        }

        if (!isBedStillValid(level, restData.bedPos, npc)) {
            restData.bedPos = null;
            restData.restStage = REST_STAGE_AT_HOME;
            return;
        }

        if (!npc.isSleeping()) {
            restData.restStage = REST_STAGE_AT_HOME;
            updateAtHomeStatus(npc, restData, level);
            return;
        }

        // simukraft: 躺在床上时限制寻路和移动
        npc.stopNewPathfinder();
        npc.getNavigation().stop();
        npc.setNoAi(true);
        npc.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);

        // 确保NPC保持在与上床时一致的躺姿锚点，避免被纠偏到床边后掉到地上抽搐
        if (restData.bedPos != null) {
            BlockPos headPos = getBedHeadPos(level, restData.bedPos);
            net.minecraft.world.phys.Vec3 sleepAnchor = resolveSleepAnchor(level, restData.bedPos);
            double distance = npc.position().distanceTo(sleepAnchor);
            if (distance > 0.35D) {
                npc.teleportTo(sleepAnchor.x, sleepAnchor.y, sleepAnchor.z);
                npc.startSleeping(headPos);
            }
        }

        npc.setStatusLabel("gui.npc.status.at_home");
    }

    private static void tryStartSleeping(CustomEntity npc, BlockPos bedPos, ServerLevel level) {
        if (npc == null || bedPos == null) {
            return;
        }

        if (npc.isSleeping()) {
            return;
        }

        // simukraft: 检查床是否被占用
        if (isBedOccupiedByOther(level, bedPos, npc)) {
        //    LOGGER.debug("[NPCRestHandler] NPC {} 尝试上床但床已被占用，位置: {}", npc.getFullName(), bedPos);
            return;
        }

        // simukraft: 检查上床冷却时间
        UUID npcUuid = npc.getUUID();
        long currentTime = level.getGameTime();
        Long lastBedTime = npcLastBedTime.get(npcUuid);
        if (lastBedTime != null && (currentTime - lastBedTime) < BED_COOLDOWN_TICKS) {
            // 冷却中，不上床
            return;
        }

        // simukraft: 记录上床时间
        npcLastBedTime.put(npcUuid, currentTime);

        // simukraft: 停止寻路，防止"躺着跑"
        npc.stopNewPathfinder();
        npc.getNavigation().stop();
        npc.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);

        // simukraft: 计算床头位置和睡觉位置（根据床朝向调整）
        BlockPos headPos = getBedHeadPos(level, bedPos);
        net.minecraft.world.phys.Vec3 sleepAnchor = resolveSleepAnchor(level, bedPos);

        // simukraft: 将NPC传送到计算好的睡觉位置
        npc.teleportTo(sleepAnchor.x, sleepAnchor.y, sleepAnchor.z);

        // simukraft: 使用床头位置开始睡觉
        npc.startSleeping(headPos);
    }

    public static void tryStartSleepingForFamily(CustomEntity npc, BlockPos bedPos, ServerLevel level) {
        tryStartSleeping(npc, bedPos, level);
    }

    /**
     * 获取床头位置（根据床尾位置计算床头位置）
     * @param level 世界
     * @param footPos 床尾位置
     * @return 床头位置
     */
    private static BlockPos getBedHeadPos(ServerLevel level, BlockPos footPos) {
        if (level == null || footPos == null) {
            return footPos;
        }

        BlockState bedState = level.getBlockState(footPos);
        if (bedState.getBlock() instanceof BedBlock && bedState.hasProperty(BedBlock.FACING)) {
            Direction facing = bedState.getValue(BedBlock.FACING);
            // 床头在床尾朝向的方向上
            return footPos.relative(facing);
        }

        return footPos;
    }

    private static net.minecraft.world.phys.Vec3 resolveSleepAnchor(ServerLevel level, BlockPos bedPos) {
        BlockPos headPos = getBedHeadPos(level, bedPos);
        BlockState bedState = level.getBlockState(bedPos);
        Direction bedFacing = Direction.NORTH;
        if (bedState.getBlock() instanceof BedBlock && bedState.hasProperty(BedBlock.FACING)) {
            bedFacing = bedState.getValue(BedBlock.FACING);
        }

        double sleepX = headPos.getX() + 0.5D;
        double sleepZ = headPos.getZ() + 0.5D;
        switch (bedFacing) {
            case NORTH -> sleepZ = headPos.getZ() + 0.8D;
            case SOUTH -> sleepZ = headPos.getZ() + 0.2D;
            case WEST -> sleepX = headPos.getX() + 0.8D;
            case EAST -> sleepX = headPos.getX() + 0.2D;
            default -> {
            }
        }
        return new net.minecraft.world.phys.Vec3(sleepX, headPos.getY() + 0.5625D, sleepZ);
    }

    private static void stopSleepingIfNeeded(CustomEntity npc) {
        if (npc == null) {
            return;
        }
        if (!npc.isSleeping()) {
            npc.setNoAi(false);
            return;
        }

        npc.stopSleeping();
        npc.setNoAi(false);
    }

    /**
     * 玩家唤醒NPC（原版风格，玩家右键床唤醒）
     * @param npc 正在睡觉的NPC
     * @param level 服务器世界
     */
    public static void wakeUpNPC(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null || !npc.isSleeping()) {
            return;
        }

        UUID npcUuid = npc.getUUID();
        RestData restData = restingNPCs.get(npcUuid);

        // 获取床位置
        BlockPos bedPos = null;
        if (restData != null && restData.bedPos != null) {
            bedPos = restData.bedPos;
        } else {
            bedPos = npc.getSleepingPos().orElse(null);
        }

        // 停止睡觉（原版风格：直接停止睡觉状态）
        stopSleepingIfNeeded(npc);
        npc.setWorking(false);

        // 原版风格：尝试让NPC站在床旁边
        if (bedPos != null) {
            BlockPos wakePos = findBedStandPos(level, bedPos);
            if (wakePos != null) {
                // 原版风格：传送到床边站立位置
                npc.teleportTo(wakePos.getX() + 0.5, wakePos.getY(), wakePos.getZ() + 0.5);
            }
        }

        // simukraft: 如果NPC在休息流程中，保持休息状态，只改为"在家"阶段
        if (restData != null) {
            restData.restStage = REST_STAGE_AT_HOME;
            restData.hasArrivedHome = true;
            restData.lastWakeUpTime = level.getGameTime(); // 记录唤醒时间
            npc.setWorkSubState(WorkSubState.RESTING);
            npc.setStatusLabel("gui.npc.status.at_home");
            //    LOGGER.info("[NPCRestHandler] NPC {} 被玩家唤醒，继续在家休息", npc.getFullName());
        } else {
            // 不在休息流程中（异常情况），设置为空闲
            npc.setWorkStatus(WorkStatus.IDLE);
            npc.setWorkSubState(WorkSubState.NONE);
            npc.setStatusLabel("gui.npc.status.idle");
            //    LOGGER.info("[NPCRestHandler] NPC {} 被玩家唤醒（不在休息流程中）", npc.getFullName());
        }
    }

    private static void wakeUpAtHome(CustomEntity npc, ServerLevel level, RestData restData) {
        if (npc == null) {
            return;
        }

        if (restData != null) {
            restData.restStage = REST_STAGE_WAKING_UP;
        }

        // simukraft: 只停止睡觉，不自动传送。让调用者决定传送到哪里
        stopSleepingIfNeeded(npc);
        npc.setWorking(false);
        npc.setStatusLabel("gui.npc.status.at_home");

        // 如果有床，传送到床边（这是正常的起床行为）
        if (level != null && restData != null && restData.bedPos != null) {
            BlockPos wakePos = findBedStandPos(level, restData.bedPos);
            if (wakePos != null) {
                npc.teleportTo(wakePos.getX() + 0.5, wakePos.getY() + 0.1, wakePos.getZ() + 0.5);
            }
        }
        // 如果没有床，不进行传送，让NPC保持在当前位置
    }

    private static void clearRestWorkflowData(UUID npcUuid, boolean clearPreviousWorkData) {
        restingNPCs.remove(npcUuid);
        npcSubStates.remove(npcUuid);
        npcPathfindingStatus.remove(npcUuid);
        npcHomePositions.remove(npcUuid);
        if (clearPreviousWorkData) {
            npcPreviousWorkStatus.remove(npcUuid);
            npcPreviousJob.remove(npcUuid);
            npcPreviousPlanningTaskId.remove(npcUuid);
        }
    }

    private static boolean isBedStillValid(ServerLevel level, BlockPos bedPos, CustomEntity npc) {
        if (level == null || bedPos == null || !level.isLoaded(bedPos)) {
            return false;
        }

        BlockState bedState = level.getBlockState(bedPos);
        if (!(bedState.getBlock() instanceof BedBlock)) {
            return false;
        }

        if (isBedOccupiedByOther(level, bedPos, npc)) {
            return false;
        }

        // 床被标记为 occupied 时，允许当前正睡在这张床上的 NPC 继续保持睡眠，
        // 避免把“自己正在使用的床”误判成失效床并在下一 tick 被踢出睡眠状态。
        if (bedState.hasProperty(BedBlock.OCCUPIED) && bedState.getValue(BedBlock.OCCUPIED)) {
            return isSleepingOnBed(npc, getBedHeadPos(level, bedPos));
        }

        return true;
    }

    private static boolean isSleepingOnBed(CustomEntity npc, BlockPos headPos) {
        if (npc == null || headPos == null || !npc.isSleeping()) {
            return false;
        }
        return npc.getSleepingPos()
                .map(headPos::equals)
                .orElse(false);
    }

    /**
     * 检查床是否被其他实体占用（用于上床前检查）
     * @param level 世界
     * @param bedPos 床位置
     * @param npc 要上床的NPC（排除自己）
     * @return 如果被其他实体占用返回true
     */
    private static boolean isBedOccupiedByOther(ServerLevel level, BlockPos bedPos, CustomEntity npc) {
        if (level == null || bedPos == null) {
            return true;
        }

        // 获取床头位置
        BlockPos headPos = getBedHeadPos(level, bedPos);

        // 检查是否有其他实体正在使用这个床
        for (net.minecraft.world.entity.LivingEntity entity : level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class,
                new AABB(headPos).inflate(0.5D, 0.5D, 0.5D))) {
            if (entity != npc && entity.isSleeping()) {
                // 检查这个实体是否睡在这个床上
                if (entity.getSleepingPos().isPresent() && entity.getSleepingPos().get().equals(headPos)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static BlockPos findNearbyBed(ServerLevel level, BlockPos homePos, CustomEntity npc) {
        if (level == null || homePos == null) {
            return null;
        }

        // simukraft: 获取住宅建筑边界，使用建筑中心作为搜索中心
        BuildingBounds bounds = getResidenceBuildingBounds(level, homePos, npc);

        BlockPos searchCenter;
        int rangeX, rangeY, rangeZ;

        if (bounds != null) {
            // 使用建筑中心作为搜索中心
            searchCenter = bounds.center;
            rangeX = bounds.rangeX;
            rangeY = bounds.rangeY;
            rangeZ = bounds.rangeZ;
        } else {
            // 回退：使用控制盒位置作为搜索中心，使用默认范围
            searchCenter = homePos;
            rangeX = BED_SEARCH_HORIZONTAL_RADIUS;
            rangeY = BED_SEARCH_VERTICAL_RADIUS;
            rangeZ = BED_SEARCH_HORIZONTAL_RADIUS;
        }

        // simukraft: 先在NBT定义范围内搜索
        BlockPos bestBedPos = findBedInRange(level, searchCenter, rangeX, rangeY, rangeZ);

        // 如果没找到，扩大搜索范围（玩家可能自己加了床）
        if (bestBedPos == null) {
            int extendedRangeX = Math.max(rangeX, BED_SEARCH_HORIZONTAL_RADIUS);
            int extendedRangeY = Math.max(rangeY, BED_SEARCH_VERTICAL_RADIUS);
            int extendedRangeZ = Math.max(rangeZ, BED_SEARCH_HORIZONTAL_RADIUS);
            bestBedPos = findBedInRange(level, searchCenter, extendedRangeX, extendedRangeY, extendedRangeZ);
        }

        return bestBedPos;
    }

    public static BlockPos findFamilyBed(ServerLevel level, BlockPos homePos, CustomEntity npc) {
        return findNearbyBed(level, homePos, npc);
    }

    /**
     * 在指定范围内搜索床（辅助方法）
     */
    private static BlockPos findBedInRange(ServerLevel level, BlockPos center, int rangeX, int rangeY, int rangeZ) {
        BlockPos bestBedPos = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = -rangeY; y <= rangeY; y++) {
            for (int x = -rangeX; x <= rangeX; x++) {
                for (int z = -rangeZ; z <= rangeZ; z++) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof BedBlock)) {
                        continue;
                    }
                    if (state.hasProperty(BedBlock.PART) && state.getValue(BedBlock.PART) != BedPart.FOOT) {
                        continue;
                    }

                    BlockPos candidateBedPos = cursor.immutable();
                    if (findBedStandPos(level, candidateBedPos) == null) {
                        continue;
                    }

                    double distance = candidateBedPos.distSqr(center);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestBedPos = candidateBedPos;
                    }
                }
            }
        }
        return bestBedPos;
    }

    /**
     * 建筑边界信息（存储建筑的边界和中心点）
     */
    private static class BuildingBounds {
        final BlockPos center;  // 建筑中心
        final int rangeX;       // X方向范围
        final int rangeY;       // Y方向范围
        final int rangeZ;       // Z方向范围

        BuildingBounds(BlockPos center, int rangeX, int rangeY, int rangeZ) {
            this.center = center;
            this.rangeX = rangeX;
            this.rangeY = rangeY;
            this.rangeZ = rangeZ;
        }
    }

    /**
     * 获取住宅建筑边界（优先从PlacedBuildingManager获取，失败则回退到NBT文件读取）
     * @param level 世界
     * @param homePos 住宅控制盒位置
     * @param npc NPC实体
     * @return 建筑边界信息，失败返回null
     */
    private static BuildingBounds getResidenceBuildingBounds(ServerLevel level, BlockPos homePos, CustomEntity npc) {
        try {
            // simukraft: 首先尝试从PlacedBuildingManager获取已放置的建筑数据（menglannnn: 更精确的边界）
            com.xiaoliang.simukraft.building.PlacedBuildingManager.PlacedBuildingData placedBuilding =
                com.xiaoliang.simukraft.building.PlacedBuildingManager.getBuildingByControlBox(homePos);

            if (placedBuilding != null) {
                // simukraft: 使用已放置建筑的方块列表计算精确边界（menglannnn: 正确处理相对坐标）
                // 方块位置是相对于控制盒的，需要加上控制盒位置得到世界坐标
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

                for (com.xiaoliang.simukraft.building.PlacedBuildingManager.BlockEntry block : placedBuilding.blocks) {
                    BlockPos worldPos = block.relativePos.offset(homePos);
                    minX = Math.min(minX, worldPos.getX());
                    maxX = Math.max(maxX, worldPos.getX());
                    minY = Math.min(minY, worldPos.getY());
                    maxY = Math.max(maxY, worldPos.getY());
                    minZ = Math.min(minZ, worldPos.getZ());
                    maxZ = Math.max(maxZ, worldPos.getZ());
                }

                // 如果没有方块，使用默认范围
                if (minX == Integer.MAX_VALUE) {
                    minX = homePos.getX() - 5;
                    maxX = homePos.getX() + 5;
                    minY = homePos.getY() - 2;
                    maxY = homePos.getY() + 4;
                    minZ = homePos.getZ() - 5;
                    maxZ = homePos.getZ() + 5;
                }

                int centerX = (minX + maxX) / 2;
                int centerY = (minY + maxY) / 2;
                int centerZ = (minZ + maxZ) / 2;
                BlockPos centerPos = new BlockPos(centerX, centerY, centerZ);

                int rangeX = (maxX - minX) / 2 + 2;
                int rangeY = (maxY - minY) / 2 + 3;
                int rangeZ = (maxZ - minZ) / 2 + 2;

                //    LOGGER.debug("[NPCRestHandler] NPC {} 住宅建筑边界(已放置): 控制盒={}, 中心={}, 范围: x={}, y={}, z={}",
                //        npc.getFullName(), homePos, centerPos, rangeX, rangeY, rangeZ);

                return new BuildingBounds(centerPos, rangeX, rangeY, rangeZ);
            }

            // 回退：从NBT文件读取建筑边界（用于尚未注册的建筑）
            return getBuildingBoundsFromNBT(level, homePos, npc);

        } catch (Exception e) {
            //    LOGGER.error("[NPCRestHandler] 获取NPC {} 住宅建筑边界失败: {}", npc.getFullName(), e.getMessage());
            return null;
        }
    }

    /**
     * 从NBT文件读取建筑边界（menglannnn: 用于尚未注册到PlacedBuildingManager的建筑）
     */
    private static BuildingBounds getBuildingBoundsFromNBT(ServerLevel level, BlockPos homePos, CustomEntity npc) {
        try {
            // 读取住宅控制盒数据获取建筑文件名
            com.xiaoliang.simukraft.building.ControlBoxDataManager.ControlBoxData controlBoxData =
                com.xiaoliang.simukraft.building.ControlBoxDataManager.readControlBox(
                    level.getServer(), homePos, "residential");

            if (controlBoxData == null || controlBoxData.buildingFileName == null) {
                return null;
            }

            // 从建筑NBT文件读取方块列表
            String nbtFilePath = "simukraftbuilding/residential/" + controlBoxData.buildingFileName + ".nbt";
            java.io.File nbtFile = new java.io.File(nbtFilePath);

            java.util.List<com.xiaoliang.simukraft.client.preview.SchematicNBTLoader.SchematicBlock> blocks;
            if (nbtFile.exists()) {
                blocks = com.xiaoliang.simukraft.client.preview.SchematicNBTLoader.loadSchematicBlocks(nbtFilePath);
            } else {
                // 尝试从资源文件读取
                String resourcePath = "assets/simukraft/building/residential/" + controlBoxData.buildingFileName + ".nbt";
                java.io.InputStream is = SchematicNBTLoader.class.getClassLoader().getResourceAsStream(resourcePath);
                if (is == null) {
                    //    LOGGER.debug("[NPCRestHandler] 找不到建筑NBT文件: {}", controlBoxData.buildingFileName);
                    return null;
                }
                blocks = com.xiaoliang.simukraft.client.preview.SchematicNBTLoader.loadSchematicBlocksFromStream(is);
            }

            if (blocks.isEmpty()) {
                return null;
            }

            // 计算建筑边界
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

            for (com.xiaoliang.simukraft.client.preview.SchematicNBTLoader.SchematicBlock block : blocks) {
                BlockPos pos = block.pos();
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY());
                maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
            }

            // 计算建筑中心（相对于控制盒的偏移）
            int centerOffsetX = (minX + maxX) / 2;
            int centerOffsetY = (minY + maxY) / 2;
            int centerOffsetZ = (minZ + maxZ) / 2;

            // 计算搜索范围（半尺寸+缓冲）
            int rangeX = (maxX - minX) / 2 + 2;
            int rangeY = (maxY - minY) / 2 + 3;
            int rangeZ = (maxZ - minZ) / 2 + 2;

            // 建筑中心 = 控制盒位置 + 中心偏移
            BlockPos centerPos = homePos.offset(centerOffsetX, centerOffsetY, centerOffsetZ);

            //    LOGGER.debug("[NPCRestHandler] NPC {} 住宅建筑边界(NBT): 控制盒={}, 中心={}, 范围: x={}, y={}, z={}",
            //        npc.getFullName(), homePos, centerPos, rangeX, rangeY, rangeZ);

            return new BuildingBounds(centerPos, rangeX, rangeY, rangeZ);

        } catch (Exception e) {
            LOGGER.error("[NPCRestHandler] 从NBT获取NPC {} 住宅建筑边界失败: {}", npc.getFullName(), e.getMessage());
            return null;
        }
    }

    private static BlockPos findBedStandPos(ServerLevel level, BlockPos bedPos) {
        if (level == null || bedPos == null || !level.isLoaded(bedPos)) {
            return null;
        }

        BlockState bedState = level.getBlockState(bedPos);
        List<BlockPos> candidates = new ArrayList<>();

        if (bedState.getBlock() instanceof BedBlock && bedState.hasProperty(BedBlock.FACING)) {
            Direction facing = bedState.getValue(BedBlock.FACING);
            candidates.add(bedPos.relative(facing.getOpposite()));
            candidates.add(bedPos.relative(facing.getClockWise()));
            candidates.add(bedPos.relative(facing.getCounterClockWise()));
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(bedPos.relative(direction));
        }

        for (BlockPos candidate : candidates) {
            if (canNpcStandAt(level, candidate)) {
                return candidate.immutable();
            }
        }

        return bedPos;
    }

    private static boolean canNpcStandAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        BlockPos abovePos = pos.above();
        if (!level.isLoaded(pos) || !level.isLoaded(abovePos)) {
            return false;
        }

        return level.getBlockState(pos).canBeReplaced()
            && level.getBlockState(abovePos).canBeReplaced()
            && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
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
            // simukraft: 未被雇佣的NPC不应该提前唤醒，继续睡觉直到正常起床时间
            return;
        }

        // simukraft: 被雇佣的NPC才唤醒并准备出发
        wakeUpAtHome(npc, level, restData);

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

            //    LOGGER.info("[NPCRestHandler] NPC {} 早上5点了，提前出发去工作岗位: {}，职业: {}",
            //        npc.getFullName(), workPos, previousJob);

        // simukraft: 首先设置状态标签，让NPCBoundaryManager知道NPC要去工作了
        // 这必须在清除界限之前完成，防止tick过程中界限仍然生效
        npc.setStatusLabel("gui.npc.status.going_to_work");

        // 从休息数据中移除（不再处于休息状态）
        clearRestWorkflowData(npcUuid, false);

        // simukraft: 清除休息界限，防止NPC被限制在住宅范围内无法前往工作岗位
        com.xiaoliang.simukraft.entity.ai.NPCBoundaryManager boundaryManager = npc.getBoundaryManager();
        if (boundaryManager != null) {
            boundaryManager.clearRestrictedArea();
        }

        // 允许NPC移动
        npc.setNoAi(false);
        npc.setWorking(false);
        npc.getNavigation().stop();

        // 检查距离
        double distance = npc.position().distanceTo(new net.minecraft.world.phys.Vec3(workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5));

        if (distance > 10.0) {
            // 距离太远，直接传送
            //    LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，直接传送", npc.getFullName(), distance);
            //    LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，直接传送", npc.getFullName(), distance);
            spawnTeleportParticles(npc);
            npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);
            spawnTeleportParticles(npc);

            // 直接恢复工作状态
            restoreWorkStatus(npc, npcUuid, previousWorkStatus, previousJob, level);
        } else {
            // 距离适中，开始寻路
            //    LOGGER.info("[NPCRestHandler] NPC {} 距离工作岗位{}格，开始寻路去工作", npc.getFullName(), distance);

            // 设置子状态为正在去工作
            npc.setWorkSubState(WorkSubState.RESTING);
            npcSubStates.put(npcUuid, WorkSubState.RESTING);

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

        // 已经恢复为正常工作状态时，立即退出去工作流程，避免再次被休息链路拉回家
        if (npc.getWorkStatus() == WorkStatus.WORKING && npc.getWorkSubState() == WorkSubState.WORKING) {
            goingToWorkNPCs.remove(npcUuid);
            return;
        }

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
            //    LOGGER.info("[NPCRestHandler] NPC {} status label updated to: gui.npc.status.going_to_work", npc.getFullName());
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
            //    LOGGER.info("[NPCRestHandler] NPC {} 已到达工作位置: {}，水平距离: {}格，高度差: {}格，恢复工作状态",
            //    npc.getFullName(), workPos, distance, yDistance);

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
            if (ServerConfig.isDebugLogEnabled()) {
                LOGGER.info("[NPCRestHandler] NPC {} 去工作超时（已花费{}游戏刻），直接传送到工作位置: {}",
                    npc.getFullName(), timeSpent, workPos);
            }

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
            if (ServerConfig.isDebugLogEnabled()) {
                LOGGER.info("[NPCRestHandler] NPC {} 寻路中断，重新寻路到工作位置，当前距离: {}格",
                    npc.getFullName(), distance);
            }
            startPathfindingToWork(npc, workPos);
        }
    }

    /**
     * 开始寻路到工作位置
     * menglannnn: 完全使用新的自定义寻路系统，不再使用原版寻路
     */
    private static void startPathfindingToWork(CustomEntity npc, BlockPos workPos) {
        if (npc == null || workPos == null) return;

        try {
            if (npc.moveToWithNewPathfinder(workPos, 1.0D)) {
                if (ServerConfig.isDebugLogEnabled()) {
                    LOGGER.info("[NPCRestHandler] NPC {} 开始前往工作位置，当前位置: {}，目标位置: {}，目的: 上班", npc.getFullName(), npc.blockPosition(), workPos);
                }
                return;
            }

            LOGGER.warn("[NPCRestHandler] NPC {} 前往工作位置寻路失败，当前位置: {}，目标位置: {}，目的: 上班，改为直接传送", npc.getFullName(), npc.blockPosition(), workPos);
            npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);
            npc.stopNewPathfinder();
        } catch (Exception e) {
            LOGGER.error("[NPCRestHandler] NPC {} 寻路到工作位置时发生错误", npc.getFullName(), e);
        }
    }

    /**
     * 开始寻路回家
     * menglannnn: 完全使用新的自定义寻路系统，不再使用原版寻路
     */
    private static void queuePathfindingToHome(UUID npcUuid, ServerLevel level) {
        if (npcUuid == null || level == null) return;
        pendingHomePathStartTicks.putIfAbsent(npcUuid, level.getGameTime());
        npcPathfindingStatus.put(npcUuid, false);
    }

    private static boolean startQueuedPathfindingToHome(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null) return false;
        UUID npcUuid = npc.getUUID();
        RestData restData = restingNPCs.get(npcUuid);
        if (restData == null || restData.hasArrivedHome || restData.homePos == null) {
            pendingHomePathStartTicks.remove(npcUuid);
            return false;
        }
        pendingHomePathStartTicks.remove(npcUuid);
        startPathfindingToHome(npc, restData.homePos);
        return true;
    }

    private static void startPathfindingToHome(CustomEntity npc, BlockPos homePos) {
        if (npc == null || homePos == null) return;

        try {
            if (npc.moveToWithNewPathfinder(homePos, 1.0D)) {
                npcPathfindingStatus.put(npc.getUUID(), true);
                if (ServerConfig.isDebugLogEnabled()) {
                    LOGGER.info("[NPCRestHandler] NPC {} 开始前往住宅，当前位置: {}，目标位置: {}，目的: 回家", npc.getFullName(), npc.blockPosition(), homePos);
                }
                return;
            }

            LOGGER.warn("[NPCRestHandler] NPC {} 前往住宅寻路失败，当前位置: {}，目标位置: {}，目的: 回家，改为直接传送", npc.getFullName(), npc.blockPosition(), homePos);
            teleportNPCHomeWithEffects(npc, homePos);
            UUID npcUuid = npc.getUUID();
            RestData restData = restingNPCs.get(npcUuid);
            if (restData != null) {
                restData.hasArrivedHome = true;
                restData.restStage = REST_STAGE_AT_HOME;
            }
            npcPathfindingStatus.put(npcUuid, false);
            npc.stopNewPathfinder();
        } catch (Exception e) {
            LOGGER.error("NPC {} 寻路回家时发生错误", npc.getFullName(), e);
        }
    }

    /**
     * 获取NPC的住宅位置
     */
    private static BlockPos getNPCHomePosition(CustomEntity npc, MinecraftServer server) {
        if (npc == null || server == null) {
            return null;
        }

        // 住宅归属必须以住宅控制盒 resident_uuid 绑定为准，避免按名字匹配回错家。
        return ResidentManager.getNPCResidenceControlBoxPos(server, npc.getUUID());
    }

    public static BlockPos getFamilyHomePosition(CustomEntity npc, MinecraftServer server) {
        return getNPCHomePosition(npc, server);
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
        pendingHomePathStartTicks.clear();
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
