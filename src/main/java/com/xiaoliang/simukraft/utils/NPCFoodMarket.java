package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public final class NPCFoodMarket {
    private static final int MAX_DISTANCE = 256;

    // NPC交易税记录：城市ID -> 税额
    private static final Map<UUID, Double> npcTradeTaxByCity = new ConcurrentHashMap<>();
    private static long lastTaxRecordDay = -1;

    private NPCFoodMarket() {}

    public record PurchasePlan(BlockPos shopPos, String buildingFileName, String itemId, int nutrition, double pricePerItem) {}

    @Nullable
    public static PurchasePlan findPurchasePlan(ServerLevel level, CustomEntity npc) {
        MinecraftServer server = level.getServer();

        UUID cityId = npc.getCityId();
        if (cityId == null) return null;

        BlockPos npcPos = npc.blockPosition();

        try {
            Map<BlockPos, CommercialHiredData.CommercialHireInfo> hires = CommercialHiredData.loadHiredEmployees(server);
            if (hires.isEmpty()) return null;

            return hires.values().stream()
                    .filter(Objects::nonNull)
                    .map(hire -> findBestFoodInShop(level, npc, hire))
                    .filter(Objects::nonNull)
                    .min(Comparator.comparingDouble(plan -> score(npcPos, plan)))
                    .orElse(null);
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCFoodMarket] 查找NPC购买食物目标失败: {}", npc.getFullName(), e);
            return null;
        }
    }

    @Nullable
    private static PurchasePlan findBestFoodInShop(ServerLevel level, CustomEntity npc, CommercialHiredData.CommercialHireInfo hire) {
        if (hire.getPosition() == null) return null;
        if (hire.getBuildingFileName() == null || hire.getBuildingFileName().isEmpty()) return null;

        BlockPos shopPos = hire.getPosition();
        if (npc.blockPosition().distManhattan(Objects.requireNonNull(shopPos)) > MAX_DISTANCE) return null;

        CommercialBuildingConfig config = CommercialBuildingManager.getConfig(hire.getBuildingFileName());
        if (config == null) return null;

        CommercialBuildingConfig.ShopMode mode = config.getShopMode();
        if (mode != CommercialBuildingConfig.ShopMode.NPC_SELL && mode != CommercialBuildingConfig.ShopMode.MIXED) {
            return null;
        }

        long dayTime = level.getDayTime();
        if (!config.isWorkTime(dayTime)) return null;

        var itemRegistry = level.registryAccess().registry(Objects.requireNonNull(Registries.ITEM)).orElse(null);
        if (itemRegistry == null) return null;

        PurchasePlan best = null;
        double bestRatio = Double.MAX_VALUE;

        for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
            if (trade == null) continue;
            if (!trade.isRetail()) continue;
            if (trade.getSellPrice() <= 0) continue;

            String itemId = Objects.requireNonNull(trade.getItemId());
            ResourceLocation itemLocation = ResourceLocation.tryParse(itemId);
            Item item = itemLocation == null ? null : itemRegistry.getOptional(itemLocation).orElse(null);
            if (item == null) continue;

            var stack = item.getDefaultInstance();
            if (!stack.isEdible()) continue;

            FoodProperties food = stack.getFoodProperties(npc);
            if (food == null || food.getNutrition() <= 0) continue;

            double ratio = trade.getSellPrice() / food.getNutrition();
            if (ratio < bestRatio) {
                bestRatio = ratio;
                best = new PurchasePlan(shopPos, hire.getBuildingFileName(), trade.getItemId(), food.getNutrition(), trade.getSellPrice());
            }
        }

        return best;
    }

    private static double score(BlockPos npcPos, PurchasePlan plan) {
        double dist = Math.sqrt(npcPos.distSqr(Objects.requireNonNull(plan.shopPos())));
        return plan.pricePerItem() / Math.max(1, plan.nutrition()) + dist * 0.01;
    }

    public static String getTravelStatusLabel(@Nullable PurchasePlan plan) {
        return "gui.npc.status.going_to_buy_food";
    }

    public static String getBuyingStatusLabel(@Nullable PurchasePlan plan) {
        return "gui.npc.status.buying_food";
    }

    public static String getFoodDetailKey(@Nullable PurchasePlan plan) {
        if (plan == null) {
            return "";
        }

        ResourceLocation itemId = ResourceLocation.tryParse(Objects.requireNonNull(plan.itemId()));
        if (itemId == null) {
            return "";
        }
        return "item." + itemId.getNamespace() + "." + itemId.getPath().replace('/', '.');
    }

    public static boolean isFoodStatusLabel(@Nullable String label) {
        return "gui.npc.status.going_to_buy_food".equals(label)
                || "gui.npc.status.buying_food".equals(label);
    }

    @Nullable
    public static ItemStack tryPurchaseFood(ServerLevel level, CustomEntity npc, PurchasePlan plan) {
        MinecraftServer server = level.getServer();

        try {
            CommercialBuildingConfig config = CommercialBuildingManager.getConfig(plan.buildingFileName());
            if (config == null) return ItemStack.EMPTY;

            CommercialBuildingConfig.ShopMode mode = config.getShopMode();
            if (mode != CommercialBuildingConfig.ShopMode.NPC_SELL && mode != CommercialBuildingConfig.ShopMode.MIXED) {
                return ItemStack.EMPTY;
            }

            long dayTime = level.getDayTime();
            if (!config.isWorkTime(dayTime)) return ItemStack.EMPTY;

            UUID cityId = npc.getCityId();
            if (cityId == null) return ItemStack.EMPTY;

            CityData cityData = CityData.get(server.overworld());
            CityData.CityInfo city = cityData.getCity(cityId);
            if (city == null) return ItemStack.EMPTY;

            double funds = city.getFunds();
            if (funds < plan.pricePerItem()) return ItemStack.EMPTY;

            // 检查库存（支持实时库存系统）
            CommercialBuildingConfig tradeConfig = CommercialBuildingManager.getConfig(plan.buildingFileName());
            CommercialBuildingConfig.TradeItem trade = tradeConfig != null ? tradeConfig.getTradeByItemId(plan.itemId()) : null;

            int availableStock;
            if (trade != null && trade.requiresMaterial()) {
                // 需要原料的商品：实时从箱子计算库存
                availableStock = calculateStockFromMaterials(level, plan.shopPos(), trade);
            } else {
                // 不需要原料的商品：使用传统库存系统
                CommercialHiredData.loadStockData(server);
                CommercialHiredData.StockInfo stock = CommercialHiredData.getStock(plan.shopPos(), plan.itemId());
                availableStock = stock != null ? stock.getCurrentStock() : 0;
            }

            if (availableStock < 1) return ItemStack.EMPTY;

            // 扣除库存（需要原料的商品从箱子扣除，不需要的从库存扣除）
            if (trade != null && trade.requiresMaterial()) {
                for (CommercialBuildingConfig.MaterialRequirement requirement : trade.getRequiredMaterials()) {
                    ItemStack materialTemplate = parseItemStack(requirement.getItemId());
                    if (materialTemplate.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                    int consumed = consumeMaterialsFromNearbyContainers(level, plan.shopPos(), materialTemplate, requirement.getCount());
                    if (consumed < requirement.getCount()) {
                        return ItemStack.EMPTY;
                    }
                }
            } else {
                // 从传统库存扣除
                CommercialHiredData.StockInfo stock = CommercialHiredData.getStock(plan.shopPos(), plan.itemId());
                if (stock == null || !stock.removeStock(1)) return ItemStack.EMPTY;
            }

            // NPC购买不扣除城市资金，但记录企业税（营业额的40%）
            double businessTax = round2(plan.pricePerItem() * 0.4);
            recordNPCTradeTax(level, cityId, plan.shopPos(), businessTax);

            CommercialHiredData.saveStockData(server);

            // 生成物品实体（像玩家购买一样）
            var itemRegistry = level.registryAccess().registry(Registries.ITEM).orElse(null);
            if (itemRegistry == null) return ItemStack.EMPTY;

            Item item = itemRegistry.getOptional(ResourceLocation.tryParse(plan.itemId())).orElse(null);
            if (item == null) return ItemStack.EMPTY;

            ItemStack foodStack = new ItemStack(item, 1);
            FoodProperties foodProperties = foodStack.getFoodProperties(npc);
            int nutrition = foodProperties != null ? foodProperties.getNutrition() : 0;
            if (nutrition <= 0) {
                return ItemStack.EMPTY;
            }

            npc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, foodStack.copy());
            return foodStack;
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCFoodMarket] NPC购买食物失败: npc={}, item={}, shop={}", npc.getFullName(), plan.itemId(), plan.shopPos(), e);
            return ItemStack.EMPTY;
        }
    }

    public static void finishPurchasedMeal(ServerLevel level, CustomEntity npc, @Nullable PurchasePlan plan) {
        if (level == null || npc == null) {
            return;
        }

        npc.setHunger(20);
        if (plan != null) {
            NPCVoiceManager.playFoodVoice(level, npc, plan.buildingFileName());
        }

        level.playSound(
                null,
                Objects.requireNonNull(npc.blockPosition()),
                Objects.requireNonNull(SoundEvents.GENERIC_EAT),
                SoundSource.NEUTRAL,
                0.8f,
                1.0f
        );
        npc.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        npc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static double round2(double v) {
        return Double.parseDouble(String.format(Locale.US, "%.2f", v));
    }

    /**
     * 从箱子中的原料计算可售库存
     */
    private static int calculateStockFromMaterials(ServerLevel level, BlockPos buildingPos,
                                                   CommercialBuildingConfig.TradeItem trade) {
        if (!trade.requiresMaterial()) {
            return Integer.MAX_VALUE;
        }

        List<CommercialBuildingConfig.MaterialRequirement> requiredMaterials = trade.getRequiredMaterials();
        if (requiredMaterials.isEmpty()) {
            return Integer.MAX_VALUE;
        }

        int maxProducible = Integer.MAX_VALUE;
        for (CommercialBuildingConfig.MaterialRequirement requirement : requiredMaterials) {
            if (requirement.getCount() <= 0) {
                continue;
            }
            ItemStack materialTemplate = parseItemStack(requirement.getItemId());
            if (materialTemplate.isEmpty()) {
                return 0;
            }
            int totalMaterials = countMaterialsInNearbyContainers(level, buildingPos, materialTemplate);
            maxProducible = Math.min(maxProducible, totalMaterials / requirement.getCount());
        }
        return maxProducible == Integer.MAX_VALUE ? 0 : maxProducible;
    }

    /**
     * 计算附近箱子中的原料数量
     * 搜索范围：以 centerPos 为中心，水平半径5格，垂直半径2格
     */
    private static int countMaterialsInNearbyContainers(ServerLevel level, BlockPos centerPos, ItemStack itemTemplate) {
        if (level == null || centerPos == null || itemTemplate.isEmpty()) {
            return 0;
        }

        return ContainerUtils.executeOnMainThread(level, () -> {
            int totalCount = 0;
            // 搜索范围：水平半径5格，垂直半径2格
            for (int dx = -5; dx <= 5; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        BlockPos checkPos = centerPos.offset(dx, dy, dz);
                        if (ContainerUtils.isContainer(level, checkPos)) {
                            totalCount += ContainerUtils.countItem(level, checkPos, itemTemplate);
                        }
                    }
                }
            }
            return totalCount;
        });
    }

    /**
     * 从附近箱子中扣除原料
     * 搜索范围：以 centerPos 为中心，水平半径5格，垂直半径2格
     */
    private static int consumeMaterialsFromNearbyContainers(ServerLevel level, BlockPos centerPos,
                                                            ItemStack itemTemplate, int amount) {
        if (level == null || centerPos == null || itemTemplate.isEmpty() || amount <= 0) {
            return 0;
        }

        return ContainerUtils.executeOnMainThread(level, () -> {
            int remaining = amount;
            int consumed = 0;

            // 搜索范围：水平半径5格，垂直半径2格
            for (int dx = -5; dx <= 5 && remaining > 0; dx++) {
                for (int dy = -2; dy <= 2 && remaining > 0; dy++) {
                    for (int dz = -5; dz <= 5 && remaining > 0; dz++) {
                        BlockPos checkPos = centerPos.offset(dx, dy, dz);
                        if (!ContainerUtils.isContainer(level, checkPos)) {
                            continue;
                        }

                        int available = ContainerUtils.countItem(level, checkPos, itemTemplate);
                        if (available <= 0) {
                            continue;
                        }

                        int toConsume = Math.min(available, remaining);
                        ItemStack consumeStack = itemTemplate.copy();
                        consumeStack.setCount(toConsume);

                        if (ContainerUtils.consumeItem(level, checkPos, consumeStack)) {
                            remaining -= toConsume;
                            consumed += toConsume;
                        }
                    }
                }
            }
            return consumed;
        });
    }

    /**
     * 解析物品ID为ItemStack
     */
    private static ItemStack parseItemStack(String itemId) {
        try {
            net.minecraft.resources.ResourceLocation resourceLocation =
                net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (resourceLocation == null) return ItemStack.EMPTY;

            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(resourceLocation);
            if (item == null) return ItemStack.EMPTY;

            return new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 记录NPC交易税
     */
    private static void recordNPCTradeTax(ServerLevel level, UUID cityId, BlockPos shopPos, double taxAmount) {
        long currentDay = level.getDayTime() / 24000L;

        // 新的一天，清空记录
        if (currentDay != lastTaxRecordDay) {
            npcTradeTaxByCity.clear();
            lastTaxRecordDay = currentDay;
        }

        // 累加税额
        npcTradeTaxByCity.merge(cityId, taxAmount, Double::sum);

        Simukraft.LOGGER.debug("[NPCFoodMarket] 记录NPC交易税: cityId={}, shopPos={}, tax={}", cityId, shopPos, taxAmount);
    }

    /**
     * 获取指定城市的NPC交易税总额（每日结算后自动清零）
     */
    public static double getAndClearNPCTradeTax(UUID cityId) {
        Double tax = npcTradeTaxByCity.get(cityId);
        if (tax != null) {
            npcTradeTaxByCity.remove(cityId);
            return round2(tax);
        }
        return 0.0;
    }

    /**
     * 获取所有城市的NPC交易税（用于每日结算）
     */
    public static Map<UUID, Double> getAllNPCTradeTaxes() {
        Map<UUID, Double> result = new ConcurrentHashMap<>(npcTradeTaxByCity);
        npcTradeTaxByCity.clear();
        return result;
    }
}
