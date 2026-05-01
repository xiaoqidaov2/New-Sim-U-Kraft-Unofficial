package com.xiaoliang.simukraft.job.core;

import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.RestWorkflow;

public final class DefaultRestWorkflow implements RestWorkflow {
    public static final DefaultRestWorkflow INSTANCE = new DefaultRestWorkflow();

    private DefaultRestWorkflow() {
    }

    @Override
    public JobResult tickRest(JobContext context) {
        if (context == null || !context.hasLoadedNpc()) {
            return JobResult.paused("npc_unloaded");
        }
        return JobResult.success();
    }
}
