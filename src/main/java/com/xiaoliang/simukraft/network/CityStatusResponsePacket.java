package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityStatusResponsePacket {
    private final boolean hasCity;
    private final boolean isOwner;
    private final boolean hasData;
    private final BlockPos cityCorePos;
    private final String cityName;
    private final int population;
    private final String mayorName;

    public CityStatusResponsePacket(boolean hasCity, boolean isOwner, boolean hasData, BlockPos cityCorePos, String cityName, int population, String mayorName) {
        this.hasCity = hasCity;
        this.isOwner = isOwner;
        this.hasData = hasData;
        this.cityCorePos = cityCorePos;
        this.cityName = cityName;
        this.population = population;
        this.mayorName = mayorName;
    }

    public CityStatusResponsePacket(FriendlyByteBuf buf) {
        this.hasCity = buf.readBoolean();
        this.isOwner = buf.readBoolean();
        this.hasData = buf.readBoolean();
        this.cityCorePos = buf.readBlockPos();
        this.cityName = buf.readUtf();
        this.population = buf.readInt();
        this.mayorName = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.hasCity);
        buf.writeBoolean(this.isOwner);
        buf.writeBoolean(this.hasData);
        buf.writeBlockPos(this.cityCorePos);
        buf.writeUtf(this.cityName);
        buf.writeInt(this.population);
        buf.writeUtf(this.mayorName);
    }

    public static CityStatusResponsePacket decode(FriendlyByteBuf buf) {
        return new CityStatusResponsePacket(buf);
    }

    public static void handle(CityStatusResponsePacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 只在客户端处理
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                handleClientSide(message);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClientSide(CityStatusResponsePacket message) {
        // 延迟加载客户端类，避免服务端编译错误
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null) {
            try {
                // 使用反射创建GUI实例，避免直接导入客户端类
                Class<?> guiClass;
                if (message.hasCity) {
                    if (message.isOwner) {
                        // 玩家有城市，且是该城市的所有者：显示管理面板
                        guiClass = Class.forName("com.xiaoliang.simukraft.client.gui.CityManagementScreen");
                    } else {
                        // 玩家有城市，但不是该城市的所有者：显示基本信息
                        guiClass = Class.forName("com.xiaoliang.simukraft.client.gui.CityInfoScreen");
                    }
                } else {
                    // 玩家没有城市
                    if (message.hasData) {
                        // 城市核心有数据（属于其他玩家）：显示基本信息
                        guiClass = Class.forName("com.xiaoliang.simukraft.client.gui.CityInfoScreen");
                    } else {
                        // 城市核心没有数据：直接打开创建界面
                        guiClass = Class.forName("com.xiaoliang.simukraft.client.gui.CityNamingScreen");
                    }
                }
                
                Object screen;
                if (guiClass.getSimpleName().equals("CityInfoScreen")) {
                    screen = guiClass.getConstructor(BlockPos.class, String.class, int.class, String.class)
                            .newInstance(message.cityCorePos, message.cityName, message.population, message.mayorName);
                } else {
                    screen = guiClass.getConstructor(BlockPos.class)
                            .newInstance(message.cityCorePos);
                }
                
                minecraft.setScreen((net.minecraft.client.gui.screens.Screen) screen);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}