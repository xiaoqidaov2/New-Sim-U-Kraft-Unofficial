package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * NPC工作恢复协调器
 * 统一处理主线程上的工作状态恢复，避免休息链路和进档恢复链路互相覆盖。
 */
public final class NPCWorkResumeCoordinator {
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
            if (level != null && workplacePos != null && buildingFileName != null && !buildingFileName.isBlank()) {
                com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.restoreNpcAfterRest(npc, level, workplacePos, buildingFileName);
            }
        });
    }

    public static boolean resumeIndustrialWork(CustomEntity npc, ServerLevel level, BlockPos workplacePos, String buildingFileName) {
        return resumeAssignedWork(npc, npc != null ? npc.getJob() : null, workplacePos, () -> {
            scheduleTeleportIfNeeded(npc, workplacePos);
            if (level != null && workplacePos != null && buildingFileName != null && !buildingFileName.isBlank()) {
                com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.restoreNpcAfterRest(npc, level, workplacePos, buildingFileName);
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
        // simukraft: 午休期间不恢复工作状态，也不传送
        if (npc.getWorkSubState() == WorkSubState.LUNCH_BREAK) {
            return false;
        }

        if (fallbackJob != null && !fallbackJob.isBlank() && !fallbackJob.equals(npc.getJob())) {
            npc.setJob(fallbackJob);
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
        if (npc.moveToWithNewPathfinder(workplacePos, 1.0D)) {
            return;
        }
        if (ServerConfig.isDebugLogEnabled()) {
            com.xiaoliang.simukraft.Simukraft.LOGGER.info("[NPCWorkResumeCoordinator] NPC {} 恢复工作寻路失败，改为安排传送到: {}", npc.getFullName(), workplacePos);
        }
        if (shouldTeleportToWorkplace(npc, workplacePos)) {
            npc.scheduleHireArrivalTeleport(workplacePos);
        }
    }
}
