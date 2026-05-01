package com.xiaoliang.simukraft.job.jobs.warehousemanager;

import com.xiaoliang.simukraft.job.api.WorkTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record WarehouseManagerWorkTarget(
        ServerLevel level,
        BlockPos mainPos,
        BlockPos warehousePos
) implements WorkTarget {
    @Override
    public boolean isValid() {
        return level != null && mainPos != null && warehousePos != null;
    }
}
