package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;

import java.util.Optional;

public final class PlannerTargetResolver implements WorkTargetResolver<PlannerWorkTarget> {
    @Override
    public Optional<PlannerWorkTarget> resolve(JobContext context) {
        var assignment = context.assignment();
        var level = context.level();
        if (assignment == null || assignment.workplacePos() == null || level == null) {
            return Optional.empty();
        }
        return Optional.of(new PlannerWorkTarget(level, assignment.workplacePos(), assignment.workplacePos()));
    }
}
