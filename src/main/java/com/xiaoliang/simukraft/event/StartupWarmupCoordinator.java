package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.PlacedBuildingManager;
import com.xiaoliang.simukraft.integration.IntegrationBridge;
import com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler;
import com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService;
import com.xiaoliang.simukraft.utils.FileUtils;
import com.xiaoliang.simukraft.utils.NPCRestHandler;
import com.xiaoliang.simukraft.utils.NPCTaskScheduler;
import com.xiaoliang.simukraft.utils.NpcChunkLoadManager;
import com.xiaoliang.simukraft.world.FarmlandHiredData;
import com.xiaoliang.simukraft.world.IndustrialHiredData;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 启动预热协调器
 * 将启动期的磁盘读取和恢复逻辑拆分到多个阶段，避免玩家进世界时主线程瞬时抖动。
 */
public final class StartupWarmupCoordinator {
    private static final int DEFAULT_TASK_DELAY_TICKS = 20;
    private static final Queue<WarmupTask> PENDING_TASKS = new ArrayDeque<>();
    private static boolean active = false;
    private static boolean taskRunning = false;
    private static int waitTicks = 0;

    private StartupWarmupCoordinator() {
    }

    public static void schedule(MinecraftServer server) {
        if (server == null) {
            return;
        }

        PENDING_TASKS.clear();
        active = true;
        taskRunning = false;
        waitTicks = 0;

        PENDING_TASKS.add(WarmupTask.async(
                "PreloadSkCache", DEFAULT_TASK_DELAY_TICKS,
                () -> FileUtils.buildSkCacheSnapshot(server),
                result -> FileUtils.applySkCacheSnapshot((FileUtils.SkCacheSnapshot) result)
        ));
        PENDING_TASKS.add(WarmupTask.async(
                "PreloadPlacedBuildings", DEFAULT_TASK_DELAY_TICKS,
                () -> PlacedBuildingManager.loadSnapshotFromWorld(server),
                result -> PlacedBuildingManager.applySnapshot(server, (PlacedBuildingManager.PlacedBuildingSnapshot) result)
        ));
        PENDING_TASKS.add(WarmupTask.sync(
                "LoadAnimalGenerationData", DEFAULT_TASK_DELAY_TICKS,
                () -> IndustrialHiredData.loadAnimalGenerationData(server)
        ));
        PENDING_TASKS.add(WarmupTask.sync(
                "LoadCommercialStockData", DEFAULT_TASK_DELAY_TICKS,
                () -> CommercialWorkHandler.loadPersistentState(server)
        ));
        PENDING_TASKS.add(WarmupTask.sync(
                "LoadFarmlandData", DEFAULT_TASK_DELAY_TICKS,
                () -> FarmlandHiredData.loadAllFarmlandData(server)
        ));
        PENDING_TASKS.add(WarmupTask.sync(
                "RestoreFarmerWorkState", DEFAULT_TASK_DELAY_TICKS,
                () -> FarmerWorkService.INSTANCE.onServerStart(server.overworld())
        ));
        PENDING_TASKS.add(WarmupTask.sync(
                "LoadRestWorkflowState", DEFAULT_TASK_DELAY_TICKS,
                () -> NPCRestHandler.onServerStart(server)
        ));
        PENDING_TASKS.add(WarmupTask.sync(
                "RestoreNpcForcedChunks", DEFAULT_TASK_DELAY_TICKS,
                () -> NpcChunkLoadManager.restoreForcedChunks(server)
        ));
        PENDING_TASKS.add(WarmupTask.sync(
                "FinalizeIntegrations", 0,
                () -> IntegrationBridge.onServerStarted(server)
        ));

        Simukraft.LOGGER.info("[StartupWarmup] 已计划 {} 个启动预热阶段", PENDING_TASKS.size());
    }

    public static void tick(MinecraftServer server) {
        if (!active || server == null) {
            return;
        }
        if (taskRunning) {
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        WarmupTask task = PENDING_TASKS.poll();
        if (task == null) {
            active = false;
            Simukraft.LOGGER.info("[StartupWarmup] 启动预热已完成");
            return;
        }

        taskRunning = true;
        Simukraft.LOGGER.info("[StartupWarmup] 开始阶段: {}", task.name);
        task.execute(() -> {
            taskRunning = false;
            waitTicks = task.delayAfterTicks;
            Simukraft.LOGGER.info("[StartupWarmup] 完成阶段: {}", task.name);
        });
    }

    public static void reset() {
        PENDING_TASKS.clear();
        active = false;
        taskRunning = false;
        waitTicks = 0;
    }

    private interface AsyncSupplier {
        Object get() throws Exception;
    }

    private interface MainThreadConsumer {
        void accept(Object result);
    }

    private static final class WarmupTask {
        private final String name;
        private final int delayAfterTicks;
        private final Runnable syncAction;
        private final AsyncSupplier asyncSupplier;
        private final MainThreadConsumer asyncApply;

        private WarmupTask(String name,
                           int delayAfterTicks,
                           Runnable syncAction,
                           AsyncSupplier asyncSupplier,
                           MainThreadConsumer asyncApply) {
            this.name = name;
            this.delayAfterTicks = delayAfterTicks;
            this.syncAction = syncAction;
            this.asyncSupplier = asyncSupplier;
            this.asyncApply = asyncApply;
        }

        private static WarmupTask sync(String name, int delayAfterTicks, Runnable action) {
            return new WarmupTask(name, delayAfterTicks, action, null, null);
        }

        private static WarmupTask async(String name,
                                        int delayAfterTicks,
                                        AsyncSupplier supplier,
                                        MainThreadConsumer apply) {
            return new WarmupTask(name, delayAfterTicks, null, supplier, apply);
        }

        private void execute(Runnable onComplete) {
            if (syncAction != null) {
                try {
                    syncAction.run();
                } finally {
                    onComplete.run();
                }
                return;
            }

            NPCTaskScheduler.submitCallableWithMainThreadCallback(
                    asyncSupplier::get,
                    result -> {
                        if (asyncApply != null) {
                            asyncApply.accept(result);
                        }
                        onComplete.run();
                    },
                    name
            );
        }
    }
}
