package com.xiaoliang.simukraft.job.api;

import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public record JobContext(
        MinecraftServer server,
        ServerLevel level,
        CustomEntity npc,
        EmploymentAssignment assignment,
        JobDefinition definition,
        long dayTime
) {
    public boolean hasLoadedNpc() {
        return npc != null && npc.isAlive();
    }
}
