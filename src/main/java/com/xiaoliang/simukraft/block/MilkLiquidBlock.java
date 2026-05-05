package com.xiaoliang.simukraft.block;

import com.xiaoliang.simukraft.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import java.util.Objects;
import javax.annotation.Nonnull;

@SuppressWarnings("null")
public class MilkLiquidBlock extends LiquidBlock {
    public MilkLiquidBlock(java.util.function.Supplier<? extends FlowingFluid> fluid, @Nonnull Properties properties) {
        super(Objects.requireNonNull(fluid), Objects.requireNonNull(properties));
    }
<<<<<<< HEAD
    
=======

>>>>>>> c959a63 (农田盒工业职业不解雇问题)
    @Override
    public void onPlace(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState oldState, boolean isMoving) {
        super.onPlace(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(oldState),
                isMoving
        );
        if (!level.isClientSide()) {
            level.scheduleTick(Objects.requireNonNull(pos), this, 7200);
        }
    }

    @Override
    public void randomTick(@Nonnull BlockState state, @Nonnull ServerLevel level, @Nonnull BlockPos pos, @Nonnull RandomSource random) {
        super.randomTick(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(random)
        );
    }

    @SuppressWarnings("deprecation")
    @Override
    public void tick(@Nonnull BlockState state, @Nonnull ServerLevel level, @Nonnull BlockPos pos, @Nonnull RandomSource random) {
        super.tick(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(random)
        );
        if (!level.isClientSide() && state.getBlock() == this) {
            level.setBlockAndUpdate(
                    Objects.requireNonNull(pos),
                    Objects.requireNonNull(ModBlocks.CHEESE_BLOCK.get().defaultBlockState())
            );
        }
    }
}
