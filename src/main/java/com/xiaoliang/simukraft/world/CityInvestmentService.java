package com.xiaoliang.simukraft.world;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 城市理财服务
 * 统一管理产品目录、购买校验、到期结算与界面快照。
 */
public final class CityInvestmentService {
    private static final Map<String, InvestmentProductDefinition> PRODUCT_DEFINITIONS = createProductDefinitions();

    private CityInvestmentService() {
    }

    public static CityFinanceSnapshot createFinanceSnapshot(ServerLevel level, CityData.CityInfo cityInfo) {
        CityLoanService.FinanceSnapshot loanSnapshot = CityLoanService.createSnapshot(cityInfo);
        int currentDay = SimukraftWorldData.get(level).getCurrentDay();

        List<InvestmentProductSnapshot> products = PRODUCT_DEFINITIONS.values().stream()
                .map(product -> new InvestmentProductSnapshot(
                        product.id(),
                        product.nameKey(),
                        product.stable(),
                        product.cycleDays(),
                        product.successChance(),
                        product.positiveMinRate(),
                        product.positiveMaxRate(),
                        product.negativeMinRate(),
                        product.negativeMaxRate()
                ))
                .toList();

        List<InvestmentPositionSnapshot> positions = cityInfo.getInvestmentPositions().stream()
                .map(position -> toPositionSnapshot(position, currentDay))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(InvestmentPositionSnapshot::maturityDay))
                .toList();

        return new CityFinanceSnapshot(
                loanSnapshot.cityFunds(),
                loanSnapshot.outstandingDebt(),
                loanSnapshot.maxLoanAmount(),
                loanSnapshot.availableLoanAmount(),
                loanSnapshot.dailyInterestRate(),
                loanSnapshot.cityLevel(),
                currentDay,
                products,
                positions
        );
    }

    public static boolean purchaseProduct(ServerLevel level, ServerPlayer player, CityData cityData,
                                          CityData.CityInfo cityInfo, String productId, double amount) {
        InvestmentProductDefinition product = PRODUCT_DEFINITIONS.get(productId);
        if (product == null) {
            player.sendSystemMessage(translate("message.city_finance.investment.invalid_product"));
            return false;
        }

        double normalizedAmount = roundCurrency(amount);
        if (normalizedAmount <= 0.0D) {
            player.sendSystemMessage(translate("message.city_finance.invalid_amount"));
            return false;
        }
        if (normalizedAmount > cityInfo.getFunds()) {
            player.sendSystemMessage(translate(
                    "message.city_finance.investment.insufficient_funds",
                    formatCurrency(cityInfo.getFunds())
            ));
            return false;
        }

        int currentDay = SimukraftWorldData.get(level).getCurrentDay();
        cityInfo.setFunds(roundCurrency(cityInfo.getFunds() - normalizedAmount));
        cityInfo.addInvestmentPosition(new CityData.CityInfo.InvestmentPosition(
                UUID.randomUUID(),
                product.id(),
                normalizedAmount,
                currentDay
        ));
        cityData.setDirty();
        cityData.syncCityHUDData(cityInfo.getCityId(), level);

        player.sendSystemMessage(translate(
                "message.city_finance.investment.buy.success",
                translate(product.nameKey()),
                formatCurrency(normalizedAmount),
                product.cycleDays()
        ));
        return true;
    }

    public static void settleDailyInvestments(ServerLevel level) {
        CityData cityData = CityData.get(level);
        int currentDay = SimukraftWorldData.get(level).getCurrentDay();
        List<UUID> changedCities = new ArrayList<>();

        for (CityData.CityInfo cityInfo : cityData.getAllCities()) {
            boolean cityChanged = false;
            List<CityData.CityInfo.InvestmentPosition> positions = cityInfo.getInvestmentPositions();
            for (CityData.CityInfo.InvestmentPosition position : positions) {
                InvestmentProductDefinition product = PRODUCT_DEFINITIONS.get(position.productId());
                if (product == null) {
                    if (cityInfo.removeInvestmentPosition(position.positionId())) {
                        cityChanged = true;
                    }
                    continue;
                }

                int maturityDay = position.startDay() + product.cycleDays();
                if (currentDay < maturityDay) {
                    continue;
                }

                double settlementRate = resolveSettlementRate(level, cityInfo.getCityId(), position, product, maturityDay);
                double payout = roundCurrency(position.principal() * (1.0D + settlementRate));
                cityInfo.setFunds(roundCurrency(cityInfo.getFunds() + payout));
                cityInfo.removeInvestmentPosition(position.positionId());
                cityChanged = true;

                notifySettlement(level, cityInfo, product, position.principal(), payout, settlementRate);
            }

            if (cityChanged) {
                changedCities.add(cityInfo.getCityId());
            }
        }

        if (changedCities.isEmpty()) {
            return;
        }

        cityData.setDirty();
        for (UUID cityId : changedCities) {
            cityData.syncCityHUDData(cityId, level);
        }
    }

    @Nullable
    private static InvestmentPositionSnapshot toPositionSnapshot(CityData.CityInfo.InvestmentPosition position, int currentDay) {
        InvestmentProductDefinition product = PRODUCT_DEFINITIONS.get(position.productId());
        if (product == null) {
            return null;
        }

        int maturityDay = position.startDay() + product.cycleDays();
        return new InvestmentPositionSnapshot(
                position.positionId(),
                product.id(),
                product.nameKey(),
                product.stable(),
                roundCurrency(position.principal()),
                position.startDay(),
                maturityDay,
                Math.max(0, maturityDay - currentDay)
        );
    }

    private static void notifySettlement(ServerLevel level, CityData.CityInfo cityInfo, InvestmentProductDefinition product,
                                         double principal, double payout, double settlementRate) {
        MinecraftServer server = level.getServer();
        double netChange = roundCurrency(payout - principal);
        Component message = translate(
                netChange >= 0.0D
                        ? "message.city_finance.investment.settlement_profit"
                        : "message.city_finance.investment.settlement_loss",
                translate(product.nameKey()),
                formatCurrency(principal),
                formatSignedPercent(settlementRate),
                formatCurrency(Math.abs(netChange))
        );

        ServerPlayer mayor = server.getPlayerList().getPlayer(nn(cityInfo.getMayorId()));
        if (mayor != null) {
            mayor.sendSystemMessage(nn(message));
        }
        for (String officialName : cityInfo.getOfficials()) {
            ServerPlayer official = server.getPlayerList().getPlayerByName(nn(officialName));
            if (official != null) {
                official.sendSystemMessage(nn(message));
            }
        }
    }

    private static double resolveSettlementRate(ServerLevel level, UUID cityId, CityData.CityInfo.InvestmentPosition position,
                                                InvestmentProductDefinition product, int maturityDay) {
        long seed = level.getSeed()
                ^ cityId.getMostSignificantBits()
                ^ cityId.getLeastSignificantBits()
                ^ position.positionId().getMostSignificantBits()
                ^ position.positionId().getLeastSignificantBits()
                ^ ((long) position.startDay() * 341873128712L)
                ^ ((long) maturityDay * 132897987541L)
                ^ product.id().hashCode();
        RandomSource random = RandomSource.create(seed);

        if (product.stable()) {
            return roundRate(randomBetween(random, product.positiveMinRate(), product.positiveMaxRate()));
        }

        boolean positiveOutcome = random.nextDouble() < product.successChance();
        double minRate = positiveOutcome ? product.positiveMinRate() : product.negativeMinRate();
        double maxRate = positiveOutcome ? product.positiveMaxRate() : product.negativeMaxRate();
        return roundRate(randomBetween(random, minRate, maxRate));
    }

    private static double randomBetween(RandomSource random, double minValue, double maxValue) {
        if (Math.abs(maxValue - minValue) < 1.0E-9D) {
            return minValue;
        }
        return minValue + (maxValue - minValue) * random.nextDouble();
    }

    private static Map<String, InvestmentProductDefinition> createProductDefinitions() {
        Map<String, InvestmentProductDefinition> definitions = new LinkedHashMap<>();
        addProduct(definitions, new InvestmentProductDefinition(
                "alloy_factory",
                "gui.city_finance.investment.product.alloy_factory",
                true,
                3,
                1.0D,
                0.0001D,
                0.0008D,
                0.0D,
                0.0D
        ));
        addProduct(definitions, new InvestmentProductDefinition(
                "nether_star_industry",
                "gui.city_finance.investment.product.nether_star_industry",
                true,
                5,
                1.0D,
                0.0005D,
                0.0018D,
                0.0D,
                0.0D
        ));
        addProduct(definitions, new InvestmentProductDefinition(
                "oak_technology",
                "gui.city_finance.investment.product.oak_technology",
                true,
                7,
                1.0D,
                0.0010D,
                0.0030D,
                0.0D,
                0.0D
        ));
        addProduct(definitions, new InvestmentProductDefinition(
                "gold_pickaxe_construction",
                "gui.city_finance.investment.product.gold_pickaxe_construction",
                false,
                4,
                0.28D,
                0.30D,
                0.90D,
                -1.00D,
                -2.50D
        ));
        addProduct(definitions, new InvestmentProductDefinition(
                "stick_pharmaceutical",
                "gui.city_finance.investment.product.stick_pharmaceutical",
                false,
                6,
                0.16D,
                0.80D,
                1.60D,
                -1.50D,
                -5.00D
        ));
        addProduct(definitions, new InvestmentProductDefinition(
                "diamond_husbandry",
                "gui.city_finance.investment.product.diamond_husbandry",
                false,
                8,
                0.08D,
                1.50D,
                2.00D,
                -2.00D,
                -10.00D
        ));
        return definitions;
    }

    private static void addProduct(Map<String, InvestmentProductDefinition> definitions, InvestmentProductDefinition product) {
        definitions.put(product.id(), product);
    }

    private static String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", roundCurrency(amount));
    }

    private static String formatSignedPercent(double rate) {
        return String.format(Locale.US, "%+.2f%%", roundRate(rate) * 100.0D);
    }

    private static double roundCurrency(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double roundRate(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    @Nonnull
    private static Component translate(String key, Object... args) {
        return nn(Component.translatable(nn(key), nn(args)));
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    private record InvestmentProductDefinition(
            String id,
            String nameKey,
            boolean stable,
            int cycleDays,
            double successChance,
            double positiveMinRate,
            double positiveMaxRate,
            double negativeMinRate,
            double negativeMaxRate
    ) {
    }

    public record CityFinanceSnapshot(
            double cityFunds,
            double outstandingDebt,
            double maxLoanAmount,
            double availableLoanAmount,
            double dailyInterestRate,
            int cityLevel,
            int currentDay,
            List<InvestmentProductSnapshot> products,
            List<InvestmentPositionSnapshot> positions
    ) {
    }

    public record InvestmentProductSnapshot(
            String productId,
            String nameKey,
            boolean stable,
            int cycleDays,
            double successChance,
            double positiveMinRate,
            double positiveMaxRate,
            double negativeMinRate,
            double negativeMaxRate
    ) {
    }

    public record InvestmentPositionSnapshot(
            UUID positionId,
            String productId,
            String nameKey,
            boolean stable,
            double principal,
            int startDay,
            int maturityDay,
            int remainingDays
    ) {
    }
}
