package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.job.jobs.industrialgeneric.CheeseFactoryWorkController;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * NPC工作恢复协调器
 * 统一处理主线程上的工作状态恢复，避免休息链路和进档恢复链路互相覆盖。
 */
public final class NPCWorkResumeCoordinator {
    private static final double DIRECT_WORK_TELEPORT_DISTANCE_SQR = 256.0D;
    private static final long MOVEMENT_REISSUE_INTERVAL_TICKS = 20L;
    private static final long WORKSITE_RESTORE_INTERVAL_TICKS = 40L;
    private static final ConcurrentMap<UUID, ResumeAttempt> lastMovementAttempts = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, ResumeAttempt> lastWorksiteRestoreAttempts = new ConcurrentHashMap<>();

    private NPCWorkResumeCoordinator() {}

    public static boolean resumeBuilderWork(CustomEntity npc, BlockPos workplacePos, boolean hasActiveTask) {
        return resumeAssignedWork(npc, "builder", workplacePos, () -> {
            if (!hasActiveTask) {
                return;
            }
            scheduleTeleportIfNeeded(npc, workplacePos);
        });
    }

    public static boolean activateCommercialShift(CustomEntity npc, BlockPos workplacePos) {
        return resumeAssignedWork(npc, npc != null ? npc.getJob() : null, workplacePos,
                () -> scheduleTeleportIfNeeded(npc, workplacePos));
    }

    public static boolean resumeCommercialWork(CustomEntity npc, ServerLevel level, BlockPos workplacePos, String buildingFileName) {
        return resumeAssignedWork(npc, npc != null ? npc.getJob() : null, workplacePos, () -> {
            scheduleTeleportIfNeeded(npc, workplacePos);
            if (shouldRestoreWorksite(npc, workplacePos) && level != null && workplacePos != null && buildingFileName != null && !buildingFileName.isBlank()) {
                com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.restoreNpcAfterRest(npc, level, workplacePos, buildingFileName);
                markWorksiteRestore(npc, workplacePos);
            }
        });
    }

    public static boolean resumeIndustrialWork(CustomEntity npc, ServerLevel level, BlockPos workplacePos, String buildingFileName) {
        return resumeAssignedWork(npc, npc != null ? npc.getJob() : null, workplacePos, () -> {
            boolean suppressWorkplacePull = CheeseFactoryWorkController.shouldSuppressWorkplacePull(
                    level, workplacePos, npc, buildingFileName
            );
            if (!suppressWorkplacePull) {
                scheduleTeleportIfNeeded(npc, workplacePos);
            }
            if (!suppressWorkplacePull
                    && shouldRestoreWorksite(npc, workplacePos)
                    && level != null && workplacePos != null && buildingFileName != null && !buildingFileName.isBlank()) {
                com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.restoreNpcAfterRest(npc, level, workplacePos, buildingFileName);
                markWorksiteRestore(npc, workplacePos);
            }
        });
    }

    private static boolean resumeAssignedWork(CustomEntity npc,
                                              String fallbackJob,
                                              BlockPos workplacePos,
                                              Runnable postPrepareAction) {
        if (!prepareWorkingNpc(npc, fallbackJob)) {
            return false;
        }
        if (postPrepareAction != null) {
            postPrepareAction.run();
        } else {
            scheduleTeleportIfNeeded(npc, workplacePos);
        }
        return true;
    }

    private static boolean prepareWorkingNpc(CustomEntity npc, String fallbackJob) {
        if (npc == null || !npc.isAlive()) {
            return false;
        }
        if (NPCRestHandler.isNpcInRestWorkflow(npc.getUUID())) {
            return false;
        }
        // simukraft: 午休和自行吃饭期间不恢复工作状态，也不传送
        if (npc.getWorkSubState() == WorkSubState.LUNCH_BREAK
                || npc.getWorkSubState() == WorkSubState.BUYING_FOOD) {
            return false;
        }

        if (fallbackJob != null && !fallbackJob.isBlank() && !fallbackJob.equals(npc.getJob())) {
            npc.setJob(fallbackJob);
        }
        if ("unemployed".equals(npc.getJob())) {
            return false;
        }
        if (npc.getWorkStatus() != WorkStatus.WORKING) {
            npc.setWorkStatus(WorkStatus.WORKING);
        }
        npc.setWorkSubState(WorkSubState.WORKING);
        npc.setWorking(true);
        npc.setStatusLabel(null);
        return true;
    }

    private static boolean shouldTeleportToWorkplace(CustomEntity npc, BlockPos workplacePos) {
        if (npc == null || workplacePos == null) {
            return false;
        }

        double targetX = workplacePos.getX() + 0.5D;
        double targetY = workplacePos.getY() + 1.0D;
        double targetZ = workplacePos.getZ() + 0.5D;
        return npc.distanceToSqr(targetX, targetY, targetZ) > 9.0D;
    }

    private static void scheduleTeleportIfNeeded(CustomEntity npc, BlockPos workplacePos) {
        if (npc == null || workplacePos == null) {
            return;
        }
        if (npc.isTeleportingForWork()) {
            return;
        }
        double distanceSqr = npc.distanceToSqr(
                workplacePos.getX() + 0.5D,
                workplacePos.getY() + 1.0D,
                workplacePos.getZ() + 0.5D
        );
        if (distanceSqr <= 9.0D) {
            return;
        }
        if (!canIssueMovementRequest(npc, workplacePos)) {
            return;
        }
        // menglan: 上班恢复时，远距离直接走受控传送，避免先做大范围寻路导致严重掉刻。
        if (distanceSqr >= DIRECT_WORK_TELEPORT_DISTANCE_SQR) {
            markMovementAttempt(npc, workplacePos);
            npc.scheduleHireArrivalTeleport(workplacePos);
            return;
        }
        if (npc.moveToWithNewPathfinder(workplacePos, 1.0D)) {
            markMovementAttempt(npc, workplacePos);
            return;
        }
        if (ServerConfig.isDebugLogEnabled()) {
            com.xiaoliang.simukraft.Simukraft.LOGGER.info("[NPCWorkResumeCoordinator] NPC {} 恢复工作寻路失败，改为安排传送到: {}", npc.getFullName(), workplacePos);
        }
        if (shouldTeleportToWorkplace(npc, workplacePos)) {
            markMovementAttempt(npc, workplacePos);
            npc.scheduleHireArrivalTeleport(workplacePos);
        }
    }

    private static boolean canIssueMovementRequest(CustomEntity npc, BlockPos workplacePos) {
        if (npc == null || workplacePos == null) {
            return false;
        }
        if (npc.isUsingCustomPathfinder() && npc.isPathfindingTo(workplacePos)) {
            return false;
        }
        ResumeAttempt lastAttempt = lastMovementAttempts.get(npc.getUUID());
        long gameTime = getGameTime(npc);
        return lastAttempt == null
                || gameTime < 0
                || !workplacePos.equals(lastAttempt.targetPos)
                || gameTime - lastAttempt.gameTime >= MOVEMENT_REISSUE_INTERVAL_TICKS;
    }

    private static boolean shouldRestoreWorksite(CustomEntity npc, BlockPos workplacePos) {
        if (npc == null || workplacePos == null || shouldTeleportToWorkplace(npc, workplacePos)) {
            return false;
        }
        ResumeAttempt lastAttempt = lastWorksiteRestoreAttempts.get(npc.getUUID());
        long gameTime = getGameTime(npc);
        return lastAttempt == null
                || gameTime < 0
                || !workplacePos.equals(lastAttempt.targetPos)
                || gameTime - lastAttempt.gameTime >= WORKSITE_RESTORE_INTERVAL_TICKS;
    }

    private static void markMovementAttempt(CustomEntity npc, BlockPos workplacePos) {
        if (npc == null || workplacePos == null) {
            return;
        }
        lastMovementAttempts.put(npc.getUUID(), new ResumeAttempt(workplacePos.immutable(), getGameTime(npc)));
    }

    private static void markWorksiteRestore(CustomEntity npc, BlockPos workplacePos) {
        if (npc == null || workplacePos == null) {
            return;
        }
        lastWorksiteRestoreAttempts.put(npc.getUUID(), new ResumeAttempt(workplacePos.immutable(), getGameTime(npc)));
    }

    private static long getGameTime(CustomEntity npc) {
        if (npc != null && npc.level() instanceof ServerLevel serverLevel) {
            return serverLevel.getGameTime();
        }
        return -1L;
    }

    private static final class ResumeAttempt {
        private final BlockPos targetPos;
        private final long gameTime;

        private ResumeAttempt(BlockPos targetPos, long gameTime) {
            this.targetPos = targetPos;
            this.gameTime = gameTime;
        }
    }
}
