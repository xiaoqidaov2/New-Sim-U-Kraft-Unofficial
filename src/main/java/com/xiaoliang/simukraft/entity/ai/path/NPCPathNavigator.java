package com.xiaoliang.simukraft.entity.ai.path;

import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NPC路径导航器（menglannnn: 管理NPC的寻路和移动）
 */
@SuppressWarnings("null")
public class NPCPathNavigator {
    
    private final CustomEntity npc;
    private final NPCPathFinder pathFinder;
    private final NPCMoveController moveController;
    
    // 异步寻路线程池
    private static final ExecutorService pathfindingExecutor = Executors.newFixedThreadPool(2);
    
    // 寻路参数
    private static final int PATH_RECALCULATE_INTERVAL = 20; // 1秒重新计算一次路径
    private static final double PATH_RECALCULATE_DISTANCE = 2.0; // 偏离路径超过2格重新计算
    
    // 状态
    private boolean isPathfinding;
    private int pathRecalculateCooldown;
    private int blockedRepathTicks;
    private boolean usingTemporaryBypassTarget;
    private BlockPos targetPos;
    private Vec3 preciseTargetPos;
    private Vec3 debugDisplayTargetPos;
    private double reachDistance;
    
    // 路径完成回调
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
    }
    
    /**
     * 移动到指定位置
     * @param pos 目标位置
     * @param reachDistance 到达距离阈值
     * @return 是否成功开始寻路
     */
    public boolean moveTo(BlockPos pos, double reachDistance) {
        return moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, reachDistance);
    }
    
    /**
     * 移动到指定位置
     * @param x 目标X坐标
     * @param y 目标Y坐标
     * @param z 目标Z坐标
     * @param reachDistance 到达距离阈值
     * @return 是否成功开始寻路
     */
    public boolean moveTo(double x, double y, double z, double reachDistance) {
        BlockPos target = BlockPos.containing(x, y, z);
        Vec3 preciseTarget = new Vec3(x, y, z);
        
        // 检查是否已经在目标位置
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
        
        // 立即开始寻路
        return startPathfinding();
    }
    
    /**
     * 异步移动到指定位置（不会阻塞主线程）
     */
    public CompletableFuture<Boolean> moveToAsync(BlockPos pos, double reachDistance) {
        return CompletableFuture.supplyAsync(() -> moveTo(pos, reachDistance), pathfindingExecutor);
    }
    
    /**
     * 停止导航
     */
    public void stop() {
        isPathfinding = false;
        blockedRepathTicks = 0;
        usingTemporaryBypassTarget = false;
        targetPos = null;
        preciseTargetPos = Vec3.ZERO;
        debugDisplayTargetPos = Vec3.ZERO;
        moveController.stop();
    }
    
    /**
     * 每tick更新
     */
    public void tick() {
        if (npc.isSleeping()) {
            stop();
            return;
        }

        if (!isPathfinding) {
            return;
        }
        
        // 更新移动控制器
        moveController.tick();
        
        if (moveController.isInStepUpTraversal()) {
            blockedRepathTicks = 0;
            if (pathRecalculateCooldown > 0) {
                pathRecalculateCooldown--;
            }
            return;
        }
        
        // 检查是否到达目标
        if (targetPos != null && !usingTemporaryBypassTarget && isAtTarget()) {
            handlePathComplete();
            return;
        }
        if (usingTemporaryBypassTarget && isAtTemporaryBypassTarget()) {
            usingTemporaryBypassTarget = false;
            recalculatePath(6);
            return;
        }
        
        // 检查是否需要重新计算路径
        if (shouldRecalculatePath()) {
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
        
        // 更新冷却
        if (pathRecalculateCooldown > 0) {
            pathRecalculateCooldown--;
        }
    }
    
    /**
     * 开始寻路
     */
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
        return moveController.setPath(path);
    }
    
    /**
     * 重新计算路径
     */
    private void recalculatePath(int alternativeSearchRange) {
        if (pathRecalculateCooldown > 0) return;
        
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
            return;
        }

        BlockPos alternativeTarget = pathFinder.findAlternativeTargetNear(targetPos, alternativeSearchRange);
        if (alternativeTarget != null) {
            NPCPath alternativePath = pathFinder.findPath(currentPos, alternativeTarget);
            if (!alternativePath.isFailed()) {
                moveController.setPath(alternativePath);
            }
        }
    }

    private void recalculatePathWithLocalBypass() {
        if (pathRecalculateCooldown > 0 || targetPos == null) {
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

            NPCPath candidateToFinal = pathFinder.findPath(normalizedCandidate, normalizedTarget);
            if (candidateToFinal.isFailed()) {
                continue;
            }

            usingTemporaryBypassTarget = true;
            moveController.setPath(toCandidatePath);
            return;
        }

        BlockPos alternativeTarget = pathFinder.findAlternativeTargetNear(normalizedTarget, 8);
        if (alternativeTarget != null) {
            NPCPath alternativePath = pathFinder.findPath(currentPos, alternativeTarget);
            if (!alternativePath.isFailed()) {
                usingTemporaryBypassTarget = true;
                moveController.setPath(alternativePath);
            }
        }
    }

    private List<BlockPos> buildLocalBypassTargets(BlockPos currentPos, BlockPos finalTarget) {
        List<BlockPos> targets = new ArrayList<>();
        int dx = Integer.compare(finalTarget.getX(), currentPos.getX());
        int dz = Integer.compare(finalTarget.getZ(), currentPos.getZ());
        int sideX = -dz;
        int sideZ = dx;

        int[] sideSteps = new int[] {1, 2, -1, -2};
        for (int sideStep : sideSteps) {
            int actualSideX = sideX * sideStep;
            int actualSideZ = sideZ * sideStep;
            targets.add(currentPos.offset(actualSideX + dx, 0, actualSideZ + dz));
            targets.add(currentPos.offset(actualSideX + dx * 2, 0, actualSideZ + dz * 2));
            targets.add(currentPos.offset(actualSideX + dx, 1, actualSideZ + dz));
        }

        return targets;
    }
    
    /**
     * 检查是否需要重新计算路径
     */
    private boolean shouldRecalculatePath() {
        NPCPath currentPath = moveController.getCurrentPath();
        if (currentPath == null) return true;
        
        if (moveController.isMovementBlockedState()) {
            return true;
        }

        // 检查是否偏离路径
        Vec3 currentPos = npc.position();
        NPCPathNode currentNode = currentPath.getCurrentNode();
        
        if (currentNode != null) {
            double distToPath = currentPos.distanceTo(currentPath.getCurrentTarget());
            if (distToPath > PATH_RECALCULATE_DISTANCE) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查是否到达目标
     */
    private boolean isAtTarget() {
        if (targetPos == null) return false;
        
        Vec3 targetVec = preciseTargetPos != null ? preciseTargetPos : new Vec3(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
        return npc.position().distanceTo(targetVec) <= reachDistance;
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
    
    /**
     * 检查是否在指定位置
     */
    private boolean isAtPosition(double x, double y, double z, double distance) {
        Vec3 target = new Vec3(x, y, z);
        return npc.position().distanceTo(target) <= distance;
    }
    
    /**
     * 处理路径完成
     */
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

    /**
     * 处理路径失败
     */
    private void handlePathFail() {
        stop();
        if (onPathFail != null) {
            onPathFail.onFail();
        }
    }
    
    /**
     * 设置路径完成回调
     */
    public void setOnPathComplete(PathCompleteCallback callback) {
        this.onPathComplete = callback;
    }
    
    /**
     * 设置路径失败回调
     */
    public void setOnPathFail(PathFailCallback callback) {
        this.onPathFail = callback;
    }
    
    /**
     * 是否正在寻路
     */
    public boolean isPathfinding() {
        return isPathfinding;
    }
    
    /**
     * 是否已到达目标
     */
    public boolean isDone() {
        return !isPathfinding;
    }
    
    /**
     * 获取当前路径
     */
    @Nullable
    public NPCPath getCurrentPath() {
        return moveController.getCurrentPath();
    }
    
    /**
     * 获取目标位置
     */
    @Nullable
    public BlockPos getTargetPos() {
        return targetPos;
    }

    @Nullable
    public Vec3 getDebugDisplayTargetPos() {
        return debugDisplayTargetPos == Vec3.ZERO ? null : debugDisplayTargetPos;
    }
    
    /**
     * 获取到目标的距离
     */
    public double getDistanceToTarget() {
        if (targetPos == null) return 0;
        
        Vec3 target = new Vec3(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
        return npc.position().distanceTo(target);
    }
    
    /**
     * 获取移动控制器
     */
    public NPCMoveController getMoveController() {
        return moveController;
    }

    public boolean isBlockedByObstacle() {
        return moveController.isMovementBlockedState();
    }
    
    /**
     * 关闭导航器（释放资源）
     */
    public static void shutdown() {
        pathfindingExecutor.shutdown();
    }
}
