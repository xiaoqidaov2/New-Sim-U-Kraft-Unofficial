package com.xiaoliang.simukraft.entity.ai.path;

/**
 * Baritone 风格的代价计算核心：
 * 用统一公式把移动距离、基础地形成本、最大惩罚和动态危险成本合并
 */
public final class PathCostEngine {
    private PathCostEngine() {
    }

    public static PathCostBreakdown calculate(TerrainMoveDescriptor descriptor, PathCostRules rules) {
        double distanceCost = descriptor.geometricDistance() + getMovementSurcharge(descriptor.movementType(), rules);
        double terrainCost = descriptor.carpetFenceHop() ? rules.carpetFenceHopCost() : rules.normalTerrainCost();
        double maxPenaltyCost = Math.max(0, descriptor.maxPenaltyTriggers()) * rules.maxPenaltyCost();
        String terrainSummary = descriptor.terrainSummary() == null || descriptor.terrainSummary().isBlank()
                ? "normal"
                : descriptor.terrainSummary();

        return PathCostBreakdown.of(
                distanceCost,
                terrainCost,
                maxPenaltyCost,
                descriptor.dangerCost(),
                descriptor.nearbyBarrierCost(),
                terrainSummary
        );
    }

    private static double getMovementSurcharge(PathMovementType movementType, PathCostRules rules) {
        if (movementType == null) {
            return 0.0D;
        }
        return switch (movementType) {
            case ASCEND -> rules.ascendSurcharge();
            case JUMP_OVER -> rules.jumpOverSurcharge();
            case DESCEND -> rules.descendSurcharge();
            case FALL -> rules.fallSurcharge();
            case DOOR -> rules.doorSurcharge();
            case TRAVERSE -> 0.0D;
        };
    }
}
