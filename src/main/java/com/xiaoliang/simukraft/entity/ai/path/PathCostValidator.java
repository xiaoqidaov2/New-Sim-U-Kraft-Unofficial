package com.xiaoliang.simukraft.entity.ai.path;

/**
 * 路径代价合法性校验：
 * 保证地毯-栅栏折扣只在合法跳跃场景下生效
 */
public final class PathCostValidator {
    private PathCostValidator() {
    }

    public static TerrainMoveDescriptor validate(TerrainMoveDescriptor descriptor) {
        if (descriptor == null) {
            return TerrainMoveDescriptor.normal(0.0D, PathMovementType.TRAVERSE);
        }

        boolean validCarpetFenceHop = descriptor.carpetFenceHop()
                && descriptor.movementType() == PathMovementType.JUMP_OVER
                && descriptor.maxPenaltyTriggers() == 0;

        if (validCarpetFenceHop == descriptor.carpetFenceHop()) {
            return descriptor;
        }

        int adjustedPenalty = Math.max(1, descriptor.maxPenaltyTriggers());
        String summary = descriptor.terrainSummary() == null || descriptor.terrainSummary().isBlank()
                ? "invalid_discount_fallback"
                : descriptor.terrainSummary() + "+invalid_discount_fallback";
        return new TerrainMoveDescriptor(
                descriptor.geometricDistance(),
                descriptor.movementType(),
                false,
                adjustedPenalty,
                descriptor.dangerCost(),
                descriptor.nearbyBarrierCost(),
                summary
        );
    }
}
