package com.xiaoliang.simukraft.job.jobs.farmer;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobWorkflow;

public final class FarmerWorkflow implements JobWorkflow {
    private final FarmerTargetResolver targetResolver;
    private final FarmerWorkService workService;

    public FarmerWorkflow(FarmerTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
        this.workService = FarmerWorkService.INSTANCE;
    }

    @Override
    public boolean canWork(JobContext context) {
        return targetResolver.resolve(context)
                .map(FarmerWorkTarget::isValid)
                .orElse(false);
    }

    @Override
    public void onStartWork(JobContext context) {
        CustomEntity npc = context.npc();
        FarmerWorkTarget target = targetResolver.resolve(context).orElse(null);

        if (npc != null && target != null) {
            npc.setJob("farmer");
            workService.restoreWorkState(npc, context.assignment().npcUuid(), context.level());
        }
    }

    @Override
    public JobResult tick(JobContext context) {
        return targetResolver.resolve(context)
                .filter(FarmerWorkTarget::isValid)
                .map(target -> {
                    CustomEntity npc = context.npc();

                    if (npc != null) {
                        ensureWorkState(npc);
                        workService.handleContinuousWork(context);
                    }

                    return JobResult.success();
                })
                .orElseGet(() -> JobResult.paused("missing_farmland_target"));
    }

    private void ensureWorkState(CustomEntity npc) {
        if (!"farmer".equals(npc.getJob())) {
            npc.setJob("farmer");
        }
        if (npc.getWorkStatus() != WorkStatus.WORKING) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorking(true);
        }
    }
}