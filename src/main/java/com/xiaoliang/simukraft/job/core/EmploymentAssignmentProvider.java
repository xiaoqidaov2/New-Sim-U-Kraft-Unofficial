package com.xiaoliang.simukraft.job.core;

import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import net.minecraft.server.MinecraftServer;

import java.util.Collection;

public interface EmploymentAssignmentProvider {
    Collection<EmploymentAssignment> loadAssignments(MinecraftServer server);

    static EmploymentAssignmentProvider legacy() {
        return server -> server == null ? java.util.List.of() : EmploymentServices.get(server).listByCity(null);
    }
}
