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
public class BankLoanActionPacket {
    private final BlockPos controlBoxPos;
    private final ActionType actionType;
    private final double amount;

    public BankLoanActionPacket(BlockPos controlBoxPos, ActionType actionType, double amount) {
        this.controlBoxPos = controlBoxPos;
        this.actionType = actionType;
        this.amount = amount;
    }

    public BankLoanActionPacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
        this.actionType = buf.readEnum(ActionType.class);
        this.amount = buf.readDouble();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeEnum(actionType);
        buf.writeDouble(amount);
    }

    public static BankLoanActionPacket decode(FriendlyByteBuf buf) {
        return new BankLoanActionPacket(buf);
    }

    public static void handle(BankLoanActionPacket message, Supplier<NetworkEvent.Context> context) {
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

            switch (message.actionType) {
                case BORROW -> CityLoanService.borrow(level, player, cityData, cityInfo, message.amount);
                case REPAY -> CityLoanService.repay(level, player, cityData, cityInfo, message.amount);
                case REPAY_ALL_CURRENT_FUNDS -> CityLoanService.repayAllWithCurrentFunds(level, player, cityData, cityInfo);
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

    public enum ActionType {
        BORROW,
        REPAY,
        REPAY_ALL_CURRENT_FUNDS
    }
}
