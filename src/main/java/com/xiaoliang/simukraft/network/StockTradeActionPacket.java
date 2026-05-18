package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.StockMarketService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;
@SuppressWarnings("null")
public class StockTradeActionPacket {
    private final BlockPos controlBoxPos;
    private final UUID stockCityId;
    private final ActionType actionType;
    private final int quantity;

    public StockTradeActionPacket(BlockPos controlBoxPos, UUID stockCityId, ActionType actionType, int quantity) {
        this.controlBoxPos = controlBoxPos;
        this.stockCityId = stockCityId;
        this.actionType = actionType;
        this.quantity = quantity;
    }

    public StockTradeActionPacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
        this.stockCityId = buf.readUUID();
        this.actionType = buf.readEnum(ActionType.class);
        this.quantity = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeUUID(stockCityId);
        buf.writeEnum(actionType);
        buf.writeVarInt(quantity);
    }

    public static StockTradeActionPacket decode(FriendlyByteBuf buf) {
        return new StockTradeActionPacket(buf);
    }

    public static void handle(StockTradeActionPacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) {
                return;
            }

            switch (message.actionType) {
                case BUY -> StockMarketService.buyShares(player.serverLevel(), player, message.stockCityId, message.quantity);
                case SELL -> StockMarketService.sellShares(player.serverLevel(), player, message.stockCityId, message.controlBoxPos, message.quantity);
            }

            NetworkManager.sendToPlayer(new StockMarketInfoResponsePacket(
                    message.controlBoxPos,
                    StockMarketService.createSnapshot(player.serverLevel(), player, message.controlBoxPos)
            ), player);
        });
        context.get().setPacketHandled(true);
    }

    public enum ActionType {
        BUY,
        SELL
    }
}
