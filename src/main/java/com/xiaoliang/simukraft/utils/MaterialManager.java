package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.Objects;

/**
 * 材料管理器
 * 处理材料配置系统的逻辑，包括专家模式、基础材料、通类匹配等
 */
public class MaterialManager {

    /**
     * 检查方块是否需要材料（根据配置）
     * @param state 方块状态
     * @return 如果需要材料返回true
     */
    public static boolean requiresMaterial(BlockState state) {
        if (!ServerConfig.isBuilderRequireMaterials()) {
            return false; // 不需要材料模式
        }

        Block block = state.getBlock();
        String blockId = getBlockId(block);

        // 专家模式：所有方块都需要材料（除了跳过列表中的）
        if (ServerConfig.isExpertModeEnabled()) {
            List<String> skipList = ServerConfig.getExpertModeSkipList();
            return !skipList.contains(blockId);
        }

        // 普通模式：检查是否在基础材料列表中或通类匹配组中
        List<String> basicMaterials = ServerConfig.getBasicMaterials();

        // 检查是否是基础材料
        if (basicMaterials.contains(blockId)) {
            return true;
        }

        // 检查是否在通类匹配组中
        if (ServerConfig.isMaterialCategoryMatchingEnabled()) {
            return ServerConfig.isMaterialInCategoryGroup(blockId);
        }

        return false;
    }

    /**
     * 获取方块所需的材料物品
     * 考虑通类匹配
     * @param state 方块状态
     * @return 可接受的物品ID列表
     */
    public static List<String> getRequiredMaterials(BlockState state) {
        Block block = state.getBlock();
        String blockId = getBlockId(block);

        // 专家模式：返回方块本身
        if (ServerConfig.isExpertModeEnabled()) {
            return Collections.singletonList(blockId);
        }

        // 普通模式：检查通类匹配
        if (ServerConfig.isMaterialCategoryMatchingEnabled()) {
            List<String> alternatives = ServerConfig.getAlternativeMaterials(blockId);
            if (alternatives.size() > 1) {
                return alternatives;
            }
        }

        return Collections.singletonList(blockId);
    }

    /**
     * 获取可用于该方块的物品 ID 集合。
     * 该方法与 canUseItemForBlock 保持同一套判定规则，方便缓存快速命中。
     */
    public static Set<String> getAcceptedItemIds(BlockState state) {
        LinkedHashSet<String> acceptedItemIds = new LinkedHashSet<>();
        ResourceLocation blockKey = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockKey == null) {
            return acceptedItemIds;
        }

        String blockId = blockKey.toString();
        if (ServerConfig.isExpertModeEnabled()) {
            addAcceptedMaterialId(acceptedItemIds, blockId);
            return acceptedItemIds;
        }

        if (ServerConfig.isMaterialCategoryMatchingEnabled()) {
            ServerConfig.MaterialGroupInfo groupInfo = ServerConfig.getMaterialGroup(blockId);
            if (groupInfo != null) {
                if (!groupInfo.getHeaders().isEmpty()) {
                    if (!groupInfo.isHeader(blockId)) {
                        addAcceptedMaterialId(acceptedItemIds, blockId);
                    }
                    for (String header : groupInfo.getHeaders()) {
                        addAcceptedMaterialId(acceptedItemIds, header);
                    }
                    return acceptedItemIds;
                }

                for (String materialId : groupInfo.getAllMaterials()) {
                    addAcceptedMaterialId(acceptedItemIds, materialId);
                }
                return acceptedItemIds;
            }
        }

        addAcceptedMaterialId(acceptedItemIds, blockId);
        return acceptedItemIds;
    }

    public static String getItemId(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return "";
        }
        return getItemId(itemStack.getItem());
    }

    /**
     * 检查物品是否可以用于放置指定方块
     * 考虑通类匹配配置（组头可以匹配组员，组员不能匹配其他材料）
     * @param itemStack 物品堆
     * @param state 要放置的方块状态
     * @return 如果可以使用返回true
     */
    public static boolean canUseItemForBlock(ItemStack itemStack, BlockState state) {
        if (itemStack.isEmpty()) return false;

        Item item = itemStack.getItem();
        String itemId = getItemId(item);

        Block block = state.getBlock();
        String blockId = getBlockId(block);

        // 专家模式：需要精确匹配（但处理方块变体如墙上的火把）
        if (ServerConfig.isExpertModeEnabled()) {
            // 直接匹配
            if (itemId.equals(blockId)) {
                return true;
            }
            // 处理方块变体（如墙上的火把、地上的火把）
            return isBlockVariantMatch(itemId, blockId);
        }

        // 普通模式：检查通类匹配（使用新的组头逻辑）
        if (ServerConfig.isMaterialCategoryMatchingEnabled()) {
            return ServerConfig.canMaterialReplace(blockId, itemId);
        }

        // 默认：精确匹配或方块变体匹配
        return itemId.equals(blockId) || isBlockVariantMatch(itemId, blockId);
    }

    /**
     * 检查物品ID和方块ID是否是同一方块的变体
     * 例如：minecraft:torch 和 minecraft:wall_torch
     * @param itemId 物品ID
     * @param blockId 方块ID
     * @return 如果是变体返回true
     */
    private static boolean isBlockVariantMatch(String itemId, String blockId) {
        // 提取基础ID（去掉命名空间）
        String itemBase = itemId.contains(":") ? itemId.substring(itemId.indexOf(":") + 1) : itemId;
        String blockBase = blockId.contains(":") ? blockId.substring(blockId.indexOf(":") + 1) : blockId;

        // 检查是否是墙上的火把变体
        if (itemBase.equals("torch") && blockBase.equals("wall_torch")) {
            return true;
        }
        // 检查是否是灵魂火把的变体
        if (itemBase.equals("soul_torch") && blockBase.equals("soul_wall_torch")) {
            return true;
        }
        // 检查是否是红石火把的变体
        if (itemBase.equals("redstone_torch") && blockBase.equals("redstone_wall_torch")) {
            return true;
        }
        // 检查是否是灯笼的变体
        if (itemBase.equals("lantern") && blockBase.equals("wall_lantern")) {
            return true;
        }
        // 检查是否是灵魂灯笼的变体
        if (itemBase.equals("soul_lantern") && blockBase.equals("soul_wall_lantern")) {
            return true;
        }

        return false;
    }

    private static void addAcceptedMaterialId(Set<String> acceptedItemIds, String materialId) {
        acceptedItemIds.add(materialId);
        addVariantItemIds(acceptedItemIds, materialId);
    }

    public static String getBlockId(Block block) {
        ResourceLocation blockKey = ForgeRegistries.BLOCKS.getKey(block);
        return blockKey != null ? blockKey.toString() : "";
    }

    private static String getItemId(Item item) {
        ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(item);
        return itemKey != null ? itemKey.toString() : "";
    }

    private static void addVariantItemIds(Set<String> acceptedItemIds, String blockId) {
        String namespace = "minecraft";
        String path = blockId;
        int separatorIndex = blockId.indexOf(':');
        if (separatorIndex >= 0) {
            namespace = blockId.substring(0, separatorIndex);
            path = blockId.substring(separatorIndex + 1);
        }

        switch (path) {
            case "wall_torch" -> acceptedItemIds.add(namespace + ":torch");
            case "soul_wall_torch" -> acceptedItemIds.add(namespace + ":soul_torch");
            case "redstone_wall_torch" -> acceptedItemIds.add(namespace + ":redstone_torch");
            case "wall_lantern" -> acceptedItemIds.add(namespace + ":lantern");
            case "soul_wall_lantern" -> acceptedItemIds.add(namespace + ":soul_lantern");
            default -> {
            }
        }
    }

    /**
     * 获取材料的显示名称列表
     * @param materialIds 材料ID列表
     * @return 显示名称列表
     */
    public static List<String> getMaterialDisplayNames(List<String> materialIds) {
        List<String> names = new ArrayList<>();
        for (String materialId : materialIds) {
            names.add(getMaterialDisplayName(materialId));
        }
        return names;
    }

    /**
     * 获取材料的本地化显示名称（自动适配当前语言）
     *
     * @param materialId 材料 ID（如 {@code minecraft:oak_planks}）
     * @return 当前客户端语言的显示名称；若注册表中找不到则回退到 ID 本身
     */
    public static String getMaterialDisplayName(String materialId) {
        // 使用 BlockNameTranslator（通过 MC 原生翻译，支持全语言）
        String localizedName = BlockNameTranslator.getItemName(materialId);
        if (!localizedName.equals(materialId)) {
            return localizedName;
        }

        // 再尝试方块注册表
        localizedName = BlockNameTranslator.getBlockName(materialId);
        if (!localizedName.equals(materialId)) {
            return localizedName;
        }

        // 找不到则返回原始 ID
        return materialId;
    }

    /**
     * 获取缺少的材料提示信息
     * @param state 方块状态
     * @return 提示信息
     */
    public static String getMaterialRequirementMessage(BlockState state) {
        Block block = state.getBlock();
        String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();

        if (ServerConfig.isExpertModeEnabled()) {
            return getMaterialDisplayName(blockId);
        }

        if (ServerConfig.isMaterialCategoryMatchingEnabled()) {
            // 获取材料所属的组信息
            ServerConfig.MaterialGroupInfo group = ServerConfig.getMaterialGroup(blockId);
            if (group != null) {
                String targetDisplayName = getMaterialDisplayName(blockId);
                
                // 检查是否有组头
                if (!group.getHeaders().isEmpty()) {
                    // 有组头的组：只显示组头作为可用材料
                    List<String> headerDisplayNames = getUniqueMaterialDisplayNames(group.getHeaders());
                    
                    // 如果目标方块本身就是组头（且组头列表只有一个），省略"可用"部分
                    if (group.isHeader(blockId) && group.getHeaders().size() == 1) {
                        return targetDisplayName;
                    }
                    
                    // 否则显示 目标方块 (可用: 组头1, 组头2 等)
                    StringBuilder sb = new StringBuilder();
                    String available = TranslationUtil.translate("simukraft.material.available");
                    String etc       = TranslationUtil.translate("simukraft.material.etc");
                    sb.append(targetDisplayName).append(" (").append(available).append(": ");
                    for (int i = 0; i < Math.min(3, headerDisplayNames.size()); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(headerDisplayNames.get(i));
                    }
                    if (headerDisplayNames.size() > 3) {
                        sb.append(" ").append(etc);
                    }
                    sb.append(")");
                    return sb.toString();
                } else {
                    // 无组头的组：显示所有组员作为可用材料（组员之间可以互相替代）
                    List<String> memberDisplayNames = getUniqueMaterialDisplayNames(group.getMembers());

                    StringBuilder sb = new StringBuilder();
                    String available = TranslationUtil.translate("simukraft.material.available");
                    String etc       = TranslationUtil.translate("simukraft.material.etc");
                    sb.append(targetDisplayName).append(" (").append(available).append(": ");
                    for (int i = 0; i < Math.min(3, memberDisplayNames.size()); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(memberDisplayNames.get(i));
                    }
                    if (memberDisplayNames.size() > 3) {
                        sb.append(" ").append(etc);
                    }
                    sb.append(")");
                    return sb.toString();
                }
            }
        }

        return getMaterialDisplayName(blockId);
    }

    /**
     * 获取缺少的材料提示信息（Component 版本，支持客户端翻译）
     */
    public static Component getMaterialRequirementComponent(BlockState state) {
        Block block = state.getBlock();
        String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();

        if (ServerConfig.isExpertModeEnabled()) {
            return BlockNameTranslator.getItemComponent(blockId);
        }

        if (ServerConfig.isMaterialCategoryMatchingEnabled()) {
            ServerConfig.MaterialGroupInfo group = ServerConfig.getMaterialGroup(blockId);
            if (group != null) {
                Component targetName = BlockNameTranslator.getItemComponent(blockId);

                List<String> sourceIds;
                if (!group.getHeaders().isEmpty()) {
                    if (group.isHeader(blockId) && group.getHeaders().size() == 1) {
                        return targetName;
                    }
                    sourceIds = group.getHeaders();
                } else {
                    sourceIds = group.getMembers();
                }

                // 用 Component 拼接，保留翻译键
                MutableComponent result = Component.empty()
                        .append(Objects.requireNonNull(targetName))
                        .append(Objects.requireNonNull(Component.literal(" (")))
                        .append(Objects.requireNonNull(Component.translatable("simukraft.material.available")))
                        .append(Objects.requireNonNull(Component.literal(": ")));

                List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(sourceIds));
                int count = Math.min(3, uniqueIds.size());
                for (int i = 0; i < count; i++) {
                    if (i > 0) result.append(Objects.requireNonNull(Component.literal(", ")));
                    result.append(Objects.requireNonNull(BlockNameTranslator.getItemComponent(uniqueIds.get(i))));
                }
                if (uniqueIds.size() > 3) {
                    result.append(Objects.requireNonNull(Component.literal(" ")))
                            .append(Objects.requireNonNull(Component.translatable("simukraft.material.etc")));
                }
                result.append(Objects.requireNonNull(Component.literal(")")));
                return result;
            }
        }

        return BlockNameTranslator.getItemComponent(blockId);
    }

    /**
     * 获取去重后的材料显示名称列表
     * @param materialIds 材料ID列表
     * @return 去重后的显示名称列表
     */
    private static List<String> getUniqueMaterialDisplayNames(List<String> materialIds) {
        Set<String> uniqueNames = new LinkedHashSet<>();
        for (String materialId : materialIds) {
            uniqueNames.add(getMaterialDisplayName(materialId));
        }
        return new ArrayList<>(uniqueNames);
    }

    /**
     * 检查是否是有效的材料ID
     * @param materialId 材料ID
     * @return 如果有效返回true
     */
    public static boolean isValidMaterial(String materialId) {
        ResourceLocation location = ResourceLocation.tryParse(Objects.requireNonNull(materialId));
        if (location == null) return false;

        Block block = ForgeRegistries.BLOCKS.getValue(location);
        if (block != null) {
            return true;
        }

        Item item = ForgeRegistries.ITEMS.getValue(location);
        return item != null;
    }

    /**
     * 获取所有可用的材料ID列表
     * @return 材料ID列表
     */
    public static List<String> getAllAvailableMaterials() {
        List<String> materials = new ArrayList<>();

        // 获取所有方块
        ForgeRegistries.BLOCKS.forEach(block -> {
            String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();
            materials.add(blockId);
        });

        // 获取所有物品（不包括方块）
        ForgeRegistries.ITEMS.forEach(item -> {
            String itemId = ForgeRegistries.ITEMS.getKey(item).toString();
            if (!materials.contains(itemId)) {
                materials.add(itemId);
            }
        });

        materials.sort(String::compareTo);
        return materials;
    }

    /**
     * 将方块ID转换为物品ID
     * @param blockId 方块ID
     * @return 对应的物品ID，如果没有找到返回原ID
     */
    public static String blockIdToItemId(String blockId) {
        String safeBlockId = Objects.requireNonNull(blockId);
        ResourceLocation location = ResourceLocation.tryParse(safeBlockId);
        if (location == null) return safeBlockId;

        Block block = ForgeRegistries.BLOCKS.getValue(location);
        if (block != null) {
            Item item = block.asItem();
            if (item != null) {
                return ForgeRegistries.ITEMS.getKey(item).toString();
            }
        }

        return safeBlockId;
    }
}
