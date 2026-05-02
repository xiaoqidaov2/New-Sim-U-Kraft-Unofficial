package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class GetCityNamePacket {
    private final UUID cityId;

    public GetCityNamePacket(UUID cityId) {
        this.cityId = cityId;
    }

    public GetCityNamePacket(FriendlyByteBuf buf) {
        this.cityId = buf.readUUID();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(cityId);
    }

    public static void handle(GetCityNamePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ServerLevel level = player.serverLevel();
                CityData cityData = CityData.get(level);
                CityData.CityInfo cityInfo = cityData.getCity(packet.cityId);
                String cityName = cityInfo != null ? cityInfo.getCityName() : "gui.city.unknown";
                
                NetworkManager.INSTANCE.sendTo(
                    new CityNameResponsePacket(packet.cityId, cityName), 
                    player.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
                );
            }
        });
        context.setPacketHandled(true);
    }
}