package com.xiaoliang.simukraft.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
@SuppressWarnings("null")
/**
 * 股票市场世界存档
 * 负责持久化每日K线、成交量和玩家持股数量。
 */
public class StockMarketData extends SavedData {
    private static final String DATA_NAME = "simukraft_stock_market";
    private static final int MAX_HISTORY_DAYS = 30;
    private static final double BASE_PRICE = 1.0D;
    private static final double MIN_PRICE = 0.1D;
    private static final double PRICE_STEP = 0.1D;

    private final CopyOnWriteArrayList<StockCandle> history = new CopyOnWriteArrayList<>();
    private final Map<UUID, Integer> holdings = new ConcurrentHashMap<>();
    private int lastProcessedDay;
    private double currentPrice = BASE_PRICE;

    public synchronized void advanceToDay(ServerLevel level, int targetDay) {
        if (targetDay <= 0) {
            return;
        }

        if (lastProcessedDay <= 0) {
            addInitialCandle(targetDay);
        }

        while (lastProcessedDay < targetDay) {
            generateNextCandle(level, lastProcessedDay + 1);
        }
    }

    private void addInitialCandle(int day) {
        history.clear();
        history.add(new StockCandle(day, BASE_PRICE, BASE_PRICE, BASE_PRICE, BASE_PRICE, 100L, 0));
        lastProcessedDay = day;
        currentPrice = BASE_PRICE;
        setDirty();
    }

    private void generateNextCandle(ServerLevel level, int day) {
        RandomSource random = RandomSource.create(level.getSeed() ^ (day * 341873128712L));
        int dailyChange = random.nextInt(21) - 10;
        double open = roundCurrency(currentPrice);
        double close = Math.max(MIN_PRICE, roundCurrency(open + dailyChange * PRICE_STEP));
        double high = roundCurrency(Math.max(open, close) + random.nextInt(4) * 0.05D);
        double low = Math.max(MIN_PRICE, roundCurrency(Math.min(open, close) - random.nextInt(4) * 0.05D));
        long volume = 100L + Math.abs(dailyChange) * 80L + random.nextInt(160);

        history.add(new StockCandle(day, open, high, low, close, volume, dailyChange));
        trimHistory();

        currentPrice = close;
        lastProcessedDay = day;
        setDirty();
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY_DAYS) {
            history.remove(0);
        }
    }

    public synchronized void recordTradeVolume(int quantity) {
        if (quantity <= 0 || history.isEmpty()) {
            return;
        }

        int lastIndex = history.size() - 1;
        StockCandle current = history.get(lastIndex);
        history.set(lastIndex, new StockCandle(
                current.day(),
                current.open(),
                current.high(),
                current.low(),
                current.close(),
                current.volume() + quantity,
                current.dailyChange()
        ));
        setDirty();
    }

    public synchronized List<StockCandle> getHistorySnapshot() {
        return new ArrayList<>(history);
    }

    public synchronized double getCurrentPrice() {
        return currentPrice;
    }

    public synchronized int getLastProcessedDay() {
        return lastProcessedDay;
    }

    public synchronized int getHolding(UUID playerId) {
        return holdings.getOrDefault(playerId, 0);
    }

    public synchronized void setHolding(UUID playerId, int shares) {
        if (shares <= 0) {
            holdings.remove(playerId);
        } else {
            holdings.put(playerId, shares);
        }
        setDirty();
    }

    @Override
    public CompoundTag save(@Nonnull CompoundTag tag) {
        tag.putInt("lastProcessedDay", lastProcessedDay);
        tag.putDouble("currentPrice", currentPrice);

        ListTag historyList = new ListTag();
        for (StockCandle candle : history) {
            CompoundTag candleTag = new CompoundTag();
            candleTag.putInt("day", candle.day());
            candleTag.putDouble("open", candle.open());
            candleTag.putDouble("high", candle.high());
            candleTag.putDouble("low", candle.low());
            candleTag.putDouble("close", candle.close());
            candleTag.putLong("volume", candle.volume());
            candleTag.putInt("dailyChange", candle.dailyChange());
            historyList.add(candleTag);
        }
        tag.put("history", historyList);

        ListTag holdingsList = new ListTag();
        for (Map.Entry<UUID, Integer> entry : holdings.entrySet()) {
            CompoundTag holdingTag = new CompoundTag();
            holdingTag.putUUID("playerId", entry.getKey());
            holdingTag.putInt("shares", entry.getValue());
            holdingsList.add(holdingTag);
        }
        tag.put("holdings", holdingsList);
        return tag;
    }

    public static StockMarketData load(CompoundTag tag) {
        StockMarketData data = new StockMarketData();
        data.lastProcessedDay = tag.getInt("lastProcessedDay");
        data.currentPrice = tag.contains("currentPrice") ? tag.getDouble("currentPrice") : BASE_PRICE;

        ListTag historyList = tag.getList("history", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < historyList.size(); i++) {
            CompoundTag candleTag = historyList.getCompound(i);
            data.history.add(new StockCandle(
                    candleTag.getInt("day"),
                    candleTag.getDouble("open"),
                    candleTag.getDouble("high"),
                    candleTag.getDouble("low"),
                    candleTag.getDouble("close"),
                    candleTag.getLong("volume"),
                    candleTag.getInt("dailyChange")
            ));
        }

        ListTag holdingsList = tag.getList("holdings", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < holdingsList.size(); i++) {
            CompoundTag holdingTag = holdingsList.getCompound(i);
            if (holdingTag.hasUUID("playerId")) {
                data.holdings.put(holdingTag.getUUID("playerId"), holdingTag.getInt("shares"));
            }
        }

        if (data.history.isEmpty()) {
            data.currentPrice = BASE_PRICE;
        }
        return data;
    }

    public static StockMarketData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        DimensionDataStorage storage = overworld.getDataStorage();
        return storage.computeIfAbsent(StockMarketData::load, StockMarketData::new, DATA_NAME);
    }

    private static double roundCurrency(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public record StockCandle(
            int day,
            double open,
            double high,
            double low,
            double close,
            long volume,
            int dailyChange
    ) {
    }
}
