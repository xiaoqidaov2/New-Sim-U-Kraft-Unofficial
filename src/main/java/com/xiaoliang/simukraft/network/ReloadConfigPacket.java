package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 重载配置数据包
 * 客户端发送请求到服务器，要求重载配置文件
 */
@SuppressWarnings("null")
public class ReloadConfigPacket {

    public ReloadConfigPacket() {
    }

    public ReloadConfigPacket(FriendlyByteBuf buf) {
        // 无数据需要读取
    }

    public void encode(FriendlyByteBuf buf) {
        // 无数据需要写入
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 检查权限（需要OP权限）
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(Component.translatable("message.simukraft.config.no_permission_reload"));
                return;
            }

            // 重载配置
            try {
                // 保存当前配置以确保一致性
                ServerConfig.SPEC.save();
                Simukraft.LOGGER.info("[ReloadConfigPacket] 服务器配置已重载，操作者: {}", player.getName().getString());
                player.sendSystemMessage(Component.translatable("message.simukraft.config.reload_success"));
            } catch (Exception e) {
                Simukraft.LOGGER.error("[ReloadConfigPacket] 重载配置失败", e);
                player.sendSystemMessage(Component.translatable("message.simukraft.config.reload_failed", e.getMessage()));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
