package com.xiaoliang.simukraft.job.jobs.commercialgeneric;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;
import java.util.Optional;

public final class CommercialGenericTargetResolver implements WorkTargetResolver<CommercialGenericWorkTarget> {
    @Override
    public Optional<CommercialGenericWorkTarget> resolve(JobContext context) {
        var assignment = context.assignment();
        var level = context.level();
        if (assignment == null || assignment.workplacePos() == null || level == null) {
            return Optional.empty();
        }

        String buildingFileName = CommercialWorkHandler.getBuildingFileName(level, assignment.workplacePos());
        CommercialBuildingConfig config = null;
        if (buildingFileName != null) {
            config = CommercialBuildingManager.getConfig(buildingFileName);
        }

        return Optional.of(new CommercialGenericWorkTarget(level, assignment.workplacePos(), assignment.workplacePos(), config));
    }
}
