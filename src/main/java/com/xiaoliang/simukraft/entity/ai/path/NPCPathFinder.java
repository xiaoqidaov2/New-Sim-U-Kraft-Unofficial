package com.xiaoliang.simukraft.entity.ai.path;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * NPC路径查找器（menglannnn: 使用A*算法）
 */
@SuppressWarnings("null")
public class NPCPathFinder {
    
    // 最大迭代次数
    private static final int MAX_ITERATIONS = 2000;
    // 移动代价
    private static final double COST_STRAIGHT = 1.0;
    private static final double COST_JUMP = 2.0;
    private static final double COST_FALL = 1.5;
    private static final double COST_CLIMB = 1.0;
    private static final double COST_DOOR = 1.2;
    private static final double COST_WATER = 3.0;
    private static final double COST_DIAGONAL_NEAR_OBSTACLE = 3.5;
    private static final double COST_DIAGONAL_CLEAR = 1.4;
    
    private final ServerLevel level;
    public NPCPathFinder(ServerLevel level) {
        this.level = level;
        new PathCostCalculator();
    }
    
    /**
     * 查找路径（A*算法）
     * @param start 起点
     * @param end 终点
     * @return 找到的路径，失败返回null
     */
    public NPCPath findPath(BlockPos start, BlockPos end) {
        // 快速检查：如果起点和终点相同，返回空路径
        if (start.equals(end)) {
            NPCPath path = new NPCPath(level, start, end);
            path.addNode(new NPCPathNode(start));
            path.markCompleted();
            return path;
        }
        
        // 检查终点是否可达
        if (!isValidEndPoint(end)) {
            // 尝试找到附近的可达位置
            BlockPos alternativeEnd = findAlternativeEndPoint(end);
            if (alternativeEnd == null) {
                return createFailedPath(start, end);
            }
            end = alternativeEnd;
        }
        
        // A*算法
        PriorityQueue<NPCPathNode> openSet = new PriorityQueue<>();
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, NPCPathNode> nodeMap = new HashMap<>();
        
        NPCPathNode startNode = new NPCPathNode(start);
        startNode.gCost = 0;
        startNode.hCost = calculateHeuristic(start, end);
        startNode.fCost = startNode.hCost;
        
        openSet.add(startNode);
        nodeMap.put(start, startNode);
        
        int iterations = 0;
        
        while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            
            NPCPathNode current = openSet.poll();
            
            if (current.pos.equals(end)) {
                // 找到路径
                NPCPath path = reconstructPath(current, start, end);
                logPathComposition(start, end, path);
                return path;
            }
            
            closedSet.add(current.pos);
            
            // 获取邻居节点
            List<NPCPathNode> neighbors = getNeighbors(current);
            
            for (NPCPathNode neighbor : neighbors) {
                if (closedSet.contains(neighbor.pos)) {
                    continue;
                }
                
                double tentativeGCost = current.gCost + calculateMoveCost(current, neighbor);
                
                NPCPathNode existingNode = nodeMap.get(neighbor.pos);
                if (existingNode == null || tentativeGCost < existingNode.gCost) {
                    neighbor.gCost = tentativeGCost;
                    neighbor.hCost = calculateHeuristic(neighbor.pos, end);
                    neighbor.fCost = neighbor.gCost + neighbor.hCost;
                    neighbor.parent = current;
                    
                    if (existingNode == null) {
                        nodeMap.put(neighbor.pos, neighbor);
                        openSet.add(neighbor);
                    }
                }
            }
        }
        
        // 寻路失败
        return createFailedPath(start, end);
    }
    
    /**
     * 获取邻居节点
     */
    private List<NPCPathNode> getNeighbors(NPCPathNode node) {
        List<NPCPathNode> neighbors = new ArrayList<>();
        
        // 水平方向（8个方向）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                // 对角移动时，禁止穿过墙角
                if (dx != 0 && dz != 0 && !canMoveDiagonally(node.x, node.y, node.z, dx, dz)) {
                    continue;
                }

                BlockPos horizontalPos = new BlockPos(node.x + dx, node.y, node.z + dz);
                BlockPos jumpPos = new BlockPos(node.x + dx, node.y + 1, node.z + dz);
                boolean shouldPreferStepUp = isStepUp(node.pos, jumpPos);
                if (shouldPreferStepUp) {
                    NPCPathNode neighbor = new NPCPathNode(jumpPos);
                    neighbor.type = NPCPathNode.NodeType.STEP_UP;
                    neighbors.add(neighbor);
                } else if (isWalkable(horizontalPos)) {
                    NPCPathNode neighbor = new NPCPathNode(horizontalPos);
                    neighbor.type = isDoorNode(horizontalPos) ? NPCPathNode.NodeType.DOOR : NPCPathNode.NodeType.WALKABLE;
                    neighbors.add(neighbor);
                }
                
                // 检查向上跳跃（1格高）
                if (!shouldPreferStepUp && isJumpable(node.pos, jumpPos)) {
                    NPCPathNode neighbor = new NPCPathNode(jumpPos);
                    neighbor.type = NPCPathNode.NodeType.JUMP;
                    neighbors.add(neighbor);
                }
                
                // 检查向下（掉落）
                BlockPos fallPos = findFallPosition(node.x + dx, node.y, node.z + dz);
                if (fallPos != null && !fallPos.equals(node.pos)) {
                    NPCPathNode neighbor = new NPCPathNode(fallPos);
                    neighbor.type = NPCPathNode.NodeType.FALL;
                    neighbors.add(neighbor);
                }
            }
        }
        
        // 检查梯子/藤蔓（上下攀爬）
        BlockPos ladderUp = new BlockPos(node.x, node.y + 1, node.z);
        BlockPos ladderDown = new BlockPos(node.x, node.y - 1, node.z);
        
        if (isClimbable(ladderUp)) {
            NPCPathNode neighbor = new NPCPathNode(ladderUp);
            neighbor.type = NPCPathNode.NodeType.CLIMB;
            neighbors.add(neighbor);
        }
        
        if (isClimbable(ladderDown)) {
            NPCPathNode neighbor = new NPCPathNode(ladderDown);
            neighbor.type = NPCPathNode.NodeType.CLIMB;
            neighbors.add(neighbor);
        }
        
        return neighbors;
    }
    
    /**
     * 检查是否允许对角移动，避免穿过墙角
     */
    private boolean canMoveDiagonally(int x, int y, int z, int dx, int dz) {
        BlockPos sidePosX = new BlockPos(x + dx, y, z);
        BlockPos sidePosZ = new BlockPos(x, y, z + dz);
        return isWalkable(sidePosX) && isWalkable(sidePosZ);
    }

    /**
     * 检查位置是否可行走
     */
    private boolean isWalkable(BlockPos pos) {
        BlockPos groundPos = pos.below();
        BlockPos headPos = pos.above();
        
        BlockState groundState = level.getBlockState(groundPos);
        BlockState footState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(headPos);
        
        // 脚下必须是可支撑站立的方块
        if (!groundState.isFaceSturdy(level, groundPos, net.minecraft.core.Direction.UP)) {
            // 检查是否是可站立的非实体方块（如栅栏、墙）
            if (!canStandOn(groundState)) {
                return false;
            }
        }
        
        // 脚和头的位置必须是可通行的
        if (!isPassable(footState) || !isPassable(headState)) {
            return false;
        }
        
        // 检查危险方块（岩浆、火等）
        if (isDangerous(footState) || isDangerous(headState)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查是否可以跳跃到该位置
     */
    private boolean isJumpable(BlockPos from, BlockPos to) {
        if (isStepUp(from, to)) {
            return false;
        }

        // 目标位置必须可行走
        if (!isWalkable(to)) return false;
        
        // 检查起跳位置是否有足够的空间
        BlockPos jumpSpace = from.above().above();
        if (!isPassable(level.getBlockState(jumpSpace))) {
            return false;
        }
        
        return true;
    }

    private boolean isStepUp(BlockPos from, BlockPos to) {
        if (to.getY() - from.getY() != 1) {
            return false;
        }

        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return false;
        }

        BlockPos obstaclePos = new BlockPos(from.getX() + dx, from.getY(), from.getZ() + dz);
        BlockPos landingPos = obstaclePos.above();
        BlockPos landingHeadPos = landingPos.above();
        if (!landingPos.equals(to)) {
            return false;
        }

        BlockState obstacleState = level.getBlockState(obstaclePos);
        BlockState landingState = level.getBlockState(landingPos);
        BlockState landingHeadState = level.getBlockState(landingHeadPos);

        if (isVanillaStepBlock(obstacleState, obstaclePos)) {
            return false;
        }

        boolean obstacleBlocksFeet = !obstacleState.getCollisionShape(level, obstaclePos).isEmpty();
        boolean landingClear = landingState.getCollisionShape(level, landingPos).isEmpty();
        boolean landingHeadClear = landingHeadState.getCollisionShape(level, landingHeadPos).isEmpty();
        return obstacleBlocksFeet && landingClear && landingHeadClear;
    }

    private boolean isDoorNode(BlockPos pos) {
        return isDoorBlock(level.getBlockState(pos)) || isDoorBlock(level.getBlockState(pos.below()));
    }

    private boolean isDoorBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock;
    }
    
    /**
     * 查找掉落位置（向下搜索）
     */
    private BlockPos findFallPosition(int x, int y, int z) {
        // 最多掉落3格
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos fallPos = new BlockPos(x, y - dy, z);
            if (isWalkable(fallPos)) {
                return fallPos;
            }
        }
        return null;
    }
    
    /**
     * 检查是否可以站立在该方块上
     */
    private boolean canStandOn(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FenceBlock ||
               block instanceof FenceGateBlock ||
               block instanceof StairBlock ||
               block instanceof SlabBlock;
    }

    private boolean isVanillaStepBlock(BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (block instanceof SlabBlock || block instanceof StairBlock) {
            return true;
        }
        return getCollisionHeight(state, pos) > 0.0D && getCollisionHeight(state, pos) <= 1.0D
                && !state.isFaceSturdy(level, pos, Direction.UP);
    }

    private double getCollisionHeight(BlockState state, BlockPos pos) {
        var shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return 0.0D;
        }
        return shape.max(Direction.Axis.Y);
    }
    
    /**
     * 检查方块是否可通行
     */
    private boolean isPassable(BlockState state) {
        Block block = state.getBlock();
        
        // 空气、液体、门、活板门等都可以通行
        if (state.isAir() || !state.getFluidState().isEmpty() || 
            block instanceof DoorBlock ||
            block instanceof TrapDoorBlock ||
            block instanceof LadderBlock) {
            return true;
        }
        
        // 检查是否是可通行的非完整碰撞方块
        return state.getCollisionShape(level, BlockPos.ZERO).isEmpty();
    }
    
    /**
     * 检查是否可以攀爬
     */
    private boolean isClimbable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        
        if (block instanceof LadderBlock) {
            return true;
        }
        
        // 检查是否是藤蔓
        if (block == Blocks.VINE) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查是否是危险方块
     */
    private boolean isDangerous(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.LAVA ||
               block == Blocks.FIRE ||
               block == Blocks.SOUL_FIRE ||
               block == Blocks.CACTUS ||
               block == Blocks.SWEET_BERRY_BUSH;
    }
    
    /**
     * 检查终点是否有效
     */
    private boolean isValidEndPoint(BlockPos pos) {
        return isWalkable(pos);
    }
    
    /**
     * 查找替代的终点位置
     */
    private BlockPos findAlternativeEndPoint(BlockPos target) {
        return findAlternativeTargetNear(target, 3);
    }

    public BlockPos findAlternativeTargetNear(BlockPos target, int rangeLimit) {
        // 在目标周围搜索可达的位置
        for (int range = 1; range <= rangeLimit; range++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        BlockPos pos = target.offset(dx, dy, dz);
                        if (isWalkable(pos)) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * 计算启发式（欧几里得距离）
     */
    private double calculateHeuristic(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * 计算移动代价
     */
    private double calculateMoveCost(NPCPathNode from, NPCPathNode to) {
        double baseCost = from.distanceTo(to);
        boolean diagonal = from.x != to.x && from.z != to.z;
        
        switch (to.type) {
            case STEP_UP:
                return baseCost * 0.95D;
            case JUMP:
                return baseCost * COST_JUMP;
            case FALL:
                return baseCost * COST_FALL;
            case CLIMB:
                return baseCost * COST_CLIMB;
            case DOOR:
                return baseCost * COST_DOOR;
            case WATER:
                return baseCost * COST_WATER;
            default:
                if (diagonal) {
                    return baseCost * (isNearObstacle(to.pos) ? COST_DIAGONAL_NEAR_OBSTACLE : COST_DIAGONAL_CLEAR);
                }
                return baseCost * COST_STRAIGHT;
        }
    }

    private boolean isNearObstacle(BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos checkPos = pos.offset(dx, 0, dz);
                BlockPos headPos = checkPos.above();
                if (!level.getBlockState(checkPos).getCollisionShape(level, checkPos).isEmpty()
                        || !level.getBlockState(headPos).getCollisionShape(level, headPos).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 重建路径
     */
    private NPCPath reconstructPath(NPCPathNode endNode, BlockPos start, BlockPos end) {
        List<NPCPathNode> path = new ArrayList<>();
        NPCPathNode current = endNode;
        
        while (current != null) {
            path.add(current);
            current = current.parent;
        }
        
        Collections.reverse(path);
        
        NPCPath npcPath = NPCPath.fromNodes(level, path, start, end);
        
        // 平滑路径
        npcPath.smooth();
        
        return npcPath;
    }
    
    private void logPathComposition(BlockPos start, BlockPos end, NPCPath path) {
        if (path == null || path.isFailed() || path.isEmpty()) {
            return;
        }

        int stepUpCount = 0;
        int jumpCount = 0;
        StringBuilder typesBuilder = new StringBuilder();
        for (NPCPathNode node : path.getNodes()) {
            if (node.type == NPCPathNode.NodeType.STEP_UP) {
                stepUpCount++;
            } else if (node.type == NPCPathNode.NodeType.JUMP) {
                jumpCount++;
            }
            if (typesBuilder.length() > 0) {
                typesBuilder.append(" -> ");
            }
            typesBuilder.append(node.type.name()).append("@").append(node.pos);
        }

        Simukraft.LOGGER.info("[NPCPathFinder] 路径完成 start={} end={} nodes={} stepUp={} jump={} path={}",
                start, end, path.getTotalNodes(), stepUpCount, jumpCount, typesBuilder);
    }

    /**
     * 创建失败的路径
     */
    private NPCPath createFailedPath(BlockPos start, BlockPos end) {
        NPCPath path = new NPCPath(level, start, end);
        path.markFailed();
        return path;
    }
    
    /**
     * 路径代价计算器（可扩展用于不同地形）
     */
    private static class PathCostCalculator {
    }
}
