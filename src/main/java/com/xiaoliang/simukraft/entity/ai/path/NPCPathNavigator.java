package com.xiaoliang.simukraft.entity.ai.path;

import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NPC路径导航器（menglannnn: 管理NPC的寻路和移动）
 */
@SuppressWarnings("null")
public class NPCPathNavigator {
    
    private final CustomEntity npc;
    private final NPCPathFinder pathFinder;
    private final NPCMoveController moveController;
    
    private static final ExecutorService pathfindingExecutor = Executors.newFixedThreadPool(2);
    private static final int MAX_PATH_RECALCULATIONS_PER_TICK = 2;
    private static final ConcurrentHashMap<Long, AtomicInteger> PATH_RECALCULATION_BUDGETS = new ConcurrentHashMap<>();
    private static volatile long lastBudgetCleanupTick = Long.MIN_VALUE;
    
    private static final int PATH_RECALCULATE_INTERVAL = 20;
    private static final double PATH_RECALCULATE_DISTANCE = 2.0;
    private static final int LIVE_OBSTACLE_RECHECK_INTERVAL = 4;
    private static final int TARGET_PROGRESS_STALL_TICKS = 30;
    
    private boolean isPathfinding;
    private int pathRecalculateCooldown;
    private int blockedRepathTicks;
    private boolean usingTemporaryBypassTarget;
    private BlockPos targetPos;
    private Vec3 preciseTargetPos;
    private Vec3 debugDisplayTargetPos;
    private double reachDistance;
    private int liveObstacleCheckCooldown;
    private int targetProgressStallTicks;
    private double lastDistanceToTarget;
    private final int scheduleOffset;
    private final Set<BlockPos> openedFenceGates;
    
    private PathCompleteCallback onPathComplete;
    private PathFailCallback onPathFail;
    
    public interface PathCompleteCallback {
        void onComplete();
    }
    
    public interface PathFailCallback {
        void onFail();
    }
    
    public NPCPathNavigator(CustomEntity npc, ServerLevel level) {
        this.npc = npc;
        this.pathFinder = new NPCPathFinder(level);
        this.moveController = new NPCMoveController(npc, level);
        this.isPathfinding = false;
        this.pathRecalculateCooldown = 0;
        this.blockedRepathTicks = 0;
        this.preciseTargetPos = Vec3.ZERO;
        this.debugDisplayTargetPos = Vec3.ZERO;
        this.usingTemporaryBypassTarget = false;
        this.reachDistance = 1.0;
        this.liveObstacleCheckCooldown = 0;
        this.targetProgressStallTicks = 0;
        this.lastDistanceToTarget = Double.MAX_VALUE;
        this.scheduleOffset = Math.floorMod(npc.getUUID().hashCode(), PATH_RECALCULATE_INTERVAL);
        this.openedFenceGates = new LinkedHashSet<>();
        this.moveController.setFenceGateTracker(this::trackOpenedFenceGate);
    }
    
    public boolean moveTo(BlockPos pos, double reachDistance) {
        return moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, reachDistance);
    }
    
    public boolean moveTo(double x, double y, double z, double reachDistance) {
        BlockPos target = BlockPos.containing(x, y, z);
        Vec3 preciseTarget = new Vec3(x, y, z);
        
        if (isAtPosition(x, y, z, reachDistance)) {
            stop();
            if (onPathComplete != null) {
                onPathComplete.onComplete();
            }
            return true;
        }
        
        this.targetPos = target;
        this.preciseTargetPos = preciseTarget;
        this.debugDisplayTargetPos = preciseTarget;
        this.usingTemporaryBypassTarget = false;
        this.reachDistance = reachDistance;
        this.liveObstacleCheckCooldown = 0;
        this.targetProgressStallTicks = 0;
        this.lastDistanceToTarget = getDistanceToPreciseTarget();
        
        return startPathfinding();
    }
    
    public CompletableFuture<Boolean> moveToAsync(BlockPos pos, double reachDistance) {
        return CompletableFuture.supplyAsync(() -> moveTo(pos, reachDistance), pathfindingExecutor);
    }
    
    public void stop() {
        isPathfinding = false;
        blockedRepathTicks = 0;
        usingTemporaryBypassTarget = false;
        targetPos = null;
        preciseTargetPos = Vec3.ZERO;
        debugDisplayTargetPos = Vec3.ZERO;
        liveObstacleCheckCooldown = 0;
        targetProgressStallTicks = 0;
        lastDistanceToTarget = Double.MAX_VALUE;
        moveController.stop();
        restoreOpenedFenceGates();
    }
    
    public void tick() {
        if (npc.isSleeping()) {
            stop();
            return;
        }

        if (!isPathfinding) {
            return;
        }
        
        moveController.tick();
        cleanupPathRecalculationBudgets();
        
        if (moveController.isInStepUpTraversal()) {
            blockedRepathTicks = 0;
            if (pathRecalculateCooldown > 0) {
                pathRecalculateCooldown--;
            }
            if (liveObstacleCheckCooldown > 0) {
                liveObstacleCheckCooldown--;
            }
            return;
        }
        
        if (targetPos != null && !usingTemporaryBypassTarget && isAtTarget()) {
            handlePathComplete();
            return;
        }
        if (usingTemporaryBypassTarget && isAtTemporaryBypassTarget()) {
            usingTemporaryBypassTarget = false;
            recalculatePath(6);
            return;
        }

        updateProgressTracking();
        if (isScheduledPathCheckTick() && shouldRecalculateForLiveObstacle()) {
            recalculatePath(moveController.isMovementBlockedState() ? 6 : 3);
        }
        
        if (isScheduledPathCheckTick() && shouldRecalculatePath()) {
            if (moveController.isMovementBlockedState()) {
                blockedRepathTicks++;
            } else {
                blockedRepathTicks = 0;
            }

            if (blockedRepathTicks >= 6) {
                recalculatePathWithLocalBypass();
                blockedRepathTicks = 0;
            } else {
                recalculatePath(moveController.isMovementBlockedState() ? 6 : 3);
            }
        } else {
            blockedRepathTicks = 0;
        }
        
        if (pathRecalculateCooldown > 0) {
            pathRecalculateCooldown--;
        }
        if (liveObstacleCheckCooldown > 0) {
            liveObstacleCheckCooldown--;
        }
    }
    
    private boolean startPathfinding() {
        if (targetPos == null) {
            return false;
        }
        
        BlockPos startPos = getNavigationStartPos();
        if (startPos == null) {
            handlePathFail();
            return false;
        }
        BlockPos normalizedTarget = pathFinder.normalizeTargetPosition(targetPos);
        if (normalizedTarget == null) {
            handlePathFail();
            return false;
        }
        
        NPCPath path = pathFinder.findPath(startPos, normalizedTarget);
        
        if (path.isFailed()) {
            handlePathFail();
            return false;
        }
        
        isPathfinding = true;
        liveObstacleCheckCooldown = 0;
        targetProgressStallTicks = 0;
        lastDistanceToTarget = getDistanceToPreciseTarget();
        return moveController.setPath(path);
    }
    
    private void recalculatePath(int alternativeSearchRange) {
        if (pathRecalculateCooldown > 0 || !tryAcquirePathRecalculationBudget()) return;
        
        pathRecalculateCooldown = PATH_RECALCULATE_INTERVAL;
        
        BlockPos currentPos = getNavigationStartPos();
        if (currentPos == null) {
            return;
        }
        BlockPos normalizedTarget = pathFinder.normalizeTargetPosition(targetPos);
        if (normalizedTarget == null) {
            return;
        }
        NPCPath newPath = pathFinder.findPath(currentPos, normalizedTarget);
        
        if (!newPath.isFailed()) {
            moveController.setPath(newPath);
            resetPathProgressState();
            return;
        }

        BlockPos alternativeTarget = pathFinder.findAlternativeTargetNear(targetPos, alternativeSearchRange);
        if (alternativeTarget != null) {
            NPCPath alternativePath = pathFinder.findPath(currentPos, alternativeTarget);
            if (!alternativePath.isFailed()) {
                moveController.setPath(alternativePath);
                resetPathProgressState();
            }
        }
    }

    private void recalculatePathWithLocalBypass() {
        if (pathRecalculateCooldown > 0 || targetPos == null || !tryAcquirePathRecalculationBudget()) {
            return;
        }

        pathRecalculateCooldown = PATH_RECALCULATE_INTERVAL;
        BlockPos currentPos = getNavigationStartPos();
        if (currentPos == null) {
            return;
        }
        BlockPos normalizedTarget = pathFinder.normalizeTargetPosition(targetPos);
        if (normalizedTarget == null) {
            return;
        }
        NPCPath directPath = pathFinder.findPath(currentPos, normalizedTarget);
        if (!directPath.isFailed()) {
            usingTemporaryBypassTarget = false;
            moveController.setPath(directPath);
            resetPathProgressState();
            return;
        }

        List<BlockPos> candidateTargets = buildLocalBypassTargets(currentPos, normalizedTarget);
        for (BlockPos candidate : candidateTargets) {
            BlockPos normalizedCandidate = pathFinder.normalizeTargetPosition(candidate);
            if (normalizedCandidate == null) {
                continue;
            }
            NPCPath toCandidatePath = pathFinder.findPath(currentPos, normalizedCandidate);
            if (toCandidatePath.isFailed()) {
                continue;
            }
            usingTemporaryBypassTarget = true;
            debugDisplayTargetPos = Vec3.atCenterOf(targetPos);
            moveController.setPath(toCandidatePath);
            resetPathProgressState();
            return;
        }
    }

    private boolean isScheduledPathCheckTick() {
        return Math.floorMod(npc.tickCount + scheduleOffset, LIVE_OBSTACLE_RECHECK_INTERVAL) == 0;
    }

    private boolean tryAcquirePathRecalculationBudget() {
        long gameTime = npc.level().getGameTime();
        AtomicInteger budget = PATH_RECALCULATION_BUDGETS.computeIfAbsent(gameTime, tick -> new AtomicInteger(MAX_PATH_RECALCULATIONS_PER_TICK));
        while (true) {
            int remaining = budget.get();
            if (remaining <= 0) {
                return false;
            }
            if (budget.compareAndSet(remaining, remaining - 1)) {
                return true;
            }
        }
    }

    private void cleanupPathRecalculationBudgets() {
        long gameTime = npc.level().getGameTime();
        long lastCleanup = lastBudgetCleanupTick;
        if (lastCleanup != Long.MIN_VALUE && gameTime - lastCleanup < 100L) {
            return;
        }
        lastBudgetCleanupTick = gameTime;
        PATH_RECALCULATION_BUDGETS.keySet().removeIf(tick -> tick < gameTime - 20L);
    }

    private List<BlockPos> buildLocalBypassTargets(BlockPos currentPos, BlockPos finalTarget) {
        Set<BlockPos> uniqueCandidates = new LinkedHashSet<>();
        int dx = Integer.compare(finalTarget.getX(), currentPos.getX());
        int dz = Integer.compare(finalTarget.getZ(), currentPos.getZ());
        BlockPos lateralLeft = currentPos.offset(-dz, 0, dx);
        BlockPos lateralRight = currentPos.offset(dz, 0, -dx);
        BlockPos forward = currentPos.offset(dx, 0, dz);
        BlockPos diagonalLeft = lateralLeft.offset(dx, 0, dz);
        BlockPos diagonalRight = lateralRight.offset(dx, 0, dz);
        BlockPos fartherLeft = lateralLeft.offset(-dz, 0, dx);
        BlockPos fartherRight = lateralRight.offset(dz, 0, -dx);
        BlockPos fartherForward = forward.offset(dx, 0, dz);
        BlockPos diagonalFarLeft = fartherLeft.offset(dx, 0, dz);
        BlockPos diagonalFarRight = fartherRight.offset(dx, 0, dz);

        uniqueCandidates.add(diagonalLeft);
        uniqueCandidates.add(diagonalRight);
        uniqueCandidates.add(lateralLeft);
        uniqueCandidates.add(lateralRight);
        uniqueCandidates.add(forward);
        uniqueCandidates.add(diagonalFarLeft);
        uniqueCandidates.add(diagonalFarRight);
        uniqueCandidates.add(fartherLeft);
        uniqueCandidates.add(fartherRight);
        uniqueCandidates.add(fartherForward);
        return new ArrayList<>(uniqueCandidates);
    }

    private boolean shouldRecalculatePath() {
        if (pathRecalculateCooldown > 0 || targetPos == null || preciseTargetPos == Vec3.ZERO) {
            return false;
        }

        if (moveController.isMovementBlockedState()) {
            return true;
        }

        NPCPath currentPath = moveController.getCurrentPath();
        if (currentPath == null || currentPath.isFailed() || currentPath.isCompleted()) {
            return true;
        }

        Vec3 currentPos = npc.position();
        double distanceToPath = calculateDistanceToPath(currentPath, currentPos);
        if (distanceToPath > PATH_RECALCULATE_DISTANCE) {
            return true;
        }

        Vec3 currentTarget = currentPath.getCurrentTarget();
        if (currentTarget == null) {
            return true;
        }

        double distanceToCurrentNode = currentPos.distanceTo(currentTarget);
        return distanceToCurrentNode > 3.0;
    }

    private boolean shouldRecalculateForLiveObstacle() {
        if (targetPos == null || liveObstacleCheckCooldown > 0) {
            return false;
        }
        NPCPath currentPath = moveController.getCurrentPath();
        if (currentPath == null || currentPath.isFailed() || currentPath.isCompleted()) {
            liveObstacleCheckCooldown = LIVE_OBSTACLE_RECHECK_INTERVAL;
            return true;
        }
        if (moveController.shouldReplanForObstacle()) {
            liveObstacleCheckCooldown = LIVE_OBSTACLE_RECHECK_INTERVAL;
            return true;
        }
        if (targetProgressStallTicks >= TARGET_PROGRESS_STALL_TICKS) {
            liveObstacleCheckCooldown = LIVE_OBSTACLE_RECHECK_INTERVAL;
            return true;
        }
        return false;
    }

    private double calculateDistanceToPath(NPCPath path, Vec3 position) {
        if (path == null || position == null) {
            return Double.MAX_VALUE;
        }
        double nearestDistance = Double.MAX_VALUE;
        for (NPCPathNode node : path.getNodes()) {
            if (node == null) {
                continue;
            }
            Vec3 nodePos = new Vec3(node.standX, node.standY, node.standZ);
            nearestDistance = Math.min(nearestDistance, position.distanceTo(nodePos));
        }
        return nearestDistance;
    }

    private void updateProgressTracking() {
        double distanceToTarget = getDistanceToPreciseTarget();
        if (distanceToTarget + 0.15D < lastDistanceToTarget) {
            targetProgressStallTicks = 0;
            lastDistanceToTarget = distanceToTarget;
            return;
        }
        if (Math.abs(distanceToTarget - lastDistanceToTarget) <= 0.05D) {
            targetProgressStallTicks++;
        } else {
            targetProgressStallTicks = 0;
        }
        lastDistanceToTarget = distanceToTarget;
    }

    private void resetPathProgressState() {
        liveObstacleCheckCooldown = LIVE_OBSTACLE_RECHECK_INTERVAL;
        targetProgressStallTicks = 0;
        lastDistanceToTarget = getDistanceToPreciseTarget();
    }

    private double getDistanceToPreciseTarget() {
        if (preciseTargetPos == null || preciseTargetPos == Vec3.ZERO) {
            return targetPos == null ? Double.MAX_VALUE : npc.position().distanceTo(Vec3.atCenterOf(targetPos));
        }
        return npc.position().distanceTo(preciseTargetPos);
    }

    private boolean isAtTarget() {
        if (preciseTargetPos == null || preciseTargetPos == Vec3.ZERO) {
            return false;
        }
        return npc.position().distanceTo(preciseTargetPos) <= reachDistance;
    }

    private boolean isAtTemporaryBypassTarget() {
        NPCPath currentPath = moveController.getCurrentPath();
        if (currentPath == null) {
            return false;
        }
        Vec3 currentTarget = currentPath.getCurrentTarget();
        if (currentTarget == null) {
            return false;
        }
        return npc.position().distanceTo(currentTarget) <= Math.max(reachDistance, 0.8D);
    }
    
    private boolean isAtPosition(double x, double y, double z, double distance) {
        Vec3 target = new Vec3(x, y, z);
        return npc.position().distanceTo(target) <= distance;
    }
    
    private void handlePathComplete() {
        stop();
        if (onPathComplete != null) {
            onPathComplete.onComplete();
        }
    }
    
    private BlockPos getNavigationStartPos() {
        BlockPos fromFeet = BlockPos.containing(npc.getX(), npc.getY(), npc.getZ());
        BlockPos normalized = pathFinder.normalizeStartPosition(fromFeet);
        if (normalized != null) {
            return normalized;
        }
        return pathFinder.normalizeStartPosition(npc.blockPosition());
    }

    private void handlePathFail() {
        BlockPos failedTarget = this.targetPos;
        stop();
        if (onPathFail != null) {
            onPathFail.onFail();
        }
        // menglannnn: 如果寻路失败且NPC正在工作中，则强制TP回目标位置（控制盒）
        if (npc.isWorking() && failedTarget != null) {
            npc.scheduleHireArrivalTeleport(failedTarget);
        }
    }
    
    public void setOnPathComplete(PathCompleteCallback callback) {
        this.onPathComplete = callback;
    }
    
    public void setOnPathFail(PathFailCallback callback) {
        this.onPathFail = callback;
    }
    
    public boolean isPathfinding() {
        return isPathfinding;
    }
    
    public boolean isDone() {
        return !isPathfinding;
    }
    
    @Nullable
    public NPCPath getCurrentPath() {
        return moveController.getCurrentPath();
    }
    
    @Nullable
    public BlockPos getTargetPos() {
        return targetPos;
    }

    @Nullable
    public Vec3 getDebugDisplayTargetPos() {
        return debugDisplayTargetPos == Vec3.ZERO ? null : debugDisplayTargetPos;
    }
    
    public double getDistanceToTarget() {
        if (targetPos == null) return 0;
        
        Vec3 target = preciseTargetPos == null || preciseTargetPos == Vec3.ZERO
                ? new Vec3(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5)
                : preciseTargetPos;
        return npc.position().distanceTo(target);
    }
    
    public NPCMoveController getMoveController() {
        return moveController;
    }

    public boolean isBlockedByObstacle() {
        return moveController.isMovementBlockedState();
    }

    private void trackOpenedFenceGate(BlockPos gatePos) {
        if (gatePos != null) {
            openedFenceGates.add(gatePos.immutable());
        }
    }

    private void restoreOpenedFenceGates() {
        if (!(npc.level() instanceof ServerLevel serverLevel) || openedFenceGates.isEmpty()) {
            openedFenceGates.clear();
            return;
        }
        for (BlockPos gatePos : new ArrayList<>(openedFenceGates)) {
            BlockState gateState = serverLevel.getBlockState(gatePos);
            if (gateState.getBlock() instanceof FenceGateBlock && gateState.hasProperty(FenceGateBlock.OPEN) && gateState.getValue(FenceGateBlock.OPEN)) {
                serverLevel.setBlock(gatePos, gateState.setValue(FenceGateBlock.OPEN, false), 10);
            }
        }
        openedFenceGates.clear();
    }
    
    public static void shutdown() {
        pathfindingExecutor.shutdown();
    }
}
