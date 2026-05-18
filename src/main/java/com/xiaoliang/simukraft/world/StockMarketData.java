package com.xiaoliang.simukraft.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings("null")
/**
 * 股票市场世界存档
 * 负责持久化多城市股票的每日K线、成交量和玩家分城市持股数量。
 */
public class StockMarketData extends SavedData {
    private static final String DATA_NAME = "simukraft_stock_market";
    private static final int MAX_HISTORY_DAYS = 50;
    private static final double BASE_PRICE = 1.0D;
    private static final double MIN_PRICE = 0.1D;
    private static final double PRICE_STEP = 0.1D;

    private final Map<UUID, CityStockEntry> cityMarkets = new ConcurrentHashMap<>();
    @Nullable
    private LegacySingleMarketData legacySingleMarketData;

    public synchronized void advanceToDay(ServerLevel level, int targetDay, Collection<CityData.CityInfo> cities) {
        if (targetDay <= 0 || cities == null || cities.isEmpty()) {
            return;
        }

        List<CityData.CityInfo> sortedCities = new ArrayList<>(cities);
        sortedCities.sort(Comparator.comparing(CityData.CityInfo::getCityName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(city -> city.getCityId().toString()));

        for (CityData.CityInfo city : sortedCities) {
            CityStockEntry market = cityMarkets.get(city.getCityId());
            if (market == null) {
                market = createMarketForCity(city.getCityId());
            }
            market.advanceToDay(level, targetDay, city.getCityId());
        }
    }

    @Nonnull
    public synchronized List<UUID> getMarketCityIds() {
        return new ArrayList<>(cityMarkets.keySet());
    }

    public synchronized boolean hasMarket(UUID cityId) {
        return cityMarkets.containsKey(cityId);
    }

    public synchronized double getCurrentPrice(UUID cityId) {
        CityStockEntry entry = cityMarkets.get(cityId);
        return entry == null ? BASE_PRICE : entry.getCurrentPrice();
    }

    @Nonnull
    public synchronized List<StockCandle> getHistorySnapshot(UUID cityId) {
        CityStockEntry entry = cityMarkets.get(cityId);
        return entry == null ? List.of() : entry.getHistorySnapshot();
    }

    public synchronized int getHolding(UUID cityId, UUID playerId) {
        CityStockEntry entry = cityMarkets.get(cityId);
        return entry == null ? 0 : entry.getHolding(playerId);
    }

    public synchronized void setHolding(UUID cityId, UUID playerId, int shares) {
        createMarketForCity(cityId).setHolding(playerId, shares);
        setDirty();
    }

    public synchronized void recordTradeVolume(UUID cityId, int quantity) {
        createMarketForCity(cityId).recordTradeVolume(quantity);
        setDirty();
    }

    @Override
    public synchronized CompoundTag save(@Nonnull CompoundTag tag) {
        ListTag marketsTag = new ListTag();
        for (Map.Entry<UUID, CityStockEntry> entry : cityMarkets.entrySet()) {
            CompoundTag cityTag = entry.getValue().save(entry.getKey());
            marketsTag.add(cityTag);
        }
        tag.put("cityMarkets", marketsTag);
        return tag;
    }

    public static StockMarketData load(CompoundTag tag) {
        StockMarketData data = new StockMarketData();
        if (tag.contains("cityMarkets", CompoundTag.TAG_LIST)) {
            ListTag marketsTag = tag.getList("cityMarkets", CompoundTag.TAG_COMPOUND);
            for (int i = 0; i < marketsTag.size(); i++) {
                CompoundTag marketTag = marketsTag.getCompound(i);
                if (marketTag.hasUUID("cityId")) {
                    data.cityMarkets.put(marketTag.getUUID("cityId"), CityStockEntry.load(marketTag));
                }
            }
            return data;
        }

        if (tag.contains("history", CompoundTag.TAG_LIST) || tag.contains("holdings", CompoundTag.TAG_LIST)) {
            data.legacySingleMarketData = LegacySingleMarketData.load(tag);
        }
        return data;
    }

    public static StockMarketData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        DimensionDataStorage storage = overworld.getDataStorage();
        return storage.computeIfAbsent(StockMarketData::load, StockMarketData::new, DATA_NAME);
    }

    @Nonnull
    private CityStockEntry createMarketForCity(@Nonnull UUID cityId) {
        CityStockEntry existing = cityMarkets.get(cityId);
        if (existing != null) {
            return existing;
        }

        CityStockEntry created;
        if (legacySingleMarketData != null) {
            created = CityStockEntry.fromLegacy(legacySingleMarketData);
            legacySingleMarketData = null;
            setDirty();
        } else {
            created = new CityStockEntry();
        }
        cityMarkets.put(cityId, created);
        return created;
    }

    private static double roundCurrency(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static final class CityStockEntry {
        private final CopyOnWriteArrayList<StockCandle> history = new CopyOnWriteArrayList<>();
        private final Map<UUID, Integer> holdings = new ConcurrentHashMap<>();
        private int lastProcessedDay;
        private double currentPrice = BASE_PRICE;

        private void advanceToDay(ServerLevel level, int targetDay, UUID cityId) {
            if (lastProcessedDay <= 0) {
                addInitialCandle(targetDay);
            }

            while (lastProcessedDay < targetDay) {
                generateNextCandle(level, lastProcessedDay + 1, cityId);
            }
        }

        private void addInitialCandle(int day) {
            history.clear();
            history.add(new StockCandle(day, BASE_PRICE, BASE_PRICE, BASE_PRICE, BASE_PRICE, 100L, 0));
            lastProcessedDay = day;
            currentPrice = BASE_PRICE;
        }

        private void generateNextCandle(ServerLevel level, int day, UUID cityId) {
            long citySeed = cityId.getMostSignificantBits() ^ cityId.getLeastSignificantBits();
            RandomSource random = RandomSource.create(level.getSeed() ^ citySeed ^ (day * 341873128712L));
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
        }

        private void trimHistory() {
            while (history.size() > MAX_HISTORY_DAYS) {
                history.remove(0);
            }
        }

        private void recordTradeVolume(int quantity) {
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
        }

        @Nonnull
        private List<StockCandle> getHistorySnapshot() {
            return new ArrayList<>(history);
        }

        private double getCurrentPrice() {
            return currentPrice;
        }

        private int getHolding(UUID playerId) {
            return holdings.getOrDefault(playerId, 0);
        }

        private void setHolding(UUID playerId, int shares) {
            if (shares <= 0) {
                holdings.remove(playerId);
            } else {
                holdings.put(playerId, shares);
            }
        }

        private CompoundTag save(UUID cityId) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("cityId", cityId);
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

        private static CityStockEntry load(CompoundTag tag) {
            CityStockEntry entry = new CityStockEntry();
            entry.lastProcessedDay = tag.getInt("lastProcessedDay");
            entry.currentPrice = tag.contains("currentPrice") ? tag.getDouble("currentPrice") : BASE_PRICE;

            ListTag historyList = tag.getList("history", CompoundTag.TAG_COMPOUND);
            for (int i = 0; i < historyList.size(); i++) {
                CompoundTag candleTag = historyList.getCompound(i);
                entry.history.add(new StockCandle(
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
                    entry.holdings.put(holdingTag.getUUID("playerId"), holdingTag.getInt("shares"));
                }
            }

            if (entry.history.isEmpty()) {
                entry.currentPrice = BASE_PRICE;
            }
            return entry;
        }

        private static CityStockEntry fromLegacy(LegacySingleMarketData legacyData) {
            CityStockEntry entry = new CityStockEntry();
            entry.lastProcessedDay = legacyData.lastProcessedDay();
            entry.currentPrice = legacyData.currentPrice();
            entry.history.addAll(legacyData.history());
            entry.holdings.putAll(legacyData.holdings());
            if (entry.history.isEmpty()) {
                entry.currentPrice = BASE_PRICE;
            }
            return entry;
        }
    }

    private record LegacySingleMarketData(
            int lastProcessedDay,
            double currentPrice,
            @Nonnull List<StockCandle> history,
            @Nonnull Map<UUID, Integer> holdings
    ) {
        private static LegacySingleMarketData load(CompoundTag tag) {
            int lastProcessedDay = tag.getInt("lastProcessedDay");
            double currentPrice = tag.contains("currentPrice") ? tag.getDouble("currentPrice") : BASE_PRICE;
            List<StockCandle> history = new ArrayList<>();
            ListTag historyList = tag.getList("history", CompoundTag.TAG_COMPOUND);
            for (int i = 0; i < historyList.size(); i++) {
                CompoundTag candleTag = historyList.getCompound(i);
                history.add(new StockCandle(
                        candleTag.getInt("day"),
                        candleTag.getDouble("open"),
                        candleTag.getDouble("high"),
                        candleTag.getDouble("low"),
                        candleTag.getDouble("close"),
                        candleTag.getLong("volume"),
                        candleTag.getInt("dailyChange")
                ));
            }

            Map<UUID, Integer> holdings = new ConcurrentHashMap<>();
            ListTag holdingsList = tag.getList("holdings", CompoundTag.TAG_COMPOUND);
            for (int i = 0; i < holdingsList.size(); i++) {
                CompoundTag holdingTag = holdingsList.getCompound(i);
                if (holdingTag.hasUUID("playerId")) {
                    holdings.put(holdingTag.getUUID("playerId"), holdingTag.getInt("shares"));
                }
            }
            return new LegacySingleMarketData(lastProcessedDay, currentPrice, history, holdings);
        }
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
