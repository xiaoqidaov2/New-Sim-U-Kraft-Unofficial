package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 请求建筑盒雇佣状态的网络包
 * 客户端 -> 服务器
 * 客户端打开建筑盒界面时请求同步雇佣数据
 */
@SuppressWarnings("null")
public class RequestBuildBoxHireStatusPacket {
    private final BlockPos buildBoxPos;

    public RequestBuildBoxHireStatusPacket(BlockPos pos) {
        this.buildBoxPos = pos;
    }

    public RequestBuildBoxHireStatusPacket(FriendlyByteBuf buf) {
        this.buildBoxPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(buildBoxPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            MinecraftServer server = player.getServer();
            if (server == null) return;

            var status = LegacyHireStatusResolver.resolveBuildBoxStatus(server, player, buildBoxPos);

            // 发送同步数据包给客户端
            NetworkManager.sendToPlayer(
                new SyncBuildBoxHireStatusPacket(buildBoxPos, status.builderUuid(), status.plannerUuid(), status.builderName(), status.plannerName()),
                player
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
