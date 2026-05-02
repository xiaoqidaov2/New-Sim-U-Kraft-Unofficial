package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 请求工作方块雇佣状态的网络包
 * 客户端 -> 服务器
 * 客户端打开工作方块界面时请求同步雇佣数据
 */
@SuppressWarnings("null")
public class RequestWorkBlockHireStatusPacket {
    private final BlockPos workBlockPos;
    private final String workBlockType;  // 工作方块类型

    public RequestWorkBlockHireStatusPacket(BlockPos pos, String type) {
        this.workBlockPos = pos;
        this.workBlockType = type;
    }

    public RequestWorkBlockHireStatusPacket(FriendlyByteBuf buf) {
        this.workBlockPos = buf.readBlockPos();
        this.workBlockType = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(workBlockPos);
        buf.writeUtf(workBlockType);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            MinecraftServer server = player.getServer();
            if (server == null) return;

            var status = LegacyHireStatusResolver.resolveWorkBlockStatus(server, player, workBlockPos, workBlockType);

            // 发送同步数据包给客户端，包含职业类型和建筑文件名
            NetworkManager.sendToPlayer(
                new SyncWorkBlockHireStatusPacket(workBlockPos, workBlockType, status.employeeUuid(), status.employeeName(), status.legacyJobType(), status.buildingFileName()),
                player
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
