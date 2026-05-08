package com.xiaoliang.simukraft.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 控制盒信息响应网络包（服务器 -> 客户端）
 */
@SuppressWarnings("null")
public class ControlBoxInfoResponsePacket {
    private final BlockPos controlBoxPos;
    private final String buildingName;
    private final boolean homeTeleportToAbove;

    public ControlBoxInfoResponsePacket(BlockPos pos, String buildingName, boolean homeTeleportToAbove) {
        this.controlBoxPos = pos;
        this.buildingName = buildingName;
        this.homeTeleportToAbove = homeTeleportToAbove;
    }

    public ControlBoxInfoResponsePacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
        this.buildingName = buf.readUtf();
        this.homeTeleportToAbove = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeUtf(buildingName != null ? buildingName : "");
        buf.writeBoolean(homeTeleportToAbove);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 在客户端执行
            Minecraft minecraft = Minecraft.getInstance();
            Screen currentScreen = minecraft.screen;
            
            // 检查当前是否打开了住宅控制盒界面
            if (currentScreen instanceof com.xiaoliang.simukraft.client.gui.ResidentialControlBoxScreen) {
                com.xiaoliang.simukraft.client.gui.ResidentialControlBoxScreen screen = 
                    (com.xiaoliang.simukraft.client.gui.ResidentialControlBoxScreen) currentScreen;
                
                // 检查位置是否匹配
                if (screen.getControlBoxPos().equals(controlBoxPos)) {
                    screen.setBuildingName(buildingName != null && !buildingName.isEmpty() ? buildingName : null);
                    screen.setHomeTeleportToAbove(homeTeleportToAbove);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
