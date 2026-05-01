package com.xiaoliang.simukraft.job.api;

import java.util.Optional;

public interface WorkTargetResolver<T extends WorkTarget> {
    Optional<T> resolve(JobContext context);
}
