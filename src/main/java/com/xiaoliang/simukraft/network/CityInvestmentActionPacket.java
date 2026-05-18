package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CityInvestmentService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityInvestmentActionPacket {
    private final BlockPos cityCorePos;
    private final String productId;
    private final double amount;

    public CityInvestmentActionPacket(BlockPos cityCorePos, String productId, double amount) {
        this.cityCorePos = cityCorePos;
        this.productId = productId;
        this.amount = amount;
    }

    public CityInvestmentActionPacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
        this.productId = buf.readUtf(64);
        this.amount = buf.readDouble();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cityCorePos);
        buf.writeUtf(productId, 64);
        buf.writeDouble(amount);
    }

    public static CityInvestmentActionPacket decode(FriendlyByteBuf buf) {
        return new CityInvestmentActionPacket(buf);
    }

    public static void handle(CityInvestmentActionPacket message, Supplier<NetworkEvent.Context> context) {
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

            CityInvestmentService.purchaseProduct(level, player, cityData, cityInfo, message.productId, message.amount);
            NetworkManager.sendToPlayer(new CityFinanceInfoResponsePacket(
                    message.cityCorePos,
                    CityInvestmentService.createFinanceSnapshot(level, cityInfo)
            ), player);
        });
        context.get().setPacketHandled(true);
    }
}
