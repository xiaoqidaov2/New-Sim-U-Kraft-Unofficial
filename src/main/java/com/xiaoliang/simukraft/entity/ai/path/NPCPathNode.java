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
    public String key;
    
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
        CLIMB,         // 需要攀爬（梯子/藤蔓）
        DOOR,          // 门
        WATER,         // 水中
        AIR            // 空中（需要特殊处理）
    }

    public enum MovementAction {
        TRAVERSE,
        ASCEND,
        DESCEND,
        FALL,
        CLIMB,
        DOOR
    }
    
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
    }
    
    public void setStandPosition(double standX, double standY, double standZ) {
        this.standX = standX;
        this.standY = standY;
        this.standZ = standZ;
        this.key = createKey(standX, standY, standZ);
    }

    public static String createKey(double x, double y, double z) {
        return Math.round(x * 16.0D) + ":" + Math.round(y * 16.0D) + ":" + Math.round(z * 16.0D);
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
        return this.key.equals(other.key);
    }
    
    @Override
    public int hashCode() {
        return key.hashCode();
    }
    
    @Override
    public String toString() {
        return String.format("Node[%d,%d,%d] stand=%.3f,%.3f,%.3f g=%.2f h=%.2f f=%.2f %s/%s", 
            x, y, z, standX, standY, standZ, gCost, hCost, fCost, type, action);
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
