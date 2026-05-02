package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.utils.CommercialStorageHelper;
import com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.utils.FileUtils;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 商业建筑购买物品数据包 - 包含库存减少逻辑
 * 支持两种模式：
 * 1. 需要原料的商业建筑：实时从箱子读取原料作为库存，购买时扣除原料
 * 2. 不需要原料的商业建筑：使用传统库存系统
 */
@SuppressWarnings("null")
public class CommercialBuyPacket {
    private final BlockPos controlBoxPos;
    private final String buildingFileName;
    private final Map<String, BuyItemInfo> materials;
    private final double totalPrice;

    /**
     * 购买物品信息
     */
    public static class BuyItemInfo {
        public final int quantity;  // 购买数量（零售模式=个数，批发模式=组数）
        public final boolean retail; // 是否是零售模式

        public BuyItemInfo(int quantity, boolean retail) {
            this.quantity = quantity;
            this.retail = retail;
        }
    }

    public CommercialBuyPacket(BlockPos controlBoxPos, String buildingFileName, Map<String, BuyItemInfo> materials, double totalPrice) {
        this.controlBoxPos = controlBoxPos;
        this.buildingFileName = buildingFileName;
        this.materials = materials;
        this.totalPrice = totalPrice;
    }

    public static void encode(CommercialBuyPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.controlBoxPos);
        buf.writeUtf(packet.buildingFileName);
        buf.writeInt(packet.materials.size());
        for (Map.Entry<String, BuyItemInfo> entry : packet.materials.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeInt(entry.getValue().quantity);
            buf.writeBoolean(entry.getValue().retail);
        }
        buf.writeDouble(packet.totalPrice);
    }

    public static CommercialBuyPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String buildingFileName = buf.readUtf();
        int size = buf.readInt();
        Map<String, BuyItemInfo> materials = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String itemId = buf.readUtf();
            int quantity = buf.readInt();
            boolean retail = buf.readBoolean();
            materials.put(itemId, new BuyItemInfo(quantity, retail));
        }
        double totalPrice = buf.readDouble();
        return new CommercialBuyPacket(pos, buildingFileName, materials, totalPrice);
    }

    public static void handle(CommercialBuyPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) return;

            MinecraftServer server = player.server;
            if (server == null) return;

            ServerLevel level = server.overworld();
            if (level == null) return;

            // 获取建筑配置
            CommercialBuildingConfig config = CommercialBuildingManager.getConfig(packet.buildingFileName);

            if (config == null) {
                player.sendSystemMessage(Component.translatable("message.simukraft.commercial.config_not_found"));
                return;
            }

            // 检查商店模式是否支持购买
            if (config.getShopMode() != CommercialBuildingConfig.ShopMode.PLAYER_SELL &&
                config.getShopMode() != CommercialBuildingConfig.ShopMode.MIXED) {
                player.sendSystemMessage(Component.translatable("message.simukraft.commercial.shop_not_support_buy"));
                return;
            }

            // 加载库存数据
            CommercialHiredData.loadStockData(server);
            CommercialWorkHandler.ensureStockInitialized(packet.controlBoxPos, level, config);

            // 获取当前游戏天数
            long currentDay = level.getDayTime() / 24000L;
            boolean deliverToStoreChest = CommercialStorageHelper.isBuildingMaterialStore(config);
            List<ItemStack> deliveryStacks = new ArrayList<>();

            // 验证库存是否足够
            for (Map.Entry<String, BuyItemInfo> entry : packet.materials.entrySet()) {
                String itemId = entry.getKey();
                BuyItemInfo buyInfo = entry.getValue();
                int requestedAmount = buyInfo.retail ? buyInfo.quantity : buyInfo.quantity * 64;

                // 获取交易配置
                CommercialBuildingConfig.TradeItem trade = config.getTradeByItemId(itemId);
                if (trade == null) {
                    player.sendSystemMessage(Component.translatable("message.simukraft.commercial.item_not_found", itemId));
                    return;
                }

                // 检查是否需要原料
                if (trade.requiresMaterial()) {
                    // 需要原料的模式：实时从箱子读取原料计算库存
                    int availableStock = calculateStockFromMaterials(level, packet.controlBoxPos, trade);
                    
                    // 检查每日销售限制
                    CommercialHiredData.StockInfo stockInfo = CommercialHiredData.getStock(packet.controlBoxPos, itemId);
                    int remainingDailySale = stockInfo != null ? 
                        stockInfo.getRemainingDailySale(currentDay, trade.getMaxStock()) : trade.getMaxStock();
                    
                    if (requestedAmount > remainingDailySale) {
                        player.sendSystemMessage(Component.translatable("message.simukraft.commercial.daily_sale_limit",
                                trade.getItemId(), requestedAmount, remainingDailySale));
                        return;
                    }
                    
                    if (availableStock < requestedAmount) {
                        String unit = buyInfo.retail ? "item" : "stack";
                        player.sendSystemMessage(Component.translatable("message.simukraft.commercial.insufficient_materials",
                                itemId, buyInfo.quantity, unit, availableStock));
                        return;
                    }
                } else {
                    // 不需要原料的模式：使用传统库存系统
                    CommercialHiredData.StockInfo stockInfo = CommercialHiredData.getStock(packet.controlBoxPos, itemId);
                    int currentStock = stockInfo != null ? stockInfo.getCurrentStock() : 0;

                    if (currentStock < requestedAmount) {
                        String unit = buyInfo.retail ? "item" : "stack";
                        player.sendSystemMessage(Component.translatable("message.simukraft.commercial.insufficient_stock",
                                itemId, buyInfo.quantity, unit, currentStock));
                        return;
                    }
                }

                if (deliverToStoreChest) {
                    ItemStack template = parseItemStack(itemId);
                    if (template.isEmpty()) {
                        player.sendSystemMessage(Component.translatable("message.simukraft.commercial.item_not_found", itemId));
                        return;
                    }
                    deliveryStacks.addAll(createDeliveryStacks(template, requestedAmount));
                }
            }

            // 获取城市数据并检查资金
            CityData cityData = CityData.get(level);
            String playerName = player.getName().getString();
            double currentFunds = cityData.getPlayerCityFunds(playerName);

            if (currentFunds < packet.totalPrice) {
                player.sendSystemMessage(Component.translatable("message.simukraft.building_material.insufficient_funds",
                    String.format(Locale.US, "%.2f", currentFunds),
                    String.format(Locale.US, "%.2f", packet.totalPrice)));
                return;
            }

            if (deliverToStoreChest &&
                !CommercialStorageHelper.canStoreItemsInNearbyContainers(level, packet.controlBoxPos, deliveryStacks)) {
                player.sendSystemMessage(Component.translatable("message.simukraft.commercial.no_delivery_space"));
                return;
            }

            // 扣除资金
            double newFunds = currentFunds - packet.totalPrice;
            cityData.setPlayerCityFunds(playerName, newFunds);
            cityData.setDirty();

            // 处理购买
            for (Map.Entry<String, BuyItemInfo> entry : packet.materials.entrySet()) {
                String itemId = entry.getKey();
                BuyItemInfo buyInfo = entry.getValue();
                int quantity = buyInfo.quantity;
                int totalItems = buyInfo.retail ? quantity : quantity * 64;

                // 获取交易配置
                CommercialBuildingConfig.TradeItem trade = config.getTradeByItemId(itemId);
                if (trade == null) continue;

                boolean success;
                if (trade.requiresMaterial()) {
                    // 需要原料的模式：扣除原料并记录销售
                    success = processMaterialPurchase(level, packet.controlBoxPos, trade, totalItems, currentDay);
                } else {
                    // 不需要原料的模式：扣除库存
                    CommercialHiredData.StockInfo stockInfo = CommercialHiredData.getStock(packet.controlBoxPos, itemId);
                    success = stockInfo != null && stockInfo.removeStock(totalItems);
                }

                if (!success) {
                    player.sendSystemMessage(Component.translatable("message.simukraft.commercial.purchase_failed_refund", itemId));
                    continue;
                }

                ItemStack template = parseItemStack(itemId);
                if (template.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("message.simukraft.commercial.purchase_failed_refund", itemId));
                    continue;
                }

                List<ItemStack> purchasedStacks = createDeliveryStacks(template, totalItems);
                if (deliverToStoreChest) {
                    if (!CommercialStorageHelper.storeItemsInNearbyContainers(level, packet.controlBoxPos, purchasedStacks)) {
                        player.sendSystemMessage(Component.translatable("message.simukraft.commercial.purchase_failed_refund", itemId));
                        continue;
                    }
                } else {
                    for (ItemStack stack : purchasedStacks) {
                        spawnItemAtPlayer(player, stack);
                    }
                }
            }

            // 保存库存数据
            CommercialHiredData.saveStockData(server);

            // 发送库存同步包给客户端
            sendStockSync(player, packet.controlBoxPos, server);

            player.sendSystemMessage(Component.translatable("message.simukraft.building_material.purchase_success",
                String.format(Locale.US, "%.2f", packet.totalPrice),
                String.format(Locale.US, "%.2f", newFunds)));
        });

        context.get().setPacketHandled(true);
    }

    /**
     * 从箱子中的原料计算可售库存
     * @param level 服务器世界
     * @param buildingPos 建筑位置
     * @param trade 交易配置
     * @return 可售数量
     */
    private static int calculateStockFromMaterials(ServerLevel level, BlockPos buildingPos, 
                                                   CommercialBuildingConfig.TradeItem trade) {
        if (!trade.requiresMaterial()) {
            return Integer.MAX_VALUE;
        }

        String requiredMaterial = trade.getRequiredMaterial();
        int requiredCount = trade.getRequiredMaterialCount();
        
        ItemStack materialTemplate = parseItemStack(requiredMaterial);
        if (materialTemplate.isEmpty()) {
            return 0;
        }

        // 计算箱子中的原料总数
        int totalMaterials = countMaterialsInNearbyContainers(level, buildingPos, materialTemplate);
        
        // 计算可以生产多少商品
        return totalMaterials / requiredCount;
    }

    /**
     * 处理需要原料的购买
     * @param level 服务器世界
     * @param buildingPos 建筑位置
     * @param trade 交易配置
     * @param amount 购买数量
     * @param currentDay 当前游戏天数
     * @return 是否成功
     */
    private static boolean processMaterialPurchase(ServerLevel level, BlockPos buildingPos,
                                                   CommercialBuildingConfig.TradeItem trade, 
                                                   int amount, long currentDay) {
        if (!trade.requiresMaterial()) {
            return true;
        }

        String requiredMaterial = trade.getRequiredMaterial();
        int requiredCount = trade.getRequiredMaterialCount();
        int totalMaterialsNeeded = amount * requiredCount;

        ItemStack materialTemplate = parseItemStack(requiredMaterial);
        if (materialTemplate.isEmpty()) {
            return false;
        }

        // 检查并更新每日销售记录
        CommercialHiredData.StockInfo stockInfo = CommercialHiredData.getStock(buildingPos, trade.getItemId());
        if (stockInfo == null) {
            stockInfo = new CommercialHiredData.StockInfo(trade.getItemId(), 0, level.getDayTime());
            CommercialHiredData.updateStock(buildingPos, trade.getItemId(), 0, level.getDayTime());
        }

        if (!stockInfo.checkAndUpdateDailySale(currentDay, amount, trade.getMaxStock())) {
            return false; // 超过每日销售限制
        }

        // 扣除原料
        int consumed = consumeMaterialsFromNearbyContainers(level, buildingPos, materialTemplate, totalMaterialsNeeded);
        return consumed >= totalMaterialsNeeded;
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

    private static List<ItemStack> createDeliveryStacks(ItemStack template, int totalItems) {
        List<ItemStack> stacks = new ArrayList<>();
        if (template.isEmpty() || totalItems <= 0) {
            return stacks;
        }

        int remaining = totalItems;
        while (remaining > 0) {
            int stackSize = Math.min(template.getMaxStackSize(), remaining);
            ItemStack stack = template.copy();
            stack.setCount(stackSize);
            stacks.add(stack);
            remaining -= stackSize;
        }
        return stacks;
    }

    /**
     * 在玩家脚下生成物品实体
     * @param player 玩家
     * @param stack 物品堆
     */
    private static void spawnItemAtPlayer(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;

        // 获取玩家位置
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        // 创建物品实体
        net.minecraft.world.entity.item.ItemEntity itemEntity = new net.minecraft.world.entity.item.ItemEntity(
            player.level(),
            x,
            y,
            z,
            stack.copy()
        );

        // 设置拾取延迟（让玩家有时间看到物品掉落）
        itemEntity.setPickUpDelay(10);

        // 添加到世界中
        player.level().addFreshEntity(itemEntity);
    }

    /**
     * 发送库存同步包给客户端
     * 对于需要原料的商品，实时计算箱子中的原料库存
     */
    private static void sendStockSync(ServerPlayer player, BlockPos pos, MinecraftServer server) {
        CommercialHiredData.loadStockData(server);
        ServerLevel level = server.overworld();
        if (level == null) return;

        // 获取建筑配置
        String buildingFileName = FileUtils.readCommercialBuildingFileNameCached(server, pos);
        CommercialBuildingConfig config = CommercialBuildingManager.getConfig(buildingFileName);

        Map<String, CommercialHiredData.StockInfo> stockMap = CommercialHiredData.getAllStockAtPos(pos);
        Map<String, SyncBuildingMaterialStockPacket.StockData> syncMap = new HashMap<>();

        if (stockMap != null) {
            for (Map.Entry<String, CommercialHiredData.StockInfo> entry : stockMap.entrySet()) {
                CommercialHiredData.StockInfo stock = entry.getValue();
                int currentStock = stock.getCurrentStock();

                // 如果需要原料，实时计算箱子中的原料库存
                if (config != null) {
                    CommercialBuildingConfig.TradeItem trade = config.getTradeByItemId(stock.getItemId());
                    if (trade != null && trade.requiresMaterial()) {
                        currentStock = calculateStockFromMaterials(level, pos, trade);
                    }
                }

                syncMap.put(entry.getKey(), new SyncBuildingMaterialStockPacket.StockData(
                    stock.getItemId(),
                    currentStock,
                    stock.getMaxStock() > 0 ? stock.getMaxStock() : currentStock,
                    64,
                    stock.getLastRestockTime()
                ));
            }
        }

        // 同步配置中的交易物品（可能没有库存记录的）
        if (config != null) {
            for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
                if (!syncMap.containsKey(trade.getItemId())) {
                    int currentStock = 0;
                    if (trade.requiresMaterial()) {
                        currentStock = calculateStockFromMaterials(level, pos, trade);
                    }
                    syncMap.put(trade.getItemId(), new SyncBuildingMaterialStockPacket.StockData(
                        trade.getItemId(),
                        currentStock,
                        trade.getMaxStock(),
                        trade.getRestockAmount(),
                        level.getDayTime()
                    ));
                }
            }
        }

        if (syncMap.isEmpty()) return;

        SyncBuildingMaterialStockPacket packet = new SyncBuildingMaterialStockPacket(
            pos,
            syncMap,
            level.getDayTime()
        );

        NetworkManager.INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
