package com.xiaoliang.simukraft.job.jobs.farmer;

import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;
import com.xiaoliang.simukraft.utils.FarmlandManager;
import com.xiaoliang.simukraft.world.FarmlandHiredData;

import java.util.Optional;

public final class FarmerTargetResolver implements WorkTargetResolver<FarmerWorkTarget> {
    @Override
    public Optional<FarmerWorkTarget> resolve(JobContext context) {
        if (context == null || context.level() == null || context.assignment() == null) {
            return Optional.empty();
        }
        var boxPos = context.assignment().workplacePos();
        var plot = FarmlandHiredData.getSelectedPlot(boxPos);
        var chestPos = FarmlandManager.getBoundChestIfValid(context.level(), boxPos);
        return Optional.of(new FarmerWorkTarget(context.level(), boxPos, plot, chestPos));
    }
}
