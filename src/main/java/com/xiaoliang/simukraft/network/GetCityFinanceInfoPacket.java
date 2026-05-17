package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CityLoanService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class GetCityFinanceInfoPacket {
    private final BlockPos cityCorePos;

    public GetCityFinanceInfoPacket(BlockPos cityCorePos) {
        this.cityCorePos = cityCorePos;
    }

    public GetCityFinanceInfoPacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cityCorePos);
    }

    public static GetCityFinanceInfoPacket decode(FriendlyByteBuf buf) {
        return new GetCityFinanceInfoPacket(buf);
    }

    public static void handle(GetCityFinanceInfoPacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) {
                return;
            }

            ServerLevel level = player.serverLevel();
            CityData cityData = CityData.get(level);
            CityData.CityInfo cityInfo = cityData.getCityByCorePos(message.cityCorePos);
            if (cityInfo == null) {
                player.sendSystemMessage(Component.translatable("message.city_finance.city_not_found"));
                return;
            }

            String playerName = player.getGameProfile().getName();
            if (!cityInfo.canManageCity(playerName)) {
                player.sendSystemMessage(Component.translatable("message.city_finance.no_permission"));
                return;
            }

            NetworkManager.sendToPlayer(new CityFinanceInfoResponsePacket(
                    message.cityCorePos,
                    CityLoanService.createSnapshot(cityInfo)
            ), player);
        });
        context.get().setPacketHandled(true);
    }
}
