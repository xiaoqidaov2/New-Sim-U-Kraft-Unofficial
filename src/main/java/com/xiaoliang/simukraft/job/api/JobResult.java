package com.xiaoliang.simukraft.job.api;

public record JobResult(JobResultType type, String reason, int cooldownTicks) {
    public static JobResult success() {
        return new JobResult(JobResultType.SUCCESS, "", 20);
    }

    public static JobResult paused(String reason) {
        return new JobResult(JobResultType.PAUSED, reason == null ? "" : reason, 100);
    }

    public static JobResult blocked(String reason) {
        return new JobResult(JobResultType.BLOCKED, reason == null ? "" : reason, 60);
    }

    public static JobResult invalid(String reason) {
        return new JobResult(JobResultType.INVALID, reason == null ? "" : reason, 0);
    }
}
