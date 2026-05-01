package com.xiaoliang.simukraft.job.jobs.farmer;

import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import com.xiaoliang.simukraft.job.api.WorkTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record FarmerWorkTarget(
        ServerLevel level,
        BlockPos mainPos,
        FarmlandPlot plot,
        BlockPos chestPos
) implements WorkTarget {
    @Override
    public boolean isValid() {
        return level != null && mainPos != null;
    }
}
