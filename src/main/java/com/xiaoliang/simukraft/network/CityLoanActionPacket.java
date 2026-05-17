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
public class CityLoanActionPacket {
    private final BlockPos cityCorePos;
    private final ActionType actionType;
    private final double amount;

    public CityLoanActionPacket(BlockPos cityCorePos, ActionType actionType, double amount) {
        this.cityCorePos = cityCorePos;
        this.actionType = actionType;
        this.amount = amount;
    }

    public CityLoanActionPacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
        this.actionType = buf.readEnum(ActionType.class);
        this.amount = buf.readDouble();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cityCorePos);
        buf.writeEnum(actionType);
        buf.writeDouble(amount);
    }

    public static CityLoanActionPacket decode(FriendlyByteBuf buf) {
        return new CityLoanActionPacket(buf);
    }

    public static void handle(CityLoanActionPacket message, Supplier<NetworkEvent.Context> context) {
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

            switch (message.actionType) {
                case BORROW -> CityLoanService.borrow(level, player, cityData, cityInfo, message.amount);
                case REPAY -> CityLoanService.repay(level, player, cityData, cityInfo, message.amount);
                case REPAY_ALL_CURRENT_FUNDS -> CityLoanService.repayAllWithCurrentFunds(level, player, cityData, cityInfo);
            }

            NetworkManager.sendToPlayer(new CityFinanceInfoResponsePacket(
                    message.cityCorePos,
                    CityLoanService.createSnapshot(cityInfo)
            ), player);
        });
        context.get().setPacketHandled(true);
    }

    public enum ActionType {
        BORROW,
        REPAY,
        REPAY_ALL_CURRENT_FUNDS
    }
}
