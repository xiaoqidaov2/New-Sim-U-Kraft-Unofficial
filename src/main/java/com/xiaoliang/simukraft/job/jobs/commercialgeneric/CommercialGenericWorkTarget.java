package com.xiaoliang.simukraft.job.jobs.commercialgeneric;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.job.api.WorkTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record CommercialGenericWorkTarget(
        ServerLevel level,
        BlockPos mainPos,
        BlockPos buildingPos,
        CommercialBuildingConfig buildingConfig
) implements WorkTarget {
    @Override
    public boolean isValid() {
        return level != null && mainPos != null && buildingPos != null;
    }
}
