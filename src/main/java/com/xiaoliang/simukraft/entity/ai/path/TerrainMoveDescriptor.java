package com.xiaoliang.simukraft.entity.ai.path;

/**
 * 单步移动的地形与动作描述：由地形检测模块生成，再交给代价引擎计算总代价
 */
public record TerrainMoveDescriptor(
        double geometricDistance,
        PathMovementType movementType,
        boolean carpetFenceHop,
        int maxPenaltyTriggers,
        double dangerCost,
        double nearbyBarrierCost,
        String terrainSummary
) {
    public static TerrainMoveDescriptor normal(double geometricDistance, PathMovementType movementType) {
        return new TerrainMoveDescriptor(geometricDistance, movementType, false, 0, 0.0D, 0.0D, "normal");
    }
}
