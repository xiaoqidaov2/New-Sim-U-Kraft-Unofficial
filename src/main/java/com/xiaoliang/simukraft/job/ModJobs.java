package com.xiaoliang.simukraft.job;

import com.xiaoliang.simukraft.job.core.JobRegistry;
import com.xiaoliang.simukraft.job.jobs.builder.BuilderJobDefinition;
import com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialGenericJobDefinition;
import com.xiaoliang.simukraft.job.jobs.farmer.FarmerJobDefinition;
import com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialGenericJobDefinition;
import com.xiaoliang.simukraft.job.jobs.planner.PlannerJobDefinition;
import com.xiaoliang.simukraft.job.jobs.warehousemanager.WarehouseManagerJobDefinition;

public final class ModJobs {
    private static boolean initialized = false;

    private ModJobs() {
    }

    public static synchronized void register() {
        if (initialized) {
            return;
        }
        JobRegistry.register(new FarmerJobDefinition());
        JobRegistry.register(new WarehouseManagerJobDefinition());
        JobRegistry.register(new BuilderJobDefinition());
        JobRegistry.register(new PlannerJobDefinition());
        JobRegistry.register(new CommercialGenericJobDefinition());
        JobRegistry.register(new IndustrialGenericJobDefinition());
        initialized = true;
    }
}
