package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 方块黑名单配置界面 - LDLib版本
 * 用于配置规划师和建筑师不能处理的方块
 */
@OnlyIn(Dist.CLIENT)
public class BlockBlacklistScreenLDLib extends ConfigurableListScreenLDLib {

    public enum BlacklistType {
        PLANNING("规划师方块黑名单"),
        CONSTRUCTION("建筑师方块黑名单");

        private final String displayName;

        BlacklistType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public BlockBlacklistScreenLDLib(Screen parent, BlacklistType type,
                                     List<String> initialItems, Consumer<List<String>> onSave) {
        super(parent, type.getDisplayName(), initialItems, getAllBlocks(), onSave);
    }

    @Override
    protected List<String> getAllAvailableItems() {
        return getAllBlocks();
    }

    /**
     * 获取所有可用的方块ID列表
     */
    @SuppressWarnings("deprecation")
    public static List<String> getAllBlocks() {
        List<String> blocks = new ArrayList<>();

        // 获取所有注册的方块
        BuiltInRegistries.BLOCK.forEach(block -> {
            if (block != Blocks.AIR) {
                String blockId = BuiltInRegistries.BLOCK.getKey(Objects.requireNonNull(block)).toString();
                blocks.add(blockId);
            }
        });

        // 按字母顺序排序
        blocks.sort(String::compareTo);

        return blocks;
    }

    /**
     * 创建规划师黑名单配置界面
     */
    public static BlockBlacklistScreenLDLib createPlanningBlacklistScreen(Screen parent, Runnable onSaveCallback) {
        return new BlockBlacklistScreenLDLib(
                parent,
                BlacklistType.PLANNING,
                new ArrayList<>(ServerConfig.PLANNING_BLOCK_BLACKLIST.get()),
                items -> {
                    ServerConfig.PLANNING_BLOCK_BLACKLIST.set(items);
                    ServerConfig.SPEC.save();
                    if (onSaveCallback != null) {
                        onSaveCallback.run();
                    }
                }
        );
    }

    /**
     * 创建建筑师黑名单配置界面
     */
    public static BlockBlacklistScreenLDLib createConstructionBlacklistScreen(Screen parent, Runnable onSaveCallback) {
        return new BlockBlacklistScreenLDLib(
                parent,
                BlacklistType.CONSTRUCTION,
                new ArrayList<>(ServerConfig.CONSTRUCTION_BLOCK_BLACKLIST.get()),
                items -> {
                    ServerConfig.CONSTRUCTION_BLOCK_BLACKLIST.set(items);
                    ServerConfig.SPEC.save();
                    if (onSaveCallback != null) {
                        onSaveCallback.run();
                    }
                }
        );
    }
}
