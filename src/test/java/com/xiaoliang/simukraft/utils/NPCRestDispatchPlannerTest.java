package com.xiaoliang.simukraft.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NPCRestDispatchPlannerTest {

    @Test
    void longDistanceWorkingNpcLeavesEarlierThanNearIdleNpc() {
        var longDistanceWorking = NPCRestDispatchPlanner.createPlan(12_000L, 64.0D, true, true, 0);
        var nearIdle = NPCRestDispatchPlanner.createPlan(12_000L, 6.0D, false, false, 0);

        assertTrue(
                longDistanceWorking.scheduledTick() < nearIdle.scheduledTick(),
                "long-distance working NPCs should be dispatched earlier than nearby idle NPCs"
        );
    }

    @Test
    void queueIndexAddsBatchSpacingToAvoidSameTickBurst() {
        var first = NPCRestDispatchPlanner.createPlan(12_000L, 24.0D, true, true, 0);
        var second = NPCRestDispatchPlanner.createPlan(12_000L, 24.0D, true, true, 1);
        var third = NPCRestDispatchPlanner.createPlan(12_000L, 24.0D, true, true, 2);

        assertTrue(
                second.scheduledTick() - first.scheduledTick() >= NPCRestDispatchPlanner.DISPATCH_BATCH_INTERVAL_TICKS,
                "second batch should be delayed by at least one dispatch interval"
        );
        assertTrue(
                third.scheduledTick() - second.scheduledTick() >= NPCRestDispatchPlanner.DISPATCH_BATCH_INTERVAL_TICKS,
                "third batch should also be delayed by at least one dispatch interval"
        );
    }

    @Test
    void largePopulationGetsSpreadAcrossMultipleTicks() {
        long previousTick = Long.MIN_VALUE;
        for (int i = 0; i < 120; i++) {
            var plan = NPCRestDispatchPlanner.createPlan(12_000L, 18.0D, true, true, i);
            assertTrue(
                    plan.scheduledTick() > previousTick,
                    "dispatch schedule should keep increasing for large NPC groups"
            );
            previousTick = plan.scheduledTick();
        }
    }
}
