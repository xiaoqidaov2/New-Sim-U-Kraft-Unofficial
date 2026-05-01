package com.xiaoliang.simukraft.job.jobs.warehousemanager;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobWorkflow;

public final class WarehouseManagerWorkflow implements JobWorkflow {
    private final WarehouseManagerTargetResolver targetResolver;
    private final WarehouseManagerWorkService workService;

    public WarehouseManagerWorkflow(WarehouseManagerTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
        this.workService = new WarehouseManagerWorkService();
    }

    @Override
    public boolean canWork(JobContext context) {
        return targetResolver.resolve(context)
                .map(WarehouseManagerWorkTarget::isValid)
                .orElse(false);
    }

    @Override
    public void onStartWork(JobContext context) {
        CustomEntity npc = context.npc();
        if (npc != null) {
            workService.restoreWorkState(npc, context.assignment().npcUuid(), context.level());
        }
    }

    @Override
    public JobResult tick(JobContext context) {
        return targetResolver.resolve(context)
                .filter(WarehouseManagerWorkTarget::isValid)
                .map(target -> {
                    CustomEntity npc = context.npc();
                    if (npc != null) {
                        workService.restoreWorkState(npc, context.assignment().npcUuid(), context.level());
                    }
                    return JobResult.success();
                })
                .orElseGet(() -> JobResult.paused("missing_warehouse_target"));
    }
}
