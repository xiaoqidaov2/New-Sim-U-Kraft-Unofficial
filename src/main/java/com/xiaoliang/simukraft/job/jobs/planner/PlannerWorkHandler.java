package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.planning.PlanningTask;
import com.xiaoliang.simukraft.planning.PlanningTaskManager;
import com.xiaoliang.simukraft.utils.LunchBreakManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class PlannerWorkHandler {

    private final CustomEntity npc;
    private final UUID npcId;
    private final PlannerWorkController controller;
    private final Map<PlanningTask.TaskType, PlannerTaskExecutor> executors = new EnumMap<>(PlanningTask.TaskType.class);
    private PlanningTask currentTask;
    private int restCheckTimer;

    public PlannerWorkHandler(CustomEntity npc) {
        this.npc = npc;
        this.npcId = npc.getUUID();
        this.controller = new PlannerWorkController(npc);
        executors.put(PlanningTask.TaskType.REMOVE, new PlannerRemoveTaskExecutor());
        executors.put(PlanningTask.TaskType.REPLACE, new PlannerReplaceTaskExecutor());
        executors.put(PlanningTask.TaskType.FILL, new PlannerFillTaskExecutor());
    }

    public void tick(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        if (LunchBreakManager.isLunchBreakTime(serverLevel.getDayTime())) {
            return;
        }

        restorePlannerWorkState();
        checkRest(serverLevel);

        if (isNPCResting()) {
            return;
        }

        if (currentTask == null || currentTask.getStatus() != PlanningTask.TaskStatus.IN_PROGRESS) {
            currentTask = PlanningTaskManager.get(level).getActiveTaskByNpc(npcId);
            if (currentTask == null) return;
        }

        PlannerTaskExecutor executor = executors.get(currentTask.getType());
        if (executor == null) {
            currentTask.setStatus(PlanningTask.TaskStatus.CANCELLED);
            controller.completeTask(serverLevel, currentTask);
            currentTask = null;
            return;
        }

        executor.process(serverLevel, controller, currentTask);
        if (currentTask.getStatus() != PlanningTask.TaskStatus.IN_PROGRESS) {
            currentTask = null;
        }
    }

    private void restorePlannerWorkState() {
        if (npc.getWorkStatus() == WorkStatus.IDLE) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(WorkSubState.WORKING);
            npc.setWorking(true);
        }
    }

    private void checkRest(ServerLevel level) {
        restCheckTimer++;
        if (restCheckTimer >= ServerConfig.getPlannerRestCheckInterval()) {
            restCheckTimer = 0;
        }
    }

    private boolean isNPCResting() {
        return npc.getWorkSubState() == WorkSubState.RESTING;
    }

    public boolean isWorking() {
        return currentTask != null && currentTask.getStatus() == PlanningTask.TaskStatus.IN_PROGRESS;
    }

    public boolean hasActiveTask() {
        return currentTask != null && currentTask.getStatus() == PlanningTask.TaskStatus.IN_PROGRESS;
    }

    public float getProgress() {
        if (currentTask == null) return 0f;
        return currentTask.getProgress();
    }

    public PlanningTask getCurrentTask() {
        return currentTask;
    }
}
