package com.xiaoliang.simukraft.job.jobs.builder;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.utils.NPCWorkResumeCoordinator;
import net.minecraft.server.level.ServerLevel;

public final class BuilderWorkflow implements JobWorkflow {
    private final BuilderTargetResolver targetResolver;

    public BuilderWorkflow(BuilderTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
    }

    @Override
    public boolean canWork(JobContext context) {
        return targetResolver.resolve(context)
                .map(BuilderWorkTarget::isValid)
                .orElse(false);
    }

    @Override
    public void onStartWork(JobContext context) {
        CustomEntity npc = context.npc();
        BuilderWorkTarget target = targetResolver.resolve(context).orElse(null);

        if (npc != null && target != null) {
            npc.setJob("builder");

            boolean hasActiveTask = target.hasActiveTask();
            NPCWorkResumeCoordinator.resumeBuilderWork(npc, target.buildBoxPos(), hasActiveTask);

            Simukraft.LOGGER.info("[BuilderWorkflow] 建筑师开始工?- NPC: {}, 建筑? {}",
                    npc.getFullName(), target.buildBoxPos());
        }
    }

    @Override
    public JobResult tick(JobContext context) {
        return targetResolver.resolve(context)
                .filter(BuilderWorkTarget::isValid)
                .map(target -> {
                    CustomEntity npc = context.npc();
                    ServerLevel level = context.level();

                    if (npc != null && level != null) {
                        ensureWorkState(npc);

                        if (target.hasActiveTask()) {
                            ConstructionTask task = target.constructionTask();
                            if (task != null && task.isCompleted()) {
                                handleTaskCompleted(npc, target.buildBoxPos(), level);
                                return JobResult.success();
                            }
                        }
                    }

                    return JobResult.success();
                })
                .orElseGet(() -> JobResult.paused("missing_builder_target"));
    }

    private void ensureWorkState(CustomEntity npc) {
        if (!"builder".equals(npc.getJob())) {
            npc.setJob("builder");
        }
    }

    private void handleTaskCompleted(CustomEntity npc, net.minecraft.core.BlockPos buildBoxPos, ServerLevel level) {
        Simukraft.LOGGER.info("[BuilderWorkflow] 建造任务完?- NPC: {}", npc.getFullName());
    }
}
