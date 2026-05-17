package com.xiaoliang.simukraft.utils;

/**
 * NPC 下班归家调度规划器
 * 按距离、工作优先级和队列序号生成分批出发时间，避免同一 tick 集中触发大量寻路。
 */
public final class NPCRestDispatchPlanner {
    static final int DISPATCH_BATCH_INTERVAL_TICKS = 12;
    private static final int MAX_DISTANCE_PRIORITY_TICKS = 180;
    private static final int WORKING_PRIORITY_REDUCTION_TICKS = 48;
    private static final int EMPLOYED_PRIORITY_REDUCTION_TICKS = 20;

    private NPCRestDispatchPlanner() {
    }

    public static DispatchPlan createPlan(long windowStartTick,
                                          double travelDistance,
                                          boolean currentlyWorking,
                                          boolean hasWorkplace,
                                          int queueIndex) {
        int distanceReduction = Math.min(MAX_DISTANCE_PRIORITY_TICKS, (int) Math.round(Math.max(0.0D, travelDistance) * 3.0D));
        int priorityReduction = currentlyWorking
                ? WORKING_PRIORITY_REDUCTION_TICKS
                : (hasWorkplace ? EMPLOYED_PRIORITY_REDUCTION_TICKS : 0);
        int queueOffset = Math.max(0, queueIndex) * DISPATCH_BATCH_INTERVAL_TICKS;
        int initialDelayTicks = Math.max(0, MAX_DISTANCE_PRIORITY_TICKS - distanceReduction - priorityReduction) + queueOffset;
        return new DispatchPlan(windowStartTick + initialDelayTicks, initialDelayTicks, distanceReduction, priorityReduction);
    }

    public record DispatchPlan(long scheduledTick,
                               int initialDelayTicks,
                               int distanceReductionTicks,
                               int priorityReductionTicks) {
    }
}
