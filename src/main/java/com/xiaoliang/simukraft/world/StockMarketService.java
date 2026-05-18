package com.xiaoliang.simukraft.world;

import com.xiaoliang.simukraft.utils.MoneyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@SuppressWarnings("null")
/**
 * 股票市场服务
 * 统一处理行情快照与买卖校验，避免数据包和界面直接操作存档。
 */
public final class StockMarketService {
    private static final int MAX_TRADE_QUANTITY = 100000;

    private StockMarketService() {
    }

    public static StockMarketSnapshot createSnapshot(ServerLevel level, ServerPlayer player, BlockPos controlBoxPos) {
        StockMarketData data = prepareData(level);
        CityData cityData = CityData.get(level);
        UUID currentMarketCityId = resolveCurrentMarketCityId(level, player, controlBoxPos);
        CityData.CityInfo currentMarketCity = currentMarketCityId == null ? null : cityData.getCity(currentMarketCityId);

        List<CityStockSnapshot> markets = new ArrayList<>();
        List<CityData.CityInfo> sortedCities = new ArrayList<>(cityData.getAllCities());
        sortedCities.sort(Comparator
                .comparing((CityData.CityInfo city) -> !city.getCityId().equals(currentMarketCityId))
                .thenComparing(CityData.CityInfo::getCityName, String.CASE_INSENSITIVE_ORDER));

        for (CityData.CityInfo city : sortedCities) {
            UUID cityId = city.getCityId();
            markets.add(new CityStockSnapshot(
                    cityId,
                    city.getCityName(),
                    roundCurrency(data.getCurrentPrice(cityId)),
                    data.getHolding(cityId, player.getUUID()),
                    data.getHistorySnapshot(cityId)
            ));
        }

        return new StockMarketSnapshot(
                SimukraftWorldData.get(level).getCurrentDay(),
                roundCurrency(MoneyManager.getMoney(player)),
                currentMarketCityId,
                currentMarketCity == null ? "" : currentMarketCity.getCityName(),
                markets
        );
    }

    public static boolean buyShares(ServerLevel level, ServerPlayer player, UUID stockCityId, int quantity) {
        if (!isQuantityValid(player, quantity)) {
            return false;
        }

        CityData.CityInfo stockCity = CityData.get(level).getCity(stockCityId);
        if (stockCity == null) {
            player.sendSystemMessage(Component.translatable("message.stock_market.city_not_found"));
            return false;
        }

        StockMarketData data = prepareData(level);
        double currentPrice = roundCurrency(data.getCurrentPrice(stockCityId));
        double totalCost = roundCurrency(currentPrice * quantity);
        if (!MoneyManager.deductMoney(player, totalCost)) {
            player.sendSystemMessage(Component.translatable(
                    "message.stock_market.buy.insufficient_funds",
                    formatCurrency(totalCost)
            ));
            return false;
        }

        int currentHolding = data.getHolding(stockCityId, player.getUUID());
        data.setHolding(stockCityId, player.getUUID(), currentHolding + quantity);
        data.recordTradeVolume(stockCityId, quantity);
        player.sendSystemMessage(Component.translatable(
                "message.stock_market.buy.success",
                stockCity.getCityName(),
                quantity,
                formatCurrency(currentPrice),
                formatCurrency(totalCost)
        ));
        return true;
    }

    public static boolean sellShares(ServerLevel level, ServerPlayer player, UUID stockCityId, BlockPos controlBoxPos, int quantity) {
        if (!isQuantityValid(player, quantity)) {
            return false;
        }

        CityData cityData = CityData.get(level);
        CityData.CityInfo stockCity = cityData.getCity(stockCityId);
        if (stockCity == null) {
            player.sendSystemMessage(Component.translatable("message.stock_market.city_not_found"));
            return false;
        }

        UUID currentMarketCityId = resolveCurrentMarketCityId(level, player, controlBoxPos);
        if (!Objects.equals(currentMarketCityId, stockCityId)) {
            String currentCityName = currentMarketCityId == null
                    ? "-"
                    : resolveCityName(cityData.getCity(currentMarketCityId));
            player.sendSystemMessage(Component.translatable(
                    "message.stock_market.sell.wrong_city",
                    stockCity.getCityName(),
                    currentCityName
            ));
            return false;
        }

        StockMarketData data = prepareData(level);
        int currentHolding = data.getHolding(stockCityId, player.getUUID());
        if (currentHolding < quantity) {
            player.sendSystemMessage(Component.translatable(
                    "message.stock_market.sell.insufficient_shares",
                    currentHolding
            ));
            return false;
        }

        double currentPrice = roundCurrency(data.getCurrentPrice(stockCityId));
        double totalIncome = roundCurrency(currentPrice * quantity);
        data.setHolding(stockCityId, player.getUUID(), currentHolding - quantity);
        data.recordTradeVolume(stockCityId, quantity);
        MoneyManager.addMoney(player, totalIncome);
        player.sendSystemMessage(Component.translatable(
                "message.stock_market.sell.success",
                stockCity.getCityName(),
                quantity,
                formatCurrency(currentPrice),
                formatCurrency(totalIncome)
        ));
        return true;
    }

    public static void refreshDailyMarket(ServerLevel level) {
        prepareData(level);
    }

    private static boolean isQuantityValid(ServerPlayer player, int quantity) {
        if (quantity <= 0) {
            player.sendSystemMessage(Component.translatable("message.stock_market.invalid_quantity"));
            return false;
        }
        if (quantity > MAX_TRADE_QUANTITY) {
            player.sendSystemMessage(Component.translatable(
                    "message.stock_market.quantity_too_large",
                    MAX_TRADE_QUANTITY
            ));
            return false;
        }
        return true;
    }

    private static StockMarketData prepareData(ServerLevel level) {
        StockMarketData data = StockMarketData.get(level);
        data.advanceToDay(level, SimukraftWorldData.get(level).getCurrentDay(), CityData.get(level).getAllCities());
        return data;
    }

    private static UUID resolveCurrentMarketCityId(ServerLevel level, ServerPlayer player, BlockPos controlBoxPos) {
        if (controlBoxPos != null) {
            UUID byChunk = CityChunkData.get(level).getChunkOwner(new ChunkPos(controlBoxPos).toLong());
            if (byChunk != null) {
                return byChunk;
            }
        }

        CityData cityData = CityData.get(level);
        UUID playerCityId = cityData.refreshPlayerCityAccess(player);
        if (playerCityId == null) {
            playerCityId = cityData.getPlayerCityIdByName(player.getGameProfile().getName());
        }
        return playerCityId;
    }

    private static String resolveCityName(CityData.CityInfo city) {
        return city == null ? "-" : city.getCityName();
    }

    private static String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", roundCurrency(amount));
    }

    private static double roundCurrency(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public record StockMarketSnapshot(
            int currentDay,
            double playerFunds,
            UUID currentMarketCityId,
            String currentMarketCityName,
            List<CityStockSnapshot> markets
    ) {
    }

    public record CityStockSnapshot(
            UUID cityId,
            String cityName,
            double currentPrice,
            int ownedShares,
            List<StockMarketData.StockCandle> history
    ) {
    }
}
