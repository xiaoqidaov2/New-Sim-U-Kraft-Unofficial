package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * 切换界限显示网络包（客户端 -> 服务器）
 * menglannnn: 用于同步建筑界限和活动界限的显示状态
 */
@SuppressWarnings("null")
public class ToggleBoundsDisplayPacket {
    private static final Logger LOGGER = LogManager.getLogger();
    private final BlockPos controlBoxPos;
    private final String boundsType; // "building" 或 "activity"
    private final boolean visible;

    public ToggleBoundsDisplayPacket(BlockPos pos, String boundsType, boolean visible) {
        this.controlBoxPos = pos;
        this.boundsType = boundsType;
        this.visible = visible;
    }

    public ToggleBoundsDisplayPacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
        this.boundsType = buf.readUtf(32);
        this.visible = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeUtf(boundsType, 32);
        buf.writeBoolean(visible);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 服务器记录状态（可选）
            LOGGER.debug("[ToggleBoundsDisplayPacket] 玩家 {} 切换 {} 界限显示: {} at {}",
                player.getName().getString(), boundsType, visible, controlBoxPos);
        });
        ctx.get().setPacketHandled(true);
    }
}
