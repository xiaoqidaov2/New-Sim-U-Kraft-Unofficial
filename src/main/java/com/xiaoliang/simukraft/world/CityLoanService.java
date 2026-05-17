package com.xiaoliang.simukraft.world;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 统一处理城市贷款额度、利息和还款逻辑，避免把财务规则散落到 GUI 和数据包中。
 */
public final class CityLoanService {
    private static final double BASE_LOAN_UNIT = 10.0;
    private static final double DAILY_INTEREST_INCREMENT_PER_STAGE = 0.002D;

    private CityLoanService() {
    }

    public static double getMaxLoanAmount(CityData.CityInfo cityInfo) {
        int loanStage = getLoanStage(cityInfo);
        return roundToCurrency(BASE_LOAN_UNIT * loanStage * loanStage);
    }

    public static double getDailyInterestRate(CityData.CityInfo cityInfo) {
        return roundRate(getLoanStage(cityInfo) * DAILY_INTEREST_INCREMENT_PER_STAGE);
    }

    public static double getAvailableLoanAmount(CityData.CityInfo cityInfo) {
        return roundToCurrency(Math.max(0.0D, getMaxLoanAmount(cityInfo) - cityInfo.getOutstandingLoanDebt()));
    }

    public static FinanceSnapshot createSnapshot(CityData.CityInfo cityInfo) {
        return new FinanceSnapshot(
                roundToCurrency(cityInfo.getFunds()),
                roundToCurrency(cityInfo.getOutstandingLoanDebt()),
                getMaxLoanAmount(cityInfo),
                getAvailableLoanAmount(cityInfo),
                getDailyInterestRate(cityInfo),
                cityInfo.getCityLevel()
        );
    }

    public static boolean borrow(ServerLevel level, ServerPlayer player, CityData cityData, CityData.CityInfo cityInfo, double amount) {
        double normalizedAmount = roundToCurrency(amount);
        if (normalizedAmount <= 0.0D) {
            player.sendSystemMessage(Component.translatable("message.city_finance.invalid_amount"));
            return false;
        }

        double availableLoanAmount = getAvailableLoanAmount(cityInfo);
        if (availableLoanAmount <= 0.0D) {
            player.sendSystemMessage(Component.translatable("message.city_finance.no_available_quota"));
            return false;
        }
        if (normalizedAmount > availableLoanAmount) {
            player.sendSystemMessage(Component.translatable(
                    "message.city_finance.borrow.exceeds_limit",
                    formatCurrency(availableLoanAmount)
            ));
            return false;
        }

        cityInfo.setFunds(roundToCurrency(cityInfo.getFunds() + normalizedAmount));
        cityInfo.setOutstandingLoanDebt(roundToCurrency(cityInfo.getOutstandingLoanDebt() + normalizedAmount));
        cityData.setDirty();
        cityData.syncCityHUDData(cityInfo.getCityId(), level);

        player.sendSystemMessage(Component.translatable(
                "message.city_finance.borrow.success",
                formatCurrency(normalizedAmount),
                formatCurrency(cityInfo.getOutstandingLoanDebt())
        ));
        return true;
    }

    public static boolean repay(ServerLevel level, ServerPlayer player, CityData cityData, CityData.CityInfo cityInfo, double requestedAmount) {
        double normalizedAmount = roundToCurrency(requestedAmount);
        if (normalizedAmount <= 0.0D) {
            player.sendSystemMessage(Component.translatable("message.city_finance.invalid_amount"));
            return false;
        }
        if (cityInfo.getOutstandingLoanDebt() <= 0.0D) {
            player.sendSystemMessage(Component.translatable("message.city_finance.repay.no_debt"));
            return false;
        }
        if (cityInfo.getFunds() <= 0.0D) {
            player.sendSystemMessage(Component.translatable("message.city_finance.repay.no_funds"));
            return false;
        }
        if (normalizedAmount > cityInfo.getFunds()) {
            player.sendSystemMessage(Component.translatable(
                    "message.city_finance.repay.insufficient_funds",
                    formatCurrency(cityInfo.getFunds())
            ));
            return false;
        }

        double actualRepayment = roundToCurrency(Math.min(normalizedAmount, cityInfo.getOutstandingLoanDebt()));
        cityInfo.setFunds(roundToCurrency(cityInfo.getFunds() - actualRepayment));
        cityInfo.setOutstandingLoanDebt(roundToCurrency(cityInfo.getOutstandingLoanDebt() - actualRepayment));
        cityData.setDirty();
        cityData.syncCityHUDData(cityInfo.getCityId(), level);

        player.sendSystemMessage(Component.translatable(
                "message.city_finance.repay.success",
                formatCurrency(actualRepayment),
                formatCurrency(cityInfo.getOutstandingLoanDebt())
        ));
        return true;
    }

    public static boolean repayAllWithCurrentFunds(ServerLevel level, ServerPlayer player, CityData cityData, CityData.CityInfo cityInfo) {
        double repaymentAmount = roundToCurrency(Math.min(cityInfo.getFunds(), cityInfo.getOutstandingLoanDebt()));
        if (repaymentAmount <= 0.0D) {
            if (cityInfo.getOutstandingLoanDebt() <= 0.0D) {
                player.sendSystemMessage(Component.translatable("message.city_finance.repay.no_debt"));
            } else {
                player.sendSystemMessage(Component.translatable("message.city_finance.repay.no_funds"));
            }
            return false;
        }
        return repay(level, player, cityData, cityInfo, repaymentAmount);
    }

    public static void applyDailyInterest(ServerLevel level) {
        CityData cityData = CityData.get(level);
        boolean changed = false;
        for (CityData.CityInfo cityInfo : cityData.getAllCities()) {
            double currentDebt = cityInfo.getOutstandingLoanDebt();
            if (currentDebt <= 0.0D) {
                continue;
            }

            double interest = roundToCurrency(currentDebt * getDailyInterestRate(cityInfo));
            if (interest <= 0.0D) {
                continue;
            }

            cityInfo.setOutstandingLoanDebt(roundToCurrency(currentDebt + interest));
            changed = true;
        }

        if (changed) {
            cityData.setDirty();
        }
    }

    private static int getLoanStage(CityData.CityInfo cityInfo) {
        // 城市等级从0开始，为避免0级完全没有额度，这里按阶段号（等级+1）计算贷款规则。
        return Math.max(1, cityInfo.getCityLevel() + 1);
    }

    private static String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", roundToCurrency(amount));
    }

    private static double roundToCurrency(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double roundRate(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public record FinanceSnapshot(
            double cityFunds,
            double outstandingDebt,
            double maxLoanAmount,
            double availableLoanAmount,
            double dailyInterestRate,
            int cityLevel
    ) {
    }
}
