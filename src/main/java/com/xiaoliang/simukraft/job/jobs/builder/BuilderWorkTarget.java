package com.xiaoliang.simukraft.job.jobs.builder;

import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.job.api.WorkTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record BuilderWorkTarget(
        ServerLevel level,
        BlockPos mainPos,
        BlockPos buildBoxPos,
        ConstructionTask constructionTask
) implements WorkTarget {
    @Override
    public boolean isValid() {
        return level != null && mainPos != null && buildBoxPos != null;
    }

    public boolean hasActiveTask() {
        return constructionTask != null && !constructionTask.isCompleted() && constructionTask.hasNextBlock();
    }
}
