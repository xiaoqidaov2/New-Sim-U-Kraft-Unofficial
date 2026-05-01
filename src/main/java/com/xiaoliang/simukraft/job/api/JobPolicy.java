package com.xiaoliang.simukraft.job.api;

public record JobPolicy(
        boolean requiresWorkplace,
        boolean requiresResidence,
        boolean canWorkAtNight,
        int maxRetryCount,
        int tickInterval
) {
    public static final JobPolicy DEFAULT = new JobPolicy(true, false, false, 3, 20);
}
