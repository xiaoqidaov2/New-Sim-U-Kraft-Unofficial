package com.xiaoliang.simukraft.entity.ai.path;

import java.util.Locale;

/**
 * 单步移动代价拆解：用于调试显示与客户端可视化
 */
public record PathCostBreakdown(
        double totalCost,
        double distanceCost,
        double terrainCost,
        double maxPenaltyCost,
        double dangerCost,
        double nearbyBarrierCost,
        String summary
) {
    public static PathCostBreakdown of(
            double distanceCost,
            double terrainCost,
            double maxPenaltyCost,
            double dangerCost,
            double nearbyBarrierCost,
            String terrainSummary
    ) {
        double totalCost = distanceCost + terrainCost + maxPenaltyCost + dangerCost + nearbyBarrierCost;
        String summary = String.format(
                Locale.ROOT,
                "%s | total=%.2f dist=%.2f terrain=%.2f max=%.2f danger=%.2f nearby=%.2f",
                terrainSummary,
                totalCost,
                distanceCost,
                terrainCost,
                maxPenaltyCost,
                dangerCost,
                nearbyBarrierCost
        );
        return new PathCostBreakdown(totalCost, distanceCost, terrainCost, maxPenaltyCost, dangerCost, nearbyBarrierCost, summary);
    }
}
