package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class CheckCityStatusPacket {
    private final BlockPos cityCorePos;

    public CheckCityStatusPacket(BlockPos cityCorePos) {
        this.cityCorePos = cityCorePos;
    }

    public CheckCityStatusPacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.cityCorePos);
    }

    public static CheckCityStatusPacket decode(FriendlyByteBuf buf) {
        return new CheckCityStatusPacket(buf);
    }

    public static void handle(CheckCityStatusPacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                ServerLevel level = player.serverLevel();
                CityData cityData = CityData.get(level);
                String playerName = player.getGameProfile().getName();
                UUID playerUUID = player.getUUID();
                UUID playerCityId = cityData.refreshPlayerCityAccess(player);
                
                // 检查当前城市核心位置是否已经有城市
                CityData.CityInfo cityInfo = cityData.getCityByCorePos(message.cityCorePos);
                boolean hasData = cityInfo != null;
                
                if (hasData) {
                    // 城市已存在
                    boolean isMayor = cityInfo.isMayor(playerUUID);
                    boolean isOfficial = cityInfo.isOfficial(playerName);
                    boolean canManage = isMayor || isOfficial; // 市长或官员都可以管理
                    String cityName = cityInfo.getCityName();
                    int population = cityInfo.getCitizenIds().size();
                    // 获取市长名称，优先使用在线玩家名称，否则使用保存的市长名称
                    String mayorName = cityInfo.getMayorName();

                    // 如果市长在线，更新市长名称
                    for (ServerPlayer onlinePlayer : level.getServer().getPlayerList().getPlayers()) {
                        if (onlinePlayer.getUUID().equals(cityInfo.getMayorId())) {
                            mayorName = onlinePlayer.getName().getString();
                            // 更新保存的市长名称
                            cityInfo.setMayorName(mayorName);
                            break;
                        }
                    }

                    // 登录自修复后直接使用修复结果，避免旧映射导致界面误判
                    boolean hasCity = playerCityId != null;

                    // 发送响应包给客户端（官员也可以管理城市）
                    CityStatusResponsePacket responsePacket = new CityStatusResponsePacket(hasCity, canManage, hasData, message.cityCorePos, cityName, population, mayorName);
                    NetworkManager.sendToPlayer(responsePacket, player);
                } else {
                    // 城市不存在时也沿用登录自修复结果
                    boolean hasCity = playerCityId != null;

                    // 发送响应包给客户端
                    CityStatusResponsePacket responsePacket = new CityStatusResponsePacket(hasCity, false, hasData, message.cityCorePos, "", 0, "");
                    NetworkManager.sendToPlayer(responsePacket, player);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
