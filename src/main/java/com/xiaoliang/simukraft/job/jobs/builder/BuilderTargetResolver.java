package com.xiaoliang.simukraft.job.jobs.builder;

import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.WorkTargetResolver;

import java.util.Optional;

public final class BuilderTargetResolver implements WorkTargetResolver<BuilderWorkTarget> {
    @Override
    public Optional<BuilderWorkTarget> resolve(JobContext context) {
        var assignment = context.assignment();
        var level = context.level();
        if (assignment == null || assignment.workplacePos() == null || level == null) {
            return Optional.empty();
        }

        CustomEntity npc = context.npc();
        ConstructionTask task = null;
        if (npc != null) {
            task = npc.getConstructionTask();
        }

        return Optional.of(new BuilderWorkTarget(level, assignment.workplacePos(), assignment.workplacePos(), task));
    }
}
