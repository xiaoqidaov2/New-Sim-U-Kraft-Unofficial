package com.xiaoliang.simukraft.entity.ai.path;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NPC路径（menglannnn: 包含一系列路径节点）
 */
@SuppressWarnings("null")
public class NPCPath {
    private final List<NPCPathNode> nodes;
    private int currentNodeIndex;
    private final BlockPos startPos;
    private final BlockPos endPos;
    private final ServerLevel level;
    private boolean completed;
    private boolean failed;
    
    // 路径属性
    private double totalLength;
    private long creationTime;
    
    public NPCPath(ServerLevel level, BlockPos startPos, BlockPos endPos) {
        this.nodes = new ArrayList<>();
        this.currentNodeIndex = 0;
        this.startPos = startPos;
        this.endPos = endPos;
        this.level = level;
        this.completed = false;
        this.failed = false;
        this.totalLength = 0;
        this.creationTime = System.currentTimeMillis();
    }
    
    /**
     * 从节点列表创建路径
     */
    public static NPCPath fromNodes(ServerLevel level, List<NPCPathNode> nodeList, BlockPos start, BlockPos end) {
        NPCPath path = new NPCPath(level, start, end);
        path.nodes.addAll(nodeList);
        path.calculateTotalLength();
        return path;
    }
    
    /**
     * 添加节点到路径
     */
    public void addNode(NPCPathNode node) {
        nodes.add(node);
    }
    
    /**
     * 获取当前节点
     */
    public NPCPathNode getCurrentNode() {
        if (currentNodeIndex >= 0 && currentNodeIndex < nodes.size()) {
            return nodes.get(currentNodeIndex);
        }
        return null;
    }
    
    /**
     * 获取下一个节点
     */
    public NPCPathNode getNextNode() {
        if (currentNodeIndex + 1 < nodes.size()) {
            return nodes.get(currentNodeIndex + 1);
        }
        return null;
    }
    
    /**
     * 前进到下一个节点
     */
    public boolean advance() {
        if (currentNodeIndex < nodes.size() - 1) {
            currentNodeIndex++;
            return true;
        }
        completed = true;
        return false;
    }
    
    /**
     * 回退到上一个节点
     */
    public boolean retreat() {
        if (currentNodeIndex > 0) {
            currentNodeIndex--;
            return true;
        }
        return false;
    }
    
    /**
     * 获取路径中剩余的节点数
     */
    public int getRemainingNodes() {
        return nodes.size() - currentNodeIndex;
    }
    
    /**
     * 获取路径总节点数
     */
    public int getTotalNodes() {
        return nodes.size();
    }
    
    /**
     * 获取当前节点索引
     */
    public int getCurrentIndex() {
        return currentNodeIndex;
    }
    
    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return completed;
    }
    
    /**
     * 是否失败
     */
    public boolean isFailed() {
        return failed;
    }
    
    /**
     * 标记为失败
     */
    public void markFailed() {
        this.failed = true;
    }
    
    /**
     * 标记为完成
     */
    public void markCompleted() {
        this.completed = true;
    }
    
    /**
     * 获取起点
     */
    public BlockPos getStartPos() {
        return startPos;
    }
    
    /**
     * 获取终点
     */
    public BlockPos getEndPos() {
        return endPos;
    }
    
    /**
     * 获取所有节点
     */
    public List<NPCPathNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }
    
    /**
     * 获取剩余路径长度（估计）
     */
    public double getRemainingLength() {
        double length = 0;
        for (int i = currentNodeIndex; i < nodes.size() - 1; i++) {
            length += nodes.get(i).distanceTo(nodes.get(i + 1));
        }
        return length;
    }
    
    /**
     * 获取总长度
     */
    public double getTotalLength() {
        return totalLength;
    }
    
    /**
     * 计算总长度
     */
    private void calculateTotalLength() {
        totalLength = 0;
        for (int i = 0; i < nodes.size() - 1; i++) {
            totalLength += nodes.get(i).distanceTo(nodes.get(i + 1));
        }
    }
    
    /**
     * 获取当前目标位置（用于移动）
     */
    public Vec3 getCurrentTarget() {
        NPCPathNode node = getCurrentNode();
        if (node != null) {
            return new Vec3(node.standX, node.standY, node.standZ);
        }
        return null;
    }
    
    /**
     * 获取下一个目标位置
     */
    public Vec3 getNextTarget() {
        NPCPathNode node = getNextNode();
        if (node != null) {
            return new Vec3(node.standX, node.standY, node.standZ);
        }
        return null;
    }
    
    /**
     * 检查路径是否过期（超过一定时间）
     */
    public boolean isExpired(long maxAgeMs) {
        return System.currentTimeMillis() - creationTime > maxAgeMs;
    }
    
    /**
     * 路径是否为空
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }
    
    /**
     * 获取路径的字符串表示（用于调试）
     */
    @Override
    public String toString() {
        return String.format("Path[nodes=%d, current=%d, completed=%s, failed=%s, length=%.2f]",
            nodes.size(), currentNodeIndex, completed, failed, totalLength);
    }
    
    /**
     * 平滑路径（移除不必要的节点）
     */
    public void smooth() {
        if (nodes.size() <= 2) return;
        if (containsSpecialTraversalNode()) return;
        
        List<NPCPathNode> smoothed = new ArrayList<>();
        smoothed.add(nodes.get(0));
        
        int i = 0;
        while (i < nodes.size() - 1) {
            NPCPathNode current = nodes.get(i);
            
            // 尝试找到最远的可以直接到达的节点
            int farthest = i + 1;
            for (int j = i + 2; j < nodes.size(); j++) {
                if (isDiagonalSegment(current, nodes.get(j)) && isNearObstacleBetween(current, nodes.get(j))) {
                    break;
                }
                if (canWalkDirectly(current, nodes.get(j))) {
                    farthest = j;
                } else {
                    break;
                }
            }
            
            smoothed.add(nodes.get(farthest));
            i = farthest;
        }
        
        nodes.clear();
        nodes.addAll(smoothed);
        calculateTotalLength();
    }
    
    private boolean containsSpecialTraversalNode() {
        for (NPCPathNode node : nodes) {
            if (node.action != NPCPathNode.MovementAction.TRAVERSE || isHeightSensitiveNode(node)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHeightSensitiveNode(NPCPathNode node) {
        if (Math.abs(node.standY - node.y) > 1.0E-4D) {
            return true;
        }
        return isVanillaStepBlock(level.getBlockState(node.pos), node.pos)
                || isVanillaStepBlock(level.getBlockState(node.pos.below()), node.pos.below());
    }

    /**
     * 检查是否可以直接从起点走到终点（简单的直线检查）
     */
    private boolean canWalkDirectly(NPCPathNode start, NPCPathNode end) {
        if (level == null) return false;
        if (Math.abs(start.y - end.y) > 1) return false;

        if (Math.abs(start.standY - end.standY) > 1.0E-4D) return false;
        if (isHeightSensitiveNode(start) || isHeightSensitiveNode(end)) return false;

        double distance = start.distanceTo(end);
        if (distance > 5.0) return false;

        Vec3 startVec = new Vec3(start.x + 0.5D, start.y, start.z + 0.5D);
        Vec3 endVec = new Vec3(end.x + 0.5D, end.y, end.z + 0.5D);
        int samples = Math.max(2, (int) Math.ceil(distance * 4.0D));

        for (int i = 1; i < samples; i++) {
            double t = (double) i / (double) samples;
            double sampleX = startVec.x + (endVec.x - startVec.x) * t;
            double sampleY = startVec.y + (endVec.y - startVec.y) * t;
            double sampleZ = startVec.z + (endVec.z - startVec.z) * t;

            BlockPos footPos = BlockPos.containing(sampleX, sampleY, sampleZ);
            double standY = getStandY(footPos, sampleX, sampleZ);

            if (!hasHeadroomAt(footPos, standY)) {
                return false;
            }
            if (Math.abs(standY - sampleY) > 0.75D) {
                return false;
            }
        }

        return true;
    }

    private boolean isPassableForPath(BlockState state, BlockPos pos) {
        return state.getCollisionShape(level, pos).isEmpty() || isVanillaStepBlock(state, pos);
    }

    private boolean isVanillaStepBlock(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof SlabBlock || state.getBlock() instanceof StairBlock) {
            return true;
        }
        double height = getCollisionHeight(state, pos);
        return height > 0.0D && height <= 12.0D / 16.0D;
    }

    private double getStandY(BlockPos pos, double worldX, double worldZ) {
        double top = getHighestCollisionTopAt(pos, worldX, worldZ);
        if (top > pos.getY() + 1.0E-5D) {
            return top;
        }
        double belowTop = getHighestCollisionTopAt(pos.below(), worldX, worldZ);
        if (belowTop > pos.getY() - 1.0D + 1.0E-5D) {
            return belowTop;
        }
        return pos.getY();
    }

    private double getHighestCollisionTopAt(BlockPos blockPos, double worldX, double worldZ) {
        BlockState state = level.getBlockState(blockPos);
        if (state.getCollisionShape(level, blockPos).isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        double localX = worldX - blockPos.getX();
        double localZ = worldZ - blockPos.getZ();
        double highest = Double.NEGATIVE_INFINITY;
        for (AABB box : state.getCollisionShape(level, blockPos).toAabbs()) {
            if (localX >= box.minX - 0.31D && localX <= box.maxX + 0.31D
                    && localZ >= box.minZ - 0.31D && localZ <= box.maxZ + 0.31D) {
                highest = Math.max(highest, blockPos.getY() + box.maxY);
            }
        }
        return highest;
    }

    private boolean hasHeadroomAt(BlockPos pos, double standY) {
        int minY = (int) Math.floor(standY + 1.0E-5D);
        int maxY = (int) Math.floor(standY + 1.8D - 1.0E-5D);
        for (int y = minY; y <= maxY; y++) {
            BlockPos checkPos = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState state = level.getBlockState(checkPos);
            if (state.getCollisionShape(level, checkPos).isEmpty()) {
                continue;
            }
            double localMinY = standY - checkPos.getY() + 1.0E-5D;
            for (AABB box : state.getCollisionShape(level, checkPos).toAabbs()) {
                if (box.maxY > localMinY) {
                    return false;
                }
            }
        }
        return true;
    }

    private double getCollisionHeight(BlockState state, BlockPos pos) {
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return 0.0D;
        }
        double height = 0.0D;
        for (AABB box : state.getCollisionShape(level, pos).toAabbs()) {
            height = Math.max(height, box.maxY);
        }
        return height;
    }

    private boolean isDiagonalSegment(NPCPathNode start, NPCPathNode end) {
        return start.x != end.x && start.z != end.z;
    }

    private boolean isNearObstacleBetween(NPCPathNode start, NPCPathNode end) {
        if (level == null) {
            return false;
        }

        Vec3 startVec = new Vec3(start.x + 0.5D, start.y, start.z + 0.5D);
        Vec3 endVec = new Vec3(end.x + 0.5D, end.y, end.z + 0.5D);
        double distance = start.distanceTo(end);
        int samples = Math.max(2, (int) Math.ceil(distance * 4.0D));

        for (int i = 1; i < samples; i++) {
            double t = (double) i / (double) samples;
            double sampleX = startVec.x + (endVec.x - startVec.x) * t;
            double sampleY = startVec.y + (endVec.y - startVec.y) * t;
            double sampleZ = startVec.z + (endVec.z - startVec.z) * t;
            BlockPos centerPos = BlockPos.containing(sampleX, sampleY, sampleZ);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos checkPos = centerPos.offset(dx, 0, dz);
                    BlockPos headPos = checkPos.above();
                    BlockState checkState = level.getBlockState(checkPos);
                    if (!isPassableForPath(checkState, checkPos)
                            || !level.getBlockState(headPos).getCollisionShape(level, headPos).isEmpty()) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
