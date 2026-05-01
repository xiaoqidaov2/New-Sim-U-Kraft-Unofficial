package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.job.api.JobDefinition;
import com.xiaoliang.simukraft.job.api.JobPolicy;
import com.xiaoliang.simukraft.job.api.JobSchedule;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;
import net.minecraft.network.chat.Component;

public final class PlannerJobDefinition implements JobDefinition {
    private final PlannerTargetResolver targetResolver = new PlannerTargetResolver();
    private final PlannerWorkflow workflow = new PlannerWorkflow(targetResolver);

    @Override
    public JobType type() {
        return JobType.PLANNER;
    }

    @Override
    public Component displayName() {
        return Component.translatable("job.simukraft.planner");
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
