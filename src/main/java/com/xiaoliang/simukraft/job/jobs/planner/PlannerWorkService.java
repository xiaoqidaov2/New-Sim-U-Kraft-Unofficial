package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.core.services.AbstractWorkService;
import com.xiaoliang.simukraft.planning.PlanningTask;
import com.xiaoliang.simukraft.planning.PlanningTaskManager;
import com.xiaoliang.simukraft.world.BuildBoxHiredData;
import com.xiaoliang.simukraft.world.PlanningTaskData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlannerWorkService extends AbstractWorkService {

    public static final PlannerWorkService INSTANCE = new PlannerWorkService();

    public void startDailyWork(ServerLevel level) {
        if (level == null) return;

        long dayTime = level.getDayTime() % 24000L;
        boolean isWorkTime = dayTime >= 0 && dayTime < 12000;
        if (!isWorkTime) {
            return;
        }

        MinecraftServer server = level.getServer();
        Map<BlockPos, UUID> hiredPlanners = BuildBoxHiredData.loadHiredPlanners(server);

        if (hiredPlanners.isEmpty()) {
            return;
        }

        var employmentService = com.xiaoliang.simukraft.employment.service.EmploymentServices.get(server);
        String dimensionId = level.dimension().location().toString();

        for (Map.Entry<BlockPos, UUID> entry : hiredPlanners.entrySet()) {
            BlockPos buildBoxPos = entry.getKey();
            UUID npcUuid = entry.getValue();
            CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);

            if (npc == null) {
                continue;
            }

            var existingAssignment = employmentService.findByNpc(npcUuid);
            if (existingAssignment.isEmpty()) {
                var allAssignments = employmentService.listByCity(null);
                boolean hasOtherJob = allAssignments.stream()
                        .filter(a -> a.npcUuid().equals(npcUuid))
                        .filter(a -> a.jobType() != com.xiaoliang.simukraft.employment.domain.JobType.PLANNER)
                        .anyMatch(a -> a.status() == com.xiaoliang.simukraft.employment.domain.EmploymentStatus.ASSIGNED);
                if (!hasOtherJob) {
                    var hireResult = employmentService.hire(new com.xiaoliang.simukraft.employment.service.EmploymentCommands.HireCommand(
                        npcUuid,
                        dimensionId,
                        buildBoxPos,
                        com.xiaoliang.simukraft.employment.domain.WorkBlockType.BUILD_BOX,
                        com.xiaoliang.simukraft.employment.domain.JobType.PLANNER
                    ));
                    if (!hireResult.success()) {
                        Simukraft.LOGGER.warn("[PlannerWorkService] 创建v2雇佣记录失败 - NPC: {}, 原因: {}",
                            npcUuid.toString().substring(0, 8), hireResult.message());
                    }
                }
            }

            restoreWorkState(npc, npcUuid, level);
            restorePlanningTaskIfNeeded(server, level, npc);
        }
    }

    private void restorePlanningTaskIfNeeded(MinecraftServer server, ServerLevel level, CustomEntity npc) {
        PlanningTaskManager taskManager = PlanningTaskManager.get(level);
        PlanningTask existingTask = taskManager.getActiveTaskByNpc(npc.getUUID());

        if (existingTask != null) {
            return;
        }

        PlanningTaskData.TaskInfo taskInfo = PlanningTaskData.loadTaskByNpc(server, npc.getUUID());
        if (taskInfo == null) {
            return;
        }

        try {
            Map<BlockPos, UUID> hiredPlanners = BuildBoxHiredData.loadHiredPlanners(server);
            boolean isStillHired = hiredPlanners.values().stream()
                    .anyMatch(uuid -> uuid.equals(npc.getUUID()));

            if (!isStillHired) {
                PlanningTaskData.removeTaskByNpc(server, npc.getUUID());
                return;
            }

            BlockPos taskBuildBoxPos = taskInfo.buildBoxPos;
            if (taskBuildBoxPos == null) {
                PlanningTaskData.removeTaskByNpc(server, npc.getUUID());
                return;
            }

            BlockState buildBoxState = level.getBlockState(taskBuildBoxPos);
            if (buildBoxState.isAir()) {
                PlanningTaskData.removeTaskByNpc(server, npc.getUUID());
                return;
            }

            if ("COMPLETED".equals(taskInfo.status) || "CANCELLED".equals(taskInfo.status)) {
                PlanningTaskData.removeTaskByNpc(server, npc.getUUID());
                return;
            }

            if (taskInfo.targetBlocks == null || taskInfo.currentBlockIndex >= taskInfo.targetBlocks.size() || taskInfo.taskType == null) {
                PlanningTaskData.removeTaskByNpc(server, npc.getUUID());
                return;
            }

            PlanningTask.TaskType taskType = PlanningTask.TaskType.valueOf(taskInfo.taskType);
            PlanningTask task = new PlanningTask(npc.getUUID(), taskBuildBoxPos, taskType, taskInfo.targetBlocks);
            task.setTaskId(taskInfo.taskId);
            task.setStatus("IN_PROGRESS".equals(taskInfo.status) ? PlanningTask.TaskStatus.IN_PROGRESS : PlanningTask.TaskStatus.PENDING);
            task.setCurrentBlockIndex(taskInfo.currentBlockIndex);

            if (taskInfo.targetBlockId != null) {
                task.setTargetBlockId(taskInfo.targetBlockId);
            }
            if (taskInfo.replacementMap != null && !taskInfo.replacementMap.isEmpty()) {
                task.setReplacementMap(taskInfo.replacementMap);
            }

            taskManager.addTask(task);

            double distance = npc.position().distanceTo(new net.minecraft.world.phys.Vec3(
                    taskBuildBoxPos.getX() + 0.5,
                    taskBuildBoxPos.getY(),
                    taskBuildBoxPos.getZ() + 0.5
            ));
            if (distance > 3.0) {
                npc.scheduleHireArrivalTeleport(taskBuildBoxPos);
            }

            Simukraft.LOGGER.info("[PlannerWorkService] 成功恢复规划任务 - NPC: {}, 类型: {}, 进度: {}/{}",
                npc.getUUID().toString().substring(0, 8),
                taskInfo.taskType,
                taskInfo.currentBlockIndex,
                taskInfo.targetBlocks.size());
        } catch (Exception e) {
            Simukraft.LOGGER.error("[PlannerWorkService] 恢复规划任务失败 - NPC: {}",
                npc.getUUID().toString().substring(0, 8), e);
        }
    }

    public void savePlanningTask(MinecraftServer server, PlanningTask task) {
        if (server == null || task == null) return;

        try {
            UUID taskId = task.getTaskId();
            UUID npcId = task.getNpcId();
            BlockPos buildBoxPos = task.getBuildBoxPos();
            PlanningTask.TaskType type = task.getType();
            PlanningTask.TaskStatus status = task.getStatus();
            List<BlockPos> targetBlocks = task.getTargetBlocks();

            if (taskId == null || npcId == null || buildBoxPos == null || type == null || status == null || targetBlocks == null) {
                return;
            }

            PlanningTaskData.TaskInfo taskInfo = new PlanningTaskData.TaskInfo(
                taskId,
                npcId,
                buildBoxPos,
                type.name(),
                status.name(),
                targetBlocks,
                task.getCompletedBlocks(),
                task.getTargetBlockId(),
                task.getReplacementMap(),
                task.getCreateTime()
            );

            PlanningTaskData.saveTask(server, taskInfo);
        } catch (Exception e) {
            Simukraft.LOGGER.error("[PlannerWorkService] 保存规划任务失败 - Task: {}",
                task.getTaskId() != null ? task.getTaskId().toString().substring(0, 8) : "null", e);
        }
    }

    public void removePlanningTask(MinecraftServer server, UUID taskId) {
        if (server == null || taskId == null) return;
        PlanningTaskData.removeTask(server, taskId);
    }

    public void removePlanningTaskByNpc(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) return;
        PlanningTaskData.removeTaskByNpc(server, npcUuid);
    }

    @Override
    public void restoreWorkState(CustomEntity npc, UUID npcUuid, ServerLevel level) {
        if (npc == null || npcUuid == null || level == null) return;

        if (!"planner".equals(npc.getJob())) {
            npc.setJob("planner");
        }
        if (npc.getWorkStatus() != WorkStatus.WORKING) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
            npc.setWorking(true);
        }
    }

    @Override
    public void handleContinuousWork(JobContext context) {
        if (context == null || context.level() == null) return;

        ServerLevel level = context.level();
        MinecraftServer server = level.getServer();
        UUID npcUuid = context.assignment().npcUuid();
        CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);

        if (npc == null || !npc.isAlive() || !"planner".equals(npc.getJob())) {
            return;
        }

        if (!dataInitialized) {
            dataInitialized = true;
        }

        restoreWorkState(npc, npcUuid, level);
        restorePlanningTaskIfNeeded(server, level, npc);

        PlanningTask task = PlanningTaskManager.get(level).getActiveTaskByNpc(npcUuid);
        if (task != null) {
            savePlanningTask(server, task);
        }
    }

    @Override
    protected void onServerStart0(MinecraftServer server, ServerLevel level) {
    }

    @Override
    protected void onServerStop0(ServerLevel level) {
    }

    @Override
    protected void handleDailyXp0(ServerLevel level) {
    }
}
