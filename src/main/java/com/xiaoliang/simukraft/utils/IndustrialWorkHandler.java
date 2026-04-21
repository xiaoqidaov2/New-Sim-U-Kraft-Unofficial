package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.building.ControlBoxDataManager;
import com.xiaoliang.simukraft.building.IndustrialBuildingConfig;
import com.xiaoliang.simukraft.building.IndustrialBuildingManager;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.world.IndustrialHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工业工作处理器
 * 统一处理所有工业建筑的工作逻辑、生物生成和手持物品设置
 * 完全配置化，无硬编码
 */
public class IndustrialWorkHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    // 存储每个工业建筑的上次处理时间，用于计算工作刻间隔
    private static final Map<BlockPos, Long> lastWorkTick = new HashMap<>();
    // 存储每个工业建筑当天是否已经在工作结束时间处理过
    private static final Map<BlockPos, Long> processedEndOfDay = new HashMap<>();
    private static final Map<Path, String> BUILDING_FILE_NAME_CACHE = new ConcurrentHashMap<>();
    private static boolean dataInitialized = false;

    // 性能优化：添加tick冷却，避免每tick都检查
    private static long lastCheckTick = 0;
    private static final long CHECK_INTERVAL = 20; // 每秒检查一次（20 ticks）
    
    // 添加服务器启动时的数据加载方法
    public static void onServerStart(MinecraftServer server) {
        if (server == null) return;

        // 重置初始化状态，确保数据重新加载
        dataInitialized = false;
        lastWorkTick.clear();
        processedEndOfDay.clear();
        BUILDING_FILE_NAME_CACHE.clear();

        // 初始化工业建筑配置管理器
        IndustrialBuildingManager.init(server);

        LOGGER.info(Component.translatable("message.industrial_work_handler.server_start.reload").getString());
    }

    /**
     * 处理工业建筑的工作刻
     */
    public static void handleDailyWork(ServerLevel level) {
        if (level == null) return;

        // 性能优化：检查tick冷却
        long gameTime = level.getDayTime();

        if (gameTime - lastCheckTick < CHECK_INTERVAL) {
            return; // 冷却中，跳过检查
        }
        lastCheckTick = gameTime;

        // 确保数据已加载
        if (!dataInitialized) {
            dataInitialized = true;
        }

        long currentDay = gameTime / 24000; // 计算当前是第几天

        // 从IndustrialHiredData加载所有雇佣数据
        Map<BlockPos, IndustrialHiredData.IndustrialHireInfo> hiredEmployees = IndustrialHiredData.loadHiredEmployees(level.getServer());

        // 遍历所有雇佣的工业建筑
        for (Map.Entry<BlockPos, IndustrialHiredData.IndustrialHireInfo> entry : hiredEmployees.entrySet()) {
            BlockPos buildingPos = entry.getKey();
            IndustrialHiredData.IndustrialHireInfo hireInfo = entry.getValue();

            // 获取建筑配置
            String buildingFileName = hireInfo.getBuildingFileName();
            IndustrialBuildingConfig config = IndustrialBuildingManager.getConfig(buildingFileName);
            if (config == null) {
                LOGGER.warn(Component.translatable("message.industrial_work_handler.error.config_not_found", buildingFileName).getString());
                continue;
            }

            // 查找实际的NPC实体
            CustomEntity npc = IndustrialHiredData.findNPCByUuid(level.getServer(), hireInfo.getNpcUuid());

            if (npc != null && npc.isAlive()) {
                // 计算当前时间（currentDay已在方法开头定义）
                long timeOfDay = gameTime % 24000;
                
                // 获取当前选择的配方ID
                String selectedRecipeId = ControlBoxDataManager.getSelectedRecipe(level.getServer(), buildingPos);

                // 检查是否在工作时间内（考虑配方自定义时间）
                boolean isWorkTime = config.isWorkTimeForRecipe(selectedRecipeId, gameTime);

                if (isWorkTime) {
                    // 计算工作刻间隔（基于NPC等级）
                    int npcLevel = NPCDataManager.getNPCLevel(level.getServer(), npc.getUUID());
                    long workTickInterval = getWorkTickInterval(npcLevel);
                    
                    // 检查是否到达工作刻
                    // 使用基于天的相对时间来处理时间跳跃（如/time set指令）
                    Long lastTick = lastWorkTick.get(buildingPos);
                    long timeSinceLastWork;
                    
                    if (lastTick == null) {
                        timeSinceLastWork = Long.MAX_VALUE;
                    } else {
                        long lastDay = lastTick / 24000;
                        long lastTimeOfDay = lastTick % 24000;
                        
                        if (currentDay > lastDay) {
                            // 已经过了新的一天，重置工作间隔
                            timeSinceLastWork = Long.MAX_VALUE;
                        } else if (currentDay < lastDay) {
                            // 时间被调回（如/time set指令），重置工作间隔
                            timeSinceLastWork = Long.MAX_VALUE;
                        } else {
                            // 同一天内，正常计算间隔
                            timeSinceLastWork = timeOfDay - lastTimeOfDay;
                            // 如果时间被调回（负数），重置
                            if (timeSinceLastWork < 0) {
                                timeSinceLastWork = Long.MAX_VALUE;
                            }
                        }
                    }
                    
                    if (timeSinceLastWork >= workTickInterval) {
                        // 到达工作刻，执行工作（只生产物品，不发送消息和经验值）
                        processIndustrialWork(npc, buildingPos, level, config, selectedRecipeId);

                        // 记录上次工作刻时间
                        lastWorkTick.put(buildingPos, gameTime);
                    }
                }
                
                // 检查是否接近工作结束时间（在结束时间前100tick内）
                int workEndTime = config.getWorkEndTime();
                if (Math.abs(timeOfDay - workEndTime) < 100) {
                    // 检查当天是否已经处理过结束时间逻辑
                    Long lastProcessedDay = processedEndOfDay.get(buildingPos);
                    if (lastProcessedDay == null || lastProcessedDay < currentDay) {
                        // 执行工作结束逻辑：发送消息和添加经验
                        handleEndOfWorkDay(npc, level, config);
                        
                        // 记录处理状态
                        processedEndOfDay.put(buildingPos, currentDay);
                    }
                }
            } else {
                LOGGER.warn(Component.translatable("message.industrial_work_handler.error.invalid_npc", npc, buildingFileName).getString());
            }
        }

        // 清理过期的记录（每天开始时清理）
        if (gameTime % 24000 < 100) {
            lastWorkTick.clear();
            processedEndOfDay.clear();
        }
    }

    /**
     * 处理工业工作
     * 根据配置执行对应的生产逻辑（只生产物品，不发送消息和经验值）
     * @param selectedRecipeId 当前选择的配方ID
     */
    private static void processIndustrialWork(CustomEntity npc, BlockPos buildingPos, ServerLevel level, IndustrialBuildingConfig config, String selectedRecipeId) {
        if (npc == null || level == null || config == null) {
            return;
        }

        // 检查是否有足够的材料（只检查，不拿取）
        if (!hasEnoughMaterials(level, buildingPos, config, selectedRecipeId)) {
            // 材料不足，不执行工作，也不发送消息
            return;
        }

        // 获取NPC等级并计算产量加成
        int npcLevel = NPCDataManager.getNPCLevel(level.getServer(), npc.getUUID());
        float multiplier = getYieldMultiplierByLevel(npcLevel);

        // 获取当前配方的产物列表
        List<IndustrialBuildingConfig.ProductOutput> products = config.getEffectiveProducts(selectedRecipeId);

        // 先计算所有产物的产出数量
        Map<Item, Integer> itemsToProduce = new HashMap<>();
        for (IndustrialBuildingConfig.ProductOutput product : products) {
            int amount = product.calculateAmount(level.random, multiplier);
            if (amount <= 0) {
                continue;
            }

            Item item = parseItemId(product.getItemId());
            if (item == null) {
                continue;
            }

            itemsToProduce.put(item, itemsToProduce.getOrDefault(item, 0) + amount);
        }

        // 如果没有产物，直接返回
        if (itemsToProduce.isEmpty()) {
            return;
        }

        // simukraft: 先检查是否有足够的空间存放所有产物
        if (!hasEnoughSpaceForProducts(level, buildingPos, itemsToProduce)) {
            // 空间不足，不执行工作
            return;
        }

        // 现在拿取材料（因为确定要生产了）
        if (!consumeMaterials(level, buildingPos, config, selectedRecipeId)) {
            // 拿取材料失败（可能刚好被其他东西消耗了），不执行生产
            return;
        }

        // 放入产物到箱子
        for (Map.Entry<Item, Integer> entry : itemsToProduce.entrySet()) {
            placeItemInNearbyChest(level, buildingPos, entry.getKey(), entry.getValue());
        }
    }

    /**
     * 检查周围箱子是否有足够空间存放所有产物
     */
    private static boolean hasEnoughSpaceForProducts(ServerLevel level, BlockPos buildingPos, Map<Item, Integer> itemsToProduce) {
        for (Map.Entry<Item, Integer> entry : itemsToProduce.entrySet()) {
            Item item = entry.getKey();
            int amount = entry.getValue();
            ItemStack itemStack = new ItemStack(item, amount);

            if (!hasSpaceInNearbyChests(level, buildingPos, itemStack)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查周围箱子是否有空间放置指定物品
     */
    private static boolean hasSpaceInNearbyChests(ServerLevel level, BlockPos buildingPos, ItemStack itemStack) {
        if (buildingPos == null || level == null || itemStack.isEmpty()) return false;

        int range = 3;
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos checkPos = Objects.requireNonNull(buildingPos.offset(x, y, z));
                    if (!level.isLoaded(Objects.requireNonNull(checkPos))) continue;
                    if (!ContainerUtils.isContainer(level, checkPos)) continue;
                    if (ContainerUtils.canInsertItem(level, checkPos, itemStack)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检查是否有足够的材料（只检查，不拿取）
     */
    private static boolean hasEnoughMaterials(ServerLevel level, BlockPos buildingPos, IndustrialBuildingConfig config, String selectedRecipeId) {
        List<IndustrialBuildingConfig.MaterialRequirement> materials = config.getEffectiveMaterials(selectedRecipeId);

        for (IndustrialBuildingConfig.MaterialRequirement material : materials) {
            if (!material.isConsume()) continue;

            Item item = parseItemId(material.getItemId());
            if (item == null) continue;

            int requiredCount = material.getCount();
            int availableCount = countItemInNearbyChests(level, buildingPos, item);

            if (availableCount < requiredCount) {
                return false;
            }
        }

        return true;
    }

    /**
     * 统计周围箱子中指定物品的数量
     */
    private static int countItemInNearbyChests(ServerLevel level, BlockPos buildingPos, Item item) {
        if (level == null || buildingPos == null || item == null) {
            return 0;
        }

        int range = 3;
        int totalCount = 0;

        for (int x = -range; x <= range && totalCount >= 0; x++) {
            for (int y = -range; y <= range && totalCount >= 0; y++) {
                for (int z = -range; z <= range && totalCount >= 0; z++) {
                    BlockPos checkPos = Objects.requireNonNull(buildingPos.offset(x, y, z));

                    if (!level.isLoaded(Objects.requireNonNull(checkPos))) {
                        continue;
                    }

                    if (!ContainerUtils.isContainer(level, checkPos)) {
                        continue;
                    }

                    ItemStack requiredStack = new ItemStack(item, 64);
                    totalCount += ContainerUtils.countItem(level, checkPos, requiredStack);
                }
            }
        }

        return totalCount;
    }
    
    /**
     * 处理工作结束逻辑：发送消息和添加经验
     */
    private static void handleEndOfWorkDay(CustomEntity npc, ServerLevel level, IndustrialBuildingConfig config) {
        if (npc == null || level == null || config == null) return;
        
        // 发送工作完成消息
        sendWorkMessage(npc, level.getServer(), config, true);
        
        // 添加经验
        addXp(npc, level.getServer(), level, config);
    }
    
    /**
     * 检查并消耗原料
     * 从建筑周围的箱子中取料
     * @param selectedRecipeId 当前选择的配方ID
     */
    private static boolean consumeMaterials(ServerLevel level, BlockPos buildingPos, IndustrialBuildingConfig config, String selectedRecipeId) {
        // 获取当前配方的原料列表
        List<IndustrialBuildingConfig.MaterialRequirement> materials = config.getEffectiveMaterials(selectedRecipeId);

        // 检查并消耗每种原料
        for (IndustrialBuildingConfig.MaterialRequirement material : materials) {
            if (!material.isConsume()) continue; // 不需要消耗的原料跳过

            Item item = parseItemId(material.getItemId());
            if (item == null) continue;

            int requiredCount = material.getCount();
            int remaining = requiredCount;

            // 从周围箱子取料
            remaining = consumeFromNearbyChests(level, buildingPos, item, remaining);

            // 检查是否收集足够
            if (remaining > 0) {
                // 材料不足，返回false（不再发送消息）
                return false;
            }
        }

        return true;
    }

    /**
     * 从建筑周围的箱子取料
     */
    private static int consumeFromNearbyChests(ServerLevel level, BlockPos buildingPos, Item item, int requested) {
        if (level == null || buildingPos == null || item == null || requested <= 0) {
            return requested;
        }

        int range = 3;
        int remaining = requested;

        for (int x = -range; x <= range && remaining > 0; x++) {
            for (int y = -range; y <= range && remaining > 0; y++) {
                for (int z = -range; z <= range && remaining > 0; z++) {
                    BlockPos checkPos = Objects.requireNonNull(buildingPos.offset(x, y, z));
                    
                    if (!level.isLoaded(Objects.requireNonNull(checkPos))) {
                        continue;
                    }
                    
                    if (!ContainerUtils.isContainer(level, checkPos)) {
                        continue;
                    }

                    ItemStack requiredStack = new ItemStack(item, remaining);
                    int available = ContainerUtils.countItem(level, checkPos, requiredStack);
                    if (available <= 0) {
                        continue;
                    }

                    int toConsume = Math.min(available, remaining);
                    ItemStack consumeStack = new ItemStack(item, toConsume);
                    if (ContainerUtils.consumeItem(level, checkPos, consumeStack)) {
                        remaining -= toConsume;
                    }
                }
            }
        }

        return remaining;
    }

    /**
     * NPC被雇佣时调用
     * 统一处理雇佣逻辑：设置手持物品、生成生物、发送消息
     */
    public static void onIndustrialNpcHired(CustomEntity npc, ServerLevel level, BlockPos farmPos, String buildingFileName) {
        if (npc == null || level == null || farmPos == null || buildingFileName == null) return;

        IndustrialBuildingConfig config = IndustrialBuildingManager.getConfig(buildingFileName);
        if (config == null) {
            LOGGER.warn(Component.translatable("message.industrial_work_handler.hire.config_not_found", buildingFileName).getString());
            return;
        }

        // 获取当前选择的配方ID
        String selectedRecipeId = ControlBoxDataManager.getSelectedRecipe(level.getServer(), farmPos);

        // 发送雇佣消息
        sendHireMessage(npc, level.getServer(), config);

        // 设置手持物品（使用配方配置）
        setHeldItemFromConfig(npc, config, selectedRecipeId);

        // 生成生物（附加项）
        spawnEntitiesIfNeeded(level, farmPos, config);

        LOGGER.info(Component.translatable("message.industrial_work_handler.hire.npc_hired", config.getBuildingName(), farmPos, npc.getFullName()).getString());
    }

    /**
     * NPC传送到工作位置时调用
     */
    public static void onIndustrialNpcTeleported(CustomEntity npc, ServerLevel level, BlockPos farmPos, String buildingFileName) {
        if (npc == null || level == null || farmPos == null || buildingFileName == null) return;

        IndustrialBuildingConfig config = IndustrialBuildingManager.getConfig(buildingFileName);
        if (config == null) return;

        // 生成生物（附加项）
        spawnEntitiesIfNeeded(level, farmPos, config);
    }

    /**
     * NPC起床后回到工业工作方块时，恢复当天的工业工作现场状态。
     */
    public static void restoreNpcAfterRest(CustomEntity npc, ServerLevel level, BlockPos farmPos, String buildingFileName) {
        if (npc == null || level == null || farmPos == null || buildingFileName == null) {
            return;
        }

        IndustrialBuildingConfig config = IndustrialBuildingManager.getConfig(buildingFileName);
        if (config == null) {
            return;
        }

        String selectedRecipeId = ControlBoxDataManager.getSelectedRecipe(level.getServer(), farmPos);
        setHeldItemFromConfig(npc, config, selectedRecipeId);
        spawnEntitiesIfNeeded(level, farmPos, config);
    }

    /**
     * 设置手持物品（公共方法，供外部调用）
     * @param selectedRecipeId 当前选择的配方ID
     */
    public static void setNpcHeldItem(CustomEntity npc, IndustrialBuildingConfig config, String selectedRecipeId) {
        if (npc == null || config == null) return;
        setHeldItemFromConfig(npc, config, selectedRecipeId);
    }

    /**
     * 设置手持物品
     * @param selectedRecipeId 当前选择的配方ID
     */
    private static void setHeldItemFromConfig(CustomEntity npc, IndustrialBuildingConfig config, String selectedRecipeId) {
        if (npc == null || config == null) return;

        // 获取有效手持物品（考虑配方配置）
        String heldItemId = config.getEffectiveHeldItem(selectedRecipeId);
        if (heldItemId == null || heldItemId.isEmpty()) {
            return; // 没有配置手持物品，不设置
        }

        Item item = parseItemId(heldItemId);
        if (item != null) {
            npc.setItemInHand(Objects.requireNonNull(npc.getUsedItemHand()), new ItemStack(item));
        }
    }

    /**
     * 根据配置生成生物
     */
    private static void spawnEntitiesIfNeeded(ServerLevel level, BlockPos farmPos, IndustrialBuildingConfig config) {
        if (level == null || farmPos == null || config == null) return;

        if (!config.isSpawnEntity() || config.getEntityType() == null || config.getEntityCount() <= 0) {
            return;
        }

        String entityKey = config.getEntityType().replace(":", "_");
        if (IndustrialHiredData.hasGeneratedAnimals(farmPos, entityKey)) {
            return;
        }

        spawnEntities(level, farmPos, config.getEntityType(), config.getEntityCount());

        IndustrialHiredData.markAnimalsGenerated(farmPos, entityKey, config.getEntityCount());
        IndustrialHiredData.saveAnimalGenerationData(level.getServer());

        LOGGER.info(Component.translatable("message.industrial_work_handler.spawn_entity.spawned", config.getEntityCount(), config.getEntityType()).getString());
    }

    /**
     * 生成指定类型的生物
     */
    private static void spawnEntities(ServerLevel level, BlockPos centerPos, String entityTypeId, int count) {
        Random random = new Random();

        for (int i = 0; i < count; i++) {
            int offsetX = random.nextInt(5) - 2;
            int offsetZ = random.nextInt(5) - 2;
            BlockPos spawnPos = centerPos.offset(offsetX, 1, offsetZ);

            EntityType<?> entityType = parseEntityType(entityTypeId);
            if (entityType == null) continue;

            Entity entity = entityType.create(Objects.requireNonNull(level));
            if (entity == null) continue;

            entity.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);

            if (entity instanceof Sheep sheep) {
                sheep.setColor(DyeColor.WHITE);
                sheep.setAge(0);
            } else if (entity instanceof Cow cow) {
                cow.setAge(0);
            }

            level.addFreshEntity(entity);
        }
    }

    /**
     * 从控制盒数据文件获取建筑文件名
     */
    public static String getBuildingFileName(ServerLevel level, BlockPos pos) {
        try {
            Path worldPath = level.getServer().getWorldPath(Objects.requireNonNull(net.minecraft.world.level.storage.LevelResource.ROOT));
            Path industrialDir = worldPath.resolve("simukraft").resolve("industrial");
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            String cached = BUILDING_FILE_NAME_CACHE.get(skFile);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }

            String buildingFileName = readBuildingFileName(skFile);
            BUILDING_FILE_NAME_CACHE.put(skFile, buildingFileName == null ? "" : buildingFileName);
            return buildingFileName;
        } catch (Exception e) {
            LOGGER.error(Component.translatable("message.industrial_work_handler.error.read_building_name_failed", e.getMessage()).getString());
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
                    return FileUtils.normalizeBuildingFileName(trimmed.substring(19).trim());
                }
            }
        }

        return null;
    }

    /**
     * 解析物品ID
     */
    private static Item parseItemId(String itemId) {
        try {
            String safeItemId = Objects.requireNonNull(itemId);
            net.minecraft.resources.ResourceLocation itemKey = safeItemId.contains(":")
                    ? net.minecraft.resources.ResourceLocation.tryParse(safeItemId)
                    : net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + safeItemId);
            return itemKey != null ? ForgeRegistries.ITEMS.getValue(itemKey) : null;
        } catch (Exception e) {
            LOGGER.error(Component.translatable("message.industrial_work_handler.error.parse_item_id_failed", itemId).getString());
            return null;
        }
    }

    /**
     * 解析实体类型ID
     */
    private static EntityType<?> parseEntityType(String entityTypeId) {
        try {
            String safeEntityTypeId = Objects.requireNonNull(entityTypeId);
            net.minecraft.resources.ResourceLocation entityKey = safeEntityTypeId.contains(":")
                    ? net.minecraft.resources.ResourceLocation.tryParse(safeEntityTypeId)
                    : net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + safeEntityTypeId);
            return entityKey != null ? ForgeRegistries.ENTITY_TYPES.getValue(entityKey) : null;
        } catch (Exception e) {
            LOGGER.error(Component.translatable("message.industrial_work_handler.error.parse_entity_type_failed", entityTypeId).getString());
            return null;
        }
    }

    /**
     * 获取等级对应的产量加成倍数
     * 从配置读取或默认
     */
    public static float getYieldMultiplierByLevel(int level) {
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
     * 获取等级对应的工作刻间隔（tick）
     * 等级越高，工作刻间隔越短，工作速度越快
     */
    public static long getWorkTickInterval(int level) {
        int safeLevel = Math.max(1, level);
        int baseTicks = 40;
        int bonusPerLevel = getNpcSpeedBonusPerLevelSafe();
        int minTicks = getNpcMinSpeedTicksSafe();
        int adjusted = baseTicks - bonusPerLevel * (safeLevel - 1);
        return Math.max(minTicks, adjusted);
    }

    private static int getNpcSpeedBonusPerLevelSafe() {
        try {
            return ServerConfig.getNpcSpeedBonusPerLevel();
        } catch (IllegalStateException ignored) {
            return 5;
        }
    }

    private static int getNpcMinSpeedTicksSafe() {
        try {
            return ServerConfig.getNpcMinSpeedTicks();
        } catch (IllegalStateException ignored) {
            return 5;
        }
    }

    /**
     * 在控制箱周围寻找箱子并放入物品
     * 使用与建造类类似的箱子检测方式，但不强制加载区块
     */
    private static boolean placeItemInNearbyChest(ServerLevel level, BlockPos buildingPos, Item item, int amount) {
        if (buildingPos == null || level == null || amount <= 0 || item == null) return false;

        // 搜索范围3格
        int range = 3;

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos checkPos = Objects.requireNonNull(buildingPos.offset(x, y, z));
                    
                    // 检查区块是否已加载
                    if (!level.isLoaded(Objects.requireNonNull(checkPos))) {
                        continue;
                    }
                    
                    // 检查该位置是否是容器方块
                    if (ContainerUtils.isContainer(level, checkPos)) {
                        if (tryPlaceItemInChest(level, checkPos, item, amount)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * 尝试将指定数量的物品放入容器
     * 使用非主线程版本，因为IndustrialWorkHandler已经在主线程中运行
     */
    private static boolean tryPlaceItemInChest(ServerLevel level, BlockPos chestPos, Item item, int amount) {
        if (chestPos == null || level == null || amount <= 0 || item == null) return false;

        ItemStack itemStack = new ItemStack(item, amount);

        if (!hasSpaceForItem(level, chestPos, itemStack)) {
            return false;
        }

        // 使用非主线程版本，因为IndustrialWorkHandler.handleDailyWork已经在服务器主线程中运行
        int inserted = ContainerUtils.insertItem(level, chestPos, itemStack);
        return inserted >= amount;
    }

    /**
     * 检查容器是否有足够的空间放置物品
     * 使用非主线程版本，因为IndustrialWorkHandler已经在主线程中运行
     */
    private static boolean hasSpaceForItem(ServerLevel level, BlockPos chestPos, ItemStack itemStack) {
        // 使用非主线程版本，因为IndustrialWorkHandler.handleDailyWork已经在服务器主线程中运行
        return ContainerUtils.canInsertItem(level, chestPos, itemStack);
    }

    /**
     * 添加经验值- 每日工作获得5xp
     */
    private static void addXp(CustomEntity npc, MinecraftServer server, ServerLevel level, IndustrialBuildingConfig config) {
        if (npc == null || server == null || level == null || config == null) return;

        boolean leveledUp = NPCDataManager.addXp(server, npc.getUUID(), 5);

        String safeJobName = Objects.requireNonNull(config.getJobName());
        Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(safeJobName);
        Component jobName = Component.translatable(safeJobName);

        Component message = Component.translatable("message.simukraft.industrial.work_complete", npcName, jobName);
        if (leveledUp) {
            int newLevel = NPCDataManager.getNPCLevel(server, npc.getUUID());
            Component levelUpMsg = Objects.requireNonNull(Component.translatable("message.simukraft.industrial.level_up", newLevel));
            message = Objects.requireNonNull(
                    Component.translatable("message.simukraft.industrial.work_complete_with_xp", npcName, jobName)
            ).append(levelUpMsg);
        }

        sendMessageToMayor(npc, server, message);
    }

    /**
     * 发送工作完成消息到城市群组
     */
    private static void sendWorkMessage(CustomEntity npc, MinecraftServer server, IndustrialBuildingConfig config, boolean placed) {
        if (npc == null || server == null || config == null) return;

        String safeJobName = Objects.requireNonNull(config.getJobName());
        Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(safeJobName);
        Component jobName = Component.translatable(safeJobName);

        Component message = placed
            ? Component.translatable("message.simukraft.industrial.work_complete", npcName, jobName)
            : Component.translatable("message.simukraft.industrial.chest_full", npcName, jobName);

        // 获取NPC所在的城市层级
        net.minecraft.resources.ResourceLocation overworldId = net.minecraft.resources.ResourceLocation.tryParse("minecraft:overworld");
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> overworldKey = overworldId == null
                ? null
                : net.minecraft.resources.ResourceKey.create(
                        Objects.requireNonNull(net.minecraft.core.registries.Registries.DIMENSION),
                        Objects.requireNonNull(overworldId)
                );
        ServerLevel level = overworldKey != null ? server.getLevel(overworldKey) : null;

        if (level != null && npc.getCityId() != null) {
            CityMessageUtils.sendToCityGroup(level.getServer(), npc.getCityId(), message,
                    com.xiaoliang.simukraft.notification.MessageCategory.INDUSTRIAL);
        }
    }

    /**
     * 发送雇佣消息
     */
    private static void sendHireMessage(CustomEntity npc, MinecraftServer server, IndustrialBuildingConfig config) {
        if (npc == null || server == null || config == null) return;

        String safeJobName = Objects.requireNonNull(config.getJobName());
        Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(safeJobName);
        Component jobName = Component.translatable(safeJobName);

        Component message = Component.translatable("message.simukraft.industrial.hired", npcName, jobName);

        sendMessageToMayor(npc, server, message);
    }

    /**
     * 发送消息给NPC所在城市的市长（通过通知服务）
     */
    private static void sendMessageToMayor(CustomEntity npc, MinecraftServer server, Component message) {
        if (npc == null || server == null || message == null) return;

        UUID cityId = npc.getCityId();
        if (cityId == null) {
            LOGGER.warn(Component.translatable("message.industrial_work_handler.error.npc_no_city_id", npc.getFullName()).getString());
            return;
        }

        CityMessageUtils.sendToMayorViaService(server, cityId,
                net.minecraft.network.chat.Component.translatable("notify.title.industrial"), message,
                com.xiaoliang.simukraft.notification.MessageCategory.INDUSTRIAL);
    }



    /**
     * 获取建筑配置（供其他类使用）
     */
    public static IndustrialBuildingConfig getBuildingConfig(String buildingId) {
        return IndustrialBuildingManager.getConfig(buildingId);
    }
}
