package com.xiaoliang.simukraft.job.jobs.commercialgeneric;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.notification.MessageCategory;
import com.xiaoliang.simukraft.notification.MessageNotification;
import com.xiaoliang.simukraft.notification.NotificationServiceManager;
import com.xiaoliang.simukraft.utils.CityMessageUtils;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.utils.NPCDataManager;
import com.xiaoliang.simukraft.utils.NPCRestHandler;
import com.xiaoliang.simukraft.utils.NPCWorkResumeCoordinator;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商业建筑工作处理器
 * 统一处理所有商业建筑的工作逻辑、库存管理和交易处理
 * 完全配置化，无硬编码
 */
public class CommercialWorkHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    // 存储每个商业建筑的上次补货时间
    private static final Map<BlockPos, Long> lastRestockTime = new ConcurrentHashMap<>();
    // 记录每天是否已处理开工逻辑（到岗 + 取料）
    private static final Map<BlockPos, Long> lastShiftStartDay = new ConcurrentHashMap<>();
    // 记录每天是否已处理下班逻辑，避免夜间重复切换状态
    private static final Map<BlockPos, Long> lastShiftEndDay = new ConcurrentHashMap<>();
    // 记录当天是否已满足开售原料前置条件
    private static final Map<BlockPos, Long> preparedMaterialsDay = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> recentlyRestoredCommercialNpcs = new ConcurrentHashMap<>();
    private static final long RESTORE_SHIFT_END_PROTECTION_TICKS = 100L;
    private static final Map<Path, String> BUILDING_FILE_NAME_CACHE = new ConcurrentHashMap<>();
    private static boolean dataInitialized = false;

    // 性能优化：添加tick冷却，避免每tick都检查
    private static long lastCheckTick = 0;
    private static final long CHECK_INTERVAL = 20; // 每秒检查一次（20 ticks）
    
    // 记录上次游戏时间，用于检测时间跳跃（如/time set指令）
    private static long lastGameTime = -1;
    /**
     * 服务器启动时的初始化方法
     */
    public static void onServerStart(MinecraftServer server) {
        if (server == null) return;

        // 重置初始化状态，确保数据重新加载
        dataInitialized = false;
        lastRestockTime.clear();
        lastShiftStartDay.clear();
        lastShiftEndDay.clear();
        preparedMaterialsDay.clear();
        recentlyRestoredCommercialNpcs.clear();
        BUILDING_FILE_NAME_CACHE.clear();

        // 初始化商业建筑配置管理器
        CommercialBuildingManager.init(server);

        // 加载库存数据
        CommercialHiredData.loadStockData(server);

    }

    /**
     * 处理商业建筑补货（按补货时间点）
     */
    public static void handleRestock(ServerLevel level, long dayTime) {
        if (level == null) return;

        // 性能优化：不再每 tick 加载/保存数据，数据已在 onServerStart 加载并在内存中维护
        // 从 CommercialHiredData 获取所有雇佣数据
        Map<BlockPos, CommercialHiredData.CommercialHireInfo> hiredEmployees = CommercialHiredData.loadHiredEmployees(level.getServer());

        boolean anyRestocked = false;
        // 遍历所有雇佣的商业建筑
        for (Map.Entry<BlockPos, CommercialHiredData.CommercialHireInfo> entry : hiredEmployees.entrySet()) {
            BlockPos buildingPos = entry.getKey();
            CommercialHiredData.CommercialHireInfo hireInfo = entry.getValue();

            // 获取建筑配置
            String buildingFileName = hireInfo.getBuildingFileName();
            CommercialBuildingConfig config = CommercialBuildingManager.getConfig(buildingFileName);
            if (config == null) {
                continue;
            }

            // 检查是否到达补货时间点
            int restockTime = config.getRestockTime();
            if (dayTime == restockTime) {
                // 执行补货
                processRestock(buildingPos, level, config);
                anyRestocked = true;
            }
        }

        // 也检查有库存数据但没有雇佣NPC的建筑（如PLAYER_SELL模式）
        for (BlockPos buildingPos : CommercialHiredData.getAllStockPositions()) {
            if (hiredEmployees.containsKey(buildingPos)) {
                continue; // 已处理过
            }

            // 尝试从建筑位置获取配置
            CommercialBuildingConfig config = findConfigForPosition(buildingPos, level);
            if (config == null) {
                continue;
            }

            // 检查是否到达补货时间点
            int restockTime = config.getRestockTime();
            if (dayTime == restockTime) {
                // 执行补货
                processRestock(buildingPos, level, config);
                anyRestocked = true;
            }
        }

        // 只有在实际补货后才保存数据，避免每 tick 无谓的 IO
        if (anyRestocked) {
            CommercialHiredData.saveStockData(level.getServer());
        }
    }

    /**
     * 根据建筑位置查找配置
     */
    private static CommercialBuildingConfig findConfigForPosition(BlockPos pos, ServerLevel level) {
        // 尝试从库存数据中获取物品ID，然后匹配配置
        Map<String, CommercialHiredData.StockInfo> stockInfoMap = CommercialHiredData.getAllStockForPos(pos);
        if (stockInfoMap.isEmpty()) {
            return null;
        }

        // 获取第一个物品的ID，尝试匹配配置
        String itemId = stockInfoMap.keySet().iterator().next();

        // 遍历所有配置，查找包含该物品的配置
        for (CommercialBuildingConfig config : CommercialBuildingManager.getAllConfigs()) {
            for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
                if (trade.getItemId().equals(itemId)) {
                    return config;
                }
            }
        }

        return null;
    }

    /**
     * 处理商业建筑的每日工作
     */
    public static void handleDailyWork(ServerLevel level) {
        if (level == null) return;

        // 性能优化：检查tick冷却
        long gameTime = level.getDayTime();
        
        // 检测时间是否被调回（如/time set指令）- 必须在冷却检查之前
        boolean timeReset = lastGameTime >= 0 && gameTime < lastGameTime;
        if (timeReset) {
            lastShiftStartDay.clear();
            preparedMaterialsDay.clear();
            lastCheckTick = 0; // 重置冷却时间，确保本次能执行
        }
        lastGameTime = gameTime;
        
        if (gameTime - lastCheckTick < CHECK_INTERVAL) {
            return; // 冷却中，跳过检查
        }
        lastCheckTick = gameTime;

        // 确保数据已加载
        if (!dataInitialized) {
            dataInitialized = true;
        }

        // 性能优化：不再每 tick 加载/保存数据，数据已在 onServerStart 加载并在内存中维护
        // 从 CommercialHiredData 获取所有雇佣数据
        Map<BlockPos, CommercialHiredData.CommercialHireInfo> hiredEmployees = CommercialHiredData.loadHiredEmployees(level.getServer());

        boolean anyChange = false;
        // 遍历所有雇佣的商业建筑
        for (Map.Entry<BlockPos, CommercialHiredData.CommercialHireInfo> entry : hiredEmployees.entrySet()) {
            BlockPos buildingPos = entry.getKey();
            CommercialHiredData.CommercialHireInfo hireInfo = entry.getValue();

            // 获取建筑配置
            String buildingFileName = hireInfo.getBuildingFileName();
            CommercialBuildingConfig config = CommercialBuildingManager.getConfig(buildingFileName);
            if (config == null) {
                continue;
            }

            // 查找实际的NPC实体
            CustomEntity npc = CommercialHiredData.findNPCByUuid(level.getServer(), hireInfo.getNpcUuid());

            if (npc != null && npc.isAlive()) {
                // 检查是否在工作时间内
                if (config.isWorkTime(gameTime)) {
                    // 开工时间边沿触发：NPC到岗 + 从输入建筑取料入库
                    handleShiftStart(npc, buildingPos, level, config, gameTime);

                    // 根据商店模式调用不同的处理方法
                    switch (config.getShopMode()) {
                        case NPC_SELL:
                            handleNPCSellMode(npc, buildingPos, level, config);
                            break;
                        case PLAYER_SELL:
                            handlePlayerSellMode(buildingPos, level, config);
                            break;
                        case MIXED:
                            handleMixedMode(npc, buildingPos, level, config);
                            break;
                    }
                    anyChange = true;
                } else {
                    handleShiftEnd(npc, buildingPos, level, config, gameTime);
                }
            } else {
                // NPC 不存在或已死亡，但 PLAYER_SELL 模式仍然需要执行补货
                if (config.getShopMode() == CommercialBuildingConfig.ShopMode.PLAYER_SELL) {
                    handlePlayerSellMode(buildingPos, level, config);
                    anyChange = true;
                }
            }
        }

        // 只有在数据可能发生变化时才保存
        if (anyChange) {
            CommercialHiredData.saveStockData(level.getServer());
        }

        // 清理过期的记录（每天开始时清理）
        if (gameTime % 24000 < 100) {
            lastRestockTime.clear();
        }
    }

    /**
     * 处理NPC出售模式
     * NPC向商店出售物品，商店支付货币
     * 从NPC背包获取物品，增加库存
     */
    public static void handleNPCSellMode(CustomEntity npc, BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        if (npc == null || level == null || config == null) return;

        // 处理补货逻辑
        processRestock(pos, level, config);

        // 获取NPC等级并计算效率加成
        int npcLevel = NPCDataManager.getNPCLevel(level.getServer(), npc.getUUID());
        float efficiency = getEfficiencyByLevel(npcLevel);

        // 处理NPC出售物品给商店（从NPC背包获取物品，增加库存）
        // 这里简化处理：NPC工作时自动增加库存
        for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
            if (trade.canBuy()) {
                // NPC向商店出售物品，增加商店库存
                CommercialHiredData.StockInfo stockInfo = CommercialHiredData.getStock(pos, trade.getItemId());
                int currentStock = stockInfo != null ? stockInfo.getCurrentStock() : 0;
                int maxStock = trade.getMaxStock();
                int dailyBoughtAmount = stockInfo != null ? stockInfo.getDailyBoughtAmount() : 0;
                int maxBuyAmount = stockInfo != null && stockInfo.getMaxBuyAmount() > 0 ? stockInfo.getMaxBuyAmount() : maxStock / 64;

                // 根据NPC效率计算增加的库存
                int addAmount = (int) (trade.getRestockAmount() * efficiency * 0.5); // NPC出售数量是补货数量的一半
                int newStock = Math.min(currentStock + addAmount, maxStock);

                if (newStock > currentStock) {
                    // 使用 updateStockFull 保留所有字段
                    CommercialHiredData.updateStockFull(pos, trade.getItemId(), newStock, maxStock,
                            level.getDayTime(), dailyBoughtAmount, maxBuyAmount);
                }
            }
        }
    }

    /**
     * 处理玩家出售模式
     * 检查库存，执行补货逻辑
     */
    public static void handlePlayerSellMode(BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        if (level == null || config == null) return;

        // 统一使用通用的补货逻辑
        processRestock(pos, level, config);

        // 检查并重置每日收购数量
        checkAndResetDailyBuyAmount(pos, level);
    }

    /**
     * 处理混合模式
     * 综合处理NPC出售和补货逻辑
     */
    public static void handleMixedMode(CustomEntity npc, BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        if (npc == null || level == null || config == null) return;

        // 检查并重置每日收购数量
        checkAndResetDailyBuyAmount(pos, level);

        // `handleNPCSellMode` 内部已经包含补货逻辑，这里直接复用即可，
        // 避免混合模式在同一 tick 内重复跑两次补货与持久化。
        handleNPCSellMode(npc, pos, level, config);
    }

    /**
     * 检查并重置每日收购数量
     * @param pos 商店位置
     * @param level 服务器世界
     */
    private static void checkAndResetDailyBuyAmount(BlockPos pos, ServerLevel level) {
        if (level == null || level.getServer() == null) return;

        long gameTime = level.getDayTime();
        CommercialHiredData.loadStockData(level.getServer());

        Map<String, CommercialHiredData.StockInfo> stockMap = CommercialHiredData.getAllStockAtPos(pos);
        if (stockMap == null || stockMap.isEmpty()) return;

        // 检查是否需要重置（新的一天）
        for (CommercialHiredData.StockInfo stockInfo : stockMap.values()) {
            long lastRestockDay = stockInfo.getLastRestockTime() / 24000L;
            long currentDay = gameTime / 24000L;

            if (currentDay > lastRestockDay) {
                stockInfo.resetDailyBoughtAmount();
            }
        }

        CommercialHiredData.saveStockData(level.getServer());
    }

    /**
     * 补货逻辑
     * 检查是否到达补货时间点（restockTime），到达后增加库存（不超过maxStock）
     * 如果配置了原料需求，会从箱子中消耗原料来加工商品
     * 发送补货消息
     */
    public static void processRestock(BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        if (pos == null || level == null || config == null) return;

        // 需要原料前置的商业建筑改为开工时从输入建筑取料，不走自动补货。
        if (config.hasMaterialRequirements()) {
            return;
        }

        // 确保库存数据已加载
        CommercialHiredData.loadStockData(level.getServer());

        long gameTime = level.getDayTime();
        long timeOfDay = gameTime % 24000L;
        int restockTime = config.getRestockTime();

        // 只在精确的补货时间点补货
        if (timeOfDay != restockTime) {
            return; // 不是补货时间点
        }

        boolean hasRestocked = false;
        StringBuilder restockedItems = new StringBuilder();

        // 遍历所有交易物品，执行补货
        for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
            CommercialHiredData.StockInfo stockInfo = CommercialHiredData.getStock(pos, trade.getItemId());
            int currentStock = stockInfo != null ? stockInfo.getCurrentStock() : 0;
            int maxStock = trade.getMaxStock();
            int dailyBoughtAmount = stockInfo != null ? stockInfo.getDailyBoughtAmount() : 0;
            int maxBuyAmount = stockInfo != null ? stockInfo.getMaxBuyAmount() : maxStock / 64;

            if (currentStock < maxStock) {
                int restockAmount = Math.min(trade.getRestockAmount(), maxStock - currentStock);
                int newStock = currentStock + restockAmount;

                CommercialHiredData.updateStockFull(pos, trade.getItemId(), newStock, maxStock,
                        gameTime, dailyBoughtAmount, maxBuyAmount);

                if (restockedItems.length() > 0) {
                    restockedItems.append(", ");
                }
                restockedItems.append(trade.getItemId()).append(" x").append(restockAmount);

                hasRestocked = true;
            } else {
                // 即使没有补货，也要确保库存信息被正确初始化
                if (stockInfo == null || stockInfo.getMaxStock() == 0) {
                    CommercialHiredData.updateStockFull(pos, trade.getItemId(), currentStock, maxStock,
                            stockInfo != null ? stockInfo.getLastRestockTime() : 0, dailyBoughtAmount, maxBuyAmount);
                }
            }
        }

        if (hasRestocked) {
            // 发送补货消息
            sendRestockMessage(pos, level, config, restockedItems.toString());

            // 保存库存数据
            CommercialHiredData.saveStockData(level.getServer());
        }
    }

    // 开工时间窗口：1000 tick = 50秒，确保不会错过
    private static final long SHIFT_START_WINDOW = 1000;

    private static void handleShiftStart(CustomEntity npc, BlockPos buildingPos, ServerLevel level,
                                         CommercialBuildingConfig config, long gameTime) {
        if (npc == null || buildingPos == null || level == null || config == null) {
            return;
        }

        long dayIndex = gameTime / 24000L;
        long timeOfDay = gameTime % 24000L;

        // 使用更大的时间窗口（50秒），避免错过开工时间
        if (!isWithinStartWindow(timeOfDay, config.getWorkStartTime(), SHIFT_START_WINDOW)) {
            return;
        }

        Long handledDay = lastShiftStartDay.get(buildingPos);
        if (handledDay != null && handledDay == dayIndex) {
            return;
        }

        if (NPCRestHandler.isNpcInRestWorkflow(npc.getUUID())) {
            return;
        }

        // simukraft: 午休期间不传送NPC到工作位置
        if (npc.getWorkSubState() == com.xiaoliang.simukraft.entity.WorkSubState.LUNCH_BREAK) {
            return;
        }

        markShiftStarted(buildingPos, dayIndex);

        // 下班逻辑会把商业NPC切回 IDLE，这里统一通过协调器恢复为工作中，
        // 避免休息链路尚未结束时被提前标记为“工作中”。
        if (!NPCWorkResumeCoordinator.activateCommercialShift(npc, buildingPos)) {
            lastShiftStartDay.remove(buildingPos);
            return;
        }

        moveNpcToWorkplace(npc, buildingPos);
        prepareMaterialsForToday(buildingPos, level, config, dayIndex);
    }

    private static void handleShiftEnd(CustomEntity npc, BlockPos buildingPos, ServerLevel level,
                                       CommercialBuildingConfig config, long gameTime) {
        if (npc == null || buildingPos == null || level == null || config == null) {
            return;
        }

        Long restoredAt = recentlyRestoredCommercialNpcs.get(npc.getUUID());
        if (restoredAt != null) {
            long elapsed = level.getGameTime() - restoredAt;
            if (elapsed >= 0 && elapsed < RESTORE_SHIFT_END_PROTECTION_TICKS) {
                if (ServerConfig.isDebugLogEnabled()) {
                    LOGGER.info("[CommercialWorkHandler] NPC {} 刚从休息恢复，暂时跳过下班回家，当前位置: {}，工作点: {}，剩余保护: {}tick",
                            npc.getFullName(), npc.blockPosition(), buildingPos, RESTORE_SHIFT_END_PROTECTION_TICKS - elapsed);
                }
                return;
            }
            recentlyRestoredCommercialNpcs.remove(npc.getUUID());
        }

        long dayIndex = gameTime / 24000L;
        Long handledDay = lastShiftEndDay.get(buildingPos);
        if (handledDay != null && handledDay == dayIndex) {
            return;
        }

        if (npc.getWorkStatus() == com.xiaoliang.simukraft.entity.WorkStatus.IDLE) {
            lastShiftEndDay.put(buildingPos, dayIndex);
            return;
        }

        lastShiftEndDay.put(buildingPos, dayIndex);
        preparedMaterialsDay.remove(buildingPos);
        npc.setWorkStatus(com.xiaoliang.simukraft.entity.WorkStatus.IDLE);
        npc.setWorking(false);

        // 触发NPC休息流程，让商业建筑员工回家休息
        NPCRestHandler.startResting(npc, level);

        level.getServer().getPlayerList().getPlayers().forEach(player ->
                com.xiaoliang.simukraft.network.NetworkManager.sendToPlayer(
                        new com.xiaoliang.simukraft.network.NPCWorkStatusPacket(
                                npc.getUUID(),
                                com.xiaoliang.simukraft.entity.WorkStatus.IDLE,
                                buildingPos,
                                npc.getFullName(),
                                npc.getJob()
                        ),
                        player
                )
        );
    }

    private static boolean isWithinStartWindow(long timeOfDay, int startTick, long windowTicks) {
        long endTick = (startTick + windowTicks) % 24000L;
        if (startTick < endTick) {
            return timeOfDay >= startTick && timeOfDay < endTick;
        }
        return timeOfDay >= startTick || timeOfDay < endTick;
    }

    private static void prepareMaterialsForToday(BlockPos buildingPos, ServerLevel level,
                                                 CommercialBuildingConfig config, long dayIndex) {
        if (buildingPos == null || level == null || config == null) {
            return;
        }

        if (config.hasMaterialRequirements()) {
            boolean hasMaterials = hasMaterialsInNearbyContainers(buildingPos, level, config);
            if (hasMaterials) {
                preparedMaterialsDay.put(buildingPos, dayIndex);
                LOGGER.info("[商业建筑] {} 开工检查通过，箱子中有原料，今日可以开售", config.getBuildingName());
            } else {
                preparedMaterialsDay.remove(buildingPos);
                if (com.xiaoliang.simukraft.config.ServerConfig.isDebugLogEnabled()) {
                    LOGGER.info("[商业建筑] {} 开工检查失败，箱子中没有原料，今日不允许开售", config.getBuildingName());
                }
            }
            return;
        }

        preparedMaterialsDay.put(buildingPos, dayIndex);
    }

    /**
     * 移动NPC到工作位置
     * menglannnn: 完全使用新的自定义寻路系统，不再使用原版寻路
     */
    private static void moveNpcToWorkplace(CustomEntity npc, BlockPos workPos) {
        double targetX = workPos.getX() + 0.5;
        double targetY = workPos.getY() + 1.0;
        double targetZ = workPos.getZ() + 0.5;

        if (npc.moveToWithNewPathfinder(targetX, targetY, targetZ, 1.0D)) {
            if (ServerConfig.isDebugLogEnabled()) {
                LOGGER.info("[CommercialWorkHandler] NPC {} 开始前往商业工作点，当前位置: {}，目标位置: {}，目的: 商业上班", npc.getFullName(), npc.blockPosition(), workPos);
            }
            return;
        }

        LOGGER.warn("[CommercialWorkHandler] NPC {} 前往商业工作点寻路失败，当前位置: {}，目标位置: {}，目的: 商业上班，改为直接传送", npc.getFullName(), npc.blockPosition(), workPos);
        npc.teleportTo(targetX, targetY, targetZ);
        npc.stopNewPathfinder();
    }

    /**
     * 检查附近箱子中是否有足够的原料
     * 对于需要原料的商品，只要箱子中有至少一个原料，就返回true
     */
    private static boolean hasMaterialsInNearbyContainers(BlockPos targetPos, ServerLevel level,
                                                          CommercialBuildingConfig config) {
        for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
            if (!trade.requiresMaterial()) {
                continue; // 不需要原料的交易跳过
            }

            String requiredMaterial = trade.getRequiredMaterial();
            ItemStack template = parseItemStack(requiredMaterial);
            if (template.isEmpty()) {
                continue;
            }

            // 检查附近箱子中是否有该原料
            int count = countMaterialsInNearbyContainers(level, targetPos, template);
            if (count > 0) {
                return true; // 只要有一种原料就返回true
            }
        }
        return false;
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

    public static boolean canStartSelling(MinecraftServer server, BlockPos pos, CommercialBuildingConfig config) {
        if (server == null || pos == null || config == null) {
            return false;
        }

        if (!config.hasMaterialRequirements()) {
            return true;
        }

        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return false;
        }

        long dayIndex = overworld.getDayTime() / 24000L;

        // 首先检查是否已标记今天准备好
        boolean markedReady = preparedMaterialsDay.getOrDefault(pos, -1L) == dayIndex;
        if (!markedReady) {
            return false;
        }

        // 实时检查箱子中是否还有原料
        return hasMaterialsInNearbyContainers(pos, overworld, config);
    }

    /**
     * 从建筑周围的箱子中获取原料（新增）
     * 使用 ContainerUtils 在主线程中安全读取
     */
    @SuppressWarnings("unused")
    private static Map<String, Integer> getMaterialsFromChests(BlockPos pos, ServerLevel level) {
        return ContainerUtils.executeOnMainThread(level, () -> {
            Map<String, Integer> materials = new HashMap<>();

            if (pos == null || level == null) return materials;

            // 查找建筑周围的箱子（5x3x5范围）
            for (int dx = -5; dx <= 5; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        BlockPos checkPos = pos.offset(dx, dy, dz);

                        // 使用 ContainerUtils 检查是否是容器
                        if (ContainerUtils.isContainer(level, checkPos)) {
                            // 获取容器中的所有物品
                            List<ItemStack> items = ContainerUtils.getAllItems(level, checkPos);
                            for (ItemStack stack : items) {
                                if (!stack.isEmpty()) {
                                    var itemKey = ForgeRegistries.ITEMS.getKey(Objects.requireNonNull(stack.getItem()));
                                    if (itemKey != null) {
                                        String itemId = itemKey.toString();
                                        materials.put(itemId, materials.getOrDefault(itemId, 0) + stack.getCount());
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return materials;
        });
    }

    /**
     * 从建筑周围的箱子中消耗原料（新增）
     * 使用 ContainerUtils 在主线程中安全消耗
     */
    @SuppressWarnings("unused")
    private static void consumeMaterialsFromChests(BlockPos pos, ServerLevel level,
                                                   java.util.List<CommercialBuildingConfig.MaterialRequirement> requirements) {
        if (pos == null || level == null || requirements == null) return;

        ContainerUtils.executeOnMainThread(level, () -> {
            for (CommercialBuildingConfig.MaterialRequirement req : requirements) {
                if (!req.isConsume()) continue; // 如果不消耗，跳过

                int remainingToConsume = req.getCount();
                String targetItemId = req.getItemId();

                // 创建要消耗的物品堆
                ItemStack itemToConsume = parseItemStack(targetItemId);
                if (itemToConsume.isEmpty()) continue;

                // 查找建筑周围的箱子
                for (int dx = -5; dx <= 5 && remainingToConsume > 0; dx++) {
                    for (int dy = -2; dy <= 2 && remainingToConsume > 0; dy++) {
                        for (int dz = -5; dz <= 5 && remainingToConsume > 0; dz++) {
                            BlockPos checkPos = pos.offset(dx, dy, dz);

                            // 使用 ContainerUtils 检查是否是容器
                            if (ContainerUtils.isContainer(level, checkPos)) {
                                // 统计容器中该物品的数量
                                int available = ContainerUtils.countItem(level, checkPos, itemToConsume);
                                if (available > 0) {
                                    int toConsume = Math.min(remainingToConsume, available);
                                    ItemStack consumeStack = itemToConsume.copy();
                                    consumeStack.setCount(toConsume);

                                    // 消耗物品
                                    if (ContainerUtils.consumeItem(level, checkPos, consumeStack)) {
                                        remainingToConsume -= toConsume;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null;
        });
    }

    /**
     * 解析物品ID为 ItemStack
     */
    private static ItemStack parseItemStack(String itemId) {
        try {
            var itemKey = net.minecraft.resources.ResourceLocation.tryParse(Objects.requireNonNull(itemId));
            if (itemKey == null) {
                return ItemStack.EMPTY;
            }
            var item = ForgeRegistries.ITEMS.getValue(itemKey);
            return item != null ? new ItemStack(item) : ItemStack.EMPTY;
        } catch (Exception e) {
            LOGGER.error("无法解析物品ID: " + itemId, e);
            return ItemStack.EMPTY;
        }
    }

    /**
     * NPC雇佣时初始化
     */
    public static void onCommercialNpcHired(CustomEntity npc, ServerLevel level, BlockPos pos, String buildingFileName) {
        if (npc == null || level == null || pos == null || buildingFileName == null) return;

        CommercialBuildingConfig config = CommercialBuildingManager.getConfig(buildingFileName);
        if (config == null) {
            return;
        }

        // 发送雇佣消息
        sendHireMessage(npc, level.getServer(), config);

        applyCommercialWorksiteState(npc, level, pos, config);
    }

    /**
     * 统一确保商业库存已初始化，避免界面展示与服务端交易判断不一致。
     */
    public static void ensureStockInitialized(BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        initializeStock(pos, level, config);
    }

    /**
     * 初始化库存
     */
    private static void initializeStock(BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        if (pos == null || level == null || config == null) return;

        // 为每个交易物品初始化库存
        for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
            CommercialHiredData.StockInfo existingStock = CommercialHiredData.getStock(pos, trade.getItemId());
            if (existingStock == null) {
                // 初始库存为最大库存的一半
                int initialStock = trade.getMaxStock() / 2;
                int maxStock = trade.getMaxStock();
                // 使用 updateStockFull 确保所有字段都被正确初始化
                CommercialHiredData.updateStockFull(pos, trade.getItemId(), initialStock, maxStock,
                        level.getDayTime(), 0, maxStock / 64);
            }
        }

        // 保存库存数据
        CommercialHiredData.saveStockData(level.getServer());
    }

    /**
     * 根据配置设置手持物品
     */
    public static void setHeldItemFromConfig(CustomEntity npc, CommercialBuildingConfig config) {
        if (npc == null || config == null) return;

        String heldItemId = config.getHeldItem();
        if (heldItemId == null || heldItemId.isEmpty()) {
            return; // 没有配置手持物品，不设置
        }

        Item item = parseItemId(heldItemId);
        if (item != null) {
            npc.setItemInHand(Objects.requireNonNull(npc.getUsedItemHand()), new ItemStack(item));
        }
    }

    /**
     * 发送雇佣消息
     */
    private static void sendHireMessage(CustomEntity npc, MinecraftServer server, CommercialBuildingConfig config) {
        if (npc == null || server == null || config == null) return;

        String safeJobName = Objects.requireNonNull(config.getJobName());
        Component npcName = npc.getCustomName() != null
                ? npc.getCustomName()
                : Component.literal(Objects.requireNonNull(npc.getFullName()));
        Component jobName = Component.translatable(safeJobName);

        Component message = Component.translatable("message.simukraft.commercial.hired", npcName, jobName);

        sendMessageToMayor(npc, server, message);
    }

    /**
     * 发送补货消息
     */
    private static void sendRestockMessage(BlockPos pos, ServerLevel level, CommercialBuildingConfig config, String items) {
        if (pos == null || level == null || config == null) return;

        // 获取该位置的雇佣信息
        CommercialHiredData.CommercialHireInfo hireInfo = CommercialHiredData.loadHiredEmployees(level.getServer()).get(pos);
        if (hireInfo == null) return;

        // 查找NPC
        CustomEntity npc = CommercialHiredData.findNPCByUuid(level.getServer(), hireInfo.getNpcUuid());
        if (npc == null) return;

        String safeJobName = Objects.requireNonNull(config.getJobName());
        Component jobName = Component.translatable(safeJobName);
        String safeItems = Objects.requireNonNull(items);

        // 使用新的通知服务替代旧的聊天系统
        if (npc.getCityId() != null) {
            CityData cityData = CityData.get(level);
            CityData.CityInfo cityInfo = cityData.getCity(npc.getCityId());
            if (cityInfo != null) {
                // 向城市的所有成员发送通知
                for (UUID citizenId : cityInfo.getCitizenIds()) {
                    MessageNotification notification = new MessageNotification(
                        npc.getFullName(), 
                        "NPC", 
                        "notify.title.restock", 
                        "notify.content.restock", 
                        citizenId, 
                        MessageCategory.COMMERCE
                    );
                    notification.setRelatedEntityId(npc.getCityId());
                    notification.setRelatedEntityType("CITY");
                    notification.putMetadata("npc_name", npc.getFullName());
                    notification.putMetadata("job_name", jobName.getString());
                    notification.putMetadata("items", safeItems);
                    
                    NotificationServiceManager.getService().sendNotification(notification);
                }
            }
        }
    }

    /**
     * 发送消息给NPC所在城市的市长（通过通知接口）
     */
    private static void sendMessageToMayor(CustomEntity npc, MinecraftServer server, Component message) {
        if (npc == null || server == null || message == null) return;

        UUID cityId = npc.getCityId();
        if (cityId == null) {
            return;
        }

        CityMessageUtils.sendToMayorViaService(server, cityId,
                net.minecraft.network.chat.Component.translatable("notify.title.commerce"), message,
                com.xiaoliang.simukraft.notification.MessageCategory.COMMERCE);
    }

    /**
     * 从数据文件获取建筑文件名
     */
    public static String getBuildingFileName(ServerLevel level, BlockPos pos) {
        try {
            Path worldPath = level.getServer().getWorldPath(
                    Objects.requireNonNull(net.minecraft.world.level.storage.LevelResource.ROOT)
            );
            Path commercialDir = worldPath.resolve("simukraft").resolve("commercial");
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = commercialDir.resolve(fileName);

            String cached = BUILDING_FILE_NAME_CACHE.get(skFile);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }

            String buildingFileName = readBuildingFileName(skFile);
            BUILDING_FILE_NAME_CACHE.put(skFile, buildingFileName == null ? "" : buildingFileName);
            return buildingFileName;
        } catch (Exception e) {
        }
        return null;
    }

    private static String readBuildingFileName(Path skFile) throws java.io.IOException {
        if (!Files.exists(skFile)) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(skFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("building_file_name:")) {
                    return trimmed.substring(19).trim();
                }
            }
        }

        return null;
    }

    /**
     * 解析物品ID
     */
    public static Item parseItemId(String itemId) {
        try {
            String safeItemId = Objects.requireNonNull(itemId);
            net.minecraft.resources.ResourceLocation itemKey = safeItemId.contains(":")
                    ? net.minecraft.resources.ResourceLocation.tryParse(safeItemId)
                    : net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + safeItemId);
            return itemKey != null ? ForgeRegistries.ITEMS.getValue(itemKey) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取等级对应的效率加成
     */
    public static float getEfficiencyByLevel(int level) {
        if (level <= 1) {
            return 1.0f;
        }
        return switch (level) {
            case 2 -> 1.1f;
            case 3 -> 1.25f;
            case 4 -> 1.4f;
            case 5 -> 1.6f;
            case 6 -> 1.8f;
            case 7 -> 2.0f;
            default -> Math.min(3.0f, 2.0f + (level - 7) * 0.1f);
        };
    }

    /**
     * 获取建筑配置（供其他类使用）
     */
    public static CommercialBuildingConfig getBuildingConfig(String buildingId) {
        return CommercialBuildingManager.getConfig(buildingId);
    }

    /**
     * 获取指定位置的库存信息
     */
    public static Map<String, CommercialHiredData.StockInfo> getStockAtPos(BlockPos pos) {
        return CommercialHiredData.getAllStockAtPos(pos);
    }

    /**
     * 更新指定物品的库存（保留所有字段）
     */
    public static void updateStock(BlockPos pos, String itemId, int newStock, long restockTime) {
        CommercialHiredData.StockInfo stockInfo = CommercialHiredData.getStock(pos, itemId);
        int maxStock = stockInfo != null ? stockInfo.getMaxStock() : newStock;
        int dailyBoughtAmount = stockInfo != null ? stockInfo.getDailyBoughtAmount() : 0;
        int maxBuyAmount = stockInfo != null && stockInfo.getMaxBuyAmount() > 0 ? stockInfo.getMaxBuyAmount() : maxStock / 64;
        CommercialHiredData.updateStockFull(pos, itemId, newStock, maxStock, restockTime, dailyBoughtAmount, maxBuyAmount);
    }

    /**
     * NPC传送到工作位置时调用
     * 统一处理传送后的逻辑：初始化库存、设置手持物品等
     */
    public static void onCommercialNpcTeleported(CustomEntity npc, ServerLevel level, BlockPos pos, String buildingFileName) {
        if (npc == null || level == null || pos == null || buildingFileName == null) return;

        CommercialBuildingConfig config = CommercialBuildingManager.getConfig(buildingFileName);
        if (config == null) {
            return;
        }

        applyCommercialWorksiteState(npc, level, pos, config);
    }

    /**
     * NPC起床后回到商业工作方块时，补跑当天的开工初始化。
     */
    public static void restoreNpcAfterRest(CustomEntity npc, ServerLevel level, BlockPos pos, String buildingFileName) {
        if (npc == null || level == null || pos == null || buildingFileName == null) {
            return;
        }

        CommercialBuildingConfig config = CommercialBuildingManager.getConfig(buildingFileName);
        if (config == null) {
            return;
        }

        long dayIndex = level.getDayTime() / 24000L;
        applyCommercialWorksiteState(npc, level, pos, config);
        recentlyRestoredCommercialNpcs.put(npc.getUUID(), level.getGameTime());
        if (ServerConfig.isDebugLogEnabled()) {
            LOGGER.info("[CommercialWorkHandler] NPC {} 从休息恢复到商业工作，当前位置: {}，工作点: {}，职业: {}，已添加下班保护 {}tick",
                    npc.getFullName(), npc.blockPosition(), pos, npc.getJob(), RESTORE_SHIFT_END_PROTECTION_TICKS);
        }
        markShiftStarted(pos, dayIndex);
        prepareMaterialsForToday(pos, level, config, dayIndex);
    }

    /**
     * 设置NPC手持物品（公共方法，供外部调用）
     */
    public static void setNpcHeldItem(CustomEntity npc, CommercialBuildingConfig config) {
        if (npc == null || config == null) return;
        setHeldItemFromConfig(npc, config);
    }

    private static void applyCommercialWorksiteState(CustomEntity npc,
                                                     ServerLevel level,
                                                     BlockPos pos,
                                                     CommercialBuildingConfig config) {
        if (npc == null || level == null || pos == null || config == null) {
            return;
        }
        initializeStock(pos, level, config);
        setHeldItemFromConfig(npc, config);
    }

    private static void markShiftStarted(BlockPos pos, long dayIndex) {
        if (pos == null) {
            return;
        }
        lastShiftStartDay.put(pos, dayIndex);
        lastShiftEndDay.remove(pos);
    }
}
