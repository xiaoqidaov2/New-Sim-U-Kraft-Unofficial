package com.xiaoliang.simukraft.job.jobs.industrialgeneric;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobWorkflow;
import com.xiaoliang.simukraft.utils.NPCRestHandler;
import com.xiaoliang.simukraft.utils.NPCWorkResumeCoordinator;
import com.xiaoliang.simukraft.world.IndustrialHiredData;
import net.minecraft.core.BlockPos;

public final class IndustrialGenericWorkflow implements JobWorkflow {
    private final IndustrialGenericTargetResolver targetResolver;
    private final IndustrialGenericWorkService workService;

    public IndustrialGenericWorkflow(IndustrialGenericTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
        this.workService = new IndustrialGenericWorkService();
    }

    @Override
    public boolean canWork(JobContext context) {
        return targetResolver.resolve(context)
                .map(IndustrialGenericWorkTarget::isValid)
                .orElse(false);
    }

    @Override
    public void onStartWork(JobContext context) {
        CustomEntity npc = context.npc();
        IndustrialGenericWorkTarget target = targetResolver.resolve(context).orElse(null);

        if (npc != null && target != null) {
            String assignedJob = resolveAssignedJob(context, npc, target);
            if (assignedJob != null && !assignedJob.isBlank()) {
                npc.setJob(assignedJob);
            }
            NPCWorkResumeCoordinator.resumeIndustrialWork(
                    npc,
                    target.level(),
                    target.factoryPos(),
                    resolveBuildingFileName(target)
            );
        }
    }

    @Override
    public void onStopWork(JobContext context) {
        CustomEntity npc = context.npc();
        if (npc != null) {
            NPCRestHandler.startResting(npc, context.level());
        }
    }

    @Override
    public JobResult tick(JobContext context) {
        return targetResolver.resolve(context)
                .filter(IndustrialGenericWorkTarget::isValid)
                .map(target -> {
                    CustomEntity npc = context.npc();

                    if (npc != null) {
                        ensureWorkState(context, npc, target);
                        workService.handleContinuousWork(context);
                        handleIndustrialWork(npc, target);
                    }

                    return JobResult.success();
                })
                .orElseGet(() -> JobResult.paused("missing_industrial_target"));
    }

    private void ensureWorkState(JobContext context, CustomEntity npc, IndustrialGenericWorkTarget target) {
        String assignedJob = resolveAssignedJob(context, npc, target);
        if (assignedJob != null && !assignedJob.isBlank() && !assignedJob.equals(npc.getJob())) {
            npc.setJob(assignedJob);
        }
        NPCWorkResumeCoordinator.resumeIndustrialWork(
                npc,
                target.level(),
                target.factoryPos(),
                resolveBuildingFileName(target)
        );
    }

    private String resolveAssignedJob(JobContext context, CustomEntity npc, IndustrialGenericWorkTarget target) {
        String hiredJobType = resolveHiredJobType(context, target);
        if (hiredJobType != null && !hiredJobType.isBlank()) {
            return hiredJobType;
        }
        String currentJob = npc.getJob();
        if (currentJob != null && !currentJob.isBlank()) {
            return currentJob;
        }
        return "industrial";
    }

    private String resolveHiredJobType(JobContext context, IndustrialGenericWorkTarget target) {
        if (context.server() == null || target == null) {
            return null;
        }
        if (context.assignment() != null && context.assignment().workplacePos() != null) {
            String assignmentJobType = IndustrialHiredData.getJobType(context.server(), context.assignment().workplacePos());
            if (assignmentJobType != null && !assignmentJobType.isBlank()) {
                return assignmentJobType;
            }
        }
        return IndustrialHiredData.getJobType(context.server(), target.mainPos());
    }

    private void handleIndustrialWork(CustomEntity npc, IndustrialGenericWorkTarget target) {
        moveNpcToWorkplace(npc, target.factoryPos());
    }

    private void moveNpcToWorkplace(CustomEntity npc, BlockPos workPos) {
        double targetX = workPos.getX() + 0.5D;
        double targetY = workPos.getY() + 1.0D;
        double targetZ = workPos.getZ() + 0.5D;

        if (!npc.moveToWithNewPathfinder(targetX, targetY, targetZ, 1.0D)) {
            npc.scheduleHireArrivalTeleport(workPos);
        }
    }

    private String resolveBuildingFileName(IndustrialGenericWorkTarget target) {
        return IndustrialWorkHandler.getBuildingFileName(target.level(), target.mainPos());
    }
}
