package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 重置配置数据包
 * 客户端发送请求到服务器，要求重置配置为默认值
 */
@SuppressWarnings("null")
public class ResetConfigPacket {

    public ResetConfigPacket() {
    }

    public ResetConfigPacket(FriendlyByteBuf buf) {
        // 无数据需要读取
    }

    public void encode(FriendlyByteBuf buf) {
        // 无数据需要写入
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();

            // 检查权限（需要OP权限）
            if (player != null && !player.hasPermissions(2)) {
                player.sendSystemMessage(Component.translatable("message.simukraft.config.no_permission_reset"));
                return;
            }

            // 重置配置
            try {
                ServerConfig.resetToDefaults();
                Simukraft.LOGGER.info("[ResetConfigPacket] Server config has been reset to defaults");
                if (player != null) {
                    player.sendSystemMessage(Component.translatable("message.simukraft.config.reset_success"));
                }
            } catch (Exception e) {
                Simukraft.LOGGER.error("[ResetConfigPacket] Failed to reset config", e);
                if (player != null) {
                    player.sendSystemMessage(Component.translatable("message.simukraft.config.reset_failed", e.getMessage()));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
