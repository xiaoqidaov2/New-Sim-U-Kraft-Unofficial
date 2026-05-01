package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.job.api.WorkTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record PlannerWorkTarget(
        ServerLevel level,
        BlockPos mainPos,
        BlockPos buildBoxPos
) implements WorkTarget {
    @Override
    public boolean isValid() {
        return level != null && mainPos != null && buildBoxPos != null;
    }
}
