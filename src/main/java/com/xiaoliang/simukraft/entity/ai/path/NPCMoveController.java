package com.xiaoliang.simukraft.entity.ai.path;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NPC移动控制器（menglannnn: 控制NPC沿着路径移动）
 */
@SuppressWarnings("null")
public class NPCMoveController {
    
    private final CustomEntity npc;
    private final ServerLevel level;
    
    // 移动参数
    private static final double ARRIVAL_DISTANCE = 0.5; // 到达节点的距离阈值
    private static final double WALK_MOVE_SPEED = 0.22D;
    private static final double RUN_MOVE_SPEED = 0.4D;
    private static final double FATIGUE_MIN_SPEED = 0.1D;
    private static final double FATIGUE_MAX_SPEED = 0.2D;
    private static final int FATIGUE_MIN_RUNNING_TICKS = 120;
    private static final int FATIGUE_RANDOM_RUNNING_TICKS = 160;
    private static final int FATIGUE_MIN_DURATION_TICKS = 45;
    private static final int FATIGUE_RANDOM_DURATION_TICKS = 90;
    private static final int FATIGUE_RECOVERY_TICKS = 80;
    private static final double FATIGUE_TRIGGER_CHANCE = 0.018D;
    private static final double RUN_REMAINING_DISTANCE = 8.0D;
    private static final double RUN_TARGET_DISTANCE = 8.0D;
    private static final double RUN_DIRECT_DISTANCE = 4.0D;
    private static final double SPRINT_ATTRIBUTE_MULTIPLIER = 1.35D;
    private static final double WALK_STEP_HEIGHT = 12.0D / 16.0D;
    private static final double MAX_ASCEND_HEIGHT = 19.0D / 16.0D;
    private static final double MAX_JUMP_OVER_HEIGHT = 1.625D;
    private static final double MIN_ASCEND_JUMP_SPEED = 0.18D;
    private static final double MAX_ASCEND_JUMP_SPEED = 0.33D;
    private static final double MIN_AIR_TRAVERSAL_SPEED = 0.12D;
    private static final double BODY_COLLISION_XZ_SHRINK = 0.03D;
    private static final double DEFAULT_BODY_RADIUS = 0.3D;
    private static final double OPEN_TRAPDOOR_BODY_RADIUS = 0.24D;
    private static final double OPEN_TRAPDOOR_PANEL_SIDE_RADIUS = 0.14D;
    private static final double TRAPDOOR_HOP_SPEED = 0.24D;
    private static final double TRAPDOOR_HOP_MAX_DISTANCE = 1.15D;
    private static final float MIN_SNAP_TURN_DEGREES = 12.0F;
    private static final float MAX_TURN_DEGREES_PER_TICK = 55.0F;
    
    // 当前路径
    private NPCPath currentPath;
    
    // 移动状态
    private boolean isMoving;
    private double currentSpeed;
    private int stuckTicks;
    private Vec3 lastPos;
    private static final int STUCK_THRESHOLD = 20; // 1秒后认为卡住
    
    private int doorInteractCooldown;
    private boolean movementBlocked;
    private ObstacleType currentObstacleType;
    private int crowdWaitTicks;
    private Vec3 lastCrowdBlockerPos;
    private int crowdBypassTicks;
    private int narrowPassWaitTicks;
    private int narrowPassBias;
    private int stepUpLockTicks;
    private int stepUpAirTicks;
    private StepUpPhase stepUpPhase;
    private int runningTicks;
    private int fatigueTriggerTicks;
    private int fatigueTicks;
    private int fatigueCooldownTicks;
    private double fatigueSpeed;
    private Consumer<BlockPos> fenceGateTracker;

    private enum ObstacleType {
        NONE,
        DOOR_CLOSED,
        NPC_BLOCKER,
        STEP_UP,
        SOLID_BLOCK,
        TIGHT_SPACE
    }

    private enum StepUpPhase {
        NONE,
        APPROACH,
        LIFT,
        LAND
    }

    
    public NPCMoveController(CustomEntity npc, ServerLevel level) {
        this.npc = npc;
        this.level = level;
        this.isMoving = false;
        this.currentSpeed = 0;
        this.stuckTicks = 0;
        this.lastPos = Vec3.ZERO;
        this.doorInteractCooldown = 0;
        this.movementBlocked = false;
        this.currentObstacleType = ObstacleType.NONE;
        this.crowdWaitTicks = 0;
        this.lastCrowdBlockerPos = null;
        this.crowdBypassTicks = 0;
        this.narrowPassWaitTicks = 0;
        this.narrowPassBias = 0;
        this.stepUpLockTicks = 0;
        this.stepUpAirTicks = 0;
        this.stepUpPhase = StepUpPhase.NONE;
        this.runningTicks = 0;
        this.fatigueTriggerTicks = nextFatigueTriggerTicks();
        this.fatigueTicks = 0;
        this.fatigueCooldownTicks = 0;
        this.fatigueSpeed = WALK_MOVE_SPEED;
        this.fenceGateTracker = null;
    }
    
    /**
     * 设置路径并开始移动
     */
    public boolean setPath(NPCPath path) {
        if (path == null || path.isEmpty() || path.isFailed()) {
            stop();
            return false;
        }
        
        this.currentPath = path;
        this.isMoving = true;
        this.currentSpeed = calculateSpeed();
        this.stuckTicks = 0;
        this.lastPos = npc.position();
        this.movementBlocked = false;
        resetFatigueState();
        
        return true;
    }
    
    /**
     * 停止移动
     */
    public void stop() {
        this.isMoving = false;
        this.currentPath = null;
        this.currentSpeed = 0;
        this.stuckTicks = 0;
        this.movementBlocked = false;
        this.currentObstacleType = ObstacleType.NONE;
        this.crowdWaitTicks = 0;
        this.lastCrowdBlockerPos = null;
        this.crowdBypassTicks = 0;
        this.narrowPassWaitTicks = 0;
        this.narrowPassBias = 0;
        this.stepUpLockTicks = 0;
        this.stepUpAirTicks = 0;
        this.stepUpPhase = StepUpPhase.NONE;
        resetFatigueState();
        
        // 停止NPC移动
        npc.setDeltaMovement(Vec3.ZERO);
    }
    
    public boolean isInStepUpTraversal() {
        if (currentPath == null) {
            return false;
        }
        NPCPathNode currentNode = currentPath.getCurrentNode();
        if (currentNode == null || currentNode.action != NPCPathNode.MovementAction.ASCEND) {
            return false;
        }
        return stepUpPhase != StepUpPhase.NONE || stepUpLockTicks > 0 || stepUpAirTicks > 0 || !npc.onGround();
    }

    /**
     * 每tick更新移动
     */
    public void tick() {
        if (npc.isSleeping()) {
            stop();
            return;
        }

        if (!isMoving || currentPath == null) {
            return;
        }
        
        // menglannnn: 运行时危险检测 - 如果NPC当前位置有危险，立即停止并重新寻路
        if (checkAndHandleDanger()) {
            return;
        }
        
        // 检查路径是否完成
        if (currentPath.isCompleted()) {
            stop();
            return;
        }
        
        // 获取当前目标节点
        NPCPathNode targetNode = currentPath.getCurrentNode();
        if (targetNode == null) {
            stop();
            return;
        }
        
        // menglannnn: 检查目标节点是否安全
        if (isDangerousPosition(targetNode.pos)) {
            Simukraft.LOGGER.warn("[NPCMoveController] NPC {} 路径目标节点 {} 检测到危险，停止移动", 
                npc.getFullName(), targetNode.pos);
            stop();
            return;
        }

        tryHandleDoorOnPath(targetNode);
        
        // 检查是否到达当前节点
        Vec3 targetPos = currentPath.getCurrentTarget();
        double distanceToTarget = npc.position().distanceTo(targetPos);
        boolean jumpOverTraversal = targetNode.action == NPCPathNode.MovementAction.JUMP_OVER;
        boolean requiresVerticalTraversal = targetNode.action == NPCPathNode.MovementAction.ASCEND || jumpOverTraversal;
        double targetHeight = targetNode.standY;
        boolean reachedTraversalHeight = npc.getY() >= targetHeight - 0.15D;
        double horizontalDistanceToTarget = horizontalDistance(npc.position(), targetPos);
        boolean traverseNode = targetNode.action == NPCPathNode.MovementAction.TRAVERSE;
        boolean stairOrSlabTraversal = shouldUseAutoStepTraversal(targetNode, targetPos);
        boolean trapdoorTraversalScenario = isTrapdoorTraversalScenario(targetNode, targetPos);
        if (stairOrSlabTraversal) {
            requiresVerticalTraversal = false;
            resetStepUpState();
            movementBlocked = false;
            logStepMove("STAIR_OR_SLAB_OVERRIDE", targetNode, targetPos, horizontalDistanceToTarget, targetNode.standY - npc.getY());
        }
        if (stairOrSlabTraversal && targetNode.standY - npc.getY() > WALK_STEP_HEIGHT + 0.05D) {
            logStepMove("STAIR_OR_SLAB_TOO_HIGH_BLOCK", targetNode, targetPos, horizontalDistanceToTarget, targetNode.standY - npc.getY());
            stopVanillaMovement();
            movementBlocked = true;
            return;
        }
        if (traverseNode && targetNode.standY - npc.getY() > WALK_STEP_HEIGHT + 0.05D) {
            logStepMove("TRAVERSE_TOO_HIGH_BLOCK", targetNode, targetPos, horizontalDistanceToTarget, targetNode.standY - npc.getY());
            stopVanillaMovement();
            movementBlocked = true;
            return;
        }
        if (targetNode.action == NPCPathNode.MovementAction.ASCEND && targetNode.standY - npc.getY() >= MAX_ASCEND_HEIGHT) {
            logStepMove("ASCEND_TOO_HIGH_BLOCK", targetNode, targetPos, horizontalDistanceToTarget, targetNode.standY - npc.getY());
            stopVanillaMovement();
            movementBlocked = true;
            return;
        }
        if (jumpOverTraversal && horizontalDistanceToTarget <= 0.7D && Math.abs(npc.getY() - targetHeight) <= 0.85D) {
            handleNodeArrival(targetNode);
            return;
        }
        double traverseArrivalDistance = stairOrSlabTraversal ? 0.18D : ARRIVAL_DISTANCE;
        boolean reachedTraverseNode = traverseNode && horizontalDistanceToTarget <= traverseArrivalDistance
                && (!stairOrSlabTraversal || Math.abs(npc.getY() - targetNode.standY) <= 0.18D || npc.getY() >= targetNode.standY - 0.08D);
        boolean reachedFallNode = (targetNode.action == NPCPathNode.MovementAction.FALL || targetNode.action == NPCPathNode.MovementAction.DESCEND)
                && horizontalDistanceToTarget <= 0.55D && Math.abs(npc.getY() - targetHeight) <= 0.65D;
        
        if (reachedTraverseNode || (!traverseNode && distanceToTarget <= ARRIVAL_DISTANCE && (!requiresVerticalTraversal || reachedTraversalHeight)) || reachedFallNode) {
            // 到达节点，前进到下一个
            handleNodeArrival(targetNode);
            return;
        }

        if ((targetNode.action == NPCPathNode.MovementAction.FALL || targetNode.action == NPCPathNode.MovementAction.DESCEND) && executeFallAction(targetNode, targetPos)) {
            movementBlocked = false;
            return;
        }

        if (requiresVerticalTraversal && !npc.onGround()) {
            maintainAirTraversalMotion(targetNode, targetPos);
            if (stepUpPhase != StepUpPhase.NONE) {
                stepUpPhase = StepUpPhase.LAND;
            }
            return;
        }
        
        // 检查是否卡住
        if (checkStuck()) {
            if (stairOrSlabTraversal && !trapdoorTraversalScenario) {
                stuckTicks = 0;
            } else {
                handleStuck();
                return;
            }
        }

        if (!stairOrSlabTraversal && stepUpPhase != StepUpPhase.NONE && continueStepUpPhase(targetNode, targetPos)) {
            movementBlocked = false;
            return;
        }
        resetStepUpState();

        if (!stairOrSlabTraversal && tryAscendTowardNode(targetNode, targetPos)) {
            movementBlocked = false;
            return;
        }

        resetStepUpState();

        if (trapdoorTraversalScenario && tryTrapdoorForwardHop(targetNode, targetPos)) {
            movementBlocked = false;
            return;
        }

        if (jumpOverTraversal && executeJumpOverAction(targetNode, targetPos)) {
            movementBlocked = false;
            return;
        }
        
        // 执行移动前检查前方是否被阻挡
        if (!stairOrSlabTraversal && isMovementBlocked(targetPos)) {
            movementBlocked = true;
            if (currentObstacleType == ObstacleType.DOOR_CLOSED) {
                handleDoorInteraction();
            }
            if (currentObstacleType == ObstacleType.STEP_UP && tryCalibratedStepPass(targetPos)) {
                movementBlocked = false;
                return;
            }
            if (tryResolveCrowdedPath(targetPos)) {
                return;
            }
            if (tryForwardJumpRecovery(targetNode, targetPos)) {
                movementBlocked = false;
                return;
            }
            if (tryBypassObstacle(targetPos)) {
                return;
            }
            if (npc.onGround()) {
                stopVanillaMovement();
            }
            return;
        }
        movementBlocked = false;
        currentObstacleType = ObstacleType.NONE;
        resetCrowdState();

        // 执行移动
        currentSpeed = calculateSpeed(targetPos);
        if (stairOrSlabTraversal) {
            moveHorizontallyWithoutJump(targetPos, targetNode);
        } else if (traverseNode) {
            moveDirectlyAlongPath(new Vec3(targetPos.x, npc.getY(), targetPos.z));
        } else {
            moveTowards(targetPos);
        }
        syncFacingToCurrentMotion(0.8F);
        
        // 处理门交互
        handleDoorInteraction();
        
        // 更新冷却
        if (doorInteractCooldown > 0) {
            doorInteractCooldown--;
        }
    }
    
    /**
     * 处理到达节点
     */
    private void handleNodeArrival(NPCPathNode node) {
        // 根据节点类型执行特殊操作
        switch (node.action) {
            case DOOR:
                interactWithDoor(node.pos);
                break;
            case ASCEND:
            case JUMP_OVER:
                resetStepUpState();
                releaseAirTraversalControl();
                break;
            default:
                break;
        }
        
        // 前进到下一个节点
        if (!currentPath.advance()) {
            // 路径完成
            stop();
        }
    }

    private void tryHandleDoorOnPath(NPCPathNode targetNode) {
        if (targetNode == null || doorInteractCooldown > 0) {
            return;
        }

        Vec3 targetPos = currentPath.getCurrentTarget();
        if (targetPos == null) {
            return;
        }

        Vec3 currentPos = npc.position();
        double distanceToTarget = currentPos.distanceTo(targetPos);
        if (distanceToTarget > 1.6D) {
            return;
        }

        BlockPos currentBlockPos = npc.blockPosition();
        BlockPos targetBlockPos = targetNode.pos;
        BlockPos midBlockPos = BlockPos.containing(
                (currentPos.x + targetPos.x) * 0.5D,
                currentPos.y + 0.5D,
                (currentPos.z + targetPos.z) * 0.5D
        );

        BlockPos[] doorCandidates = new BlockPos[] {
                targetBlockPos,
                targetBlockPos.below(),
                currentBlockPos.relative(npc.getDirection()),
                currentBlockPos.relative(npc.getDirection()).above(),
                midBlockPos,
                midBlockPos.above()
        };

        for (BlockPos candidate : doorCandidates) {
            if (tryOpenDoorAt(candidate)) {
                return;
            }
        }
    }
    
    private boolean tryAscendTowardNode(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null || targetNode.action != NPCPathNode.MovementAction.ASCEND) {
            return false;
        }
        boolean autoStepNearby = isVanillaAutoStepNear(targetNode.pos);
        boolean onAutoStep = isOnVanillaAutoStepBlock();
        boolean allowAscendOnAutoStep = autoStepNearby && shouldAscendOnAutoStep(targetNode, targetPos);
        if ((autoStepNearby || onAutoStep) && !allowAscendOnAutoStep) {
            return false;
        }
        if (!npc.onGround()) {
            releaseAirTraversalControl();
            stepUpPhase = StepUpPhase.LAND;
            return true;
        }

        double heightDelta = targetNode.standY - npc.getY();
        if (heightDelta <= 0.75D || heightDelta >= 1.1875D) {
            return false;
        }

        Vec3 currentPos = npc.position();
        Vec3 direction = targetPos.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.05D) {
            return false;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        BlockPos landingPos = targetNode.pos;
        BlockPos landingHeadPos = landingPos.above();
        if (!isAscendLandingPassable(landingPos)) {
            return false;
        }
        if (!isOpenSpacePassable(level.getBlockState(landingHeadPos), landingHeadPos)) {
            return false;
        }

        faceMovementDirection(dx, dz, 0.7F);
        moveTowards(targetPos);
        stepUpPhase = StepUpPhase.APPROACH;
        if (horizontalDist <= 0.35D && targetNode.standY - npc.getY() > 0.75D) {
            double jumpSpeed = calculateAscendJumpSpeed(horizontalDist);
            logStepMove("ASCEND_JUMP_CONTROL", targetNode, targetPos, horizontalDist, targetNode.standY - npc.getY());
            triggerGroundJump(0.42D);
            applyDirectedTraversalMotion(dx, dz, jumpSpeed, Math.max(npc.getDeltaMovement().y, 0.42D));
            stepUpPhase = StepUpPhase.LAND;
        }
        return true;
    }

    private boolean executeJumpOverAction(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null || targetNode.action != NPCPathNode.MovementAction.JUMP_OVER || targetPos == null) {
            return false;
        }

        Vec3 currentPos = npc.position();
        Vec3 direction = targetPos.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.05D) {
            return false;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        BlockPos obstaclePos = findJumpBarrierOnPath(currentPos, dx, dz, horizontalDist);
        boolean jumpWindow = obstaclePos != null && isJumpableBarrier(obstaclePos);
        double speed = Math.max(0.08D, Math.min(currentSpeed * 1.08D, horizontalDist * 0.34D + 0.04D));
        double yMotion = npc.getDeltaMovement().y;

        faceMovementDirection(dx, dz, 0.75F);
        if (npc.onGround() && jumpWindow) {
            triggerGroundJump(0.42D);
            yMotion = Math.max(npc.getDeltaMovement().y, 0.42D);
            logStepMove("JUMP_OVER_BARRIER", targetNode, targetPos, horizontalDist, targetNode.standY - npc.getY());
        } else if (!npc.onGround()) {
            releaseAirTraversalControl();
        }

        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0D);
        npc.setDeltaMovement(dx * speed, yMotion, dz * speed);
        npc.setSpeed((float) speed);
        npc.setZza((float) speed);
        npc.setXxa(0.0F);
        return true;
    }

    private boolean tryTrapdoorForwardHop(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null || targetPos == null || !npc.onGround()) {
            return false;
        }
        if (!isTrapdoorTraversalScenario(targetNode, targetPos)) {
            return false;
        }

        Vec3 currentPos = npc.position();
        Vec3 direction = targetPos.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.08D || horizontalDist > TRAPDOOR_HOP_MAX_DISTANCE) {
            return false;
        }

        double heightDelta = targetNode.standY - npc.getY();
        if (heightDelta < -0.2D || heightDelta > 1.05D) {
            return false;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        double hopSpeed = Math.max(TRAPDOOR_HOP_SPEED, Math.min(MAX_ASCEND_JUMP_SPEED, calculateAscendJumpSpeed(horizontalDist)));
        faceMovementDirection(dx, dz, 0.8F);
        triggerGroundJump(0.42D);
        applyDirectedTraversalMotion(dx, dz, hopSpeed, Math.max(npc.getDeltaMovement().y, 0.42D));
        logStepMove("TRAPDOOR_FORWARD_HOP", targetNode, targetPos, horizontalDist, heightDelta);
        logTrapdoorContext("FORWARD_HOP", targetNode, targetPos);
        stepUpPhase = StepUpPhase.LAND;
        return true;
    }

    private boolean shouldAscendOnAutoStep(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null || targetPos == null) {
            return false;
        }
        BlockPos supportPos = BlockPos.containing(targetPos.x, targetNode.standY - 1.0E-5D, targetPos.z);
        BlockState supportState = level.getBlockState(supportPos);
        if (!(supportState.getBlock() instanceof StairBlock) && !(supportState.getBlock() instanceof SlabBlock)) {
            return false;
        }

        Vec3 currentPos = npc.position();
        Vec3 supportCenter = Vec3.atCenterOf(supportPos);
        Vec3 approach = supportCenter.subtract(currentPos);
        double horizontalDist = Math.sqrt(approach.x * approach.x + approach.z * approach.z);
        if (horizontalDist <= 1.0E-5D) {
            return false;
        }

        double dirX = approach.x / horizontalDist;
        double dirZ = approach.z / horizontalDist;
        double enterSampleX = supportPos.getX() + 0.5D - dirX * 0.31D;
        double enterSampleZ = supportPos.getZ() + 0.5D - dirZ * 0.31D;
        double innerSampleX = supportPos.getX() + 0.5D + dirX * 0.31D;
        double innerSampleZ = supportPos.getZ() + 0.5D + dirZ * 0.31D;

        double enterTop = getHighestCollisionTopAt(supportPos, enterSampleX, enterSampleZ);
        double innerTop = getHighestCollisionTopAt(supportPos, innerSampleX, innerSampleZ);
        if (enterTop == Double.NEGATIVE_INFINITY || innerTop == Double.NEGATIVE_INFINITY) {
            return false;
        }

        double rise = innerTop - enterTop;
        return Math.abs(rise) <= 0.12D;
    }

    private boolean continueStepUpPhase(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null || targetNode.action != NPCPathNode.MovementAction.ASCEND) {
            return false;
        }

        switch (stepUpPhase) {
            case APPROACH:
            case LIFT:
                if (!npc.onGround()) {
                    stepUpPhase = StepUpPhase.LAND;
                    releaseAirTraversalControl();
                    return true;
                }
                if (!tryAscendTowardNode(targetNode, targetPos)) {
                    return false;
                }
                if (stepUpLockTicks > 0) {
                    stepUpLockTicks--;
                }
                return true;
            case LAND:
                if (!npc.onGround()) {
                    releaseAirTraversalControl();
                    return true;
                }
                double remainingHeight = targetNode.standY - npc.getY();
                if (remainingHeight > 0.12D && stepUpLockTicks > 0) {
                    stepUpPhase = StepUpPhase.APPROACH;
                    return true;
                }
                stepUpPhase = StepUpPhase.NONE;
                stepUpAirTicks = 0;
                stepUpLockTicks = Math.max(0, stepUpLockTicks - 1);
                return true;
            default:
                return false;
        }
    }

    private double calculateAscendJumpSpeed(double horizontalDist) {
        double speedFromCurrent = Math.max(currentSpeed * 1.12D, MIN_ASCEND_JUMP_SPEED);
        double speedFromDistance = horizontalDist * 0.42D + 0.12D;
        return Math.min(MAX_ASCEND_JUMP_SPEED, Math.max(MIN_ASCEND_JUMP_SPEED, Math.max(speedFromCurrent, speedFromDistance)));
    }

    private void maintainAirTraversalMotion(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null || targetPos == null) {
            releaseAirTraversalControl();
            return;
        }

        Vec3 currentPos = npc.position();
        Vec3 direction = targetPos.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.01D) {
            releaseAirTraversalControl();
            return;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        double currentHorizontalSpeed = Math.sqrt(npc.getDeltaMovement().x * npc.getDeltaMovement().x + npc.getDeltaMovement().z * npc.getDeltaMovement().z);
        double targetSpeed = targetNode.action == NPCPathNode.MovementAction.ASCEND
                ? calculateAscendJumpSpeed(horizontalDist)
                : Math.max(MIN_AIR_TRAVERSAL_SPEED, Math.min(Math.max(currentSpeed * 1.08D, currentHorizontalSpeed), horizontalDist * 0.34D + 0.04D));

        faceMovementDirection(dx, dz, 0.75F);
        applyDirectedTraversalMotion(dx, dz, Math.max(currentHorizontalSpeed, targetSpeed), npc.getDeltaMovement().y);
    }

    private void applyDirectedTraversalMotion(double dx, double dz, double speed, double yMotion) {
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0D);
        npc.setDeltaMovement(dx * speed, yMotion, dz * speed);
        npc.setSpeed((float) speed);
        npc.setZza((float) speed);
        npc.setXxa(0.0F);
    }

    private double getHighestCollisionTopAt(BlockPos blockPos, double worldX, double worldZ) {
        BlockState state = level.getBlockState(blockPos);
        VoxelShape shape = state.getCollisionShape(level, blockPos);
        if (shape.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        double localX = worldX - blockPos.getX();
        double localZ = worldZ - blockPos.getZ();
        double top = getTopFromSurfaceRegion(shape.toAabbs(), localX, localZ);
        return top == Double.NEGATIVE_INFINITY ? top : blockPos.getY() + top;
    }

    private double getTopFromSurfaceRegion(java.util.List<AABB> boxes, double localX, double localZ) {
        AABB selectedRegion = null;
        double selectedArea = Double.MAX_VALUE;
        double selectedTop = Double.NEGATIVE_INFINITY;
        for (AABB box : boxes) {
            if (!containsHorizontal(box, localX, localZ)) {
                continue;
            }
            double area = Math.max(0.0D, box.maxX - box.minX) * Math.max(0.0D, box.maxZ - box.minZ);
            if (selectedRegion == null || area < selectedArea || (Math.abs(area - selectedArea) <= 1.0E-5D && box.maxY < selectedTop)) {
                selectedRegion = box;
                selectedArea = area;
                selectedTop = box.maxY;
            }
        }
        if (selectedRegion == null) {
            return Double.NEGATIVE_INFINITY;
        }
        for (AABB box : boxes) {
            if (box.maxY <= selectedTop + 1.0E-5D) {
                continue;
            }
            if (sameHorizontalRegion(selectedRegion, box)) {
                selectedTop = box.maxY;
            }
        }
        return selectedTop;
    }

    private boolean containsHorizontal(AABB box, double localX, double localZ) {
        return localX >= box.minX - 1.0E-5D && localX <= box.maxX + 1.0E-5D
                && localZ >= box.minZ - 1.0E-5D && localZ <= box.maxZ + 1.0E-5D;
    }

    private boolean sameHorizontalRegion(AABB a, AABB b) {
        return Math.abs(a.minX - b.minX) <= 1.0E-5D
                && Math.abs(a.maxX - b.maxX) <= 1.0E-5D
                && Math.abs(a.minZ - b.minZ) <= 1.0E-5D
                && Math.abs(a.maxZ - b.maxZ) <= 1.0E-5D;
    }

    private void resetStepUpState() {
        stepUpPhase = StepUpPhase.NONE;
        stepUpLockTicks = 0;
        stepUpAirTicks = 0;
    }

    private void stopVanillaMovement() {
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0D);
        npc.setSpeed(0.0F);
        npc.setZza(0.0F);
        npc.setXxa(0.0F);
    }

    private void releaseAirTraversalControl() {
        stopVanillaMovement();
    }

    private boolean executeFallAction(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null) {
            return false;
        }
        currentSpeed = WALK_MOVE_SPEED;
        double horizontalDist = horizontalDistance(npc.position(), targetPos);
        if (horizontalDist > 0.45D) {
            moveTowards(new Vec3(targetPos.x, npc.getY(), targetPos.z));
            return true;
        }
        if (!npc.onGround()) {
            releaseAirTraversalControl();
            return true;
        }
        moveTowards(targetPos);
        return true;
    }

    private double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 检查前往目标的移动是否被阻挡
     */
    private boolean isMovementBlocked(Vec3 target) {
        Vec3 currentPos = npc.position();
        Vec3 direction = target.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);

        if (horizontalDist < 0.01) {
            currentObstacleType = ObstacleType.NONE;
            return false;
        }

        double stepDistance = Math.min(0.6D, horizontalDist);
        double dx = direction.x / horizontalDist * stepDistance;
        double dz = direction.z / horizontalDist * stepDistance;

        BlockPos footPos = BlockPos.containing(currentPos.x + dx, currentPos.y, currentPos.z + dz);
        BlockPos headPos = footPos.above();

        BlockState footState = level.getBlockState(footPos);
        BlockState headState = level.getBlockState(headPos);

        if (isDoorPassable(footState) || isDoorPassable(headState)) {
            currentObstacleType = ObstacleType.NONE;
            return false;
        }

        if (isVanillaAutoStepNear(footPos)) {
            currentObstacleType = ObstacleType.NONE;
            return false;
        }

        if (isClosedDoorBlock(footState) || isClosedDoorBlock(headState)) {
            currentObstacleType = ObstacleType.DOOR_CLOSED;
            return true;
        }

        if (hasNpcBlocker(footPos, headPos)) {
            currentObstacleType = ObstacleType.NPC_BLOCKER;
            return true;
        }

        AABB projectedBodyBox = createCollisionCheckBox(currentPos.x + dx, currentPos.y, currentPos.z + dz);
        boolean footBlocked = hasBlockingCollision(footState, footPos, projectedBodyBox);
        boolean headBlocked = hasBlockingCollision(headState, headPos, projectedBodyBox);
        boolean blocked = footBlocked || headBlocked;
        if (!blocked) {
            currentObstacleType = ObstacleType.NONE;
            return false;
        }

        double collisionHeight = footBlocked ? getCollisionHeight(footState, footPos) : 0.0D;
        if (footBlocked && isLowStepHeight(collisionHeight) && !headBlocked) {
            currentObstacleType = ObstacleType.STEP_UP;
        } else if (headBlocked) {
            currentObstacleType = ObstacleType.TIGHT_SPACE;
        } else {
            currentObstacleType = ObstacleType.SOLID_BLOCK;
        }
        return true;
    }

    private boolean tryCalibratedStepPass(Vec3 target) {
        StepCollisionMeasure measure = measureForwardStepCollision(target);
        if (measure == null || !isLowStepHeight(measure.height)) {
            return false;
        }
        if (!measure.headClear || !isBodyPathClearAt(measure.calibratedX, npc.getY() + measure.height, measure.calibratedZ)) {
            return false;
        }

        Vec3 currentPos = npc.position();
        double toCenterX = measure.calibratedX - currentPos.x;
        double toCenterZ = measure.calibratedZ - currentPos.z;
        double centerDistance = Math.sqrt(toCenterX * toCenterX + toCenterZ * toCenterZ);
        double targetDirectionX = target.x - currentPos.x;
        double targetDirectionZ = target.z - currentPos.z;
        double targetDistance = Math.sqrt(targetDirectionX * targetDirectionX + targetDirectionZ * targetDirectionZ);
        if (targetDistance < 0.01D) {
            return false;
        }

        double moveX;
        double moveZ;
        if (centerDistance > 0.08D) {
            moveX = toCenterX / centerDistance;
            moveZ = toCenterZ / centerDistance;
        } else {
            moveX = targetDirectionX / targetDistance;
            moveZ = targetDirectionZ / targetDistance;
        }

        double speed = Math.max(0.035D, Math.min(WALK_MOVE_SPEED, Math.max(centerDistance, targetDistance) * 0.22D));
        double yMotion = npc.getDeltaMovement().y;
        if (centerDistance <= 0.18D && npc.onGround()) {
            yMotion = Math.max(yMotion, Math.min(0.035D, measure.height * 0.2D));
        }

        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0D);
        npc.setDeltaMovement(moveX * speed, yMotion, moveZ * speed);
        npc.setSpeed((float) speed);
        npc.setZza((float) speed);
        npc.setXxa(0.0F);
        logStepMove("CALIBRATED_STEP_PASS", currentPath != null ? currentPath.getCurrentNode() : null, target, targetDistance, measure.height);
        return true;
    }

    private StepCollisionMeasure measureForwardStepCollision(Vec3 target) {
        Vec3 currentPos = npc.position();
        Vec3 direction = target.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.01D) {
            return null;
        }
        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        double probeDistance = Math.min(0.6D, horizontalDist);
        double probeX = currentPos.x + dx * probeDistance;
        double probeZ = currentPos.z + dz * probeDistance;
        BlockPos footPos = BlockPos.containing(probeX, currentPos.y, probeZ);
        BlockState footState = level.getBlockState(footPos);
        BlockState headState = level.getBlockState(footPos.above());
        VoxelShape shape = footState.getCollisionShape(level, footPos);
        if (shape.isEmpty() || !isOpenSpacePassable(headState, footPos.above())) {
            return null;
        }
        AABB selectedBox = null;
        double selectedHeight = 0.0D;
        double localX = probeX - footPos.getX();
        double localZ = probeZ - footPos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (localX >= box.minX - 0.05D && localX <= box.maxX + 0.05D
                    && localZ >= box.minZ - 0.05D && localZ <= box.maxZ + 0.05D
                    && box.maxY > selectedHeight) {
                selectedBox = box;
                selectedHeight = box.maxY;
            }
        }
        if (selectedBox == null) {
            return null;
        }
        double calibratedX = footPos.getX() + Math.max(selectedBox.minX + 0.08D, Math.min(selectedBox.maxX - 0.08D, localX));
        double calibratedZ = footPos.getZ() + Math.max(selectedBox.minZ + 0.08D, Math.min(selectedBox.maxZ - 0.08D, localZ));
        return new StepCollisionMeasure(selectedHeight, calibratedX, calibratedZ, true);
    }

    private boolean isBodyPathClearAt(double x, double y, double z) {
        AABB bodyBox = createCollisionCheckBox(x, y, z);
        int minX = (int) Math.floor(bodyBox.minX);
        int maxX = (int) Math.floor(bodyBox.maxX);
        int minY = (int) Math.floor(bodyBox.minY);
        int maxY = (int) Math.floor(bodyBox.maxY);
        int minZ = (int) Math.floor(bodyBox.minZ);
        int maxZ = (int) Math.floor(bodyBox.maxZ);
        for (int xPos = minX; xPos <= maxX; xPos++) {
            for (int yPos = minY; yPos <= maxY; yPos++) {
                for (int zPos = minZ; zPos <= maxZ; zPos++) {
                    BlockPos pos = new BlockPos(xPos, yPos, zPos);
                    BlockState state = level.getBlockState(pos);
                    if (hasBlockingCollision(state, pos, bodyBox)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static class StepCollisionMeasure {
        private final double height;
        private final double calibratedX;
        private final double calibratedZ;
        private final boolean headClear;

        private StepCollisionMeasure(double height, double calibratedX, double calibratedZ, boolean headClear) {
            this.height = height;
            this.calibratedX = calibratedX;
            this.calibratedZ = calibratedZ;
            this.headClear = headClear;
        }
    }

    private boolean tryResolveCrowdedPath(Vec3 targetPos) {
        if (currentObstacleType != ObstacleType.NPC_BLOCKER) {
            narrowPassWaitTicks = 0;
            return false;
        }

        java.util.List<CustomEntity> blockers = getCrowdBlockers();
        if (blockers.isEmpty()) {
            resetCrowdState();
            return false;
        }

        CustomEntity nearestBlocker = findNearestBlocker(blockers);
        if (nearestBlocker == null) {
            resetCrowdState();
            return false;
        }

        Vec3 blockerPos = nearestBlocker.position();
        if (lastCrowdBlockerPos != null && lastCrowdBlockerPos.distanceToSqr(blockerPos) < 0.04D) {
            crowdWaitTicks++;
        } else {
            crowdWaitTicks = 1;
            crowdBypassTicks = 0;
            lastCrowdBlockerPos = blockerPos;
        }

        if (shouldYieldInTightPassage(nearestBlocker, targetPos)) {
            narrowPassWaitTicks++;
            if (narrowPassWaitTicks <= 12) {
                stopVanillaMovement();
                return true;
            }
        } else {
            narrowPassWaitTicks = 0;
        }

        if (crowdWaitTicks <= 8) {
            stopVanillaMovement();
            return true;
        }

        crowdBypassTicks++;
        if (tryBypassCrowd(targetPos, nearestBlocker, crowdBypassTicks)) {
            return true;
        }

        if (crowdWaitTicks <= 16) {
            stopVanillaMovement();
            return true;
        }

        return false;
    }

    private boolean tryForwardJumpRecovery(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetPos == null) {
            return false;
        }
        if (targetNode != null && targetNode.action == NPCPathNode.MovementAction.JUMP_OVER) {
            return executeJumpOverAction(targetNode, targetPos);
        }
        if (!npc.onGround()) {
            return false;
        }

        Vec3 currentPos = npc.position();
        Vec3 direction = targetPos.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.2D) {
            return false;
        }

        int stepX = 0;
        int stepZ = 0;
        if (Math.abs(direction.x) >= Math.abs(direction.z)) {
            stepX = Integer.compare((int) Math.round(direction.x * 1000.0D), 0);
        } else {
            stepZ = Integer.compare((int) Math.round(direction.z * 1000.0D), 0);
        }
        if (stepX == 0 && stepZ == 0) {
            return false;
        }

        BlockPos obstaclePos = npc.blockPosition().offset(stepX, 0, stepZ);
        if (!isJumpableBarrier(obstaclePos)) {
            return false;
        }

        BlockPos landingPos = findForwardJumpLandingPos(obstaclePos, stepX, stepZ);
        if (landingPos == null) {
            return false;
        }

        Vec3 landingTarget = new Vec3(landingPos.getX() + 0.5D, Math.max(targetPos.y, landingPos.getY()), landingPos.getZ() + 0.5D);
        faceMovementDirection(landingTarget.x - currentPos.x, landingTarget.z - currentPos.z, 0.75F);
        triggerGroundJump(0.42D);
        double speed = Math.max(currentSpeed, 0.24D);
        double length = Math.sqrt((landingTarget.x - currentPos.x) * (landingTarget.x - currentPos.x)
                + (landingTarget.z - currentPos.z) * (landingTarget.z - currentPos.z));
        if (length < 0.01D) {
            return false;
        }
        double motionX = (landingTarget.x - currentPos.x) / length * speed;
        double motionZ = (landingTarget.z - currentPos.z) / length * speed;
        double motionY = Math.max(npc.getDeltaMovement().y, 0.42D);
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0D);
        npc.setDeltaMovement(motionX, motionY, motionZ);
        npc.setSpeed((float) speed);
        npc.setZza((float) speed);
        npc.setXxa(0.0F);
        logStepMove("STUCK_FORWARD_JUMP_RECOVERY", targetNode, landingTarget, horizontalDist, landingPos.getY() - npc.getY());
        return true;
    }

    private java.util.List<CustomEntity> getCrowdBlockers() {
        Vec3 currentMotion = npc.getDeltaMovement();
        Vec3 forwardMotion = new Vec3(currentMotion.x, 0.0D, currentMotion.z);
        double forwardLengthSqr = forwardMotion.lengthSqr();
        Vec3 forwardOffset = forwardLengthSqr < 1.0E-4D ? Vec3.ZERO : forwardMotion.normalize().scale(0.35D);
        AABB frontBox = npc.getBoundingBox().inflate(0.2D, 0.1D, 0.2D).move(forwardOffset);
        return level.getEntitiesOfClass(CustomEntity.class, frontBox,
                other -> other != npc && other.isAlive() && !other.isSleeping());
    }

    private CustomEntity findNearestBlocker(java.util.List<CustomEntity> blockers) {
        CustomEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        Vec3 npcPos = npc.position();
        for (CustomEntity blocker : blockers) {
            double distance = blocker.position().distanceToSqr(npcPos);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = blocker;
            }
        }
        return nearest;
    }

    private boolean shouldYieldInTightPassage(CustomEntity blocker, Vec3 targetPos) {
        if (blocker == null) {
            return false;
        }

        Vec3 targetDirection = targetPos.subtract(npc.position());
        double targetHorizontalLength = Math.sqrt(targetDirection.x * targetDirection.x + targetDirection.z * targetDirection.z);
        if (targetHorizontalLength < 1.0E-4D) {
            return false;
        }

        Vec3 blockerMotion = blocker.getDeltaMovement();
        Vec3 blockerHorizontalMotion = new Vec3(blockerMotion.x, 0.0D, blockerMotion.z);
        double blockerMotionLength = blockerHorizontalMotion.length();
        if (blockerMotionLength < 0.02D) {
            return false;
        }

        double dirX = targetDirection.x / targetHorizontalLength;
        double dirZ = targetDirection.z / targetHorizontalLength;
        double blockerDirX = blockerHorizontalMotion.x / blockerMotionLength;
        double blockerDirZ = blockerHorizontalMotion.z / blockerMotionLength;
        double facingDot = dirX * blockerDirX + dirZ * blockerDirZ;
        if (facingDot > -0.25D) {
            return false;
        }

        BlockPos npcPos = npc.blockPosition();
        BlockPos blockerPos = blocker.blockPosition();
        double centerDistance = blocker.position().distanceTo(npc.position());
        if (centerDistance > 1.6D) {
            return false;
        }

        boolean tightWidth = Math.abs(npcPos.getX() - blockerPos.getX()) <= 1 || Math.abs(npcPos.getZ() - blockerPos.getZ()) <= 1;
        if (!tightWidth) {
            return false;
        }

        if (narrowPassBias == 0) {
            narrowPassBias = Integer.compare(npc.getId(), blocker.getId());
        }
        return narrowPassBias > 0;
    }

    private boolean tryBypassCrowd(Vec3 targetPos, CustomEntity blocker, int attemptTicks) {
        Vec3 direction = targetPos.subtract(npc.position());
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.01D) {
            return false;
        }

        double forwardX = direction.x / horizontalDist;
        double forwardZ = direction.z / horizontalDist;
        double sideX = -forwardZ;
        double sideZ = forwardX;
        boolean preferLeft = shouldPreferLeftBypass(blocker, sideX, sideZ);
        double lateralStrength = Math.min(0.22D, 0.08D + attemptTicks * 0.02D);
        double forwardStrength = Math.max(currentSpeed * 0.45D, 0.08D);

        if (tryCrowdSideStep(targetPos, sideX, sideZ, forwardX, forwardZ, lateralStrength, forwardStrength, preferLeft)) {
            return true;
        }
        if (tryCrowdSideStep(targetPos, sideX, sideZ, forwardX, forwardZ, lateralStrength, forwardStrength, !preferLeft)) {
            return true;
        }
        return false;
    }

    private boolean shouldPreferLeftBypass(CustomEntity blocker, double sideX, double sideZ) {
        Vec3 relative = blocker.position().subtract(npc.position());
        double sideDot = relative.x * sideX + relative.z * sideZ;
        return sideDot <= 0.0D;
    }

    private boolean tryCrowdSideStep(Vec3 targetPos, double sideX, double sideZ, double forwardX, double forwardZ,
                                     double lateralStrength, double forwardStrength, boolean leftSide) {
        double directionFactor = leftSide ? 1.0D : -1.0D;
        double actualSideX = sideX * directionFactor;
        double actualSideZ = sideZ * directionFactor;
        Vec3 currentPos = npc.position();

        if (!canMoveSideways(currentPos, actualSideX, actualSideZ, 0.75D, forwardX, forwardZ, 0.35D)) {
            return false;
        }

        Vec3 bypassTarget = currentPos.add(
                actualSideX * Math.max(0.55D, lateralStrength * 4.0D) + forwardX * Math.max(0.35D, forwardStrength * 2.5D),
                0.0D,
                actualSideZ * Math.max(0.55D, lateralStrength * 4.0D) + forwardZ * Math.max(0.35D, forwardStrength * 2.5D)
        );
        faceMovementDirection(bypassTarget.x - currentPos.x, bypassTarget.z - currentPos.z, 0.7F);
        moveTowards(bypassTarget);
        return true;
    }

    private void resetCrowdState() {
        crowdWaitTicks = 0;
        crowdBypassTicks = 0;
        narrowPassWaitTicks = 0;
        narrowPassBias = 0;
        lastCrowdBlockerPos = null;
    }

    private boolean tryBypassObstacle(Vec3 target) {
        Vec3 currentPos = npc.position();
        Vec3 direction = target.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.01) {
            return false;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        double leftX = -dz;
        double leftZ = dx;
        double rightX = dz;
        double rightZ = -dx;

        if (trySideBypassMove(currentPos, leftX, leftZ, dx, dz)) {
            return true;
        }

        if (trySideBypassMove(currentPos, rightX, rightZ, dx, dz)) {
            return true;
        }

        return false;
    }

    private boolean trySideBypassMove(Vec3 currentPos, double sideX, double sideZ, double forwardX, double forwardZ) {
        double[] sideSteps = new double[] {0.8D, 1.2D};
        double[] forwardSteps = new double[] {0.4D, 0.8D};

        for (double sideStep : sideSteps) {
            for (double forwardStep : forwardSteps) {
                if (canMoveSideways(currentPos, sideX, sideZ, sideStep, forwardX, forwardZ, forwardStep)) {
                    Vec3 bypassTarget = currentPos.add(
                            sideX * sideStep + forwardX * forwardStep,
                            0.0D,
                            sideZ * sideStep + forwardZ * forwardStep
                    );
                    faceMovementDirection(bypassTarget.x - currentPos.x, bypassTarget.z - currentPos.z, 0.7F);
                    moveTowards(bypassTarget);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean canMoveSideways(Vec3 currentPos, double sideX, double sideZ, double sideStep, double forwardX, double forwardZ, double forwardStep) {
        BlockPos footPos = BlockPos.containing(
                currentPos.x + sideX * sideStep + forwardX * forwardStep,
                currentPos.y,
                currentPos.z + sideZ * sideStep + forwardZ * forwardStep
        );
        BlockPos headPos = footPos.above();
        BlockPos groundPos = footPos.below();

        BlockState footState = level.getBlockState(footPos);
        BlockState headState = level.getBlockState(headPos);
        BlockState groundState = level.getBlockState(groundPos);

        return (isOpenSpacePassable(footState, footPos) || isLowStepCollision(footState, footPos))
                && isOpenSpacePassable(headState, headPos)
                && !groundState.getCollisionShape(level, groundPos).isEmpty();
    }

    private boolean isLowStepCollision(BlockState state, BlockPos pos) {
        double collisionHeight = getCollisionHeight(state, pos);
        return isLowStepHeight(collisionHeight);
    }

    private boolean isJumpableBarrier(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || isDoorPassable(state) || isClosedDoorBlock(state)) {
            return false;
        }

        double collisionHeight = getCollisionHeight(state, pos);
        if (collisionHeight <= WALK_STEP_HEIGHT + 0.05D || collisionHeight > MAX_JUMP_OVER_HEIGHT) {
            return false;
        }

        BlockPos abovePos = pos.above();
        BlockPos twoAbovePos = pos.above(2);
        if (requiresJumpCoverToClear(state) && !isThinWalkableCover(level.getBlockState(abovePos), abovePos)) {
            return false;
        }
        return isJumpCoverPassable(abovePos)
                && isOpenSpacePassable(level.getBlockState(twoAbovePos), twoAbovePos);
    }

    private BlockPos findForwardJumpLandingPos(BlockPos obstaclePos, int stepX, int stepZ) {
        for (int dy = 1; dy >= -1; dy--) {
            BlockPos candidate = obstaclePos.offset(stepX, dy, stepZ);
            if (isLandingSpotClear(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isJumpCoverPassable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isOpenSpacePassable(state, pos)) {
            return true;
        }
        return isThinWalkableCover(state, pos);
    }

    private boolean isThinWalkableCover(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof CarpetBlock) {
            return true;
        }
        return getCollisionHeight(state, pos) <= 0.125D + 1.0E-5D;
    }

    private boolean requiresJumpCoverToClear(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FenceBlock || block instanceof WallBlock || block instanceof IronBarsBlock;
    }

    private boolean isLandingSpotClear(BlockPos footPos) {
        BlockPos headPos = footPos.above();
        BlockPos groundPos = footPos.below();
        BlockState footState = level.getBlockState(footPos);
        BlockState headState = level.getBlockState(headPos);
        BlockState groundState = level.getBlockState(groundPos);

        if (!isOpenSpacePassable(footState, footPos) || !isOpenSpacePassable(headState, headPos)) {
            return false;
        }
        if (groundState.getCollisionShape(level, groundPos).isEmpty()) {
            return false;
        }
        return isBodyPathClearAt(footPos.getX() + 0.5D, footPos.getY(), footPos.getZ() + 0.5D);
    }

    private boolean isAscendLandingPassable(BlockPos footPos) {
        BlockState footState = level.getBlockState(footPos);
        if (isOpenSpacePassable(footState, footPos)) {
            return true;
        }
        return isThinWalkableCover(footState, footPos);
    }

    private BlockPos findPrimaryObstaclePos(Vec3 currentPos, double dx, double dz, double probeDistance) {
        return BlockPos.containing(currentPos.x + dx * probeDistance, currentPos.y, currentPos.z + dz * probeDistance);
    }

    private BlockPos findJumpBarrierOnPath(Vec3 currentPos, double dx, double dz, double horizontalDist) {
        double[] probeDistances = new double[] {0.35D, 0.55D, 0.75D, 0.95D, 1.15D};
        double maxProbeDistance = Math.min(1.15D, Math.max(0.35D, horizontalDist));
        for (double probeDistance : probeDistances) {
            if (probeDistance > maxProbeDistance + 1.0E-5D) {
                continue;
            }
            BlockPos candidate = findPrimaryObstaclePos(currentPos, dx, dz, probeDistance);
            if (isJumpableBarrier(candidate)) {
                return candidate;
            }
            BlockPos candidateBelow = candidate.below();
            if (isJumpableBarrier(candidateBelow)) {
                return candidateBelow;
            }
        }
        return null;
    }

    private void triggerGroundJump(double minYVelocity) {
        if (!npc.onGround()) {
            return;
        }
        npc.doJump();
        Vec3 motion = npc.getDeltaMovement();
        if (motion.y < minYVelocity) {
            npc.setDeltaMovement(motion.x, minYVelocity, motion.z);
        }
    }

    private boolean isLowStepHeight(double collisionHeight) {
        return collisionHeight > 0.0D && collisionHeight <= WALK_STEP_HEIGHT + 0.05D;
    }

    private boolean isVanillaAutoStepBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof SlabBlock || block instanceof StairBlock;
    }

    private boolean isVanillaAutoStepNear(BlockPos footPos) {
        return isVanillaAutoStepBlock(level.getBlockState(footPos))
                || isVanillaAutoStepBlock(level.getBlockState(footPos.below()))
                || isVanillaAutoStepBlock(level.getBlockState(footPos.above()))
                || isVanillaAutoStepBlock(level.getBlockState(BlockPos.containing(footPos.getX() + 0.5D, npc.getY() - 0.05D, footPos.getZ() + 0.5D)));
    }

    private boolean isOnVanillaAutoStepBlock() {
        BlockPos feet = npc.blockPosition();
        return isVanillaAutoStepBlock(level.getBlockState(feet))
                || isVanillaAutoStepBlock(level.getBlockState(feet.below()))
                || isVanillaAutoStepBlock(level.getBlockState(BlockPos.containing(npc.getX(), npc.getY() - 0.1D, npc.getZ())));
    }

    private boolean isClosedDoorBlock(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock) {
            return !state.getValue(DoorBlock.OPEN);
        }
        if (block instanceof FenceGateBlock) {
            return !state.getValue(FenceGateBlock.OPEN);
        }
        return false;
    }

    private boolean hasNpcBlocker(BlockPos footPos, BlockPos headPos) {
        AABB blockerBox = new AABB(footPos).minmax(new AABB(headPos)).inflate(0.1D, 0.0D, 0.1D);
        return !level.getEntitiesOfClass(CustomEntity.class, blockerBox,
                other -> other != npc && other.isAlive() && !other.isSleeping()).isEmpty();
    }

    private boolean isDoorPassable(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock) {
            return state.getValue(DoorBlock.OPEN);
        }
        if (block instanceof FenceGateBlock) {
            return state.getValue(FenceGateBlock.OPEN);
        }
        return false;
    }

    private boolean isOpenSpacePassable(BlockState state, BlockPos pos) {
        return state.getCollisionShape(level, pos).isEmpty() || isDoorPassable(state) || isOpenTrapdoor(state);
    }

    private double getCollisionHeight(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return 0.0D;
        }
        double height = 0.0D;
        for (AABB box : shape.toAabbs()) {
            height = Math.max(height, box.maxY);
        }
        return height;
    }

    private boolean isStairOrSlabTraversal(NPCPathNode node) {
        if (node == null || (node.action != NPCPathNode.MovementAction.TRAVERSE && node.action != NPCPathNode.MovementAction.ASCEND)) {
            return false;
        }
        return isVanillaAutoStepNear(node.pos) || isOnVanillaAutoStepBlock();
    }

    private boolean shouldUseAutoStepTraversal(NPCPathNode node, Vec3 targetPos) {
        if (!isStairOrSlabTraversal(node)) {
            return false;
        }
        if (node == null) {
            return false;
        }
        if (node.action == NPCPathNode.MovementAction.TRAVERSE) {
            return true;
        }
        return !shouldAscendOnAutoStep(node, targetPos);
    }

    private void moveHorizontallyWithoutJump(Vec3 target, NPCPathNode targetNode) {
        Vec3 currentPos = npc.position();
        Vec3 direction = target.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.01D) {
            stopVanillaMovement();
            return;
        }
        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        double heightDelta = targetNode.standY - npc.getY();
        double speed = Math.max(0.04D, Math.min(currentSpeed, horizontalDist * 0.35D));
        Vec3 motion = npc.getDeltaMovement();
        double yMotion = motion.y;
        if (heightDelta > 0.03D && heightDelta <= WALK_STEP_HEIGHT + 0.05D && horizontalDist <= 0.5D && npc.onGround()) {
            yMotion = Math.max(yMotion, Math.min(0.04D, heightDelta * 0.25D));
            logStepMove("STAIR_OR_SLAB_LIFT", targetNode, target, horizontalDist, heightDelta);
        }
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0D);
        npc.setDeltaMovement(dx * speed, yMotion, dz * speed);
        npc.setSpeed((float) speed);
        npc.setZza((float) speed);
        npc.setXxa(0.0F);
        logStepMove("STAIR_OR_SLAB_DIRECT_MOTION", targetNode, target, horizontalDist, heightDelta);
    }

    private void logStepMove(String reason, NPCPathNode node, Vec3 target, double horizontalDist, double heightDelta) {
        if (!ServerConfig.isDebugLogEnabled()) {
            return;
        }
        Simukraft.LOGGER.info("[NPCMoveController][StepTrace] npc={} reason={} node={} action={} type={} npcPos=({},{},{}) target=({},{},{}) stand=({},{},{}) horizontalDist={} heightDelta={} onGround={} motion={} stairOrSlab={} stepPhase={} stuckTicks={} currentObstacle={}",
                npc.getFullName(), reason,
                node != null ? node.pos : null,
                node != null ? node.action : null,
                node != null ? node.type : null,
                npc.getX(), npc.getY(), npc.getZ(),
                target != null ? target.x : 0.0D,
                target != null ? target.y : 0.0D,
                target != null ? target.z : 0.0D,
                node != null ? node.standX : 0.0D,
                node != null ? node.standY : 0.0D,
                node != null ? node.standZ : 0.0D,
                horizontalDist, heightDelta, npc.onGround(), npc.getDeltaMovement(),
                node != null && isStairOrSlabTraversal(node), stepUpPhase, stuckTicks, currentObstacleType);
    }

    private void moveDirectlyAlongPath(Vec3 target) {
        Vec3 currentPos = npc.position();
        Vec3 direction = target.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.01D) {
            stopVanillaMovement();
            return;
        }
        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        double speed = Math.max(0.035D, Math.min(currentSpeed, horizontalDist * 0.35D));
        Vec3 motion = npc.getDeltaMovement();
        double moveX = dx * speed;
        double moveZ = dz * speed;
        Vec3 clippedMove = clipTraverseMove(moveX, moveZ);
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0D);
        npc.setDeltaMovement(clippedMove.x, motion.y, clippedMove.z);
        npc.setSpeed((float) Math.sqrt(clippedMove.x * clippedMove.x + clippedMove.z * clippedMove.z));
        npc.setZza((float) Math.sqrt(clippedMove.x * clippedMove.x + clippedMove.z * clippedMove.z));
        npc.setXxa(0.0F);
    }

    private Vec3 clipTraverseMove(double moveX, double moveZ) {
        if (!willCollideWithFullBlock(moveX, 0.0D, moveZ)) {
            return new Vec3(moveX, 0.0D, moveZ);
        }
        double clippedX = moveX;
        double clippedZ = moveZ;
        if (willCollideWithFullBlock(moveX, 0.0D, 0.0D)) {
            clippedX = 0.0D;
        }
        if (willCollideWithFullBlock(0.0D, 0.0D, moveZ)) {
            clippedZ = 0.0D;
        }
        if (clippedX != 0.0D || clippedZ != 0.0D) {
            return new Vec3(clippedX, 0.0D, clippedZ);
        }
        return Vec3.ZERO;
    }

    private boolean willCollideWithFullBlock(double moveX, double moveY, double moveZ) {
        AABB movedBox = npc.getBoundingBox().move(moveX, moveY, moveZ).inflate(0.01D, 0.0D, 0.01D);
        int minX = (int) Math.floor(movedBox.minX);
        int maxX = (int) Math.floor(movedBox.maxX);
        int minY = (int) Math.floor(movedBox.minY);
        int maxY = (int) Math.floor(movedBox.maxY);
        int minZ = (int) Math.floor(movedBox.minZ);
        int maxZ = (int) Math.floor(movedBox.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (hasBlockingCollision(state, pos, movedBox)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isFullBlockCollision(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty() || isDoorPassable(state)) {
            return false;
        }
        return true;
    }

    private AABB createCollisionCheckBox(double x, double y, double z) {
        return npc.getBoundingBox()
                .move(x - npc.getX(), y - npc.getY(), z - npc.getZ())
                .inflate(-BODY_COLLISION_XZ_SHRINK, 0.0D, -BODY_COLLISION_XZ_SHRINK);
    }

    private boolean hasBlockingCollision(BlockState state, BlockPos pos, AABB baseBodyBox) {
        if (!isFullBlockCollision(state, pos)) {
            return false;
        }
        AABB collisionCheckBox = getCollisionCheckBoxForState(state, pos, baseBodyBox);
        for (AABB box : state.getCollisionShape(level, pos).toAabbs()) {
            if (box.move(pos).intersects(collisionCheckBox)) {
                return true;
            }
        }
        return false;
    }

    private AABB getCollisionCheckBoxForState(BlockState state, BlockPos pos, AABB baseBodyBox) {
        if (!isOpenTrapdoor(state)) {
            return baseBodyBox;
        }
        double[] trapdoorBounds = getTrapdoorHorizontalBounds(state, pos);
        if (trapdoorBounds == null) {
            double centerX = (baseBodyBox.minX + baseBodyBox.maxX) * 0.5D;
            double centerZ = (baseBodyBox.minZ + baseBodyBox.maxZ) * 0.5D;
            return new AABB(centerX - OPEN_TRAPDOOR_BODY_RADIUS, baseBodyBox.minY, centerZ - OPEN_TRAPDOOR_BODY_RADIUS,
                    centerX + OPEN_TRAPDOOR_BODY_RADIUS, baseBodyBox.maxY, centerZ + OPEN_TRAPDOOR_BODY_RADIUS);
        }

        double minX = baseBodyBox.minX;
        double maxX = baseBodyBox.maxX;
        double minZ = baseBodyBox.minZ;
        double maxZ = baseBodyBox.maxZ;
        double centerX = (baseBodyBox.minX + baseBodyBox.maxX) * 0.5D;
        double centerZ = (baseBodyBox.minZ + baseBodyBox.maxZ) * 0.5D;
        double thicknessX = trapdoorBounds[1] - trapdoorBounds[0];
        double thicknessZ = trapdoorBounds[3] - trapdoorBounds[2];

        if (thicknessX <= thicknessZ && thicknessX <= 0.25D) {
            minZ = centerZ - Math.min(centerZ - minZ, OPEN_TRAPDOOR_BODY_RADIUS);
            maxZ = centerZ + Math.min(maxZ - centerZ, OPEN_TRAPDOOR_BODY_RADIUS);
            if ((trapdoorBounds[0] + trapdoorBounds[1]) * 0.5D <= centerX) {
                minX = centerX - Math.min(centerX - minX, OPEN_TRAPDOOR_PANEL_SIDE_RADIUS);
            } else {
                maxX = centerX + Math.min(maxX - centerX, OPEN_TRAPDOOR_PANEL_SIDE_RADIUS);
            }
        } else if (thicknessZ <= 0.25D) {
            minX = centerX - Math.min(centerX - minX, OPEN_TRAPDOOR_BODY_RADIUS);
            maxX = centerX + Math.min(maxX - centerX, OPEN_TRAPDOOR_BODY_RADIUS);
            if ((trapdoorBounds[2] + trapdoorBounds[3]) * 0.5D <= centerZ) {
                minZ = centerZ - Math.min(centerZ - minZ, OPEN_TRAPDOOR_PANEL_SIDE_RADIUS);
            } else {
                maxZ = centerZ + Math.min(maxZ - centerZ, OPEN_TRAPDOOR_PANEL_SIDE_RADIUS);
            }
        } else {
            minX = centerX - Math.min(centerX - minX, OPEN_TRAPDOOR_BODY_RADIUS);
            maxX = centerX + Math.min(maxX - centerX, OPEN_TRAPDOOR_BODY_RADIUS);
            minZ = centerZ - Math.min(centerZ - minZ, OPEN_TRAPDOOR_BODY_RADIUS);
            maxZ = centerZ + Math.min(maxZ - centerZ, OPEN_TRAPDOOR_BODY_RADIUS);
        }
        return new AABB(minX, baseBodyBox.minY, minZ, maxX, baseBodyBox.maxY, maxZ);
    }

    private double[] getTrapdoorHorizontalBounds(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        boolean found = false;
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (AABB box : shape.toAabbs()) {
            found = true;
            minX = Math.min(minX, box.minX);
            maxX = Math.max(maxX, box.maxX);
            minZ = Math.min(minZ, box.minZ);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        if (!found) {
            return null;
        }
        return new double[] {
                pos.getX() + minX,
                pos.getX() + maxX,
                pos.getZ() + minZ,
                pos.getZ() + maxZ
        };
    }

    private boolean isOpenTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN);
    }

    /**
     * 向目标位置移动
     */
    private void moveTowards(Vec3 target) {
        Vec3 currentPos = npc.position();
        Vec3 direction = target.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);

        if (horizontalDist < 0.01D) {
            npc.getMoveControl().setWantedPosition(target.x, target.y, target.z, 0.0D);
            npc.setSpeed(0.0F);
            return;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        faceMovementDirection(dx, dz, 0.45F);

        double speedModifier = Math.min(1.0D, Math.max(0.35D, currentSpeed / Math.max(0.01D, npc.getAttributeValue(Attributes.MOVEMENT_SPEED))));
        npc.getMoveControl().setWantedPosition(target.x, target.y, target.z, speedModifier);
        npc.setSpeed((float) (npc.getAttributeValue(Attributes.MOVEMENT_SPEED) * speedModifier));
    }
    
    private void syncFacingToCurrentMotion(float turnMultiplier) {
        Vec3 motion = npc.getDeltaMovement();
        double horizontalSpeedSqr = motion.x * motion.x + motion.z * motion.z;
        if (horizontalSpeedSqr < 1.0E-4D) {
            return;
        }
        double horizontalSpeed = Math.sqrt(horizontalSpeedSqr);
        faceMovementDirection(motion.x / horizontalSpeed, motion.z / horizontalSpeed, turnMultiplier);
    }

    private void faceMovementDirection(double dx, double dz, float turnMultiplier) {
        if (Math.abs(dx) < 1.0E-4D && Math.abs(dz) < 1.0E-4D) {
            return;
        }
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnDecisively(targetYaw, turnMultiplier);
    }

    private void turnDecisively(float targetYaw, float turnMultiplier) {
        float currentYaw = npc.getYRot();
        float deltaYaw = wrapDegrees(targetYaw - currentYaw);
        float absDelta = Math.abs(deltaYaw);
        float maxTurn = Math.max(MIN_SNAP_TURN_DEGREES, MAX_TURN_DEGREES_PER_TICK * Math.max(0.35F, turnMultiplier));
        float newYaw = absDelta <= maxTurn ? targetYaw : currentYaw + Math.copySign(maxTurn, deltaYaw);
        applyYaw(wrapDegrees(newYaw));
    }

    private float wrapDegrees(float degrees) {
        while (degrees > 180.0F) {
            degrees -= 360.0F;
        }
        while (degrees < -180.0F) {
            degrees += 360.0F;
        }
        return degrees;
    }

    private void applyYaw(float yaw) {
        npc.setYRot(yaw);
        npc.setYHeadRot(yaw);
        npc.yBodyRot = yaw;
        npc.yHeadRotO = yaw;
        npc.yBodyRotO = yaw;
    }

    /**
     * 检查是否卡住
     */
    private boolean checkStuck() {
        Vec3 currentPos = npc.position();
        double movedDistance = currentPos.distanceTo(lastPos);
        
        if (movedDistance < 0.01) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            lastPos = currentPos;
        }
        
        return stuckTicks >= STUCK_THRESHOLD;
    }
    
    /**
     * 处理卡住情况
     */
    private void handleStuck() {
        if (currentPath == null) {
            return;
        }
        NPCPathNode currentNode = currentPath.getCurrentNode();
        Vec3 currentTarget = currentPath.getCurrentTarget();
        boolean trapdoorTraversalScenario = isTrapdoorTraversalScenario(currentNode, currentTarget);
        if (currentNode != null && currentNode.action == NPCPathNode.MovementAction.ASCEND) {
            return;
        }
        if (currentNode != null && currentNode.action == NPCPathNode.MovementAction.TRAVERSE
                && (isOnVanillaAutoStepBlock() || isVanillaAutoStepNear(currentNode.pos))
                && !trapdoorTraversalScenario) {
            logStepMove("STUCK_ON_AUTO_STEP_SUPPRESSED", currentNode, currentTarget, horizontalDistance(npc.position(), currentTarget), currentNode.standY - npc.getY());
            stopVanillaMovement();
            stuckTicks = 0;
            return;
        }
        if (stuckTicks == STUCK_THRESHOLD || stuckTicks % STUCK_THRESHOLD == 0) {
            logTrapdoorContext("STUCK", currentNode, currentTarget);
        }

        if (trapdoorTraversalScenario && tryTrapdoorForwardHop(currentNode, currentTarget)) {
            stuckTicks = Math.max(STUCK_THRESHOLD / 2, 1);
            return;
        }

        if (tryForwardJumpRecovery(currentNode, currentTarget)) {
            stuckTicks = Math.max(STUCK_THRESHOLD / 2, 1);
            return;
        }

        if (currentTarget != null && tryBypassObstacle(currentTarget)) {
            stuckTicks = Math.max(STUCK_THRESHOLD / 2, 1);
            return;
        }

        if (npc.onGround()) {
            logStepMove("STUCK_JUMP_CONTROL", currentNode, currentTarget, horizontalDistance(npc.position(), currentTarget), currentNode != null ? currentNode.standY - npc.getY() : 0.0D);
            triggerGroundJump(0.42D);
        }
        
        // 如果卡住太久，放弃当前路径
        if (stuckTicks >= STUCK_THRESHOLD * 3) {
            stop();
        }
    }

    private void logTrapdoorContext(String reason, NPCPathNode currentNode, Vec3 currentTarget) {
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        Vec3 currentPos = npc.position();
        BlockPos[] candidates = new BlockPos[] {
                npc.blockPosition(),
                npc.blockPosition().above(),
                npc.blockPosition().relative(npc.getDirection()),
                npc.blockPosition().relative(npc.getDirection()).above(),
                currentTarget != null ? BlockPos.containing(currentTarget) : null,
                currentTarget != null ? BlockPos.containing(currentTarget).above() : null
        };
        for (BlockPos candidate : candidates) {
            if (candidate == null || !visited.add(candidate)) {
                continue;
            }
            BlockState state = level.getBlockState(candidate);
            if (!isOpenTrapdoor(state)) {
                continue;
            }
            Simukraft.LOGGER.warn("[NPCMoveController][TrapdoorTrace] reason={} npc={} trapdoor={} open={} facing={} half={} obstacleType={} stuckTicks={} npcPos={} target={} node={} stand=({},{},{}) shapeBoxes={}",
                    reason,
                    npc.getFullName(),
                    candidate,
                    state.getValue(TrapDoorBlock.OPEN),
                    state.getValue(TrapDoorBlock.FACING),
                    state.getValue(TrapDoorBlock.HALF),
                    currentObstacleType,
                    stuckTicks,
                    currentPos,
                    currentTarget,
                    currentNode != null ? currentNode.pos : null,
                    currentNode != null ? currentNode.standX : 0.0D,
                    currentNode != null ? currentNode.standY : 0.0D,
                    currentNode != null ? currentNode.standZ : 0.0D,
                    state.getCollisionShape(level, candidate).toAabbs());
            return;
        }
    }

    private boolean isTrapdoorTraversalScenario(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null || targetPos == null) {
            return false;
        }
        if (!(isOnVanillaAutoStepBlock() || isVanillaAutoStepNear(targetNode.pos) || targetNode.action == NPCPathNode.MovementAction.ASCEND)) {
            return false;
        }
        return hasOpenTrapdoorNear(npc.blockPosition())
                || hasOpenTrapdoorNear(targetNode.pos)
                || hasOpenTrapdoorNear(BlockPos.containing(targetPos))
                || hasOpenTrapdoorNear(npc.blockPosition().relative(npc.getDirection()));
    }

    private boolean hasOpenTrapdoorNear(BlockPos center) {
        if (center == null) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (isOpenTrapdoor(level.getBlockState(pos))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * 处理门交互
     */
    private void handleDoorInteraction() {
        if (doorInteractCooldown > 0) return;

        BlockPos[] doorCheckPositions = new BlockPos[] {
                npc.blockPosition(),
                npc.blockPosition().above(),
                npc.blockPosition().relative(npc.getDirection()),
                npc.blockPosition().relative(npc.getDirection()).above()
        };

        for (BlockPos checkPos : doorCheckPositions) {
            if (tryOpenDoorAt(checkPos)) {
                return;
            }
        }
    }

    private boolean tryOpenDoorAt(BlockPos doorPos) {
        BlockState state = level.getBlockState(doorPos);
        Block block = state.getBlock();

        // menglannnn: 修复NPC可以打开铁门的问题
        // 铁门需要红石信号才能打开，NPC不应该能直接打开
        if (block instanceof DoorBlock && block != Blocks.IRON_DOOR) {
            if (!state.getValue(DoorBlock.OPEN)) {
                openDoor(doorPos, state);
            }
            return true;
        }

        if (block instanceof FenceGateBlock) {
            if (!state.getValue(FenceGateBlock.OPEN)) {
                level.setBlock(doorPos, state.setValue(FenceGateBlock.OPEN, true), 10);
                if (fenceGateTracker != null) {
                    fenceGateTracker.accept(doorPos);
                }
                doorInteractCooldown = 20;
            }
            return true;
        }

        return false;
    }

    private void openDoor(BlockPos doorPos, BlockState doorState) {
        BlockPos lowerHalfPos = doorState.getValue(DoorBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? doorPos : doorPos.below();
        BlockState lowerState = level.getBlockState(lowerHalfPos);
        BlockState upperState = level.getBlockState(lowerHalfPos.above());

        if (!(lowerState.getBlock() instanceof DoorBlock) || !(upperState.getBlock() instanceof DoorBlock)) {
            return;
        }

        level.setBlock(lowerHalfPos, lowerState.setValue(DoorBlock.OPEN, true), 10);
        level.setBlock(lowerHalfPos.above(), upperState.setValue(DoorBlock.OPEN, true), 10);
        doorInteractCooldown = 20;
    }
    
    /**
     * 与门交互（打开/关闭）
     */
    private void interactWithDoor(BlockPos doorPos) {
        tryOpenDoorAt(doorPos);
    }
    
    /**
     * 计算移动速度
     */
    private double calculateSpeed() {
        return calculateSpeed(null);
    }

    private double calculateSpeed(Vec3 targetPos) {
        if (npc.isSleeping()) {
            return 0;
        }
        double baseSpeed = npc.getAttributeValue(Attributes.MOVEMENT_SPEED);
        boolean running = shouldRunToTarget(targetPos);
        updateFatigueState(running);
        double targetSpeed = running ? RUN_MOVE_SPEED : WALK_MOVE_SPEED;
        if (running && fatigueTicks > 0) {
            targetSpeed = fatigueSpeed;
        }
        double maxAllowedSpeed = running ? baseSpeed * SPRINT_ATTRIBUTE_MULTIPLIER : baseSpeed;
        return Math.min(maxAllowedSpeed, targetSpeed);
    }

    private void updateFatigueState(boolean running) {
        if (fatigueCooldownTicks > 0) {
            fatigueCooldownTicks--;
        }
        if (!running) {
            runningTicks = 0;
            if (fatigueTicks > 0) {
                fatigueTicks--;
            }
            return;
        }
        if (fatigueTicks > 0) {
            fatigueTicks--;
            runningTicks = Math.max(0, runningTicks - 1);
            if (fatigueTicks == 0) {
                fatigueCooldownTicks = FATIGUE_RECOVERY_TICKS;
                fatigueTriggerTicks = nextFatigueTriggerTicks();
            }
            return;
        }
        runningTicks++;
        if (fatigueCooldownTicks <= 0 && runningTicks >= fatigueTriggerTicks && ThreadLocalRandom.current().nextDouble() < FATIGUE_TRIGGER_CHANCE) {
            startFatigueSlowdown();
        }
    }

    private void startFatigueSlowdown() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        fatigueTicks = FATIGUE_MIN_DURATION_TICKS + random.nextInt(FATIGUE_RANDOM_DURATION_TICKS + 1);
        fatigueSpeed = FATIGUE_MIN_SPEED + random.nextDouble(FATIGUE_MAX_SPEED - FATIGUE_MIN_SPEED);
        runningTicks = 0;
    }

    private int nextFatigueTriggerTicks() {
        return FATIGUE_MIN_RUNNING_TICKS + ThreadLocalRandom.current().nextInt(FATIGUE_RANDOM_RUNNING_TICKS + 1);
    }

    private void resetFatigueState() {
        runningTicks = 0;
        fatigueTriggerTicks = nextFatigueTriggerTicks();
        fatigueTicks = 0;
        fatigueCooldownTicks = 0;
        fatigueSpeed = WALK_MOVE_SPEED;
    }

    private boolean shouldRunToTarget(Vec3 targetPos) {
        if (currentPath == null) {
            return false;
        }
        if (currentPath.getRemainingLength() >= RUN_REMAINING_DISTANCE) {
            return true;
        }
        Vec3 finalTarget = Vec3.atCenterOf(currentPath.getEndPos());
        if (npc.position().distanceTo(finalTarget) >= RUN_TARGET_DISTANCE) {
            return true;
        }
        return targetPos != null && npc.position().distanceTo(targetPos) >= RUN_DIRECT_DISTANCE;
    }
    
    /**
     * 是否正在移动
     */
    public boolean isMoving() {
        return isMoving;
    }
    
    /**
     * 获取当前路径
     */
    public NPCPath getCurrentPath() {
        return currentPath;
    }

    public void setFenceGateTracker(Consumer<BlockPos> fenceGateTracker) {
        this.fenceGateTracker = fenceGateTracker;
    }

    public boolean shouldReplanForObstacle() {
        if (!isMoving || currentPath == null || currentPath.isCompleted()) {
            return false;
        }
        NPCPathNode currentNode = currentPath.getCurrentNode();
        if (currentNode == null) {
            return true;
        }
        Vec3 currentTarget = currentPath.getCurrentTarget();
        if (currentTarget == null) {
            return true;
        }
        if (currentObstacleType == ObstacleType.DOOR_CLOSED) {
            handleDoorInteraction();
            return false;
        }
        if (currentObstacleType == ObstacleType.NPC_BLOCKER) {
            return false;
        }
        if (currentObstacleType == ObstacleType.SOLID_BLOCK || currentObstacleType == ObstacleType.TIGHT_SPACE) {
            return true;
        }
        return !isCurrentNodeStillReachable(currentNode, currentTarget);
    }

    private boolean isCurrentNodeStillReachable(NPCPathNode currentNode, Vec3 currentTarget) {
        if (currentNode == null || currentTarget == null) {
            return false;
        }
        if (isMovementBlocked(currentTarget)) {
            return false;
        }
        BlockPos nextFootPos = BlockPos.containing(currentTarget.x, currentNode.standY, currentTarget.z);
        BlockPos nextHeadPos = nextFootPos.above();
        BlockState nextFootState = level.getBlockState(nextFootPos);
        BlockState nextHeadState = level.getBlockState(nextHeadPos);
        if (!isOpenSpacePassable(nextFootState, nextFootPos)
                && !isThinWalkableCover(nextFootState, nextFootPos)) {
            return false;
        }
        return isOpenSpacePassable(nextHeadState, nextHeadPos);
    }
    
    /**
     * 获取当前是否被障碍阻挡
     */
    public boolean isMovementBlockedState() {
        return movementBlocked;
    }

    public ObstacleType getCurrentObstacleType() {
        return currentObstacleType;
    }

    /**
     * 获取到目标的距离
     */
    public double getDistanceToTarget() {
        if (currentPath == null) return 0;
        return currentPath.getRemainingLength();
    }
    
    /**
     * menglannnn: 检测方块是否为危险方块
     */
    private boolean isDangerousBlock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.LAVA
            || block == Blocks.FIRE
            || block == Blocks.SOUL_FIRE
            || block == Blocks.CACTUS
            || block == Blocks.SWEET_BERRY_BUSH
            || block == Blocks.MAGMA_BLOCK
            || block == Blocks.POWDER_SNOW
            || block == Blocks.CAMPFIRE
            || block == Blocks.SOUL_CAMPFIRE
            || block == Blocks.LAVA_CAULDRON
            // 伤害性植物
            || block == Blocks.WITHER_ROSE
            || block == Blocks.DEAD_BUSH
            // 爆炸物
            || block == Blocks.TNT
            // 危险方块
            || block == Blocks.END_PORTAL
            || block == Blocks.END_GATEWAY
            || block == Blocks.NETHER_PORTAL
            || block == Blocks.VOID_AIR
            // 伤害性装置
            || block == Blocks.DISPENSER
            // 窒息方块
            || block == Blocks.SAND
            || block == Blocks.GRAVEL
            // 伤害性环境
            || block == Blocks.BUBBLE_COLUMN
            || block == Blocks.BIG_DRIPLEAF
            // 陷阱
            || block == Blocks.TRIPWIRE
            || block == Blocks.TRIPWIRE_HOOK;
    }
    
    /**
     * menglannnn: 检测位置是否为危险位置
     */
    private boolean isDangerousPosition(BlockPos pos) {
        return isDangerousBlock(level.getBlockState(pos)) 
            || isDangerousBlock(level.getBlockState(pos.above()))
            || isDangerousBlock(level.getBlockState(pos.below()));
    }
    
    /**
     * menglannnn: 运行时危险检测与处理
     * 如果NPC当前处于危险位置，返回true表示已处理
     */
    private boolean checkAndHandleDanger() {
        BlockPos footPos = npc.blockPosition();
        BlockPos headPos = footPos.above();
        
        // 检查脚部位置
        if (isDangerousBlock(level.getBlockState(footPos))) {
            Simukraft.LOGGER.warn("[NPCMoveController] NPC {} 脚部检测到危险方块 {}，执行紧急避让", 
                npc.getFullName(), level.getBlockState(footPos).getBlock());
            handleDangerEscape(footPos);
            return true;
        }
        
        // 检查头部位置
        if (isDangerousBlock(level.getBlockState(headPos))) {
            Simukraft.LOGGER.warn("[NPCMoveController] NPC {} 头部检测到危险方块 {}，执行紧急避让", 
                npc.getFullName(), level.getBlockState(headPos).getBlock());
            handleDangerEscape(headPos);
            return true;
        }
        
        // 检查下方（岩浆等）
        BlockPos belowPos = footPos.below();
        BlockState belowState = level.getBlockState(belowPos);
        if (belowState.getBlock() == Blocks.LAVA) {
            Simukraft.LOGGER.warn("[NPCMoveController] NPC {} 下方检测到岩浆，执行紧急避让", npc.getFullName());
            handleDangerEscape(belowPos);
            return true;
        }
        
        return false;
    }
    
    /**
     * menglannnn: 危险逃离处理
     */
    private void handleDangerEscape(BlockPos dangerPos) {
        // 停止当前移动
        stop();
        
        // 尝试向安全方向移动
        Vec3 escapeDir = findEscapeDirection(dangerPos);
        if (escapeDir != null) {
            Vec3 escapeTarget = npc.position().add(escapeDir.scale(2.0D));
            BlockPos escapePos = BlockPos.containing(escapeTarget);
            
            // 检查目标位置是否安全
            if (!isDangerousPosition(escapePos) && !isDangerousPosition(escapePos.above())) {
                npc.getMoveControl().setWantedPosition(escapeTarget.x, escapeTarget.y, escapeTarget.z, 1.0D);
                Simukraft.LOGGER.info("[NPCMoveController] NPC {} 向安全方向逃离: {}", 
                    npc.getFullName(), escapePos);
            }
        }
        
        // 如果是岩浆，尝试跳跃
        if (level.getBlockState(dangerPos).getBlock() == Blocks.LAVA ||
            level.getBlockState(dangerPos.below()).getBlock() == Blocks.LAVA) {
            if (npc.onGround()) {
                triggerGroundJump(0.42D);
            }
        }
    }
    
    /**
     * menglannnn: 寻找逃离危险的方向
     */
    private Vec3 findEscapeDirection(BlockPos dangerPos) {
        Vec3 npcPos = npc.position();
        Vec3 dangerVec = Vec3.atCenterOf(dangerPos);
        Vec3 awayDir = npcPos.subtract(dangerVec).normalize();
        
        // 如果方向为零向量，随机选择一个方向
        if (awayDir.lengthSqr() < 0.001D) {
            awayDir = new Vec3(1, 0, 0);
        }
        
        // 尝试找到最安全的方向
        Vec3 bestDir = null;
        double bestSafety = -1;
        
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            Vec3 testDir = new Vec3(
                awayDir.x * Math.cos(angle) - awayDir.z * Math.sin(angle),
                0,
                awayDir.x * Math.sin(angle) + awayDir.z * Math.cos(angle)
            );
            
            double safety = calculateSafetyScore(npcPos.add(testDir.scale(2.0D)));
            if (safety > bestSafety) {
                bestSafety = safety;
                bestDir = testDir;
            }
        }
        
        return bestDir;
    }
    
    /**
     * menglannnn: 计算位置的安全分数（越高越安全）
     */
    private double calculateSafetyScore(Vec3 pos) {
        BlockPos blockPos = BlockPos.containing(pos);
        double score = 100.0D;
        
        // 检查周围危险方块
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos checkPos = blockPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(checkPos);
                    if (isDangerousBlock(state)) {
                        double dist = pos.distanceTo(Vec3.atCenterOf(checkPos));
                        score -= 50.0D / (dist + 0.1D);
                    }
                }
            }
        }
        
        return score;
    }
}
