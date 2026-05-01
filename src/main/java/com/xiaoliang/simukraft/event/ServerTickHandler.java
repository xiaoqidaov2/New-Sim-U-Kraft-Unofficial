package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = "simukraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
@SuppressWarnings("null")
public class ServerTickHandler {
    private static int delayTicks = 0;
    private static ServerPlayer delayedPlayer;
    
    // 使用Map来跟踪每个玩家的睡眠状态，更可靠
    private static final Map<UUID, Boolean> playerSleepingStates = new ConcurrentHashMap<>();

    // 添加日志记录器
    private static final Logger LOGGER = LogManager.getLogger();

    // 时间检测相关
    private static boolean morningWorkTriggered = false; // 是否已经触发过早上工作
    private static final int MORNING_WORK_TIME = 0; // 早上6:00（游戏刻0）触发工作

    // 中午生成NPC相关
    private static boolean noonSpawnTriggered = false; // 是否已经触发过中午生成
    private static final int NOON_SPAWN_TIME = 6000; // 中午12:00（游戏刻6000）触发生成

    // tick计数器，用于降低某些操作的频率
    private static int tickCounter = 0;
    private static final int REST_UPDATE_INTERVAL = 5; // 每5个tick更新一次休息状态
    private static final int WORK_PROGRESS_INTERVAL = 2; // 每2个tick更新一次工作进度
    private static int startupRestoreDelayTicks = -1;
    private static final int STARTUP_RESTORE_DELAY = 40;
    private static final int BUILDER_CONTAINER_SEARCH_RADIUS = 32;
    private static final List<ScheduledBuilderRefresh> scheduledBuilderRefreshes = new ArrayList<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tickCounter++;

            if (startupRestoreDelayTicks >= 0) {
                if (startupRestoreDelayTicks-- == 0) {
                    restoreHiredNpcWorkStates(event.getServer());
                    startupRestoreDelayTicks = -1;
                }
            }

            processScheduledBuilderRefreshes(event.getServer());

            // 处理延迟的NPC处理
            if (delayedPlayer != null) {
                if (delayTicks-- <= 0) {
                    initializePendingNpcData(delayedPlayer.serverLevel());
                    delayedPlayer = null;
                }
            }

            // 检测玩家起床事件并启动肉铺老板工作（必须在主线程执行）
            detectPlayerWakeUp(event);

            // 检测中午时间并生成新NPC（如果有空闲住宅）
            detectNoonAndSpawnNPC(event);

            // 检测商业建筑补货时间
            detectCommercialRestockTime(event);

            // 使用多线程调度器更新工作进度（降低频率）
            if (tickCounter % WORK_PROGRESS_INTERVAL == 0) {
                submitWorkProgressTasks(event.getServer());
            }

            // 使用多线程调度器更新NPC休息状态（降低频率）
            if (tickCounter % REST_UPDATE_INTERVAL == 0) {
                submitRestStatusTasks(event.getServer());
            }
        }
    }

    /**
     * 登录延迟阶段只处理当前世界已缓存的 NPC，避免为了补名字扫描整张世界。
     */
    private static void initializePendingNpcData(ServerLevel level) {
        if (level == null) {
            return;
        }
        for (CustomEntity npc : NPCTaskScheduler.getNPCsInLevel(level)) {
            if (!npc.isDataRecorded()) {
                npc.initializeName();
            }
        }
    }

    /**
     * 提交工作进度更新任务到多线程调度器
     */
    private static void submitWorkProgressTasks(MinecraftServer server) {
        if (server == null) return;

        // 商业建筑工作进度更新已合并到 CommercialWorkHandler
        // 不再需要单独处理肉铺、水果店、面包店
    }

    /**
     * 提交休息状态更新任务到多线程调度器
     */
    private static void submitRestStatusTasks(MinecraftServer server) {
        if (server == null) return;

        // 获取所有NPC并分批提交任务
        List<CustomEntity> allNPCs = NPCTaskScheduler.getAllNPCs(server);

        if (allNPCs.isEmpty()) return;

        // 将NPC按世界分组
        Map<ServerLevel, List<CustomEntity>> npcsByLevel = new ConcurrentHashMap<>();
        for (CustomEntity npc : allNPCs) {
            if (npc.level() instanceof ServerLevel level) {
                npcsByLevel.computeIfAbsent(level, k -> new java.util.ArrayList<>()).add(npc);
            }
        }

        // 休息状态涉及导航、传送、实体状态写入，统一在主线程推进。
        for (Map.Entry<ServerLevel, List<CustomEntity>> entry : npcsByLevel.entrySet()) {
            ServerLevel level = entry.getKey();
            List<CustomEntity> npcs = entry.getValue();
            try {
                NPCRestHandler.updateRestStatusAsync(level, npcs);
            } catch (Exception e) {
                LOGGER.error("更新NPC休息状态时发生错误", e);
            }
        }
    }


    
    /**
     * 检测时间触发工作 - 替代玩家起床检测
     * 在早上6:00触发所有商店老板的工作
     */
    private static void detectPlayerWakeUp(TickEvent.ServerTickEvent event) {
        // 获取服务器实例
        var server = event.getServer();
        if (server == null) {
            LOGGER.warn("无法获取Minecraft服务器实例");
            return;
        }

        // 获取当前游戏时间
        ServerLevel overworld = server.getLevel(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.parse("minecraft:overworld")
        ));
        if (overworld == null) return;

        long dayTime = overworld.getDayTime() % 24000L;

// 在早上6:00触发工作（游戏刻0-100之间）
        if (dayTime >= MORNING_WORK_TIME && dayTime < MORNING_WORK_TIME + 100) {
            if (!morningWorkTriggered) {
                morningWorkTriggered = true;
                LOGGER.info("早上6:00到了，启动所有商店老板和工业建筑每日工作");

                // 商业建筑每日工作已合并到 CommercialWorkHandler
                com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.handleDailyWork(overworld);

                // 工业建筑每日工作
                com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.handleDailyWork(overworld);

                // 启动建筑师每日工作（确保第二天能正常工作）
                com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.startDailyWork(overworld);
            }
        } else if (dayTime >= MORNING_WORK_TIME + 100 && dayTime < 12000) {
            // 早上6:00之后到18:00之前，重置触发标志
            morningWorkTriggered = false;
        }
    }

    /**
     * 检测中午时间并生成新NPC（如果有空闲住宅且所有NPC都有住宅）
     */
    private static void detectNoonAndSpawnNPC(TickEvent.ServerTickEvent event) {
        var server = event.getServer();
        if (server == null) return;

        // 获取主世界时间
        ServerLevel overworld = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse("minecraft:overworld")
        ));
        if (overworld == null) return;

        long dayTime = overworld.getDayTime() % 24000L;

        // 在中午12:00触发生成（游戏刻6000-6100之间）
        if (dayTime >= NOON_SPAWN_TIME && dayTime < NOON_SPAWN_TIME + 100) {
            if (!noonSpawnTriggered) {
                noonSpawnTriggered = true;
                LOGGER.info("中午12:00到了，检查是否需要生成新NPC");

                // 生成实体和写入城市/住宅数据都必须在服务端主线程执行。
                NPCTaskScheduler.runOnMainThread(server, () -> {
                    try {
                        com.xiaoliang.simukraft.utils.ResidentManager.spawnNPCAtNoon(server);
                    } catch (Exception e) {
                        LOGGER.error("中午生成NPC时发生错误", e);
                    }
                }, "NoonNPCSpawn");
            }
        } else if (dayTime >= NOON_SPAWN_TIME + 100 && dayTime < 12000) {
            // 中午12:00之后到18:00之前，重置触发标志
            noonSpawnTriggered = false;
        }
    }

    /**
     * 检测商业建筑补货时间并触发补货
     */
    private static void detectCommercialRestockTime(TickEvent.ServerTickEvent event) {
        var server = event.getServer();
        if (server == null) return;

        // 获取主世界时间
        ServerLevel overworld = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse("minecraft:overworld")
        ));
        if (overworld == null) return;

        long dayTime = overworld.getDayTime() % 24000L;

        // 调用 CommercialWorkHandler 处理补货
        com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.handleRestock(overworld, dayTime);
    }

    /**
     * 服务器启动时调用
     */
    public static void onServerStart(MinecraftServer server) {
        playerSleepingStates.clear();
        scheduledBuilderRefreshes.clear();
        delayedPlayer = null;
        delayTicks = 0;
        startupRestoreDelayTicks = STARTUP_RESTORE_DELAY;
        LOGGER.info("服务器启动，重置玩家睡眠状态跟踪器");

        // 商业建筑工作处理器启动
        com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.onServerStart(server);

        // 工业建筑工作处理器启动
        com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.onServerStart(server);

        // 清理农民的过期持久化数据并恢复工作状态
        com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService.INSTANCE.onServerStart(server.overworld());

        // 清理所有NPC的休息状态
        NPCRestHandler.stopAllResting();
        LOGGER.info("服务器启动，重置所有NPC休息状态");
    }

    private static void restoreHiredNpcWorkStates(MinecraftServer server) {
        if (server == null) {
            return;
        }

        LOGGER.info("开始恢复已雇佣NPC的工作状态");

        com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server)
                .forEach((pos, npcUuid) -> restoreNpc(server, npcUuid, "builder", WorkStatus.WORKING, pos));
        com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(server)
                .forEach((pos, npcUuid) -> restoreNpc(server, npcUuid, "planner", WorkStatus.WORKING, pos));
        com.xiaoliang.simukraft.world.LogisticsHiredData.getServerBoxHiredNpcs(server)
                .forEach((pos, npcUuid) -> restoreNpc(server, npcUuid, "warehouse_manager", WorkStatus.WORKING, pos));
    }

    private static void restoreNpc(MinecraftServer server, UUID npcUuid, String job, WorkStatus workStatus, BlockPos workplacePos) {
        if (server == null || npcUuid == null || job == null || job.isBlank()) {
            return;
        }

        CustomEntity npc = com.xiaoliang.simukraft.world.BaseBuildingHiredData.findNPCByUuid(server, npcUuid);
        if (npc == null || !npc.isAlive()) {
            return;
        }

        if (NPCRestHandler.isNpcInRestWorkflow(npcUuid)) {
            return;
        }

        if ("builder".equals(job)) {
            NPCWorkResumeCoordinator.resumeBuilderWork(npc, workplacePos, npc.getConstructionTask() != null);
            return;
        }

        npc.setJob(job);
        npc.setWorkStatus(workStatus);
        npc.setWorkSubState(WorkSubState.WORKING);
        npc.setWorking(workStatus != WorkStatus.IDLE);
        npc.setStatusLabel(null);

        if (workStatus != WorkStatus.IDLE && workplacePos != null && npc.distanceToSqr(
                workplacePos.getX() + 0.5D,
                workplacePos.getY() + 1.0D,
                workplacePos.getZ() + 0.5D
        ) > 9.0D) {
            npc.scheduleHireArrivalTeleport(workplacePos);
        }
    }

    public static void scheduleBuilderContainerRefresh(ServerLevel level, BlockPos containerPos, int delayTicks) {
        if (level == null || containerPos == null) {
            return;
        }

        long executeTick = level.getGameTime() + Math.max(0, delayTicks);
        BlockPos immutablePos = containerPos.immutable();
        ResourceKey<Level> dimension = level.dimension();
        for (ScheduledBuilderRefresh scheduledRefresh : scheduledBuilderRefreshes) {
            if (scheduledRefresh.dimension.equals(dimension) && scheduledRefresh.containerPos.equals(immutablePos)) {
                scheduledRefresh.executeTick = Math.min(scheduledRefresh.executeTick, executeTick);
                return;
            }
        }
        scheduledBuilderRefreshes.add(new ScheduledBuilderRefresh(dimension, immutablePos, executeTick));
    }

    private static void processScheduledBuilderRefreshes(MinecraftServer server) {
        if (server == null || scheduledBuilderRefreshes.isEmpty()) {
            return;
        }

        Iterator<ScheduledBuilderRefresh> iterator = scheduledBuilderRefreshes.iterator();
        while (iterator.hasNext()) {
            ScheduledBuilderRefresh scheduledRefresh = iterator.next();
            ServerLevel level = server.getLevel(scheduledRefresh.dimension);
            if (level == null) {
                iterator.remove();
                continue;
            }
            if (level.getGameTime() < scheduledRefresh.executeTick) {
                continue;
            }

            refreshNearbyBuildersForContainer(level, scheduledRefresh.containerPos);
            iterator.remove();
        }
    }

    private static void refreshNearbyBuildersForContainer(ServerLevel level, BlockPos containerPos) {
        AABB searchBox = new AABB(containerPos).inflate(BUILDER_CONTAINER_SEARCH_RADIUS);
        for (CustomEntity npc : level.getEntitiesOfClass(CustomEntity.class, searchBox)) {
            if (!"builder".equals(npc.getJob())) {
                continue;
            }

            ConstructionTask constructionTask = npc.getConstructionTask();
            if (constructionTask == null || constructionTask.isCompleted() || !constructionTask.hasNextBlock()) {
                continue;
            }
            if (!constructionTask.handlesContainerInteraction(level, containerPos)) {
                continue;
            }

            constructionTask.requestMaterialRefresh(0);
            NPCWorkResumeCoordinator.resumeBuilderWork(npc, constructionTask.getBuildBoxPos(), true);
        }
    }
    
    public static void scheduleDelayedNPCProcessing(ServerPlayer player, int ticks) {
        delayedPlayer = player;
        delayTicks = ticks;
    }

    private static final class ScheduledBuilderRefresh {
        private final ResourceKey<Level> dimension;
        private final BlockPos containerPos;
        private long executeTick;

        private ScheduledBuilderRefresh(ResourceKey<Level> dimension, BlockPos containerPos, long executeTick) {
            this.dimension = dimension;
            this.containerPos = containerPos;
            this.executeTick = executeTick;
        }
    }
}
