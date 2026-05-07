package com.xiaoliang.simukraft.entity.ai.path;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

@SuppressWarnings("null")
public class NPCPathFinder {
    private static final int MAX_ITERATIONS = 5000;
    private static final int MAX_FALL_SCAN = 6;
    private static final double COST_DANGER = 100.0D;
    private static final double COST_DANGER_NEARBY = 30.0D;
    private static final double COST_DANGER_ADJACENT = 15.0D;
    private static final double COST_BARRIER_NEARBY = 10.0D;
    private static final double COST_BARRIER_ADJACENT = 24.0D;
    private static final double COST_BARRIER_ON_NODE = 36.0D;
    private static final double COST_BARRIER_COVER_DISCOUNT = 0.35D;
    private static final int DANGER_CHECK_RADIUS = 2;
    private static final double WALK_STEP_HEIGHT = 12.0D / 16.0D;
    private static final double MAX_ASCEND_HEIGHT = 19.0D / 16.0D;
    private static final double MAX_JUMP_OVER_HEIGHT = 1.625D;
    private static final double MAX_JUMP_OVER_LANDING_DELTA = 1.0D;
    private static final double COLLISION_EPSILON = 1.0E-5D;
    private static final double DEFAULT_BODY_RADIUS = 0.3D;
    private static final double OPEN_TRAPDOOR_BODY_RADIUS = 0.24D;
    private static final double OPEN_TRAPDOOR_PANEL_SIDE_RADIUS = 0.14D;
    private static final double OPEN_TRAPDOOR_STAND_BIAS = 0.18D;
    private static final double STAND_POSITION_MIN_MARGIN = 0.18D;
    private static final double STAND_POSITION_MAX_MARGIN = 0.82D;

    private final ServerLevel level;
    private final TerrainCostClassifier terrainCostClassifier;

    public NPCPathFinder(ServerLevel level) {
        this.level = level;
        this.terrainCostClassifier = new TerrainCostClassifier(level);
    }

    public BlockPos normalizeStartPosition(BlockPos pos) {
        return prepareStandPosition(pos);
    }

    public BlockPos normalizeTargetPosition(BlockPos pos) {
        BlockPos normalized = prepareStandPosition(pos);
        return normalized != null ? normalized : findAlternativeEndPoint(pos);
    }

    public NPCPath findPath(BlockPos start, BlockPos end) {
        PathCostRules costRules = PathCostConfigLoader.getRules();
        BlockPos normalizedStart = normalizeStartPosition(start);
        BlockPos normalizedEnd = normalizeTargetPosition(end);
        if (normalizedStart == null || normalizedEnd == null) {
            Simukraft.LOGGER.warn("[NPCPathFinder] 寻路归一化失败: start={} -> {}, end={} -> {}", start, normalizedStart, end, normalizedEnd);
            return createFailedPath(start, end);
        }
        if (normalizedStart.equals(normalizedEnd)) {
            NPCPath path = new NPCPath(level, normalizedStart, normalizedEnd);
            path.addNode(createNode(normalizedStart, NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE));
            path.markCompleted();
            return path;
        }

        PriorityQueue<NPCPathNode> openSet = new PriorityQueue<>();
        Set<String> closedSet = new HashSet<>();
        Map<String, NPCPathNode> nodeMap = new HashMap<>();
        NPCPathNode targetNode = createNode(normalizedEnd, NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE);
        NPCPathNode startNode = createNode(normalizedStart, NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE);
        startNode.gCost = 0.0D;
        startNode.hCost = calculateHeuristic(startNode, targetNode);
        startNode.fCost = startNode.hCost;
        openSet.add(startNode);
        nodeMap.put(startNode.key, startNode);

        int iterations = 0;
        while (!openSet.isEmpty() && iterations++ < MAX_ITERATIONS) {
            NPCPathNode current = openSet.poll();
            if (closedSet.contains(current.key)) {
                continue;
            }
            if (isAtTarget(current, targetNode)) {
                NPCPath path = reconstructPath(current, normalizedStart, normalizedEnd, costRules);
                //logPathComposition(normalizedStart, normalizedEnd, path);
                return path;
            }

            closedSet.add(current.key);
            for (NPCPathNode neighbor : getNeighbors(current)) {
                if (closedSet.contains(neighbor.key)) {
                    continue;
                }

                PathCostBreakdown moveCostBreakdown = calculateMoveCostBreakdown(current, neighbor, costRules);
                applyCostBreakdown(neighbor, moveCostBreakdown);
                double tentativeGCost = current.gCost + moveCostBreakdown.totalCost();
                NPCPathNode existingNode = nodeMap.get(neighbor.key);
                if (existingNode == null) {
                    neighbor.gCost = tentativeGCost;
                    neighbor.hCost = calculateHeuristic(neighbor, targetNode);
                    neighbor.fCost = neighbor.gCost + neighbor.hCost;
                    neighbor.parent = current;
                    nodeMap.put(neighbor.key, neighbor);
                    openSet.add(neighbor);
                } else if (tentativeGCost < existingNode.gCost) {
                    openSet.remove(existingNode);
                    existingNode.gCost = tentativeGCost;
                    existingNode.fCost = existingNode.gCost + existingNode.hCost;
                    existingNode.parent = current;
                    existingNode.type = neighbor.type;
                    existingNode.action = neighbor.action;
                    existingNode.setStandPosition(neighbor.standX, neighbor.standY, neighbor.standZ);
                    existingNode.stepCost = neighbor.stepCost;
                    existingNode.terrainCost = neighbor.terrainCost;
                    existingNode.costReason = neighbor.costReason;
                    openSet.add(existingNode);
                }
            }
        }
        Simukraft.LOGGER.warn("[NPCPathFinder] 寻路失败: start={} normalizedStart={}, end={} normalizedEnd={}, iterations={}, closed={}, open={}", start, normalizedStart, end, normalizedEnd, iterations, closedSet.size(), openSet.size());
        return createFailedPath(normalizedStart, normalizedEnd);
    }

    private List<NPCPathNode> getNeighbors(NPCPathNode node) {
        List<NPCPathNode> neighbors = new ArrayList<>();
        addInternalSurfaceNeighbors(neighbors, node);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx != 0 && dz != 0 && !canMoveDiagonally(node, dx, dz)) {
                    continue;
                }
                NPCPathNode next = resolveHorizontalNeighbor(node, dx, dz);
                if (next != null) {
                    neighbors.add(next);
                }
                if ((dx == 0) != (dz == 0)) {
                    NPCPathNode jumpOver = resolveJumpOverNeighbor(node, dx, dz);
                    if (jumpOver != null) {
                        neighbors.add(jumpOver);
                    }
                }
            }
        }
        return neighbors;
    }

    private void addInternalSurfaceNeighbors(List<NPCPathNode> neighbors, NPCPathNode from) {
        BlockPos supportPos = getSupportBlockPos(from);
        if (!isVanillaAutoStepBlock(level.getBlockState(supportPos))) {
            return;
        }
        for (NPCPathNode candidate : createSurfaceNodesForSupportBlock(supportPos, NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE)) {
            if (candidate.key.equals(from.key) || !isSurfaceNodeStandable(candidate)) {
                continue;
            }
            NPCPathNode node = createNeighbor(from, candidate, NPCPathNode.NodeType.WALKABLE);
            if (node != null && isReachableAdjacentHeight(from, node)) {
                neighbors.add(node);
            }
        }
    }

    private NPCPathNode resolveHorizontalNeighbor(NPCPathNode from, int dx, int dz) {
        BlockPos column = from.pos.offset(dx, 0, dz);
        NPCPathNode bestNode = findBestColumnNeighbor(from, column, true);
        if (bestNode != null) {
            return bestNode;
        }

        BlockPos fall = findFallPosition(column);
        if (fall != null && !fall.equals(from.pos)) {
            return createNeighbor(from, fall, NPCPathNode.NodeType.FALL);
        }
        return null;
    }

    private NPCPathNode resolveJumpOverNeighbor(NPCPathNode from, int dx, int dz) {
        BlockPos obstaclePos = from.pos.offset(dx, 0, dz);
        if (!isJumpOverObstacle(obstaclePos)) {
            return null;
        }

        BlockPos landingColumn = obstaclePos.offset(dx, 0, dz);
        NPCPathNode landingNode = findBestColumnNeighbor(from, landingColumn, false);
        if (landingNode == null) {
            return null;
        }

        double heightDelta = calculateSignedVerticalDistance(from, landingNode);
        if (heightDelta > MAX_JUMP_OVER_LANDING_DELTA || heightDelta < -1.25D) {
            return null;
        }
        if (!isJumpOverArcClear(obstaclePos, landingNode)) {
            return null;
        }
        return copyNode(landingNode, NPCPathNode.NodeType.JUMP, NPCPathNode.MovementAction.JUMP_OVER);
    }

    private NPCPathNode findBestColumnNeighbor(NPCPathNode from, BlockPos column, boolean allowFall) {
        NPCPathNode bestNode = null;
        double bestScore = Double.MAX_VALUE;
        Set<String> visited = new HashSet<>();
        for (int dy = -2; dy <= 2; dy++) {
            for (NPCPathNode candidateNode : createSurfaceNodes(column.offset(0, dy, 0), NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE)) {
                if (candidateNode.key.equals(from.key) || !visited.add(candidateNode.key)) {
                    continue;
                }
                if (!isSurfaceNodeStandable(candidateNode)) {
                    continue;
                }
                StandResult result = evaluateStandPosition(candidateNode.pos);
                NPCPathNode.NodeType nodeType = result.walkable ? result.nodeType : NPCPathNode.NodeType.WALKABLE;
                NPCPathNode adjustedCandidate = adjustStairCandidateForApproach(from, candidateNode);
                NPCPathNode node = createNeighbor(from, adjustedCandidate, nodeType);
                if (node == null || !isReachableAdjacentHeight(from, node) || (!allowFall && node.action == NPCPathNode.MovementAction.FALL)) {
                    continue;
                }
                double score = scoreColumnNeighbor(from, node);
                if (score < bestScore) {
                    bestScore = score;
                    bestNode = node;
                }
            }
        }
        return bestNode;
    }

    private NPCPathNode adjustStairCandidateForApproach(NPCPathNode from, NPCPathNode candidate) {
        BlockPos supportPos = getSupportBlockPos(candidate);
        BlockState supportState = level.getBlockState(supportPos);
        if (!isVanillaAutoStepBlock(supportState)) {
            return candidate;
        }

        StairTransitionType transitionType = classifyStairTransition(from, candidate);
        if (transitionType != StairTransitionType.ENTER_ASCEND && transitionType != StairTransitionType.SAME_BLOCK_ASCEND) {
            return candidate;
        }

        double dx = supportPos.getX() + 0.5D - from.standX;
        double dz = supportPos.getZ() + 0.5D - from.standZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= COLLISION_EPSILON) {
            return candidate;
        }

        double dirX = dx / length;
        double dirZ = dz / length;
        double standX = supportPos.getX() + 0.5D + dirX * 0.18D;
        double standZ = supportPos.getZ() + 0.5D + dirZ * 0.18D;
        TrapdoorStandAdjustment trapdoorAdjusted = adjustStandPositionForOpenTrapdoor(supportPos, standX, standZ);
        standX = trapdoorAdjusted.standX;
        standZ = trapdoorAdjusted.standZ;
        double standY = getHighestCollisionTopAt(supportPos, standX, standZ);
        if (standY == Double.NEGATIVE_INFINITY) {
            return candidate;
        }

        BlockPos footNodePos = BlockPos.containing(standX, standY, standZ);
        NPCPathNode adjusted = createSurfaceNode(footNodePos, standX, standY, standZ, candidate.type, candidate.action);
        if (!isSurfaceNodeStandable(adjusted)) {
            return candidate;
        }
        return adjusted;
    }

    private boolean isReachableAdjacentHeight(NPCPathNode from, NPCPathNode to) {
        double heightDelta = calculateSignedVerticalDistance(from, to);
        if (heightDelta > 0.5D + COLLISION_EPSILON && isSameVanillaAutoStepBlock(from, to)) {
            return false;
        }
        if (heightDelta >= MAX_ASCEND_HEIGHT) {
            return false;
        }
        if (heightDelta > WALK_STEP_HEIGHT + COLLISION_EPSILON && to.action != NPCPathNode.MovementAction.ASCEND) {
            return false;
        }
        return true;
    }

    private double scoreColumnNeighbor(NPCPathNode from, NPCPathNode node) {
        double heightDelta = Math.abs(calculateSignedVerticalDistance(from, node));
        if (isVanillaAutoStepNode(node.pos)) {
            return heightDelta;
        }
        switch (node.action) {
            case TRAVERSE:
                return 1.0D + heightDelta;
            case DESCEND:
                return 2.0D + heightDelta;
            case ASCEND:
                return 3.0D + heightDelta;
            case JUMP_OVER:
                return 3.4D + heightDelta;
            case FALL:
                return 4.0D + heightDelta;
            default:
                return 5.0D + heightDelta;
        }
    }

    private NPCPathNode createNeighbor(NPCPathNode from, NPCPathNode target, NPCPathNode.NodeType preferredType) {
        double heightDelta = calculateSignedVerticalDistance(from, target);
        if (preferredType == NPCPathNode.NodeType.DOOR) {
            return copyNode(target, NPCPathNode.NodeType.DOOR, NPCPathNode.MovementAction.DOOR);
        }

        StairTransitionType stairTransitionType = classifyStairTransition(from, target);
        if (stairTransitionType == StairTransitionType.SAME_BLOCK_TRAVERSE
                && heightDelta <= WALK_STEP_HEIGHT + COLLISION_EPSILON
                && heightDelta >= -WALK_STEP_HEIGHT - COLLISION_EPSILON) {
            logStepDecision("SAME_AUTO_STEP_TRAVERSE", from, target, heightDelta, preferredType);
            return copyNode(target, NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE);
        }
        if (stairTransitionType == StairTransitionType.ENTER_RAMP_TRAVERSE
                && heightDelta <= WALK_STEP_HEIGHT + COLLISION_EPSILON
                && heightDelta >= -WALK_STEP_HEIGHT - COLLISION_EPSILON) {
            logStepDecision("AUTO_STEP_TRANSITION_TRAVERSE", from, target, heightDelta, preferredType);
            return copyNode(target, NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE);
        }
        if ((stairTransitionType == StairTransitionType.ENTER_ASCEND || stairTransitionType == StairTransitionType.SAME_BLOCK_ASCEND)
                && heightDelta < MAX_ASCEND_HEIGHT) {
            logStepDecision("AUTO_STEP_TRANSITION_ASCEND", from, target, heightDelta, preferredType);
            return copyNode(target, NPCPathNode.NodeType.JUMP, NPCPathNode.MovementAction.ASCEND);
        }
        if (heightDelta > WALK_STEP_HEIGHT && heightDelta < MAX_ASCEND_HEIGHT) {
            logStepDecision("ASCEND_CLASSIFIED", from, target, heightDelta, preferredType);
            return copyNode(target, NPCPathNode.NodeType.JUMP, NPCPathNode.MovementAction.ASCEND);
        }
        if (heightDelta >= MAX_ASCEND_HEIGHT) {
            return null;
        }
        if (heightDelta < -1.0D) {
            return copyNode(target, NPCPathNode.NodeType.FALL, NPCPathNode.MovementAction.FALL);
        }
        if (heightDelta < -WALK_STEP_HEIGHT) {
            return copyNode(target, NPCPathNode.NodeType.FALL, NPCPathNode.MovementAction.DESCEND);
        }
        return copyNode(target, preferredType, NPCPathNode.MovementAction.TRAVERSE);
    }

    private NPCPathNode createNeighbor(NPCPathNode from, BlockPos pos, NPCPathNode.NodeType preferredType) {
        NPCPathNode target = createNode(pos, preferredType, NPCPathNode.MovementAction.TRAVERSE);
        return createNeighbor(from, target, preferredType);
    }

    private double calculateSignedVerticalDistance(NPCPathNode from, NPCPathNode to) {
        double hypotenuse = from.distanceTo(to);
        double dx = to.standX - from.standX;
        double dz = to.standZ - from.standZ;
        double horizontalSquared = dx * dx + dz * dz;
        double verticalSquared = Math.max(0.0D, hypotenuse * hypotenuse - horizontalSquared);
        double verticalDistance = Math.sqrt(verticalSquared);
        return to.standY >= from.standY ? verticalDistance : -verticalDistance;
    }

    private StairTransitionType classifyStairTransition(NPCPathNode from, NPCPathNode target) {
        BlockPos targetSupport = getSupportBlockPos(target);
        BlockState targetSupportState = level.getBlockState(targetSupport);
        if (!isVanillaAutoStepBlock(targetSupportState)) {
            if (isSameVanillaAutoStepBlock(from, target)) {
                return StairTransitionType.SAME_BLOCK_TRAVERSE;
            }
            return StairTransitionType.NONE;
        }

        if (isSameVanillaAutoStepBlock(from, target)) {
            return analyzeStairTransition(from, targetSupport, true);
        }
        if (isVanillaAutoStepTransition(from.pos, target.pos)) {
            return analyzeStairTransition(from, targetSupport, false);
        }
        return StairTransitionType.NONE;
    }

    private StairTransitionType analyzeStairTransition(NPCPathNode from, BlockPos targetSupport, boolean sameSupportBlock) {
        double dx = targetSupport.getX() + 0.5D - from.standX;
        double dz = targetSupport.getZ() + 0.5D - from.standZ;
        if (Math.abs(dx) <= COLLISION_EPSILON && Math.abs(dz) <= COLLISION_EPSILON) {
            return sameSupportBlock ? StairTransitionType.SAME_BLOCK_TRAVERSE : StairTransitionType.NONE;
        }

        double length = Math.sqrt(dx * dx + dz * dz);
        double dirX = dx / length;
        double dirZ = dz / length;
        double enterSampleX = targetSupport.getX() + 0.5D - dirX * 0.31D;
        double enterSampleZ = targetSupport.getZ() + 0.5D - dirZ * 0.31D;
        double innerSampleX = targetSupport.getX() + 0.5D + dirX * 0.31D;
        double innerSampleZ = targetSupport.getZ() + 0.5D + dirZ * 0.31D;

        double enterTop = getHighestCollisionTopAt(targetSupport, enterSampleX, enterSampleZ);
        double innerTop = getHighestCollisionTopAt(targetSupport, innerSampleX, innerSampleZ);
        if (enterTop == Double.NEGATIVE_INFINITY || innerTop == Double.NEGATIVE_INFINITY) {
            return StairTransitionType.NONE;
        }

        double rise = innerTop - enterTop;
        if (rise > 0.12D) {
            return StairTransitionType.ENTER_RAMP_TRAVERSE;
        }
        if (sameSupportBlock && Math.abs(rise) <= 0.12D) {
            return StairTransitionType.SAME_BLOCK_ASCEND;
        }
        if (!sameSupportBlock && Math.abs(rise) <= 0.12D) {
            return StairTransitionType.ENTER_ASCEND;
        }
        return StairTransitionType.NONE;
    }

    private void logStepDecision(String reason, NPCPathNode from, NPCPathNode target, double heightDelta, NPCPathNode.NodeType preferredType) {
        if (!ServerConfig.isDebugLogEnabled()) {
            return;
        }
        if (!isVanillaAutoStepTransition(from.pos, target.pos) && heightDelta <= WALK_STEP_HEIGHT + COLLISION_EPSILON) {
            return;
        }
        //Simukraft.LOGGER.info("[NPCPathFinder][StepTrace] reason={} from={} fromStand=({},{},{}) to={} toStand=({},{},{}) heightDelta={} hypotenuse={} preferredType={} sameAutoStep={} autoStepTransition={}",
        //        reason,
        //        from.pos, from.standX, from.standY, from.standZ,
        //        target.pos, target.standX, target.standY, target.standZ,
        //        heightDelta, from.distanceTo(target), preferredType,
        //        isSameVanillaAutoStepBlock(from, target), isVanillaAutoStepTransition(from.pos, target.pos));
    }

    private NPCPathNode createNode(BlockPos pos, NPCPathNode.NodeType type, NPCPathNode.MovementAction action) {
        NPCPathNode node = new NPCPathNode(pos);
        node.type = type;
        node.action = action;
        double standY = getStandY(pos);
        if (Double.isNaN(standY)) {
            standY = pos.getY();
        }
        node.setStandPosition(pos.getX() + 0.5D, standY, pos.getZ() + 0.5D);
        return node;
    }

    private NPCPathNode createSurfaceNode(BlockPos pos, double standX, double standY, double standZ, NPCPathNode.NodeType type, NPCPathNode.MovementAction action) {
        NPCPathNode node = new NPCPathNode(pos);
        node.type = type;
        node.action = action;
        node.setStandPosition(standX, standY, standZ);
        return node;
    }

    private NPCPathNode copyNode(NPCPathNode source, NPCPathNode.NodeType type, NPCPathNode.MovementAction action) {
        NPCPathNode node = new NPCPathNode(source.pos);
        node.type = type;
        node.action = action;
        node.setStandPosition(source.standX, source.standY, source.standZ);
        return node;
    }

    private List<NPCPathNode> createSurfaceNodes(BlockPos pos, NPCPathNode.NodeType type, NPCPathNode.MovementAction action) {
        List<NPCPathNode> nodes = new ArrayList<>();
        addSurfaceNodesForBlock(nodes, pos, pos, type, action);
        addSurfaceNodesForBlock(nodes, pos.below(), pos, type, action);
        if (nodes.isEmpty() && evaluateStandPosition(pos).walkable) {
            double standY = getStandY(pos);
            if (!Double.isNaN(standY)) {
                nodes.add(createSurfaceNode(pos, pos.getX() + 0.5D, standY, pos.getZ() + 0.5D, type, action));
            }
        }
        return nodes;
    }

    private List<NPCPathNode> createSurfaceNodesForSupportBlock(BlockPos supportPos, NPCPathNode.NodeType type, NPCPathNode.MovementAction action) {
        List<NPCPathNode> nodes = new ArrayList<>();
        addSurfaceNodesForBlock(nodes, supportPos, null, type, action);
        return nodes;
    }

    private void addSurfaceNodesForBlock(List<NPCPathNode> nodes, BlockPos blockPos, BlockPos nodePos, NPCPathNode.NodeType type, NPCPathNode.MovementAction action) {
        BlockState state = level.getBlockState(blockPos);
        // 墙、栅栏、铁栏杆等不完整方块不能作为站立表面
        if (isNonSolidBlock(state)) {
            return;
        }
        VoxelShape shape = state.getCollisionShape(level, blockPos);
        if (shape.isEmpty()) {
            return;
        }
        Set<String> added = new HashSet<>();
        List<AABB> boxes = shape.toAabbs();

        // menglannnn: 对于楼梯/台阶，基于碰撞箱生成所有可能的站立点，不依赖朝向
        if (isVanillaAutoStepBlock(state)) {
            addStairSurfaceNodes(nodes, added, boxes, blockPos, nodePos, type, action);
        } else {
            for (AABB box : boxes) {
                if (box.maxY <= 0.0D) {
                    continue;
                }
                addExposedTopSurfaceNodes(nodes, added, boxes, box, blockPos, nodePos, type, action);
            }
        }
    }

    /**
     * menglannnn: 为楼梯/台阶生成表面节点 - 基于碰撞箱，兼容任意朝向
     * 遍历所有碰撞box，为每个顶部表面生成可站立节点
     */
    private void addStairSurfaceNodes(List<NPCPathNode> nodes, Set<String> added, List<AABB> boxes, BlockPos blockPos, BlockPos nodePos, NPCPathNode.NodeType type, NPCPathNode.MovementAction action) {
        // 按高度分组，找到所有独特的顶部表面高度
        Map<Double, List<AABB>> heightGroups = new HashMap<>();
        for (AABB box : boxes) {
            if (box.maxY <= 0.0D) {
                continue;
            }
            double height = box.maxY;
            heightGroups.computeIfAbsent(height, k -> new ArrayList<>()).add(box);
        }

        // 对每个高度层，计算暴露的顶部表面区域
        for (Map.Entry<Double, List<AABB>> entry : heightGroups.entrySet()) {
            double height = entry.getKey();
            List<AABB> layerBoxes = entry.getValue();

            // 合并同一高度的所有box的水平区域
            List<double[]> regions = new ArrayList<>();
            for (AABB box : layerBoxes) {
                regions.add(new double[]{box.minX, box.maxX, box.minZ, box.maxZ});
            }

            // 减去上方更高层的遮挡区域
            for (AABB upperBox : boxes) {
                if (upperBox.maxY <= height + COLLISION_EPSILON) {
                    continue;
                }
                List<double[]> nextRegions = new ArrayList<>();
                for (double[] region : regions) {
                    subtractHorizontalRegion(region, upperBox, nextRegions);
                }
                regions = nextRegions;
                if (regions.isEmpty()) {
                    break;
                }
            }

            // 为每个暴露区域生成节点
            for (double[] region : regions) {
                double width = region[1] - region[0];
                double depth = region[3] - region[2];
                if (width <= COLLISION_EPSILON || depth <= COLLISION_EPSILON) {
                    continue;
                }
                double standX = blockPos.getX() + (region[0] + region[1]) * 0.5D;
                double standY = blockPos.getY() + height;
                double standZ = blockPos.getZ() + (region[2] + region[3]) * 0.5D;
                TrapdoorStandAdjustment trapdoorAdjusted = adjustStandPositionForOpenTrapdoor(blockPos, standX, standZ);
                standX = trapdoorAdjusted.standX;
                standZ = trapdoorAdjusted.standZ;
                BlockPos footNodePos = BlockPos.containing(standX, standY, standZ);
                String key = NPCPathNode.createKey(standX, standY, standZ);
                if ((nodePos == null || footNodePos.equals(nodePos)) && added.add(key)) {
                    nodes.add(createSurfaceNode(footNodePos, standX, standY, standZ, type, action));
                }
            }
        }
    }

    private void addExposedTopSurfaceNodes(List<NPCPathNode> nodes, Set<String> added, List<AABB> boxes, AABB baseBox, BlockPos blockPos, BlockPos nodePos, NPCPathNode.NodeType type, NPCPathNode.MovementAction action) {
        List<double[]> regions = new ArrayList<>();
        regions.add(new double[]{baseBox.minX, baseBox.maxX, baseBox.minZ, baseBox.maxZ});
        for (AABB upperBox : boxes) {
            if (upperBox == baseBox || upperBox.maxY <= baseBox.maxY + COLLISION_EPSILON || upperBox.minY > baseBox.maxY + COLLISION_EPSILON) {
                continue;
            }
            List<double[]> nextRegions = new ArrayList<>();
            for (double[] region : regions) {
                subtractHorizontalRegion(region, upperBox, nextRegions);
            }
            regions = nextRegions;
            if (regions.isEmpty()) {
                return;
            }
        }
        for (double[] region : regions) {
            double width = region[1] - region[0];
            double depth = region[3] - region[2];
            if (width <= COLLISION_EPSILON || depth <= COLLISION_EPSILON) {
                continue;
            }
            double standX = blockPos.getX() + (region[0] + region[1]) * 0.5D;
            double standY = blockPos.getY() + baseBox.maxY;
            double standZ = blockPos.getZ() + (region[2] + region[3]) * 0.5D;
            BlockPos footNodePos = BlockPos.containing(standX, standY, standZ);
            String key = NPCPathNode.createKey(standX, standY, standZ);
            if ((nodePos == null || footNodePos.equals(nodePos)) && added.add(key)) {
                nodes.add(createSurfaceNode(footNodePos, standX, standY, standZ, type, action));
            }
        }
    }

    /**
     * menglannnn: 检测节点是否可站立（包含危险区域检查）
     */
    private boolean isSurfaceNodeStandable(NPCPathNode node) {
        // 严格检查：节点位置、上方、下方都不能有危险方块
        if (isDangerous(level.getBlockState(node.pos)) || 
            isDangerous(level.getBlockState(node.pos.above())) || 
            isDangerous(level.getBlockState(node.pos.below()))) {
            return false;
        }
        
        // 检查周围是否有危险区域（防止太靠近岩浆等）
        if (isDangerousArea(node.pos)) {
            // 如果正下方是岩浆，绝对不允许站立
            BlockState belowState = level.getBlockState(node.pos.below());
            if (belowState.getBlock() == Blocks.LAVA) {
                return false;
            }
        }
        
        return hasHeadroomAt(node.pos, node.standX, node.standY, node.standZ);
    }

    private void subtractHorizontalRegion(double[] region, AABB upperBox, List<double[]> output) {
        double minX = Math.max(region[0], upperBox.minX);
        double maxX = Math.min(region[1], upperBox.maxX);
        double minZ = Math.max(region[2], upperBox.minZ);
        double maxZ = Math.min(region[3], upperBox.maxZ);
        if (minX >= maxX - COLLISION_EPSILON || minZ >= maxZ - COLLISION_EPSILON) {
            output.add(region);
            return;
        }
        if (region[0] < minX - COLLISION_EPSILON) {
            output.add(new double[]{region[0], minX, region[2], region[3]});
        }
        if (maxX < region[1] - COLLISION_EPSILON) {
            output.add(new double[]{maxX, region[1], region[2], region[3]});
        }
        if (region[2] < minZ - COLLISION_EPSILON) {
            output.add(new double[]{minX, maxX, region[2], minZ});
        }
        if (maxZ < region[3] - COLLISION_EPSILON) {
            output.add(new double[]{minX, maxX, maxZ, region[3]});
        }
    }

    private boolean canMoveDiagonally(NPCPathNode node, int dx, int dz) {
        return resolveHorizontalNeighborNoDiagonal(node, dx, 0) != null && resolveHorizontalNeighborNoDiagonal(node, 0, dz) != null;
    }

    private NPCPathNode resolveHorizontalNeighborNoDiagonal(NPCPathNode from, int dx, int dz) {
        return findBestColumnNeighbor(from, from.pos.offset(dx, 0, dz), false);
    }

    private BlockPos findFallPosition(BlockPos start) {
        if (!isPassableForBody(start) || !isPassableForBody(start.above())) {
            return null;
        }
        for (int i = 1; i <= MAX_FALL_SCAN; i++) {
            BlockPos candidate = start.below(i);
            if (evaluateStandPosition(candidate).walkable) {
                return normalizeWalkableNode(candidate);
            }
            if (!isPassableForBody(candidate)) {
                return null;
            }
        }
        return null;
    }

    private BlockPos prepareStandPosition(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        BlockPos prepared = normalizeWalkableNode(pos);
        if (prepared != null) {
            return prepared;
        }
        prepared = normalizeWalkableNode(pos.above());
        if (prepared != null) {
            return prepared;
        }
        for (int i = 1; i <= MAX_FALL_SCAN; i++) {
            BlockPos below = pos.below(i);
            prepared = normalizeWalkableNode(below);
            if (prepared != null) {
                return prepared;
            }
            if (!isPassableForBody(below)) {
                break;
            }
        }
        return null;
    }

    private BlockPos normalizeWalkableNode(BlockPos pos) {
        if (!evaluateStandPosition(pos).walkable) {
            return null;
        }
        double standY = getStandY(pos);
        if (Double.isNaN(standY)) {
            return null;
        }
        return BlockPos.containing(pos.getX() + 0.5D, standY, pos.getZ() + 0.5D);
    }

    private StandResult evaluateStandPosition(BlockPos pos) {
        BlockState footState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(pos.above());
        BlockState belowState = level.getBlockState(pos.below());
        if (isDangerous(footState) || isDangerous(headState) || isDangerous(belowState)) {
            return StandResult.blocked();
        }
        if (isDoorBlock(footState) || isDoorBlock(belowState)) {
            return StandResult.walkable(NPCPathNode.NodeType.DOOR);
        }
        // 如果脚下是墙、栅栏等非完整方块，不能站立
        if (isNonSolidBlock(footState) || isNonSolidBlock(belowState)) {
            return StandResult.blocked();
        }
        double standY = getStandY(pos);
        if (!hasStableSupportAt(pos, standY)) {
            return StandResult.blocked();
        }
        if (standY < pos.getY() - COLLISION_EPSILON || standY >= pos.getY() + 1.0D - COLLISION_EPSILON) {
            return StandResult.blocked();
        }
        if (!hasHeadroomAt(pos, standY)) {
            return StandResult.blocked();
        }
        return StandResult.walkable(NPCPathNode.NodeType.WALKABLE);
    }

    private boolean isPassableForBody(BlockPos pos) {
        return isPassableForBody(level.getBlockState(pos), pos);
    }

    private boolean isPassableForBody(BlockState state, BlockPos pos) {
        if (isDoorBlock(state)) {
            return true;
        }
        return state.isAir() || !state.getFluidState().isEmpty() || state.getCollisionShape(level, pos).isEmpty();
    }

    private boolean isVanillaAutoStepTransition(BlockPos from, BlockPos to) {
        return isVanillaAutoStepNode(from) || isVanillaAutoStepNode(to);
    }

    private boolean isSameVanillaAutoStepBlock(NPCPathNode from, NPCPathNode to) {
        BlockPos fromSupport = getSupportBlockPos(from);
        BlockPos toSupport = getSupportBlockPos(to);
        return fromSupport.equals(toSupport) && isVanillaAutoStepBlock(level.getBlockState(fromSupport));
    }

    private BlockPos getSupportBlockPos(NPCPathNode node) {
        return BlockPos.containing(node.standX, node.standY - COLLISION_EPSILON, node.standZ);
    }

    private boolean isVanillaAutoStepNode(BlockPos pos) {
        BlockState footState = level.getBlockState(pos);
        BlockState belowState = level.getBlockState(pos.below());
        double standY = getStandY(pos);
        BlockPos supportPos = Double.isNaN(standY) ? pos.below() : BlockPos.containing(pos.getX() + 0.5D, standY - COLLISION_EPSILON, pos.getZ() + 0.5D);
        return isVanillaAutoStepBlock(footState)
                || isVanillaAutoStepBlock(belowState)
                || isVanillaAutoStepBlock(level.getBlockState(supportPos));
    }

    private boolean isVanillaAutoStepBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof SlabBlock || block instanceof StairBlock;
    }

    private double getStandY(BlockPos pos) {
        double top = getHighestCollisionTopAt(pos, pos.getX() + 0.5D, pos.getZ() + 0.5D);
        if (top > pos.getY() + COLLISION_EPSILON) {
            return top;
        }
        double belowTop = getHighestCollisionTopAt(pos.below(), pos.getX() + 0.5D, pos.getZ() + 0.5D);
        if (belowTop > pos.getY() - 1.0D + COLLISION_EPSILON) {
            return belowTop;
        }
        return Double.NaN;
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

    private double getTopFromSurfaceRegion(List<AABB> boxes, double localX, double localZ) {
        AABB selectedRegion = null;
        double selectedArea = Double.MAX_VALUE;
        double selectedTop = Double.NEGATIVE_INFINITY;
        for (AABB box : boxes) {
            if (!containsHorizontal(box, localX, localZ)) {
                continue;
            }
            double area = Math.max(0.0D, box.maxX - box.minX) * Math.max(0.0D, box.maxZ - box.minZ);
            if (selectedRegion == null || area < selectedArea || (Math.abs(area - selectedArea) <= COLLISION_EPSILON && box.maxY < selectedTop)) {
                selectedRegion = box;
                selectedArea = area;
                selectedTop = box.maxY;
            }
        }
        if (selectedRegion == null) {
            return Double.NEGATIVE_INFINITY;
        }
        for (AABB box : boxes) {
            if (box.maxY <= selectedTop + COLLISION_EPSILON) {
                continue;
            }
            if (sameHorizontalRegion(selectedRegion, box)) {
                selectedTop = box.maxY;
            }
        }
        return selectedTop;
    }

    private boolean containsHorizontal(AABB box, double localX, double localZ) {
        return localX >= box.minX - COLLISION_EPSILON && localX <= box.maxX + COLLISION_EPSILON
                && localZ >= box.minZ - COLLISION_EPSILON && localZ <= box.maxZ + COLLISION_EPSILON;
    }

    private boolean sameHorizontalRegion(AABB a, AABB b) {
        return Math.abs(a.minX - b.minX) <= COLLISION_EPSILON
                && Math.abs(a.maxX - b.maxX) <= COLLISION_EPSILON
                && Math.abs(a.minZ - b.minZ) <= COLLISION_EPSILON
                && Math.abs(a.maxZ - b.maxZ) <= COLLISION_EPSILON;
    }

    private boolean hasStableSupportAt(BlockPos pos, double standY) {
        return !Double.isNaN(standY)
                && standY >= pos.getY() - COLLISION_EPSILON
                && standY < pos.getY() + 1.0D - COLLISION_EPSILON;
    }

    private boolean hasHeadroomAt(BlockPos pos, double standY) {
        return hasHeadroomAt(pos, pos.getX() + 0.5D, standY, pos.getZ() + 0.5D);
    }

    private boolean hasHeadroomAt(BlockPos pos, double standX, double standY, double standZ) {
        // 对打开的活板门使用更窄的通行判定，避免楼梯贴墙薄碰撞把整条路误判死。
        int minX = (int) Math.floor(standX - DEFAULT_BODY_RADIUS);
        int maxX = (int) Math.floor(standX + DEFAULT_BODY_RADIUS);
        int minY = (int) Math.floor(standY + COLLISION_EPSILON);
        int maxY = (int) Math.floor(standY + 1.8D - COLLISION_EPSILON);
        int minZ = (int) Math.floor(standZ - DEFAULT_BODY_RADIUS);
        int maxZ = (int) Math.floor(standZ + DEFAULT_BODY_RADIUS);

        // menglannnn: 计算NPC站立的支撑方块位置
        BlockPos supportPos = BlockPos.containing(standX, standY - COLLISION_EPSILON, standZ);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos checkPos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(checkPos);
                    if (isDoorBlock(state)) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level, checkPos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    double localMinY = standY - checkPos.getY() + COLLISION_EPSILON;

                    // menglannnn: 对于NPC站立的支撑方块（如楼梯、台阶），允许其碰撞形状与NPC身体相交
                    // 只检查是否有高于NPC头部的碰撞部分真正阻挡
                    if (checkPos.equals(supportPos)) {
                        boolean blocksHead = false;
                        for (AABB box : shape.toAabbs()) {
                            if (box.maxY > localMinY + 1.8D - COLLISION_EPSILON) {
                                BodyCollisionBounds bodyBounds = getCollisionCheckBodyBounds(state, checkPos, standX, standZ);
                                double npcMinX = bodyBounds.minX;
                                double npcMaxX = bodyBounds.maxX;
                                double npcMinZ = bodyBounds.minZ;
                                double npcMaxZ = bodyBounds.maxZ;
                                double boxMinX = checkPos.getX() + box.minX;
                                double boxMaxX = checkPos.getX() + box.maxX;
                                double boxMinZ = checkPos.getZ() + box.minZ;
                                double boxMaxZ = checkPos.getZ() + box.maxZ;

                                boolean horizontalOverlap = npcMaxX > boxMinX + COLLISION_EPSILON && npcMinX < boxMaxX - COLLISION_EPSILON
                                        && npcMaxZ > boxMinZ + COLLISION_EPSILON && npcMinZ < boxMaxZ - COLLISION_EPSILON;
                                if (horizontalOverlap) {
                                    blocksHead = true;
                                    break;
                                }
                            }
                        }
                        if (blocksHead) {
                            return false;
                        }
                        continue;
                    }

                    for (AABB box : shape.toAabbs()) {
                        // 检查box是否与NPC身体区域在水平方向上有重叠
                        BodyCollisionBounds bodyBounds = getCollisionCheckBodyBounds(state, checkPos, standX, standZ);
                        double npcMinX = bodyBounds.minX;
                        double npcMaxX = bodyBounds.maxX;
                        double npcMinZ = bodyBounds.minZ;
                        double npcMaxZ = bodyBounds.maxZ;
                        double boxMinX = checkPos.getX() + box.minX;
                        double boxMaxX = checkPos.getX() + box.maxX;
                        double boxMinZ = checkPos.getZ() + box.minZ;
                        double boxMaxZ = checkPos.getZ() + box.maxZ;

                        boolean horizontalOverlap = npcMaxX > boxMinX + COLLISION_EPSILON && npcMinX < boxMaxX - COLLISION_EPSILON
                                && npcMaxZ > boxMinZ + COLLISION_EPSILON && npcMinZ < boxMaxZ - COLLISION_EPSILON;
                        if (horizontalOverlap && box.maxY > localMinY) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private BodyCollisionBounds getCollisionCheckBodyBounds(BlockState state, BlockPos pos, double standX, double standZ) {
        double minX = standX - DEFAULT_BODY_RADIUS;
        double maxX = standX + DEFAULT_BODY_RADIUS;
        double minZ = standZ - DEFAULT_BODY_RADIUS;
        double maxZ = standZ + DEFAULT_BODY_RADIUS;
        if (!isOpenTrapdoor(state)) {
            return new BodyCollisionBounds(minX, maxX, minZ, maxZ);
        }

        double[] trapdoorBounds = getTrapdoorHorizontalBounds(state, pos);
        if (trapdoorBounds == null) {
            return new BodyCollisionBounds(standX - OPEN_TRAPDOOR_BODY_RADIUS, standX + OPEN_TRAPDOOR_BODY_RADIUS,
                    standZ - OPEN_TRAPDOOR_BODY_RADIUS, standZ + OPEN_TRAPDOOR_BODY_RADIUS);
        }

        double thicknessX = trapdoorBounds[1] - trapdoorBounds[0];
        double thicknessZ = trapdoorBounds[3] - trapdoorBounds[2];
        if (thicknessX <= thicknessZ && thicknessX <= 0.25D) {
            minZ = standZ - OPEN_TRAPDOOR_BODY_RADIUS;
            maxZ = standZ + OPEN_TRAPDOOR_BODY_RADIUS;
            if ((trapdoorBounds[0] + trapdoorBounds[1]) * 0.5D <= pos.getX() + 0.5D) {
                minX = standX - OPEN_TRAPDOOR_PANEL_SIDE_RADIUS;
            } else {
                maxX = standX + OPEN_TRAPDOOR_PANEL_SIDE_RADIUS;
            }
        } else if (thicknessZ <= 0.25D) {
            minX = standX - OPEN_TRAPDOOR_BODY_RADIUS;
            maxX = standX + OPEN_TRAPDOOR_BODY_RADIUS;
            if ((trapdoorBounds[2] + trapdoorBounds[3]) * 0.5D <= pos.getZ() + 0.5D) {
                minZ = standZ - OPEN_TRAPDOOR_PANEL_SIDE_RADIUS;
            } else {
                maxZ = standZ + OPEN_TRAPDOOR_PANEL_SIDE_RADIUS;
            }
        } else {
            minX = standX - OPEN_TRAPDOOR_BODY_RADIUS;
            maxX = standX + OPEN_TRAPDOOR_BODY_RADIUS;
            minZ = standZ - OPEN_TRAPDOOR_BODY_RADIUS;
            maxZ = standZ + OPEN_TRAPDOOR_BODY_RADIUS;
        }
        return new BodyCollisionBounds(minX, maxX, minZ, maxZ);
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
            minX = Math.min(minX, pos.getX() + box.minX);
            maxX = Math.max(maxX, pos.getX() + box.maxX);
            minZ = Math.min(minZ, pos.getZ() + box.minZ);
            maxZ = Math.max(maxZ, pos.getZ() + box.maxZ);
        }
        return found ? new double[] {minX, maxX, minZ, maxZ} : null;
    }

    private TrapdoorStandAdjustment adjustStandPositionForOpenTrapdoor(BlockPos supportPos, double standX, double standZ) {
        double originalStandX = standX;
        double originalStandZ = standZ;
        double adjustedX = standX;
        double adjustedZ = standZ;
        Set<BlockPos> visited = new HashSet<>();
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos layerPos = supportPos.above(dy);
            BlockPos[] candidates = new BlockPos[] {
                    layerPos,
                    layerPos.north(),
                    layerPos.south(),
                    layerPos.east(),
                    layerPos.west()
            };
            for (BlockPos candidate : candidates) {
                if (!visited.add(candidate)) {
                    continue;
                }
                BlockState state = level.getBlockState(candidate);
                if (!isOpenTrapdoor(state)) {
                    continue;
                }
                double[] bounds = getTrapdoorHorizontalBounds(state, candidate);
                if (bounds == null) {
                    continue;
                }
                double thicknessX = bounds[1] - bounds[0];
                double thicknessZ = bounds[3] - bounds[2];
                boolean overlapsX = adjustedX + DEFAULT_BODY_RADIUS > bounds[0] + COLLISION_EPSILON
                        && adjustedX - DEFAULT_BODY_RADIUS < bounds[1] - COLLISION_EPSILON;
                boolean overlapsZ = adjustedZ + DEFAULT_BODY_RADIUS > bounds[2] + COLLISION_EPSILON
                        && adjustedZ - DEFAULT_BODY_RADIUS < bounds[3] - COLLISION_EPSILON;
                if (!overlapsX || !overlapsZ) {
                    continue;
                }

                double supportCenterX = supportPos.getX() + 0.5D;
                double supportCenterZ = supportPos.getZ() + 0.5D;
                if (thicknessX <= thicknessZ && thicknessX <= 0.25D) {
                    double panelCenterX = (bounds[0] + bounds[1]) * 0.5D;
                    adjustedX = panelCenterX <= supportCenterX
                            ? Math.max(adjustedX, supportCenterX + OPEN_TRAPDOOR_STAND_BIAS)
                            : Math.min(adjustedX, supportCenterX - OPEN_TRAPDOOR_STAND_BIAS);
                } else if (thicknessZ <= 0.25D) {
                    double panelCenterZ = (bounds[2] + bounds[3]) * 0.5D;
                    adjustedZ = panelCenterZ <= supportCenterZ
                            ? Math.max(adjustedZ, supportCenterZ + OPEN_TRAPDOOR_STAND_BIAS)
                            : Math.min(adjustedZ, supportCenterZ - OPEN_TRAPDOOR_STAND_BIAS);
                }
            }
        }
        adjustedX = clamp(adjustedX, supportPos.getX() + STAND_POSITION_MIN_MARGIN, supportPos.getX() + STAND_POSITION_MAX_MARGIN);
        adjustedZ = clamp(adjustedZ, supportPos.getZ() + STAND_POSITION_MIN_MARGIN, supportPos.getZ() + STAND_POSITION_MAX_MARGIN);
        if (ServerConfig.isDebugLogEnabled()
                && (Math.abs(adjustedX - originalStandX) > COLLISION_EPSILON || Math.abs(adjustedZ - originalStandZ) > COLLISION_EPSILON)) {
            Simukraft.LOGGER.info("[NPCPathFinder][TrapdoorBias] support={} fromStand=({}, {}) adjustedStand=({}, {})",
                    supportPos, originalStandX, originalStandZ, adjustedX, adjustedZ);
        }
        return new TrapdoorStandAdjustment(adjustedX, adjustedZ);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isOpenTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN);
    }

    private static final class BodyCollisionBounds {
        private final double minX;
        private final double maxX;
        private final double minZ;
        private final double maxZ;

        private BodyCollisionBounds(double minX, double maxX, double minZ, double maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }

    private static final class TrapdoorStandAdjustment {
        private final double standX;
        private final double standZ;

        private TrapdoorStandAdjustment(double standX, double standZ) {
            this.standX = standX;
            this.standZ = standZ;
        }
    }

    private boolean isJumpOverObstacle(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || isDoorBlock(state) || isDangerous(state)) {
            return false;
        }

        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }

        double height = getCollisionHeight(state, pos);
        if (height <= WALK_STEP_HEIGHT + COLLISION_EPSILON || height > MAX_JUMP_OVER_HEIGHT) {
            return false;
        }

        BlockPos abovePos = pos.above();
        BlockPos twoAbovePos = pos.above(2);
        if (requiresJumpCoverToClear(state) && !isThinWalkableCover(level.getBlockState(abovePos), abovePos)) {
            return false;
        }
        return isJumpOverCoverPassable(abovePos)
                && level.getBlockState(twoAbovePos).getCollisionShape(level, twoAbovePos).isEmpty();
    }

    private boolean isJumpOverArcClear(BlockPos obstaclePos, NPCPathNode landingNode) {
        if (landingNode == null || !hasHeadroomAt(landingNode.pos, landingNode.standX, landingNode.standY, landingNode.standZ)) {
            return false;
        }

        double obstacleTop = getHighestCollisionTopAt(obstaclePos, obstaclePos.getX() + 0.5D, obstaclePos.getZ() + 0.5D);
        if (obstacleTop == Double.NEGATIVE_INFINITY) {
            return false;
        }

        double clearanceY = obstacleTop + 0.2D;
        return hasJumpClearanceAt(obstaclePos, clearanceY)
                && hasJumpClearanceAt(obstaclePos.above(), clearanceY)
                && hasJumpClearanceAt(landingNode.pos, Math.max(clearanceY, landingNode.standY + 0.1D));
    }

    private boolean hasJumpClearanceAt(BlockPos pos, double minY) {
        BlockState state = level.getBlockState(pos);
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return true;
        }
        for (AABB box : state.getCollisionShape(level, pos).toAabbs()) {
            if (pos.getY() + box.maxY > minY - COLLISION_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private boolean isJumpOverCoverPassable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return true;
        }
        return isThinWalkableCover(state, pos);
    }

    private boolean isThinWalkableCover(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof CarpetBlock) {
            return true;
        }
        return getCollisionHeight(state, pos) <= 0.125D + COLLISION_EPSILON;
    }

    private boolean requiresJumpCoverToClear(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FenceBlock || block instanceof WallBlock || block instanceof IronBarsBlock;
    }

    private boolean isDoorBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock || block instanceof FenceGateBlock;
    }

    /**
     * menglannnn: 检测方块是否为不可站立/穿过的非完整方块（墙、栅栏、铁栏杆、活板门等）
     */
    private boolean isNonSolidBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof WallBlock
            || block instanceof FenceBlock
            || block instanceof IronBarsBlock
            || block instanceof TrapDoorBlock;
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

    /**
     * menglannnn: 检测方块是否为危险方块
     */
    private boolean isDangerous(BlockState state) {
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
            // 窒息方块
            || block == Blocks.SAND
            || block == Blocks.GRAVEL
            // 伤害性环境
            || block == Blocks.BUBBLE_COLUMN
            || block == Blocks.BIG_DRIPLEAF;
    }

    /**
     * menglannnn: 检测位置是否为危险区域（包括周围）
     */
    private boolean isDangerousArea(BlockPos pos) {
        // 检查节点本身及周围
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(checkPos);
                    if (isDangerous(state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * menglannnn: 计算位置的危险代价（距离越近代价越高）
     */
    private double calculateDangerCost(BlockPos pos) {
        double cost = 0.0D;
        
        // 检查节点正下方
        BlockState belowState = level.getBlockState(pos.below());
        if (isDangerous(belowState)) {
            return COST_DANGER;
        }
        
        // 检查节点位置
        BlockState footState = level.getBlockState(pos);
        if (isDangerous(footState)) {
            return COST_DANGER;
        }
        
        // 检查头部位置
        BlockState headState = level.getBlockState(pos.above());
        if (isDangerous(headState)) {
            cost += COST_DANGER * 0.8D;
        }
        
        // 检查周围区域（距离1）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos nearbyPos = pos.offset(dx, 0, dz);
                if (isDangerous(level.getBlockState(nearbyPos)) || 
                    isDangerous(level.getBlockState(nearbyPos.below()))) {
                    cost += COST_DANGER_ADJACENT;
                }
            }
        }
        
        // 检查更远区域（距离2）
        for (int dx = -DANGER_CHECK_RADIUS; dx <= DANGER_CHECK_RADIUS; dx++) {
            for (int dz = -DANGER_CHECK_RADIUS; dz <= DANGER_CHECK_RADIUS; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                BlockPos nearbyPos = pos.offset(dx, 0, dz);
                if (isDangerous(level.getBlockState(nearbyPos)) || 
                    isDangerous(level.getBlockState(nearbyPos.below()))) {
                    cost += COST_DANGER_NEARBY / (Math.abs(dx) + Math.abs(dz));
                }
            }
        }
        
        return cost;
    }

    private double calculateBarrierCost(BlockPos pos) {
        double cost = 0.0D;
        BlockState footState = level.getBlockState(pos);
        if (isFenceLikeBarrier(footState)) {
            cost += getBarrierPenalty(level.getBlockState(pos.above()), pos.above(), COST_BARRIER_ON_NODE);
        }
        BlockState belowState = level.getBlockState(pos.below());
        if (isFenceLikeBarrier(belowState)) {
            cost += getBarrierPenalty(level.getBlockState(pos), pos, COST_BARRIER_ON_NODE * 0.8D);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos nearbyPos = pos.offset(dx, 0, dz);
                if (isFenceLikeBarrier(level.getBlockState(nearbyPos))) {
                    cost += getBarrierPenalty(level.getBlockState(nearbyPos.above()), nearbyPos.above(), COST_BARRIER_ADJACENT);
                }
                BlockPos nearbyBelowPos = nearbyPos.below();
                if (isFenceLikeBarrier(level.getBlockState(nearbyBelowPos))) {
                    cost += getBarrierPenalty(level.getBlockState(nearbyPos), nearbyPos, COST_BARRIER_ADJACENT * 0.8D);
                }
            }
        }
        for (int dx = -DANGER_CHECK_RADIUS; dx <= DANGER_CHECK_RADIUS; dx++) {
            for (int dz = -DANGER_CHECK_RADIUS; dz <= DANGER_CHECK_RADIUS; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    continue;
                }
                BlockPos nearbyPos = pos.offset(dx, 0, dz);
                int distance = Math.abs(dx) + Math.abs(dz);
                if (isFenceLikeBarrier(level.getBlockState(nearbyPos))) {
                    cost += getBarrierPenalty(level.getBlockState(nearbyPos.above()), nearbyPos.above(), COST_BARRIER_NEARBY / distance);
                }
                BlockPos nearbyBelowPos = nearbyPos.below();
                if (isFenceLikeBarrier(level.getBlockState(nearbyBelowPos))) {
                    cost += getBarrierPenalty(level.getBlockState(nearbyPos), nearbyPos, (COST_BARRIER_NEARBY * 0.8D) / distance);
                }
            }
        }
        return cost;
    }

    private double getBarrierPenalty(BlockState coverState, BlockPos coverPos, double basePenalty) {
        return isThinWalkableCover(coverState, coverPos) ? basePenalty * COST_BARRIER_COVER_DISCOUNT : basePenalty;
    }

    private boolean isFenceLikeBarrier(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FenceBlock || block instanceof WallBlock || block instanceof IronBarsBlock;
    }

    private BlockPos findAlternativeEndPoint(BlockPos target) {
        return findAlternativeTargetNear(target, 4);
    }

    public BlockPos findAlternativeTargetNear(BlockPos target, int rangeLimit) {
        for (int range = 1; range <= rangeLimit; range++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos prepared = prepareStandPosition(target.offset(dx, dy, dz));
                        if (prepared != null) {
                            return prepared;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isAtTarget(NPCPathNode node, NPCPathNode target) {
        return node.pos.equals(target.pos) || node.distanceTo(target) <= 0.35D;
    }

    private double calculateHeuristic(NPCPathNode from, NPCPathNode to) {
        return from.distanceTo(to);
    }

    /**
     * menglannnn: 计算移动代价（包含危险区域避让）
     */
    private PathCostBreakdown calculateMoveCostBreakdown(NPCPathNode from, NPCPathNode to, PathCostRules costRules) {
        double dangerCost = calculateDangerCost(to.pos);
        double barrierCost = calculateBarrierCost(to.pos);
        TerrainMoveDescriptor descriptor = terrainCostClassifier.classify(from, to, dangerCost, barrierCost);
        return PathCostEngine.calculate(descriptor, costRules);
    }

    private void applyCostBreakdown(NPCPathNode node, PathCostBreakdown breakdown) {
        node.stepCost = breakdown.totalCost();
        node.terrainCost = breakdown.terrainCost() + breakdown.maxPenaltyCost();
        node.costReason = breakdown.summary();
    }

    private NPCPath reconstructPath(NPCPathNode endNode, BlockPos start, BlockPos end, PathCostRules costRules) {
        List<NPCPathNode> path = new ArrayList<>();
        NPCPathNode current = endNode;
        while (current != null) {
            path.add(current);
            current = current.parent;
        }
        Collections.reverse(path);
        NPCPath npcPath = NPCPath.fromNodes(level, path, start, end);
        npcPath.smooth();
        refreshPathCostMetadata(npcPath, costRules);
        return npcPath;
    }

    private void refreshPathCostMetadata(NPCPath path, PathCostRules costRules) {
        if (path == null || path.isEmpty()) {
            return;
        }
        List<NPCPathNode> nodes = path.getNodes();
        if (!nodes.isEmpty()) {
            NPCPathNode first = nodes.get(0);
            first.stepCost = 0.0D;
            first.terrainCost = 0.0D;
            first.costReason = "start";
        }
        for (int i = 1; i < nodes.size(); i++) {
            NPCPathNode current = nodes.get(i);
            NPCPathNode previous = nodes.get(i - 1);
            applyCostBreakdown(current, calculateMoveCostBreakdown(previous, current, costRules));
        }
    }

    /*private void logPathComposition(BlockPos start, BlockPos end, NPCPath path) {
        if (path == null || path.isFailed() || path.isEmpty()) {
            return;
        }
        int jumpCount = 0;
        int fallCount = 0;
        StringBuilder typesBuilder = new StringBuilder();
        for (NPCPathNode node : path.getNodes()) {
            if (node.type == NPCPathNode.NodeType.JUMP) {
                jumpCount++;
            } else if (node.type == NPCPathNode.NodeType.FALL) {
                fallCount++;
            }
            if (typesBuilder.length() > 0) {
                typesBuilder.append(" -> ");
            }
            typesBuilder.append(node.type.name()).append("/").append(node.action.name()).append("@").append(node.pos);
        }
        Simukraft.LOGGER.info("[NPCPathFinder] 路径完成 start={} end={} nodes={} jump={} fall={} path={}", start, end, path.getTotalNodes(), jumpCount, fallCount, typesBuilder);
    }*/

    private NPCPath createFailedPath(BlockPos start, BlockPos end) {
        NPCPath path = new NPCPath(level, start, end);
        path.markFailed();
        return path;
    }

    private static class StandResult {
        private final boolean walkable;
        private final NPCPathNode.NodeType nodeType;

        private StandResult(boolean walkable, NPCPathNode.NodeType nodeType) {
            this.walkable = walkable;
            this.nodeType = nodeType;
        }

        private static StandResult walkable(NPCPathNode.NodeType nodeType) {
            return new StandResult(true, nodeType);
        }

        private static StandResult blocked() {
            return new StandResult(false, NPCPathNode.NodeType.WALKABLE);
        }
    }

    private enum StairTransitionType {
        NONE,
        SAME_BLOCK_TRAVERSE,
        SAME_BLOCK_ASCEND,
        ENTER_RAMP_TRAVERSE,
        ENTER_ASCEND
    }
}
