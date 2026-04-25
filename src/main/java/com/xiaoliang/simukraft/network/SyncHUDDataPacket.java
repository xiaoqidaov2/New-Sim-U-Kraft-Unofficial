package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.ClientSimukraftData;
import com.xiaoliang.simukraft.world.CityPermissionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 同步HUD数据的数据包
 * 用于将服务器端的游戏数据同步到客户端HUD显示
 */
public class SyncHUDDataPacket {
    private final int currentDay;
    private final int worldPopulation;
    private final String cityName;
    private final double cityFunds;
    private final int cityPopulation;
    private final int permissionLevel; // 权限级别
    private final boolean creativeMode; // 创造模式

    /**
     * 构造函数
     */
    public SyncHUDDataPacket(int currentDay, int worldPopulation, String cityName, double cityFunds, int cityPopulation, int permissionLevel, boolean creativeMode) {
        this.currentDay = currentDay;
        this.worldPopulation = worldPopulation;
        this.cityName = cityName;
        this.cityFunds = cityFunds;
        this.cityPopulation = cityPopulation;
        this.permissionLevel = permissionLevel;
        this.creativeMode = creativeMode;
    }

    /**
     * 从字节缓冲区解码数据包
     */
    public SyncHUDDataPacket(FriendlyByteBuf buf) {
        this.currentDay = buf.readInt();
        this.worldPopulation = buf.readInt();
        this.cityName = Objects.requireNonNull(buf.readUtf());
        this.cityFunds = buf.readDouble();
        this.cityPopulation = buf.readInt();
        this.permissionLevel = buf.readInt();
        this.creativeMode = buf.readBoolean();
    }

    /**
     * 将数据包编码到字节缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(currentDay);
        buf.writeInt(worldPopulation);
        buf.writeUtf(Objects.requireNonNull(cityName));
        buf.writeDouble(cityFunds);
        buf.writeInt(cityPopulation);
        buf.writeInt(permissionLevel);
        buf.writeBoolean(creativeMode);
    }

    /**
     * 处理数据包
     */
    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 更新客户端缓存
            ClientSimukraftData.setCurrentDay(currentDay);
            ClientSimukraftData.setCurrentPopulation(worldPopulation);
            ClientSimukraftData.setCurrentCityData(cityName, cityFunds, cityPopulation);
            ClientSimukraftData.setCreativeMode(creativeMode);

            // 更新权限数据
            CityPermissionManager.PermissionLevel level = CityPermissionManager.PermissionLevel.values()[permissionLevel];
            ClientSimukraftData.setPlayerPermissionLevel(level);
        });
        context.setPacketHandled(true);
    }
}
