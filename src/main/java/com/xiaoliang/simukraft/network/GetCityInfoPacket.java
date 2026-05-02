package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class GetCityInfoPacket {
    private final BlockPos cityCorePos;

    public GetCityInfoPacket(BlockPos cityCorePos) {
        this.cityCorePos = cityCorePos;
    }

    public GetCityInfoPacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.cityCorePos);
    }

    public static GetCityInfoPacket decode(FriendlyByteBuf buf) {
        return new GetCityInfoPacket(buf);
    }

    public static void handle(GetCityInfoPacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                ServerLevel level = player.serverLevel();
                CityData cityData = CityData.get(level);
                
                // 获取城市信息
                CityData.CityInfo cityInfo = cityData.getCityByCorePos(message.cityCorePos);
                
                if (cityInfo != null) {
                    String cityName = cityInfo.getCityName();
                    int population = cityInfo.getCitizenIds().size();
                    // 获取市长名称，优先使用在线玩家名称，否则使用保存的市长名称
                    String mayorName = cityInfo.getMayorName();
                    // 获取城市等级
                    int cityLevel = cityInfo.getCityLevel();
                    
                    // 如果市长在线，更新市长名称
                    for (ServerPlayer onlinePlayer : level.getServer().getPlayerList().getPlayers()) {
                        if (onlinePlayer.getUUID().equals(cityInfo.getMayorId())) {
                            mayorName = onlinePlayer.getName().getString();
                            // 更新保存的市长名称
                            cityInfo.setMayorName(mayorName);
                            break;
                        }
                    }
                    
                    // 发送响应包给客户端
                    CityInfoResponsePacket responsePacket = new CityInfoResponsePacket(
                        cityName, population, mayorName, message.cityCorePos, cityLevel
                    );
                    NetworkManager.sendToPlayer(responsePacket, player);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}