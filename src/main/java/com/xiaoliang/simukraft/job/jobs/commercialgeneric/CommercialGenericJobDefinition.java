package com.xiaoliang.simukraft.job.jobs.commercialgeneric;

import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.job.api.JobDefinition;
import com.xiaoliang.simukraft.job.api.JobPolicy;
import com.xiaoliang.simukraft.job.api.JobSchedule;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;
import net.minecraft.network.chat.Component;

public final class CommercialGenericJobDefinition implements JobDefinition {
    private final CommercialGenericTargetResolver targetResolver = new CommercialGenericTargetResolver();
    private final CommercialGenericWorkflow workflow = new CommercialGenericWorkflow(targetResolver);

    @Override
    public JobType type() {
        return JobType.COMMERCIAL_GENERIC;
    }

    @Override
    public Component displayName() {
        return Component.translatable("job.simukraft.commercial_generic");
    }

    @Override
    public JobSchedule schedule() {
        return new JobSchedule(2000, 10000);
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
