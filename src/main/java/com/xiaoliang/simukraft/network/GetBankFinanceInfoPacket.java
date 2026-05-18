package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CityLoanService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class GetBankFinanceInfoPacket {
    private final BlockPos controlBoxPos;

    public GetBankFinanceInfoPacket(BlockPos controlBoxPos) {
        this.controlBoxPos = controlBoxPos;
    }

    public GetBankFinanceInfoPacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
    }

    public static GetBankFinanceInfoPacket decode(FriendlyByteBuf buf) {
        return new GetBankFinanceInfoPacket(buf);
    }

    public static void handle(GetBankFinanceInfoPacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) {
                return;
            }

            ServerLevel level = player.serverLevel();
            CityData cityData = CityData.get(level);
            CityData.CityInfo cityInfo = resolveManagedCity(cityData, player);
            if (cityInfo == null) {
                player.sendSystemMessage(Component.translatable("message.city_finance.city_not_found"));
                return;
            }

            String playerName = player.getGameProfile().getName();
            if (!cityInfo.canManageCity(playerName)) {
                player.sendSystemMessage(Component.translatable("message.city_finance.no_permission"));
                return;
            }

            NetworkManager.sendToPlayer(new BankFinanceInfoResponsePacket(
                    message.controlBoxPos,
                    CityLoanService.createSnapshot(cityInfo)
            ), player);
        });
        context.get().setPacketHandled(true);
    }

    private static CityData.CityInfo resolveManagedCity(CityData cityData, ServerPlayer player) {
        if (cityData == null || player == null) {
            return null;
        }
        UUID cityId = cityData.refreshPlayerCityAccess(player);
        if (cityId == null) {
            cityId = cityData.getPlayerCityIdByName(player.getGameProfile().getName());
        }
        return cityId != null ? cityData.getCity(cityId) : null;
    }
}
