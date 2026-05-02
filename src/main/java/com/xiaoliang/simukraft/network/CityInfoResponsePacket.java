package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityInfoResponsePacket {
    private final String cityName;
    private final int population;
    private final String mayorName;
    private final BlockPos cityCorePos;
    private final int cityLevel;

    public CityInfoResponsePacket(String cityName, int population, String mayorName, BlockPos cityCorePos, int cityLevel) {
        this.cityName = cityName;
        this.population = population;
        this.mayorName = mayorName;
        this.cityCorePos = cityCorePos;
        this.cityLevel = cityLevel;
    }

    public CityInfoResponsePacket(FriendlyByteBuf buf) {
        this.cityName = buf.readUtf();
        this.population = buf.readInt();
        this.mayorName = buf.readUtf();
        this.cityCorePos = buf.readBlockPos();
        this.cityLevel = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.cityName);
        buf.writeInt(this.population);
        buf.writeUtf(this.mayorName);
        buf.writeBlockPos(this.cityCorePos);
        buf.writeInt(this.cityLevel);
    }

    public static CityInfoResponsePacket decode(FriendlyByteBuf buf) {
        return new CityInfoResponsePacket(buf);
    }

    public static void handle(CityInfoResponsePacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 只在客户端处理
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                handleClientSide(message);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClientSide(CityInfoResponsePacket message) {
        // 延迟加载客户端类，避免服务端编译错误
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null) {
            try {
                // 使用反射创建GUI实例，避免直接导入客户端类
                Class<?> guiClass = Class.forName("com.xiaoliang.simukraft.client.gui.CityInfoScreen");
                Object screen = guiClass.getConstructor(BlockPos.class, String.class, int.class, String.class)
                        .newInstance(
                                message.cityCorePos,
                                message.cityName,
                                message.population,
                                message.mayorName
                        );
                
                minecraft.setScreen((net.minecraft.client.gui.screens.Screen) screen);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}