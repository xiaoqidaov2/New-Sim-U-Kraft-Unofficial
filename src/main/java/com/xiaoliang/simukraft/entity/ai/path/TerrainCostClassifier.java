package com.xiaoliang.simukraft.entity.ai.path;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.LinkedHashSet;
import java.util.Set;
/**
 * 地形代价分类器：负责识别地毯栅栏跳跃、直穿障碍、楼梯/方块惩罚等特殊地形
 */
@SuppressWarnings("null")
public class TerrainCostClassifier {
    private static final double COLLISION_EPSILON = 1.0E-5D;

    private final ServerLevel level;

    public TerrainCostClassifier(ServerLevel level) {
        this.level = level;
    }

    public TerrainMoveDescriptor classify(NPCPathNode from, NPCPathNode to, double dangerCost, double nearbyBarrierCost) {
        Set<String> reasons = new LinkedHashSet<>();
        int maxPenaltyTriggers = 0;
        boolean carpetFenceHop = false;

        if (from == null || to == null) {
            return new TerrainMoveDescriptor(0.0D, PathMovementType.TRAVERSE, false, 0, dangerCost, nearbyBarrierCost, "normal");
        }

        BlockPos jumpedObstaclePos = resolveJumpObstaclePos(from, to);
        if (to.action == NPCPathNode.MovementAction.JUMP_OVER && jumpedObstaclePos != null) {
            BlockState jumpedState = level.getBlockState(jumpedObstaclePos);
            if (isFenceLikeBarrier(jumpedState)) {
                BlockPos coverPos = jumpedObstaclePos.above();
                if (isThinWalkableCover(level.getBlockState(coverPos), coverPos)) {
                    carpetFenceHop = true;
                    reasons.add("carpet_fence_hop");
                } else {
                    maxPenaltyTriggers++;
                    reasons.add("max:fence_without_carpet");
                }
            } else if (isVanillaAutoStepBlock(jumpedState)) {
                maxPenaltyTriggers++;
                reasons.add("max:jump_over_stair");
            } else if (isDirectBlockObstacle(jumpedState, jumpedObstaclePos)) {
                maxPenaltyTriggers++;
                reasons.add("max:jump_over_block");
            }
        }

        BlockPos traversedPos = resolveTraversedObstaclePos(from, to);
        if (traversedPos != null) {
            BlockPos fromSupport = getSupportBlockPos(from);
            BlockPos toSupport = getSupportBlockPos(to);
            if (!traversedPos.equals(fromSupport) && !traversedPos.equals(toSupport)) {
                BlockState traversedState = level.getBlockState(traversedPos);
                if (isFenceLikeBarrier(traversedState)) {
                    maxPenaltyTriggers++;
                    reasons.add("max:through_fence");
                } else if (isVanillaAutoStepBlock(traversedState)) {
                    maxPenaltyTriggers++;
                    reasons.add("max:through_stair");
                } else if (isDirectBlockObstacle(traversedState, traversedPos)) {
                    maxPenaltyTriggers++;
                    reasons.add("max:through_block");
                }
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("normal");
        }

        return new TerrainMoveDescriptor(
                from.distanceTo(to),
                PathMovementType.fromNodeAction(to.action),
                carpetFenceHop,
                maxPenaltyTriggers,
                dangerCost,
                nearbyBarrierCost,
                String.join("+", reasons)
        );
    }

    private BlockPos resolveJumpObstaclePos(NPCPathNode from, NPCPathNode to) {
        int stepX = Integer.compare(to.x, from.x);
        int stepZ = Integer.compare(to.z, from.z);
        if (stepX == 0 && stepZ == 0) {
            return null;
        }
        return from.pos.offset(stepX, 0, stepZ);
    }

    private BlockPos resolveTraversedObstaclePos(NPCPathNode from, NPCPathNode to) {
        double sampleX = (from.standX + to.standX) * 0.5D;
        double sampleY = Math.min(from.standY, to.standY) + 0.45D;
        double sampleZ = (from.standZ + to.standZ) * 0.5D;
        BlockPos samplePos = BlockPos.containing(sampleX, sampleY, sampleZ);
        return from.pos.equals(samplePos) && to.pos.equals(samplePos) ? null : samplePos;
    }

    private BlockPos getSupportBlockPos(NPCPathNode node) {
        return BlockPos.containing(node.standX, node.standY - COLLISION_EPSILON, node.standZ);
    }

    private boolean isThinWalkableCover(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof CarpetBlock) {
            return true;
        }
        return getCollisionHeight(state, pos) <= 0.125D + COLLISION_EPSILON;
    }

    private boolean isFenceLikeBarrier(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FenceBlock || block instanceof WallBlock || block instanceof IronBarsBlock;
    }

    private boolean isVanillaAutoStepBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof SlabBlock || block instanceof StairBlock;
    }

    private boolean isDirectBlockObstacle(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        if (isFenceLikeBarrier(state) || isVanillaAutoStepBlock(state)) {
            return false;
        }
        return getCollisionHeight(state, pos) > 0.0D;
    }

    private double getCollisionHeight(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return 0.0D;
        }
        double height = 0.0D;
        for (AABB box : shape.toAabbs()) {
            height = Math.max(height, box.maxY);
        }
        return height;
    }
}
