package com.xiaoliang.simukraft.job.jobs.industrialgeneric;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.utils.NPCRestHandler;

public final class IndustrialGenericWorkflow implements JobWorkflow {
    private final IndustrialGenericTargetResolver targetResolver;
    private final IndustrialGenericWorkService workService;

    public IndustrialGenericWorkflow(IndustrialGenericTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
        this.workService = new IndustrialGenericWorkService();
    }

    @Override
    public boolean canWork(JobContext context) {
        return targetResolver.resolve(context)
                .map(IndustrialGenericWorkTarget::isValid)
                .orElse(false);
    }

    @Override
    public void onStartWork(JobContext context) {
        CustomEntity npc = context.npc();
        IndustrialGenericWorkTarget target = targetResolver.resolve(context).orElse(null);

        if (npc != null) {
            npc.setJob("industrial_worker");
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorking(true);
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
                .filter(IndustrialGenericWorkTarget::isValid)
                .map(target -> {
                    CustomEntity npc = context.npc();

                    if (npc != null) {
                        ensureWorkState(npc);
                        workService.handleContinuousWork(context);
                        handleIndustrialWork(npc, target);
                    }

                    return JobResult.success();
                })
                .orElseGet(() -> JobResult.paused("missing_industrial_target"));
    }

    private void ensureWorkState(CustomEntity npc) {
        if (!"industrial_worker".equals(npc.getJob())) {
            npc.setJob("industrial_worker");
        }
        if (npc.getWorkStatus() != WorkStatus.WORKING) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorking(true);
        }
    }

    private void handleIndustrialWork(CustomEntity npc, IndustrialGenericWorkTarget target) {
        double distance = npc.position().distanceTo(
                new net.minecraft.world.phys.Vec3(
                        target.factoryPos().getX() + 0.5,
                        target.factoryPos().getY(),
                        target.factoryPos().getZ() + 0.5
                )
        );

        if (distance > 3.0) {
            npc.scheduleHireArrivalTeleport(target.factoryPos());
        }
    }
}
