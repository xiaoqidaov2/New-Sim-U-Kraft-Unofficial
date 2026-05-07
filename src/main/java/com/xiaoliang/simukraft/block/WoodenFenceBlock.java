package com.xiaoliang.simukraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

@SuppressWarnings("null")
public class WoodenFenceBlock extends FenceBlock {
    private static final int FLAMMABILITY = 20;
    private static final int FIRE_SPREAD_SPEED = 5;

    public WoodenFenceBlock() {
        super(createProperties());
    }

    private static BlockBehaviour.Properties createProperties() {
        // 复制白桦木栅栏属性，直接复用原版的连接、碰撞箱与交互行为。
        return Objects.requireNonNull(BlockBehaviour.Properties.copy(Blocks.BIRCH_FENCE));
    }

    @Override
    public boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return true;
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return FLAMMABILITY;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return FIRE_SPREAD_SPEED;
    }

    @Override
    public boolean isFireSource(BlockState state, LevelReader level, BlockPos pos, Direction direction) {
        return false;
    }
}
