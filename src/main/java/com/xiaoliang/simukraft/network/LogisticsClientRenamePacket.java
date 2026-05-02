package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.LogisticsData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 物流客户端重命名数据包
 * 用于同步客户端自定义名称
 */
@SuppressWarnings("null")
public class LogisticsClientRenamePacket {

    private final UUID clientId;
    private final String newName;

    public LogisticsClientRenamePacket(UUID clientId, String newName) {
        this.clientId = clientId;
        this.newName = newName;
    }

    public static void encode(LogisticsClientRenamePacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.clientId);
        buf.writeUtf(packet.newName, 64); // 最大64字符
    }

    public static LogisticsClientRenamePacket decode(FriendlyByteBuf buf) {
        UUID clientId = buf.readUUID();
        String newName = buf.readUtf(64);
        return new LogisticsClientRenamePacket(clientId, newName);
    }

    public static void handle(LogisticsClientRenamePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerLevel level = ctx.get().getSender().serverLevel();
            LogisticsData data = LogisticsData.get(level);

            LogisticsData.LogisticsClient client = data.getClient(packet.clientId);
            if (client != null) {
                // 限制名称长度并清理
                String cleanedName = packet.newName.trim();
                if (cleanedName.length() > 32) {
                    cleanedName = cleanedName.substring(0, 32);
                }
                client.setName(cleanedName);
                data.setDirty();

                // 同步给所有客户端
                NetworkManager.sendToAll(new ClientRenameSyncPacket(packet.clientId, cleanedName), level);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 客户端同步数据包（广播给所有客户端）
     */
    public static class ClientRenameSyncPacket {
        private final UUID clientId;
        private final String newName;

        public ClientRenameSyncPacket(UUID clientId, String newName) {
            this.clientId = clientId;
            this.newName = newName;
        }

        public static void encode(ClientRenameSyncPacket packet, FriendlyByteBuf buf) {
            buf.writeUUID(packet.clientId);
            buf.writeUtf(packet.newName, 64);
        }

        public static ClientRenameSyncPacket decode(FriendlyByteBuf buf) {
            UUID clientId = buf.readUUID();
            String newName = buf.readUtf(64);
            return new ClientRenameSyncPacket(clientId, newName);
        }

        public static void handle(ClientRenameSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                // 更新客户端数据缓存
                com.xiaoliang.simukraft.client.gui.LogisticsClientData.updateClientName(packet.clientId, packet.newName);
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
