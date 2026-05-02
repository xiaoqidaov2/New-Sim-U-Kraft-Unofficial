package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.utils.ResidentManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 请求NPC居住信息的网络包（客户端 -> 服务器）
 */
@SuppressWarnings("null")
public class RequestNPCResidencePacket {
    private final String npcName;

    public RequestNPCResidencePacket(String npcName) {
        this.npcName = npcName;
    }

    public RequestNPCResidencePacket(FriendlyByteBuf buf) {
        this.npcName = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(npcName);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 在服务端查询NPC居住信息
            boolean hasResidence = ResidentManager.hasResidenceAssigned(player.getServer(), npcName);
            String position = null;
            if (hasResidence) {
                position = ResidentManager.getNPCResidencePosition(player.getServer(), npcName);
            }

            // 发送响应给客户端
            NetworkManager.INSTANCE.sendTo(
                new NPCResidenceResponsePacket(npcName, hasResidence, position),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
