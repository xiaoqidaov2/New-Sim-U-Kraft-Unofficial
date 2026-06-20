package com.xiaoliang.simukraft.entity.ai.path;

import net.minecraft.core.BlockPos;

/**
 * 路径节点（menglannnn: A*寻路的基本单元）
 */
public class NPCPathNode implements Comparable<NPCPathNode> {
    public final BlockPos pos;
    public final int x, y, z;
    public double standX;
    public double standY;
    public double standZ;
    public long key; // 使用 long 替代 String 作为键值，避免字符串拼接开销
    public double stepCost;
    public double terrainCost;
    public String costReason;
    
    // A*算法所需的数据
    public double gCost; // 从起点到当前节点的实际代价
    public double hCost; // 从当前节点到终点的估计代价（启发式）
    public double fCost; // 总代价 = gCost + hCost
    
    public NPCPathNode parent; // 父节点，用于回溯路径
    
    // 节点类型
    public NodeType type;
    public MovementAction action;
    
    public enum NodeType {
        WALKABLE,      // 可行走
        STEP_UP,       // 一格抬脚翻越
        JUMP,          // 需要跳跃
        FALL,          // 需要下落
        DOOR,          // 门
        WATER,         // 水中
        AIR            // 空中（需要特殊处理）
    }

    public enum MovementAction {
        TRAVERSE,
        ASCEND,
        JUMP_OVER,
        DESCEND,
        FALL,
        DOOR
    }
    
    // 坐标偏移常量，用于将坐标编码为 long
    private static final int X_OFFSET = 1000000;
    private static final int Y_OFFSET = 1000000;
    private static final int Z_OFFSET = 1000000;
    private static final long SCALE_FACTOR = 16L;
    
    public NPCPathNode(BlockPos pos) {
        this.pos = pos;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.standX = pos.getX() + 0.5D;
        this.standY = pos.getY();
        this.standZ = pos.getZ() + 0.5D;
        this.key = createKey(this.standX, this.standY, this.standZ);
        this.gCost = Double.MAX_VALUE;
        this.hCost = 0;
        this.fCost = Double.MAX_VALUE;
        this.parent = null;
        this.type = NodeType.WALKABLE;
        this.action = MovementAction.TRAVERSE;
        this.stepCost = 0.0D;
        this.terrainCost = 0.0D;
        this.costReason = "start";
    }
    
    public NPCPathNode(int x, int y, int z) {
        this.pos = new BlockPos(x, y, z);
        this.x = x;
        this.y = y;
        this.z = z;
        this.standX = x + 0.5D;
        this.standY = y;
        this.standZ = z + 0.5D;
        this.key = createKey(this.standX, this.standY, this.standZ);
        this.gCost = Double.MAX_VALUE;
        this.hCost = 0;
        this.fCost = Double.MAX_VALUE;
        this.parent = null;
        this.type = NodeType.WALKABLE;
        this.action = MovementAction.TRAVERSE;
        this.stepCost = 0.0D;
        this.terrainCost = 0.0D;
        this.costReason = "start";
    }
    
    public void setStandPosition(double standX, double standY, double standZ) {
        this.standX = standX;
        this.standY = standY;
        this.standZ = standZ;
        this.key = createKey(standX, standY, standZ);
    }

    /**
     * 将三维坐标编码为单个 long 值，避免字符串拼接的性能开销
     * 坐标范围：X/Z: [-62500, 62500], Y: [0, 125000] (Minecraft 世界范围内安全)
     */
    public static long createKey(double x, double y, double z) {
        long ix = Math.round(x * SCALE_FACTOR) + X_OFFSET;
        long iy = Math.round(y * SCALE_FACTOR) + Y_OFFSET;
        long iz = Math.round(z * SCALE_FACTOR) + Z_OFFSET;
        // 使用位运算组合三个坐标：ix(20bits) | iy(21bits) << 20 | iz(20bits) << 41
        return (ix & 0xFFFFFL) | ((iy & 0x1FFFFFL) << 20) | ((iz & 0xFFFFFL) << 41);
    }

    @Override
    public int compareTo(NPCPathNode other) {
        return Double.compare(this.fCost, other.fCost);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof NPCPathNode)) return false;
        NPCPathNode other = (NPCPathNode) obj;
        return this.key == other.key;
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(key);
    }
    
    @Override
    public String toString() {
        return String.format("Node[%d,%d,%d] stand=%.3f,%.3f,%.3f g=%.2f h=%.2f f=%.2f step=%.2f terrain=%.2f %s/%s %s",
            x, y, z, standX, standY, standZ, gCost, hCost, fCost, stepCost, terrainCost, type, action, costReason);
    }
    
    /**
     * 计算到另一个节点的欧几里得距离
     */
    public double distanceTo(NPCPathNode other) {
        double dx = this.standX - other.standX;
        double dy = this.standY - other.standY;
        double dz = this.standZ - other.standZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * 计算到目标位置的曼哈顿距离（用于启发式）
     */
    public double manhattanDistanceTo(BlockPos target) {
        return Math.abs(this.x - target.getX()) + 
               Math.abs(this.y - target.getY()) + 
               Math.abs(this.z - target.getZ());
    }
    
    /**
     * 计算到目标位置的欧几里得距离（用于启发式）
     */
    public double distanceTo(BlockPos target) {
        double dx = this.standX - (target.getX() + 0.5D);
        double dy = this.standY - target.getY();
        double dz = this.standZ - (target.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
