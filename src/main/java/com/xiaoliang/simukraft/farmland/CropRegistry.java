package com.xiaoliang.simukraft.farmland;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class CropRegistry {
    private static final Map<String, String> LEGACY_ALIASES = Map.of(
            "wheat", "minecraft:wheat_seeds",
            "carrot", "minecraft:carrot",
            "potato", "minecraft:potato",
            "beetroot", "minecraft:beetroot_seeds",
            "melon", "minecraft:melon_seeds",
            "pumpkin", "minecraft:pumpkin_seeds"
    );
    private static final Map<String, CropDefinition> CACHE = new ConcurrentHashMap<>();

    private CropRegistry() {}

    public static Optional<CropDefinition> resolve(String selectionId) {
        String normalizedId = normalizeSelectionId(selectionId);
        if (normalizedId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(CACHE.computeIfAbsent(normalizedId, CropRegistry::createDefinition));
    }

    public static String normalizeSelectionId(String selectionId) {
        if (selectionId == null || selectionId.isBlank()) {
            return null;
        }
        String normalized = selectionId.trim().toLowerCase(Locale.ROOT);
        return LEGACY_ALIASES.getOrDefault(normalized, normalized);
    }

    public static List<CropDefinition> getSelectableCrops() {
        Map<String, CropDefinition> definitions = new LinkedHashMap<>();
        addIfPresent(definitions, "minecraft:wheat_seeds");
        addIfPresent(definitions, "minecraft:carrot");
        addIfPresent(definitions, "minecraft:potato");
        addIfPresent(definitions, "minecraft:beetroot_seeds");
        addIfPresent(definitions, "minecraft:melon_seeds");
        addIfPresent(definitions, "minecraft:pumpkin_seeds");

        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
            if (itemId == null) {
                continue;
            }
            resolve(itemId.toString()).ifPresent(definition -> definitions.putIfAbsent(definition.selectionId(), definition));
        }

        List<CropDefinition> result = new ArrayList<>(definitions.values());
        result.sort(Comparator.comparing(definition -> definition.displayName().getString()));
        return result;
    }

    public static boolean isSupported(String selectionId) {
        return resolve(selectionId).isPresent();
    }

    private static void addIfPresent(Map<String, CropDefinition> definitions, String selectionId) {
        resolve(selectionId).ifPresent(definition -> definitions.put(definition.selectionId(), definition));
    }

    private static CropDefinition createDefinition(String selectionId) {
        return createDefinitionOptional(selectionId).orElse(null);
    }

    private static Optional<CropDefinition> createDefinitionOptional(String selectionId) {
        ResourceLocation itemId = ResourceLocation.tryParse(selectionId);
        if (itemId == null) {
            return Optional.empty();
        }

        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) {
            return Optional.empty();
        }

        CropDefinition vanillaDefinition = createVanillaDefinition(selectionId, item);
        if (vanillaDefinition != null) {
            return Optional.of(vanillaDefinition);
        }

        Block cropBlock = null;
        if (item instanceof BlockItem blockItem) {
            cropBlock = blockItem.getBlock();
        } else if (item instanceof ItemNameBlockItem itemNameBlockItem) {
            cropBlock = itemNameBlockItem.getBlock();
        }

        if (!(cropBlock instanceof CropBlock) && !(cropBlock instanceof StemBlock)) {
            return Optional.empty();
        }

        CropLayoutType layoutType = cropBlock instanceof StemBlock ? CropLayoutType.CHECKERBOARD : CropLayoutType.FULL;
        return Optional.of(new CropDefinition(selectionId, item, cropBlock, layoutType, item.getDescription()));
    }

    private static CropDefinition createVanillaDefinition(String selectionId, Item item) {
        return switch (selectionId) {
            case "minecraft:wheat_seeds" -> new CropDefinition(selectionId, item, Blocks.WHEAT, CropLayoutType.FULL, item.getDescription());
            case "minecraft:carrot" -> new CropDefinition(selectionId, item, Blocks.CARROTS, CropLayoutType.FULL, item.getDescription());
            case "minecraft:potato" -> new CropDefinition(selectionId, item, Blocks.POTATOES, CropLayoutType.FULL, item.getDescription());
            case "minecraft:beetroot_seeds" -> new CropDefinition(selectionId, item, Blocks.BEETROOTS, CropLayoutType.FULL, item.getDescription());
            case "minecraft:melon_seeds" -> new CropDefinition(selectionId, item, Blocks.MELON_STEM, CropLayoutType.CHECKERBOARD, item.getDescription());
            case "minecraft:pumpkin_seeds" -> new CropDefinition(selectionId, item, Blocks.PUMPKIN_STEM, CropLayoutType.CHECKERBOARD, item.getDescription());
            default -> null;
        };
    }
}
