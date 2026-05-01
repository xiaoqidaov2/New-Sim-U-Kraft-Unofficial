package com.xiaoliang.simukraft.job.jobs.industrialgeneric;

import com.xiaoliang.simukraft.job.api.WorkTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record IndustrialGenericWorkTarget(
        ServerLevel level,
        BlockPos mainPos,
        BlockPos factoryPos
) implements WorkTarget {
    @Override
    public boolean isValid() {
        return level != null && mainPos != null && factoryPos != null;
    }
}
