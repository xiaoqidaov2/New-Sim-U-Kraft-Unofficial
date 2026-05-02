package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 专家模式跳过列表配置界面 - LDLib版本
 * 用于配置专家模式下不需要的材料
 */
@OnlyIn(Dist.CLIENT)
public class ExpertModeSkipListScreenLDLib extends ConfigurableListScreenLDLib {

    public ExpertModeSkipListScreenLDLib(Screen parent, List<String> initialItems, Consumer<List<String>> onSave) {
        super(parent, "专家模式跳过列表", initialItems, getAllBlocks(), onSave);
    }

    @Override
    protected List<String> getAllAvailableItems() {
        return getAllBlocks();
    }

    /**
     * 获取所有可用的方块ID列表
     */
    public static List<String> getAllBlocks() {
        List<String> blocks = new ArrayList<>();

        // 获取所有注册的方块
        ForgeRegistries.BLOCKS.forEach(block -> {
            if (block != Blocks.AIR) {
                var blockId = ForgeRegistries.BLOCKS.getKey(Objects.requireNonNull(block));
                if (blockId != null) {
                    blocks.add(blockId.toString());
                }
            }
        });

        // 按字母顺序排序
        blocks.sort(String::compareTo);

        return blocks;
    }

    /**
     * 创建专家模式跳过列表配置界面
     */
    public static ExpertModeSkipListScreenLDLib createScreen(Screen parent, Runnable onSaveCallback) {
        return new ExpertModeSkipListScreenLDLib(
                parent,
                new ArrayList<>(ServerConfig.EXPERT_MODE_SKIP_LIST.get()),
                items -> {
                    ServerConfig.setExpertModeSkipList(items);
                    ServerConfig.SPEC.save();
                    if (onSaveCallback != null) {
                        onSaveCallback.run();
                    }
                }
        );
    }
}
