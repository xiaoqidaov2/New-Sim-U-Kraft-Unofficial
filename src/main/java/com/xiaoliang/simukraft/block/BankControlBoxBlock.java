package com.xiaoliang.simukraft.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.Objects;

/**
 * NSUK-银行控制箱
 */
public class BankControlBoxBlock extends Block {

    public BankControlBoxBlock() {
        super(createProperties());
    }

    private static BlockBehaviour.Properties createProperties() {
        return Objects.requireNonNull(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(1.0F)
                .sound(SoundType.METAL));
    }
}
