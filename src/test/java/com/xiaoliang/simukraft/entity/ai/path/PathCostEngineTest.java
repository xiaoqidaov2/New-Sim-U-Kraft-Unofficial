package com.xiaoliang.simukraft.entity.ai.path;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCostEngineTest {
    private static final PathCostRules RULES = PathCostRules.defaults();

    @Test
    void avoidsMaxPenaltyRouteWhenCheaperDetourExists() {
        GridNode start = new GridNode(0, 0);
        GridNode goal = new GridNode(4, 0);
        Map<GridNode, CellRule> rules = new HashMap<>();
        rules.put(new GridNode(1, 0), CellRule.MAX_PENALTY_BLOCK);
        rules.put(new GridNode(2, 0), CellRule.MAX_PENALTY_BLOCK);

        GridPathResult result = solve(start, goal, 5, 3, rules);

        assertTrue(result.totalCost < 50.0D, "should prefer cheaper detour over +100 penalty route");
        assertTrue(result.path.contains(new GridNode(0, 1)), "detour path should move around obstacle row");
    }

    @Test
    void carpetFenceHopUsesLowCostInsteadOfMaxPenalty() {
        TerrainMoveDescriptor descriptor = new TerrainMoveDescriptor(
                1.0D,
                PathMovementType.JUMP_OVER,
                true,
                0,
                0.0D,
                0.0D,
                "carpet_fence_hop"
        );

        PathCostBreakdown breakdown = PathCostEngine.calculate(descriptor, RULES);

        assertEquals(1.0D, breakdown.terrainCost(), 0.0001D);
        assertEquals(0.0D, breakdown.maxPenaltyCost(), 0.0001D);
    }

    @Test
    void consecutiveSpecialTerrainPenaltiesAccumulate() {
        TerrainMoveDescriptor descriptor = new TerrainMoveDescriptor(
                1.0D,
                PathMovementType.TRAVERSE,
                false,
                3,
                0.0D,
                0.0D,
                "through_block+through_stair+through_fence"
        );

        PathCostBreakdown breakdown = PathCostEngine.calculate(descriptor, RULES);

        assertEquals(300.0D, breakdown.maxPenaltyCost(), 0.0001D);
        assertTrue(breakdown.totalCost() > 300.0D);
    }

    @Test
    void fiftyByFiftyBenchmarkStaysUnderTwoHundredMilliseconds() {
        Map<GridNode, CellRule> rules = new HashMap<>();
        for (int x = 5; x < 45; x++) {
            rules.put(new GridNode(x, 24), CellRule.MAX_PENALTY_BLOCK);
        }
        rules.put(new GridNode(25, 24), CellRule.CARPET_FENCE_HOP);

        long startNs = System.nanoTime();
        GridPathResult result = solve(new GridNode(0, 0), new GridNode(49, 49), 50, 50, rules);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertTrue(result.totalCost > 0.0D, "path should be found on benchmark map");
        assertTrue(elapsedMs < 200L, "50x50 benchmark should stay under 200ms, actual=" + elapsedMs + "ms");
    }

    private static GridPathResult solve(GridNode start, GridNode goal, int width, int height, Map<GridNode, CellRule> rules) {
        PriorityQueue<GridState> open = new PriorityQueue<>(Comparator.comparingDouble(state -> state.fCost));
        Map<GridNode, GridState> best = new HashMap<>();
        GridState startState = new GridState(start, null, 0.0D, heuristic(start, goal));
        open.add(startState);
        best.put(start, startState);

        while (!open.isEmpty()) {
            GridState current = open.poll();
            if (current.node.equals(goal)) {
                return new GridPathResult(rebuildPath(current), current.gCost);
            }

            for (int[] dir : DIRECTIONS) {
                int nx = current.node.x + dir[0];
                int ny = current.node.y + dir[1];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }

                GridNode nextNode = new GridNode(nx, ny);
                TerrainMoveDescriptor descriptor = describe(nextNode, rules.getOrDefault(nextNode, CellRule.NORMAL));
                double stepCost = PathCostEngine.calculate(descriptor, RULES).totalCost();
                double tentative = current.gCost + stepCost;
                GridState existing = best.get(nextNode);
                if (existing == null || tentative < existing.gCost) {
                    GridState next = new GridState(nextNode, current, tentative, tentative + heuristic(nextNode, goal));
                    best.put(nextNode, next);
                    open.add(next);
                }
            }
        }

        return new GridPathResult(List.of(), Double.MAX_VALUE);
    }

    private static TerrainMoveDescriptor describe(GridNode node, CellRule rule) {
        return switch (rule) {
            case NORMAL -> TerrainMoveDescriptor.normal(1.0D, PathMovementType.TRAVERSE);
            case MAX_PENALTY_BLOCK -> new TerrainMoveDescriptor(1.0D, PathMovementType.TRAVERSE, false, 1, 0.0D, 0.0D, "max:through_block");
            case CARPET_FENCE_HOP -> new TerrainMoveDescriptor(1.0D, PathMovementType.JUMP_OVER, true, 0, 0.0D, 0.0D, "carpet_fence_hop");
        };
    }

    private static double heuristic(GridNode from, GridNode to) {
        return Math.abs(from.x - to.x) + Math.abs(from.y - to.y);
    }

    private static List<GridNode> rebuildPath(GridState state) {
        List<GridNode> path = new ArrayList<>();
        GridState current = state;
        while (current != null) {
            path.add(0, current.node);
            current = current.parent;
        }
        return path;
    }

    private static final int[][] DIRECTIONS = new int[][]{
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private enum CellRule {
        NORMAL,
        MAX_PENALTY_BLOCK,
        CARPET_FENCE_HOP
    }

    private record GridNode(int x, int y) {
    }

    private record GridPathResult(List<GridNode> path, double totalCost) {
    }

    private static final class GridState {
        private final GridNode node;
        private final GridState parent;
        private final double gCost;
        private final double fCost;

        private GridState(GridNode node, GridState parent, double gCost, double fCost) {
            this.node = node;
            this.parent = parent;
            this.gCost = gCost;
            this.fCost = fCost;
        }
    }
}
