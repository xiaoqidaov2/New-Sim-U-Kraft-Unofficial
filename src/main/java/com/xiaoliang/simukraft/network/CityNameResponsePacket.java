package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.CityNameCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityNameResponsePacket {
    private final UUID cityId;
    private final String cityName;

    public CityNameResponsePacket(UUID cityId, String cityName) {
        this.cityId = cityId;
        this.cityName = cityName;
    }

    public CityNameResponsePacket(FriendlyByteBuf buf) {
        this.cityId = buf.readUUID();
        this.cityName = buf.readUtf(32767);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(cityId);
        buf.writeUtf(cityName);
    }

    public static void handle(CityNameResponsePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            // 客户端处理响应 - 缓存城市名称
            CityNameCache.put(packet.cityId, packet.cityName);
        });
        context.setPacketHandled(true);
    }

    public UUID getCityId() {
        return cityId;
    }

    public String getCityName() {
        return cityName;
    }
}