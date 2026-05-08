package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.building.ControlBoxDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 请求控制盒信息的网络包（客户端 -> 服务器）
 */
@SuppressWarnings("null")
public class RequestControlBoxInfoPacket {
    private final BlockPos controlBoxPos;
    private final String controlBoxType;
    private final boolean updateHomeTeleportMode;
    private final boolean homeTeleportToAbove;

    public RequestControlBoxInfoPacket(BlockPos pos, String type) {
        this.controlBoxPos = pos;
        this.controlBoxType = type;
        this.updateHomeTeleportMode = false;
        this.homeTeleportToAbove = true;
    }

    public RequestControlBoxInfoPacket(BlockPos pos, String type, boolean updateHomeTeleportMode, boolean homeTeleportToAbove) {
        this.controlBoxPos = pos;
        this.controlBoxType = type;
        this.updateHomeTeleportMode = updateHomeTeleportMode;
        this.homeTeleportToAbove = homeTeleportToAbove;
    }

    public RequestControlBoxInfoPacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
        this.controlBoxType = buf.readUtf();
        this.updateHomeTeleportMode = buf.readBoolean();
        this.homeTeleportToAbove = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeUtf(controlBoxType);
        buf.writeBoolean(updateHomeTeleportMode);
        buf.writeBoolean(homeTeleportToAbove);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (updateHomeTeleportMode && isResidentialType(controlBoxType)) {
                ControlBoxDataManager.updateResidentialHomeTeleportMode(player.getServer(), controlBoxPos, homeTeleportToAbove);
            }

            ControlBoxDataManager.ControlBoxData controlBoxData = ControlBoxDataManager.readControlBox(player.getServer(), controlBoxPos, controlBoxType);
            String buildingName = controlBoxData != null ? controlBoxData.buildingName : null;
            boolean currentHomeTeleportToAbove = controlBoxData == null || controlBoxData.homeTeleportToAbove;

            // 发送响应给客户端
            NetworkManager.INSTANCE.sendTo(
                new ControlBoxInfoResponsePacket(controlBoxPos, buildingName, currentHomeTeleportToAbove),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );
        });
        ctx.get().setPacketHandled(true);
    }

    private boolean isResidentialType(String type) {
        return "residential".equals(type) || "residence".equals(type);
    }
}
