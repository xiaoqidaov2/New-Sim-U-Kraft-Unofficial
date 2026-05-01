package com.xiaoliang.simukraft.job.jobs.industrialgeneric;

import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.job.api.JobDefinition;
import com.xiaoliang.simukraft.job.api.JobPolicy;
import com.xiaoliang.simukraft.job.api.JobSchedule;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;
import net.minecraft.network.chat.Component;

public final class IndustrialGenericJobDefinition implements JobDefinition {
    private final IndustrialGenericTargetResolver targetResolver = new IndustrialGenericTargetResolver();
    private final IndustrialGenericWorkflow workflow = new IndustrialGenericWorkflow(targetResolver);

    @Override
    public JobType type() {
        return JobType.INDUSTRIAL_GENERIC;
    }

    @Override
    public Component displayName() {
        return Component.translatable("job.simukraft.industrial_generic");
    }

    @Override
    public JobSchedule schedule() {
        return new JobSchedule(0, 12000);
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
