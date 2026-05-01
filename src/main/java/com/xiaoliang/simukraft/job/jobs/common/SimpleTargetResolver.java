package com.xiaoliang.simukraft.job.jobs.common;

import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;

import java.util.Optional;

public final class SimpleTargetResolver implements WorkTargetResolver<SimpleWorkTarget> {
    @Override
    public Optional<SimpleWorkTarget> resolve(JobContext context) {
        var assignment = context.assignment();
        var level = context.level();
        if (assignment == null || assignment.workplacePos() == null || level == null) {
            return Optional.empty();
        }
        return Optional.of(new SimpleWorkTarget(level, assignment.workplacePos(), true));
    }
}
