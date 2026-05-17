package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.BankStockMarketScreen;
import com.xiaoliang.simukraft.world.StockMarketData;
import com.xiaoliang.simukraft.world.StockMarketService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class StockMarketInfoResponsePacket {
    private final BlockPos controlBoxPos;
    private final StockMarketService.StockMarketSnapshot snapshot;

    public StockMarketInfoResponsePacket(BlockPos controlBoxPos, StockMarketService.StockMarketSnapshot snapshot) {
        this.controlBoxPos = controlBoxPos;
        this.snapshot = snapshot;
    }

    public StockMarketInfoResponsePacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
        int currentDay = buf.readVarInt();
        double currentPrice = buf.readDouble();
        int ownedShares = buf.readVarInt();
        double playerFunds = buf.readDouble();
        int historySize = buf.readVarInt();
        List<StockMarketData.StockCandle> history = new ArrayList<>(historySize);
        for (int i = 0; i < historySize; i++) {
            history.add(new StockMarketData.StockCandle(
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readVarLong(),
                    buf.readVarInt()
            ));
        }
        this.snapshot = new StockMarketService.StockMarketSnapshot(
                currentDay,
                currentPrice,
                ownedShares,
                playerFunds,
                history
        );
    }
@SuppressWarnings("null")
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeVarInt(snapshot.currentDay());
        buf.writeDouble(snapshot.currentPrice());
        buf.writeVarInt(snapshot.ownedShares());
        buf.writeDouble(snapshot.playerFunds());
        buf.writeVarInt(snapshot.history().size());
        for (StockMarketData.StockCandle candle : snapshot.history()) {
            buf.writeVarInt(candle.day());
            buf.writeDouble(candle.open());
            buf.writeDouble(candle.high());
            buf.writeDouble(candle.low());
            buf.writeDouble(candle.close());
            buf.writeVarLong(candle.volume());
            buf.writeVarInt(candle.dailyChange());
        }
    }

    public static StockMarketInfoResponsePacket decode(FriendlyByteBuf buf) {
        return new StockMarketInfoResponsePacket(buf);
    }

    public static void handle(StockMarketInfoResponsePacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof BankStockMarketScreen screen) {
                screen.updateMarketInfo(message.controlBoxPos, message.snapshot);
            }
        });
        context.get().setPacketHandled(true);
    }
}
