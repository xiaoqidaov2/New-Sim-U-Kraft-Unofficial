package com.xiaoliang.simukraft.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 居民信息响应网络包（服务器 -> 客户端）
 */
@SuppressWarnings("null")
public class ResidentInfoResponsePacket {
    private final BlockPos controlBoxPos;
    private final String residentName;

    public ResidentInfoResponsePacket(BlockPos pos, String name) {
        this.controlBoxPos = pos;
        this.residentName = name;
    }

    public ResidentInfoResponsePacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
        this.residentName = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeUtf(residentName != null ? residentName : "");
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 在客户端更新界面
            Minecraft minecraft = Minecraft.getInstance();
            
            // 统一使用 ResidentialControlBoxScreen
            if (minecraft.screen instanceof com.xiaoliang.simukraft.client.gui.ResidentialControlBoxScreen screen) {
                // 检查位置是否匹配
                if (screen.getControlBoxPos().equals(controlBoxPos)) {
                    screen.setResidentName(residentName);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getControlBoxPos() {
        return controlBoxPos;
    }

    public String getResidentName() {
        return residentName;
    }
}
