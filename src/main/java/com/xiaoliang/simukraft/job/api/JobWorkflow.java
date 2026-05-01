package com.xiaoliang.simukraft.job.api;

public interface JobWorkflow {
    JobResult tick(JobContext context);

    default boolean canWork(JobContext context) {
        return true;
    }

    default void onStartWork(JobContext context) {
    }

    default void onStopWork(JobContext context) {
    }
}
