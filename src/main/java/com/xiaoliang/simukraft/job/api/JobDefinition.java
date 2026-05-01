package com.xiaoliang.simukraft.job.api;

import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.job.core.DefaultRestWorkflow;
import net.minecraft.network.chat.Component;

public interface JobDefinition {
    JobType type();

    Component displayName();

    JobSchedule schedule();

    JobWorkflow workflow();

    WorkTargetResolver<?> targetResolver();

    default RestWorkflow restWorkflow() {
        return DefaultRestWorkflow.INSTANCE;
    }

    default JobPolicy policy() {
        return JobPolicy.DEFAULT;
    }
}
