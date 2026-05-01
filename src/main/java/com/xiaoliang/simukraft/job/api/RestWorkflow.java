package com.xiaoliang.simukraft.job.api;

public interface RestWorkflow {
    JobResult tickRest(JobContext context);

    default void tickGoToWork(JobContext context) {}

    default void tickGoHome(JobContext context) {}
}
