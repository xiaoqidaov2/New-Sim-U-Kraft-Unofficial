package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class GetCityLevelPacket {
    private final BlockPos cityCorePos;

    public GetCityLevelPacket(BlockPos cityCorePos) {
        this.cityCorePos = cityCorePos;
    }

    public GetCityLevelPacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.cityCorePos);
    }

    public static GetCityLevelPacket decode(FriendlyByteBuf buf) {
        return new GetCityLevelPacket(buf);
    }

    public static void handle(GetCityLevelPacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                ServerLevel level = player.serverLevel();
                CityData cityData = CityData.get(level);
                
                // 获取城市信息
                CityData.CityInfo cityInfo = cityData.getCityByCorePos(message.cityCorePos);
                
                if (cityInfo != null) {
                    // 发送城市等级响应包给客户端
                    CityLevelResponsePacket responsePacket = new CityLevelResponsePacket(
                        message.cityCorePos, cityInfo.getCityLevel()
                    );
                    NetworkManager.sendToPlayer(responsePacket, player);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
