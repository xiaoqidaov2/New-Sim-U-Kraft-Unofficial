package com.xiaoliang.simukraft.job.core;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobDefinition;
import com.xiaoliang.simukraft.job.api.JobResult;
import com.xiaoliang.simukraft.job.api.JobResultType;
import com.xiaoliang.simukraft.job.api.JobRuntimeState;
import com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler;
import com.xiaoliang.simukraft.job.jobs.industrialgeneric.CheeseFactoryWorkController;
import com.xiaoliang.simukraft.utils.NPCDataManager;
import com.xiaoliang.simukraft.utils.NPCEntityLocator;
import com.xiaoliang.simukraft.utils.NPCRestHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class JobRuntimeService {
    private static final JobRuntimeService INSTANCE = new JobRuntimeService(EmploymentAssignmentProvider.legacy());
    private static final int WORKPLACE_CHECK_INTERVAL_TICKS = 100;
    private static final double WORKPLACE_PATHFIND_DISTANCE_SQR = 36.0D;
    private static final double WORKPLACE_DIRECT_RECOVER_TELEPORT_DISTANCE_SQR = 256.0D;

    private static final long STATE_SAVE_INTERVAL_TICKS = 6000L;
    private static final long ASSIGNMENT_CACHE_TTL_TICKS = 40L;
    private static final int MAX_ASSIGNMENTS_PER_TICK = 4;

    private final EmploymentAssignmentProvider assignmentProvider;
    private final JobRuntimeStateStore stateStore = new JobRuntimeStateStore();
    private final JobStateMachine stateMachine = new JobStateMachine();
    private final ConcurrentMap<UUID, CustomEntity> npcCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> lastWorkplaceCorrectionTicks = new ConcurrentHashMap<>();
    private final Set<UUID> activeWorkingNpcs = ConcurrentHashMap.newKeySet();
    private long lastStateSaveTick = Long.MIN_VALUE;
    private volatile List<EmploymentAssignment> cachedAssignments = List.of();
    private volatile long cachedAssignmentsTick = Long.MIN_VALUE;
    private int assignmentTickCursor;

    private JobRuntimeService(EmploymentAssignmentProvider assignmentProvider) {
        this.assignmentProvider = assignmentProvider;
    }

    public static JobRuntimeService get() {
        return INSTANCE;
    }

    public void onLevelLoad(ServerLevel level) {
        if (level == null) {
            return;
        }
        stateStore.loadFromLevel(level);
    }

    public void onServerStopping(ServerLevel level) {
        if (level == null) {
            return;
        }
        stateStore.saveToLevel(level);
        clearNpcCache();
        lastWorkplaceCorrectionTicks.clear();
        activeWorkingNpcs.clear();
    }

    public void tick(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        List<EmploymentAssignment> assignments = loadAssignmentsCached(server, level.getGameTime());
        tickAssignmentsBatch(server, level, assignments);

        // 周期性把状态机持久化到磁盘，避免崩溃丢失全部 JobRuntimeState（默认 5 分钟一次）。
        long currentTick = level.getGameTime();
        if (lastStateSaveTick == Long.MIN_VALUE) {
            lastStateSaveTick = currentTick;
        } else if (currentTick - lastStateSaveTick >= STATE_SAVE_INTERVAL_TICKS) {
            stateStore.saveToLevel(level);
            lastStateSaveTick = currentTick;
        }
    }

    public JobRuntimeState getState(UUID npcUuid) {
        return stateStore.get(npcUuid);
    }

    /**
     * 让外部（EmploymentService 写路径、命令等）在改写雇佣状态后立即让缓存失效，
     * 避免 TTL 期间出现不一致。
     */
    public void invalidateAssignmentCache() {
        cachedAssignmentsTick = Long.MIN_VALUE;
    }

    private List<EmploymentAssignment> loadAssignmentsCached(MinecraftServer server, long currentTick) {
        long lastTick = cachedAssignmentsTick;
        if (lastTick != Long.MIN_VALUE && currentTick - lastTick < ASSIGNMENT_CACHE_TTL_TICKS) {
            return cachedAssignments;
        }
        List<EmploymentAssignment> previousAssignments = cachedAssignments;
        Collection<EmploymentAssignment> loadedAssignments = assignmentProvider.loadAssignments(server);
        List<EmploymentAssignment> fresh = loadedAssignments == null ? List.of() : List.copyOf(loadedAssignments);
        cachedAssignments = fresh;
        cachedAssignmentsTick = currentTick;
        if (fresh.size() != previousAssignments.size() && assignmentTickCursor >= fresh.size()) {
            assignmentTickCursor = 0;
        }
        return cachedAssignments;
    }

    private void tickAssignmentsBatch(MinecraftServer server, ServerLevel level, List<EmploymentAssignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            assignmentTickCursor = 0;
            return;
        }
        int assignmentCount = assignments.size();
        if (assignmentTickCursor < 0 || assignmentTickCursor >= assignmentCount) {
            assignmentTickCursor = 0;
        }
        int workBudget = Math.min(MAX_ASSIGNMENTS_PER_TICK, assignmentCount);
        for (int i = 0; i < workBudget; i++) {
            EmploymentAssignment assignment = assignments.get(assignmentTickCursor);
            assignmentTickCursor = (assignmentTickCursor + 1) % assignmentCount;
            tickAssignment(server, level, assignment);
        }
    }

    private void tickAssignment(MinecraftServer server, ServerLevel level, EmploymentAssignment assignment) {
        if (assignment == null || !assignment.isAssigned()) {
            return;
        }
        Optional<JobDefinition> definition = JobRegistry.get(assignment.jobType());
        if (definition.isEmpty()) {
            stateStore.set(assignment.npcUuid(), JobRuntimeState.INVALID);
            return;
        }

        CustomEntity npc = findLoadedNpc(server, assignment.npcUuid());
        if (npc == null && NPCDataManager.getNPCNameByUUID(server, assignment.npcUuid()) == null) {
            stateStore.set(assignment.npcUuid(), JobRuntimeState.INVALID);
            return;
        }

        JobContext context = new JobContext(server, level, npc, assignment, definition.get(), level.getDayTime());
        JobRuntimeState currentState = stateStore.get(assignment.npcUuid());
        JobRuntimeState state = stateMachine.nextState(context, currentState);
        handleStateTransition(context, currentState, state);
        stateStore.set(assignment.npcUuid(), state);
        correctWorkplaceDrift(context, state);
        executeState(context, state);
    }

    private void handleStateTransition(JobContext context, JobRuntimeState previousState, JobRuntimeState nextState) {
        if (context == null || context.assignment() == null || context.definition() == null) {
            return;
        }
        UUID npcUuid = context.assignment().npcUuid();
        if (nextState == JobRuntimeState.WORKING) {
            if (activeWorkingNpcs.add(npcUuid) || previousState != JobRuntimeState.WORKING) {
                context.definition().workflow().onStartWork(context);
            }
            return;
        }
        if (activeWorkingNpcs.remove(npcUuid) || previousState == JobRuntimeState.WORKING) {
            if (nextState == JobRuntimeState.RESTING) {
                context.definition().workflow().onStopWork(context);
            }
        }
    }

    private void executeState(JobContext context, JobRuntimeState state) {
        try {
            if (state == JobRuntimeState.WORKING) {
                JobResult result = context.definition().workflow().tick(context);
                applyResult(context, result);
            } else if (state == JobRuntimeState.RESTING) {
                JobResult result = context.definition().restWorkflow().tickRest(context);
                applyResult(context, result);
            } else if (state == JobRuntimeState.GOING_TO_WORK) {
                context.definition().restWorkflow().tickGoToWork(context);
            } else if (state == JobRuntimeState.GOING_HOME) {
                context.definition().restWorkflow().tickGoHome(context);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[JobRuntimeService] 职业运行时执行失?job={}, npc={}", context.assignment().jobType(), context.assignment().npcUuid(), e);
            stateStore.set(context.assignment().npcUuid(), JobRuntimeState.BLOCKED);
        }
    }

    private void applyResult(JobContext context, JobResult result) {
        if (context == null || result == null) {
            return;
        }
        if (result.type() == JobResultType.PAUSED) {
            stateStore.set(context.assignment().npcUuid(), JobRuntimeState.PAUSED);
        } else if (result.type() == JobResultType.BLOCKED) {
            stateStore.set(context.assignment().npcUuid(), JobRuntimeState.BLOCKED);
        } else if (result.type() == JobResultType.INVALID) {
            stateStore.set(context.assignment().npcUuid(), JobRuntimeState.INVALID);
        }
    }

    private void correctWorkplaceDrift(JobContext context, JobRuntimeState state) {
        if (context == null || state != JobRuntimeState.WORKING || !context.hasLoadedNpc()) {
            return;
        }

        CustomEntity npc = context.npc();
        EmploymentAssignment assignment = context.assignment();
        if (npc == null || assignment == null || assignment.workplacePos() == null) {
            return;
        }
        if (npc.isTeleportingForWork()) {
            return;
        }
        if (npc.isSleeping() || npc.getWorkStatus() != WorkStatus.WORKING) {
            return;
        }
        WorkSubState subState = npc.getWorkSubState();
        if (subState == WorkSubState.RESTING
                || subState == WorkSubState.LUNCH_BREAK
                || subState == WorkSubState.BUYING_FOOD) {
            return;
        }
        // 让 NPCRestHandler 完整接管"回家/上班"传送链路。drift 兜底如果在去工作过程中插一脚，
        // 会与 stopResting 的传送、scheduleHireArrivalTeleport 的隐身倒计时打架，造成 NPC 消失。
        if (NPCRestHandler.isNpcInRestWorkflow(assignment.npcUuid())) {
            return;
        }

        long gameTime = context.level().getGameTime();
        Long lastCheck = lastWorkplaceCorrectionTicks.get(assignment.npcUuid());
        if (lastCheck != null && gameTime - lastCheck < WORKPLACE_CHECK_INTERVAL_TICKS) {
            return;
        }
        lastWorkplaceCorrectionTicks.put(assignment.npcUuid(), gameTime);

        ServerLevel serverLevel = context.level();
        String buildingFileName = IndustrialWorkHandler.getBuildingFileName(serverLevel, assignment.workplacePos());
        if (CheeseFactoryWorkController.shouldSuppressWorkplacePull(
                serverLevel, assignment.workplacePos(), npc, buildingFileName)) {
            return;
        }

        double targetX = assignment.workplacePos().getX() + 0.5D;
        double targetY = assignment.workplacePos().getY() + 1.0D;
        double targetZ = assignment.workplacePos().getZ() + 0.5D;
        double distanceSqr = npc.distanceToSqr(targetX, targetY, targetZ);
        if (distanceSqr <= WORKPLACE_PATHFIND_DISTANCE_SQR) {
            return;
        }

        // menglannnn: 如果已经在寻路中，不要干扰
        if (npc.isUsingCustomPathfinder()) {
            return;
        }

        // menglannnn: 远距离回岗直接走受控传送，避免先做大范围寻路计算卡主线程。
        if (distanceSqr >= WORKPLACE_DIRECT_RECOVER_TELEPORT_DISTANCE_SQR) {
            npc.scheduleHireArrivalTeleport(assignment.workplacePos());
            return;
        }

        // 中近距离优先寻路，只有极远且寻路失败时才走传送兜底。
        if (!npc.moveToWithNewPathfinder(assignment.workplacePos(), 2.0D)) {
            // menglannnn: 寻路开始就失败，立即走受控传送恢复位置（通常回控制盒）。
            npc.scheduleHireArrivalTeleport(assignment.workplacePos());
        }
    }

    private CustomEntity findLoadedNpc(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) {
            return null;
        }
        
        CustomEntity cached = npcCache.get(npcUuid);
        if (cached != null && cached.isAlive() && !cached.isRemoved()) {
            return cached;
        }

        CustomEntity restoredNpc = NPCEntityLocator.findNpc(server, npcUuid, true);
        if (restoredNpc != null) {
            npcCache.put(npcUuid, restoredNpc);
            return restoredNpc;
        }

        npcCache.remove(npcUuid);
        return null;
    }
    
    public void invalidateNpcCache(UUID npcUuid) {
        npcCache.remove(npcUuid);
    }
    
    public void clearNpcCache() {
        npcCache.clear();
    }
}
