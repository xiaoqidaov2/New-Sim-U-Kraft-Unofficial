package com.xiaoliang.simukraft.block;

import com.xiaoliang.simukraft.client.gui.OtherControlBoxScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Objects;

@SuppressWarnings("null")
public class OtherControlBoxBlock extends Block {
    public OtherControlBoxBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(Objects.requireNonNull(MapColor.METAL))
                .strength(0.8F)
                .sound(Objects.requireNonNull(SoundType.METAL)));
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            // 客户端打开GUI
            Minecraft.getInstance().setScreen(new OtherControlBoxScreen(pos));
        }
        return InteractionResult.SUCCESS;
    }
}
