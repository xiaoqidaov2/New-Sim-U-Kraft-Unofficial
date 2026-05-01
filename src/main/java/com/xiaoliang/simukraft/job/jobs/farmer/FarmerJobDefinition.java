package com.xiaoliang.simukraft.job.jobs.farmer;

import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.job.api.JobDefinition;
import com.xiaoliang.simukraft.job.api.JobPolicy;
import com.xiaoliang.simukraft.job.api.JobSchedule;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;
import net.minecraft.network.chat.Component;

public final class FarmerJobDefinition implements JobDefinition {
    private final FarmerTargetResolver targetResolver = new FarmerTargetResolver();
    private final FarmerWorkflow workflow = new FarmerWorkflow(targetResolver);

    @Override
    public JobType type() {
        return JobType.FARMER;
    }

    @Override
    public Component displayName() {
        return Component.translatable("job.simukraft.farmer");
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
