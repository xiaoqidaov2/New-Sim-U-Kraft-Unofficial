package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 基础材料配置界面 - LDLib版本
 * 用于配置普通模式下建筑师需要的单个材料
 */
@OnlyIn(Dist.CLIENT)
public class BasicMaterialsScreenLDLib extends ConfigurableListScreenLDLib {

    public BasicMaterialsScreenLDLib(Screen parent, List<String> initialItems, Consumer<List<String>> onSave) {
        super(parent, "基础材料配置 (普通模式)", initialItems, getAllItems(), onSave);
    }

    @Override
    protected List<String> getAllAvailableItems() {
        return getAllItems();
    }

    /**
     * 获取所有可用的物品ID列表（包括方块和物品）
     */
    @SuppressWarnings("deprecation")
    public static List<String> getAllItems() {
        List<String> items = new ArrayList<>();

        // 获取所有注册的方块（作为物品）
        BuiltInRegistries.BLOCK.forEach(block -> {
            if (block != net.minecraft.world.level.block.Blocks.AIR) {
                String blockId = BuiltInRegistries.BLOCK.getKey(Objects.requireNonNull(block)).toString();
                items.add(blockId);
            }
        });

        // 获取所有注册的物品
        BuiltInRegistries.ITEM.forEach(item -> {
            if (item != Items.AIR) {
                String itemId = BuiltInRegistries.ITEM.getKey(Objects.requireNonNull(item)).toString();
                if (!items.contains(itemId)) {
                    items.add(itemId);
                }
            }
        });

        // 按字母顺序排序
        items.sort(String::compareTo);

        return items;
    }

    /**
     * 创建基础材料配置界面
     */
    public static BasicMaterialsScreenLDLib createScreen(Screen parent, Runnable onSaveCallback) {
        return new BasicMaterialsScreenLDLib(
                parent,
                new ArrayList<>(ServerConfig.BASIC_MATERIALS.get()),
                items -> {
                    ServerConfig.setBasicMaterials(items);
                    ServerConfig.SPEC.save();
                    if (onSaveCallback != null) {
                        onSaveCallback.run();
                    }
                }
        );
    }
}
