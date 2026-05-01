package com.xiaoliang.simukraft.job.jobs.common;

import com.xiaoliang.simukraft.job.api.WorkTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record SimpleWorkTarget(
        ServerLevel level,
        BlockPos mainPos,
        boolean valid
) implements WorkTarget {
    @Override
    public boolean isValid() {
        return level != null && mainPos != null && valid;
    }
}
