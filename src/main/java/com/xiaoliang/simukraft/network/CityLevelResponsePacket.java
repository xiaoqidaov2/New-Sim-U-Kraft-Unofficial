package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityLevelResponsePacket {
    private final BlockPos cityCorePos;
    private final int cityLevel;

    public CityLevelResponsePacket(BlockPos cityCorePos, int cityLevel) {
        this.cityCorePos = cityCorePos;
        this.cityLevel = cityLevel;
    }

    public CityLevelResponsePacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
        this.cityLevel = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.cityCorePos);
        buf.writeInt(this.cityLevel);
    }

    public static CityLevelResponsePacket decode(FriendlyByteBuf buf) {
        return new CityLevelResponsePacket(buf);
    }

    public static void handle(CityLevelResponsePacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 只在客户端处理
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                handleClientSide(message);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClientSide(CityLevelResponsePacket message) {
        // 延迟加载客户端类，避免服务端编译错误
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null) {
            try {
                // 使用反射创建GUI实例，避免直接导入客户端类
                Class<?> guiClass = Class.forName("com.xiaoliang.simukraft.client.gui.CityUpgradeScreen");
                Object screen = guiClass.getConstructor(BlockPos.class, int.class)
                        .newInstance(
                                message.cityCorePos,
                                message.cityLevel
                        );
                
                minecraft.setScreen((net.minecraft.client.gui.screens.Screen) screen);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
