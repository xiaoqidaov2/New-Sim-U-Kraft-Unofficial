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
import java.util.UUID;
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
        double playerFunds = buf.readDouble();
        UUID currentMarketCityId = buf.readBoolean() ? buf.readUUID() : null;
        String currentMarketCityName = buf.readUtf();
        int marketCount = buf.readVarInt();
        List<StockMarketService.CityStockSnapshot> markets = new ArrayList<>(marketCount);
        for (int i = 0; i < marketCount; i++) {
            UUID cityId = buf.readUUID();
            String cityName = buf.readUtf();
            double currentPrice = buf.readDouble();
            int ownedShares = buf.readVarInt();
            int historySize = buf.readVarInt();
            List<StockMarketData.StockCandle> history = new ArrayList<>(historySize);
            for (int j = 0; j < historySize; j++) {
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
            markets.add(new StockMarketService.CityStockSnapshot(
                    cityId,
                    cityName,
                    currentPrice,
                    ownedShares,
                    history
            ));
        }
        this.snapshot = new StockMarketService.StockMarketSnapshot(
                currentDay,
                playerFunds,
                currentMarketCityId,
                currentMarketCityName,
                markets
        );
    }
@SuppressWarnings("null")
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeVarInt(snapshot.currentDay());
        buf.writeDouble(snapshot.playerFunds());
        buf.writeBoolean(snapshot.currentMarketCityId() != null);
        if (snapshot.currentMarketCityId() != null) {
            buf.writeUUID(snapshot.currentMarketCityId());
        }
        buf.writeUtf(snapshot.currentMarketCityName());
        buf.writeVarInt(snapshot.markets().size());
        for (StockMarketService.CityStockSnapshot market : snapshot.markets()) {
            buf.writeUUID(market.cityId());
            buf.writeUtf(market.cityName());
            buf.writeDouble(market.currentPrice());
            buf.writeVarInt(market.ownedShares());
            buf.writeVarInt(market.history().size());
            for (StockMarketData.StockCandle candle : market.history()) {
                buf.writeVarInt(candle.day());
                buf.writeDouble(candle.open());
                buf.writeDouble(candle.high());
                buf.writeDouble(candle.low());
                buf.writeDouble(candle.close());
                buf.writeVarLong(candle.volume());
                buf.writeVarInt(candle.dailyChange());
            }
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
