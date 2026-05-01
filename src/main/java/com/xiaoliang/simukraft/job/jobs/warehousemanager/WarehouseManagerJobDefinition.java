package com.xiaoliang.simukraft.job.jobs.warehousemanager;

import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.job.api.JobDefinition;
import com.xiaoliang.simukraft.job.api.JobPolicy;
import com.xiaoliang.simukraft.job.api.JobSchedule;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;
import net.minecraft.network.chat.Component;

public final class WarehouseManagerJobDefinition implements JobDefinition {
    private final WarehouseManagerTargetResolver targetResolver = new WarehouseManagerTargetResolver();
    private final WarehouseManagerWorkflow workflow = new WarehouseManagerWorkflow(targetResolver);

    @Override
    public JobType type() {
        return JobType.WAREHOUSE_MANAGER;
    }

    @Override
    public Component displayName() {
        return Component.translatable("job.simukraft.warehouse_manager");
    }

    @Override
    public JobSchedule schedule() {
        return JobSchedule.ALWAYS;
    }

    @Override
    public JobWorkflow workflow() {
        return workflow;
    }

    @Override
    public WorkTargetResolver<?> targetResolver() {
        return targetResolver;
    }

    @Override
    public JobPolicy policy() {
        return new JobPolicy(true, false, false, 3, 40);
    }
}
