package com.xiaoliang.simukraft.job.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public interface WorkTarget {
    ServerLevel level();

    BlockPos mainPos();

    boolean isValid();
}
