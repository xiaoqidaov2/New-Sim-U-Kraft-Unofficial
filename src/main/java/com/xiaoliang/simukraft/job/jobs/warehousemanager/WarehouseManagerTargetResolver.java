package com.xiaoliang.simukraft.job.jobs.warehousemanager;

import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;

import java.util.Optional;

public final class WarehouseManagerTargetResolver implements WorkTargetResolver<WarehouseManagerWorkTarget> {
    @Override
    public Optional<WarehouseManagerWorkTarget> resolve(JobContext context) {
        var assignment = context.assignment();
        var level = context.level();
        if (assignment == null || assignment.workplacePos() == null || level == null) {
            return Optional.empty();
        }
        return Optional.of(new WarehouseManagerWorkTarget(level, assignment.workplacePos(), assignment.workplacePos()));
    }
}
