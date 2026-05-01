package com.xiaoliang.simukraft.job.jobs.industrialgeneric;

import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;

import java.util.Optional;

public final class IndustrialGenericTargetResolver implements WorkTargetResolver<IndustrialGenericWorkTarget> {
    @Override
    public Optional<IndustrialGenericWorkTarget> resolve(JobContext context) {
        var assignment = context.assignment();
        var level = context.level();
        if (assignment == null || assignment.workplacePos() == null || level == null) {
            return Optional.empty();
        }
        return Optional.of(new IndustrialGenericWorkTarget(level, assignment.workplacePos(), assignment.workplacePos()));
    }
}
