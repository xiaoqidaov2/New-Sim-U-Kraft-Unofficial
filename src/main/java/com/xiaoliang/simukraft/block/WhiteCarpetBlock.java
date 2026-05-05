package com.xiaoliang.simukraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Objects;
import javax.annotation.Nonnull;

public class WhiteCarpetBlock extends Block {
    protected static final VoxelShape SHAPE = Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 0.0625D, 1.0D);

    public WhiteCarpetBlock() {
        super(Objects.requireNonNull(BlockBehaviour.Properties.of())
                .mapColor(Objects.requireNonNull(MapColor.WOOL))
                .strength(0.1F)
                .sound(Objects.requireNonNull(SoundType.WOOL))
                .noOcclusion()
                .noCollission());
    }

    @Override
    public @Nonnull VoxelShape getCollisionShape(@Nonnull BlockState state, @Nonnull BlockGetter level, @Nonnull BlockPos pos, @Nonnull CollisionContext context) {
        return Objects.requireNonNull(SHAPE);
    }

    @Override
    public @Nonnull VoxelShape getShape(@Nonnull BlockState state, @Nonnull BlockGetter level, @Nonnull BlockPos pos, @Nonnull CollisionContext context) {
        return Objects.requireNonNull(SHAPE);
    }
}
