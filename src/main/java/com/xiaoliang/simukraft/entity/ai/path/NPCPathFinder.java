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
    private static final double COST_STRAIGHT = 1.0D;
    private static final double COST_DIAGONAL = 1.45D;
    private static final double COST_JUMP = 2.2D;
    private static final double COST_FALL = 1.35D;
    private static final double COST_DOOR = 1.15D;
    private static final double COST_DANGER = 100.0D;
    private static final double COST_DANGER_NEARBY = 30.0D;
    private static final double COST_DANGER_ADJACENT = 15.0D;
    private static final int DANGER_CHECK_RADIUS = 2;
    private static final double WALK_STEP_HEIGHT = 12.0D / 16.0D;
    private static final double MAX_ASCEND_HEIGHT = 19.0D / 16.0D;
    private static final double COLLISION_EPSILON = 1.0E-5D;

    private final ServerLevel level;

    public NPCPathFinder(ServerLevel level) {
        this.level = level;
    }

    public BlockPos normalizeStartPosition(BlockPos pos) {
        return prepareStandPosition(pos);
    }

    public BlockPos normalizeTargetPosition(BlockPos pos) {
        BlockPos normalized = prepareStandPosition(pos);
        return normalized != null ? normalized : findAlternativeEndPoint(pos);
    }

    public NPCPath findPath(BlockPos start, BlockPos end) {
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
                NPCPath path = reconstructPath(current, normalizedStart, normalizedEnd);
                logPathComposition(normalizedStart, normalizedEnd, path);
                return path;
            }

            closedSet.add(current.key);
            for (NPCPathNode neighbor : getNeighbors(current)) {
                if (closedSet.contains(neighbor.key)) {
                    continue;
                }

                double tentativeGCost = current.gCost + calculateMoveCost(current, neighbor);
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
                NPCPathNode node = createNeighbor(from, candidateNode, nodeType);
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

        if (isSameVanillaAutoStepBlock(from, target) && heightDelta <= WALK_STEP_HEIGHT + COLLISION_EPSILON && heightDelta >= -WALK_STEP_HEIGHT - COLLISION_EPSILON) {
            logStepDecision("SAME_AUTO_STEP_TRAVERSE", from, target, heightDelta, preferredType);
            return copyNode(target, NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE);
        }
        if (isVanillaAutoStepTransition(from.pos, target.pos) && heightDelta <= WALK_STEP_HEIGHT + COLLISION_EPSILON && heightDelta >= -WALK_STEP_HEIGHT - COLLISION_EPSILON) {
            logStepDecision("AUTO_STEP_TRANSITION_TRAVERSE", from, target, heightDelta, preferredType);
            return copyNode(target, NPCPathNode.NodeType.WALKABLE, NPCPathNode.MovementAction.TRAVERSE);
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
        for (AABB box : boxes) {
            if (box.maxY <= 0.0D) {
                continue;
            }
            addExposedTopSurfaceNodes(nodes, added, boxes, box, blockPos, nodePos, type, action);
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
        // NPC身体宽度约为0.6格，检查身体占据的整个区域
        double bodyRadius = 0.3D;
        int minX = (int) Math.floor(standX - bodyRadius);
        int maxX = (int) Math.floor(standX + bodyRadius);
        int minY = (int) Math.floor(standY + COLLISION_EPSILON);
        int maxY = (int) Math.floor(standY + 1.8D - COLLISION_EPSILON);
        int minZ = (int) Math.floor(standZ - bodyRadius);
        int maxZ = (int) Math.floor(standZ + bodyRadius);

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
                                double npcMinX = standX - bodyRadius;
                                double npcMaxX = standX + bodyRadius;
                                double npcMinZ = standZ - bodyRadius;
                                double npcMaxZ = standZ + bodyRadius;
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
                        double npcMinX = standX - bodyRadius;
                        double npcMaxX = standX + bodyRadius;
                        double npcMinZ = standZ - bodyRadius;
                        double npcMaxZ = standZ + bodyRadius;
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
    private double calculateMoveCost(NPCPathNode from, NPCPathNode to) {
        double baseCost = from.distanceTo(to);
        boolean diagonal = from.x != to.x && from.z != to.z;
        double cost = diagonal ? baseCost * COST_DIAGONAL : baseCost * COST_STRAIGHT;
        switch (to.action) {
            case ASCEND:
                cost *= COST_JUMP;
                break;
            case DESCEND:
            case FALL:
                cost *= COST_FALL;
                break;
            case DOOR:
                cost *= COST_DOOR;
                break;
            default:
                break;
        }
        
        // 使用新的危险代价计算
        double dangerCost = calculateDangerCost(to.pos);
        cost += dangerCost;
        
        return cost;
    }

    private NPCPath reconstructPath(NPCPathNode endNode, BlockPos start, BlockPos end) {
        List<NPCPathNode> path = new ArrayList<>();
        NPCPathNode current = endNode;
        while (current != null) {
            path.add(current);
            current = current.parent;
        }
        Collections.reverse(path);
        NPCPath npcPath = NPCPath.fromNodes(level, path, start, end);
        npcPath.smooth();
        return npcPath;
    }

    private void logPathComposition(BlockPos start, BlockPos end, NPCPath path) {
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
    }

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
}
