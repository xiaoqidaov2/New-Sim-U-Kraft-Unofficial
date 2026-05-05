package com.xiaoliang.simukraft.block;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.utils.ClientRuntimeBridge;
import com.xiaoliang.simukraft.utils.FarmlandManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
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

import java.util.Objects;
import javax.annotation.Nonnull;

public class NSUKFarmlandBoxBlock extends Block {
    public NSUKFarmlandBoxBlock() {
        super(Objects.requireNonNull(BlockBehaviour.Properties.of())
                .mapColor(Objects.requireNonNull(MapColor.WOOD))
                .strength(0.8F)
                .sound(Objects.requireNonNull(SoundType.WOOD)));
    }

    // 音效 - 放置时播放农田盒专用音效
    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState oldState, boolean isMoving) {
        if (!oldState.is(Objects.requireNonNull(state.getBlock()))) {
            SoundEvent sound = Objects.requireNonNull(ModSoundEvents.FARMLAND_BOX_PLACE.get());
            level.playSound(null, Objects.requireNonNull(pos), sound, SoundSource.BLOCKS, 1.0F, 1.0F);
            
            // 使用统一的农场管理器处理放置逻辑
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                FarmlandManager.onBoxPlaced(serverLevel, pos);
            }
        }
        super.onPlace(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(oldState),
                isMoving
        );
    }

    // 音效 - 破坏时播放与建筑盒相同的音效
    @Override
    public void playerWillDestroy(@Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState state, @Nonnull Player player) {
        SoundEvent sound = Objects.requireNonNull(ModSoundEvents.BUILD_BOX_BREAK.get());
        level.playSound(player, Objects.requireNonNull(pos), sound, SoundSource.BLOCKS, 1.0F, 1.0F);
        super.playerWillDestroy(level, pos, state, player);
    }

    // 添加点击交互功能
    @Override
    public @Nonnull InteractionResult use(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull Player player, @Nonnull InteractionHand hand, @Nonnull BlockHitResult hit) {
        if (level.isClientSide) {
            try {
                ClientRuntimeBridge.openScreen(
                        "com.xiaoliang.simukraft.client.gui.FarmlandBoxScreen",
                        new Class<?>[]{BlockPos.class},
                        pos
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return Objects.requireNonNull(InteractionResult.sidedSuccess(level.isClientSide));
    }

    // 新增：当农田盒被破坏时自动解雇NPC并重置数据
    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState newState, boolean isMoving) {
        if (state.is(Objects.requireNonNull(newState.getBlock()))) {
            super.onRemove(state, level, pos, newState, isMoving);
            return;
        }

        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            FarmlandManager.onBoxRemoved(serverLevel, pos);
        }
        super.onRemove(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(newState),
                isMoving
        );
    }
}
