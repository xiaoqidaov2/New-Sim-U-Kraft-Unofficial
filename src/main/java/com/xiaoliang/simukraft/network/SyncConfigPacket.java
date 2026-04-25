package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 同步配置数据包
 * 用于在客户端和服务器之间同步配置值
 */
public class SyncConfigPacket {

    // 配置类型
    private final ConfigType type;
    private final String configName;
    private final int intValue;
    private final boolean boolValue;

    public enum ConfigType {
        INT, BOOLEAN
    }

    // 整数配置
    public SyncConfigPacket(String configName, int value) {
        this.type = ConfigType.INT;
        this.configName = configName;
        this.intValue = value;
        this.boolValue = false;
    }

    // 布尔配置
    public SyncConfigPacket(String configName, boolean value) {
        this.type = ConfigType.BOOLEAN;
        this.configName = configName;
        this.intValue = 0;
        this.boolValue = value;
    }

    public SyncConfigPacket(FriendlyByteBuf buf) {
        this.type = ConfigType.values()[buf.readInt()];
        this.configName = Objects.requireNonNull(buf.readUtf());
        this.intValue = buf.readInt();
        this.boolValue = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(type.ordinal());
        buf.writeUtf(Objects.requireNonNull(configName));
        buf.writeInt(intValue);
        buf.writeBoolean(boolValue);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();

            // 服务器端接收（包括单人游戏的集成服务器）
            if (player != null) {
                // 检查权限
                if (!player.hasPermissions(2)) {
                    Simukraft.LOGGER.warn("[SyncConfigPacket] Player {} attempted to modify config without permission", player.getName().getString());
                    return;
                }

                // 应用配置
                applyConfig();

                // 保存到文件
                ServerConfig.SPEC.save();

                Simukraft.LOGGER.info("[SyncConfigPacket] Config '{}' updated", configName);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void applyConfig() {
        switch (configName) {
            // 通用配置
            case "enableBlacklistProtection" -> ServerConfig.ENABLE_BLACKLIST_PROTECTION.set(boolValue);
            case "logSkippedBlocks" -> ServerConfig.LOG_BLACKLIST_SKIPPED_BLOCKS.set(boolValue);
            case "enableDebugLog" -> ServerConfig.ENABLE_DEBUG_LOG.set(boolValue);

            // NPC等级配置
            case "npcMaxLevel" -> ServerConfig.NPC_MAX_LEVEL.set(intValue);
            case "npcSpeedBonusPerLevel" -> ServerConfig.NPC_SPEED_BONUS_PER_LEVEL.set(intValue);
            case "npcMinSpeedTicks" -> ServerConfig.NPC_MIN_SPEED_TICKS.set(intValue);

            // 规划师配置
            case "plannerRemoveSpeedBase" -> ServerConfig.PLANNER_REMOVE_SPEED_BASE.set(intValue);
            case "plannerReplaceSpeedBase" -> ServerConfig.PLANNER_REPLACE_SPEED_BASE.set(intValue);
            case "plannerFillSpeedBase" -> ServerConfig.PLANNER_FILL_SPEED_BASE.set(intValue);
            case "plannerDropItemsOnRemove" -> ServerConfig.PLANNER_DROP_ITEMS_ON_REMOVE.set(boolValue);
            case "plannerStoreItemsInChest" -> ServerConfig.PLANNER_STORE_ITEMS_IN_CHEST.set(boolValue);
            case "plannerChestSearchRange" -> ServerConfig.PLANNER_CHEST_SEARCH_RANGE.set(intValue);
            case "plannerWarningCooldown" -> ServerConfig.PLANNER_WARNING_COOLDOWN.set(intValue);
            case "plannerRestCheckInterval" -> ServerConfig.PLANNER_REST_CHECK_INTERVAL.set(intValue);
            case "plannerEnableXpGain" -> ServerConfig.PLANNER_ENABLE_XP_GAIN.set(boolValue);
            case "plannerXpPerBlock" -> ServerConfig.PLANNER_XP_PER_BLOCK.set(intValue);

            // 建筑师配置
            case "builderPlaceSpeedBase" -> ServerConfig.BUILDER_PLACE_SPEED_BASE.set(intValue);
            case "builderChestSearchRange" -> ServerConfig.BUILDER_CHEST_SEARCH_RANGE.set(intValue);
            case "builderWarningCooldown" -> ServerConfig.BUILDER_WARNING_COOLDOWN.set(intValue);
            case "builderEnableXpGain" -> ServerConfig.BUILDER_ENABLE_XP_GAIN.set(boolValue);
            case "builderXpPerBlock" -> ServerConfig.BUILDER_XP_PER_BLOCK.set(intValue);
            case "builderChunkLoadWaitTicks" -> ServerConfig.BUILDER_CHUNK_LOAD_WAIT_TICKS.set(intValue);

            // 通用配置 - 建造模式
            case "enableCreativeMode" -> ServerConfig.ENABLE_CREATIVE_MODE.set(boolValue);
            case "enableExpertMode" -> ServerConfig.ENABLE_EXPERT_MODE.set(boolValue);
            case "enableMaterialCategoryMatching" -> ServerConfig.ENABLE_MATERIAL_CATEGORY_MATCHING.set(boolValue);
        }
    }
}
