package com.xiaoliang.simukraft.world;

import com.xiaoliang.simukraft.utils.MoneyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
@SuppressWarnings("null")
/**
 * 股票市场服务
 * 统一处理行情快照与买卖校验，避免数据包和界面直接操作存档。
 */
public final class StockMarketService {
    private static final int MAX_TRADE_QUANTITY = 100000;

    private StockMarketService() {
    }

    public static StockMarketSnapshot createSnapshot(ServerLevel level, ServerPlayer player) {
        StockMarketData data = prepareData(level);
        return new StockMarketSnapshot(
                SimukraftWorldData.get(level).getCurrentDay(),
                roundCurrency(data.getCurrentPrice()),
                data.getHolding(player.getUUID()),
                roundCurrency(MoneyManager.getMoney(player)),
                data.getHistorySnapshot()
        );
    }

    public static boolean buyShares(ServerLevel level, ServerPlayer player, int quantity) {
        if (!isQuantityValid(player, quantity)) {
            return false;
        }

        StockMarketData data = prepareData(level);
        double currentPrice = roundCurrency(data.getCurrentPrice());
        double totalCost = roundCurrency(currentPrice * quantity);
        if (!MoneyManager.deductMoney(player, totalCost)) {
            player.sendSystemMessage(Component.translatable(
                    "message.stock_market.buy.insufficient_funds",
                    formatCurrency(totalCost)
            ));
            return false;
        }

        int currentHolding = data.getHolding(player.getUUID());
        data.setHolding(player.getUUID(), currentHolding + quantity);
        data.recordTradeVolume(quantity);
        player.sendSystemMessage(Component.translatable(
                "message.stock_market.buy.success",
                quantity,
                formatCurrency(currentPrice),
                formatCurrency(totalCost)
        ));
        return true;
    }

    public static boolean sellShares(ServerLevel level, ServerPlayer player, int quantity) {
        if (!isQuantityValid(player, quantity)) {
            return false;
        }

        StockMarketData data = prepareData(level);
        int currentHolding = data.getHolding(player.getUUID());
        if (currentHolding < quantity) {
            player.sendSystemMessage(Component.translatable(
                    "message.stock_market.sell.insufficient_shares",
                    currentHolding
            ));
            return false;
        }

        double currentPrice = roundCurrency(data.getCurrentPrice());
        double totalIncome = roundCurrency(currentPrice * quantity);
        data.setHolding(player.getUUID(), currentHolding - quantity);
        data.recordTradeVolume(quantity);
        MoneyManager.addMoney(player, totalIncome);
        player.sendSystemMessage(Component.translatable(
                "message.stock_market.sell.success",
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
        data.advanceToDay(level, SimukraftWorldData.get(level).getCurrentDay());
        return data;
    }

    private static String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", roundCurrency(amount));
    }

    private static double roundCurrency(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public record StockMarketSnapshot(
            int currentDay,
            double currentPrice,
            int ownedShares,
            double playerFunds,
            List<StockMarketData.StockCandle> history
    ) {
    }
}
