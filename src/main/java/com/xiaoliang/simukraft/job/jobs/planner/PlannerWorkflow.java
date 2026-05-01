package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.planning.PlanningTask;
import com.xiaoliang.simukraft.planning.PlanningTaskManager;

public final class PlannerWorkflow implements JobWorkflow {
    private final PlannerTargetResolver targetResolver;
    private final PlannerWorkService workService;

    public PlannerWorkflow(PlannerTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
        this.workService = PlannerWorkService.INSTANCE;
    }

    @Override
    public boolean canWork(JobContext context) {
        return targetResolver.resolve(context)
                .map(PlannerWorkTarget::isValid)
                .orElse(false);
    }

    @Override
    public void onStartWork(JobContext context) {
        CustomEntity npc = context.npc();
        if (npc != null) {
            npc.setJob("planner");
            workService.restoreWorkState(npc, context.assignment().npcUuid(), context.level());
        }
    }

    @Override
    public JobResult tick(JobContext context) {
        return targetResolver.resolve(context)
                .filter(PlannerWorkTarget::isValid)
                .map(target -> {
                    CustomEntity npc = context.npc();

                    if (npc != null) {
                        ensureWorkState(npc);
                        workService.handleContinuousWork(context);
                        checkPlanningTask(context, target);
                    }

                    return JobResult.success();
                })
                .orElseGet(() -> JobResult.paused("missing_planner_target"));
    }

    private void ensureWorkState(CustomEntity npc) {
        if (!"planner".equals(npc.getJob())) {
            npc.setJob("planner");
        }
        if (npc.getWorkStatus() != WorkStatus.WORKING) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorking(true);
        }
    }

    private void checkPlanningTask(JobContext context, PlannerWorkTarget target) {
        CustomEntity npc = context.npc();
        if (npc == null) return;

        PlanningTaskManager taskManager = PlanningTaskManager.get(context.level());
        if (taskManager != null) {
            PlanningTask task = taskManager.getActiveTaskByNpc(npc.getUUID());
            if (task != null) {
                // 规划任务逻辑?PlanningTaskManager 处理
            }
        }
    }
}
