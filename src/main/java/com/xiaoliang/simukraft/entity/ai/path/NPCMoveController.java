package com.xiaoliang.simukraft.entity.ai.path;

import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * NPC移动控制器（menglannnn: 控制NPC沿着路径移动）
 */
@SuppressWarnings("null")
public class NPCMoveController {
    
    private final CustomEntity npc;
    private final ServerLevel level;
    
    // 移动参数
    private static final double ARRIVAL_DISTANCE = 0.5; // 到达节点的距离阈值
    private static final double WALK_MOVE_SPEED = 0.14D;
    private static final double RUN_MOVE_SPEED = 0.22D;
    private static final double RUN_REMAINING_DISTANCE = 8.0D;
    private static final double RUN_DIRECT_DISTANCE = 4.0D;
    private static final double TURN_SPEED = 0.15;
    
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
        
        // 停止NPC移动
        npc.setDeltaMovement(Vec3.ZERO);
    }
    
    public boolean isInStepUpTraversal() {
        if (currentPath == null) {
            return false;
        }
        NPCPathNode currentNode = currentPath.getCurrentNode();
        if (currentNode == null || currentNode.type != NPCPathNode.NodeType.STEP_UP) {
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

        tryHandleDoorOnPath(targetNode);
        
        // 检查是否到达当前节点
        Vec3 targetPos = currentPath.getCurrentTarget();
        double distanceToTarget = npc.position().distanceTo(targetPos);
        boolean requiresVerticalTraversal = targetNode.type == NPCPathNode.NodeType.STEP_UP || targetNode.type == NPCPathNode.NodeType.JUMP;
        double targetHeight = targetNode.y;
        boolean reachedTraversalHeight = npc.getY() >= targetHeight - 0.15D;
        
        if (distanceToTarget <= ARRIVAL_DISTANCE && (!requiresVerticalTraversal || reachedTraversalHeight)) {
            // 到达节点，前进到下一个
            handleNodeArrival(targetNode);
            return;
        }

        if (requiresVerticalTraversal && !npc.onGround()) {
            releaseAirTraversalControl();
            if (stepUpPhase != StepUpPhase.NONE) {
                stepUpPhase = StepUpPhase.LAND;
            }
            return;
        }
        
        // 检查是否卡住
        if (checkStuck()) {
            handleStuck();
            return;
        }

        if (stepUpPhase != StepUpPhase.NONE && continueStepUpPhase(targetNode, targetPos)) {
            movementBlocked = false;
            return;
        }
        resetStepUpState();

        if (tryJumpTowardHigherNode(targetNode, targetPos)) {
            movementBlocked = false;
            return;
        }

        if (tryStepTowardHigherNode(targetNode, targetPos)) {
            movementBlocked = false;
            return;
        }

        resetStepUpState();

        if (tryJumpOutOfPit(targetNode, targetPos)) {
            movementBlocked = false;
            currentObstacleType = ObstacleType.NONE;
            return;
        }
        
        // 执行移动前检查前方是否被阻挡
        if (isMovementBlocked(targetPos)) {
            movementBlocked = true;
            if (currentObstacleType == ObstacleType.DOOR_CLOSED) {
                handleDoorInteraction();
            }
            if (tryResolveCrowdedPath(targetPos)) {
                return;
            }
            if (tryStepOverObstacle(targetPos)) {
                return;
            }
            if (tryBypassObstacle(targetPos)) {
                return;
            }
            if (npc.onGround()) {
                npc.setDeltaMovement(Vec3.ZERO);
            }
            return;
        }
        movementBlocked = false;
        currentObstacleType = ObstacleType.NONE;
        resetCrowdState();

        // 执行移动
        currentSpeed = calculateSpeed(targetPos);
        moveTowards(targetPos);
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
        switch (node.type) {
            case DOOR:
                interactWithDoor(node.pos);
                break;
            case STEP_UP:
            case JUMP:
                resetStepUpState();
                releaseAirTraversalControl();
                break;
            case CLIMB:
                // 攀爬逻辑已在移动中处理
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
    
    private boolean shouldTriggerStepJump(double horizontalDist, double heightDelta) {
        return horizontalDist <= 0.24D && heightDelta >= 0.55D;
    }

    private boolean shouldTriggerHighJump(double horizontalDist, double heightDelta) {
        return horizontalDist <= 0.22D && heightDelta >= 1.0D;
    }

    private boolean tryJumpTowardHigherNode(NPCPathNode targetNode, Vec3 targetPos) {
        if (!npc.onGround()) {
            return false;
        }
        if (targetNode == null || targetNode.type != NPCPathNode.NodeType.JUMP) {
            return false;
        }

        double currentY = npc.blockPosition().getY();
        double targetY = targetNode.y;
        double heightDelta = targetY - currentY;
        if (heightDelta <= 1.0D || heightDelta > 1.25D) {
            return false;
        }

        Vec3 direction = targetPos.subtract(npc.position());
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.05D) {
            return false;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        BlockPos landingPos = targetNode.pos;
        BlockPos landingHeadPos = landingPos.above();

        if (!level.getBlockState(landingPos).getCollisionShape(level, landingPos).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(landingHeadPos).getCollisionShape(level, landingHeadPos).isEmpty()) {
            return false;
        }

        faceMovementDirection(dx, dz, 0.85F);
        if (shouldTriggerHighJump(horizontalDist, heightDelta)) {
            npc.doJump();
            stepUpPhase = StepUpPhase.LAND;
            stepUpAirTicks = 0;
            return true;
        }
        moveTowards(targetPos);
        return true;
    }

    private boolean tryStepTowardHigherNode(NPCPathNode targetNode, Vec3 targetPos) {
        boolean continuingStepUp = targetNode != null && targetNode.type == NPCPathNode.NodeType.STEP_UP && (stepUpLockTicks > 0 || stepUpAirTicks > 0 || stepUpPhase != StepUpPhase.NONE);
        if (!npc.onGround() && !continuingStepUp) {
            return false;
        }
        if (targetNode == null || (targetNode.type != NPCPathNode.NodeType.STEP_UP && targetNode.type != NPCPathNode.NodeType.JUMP)) {
            return false;
        }

        double currentY = npc.getY();
        double targetY = targetNode.y;
        double heightDelta = targetY - currentY;
        if (heightDelta < 0.45D || heightDelta > 1.25D) {
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
        BlockPos frontFootPos = BlockPos.containing(currentPos.x + dx * 0.6D, currentPos.y, currentPos.z + dz * 0.6D);
        BlockPos frontHeadPos = frontFootPos.above();
        BlockPos upperFrontPos = frontHeadPos.above();
        BlockPos landingPos = targetNode.pos;
        BlockPos landingHeadPos = landingPos.above();

        boolean clearForward = level.getBlockState(frontHeadPos).getCollisionShape(level, frontHeadPos).isEmpty()
                && level.getBlockState(upperFrontPos).getCollisionShape(level, upperFrontPos).isEmpty();
        boolean clearLanding = level.getBlockState(landingPos).getCollisionShape(level, landingPos).isEmpty()
                && level.getBlockState(landingHeadPos).getCollisionShape(level, landingHeadPos).isEmpty();
        if (!clearForward || !clearLanding) {
            return false;
        }

        if (targetNode.type == NPCPathNode.NodeType.STEP_UP) {
            if (stepUpPhase == StepUpPhase.NONE) {
                stepUpPhase = StepUpPhase.APPROACH;
            }
        } else {
            stepUpPhase = StepUpPhase.NONE;
        }

        faceMovementDirection(dx, dz, 0.65F);

        boolean shouldJump = npc.onGround() && (targetNode.type == NPCPathNode.NodeType.JUMP
                ? shouldTriggerHighJump(horizontalDist, heightDelta)
                : shouldTriggerStepJump(horizontalDist, heightDelta));

        if (shouldJump) {
            npc.doJump();
            if (targetNode.type == NPCPathNode.NodeType.STEP_UP) {
                stepUpLockTicks = 0;
                stepUpAirTicks = 0;
                stepUpPhase = StepUpPhase.LAND;
            }
            return true;
        }

        moveTowards(targetPos);

        if (targetNode.type == NPCPathNode.NodeType.STEP_UP) {
            stepUpLockTicks = 7;
            stepUpAirTicks = 0;
            stepUpPhase = StepUpPhase.APPROACH;
        }
        return true;
    }

    private boolean continueStepUpPhase(NPCPathNode targetNode, Vec3 targetPos) {
        if (targetNode == null || targetNode.type != NPCPathNode.NodeType.STEP_UP) {
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
                if (!tryStepTowardHigherNode(targetNode, targetPos)) {
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
                double remainingHeight = targetNode.y - npc.getY();
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

    private void resetStepUpState() {
        stepUpPhase = StepUpPhase.NONE;
        stepUpLockTicks = 0;
        stepUpAirTicks = 0;
    }

    private void releaseAirTraversalControl() {
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0D);
        npc.setSpeed(0.0F);
        npc.setZza(0.0F);
        npc.setXxa(0.0F);
    }

    private boolean tryJumpOutOfPit(NPCPathNode targetNode, Vec3 targetPos) {
        if (!npc.onGround()) {
            return false;
        }
        if (targetNode == null) {
            return false;
        }

        double currentY = npc.blockPosition().getY();
        double targetY = targetNode.y;
        double heightDelta = targetY - currentY;
        if (heightDelta < 1.0D || heightDelta > 1.25D) {
            return false;
        }

        Vec3 direction = targetPos.subtract(npc.position());
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.1D) {
            return false;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;

        BlockPos frontFootPos = BlockPos.containing(npc.getX() + dx * 0.6D, npc.getY(), npc.getZ() + dz * 0.6D);
        BlockPos frontHeadPos = frontFootPos.above();
        BlockPos upperFrontPos = frontHeadPos.above();
        BlockPos landingPos = targetNode.pos;
        BlockPos landingHeadPos = landingPos.above();

        boolean blockedAhead = !level.getBlockState(frontHeadPos).getCollisionShape(level, frontHeadPos).isEmpty()
                || !level.getBlockState(upperFrontPos).getCollisionShape(level, upperFrontPos).isEmpty();
        if (blockedAhead) {
            return false;
        }

        if (!level.getBlockState(landingPos).getCollisionShape(level, landingPos).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(landingHeadPos).getCollisionShape(level, landingHeadPos).isEmpty()) {
            return false;
        }

        faceMovementDirection(dx, dz, 0.85F);
        moveTowards(targetPos);
        npc.doJump();
        return true;
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

        if (isClosedDoorBlock(footState) || isClosedDoorBlock(headState)) {
            currentObstacleType = ObstacleType.DOOR_CLOSED;
            return true;
        }

        if (hasNpcBlocker(footPos, headPos)) {
            currentObstacleType = ObstacleType.NPC_BLOCKER;
            return true;
        }

        if (isLowStepCollision(footState, footPos) && headState.getCollisionShape(level, headPos).isEmpty()) {
            currentObstacleType = ObstacleType.NONE;
            if (tryLowStepGlide(direction, currentPos, footPos, getCollisionHeight(footState, footPos))) {
                return true;
            }
            return false;
        }

        boolean blocked = !footState.getCollisionShape(level, footPos).isEmpty()
                || !headState.getCollisionShape(level, headPos).isEmpty();
        if (!blocked) {
            currentObstacleType = ObstacleType.NONE;
            return false;
        }

        double collisionHeight = getCollisionHeight(footState, footPos);
        if (collisionHeight > 0.0D && collisionHeight <= 1.0D) {
            currentObstacleType = ObstacleType.STEP_UP;
        } else if (!headState.getCollisionShape(level, headPos).isEmpty()) {
            currentObstacleType = ObstacleType.TIGHT_SPACE;
        } else {
            currentObstacleType = ObstacleType.SOLID_BLOCK;
        }
        return true;
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
                npc.setDeltaMovement(Vec3.ZERO);
                return true;
            }
        } else {
            narrowPassWaitTicks = 0;
        }

        if (crowdWaitTicks <= 8) {
            npc.setDeltaMovement(Vec3.ZERO);
            return true;
        }

        crowdBypassTicks++;
        if (tryBypassCrowd(targetPos, nearestBlocker, crowdBypassTicks)) {
            return true;
        }

        if (crowdWaitTicks <= 16) {
            npc.setDeltaMovement(Vec3.ZERO);
            return true;
        }

        return false;
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

    private boolean tryStepOverObstacle(Vec3 target) {
        if (!npc.onGround()) {
            return false;
        }

        Vec3 currentPos = npc.position();
        Vec3 direction = target.subtract(currentPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.01) {
            return false;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        BlockPos frontFootPos = BlockPos.containing(currentPos.x + dx * 0.75D, currentPos.y, currentPos.z + dz * 0.75D);
        BlockPos frontHeadPos = frontFootPos.above();
        BlockPos topPos = frontHeadPos.above();

        BlockState frontFootState = level.getBlockState(frontFootPos);
        BlockState frontHeadState = level.getBlockState(frontHeadPos);
        BlockState topState = level.getBlockState(topPos);
        double obstacleHeight = getCollisionHeight(frontFootState, frontFootPos);

        boolean clearAbove = frontHeadState.getCollisionShape(level, frontHeadPos).isEmpty()
                && topState.getCollisionShape(level, topPos).isEmpty();
        if (!clearAbove) {
            return false;
        }

        if (obstacleHeight > 0.0D && obstacleHeight < 0.9D) {
            moveTowards(target);
            return true;
        }

        if (obstacleHeight >= 0.9D && obstacleHeight <= 1.0D) {
            moveTowards(target);
            return true;
        }

        return false;
    }

    private boolean tryLowStepGlide(Vec3 direction, Vec3 currentPos, BlockPos lowStepPos, double obstacleHeight) {
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalDist < 0.01D) {
            return false;
        }
        if (obstacleHeight <= 0.0D || obstacleHeight > 0.75D) {
            return false;
        }

        double dx = direction.x / horizontalDist;
        double dz = direction.z / horizontalDist;
        BlockPos headPos = lowStepPos.above();
        BlockPos upperHeadPos = headPos.above();
        if (!level.getBlockState(headPos).getCollisionShape(level, headPos).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(upperHeadPos).getCollisionShape(level, upperHeadPos).isEmpty()) {
            return false;
        }

        faceMovementDirection(dx, dz, 0.5F);
        moveTowards(Vec3.atCenterOf(lowStepPos));
        return true;
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

        return (footState.getCollisionShape(level, footPos).isEmpty() || isLowStepCollision(footState, footPos))
                && headState.getCollisionShape(level, headPos).isEmpty()
                && !groundState.getCollisionShape(level, groundPos).isEmpty();
    }

    private boolean isLowStepCollision(BlockState state, BlockPos pos) {
        double collisionHeight = getCollisionHeight(state, pos);
        return collisionHeight > 0.0D && collisionHeight < 1.0D;
    }

    private boolean isClosedDoorBlock(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock) {
            return !state.getValue(DoorBlock.OPEN);
        }
        if (block instanceof FenceGateBlock) {
            return !state.getValue(FenceGateBlock.OPEN);
        }
        if (block instanceof TrapDoorBlock) {
            return !state.getValue(TrapDoorBlock.OPEN);
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
        if (block instanceof TrapDoorBlock) {
            return state.getValue(TrapDoorBlock.OPEN);
        }
        return false;
    }

    private double getCollisionHeight(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return 0.0D;
        }
        return shape.max(net.minecraft.core.Direction.Axis.Y);
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
        smoothTurn(targetYaw, turnMultiplier);
    }

    /**
     * 平滑转向
     */
    private void smoothTurn(float targetYaw, float turnMultiplier) {
        float currentYaw = npc.getYRot();
        float deltaYaw = targetYaw - currentYaw;
        
        while (deltaYaw > 180) deltaYaw -= 360;
        while (deltaYaw < -180) deltaYaw += 360;
        
        float appliedTurnSpeed = Math.max(0.0F, Math.min(1.0F, (float) TURN_SPEED * turnMultiplier));
        float newYaw = currentYaw + deltaYaw * appliedTurnSpeed;
        npc.setYRot(newYaw);
        npc.setYHeadRot(newYaw);
        npc.yBodyRot = newYaw;
        npc.yHeadRotO = newYaw;
        npc.yBodyRotO = newYaw;
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
        if (currentNode != null && (currentNode.type == NPCPathNode.NodeType.STEP_UP || currentNode.type == NPCPathNode.NodeType.JUMP)) {
            return;
        }

        if (npc.onGround()) {
            npc.doJump();
        }
        
        // 如果卡住太久，放弃当前路径
        if (stuckTicks >= STUCK_THRESHOLD * 3) {
            stop();
        }
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

        if (block instanceof DoorBlock) {
            if (!state.getValue(DoorBlock.OPEN)) {
                openDoor(doorPos, state);
            }
            return true;
        }

        if (block instanceof FenceGateBlock) {
            if (!state.getValue(FenceGateBlock.OPEN)) {
                level.setBlock(doorPos, state.setValue(FenceGateBlock.OPEN, true), 10);
                doorInteractCooldown = 20;
            }
            return true;
        }

        if (block instanceof TrapDoorBlock) {
            if (!state.getValue(TrapDoorBlock.OPEN)) {
                level.setBlock(doorPos, state.setValue(TrapDoorBlock.OPEN, true), 10);
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
        double baseSpeed = npc.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (npc.isSleeping()) {
            return 0;
        }
        double targetSpeed = shouldRunToTarget(targetPos) ? RUN_MOVE_SPEED : WALK_MOVE_SPEED;
        return Math.min(baseSpeed, targetSpeed);
    }

    private boolean shouldRunToTarget(Vec3 targetPos) {
        if (currentPath == null) {
            return false;
        }
        if (targetPos != null && npc.position().distanceTo(targetPos) >= RUN_DIRECT_DISTANCE) {
            return true;
        }
        return currentPath.getRemainingLength() >= RUN_REMAINING_DISTANCE;
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
}
