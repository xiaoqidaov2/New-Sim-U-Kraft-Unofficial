package com.xiaoliang.simukraft.entity.ai.path;

/**
 * 代价规则：支持从配置文件动态加载
 */
public record PathCostRules(
        double normalTerrainCost,
        double maxPenaltyCost,
        double carpetFenceHopCost,
        double ascendSurcharge,
        double jumpOverSurcharge,
        double descendSurcharge,
        double fallSurcharge,
        double doorSurcharge
) {
    public static PathCostRules defaults() {
        return new PathCostRules(
                1.0D,
                100.0D,
                1.0D,
                1.2D,
                1.4D,
                0.35D,
                0.5D,
                0.15D
        );
    }
}
