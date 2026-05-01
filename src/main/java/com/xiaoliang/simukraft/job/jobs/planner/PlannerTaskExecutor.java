package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.planning.PlanningTask;
import net.minecraft.server.level.ServerLevel;

public interface PlannerTaskExecutor {
    void process(ServerLevel level, PlannerWorkController controller, PlanningTask task);
}
