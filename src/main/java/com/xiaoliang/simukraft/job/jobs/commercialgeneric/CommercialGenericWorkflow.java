package com.xiaoliang.simukraft.job.jobs.commercialgeneric;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.utils.NPCRestHandler;
import com.xiaoliang.simukraft.utils.NPCWorkResumeCoordinator;
import net.minecraft.server.level.ServerLevel;

public final class CommercialGenericWorkflow implements JobWorkflow {
    private final CommercialGenericTargetResolver targetResolver;
    private final CommercialGenericWorkService workService;

    public CommercialGenericWorkflow(CommercialGenericTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
        this.workService = new CommercialGenericWorkService();
    }

    @Override
    public boolean canWork(JobContext context) {
        return targetResolver.resolve(context)
                .map(CommercialGenericWorkTarget::isValid)
                .orElse(false);
    }

    @Override
    public void onStartWork(JobContext context) {
        CustomEntity npc = context.npc();
        CommercialGenericWorkTarget target = targetResolver.resolve(context).orElse(null);

        if (npc != null && target != null && target.buildingConfig() != null) {
            npc.setJob(target.buildingConfig().getJobName());
            workService.setHeldItemFromConfig(npc, target.buildingConfig());
            NPCWorkResumeCoordinator.activateCommercialShift(npc, target.buildingPos());
            moveNpcToWorkplace(npc, target.buildingPos());
        }
    }

    @Override
    public void onStopWork(JobContext context) {
        CustomEntity npc = context.npc();
        if (npc != null) {
            NPCRestHandler.startResting(npc, context.level());
        }
    }

    @Override
    public JobResult tick(JobContext context) {
        return targetResolver.resolve(context)
                .filter(CommercialGenericWorkTarget::isValid)
                .map(target -> {
                    CustomEntity npc = context.npc();
                    long gameTime = context.level().getDayTime();

                    if (npc != null && target.buildingConfig() != null) {
                        CommercialBuildingConfig config = target.buildingConfig();
                        if (config.isWorkTime(gameTime)) {
                            handleCommercialWork(npc, target, config, gameTime);
                        }
                    }

                    return JobResult.success();
                })
                .orElseGet(() -> JobResult.paused("missing_commercial_target"));
    }

    private void handleCommercialWork(CustomEntity npc, CommercialGenericWorkTarget target,
                                      CommercialBuildingConfig config, long gameTime) {
        handleShiftStart(npc, target.buildingPos(), config, gameTime);

        ServerLevel serverLevel = (ServerLevel) npc.level();
        net.minecraft.core.BlockPos pos = target.buildingPos();

        switch (config.getShopMode()) {
            case NPC_SELL:
                workService.handleNPCSellMode(npc, pos, serverLevel, config);
                break;
            case PLAYER_SELL:
                workService.handlePlayerSellMode(pos, serverLevel, config);
                break;
            case MIXED:
                workService.handleMixedMode(npc, pos, serverLevel, config);
                break;
        }
    }

    private void handleShiftStart(CustomEntity npc, net.minecraft.core.BlockPos buildingPos,
                                  CommercialBuildingConfig config, long gameTime) {
        long timeOfDay = gameTime % 24000L;
        int startTime = config.getWorkStartTime();

        if (timeOfDay >= startTime && timeOfDay < startTime + 1000) {
            NPCWorkResumeCoordinator.activateCommercialShift(npc, buildingPos);
            moveNpcToWorkplace(npc, buildingPos);
        }
    }

    private void moveNpcToWorkplace(CustomEntity npc, net.minecraft.core.BlockPos workPos) {
        double targetX = workPos.getX() + 0.5;
        double targetY = workPos.getY() + 1.0;
        double targetZ = workPos.getZ() + 0.5;

        if (!npc.moveToWithNewPathfinder(targetX, targetY, targetZ, 1.0D)) {
            npc.teleportTo(targetX, targetY, targetZ);
            npc.stopNewPathfinder();
        }
    }
}
