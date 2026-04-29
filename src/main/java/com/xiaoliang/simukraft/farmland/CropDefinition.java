package com.xiaoliang.simukraft.farmland;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Objects;

public record CropDefinition(
        String selectionId,
        Item seedItem,
        Block cropBlock,
        CropLayoutType layoutType,
        Component displayName
) {
    public CropDefinition {
        selectionId = Objects.requireNonNull(selectionId);
        seedItem = Objects.requireNonNull(seedItem);
        cropBlock = Objects.requireNonNull(cropBlock);
        layoutType = Objects.requireNonNull(layoutType);
        displayName = Objects.requireNonNull(displayName);
    }

    public ResourceLocation selectionLocation() {
        return Objects.requireNonNull(ResourceLocation.tryParse(selectionId));
    }
}
