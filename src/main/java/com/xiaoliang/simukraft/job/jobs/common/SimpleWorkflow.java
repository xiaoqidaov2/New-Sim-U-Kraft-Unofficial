package com.xiaoliang.simukraft.job.jobs.common;

import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobWorkflow;

public final class SimpleWorkflow implements JobWorkflow {
    private final SimpleTargetResolver targetResolver;

    public SimpleWorkflow(SimpleTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
    }

    @Override
    public boolean canWork(JobContext context) {
        return targetResolver.resolve(context).isPresent();
    }

    @Override
    public JobResult tick(JobContext context) {
        return targetResolver.resolve(context)
                .map(target -> JobResult.success())
                .orElseGet(() -> JobResult.paused("missing_work_target"));
    }
}
