package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.block.NSUKFarmlandBoxBlock;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.farmland.CropDefinition;
import com.xiaoliang.simukraft.farmland.CropLayoutType;
import com.xiaoliang.simukraft.farmland.CropRegistry;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FarmerDailyWorkHandler {
    private static boolean dataInitialized = false;

    // 湿润度保持频率（每1秒执行一次，确保土地始终保持湿润）
    private static final int MOISTURE_INTERVAL_MS = 1000; // 1秒 = 1000毫秒
    private static final int GROWTH_INTERVAL_MS = 15000; // 15秒 = 15000毫秒
    private static final int HARVEST_INTERVAL_MS = 15000; // 15秒 = 15000毫秒

    // 为每个农田盒创建独立的工作计时器，支持多个农民同时工作
    private static final Map<BlockPos, Long> farmlandLastMoistureTime = new HashMap<>();
    private static final Map<BlockPos, Long> farmlandLastGrowthTime = new HashMap<>();
    private static final Map<BlockPos, Long> farmlandLastHarvestTime = new HashMap<>();

    // 存储农民每日经验值获取记录 (NPC UUID -> 游戏天数)
    private static final Map<java.util.UUID, Long> farmerDailyXp = new HashMap<>();
    // 添加服务器启动时的数据加载方法
    public static void onServerStart(MinecraftServer server) {
        if (server == null) return;

        // 重置初始化状态，确保数据重新加载
        dataInitialized = false;
        farmlandLastMoistureTime.clear();
        farmlandLastGrowthTime.clear();
        farmlandLastHarvestTime.clear();
        farmerDailyXp.clear();

        // 新增：服务器启动后恢复所有农民的工作状态
        restoreAllFarmersWorkState(server);
    }

    /**
     * 处理农民每日经验值获取
     * 在每天结束时调用，给工作的农民增加5xp
     */
    public static void handleDailyXp(ServerLevel level) {
        if (level == null) return;

        MinecraftServer server = level.getServer();

        long currentDay = level.getDayTime() / 24000;

        // 获取农田盒雇佣数据
        var hiredEmployeeMap = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();

        for (Map.Entry<BlockPos, java.util.UUID> entry : hiredEmployeeMap.entrySet()) {
            BlockPos farmlandBoxPos = entry.getKey();
            java.util.UUID npcUuid = entry.getValue();

            // 检查今天是否已经给这个农民加过经验值
            Long lastXpDay = farmerDailyXp.get(npcUuid);
            if (lastXpDay != null && lastXpDay == currentDay) {
                continue; // 今天已经加过经验值了
            }

            // 查找NPC实体
            CustomEntity npc = com.xiaoliang.simukraft.world.FarmlandHiredData.findNPCByUuid(server, npcUuid);
            if (npc != null && npc.isAlive() && "farmer".equals(npc.getJob())) {
                // 增加5点经验值
                boolean leveledUp = NPCDataManager.addXp(server, npcUuid, 5);

                // 记录今天已经加过经验值
                farmerDailyXp.put(npcUuid, currentDay);

                // 发送消息给市长（使用翻译键，支持多语言）
                Component message;
                Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.translatable("message.simukraft.npc.unknown");
                if (leveledUp) {
                    int newLevel = NPCDataManager.getNPCLevel(server, npcUuid);
                    message = Component.translatable("message.simukraft.npc.level_up.work_completed", npcName, newLevel);
                } else {
                    message = Component.translatable("message.simukraft.npc.work_completed", npcName);
                }

                // 发送消息给市长
                sendMessageToMayor(server, level, farmlandBoxPos, message);
            }
        }

        // 清理过期的记录（3天前的记录）
        farmerDailyXp.entrySet().removeIf(entry -> entry.getValue() < currentDay - 3);
    }

    private static void sendMessageToMayor(MinecraftServer server, ServerLevel level, BlockPos farmlandBoxPos, Component message) {
        if (server == null || level == null) return;

        // 从SK文件获取农田盒所在城市ID
        java.util.UUID cityId = getFarmlandBoxCityId(server, farmlandBoxPos);
        if (cityId == null) return;

        CityMessageUtils.sendToMayorViaService(server, cityId,
                net.minecraft.network.chat.Component.translatable("notify.title.farming"),
                message,
                com.xiaoliang.simukraft.notification.MessageCategory.FARMING);
    }

    private static java.util.UUID getFarmlandBoxCityId(MinecraftServer server, BlockPos farmlandBoxPos) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(
                    Objects.requireNonNull(net.minecraft.world.level.storage.LevelResource.ROOT)
            );
            java.nio.file.Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            java.nio.file.Path fruitDir = simukraftDir.resolve(FileUtils.FRUIT_DIR);

            String fileName = farmlandBoxPos.getX() + "_" + farmlandBoxPos.getY() + "_" + farmlandBoxPos.getZ() + ".sk";
            java.nio.file.Path skFile = fruitDir.resolve(fileName);

            if (!java.nio.file.Files.exists(skFile)) {
                return null;
            }

            try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(skFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("city_id: ")) {
                        String cityIdStr = line.substring("city_id: ".length()).trim();
                        try {
                            return java.util.UUID.fromString(cityIdStr);
                        } catch (IllegalArgumentException e) {
                            return null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (ServerConfig.isDebugLogEnabled()) {
                Simukraft.LOGGER.debug("[FarmerDailyWorkHandler] Failed to resolve cityId for farmland box {}", farmlandBoxPos, e);
            }
        }
        return null;
    }

    /**
     * 服务器启动后恢复所有农民的工作状态
     */
    private static void restoreAllFarmersWorkState(MinecraftServer server) {
        if (server == null) return;

        // 遍历所有世界，恢复农民状态
        for (ServerLevel level : server.getAllLevels()) {
            // 获取农田盒雇佣数据
            var hiredEmployeeMap = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();

            for (Map.Entry<BlockPos, java.util.UUID> entry : hiredEmployeeMap.entrySet()) {
                BlockPos farmlandBoxPos = Objects.requireNonNull(entry.getKey());
                java.util.UUID npcUuid = entry.getValue();

                // 检查农田盒是否仍然存在
                BlockState farmlandBoxState = level.getBlockState(Objects.requireNonNull(farmlandBoxPos));
                if (!isFarmlandBoxBlock(farmlandBoxState.getBlock())) {
                    continue;
                }

                // 查找NPC实体
                CustomEntity npc = com.xiaoliang.simukraft.world.FarmlandHiredData.findNPCByUuid(server, npcUuid);
                if (npc != null && npc.isAlive() && "farmer".equals(npc.getJob())) {
                    // 恢复农民工作状态
                    restoreFarmerWorkState(npc, farmlandBoxPos, level);

                    // 发送状态恢复消息到城市群组
                    Component npcNameComp = npc.getCustomName() != null ? npc.getCustomName() : Component.translatable("message.simukraft.npc.unknown");
                    Component message = Component.translatable("message.simukraft.farmer.server_restart", npcNameComp);
                    sendChatMessage(server, npc.getCityId(), message);
                }
            }
        }
    }

    /**
     * 修复农民状态，确保重启后和传送后能正常工作
     */
    public static void restoreFarmerWorkState(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        if (npc == null || farmlandBoxPos == null || level == null) return;

        // simukraft: 午休期间不恢复农民工作状态，也不传送
        if (npc.getWorkSubState() == com.xiaoliang.simukraft.entity.WorkSubState.LUNCH_BREAK) {
            return;
        }

        // 检查NPC当前状态，如果是空闲，强制恢复为工作中
        if (npc.getWorkStatus() == WorkStatus.IDLE) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
        }

        // 确保NPC在农田盒附近工作
        BlockPos npcPos = npc.blockPosition();
        double distance = npcPos.distSqr(farmlandBoxPos);

        // 如果NPC距离农田盒太远（超过20格），传送到农田盒附近
        if (distance > 400) { // 20格距离的平方
            BlockPos targetPos = findSafePositionNearFarmland(farmlandBoxPos, level);
            if (targetPos != null && !npc.moveToWithNewPathfinder(targetPos, 1.0D)) {
                npc.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
                npc.stopNewPathfinder();
            }
        }
    }

    /**
     * 在农田盒附近寻找安全位置
     */
    private static BlockPos findSafePositionNearFarmland(BlockPos farmlandBoxPos, ServerLevel level) {
        BlockPos safeFarmlandBoxPos = Objects.requireNonNull(farmlandBoxPos);
        // 在农田盒周围5格范围内寻找安全位置
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                BlockPos checkPos = safeFarmlandBoxPos.offset(x, 0, z);
                BlockPos abovePos = checkPos.above();
                BlockPos belowPos = checkPos.below();

                // 检查位置是否安全（下方有固体方块，上方有2格空间）
                if (level.getBlockState(Objects.requireNonNull(belowPos))
                        .isFaceSturdy(level, Objects.requireNonNull(belowPos), net.minecraft.core.Direction.UP) &&
                        level.isEmptyBlock(Objects.requireNonNull(checkPos)) &&
                        level.isEmptyBlock(Objects.requireNonNull(abovePos))) {
                    return checkPos;
                }
            }
        }
        return safeFarmlandBoxPos.above(); // 默认返回农田盒上方
    }

    /**
     * 处理农民的持续工作（每tick检查，但按不同频率执行不同功能）
     * 修改为使用系统时间，实现全天候工作
     * 使用多线程调度器优化性能
     */
    public static void handleContinuousWork(ServerLevel level) {
        if (level == null) return;

        // 服务器端不需要客户端GUI数据，简化处理
        if (!dataInitialized) {
            dataInitialized = true;
        }

        // 使用系统时间而不是游戏时间，实现全天候工作
        long currentTime = System.currentTimeMillis();

        // 获取农田盒雇佣数据
        var hiredEmployeeMap = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        List<BlockPos> invalidFarmlandPositions = new ArrayList<>();

        // 收集需要处理的工作项
        List<FarmWorkItem> workItems = new ArrayList<>();

        for (Map.Entry<BlockPos, java.util.UUID> entry : hiredEmployeeMap.entrySet()) {
            BlockPos farmlandBoxPos = Objects.requireNonNull(entry.getKey());
            java.util.UUID npcUuid = entry.getValue();

            // 关键修复：检查农田盒是否仍然存在
            BlockState farmlandBoxState = level.getBlockState(Objects.requireNonNull(farmlandBoxPos));
            if (!isFarmlandBoxBlock(farmlandBoxState.getBlock())) {
                if (existsFarmlandBoxInAnotherLoadedLevel(level.getServer(), level, farmlandBoxPos)) {
                    continue;
                }
                // 农田盒不存在，标记为需要清理
                invalidFarmlandPositions.add(farmlandBoxPos);
                continue;
            }

            // 关键修复：查找实际的NPC实体并进行严格验证
            CustomEntity npc = com.xiaoliang.simukraft.world.FarmlandHiredData.findNPCByUuid(level.getServer(), npcUuid);
            if (npc == null) {
                // NPC可能在视距外，尝试从NPC数据管理器验证数据有效性
                String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(level.getServer(), npcUuid);
                if (npcName != null) {
                    // 数据有效，继续处理但不执行实际工作（因为NPC不在视距内）
                    continue;
                } else {
                    // NPC数据无效，标记为需要清理
                    invalidFarmlandPositions.add(farmlandBoxPos);
                    continue;
                }
            } else if (!npc.isAlive() || !"farmer".equals(npc.getJob())) {
                // NPC存在但状态无效，标记为需要清理
                invalidFarmlandPositions.add(farmlandBoxPos);
                continue;
            }

            // 新增：状态恢复机制 - 确保农民始终处于工作状态
            restoreFarmerWorkState(npc, farmlandBoxPos, level);

            // 额外验证：检查NPC是否真的在农田盒附近工作
            double distance = npc.distanceToSqr(farmlandBoxPos.getX() + 0.5, farmlandBoxPos.getY() + 0.5, farmlandBoxPos.getZ() + 0.5);
            if (distance > 16384.0) { // 放宽到128格距离的平方，避免视距外雇佣被错误清理
                continue;
            }

            // 添加到工作项列表
            workItems.add(new FarmWorkItem(npc, farmlandBoxPos, currentTime));
        }

        // 清理无效的农田盒数据
        if (!invalidFarmlandPositions.isEmpty()) {
            cleanInvalidFarmlandData(level.getServer(), invalidFarmlandPositions);
        }

        // 使用多线程调度器处理农田工作
        processFarmWorkAsync(level, workItems);
    }

    private static boolean existsFarmlandBoxInAnotherLoadedLevel(MinecraftServer server, ServerLevel currentLevel, BlockPos farmlandBoxPos) {
        if (server == null || farmlandBoxPos == null) return false;
        for (ServerLevel otherLevel : server.getAllLevels()) {
            if (otherLevel == currentLevel) continue;
            if (isFarmlandBoxBlock(otherLevel.getBlockState(farmlandBoxPos).getBlock())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 农田工作项类
     */
    private static class FarmWorkItem {
        final CustomEntity npc;
        final BlockPos farmlandBoxPos;
        final long currentTime;

        FarmWorkItem(CustomEntity npc, BlockPos farmlandBoxPos, long currentTime) {
            this.npc = npc;
            this.farmlandBoxPos = farmlandBoxPos;
            this.currentTime = currentTime;
        }
    }

    /**
     * 异步处理农田工作
     * 预处理在后台线程执行，方块操作在主线程执行
     */
    private static void processFarmWorkAsync(ServerLevel level, List<FarmWorkItem> workItems) {
        if (workItems.isEmpty()) return;

        // 分批处理，每批最多10个农田盒
        int batchSize = 10;
        for (int i = 0; i < workItems.size(); i += batchSize) {
            List<FarmWorkItem> batch = workItems.subList(i, Math.min(i + batchSize, workItems.size()));

            NPCTaskScheduler.submitTask(() -> {
                // 在后台线程预处理每个农田盒的工作
                for (FarmWorkItem item : batch) {
                    try {
                        preprocessFarmWork(item.npc, item.farmlandBoxPos, item.currentTime);
                    } catch (Exception e) {
                        // 忽略异常，继续处理下一个
                    }
                }
            }, "FarmWorkBatch-" + (i / batchSize));
        }
    }

    /**
     * 预处理农田工作
     * 在后台线程执行，确定需要执行的操作
     */
    private static void preprocessFarmWork(CustomEntity npc, BlockPos farmlandBoxPos, long currentTime) {
        if (npc == null || farmlandBoxPos == null) return;

        // 初始化农田盒的计时器（如果不存在）
        farmlandLastMoistureTime.putIfAbsent(farmlandBoxPos, currentTime);
        farmlandLastGrowthTime.putIfAbsent(farmlandBoxPos, currentTime);
        farmlandLastHarvestTime.putIfAbsent(farmlandBoxPos, currentTime);

        // 检查是否需要保持湿润
        boolean needMoisture = currentTime - farmlandLastMoistureTime.get(farmlandBoxPos) >= MOISTURE_INTERVAL_MS;
        // 检查是否需要加速生长
        boolean needGrowth = currentTime - farmlandLastGrowthTime.get(farmlandBoxPos) >= GROWTH_INTERVAL_MS;
        // 检查是否需要收获
        boolean needHarvest = currentTime - farmlandLastHarvestTime.get(farmlandBoxPos) >= HARVEST_INTERVAL_MS;

        // 更新计时器
        if (needMoisture) farmlandLastMoistureTime.put(farmlandBoxPos, currentTime);
        if (needGrowth) farmlandLastGrowthTime.put(farmlandBoxPos, currentTime);
        if (needHarvest) farmlandLastHarvestTime.put(farmlandBoxPos, currentTime);

        // 提交实际工作到主线程执行（涉及方块操作）
        if (needMoisture || needGrowth || needHarvest) {
            final boolean finalNeedMoisture = needMoisture;
            final boolean finalNeedGrowth = needGrowth;
            final boolean finalNeedHarvest = needHarvest;

            NPCTaskScheduler.enqueueMainThreadTask(
                    () -> executeFarmWork(npc, farmlandBoxPos, finalNeedMoisture, finalNeedGrowth, finalNeedHarvest),
                    "ExecuteFarmWork-" + farmlandBoxPos
            );
        }
    }

    /**
     * 执行农田工作
     * 在主线程执行，涉及方块操作
     */
    private static void executeFarmWork(CustomEntity npc, BlockPos farmlandBoxPos,
                                        boolean needMoisture, boolean needGrowth, boolean needHarvest) {
        if (npc == null || !npc.isAlive()) return;

        ServerLevel level = (ServerLevel) npc.level();
        if (level == null) return;

        // 检查农田盒是否仍然存在
        BlockState farmlandBoxState = level.getBlockState(Objects.requireNonNull(farmlandBoxPos));
        if (!isFarmlandBoxBlock(farmlandBoxState.getBlock())) {
            return;
        }

        // 执行保持湿润
        if (needMoisture) {
            if (!keepManagedFarmlandMoistForFarm(npc, farmlandBoxPos, level)) {
                keepFarmlandMoistForFarm(npc, farmlandBoxPos, level);
            }
        }

        // 执行加速生长
        if (needGrowth) {
            if (!boostManagedCropGrowthForFarm(npc, farmlandBoxPos, level)) {
                boostCropGrowthForFarm(npc, farmlandBoxPos, level);
            }
        }

        // 执行收获和重新种植
        if (needHarvest) {
            if (!harvestAndReplantManagedForFarm(npc, farmlandBoxPos, level)) {
                harvestAndReplantForFarm(npc, farmlandBoxPos, level);
            }
        }
    }

    /**
     * 处理单个农田盒的工作（独立计时器，支持多个农民同时工作）
     */
    private static boolean keepManagedFarmlandMoistForFarm(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        FarmlandPlot plot = getManagedPlot(farmlandBoxPos);
        if (plot == null || farmlandBoxPos == null || level == null || npc == null) return false;

        int farmlandMoistened = 0;
        for (BlockPos pos : plot.positions()) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() == Blocks.FARMLAND) {
                BlockState moistState = Objects.requireNonNull(state.setValue(Objects.requireNonNull(FarmBlock.MOISTURE), 7));
                level.setBlock(pos, moistState, 3);
                farmlandMoistened++;
            }
        }
        return true;
    }

    private static boolean boostManagedCropGrowthForFarm(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        ManagedCropContext context = getManagedCropContext(farmlandBoxPos);
        if (context == null || level == null || npc == null || !ServerConfig.isFarmerCropGrowthBoostEnabled()) return false;

        int cropsBoosted = 0;
        for (BlockPos farmlandPos : context.plot.positions()) {
            if (!context.plot.shouldPlantAt(farmlandPos, context.definition.layoutType())) continue;
            BlockPos cropPos = farmlandPos.above();
            BlockState state = level.getBlockState(cropPos);
            if (state.getBlock() == context.definition.cropBlock() && boostCropState(level, cropPos, state)) {
                cropsBoosted++;
            }
        }
        return true;
    }

    private static boolean harvestAndReplantManagedForFarm(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        ManagedCropContext context = getManagedCropContext(farmlandBoxPos);
        if (context == null || level == null || npc == null) return false;

        BlockPos boundChestPos = FarmlandManager.getBoundChestIfValid(level, farmlandBoxPos);
        if (boundChestPos == null) {
            return true;
        }

        int npcLevel = NPCDataManager.getNPCLevel(level.getServer(), npc.getUUID());
        int cropsHarvested = 0;
        for (BlockPos farmlandPos : context.plot.positions()) {
            if (!context.plot.shouldPlantAt(farmlandPos, context.definition.layoutType())) continue;
            BlockPos cropPos = farmlandPos.above();
            BlockState state = level.getBlockState(cropPos);
            if (state.getBlock() == context.definition.cropBlock() && isManagedCropMature(state, context.definition)) {
                if (harvestCrop(level, cropPos, state, boundChestPos, npcLevel)) {
                    cropsHarvested++;
                    replantManagedCrop(level, cropPos, context.definition, boundChestPos);
                }
            }
            if (context.definition.layoutType() == CropLayoutType.CHECKERBOARD) {
                cropsHarvested += harvestManagedStemFruits(level, farmlandPos, context.definition, boundChestPos, npcLevel);
            }
        }
        return true;
    }

    private record ManagedCropContext(FarmlandPlot plot, CropDefinition definition) {}

    private static FarmlandPlot getManagedPlot(BlockPos farmlandBoxPos) {
        if (farmlandBoxPos == null) return null;
        return com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedPlot(farmlandBoxPos);
    }

    private static ManagedCropContext getManagedCropContext(BlockPos farmlandBoxPos) {
        FarmlandPlot plot = getManagedPlot(farmlandBoxPos);
        if (plot == null) return null;
        String cropId = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedCrop(farmlandBoxPos);
        CropDefinition definition = CropRegistry.resolve(cropId).orElse(null);
        if (definition == null) return null;
        return new ManagedCropContext(plot, definition);
    }

    private static boolean boostCropState(ServerLevel level, BlockPos cropPos, BlockState state) {
        Block block = state.getBlock();
        if (!(block instanceof net.minecraft.world.level.block.CropBlock cropBlock)) return false;
        if (level.random.nextFloat() >= 0.7f) return false;
        if (!cropBlock.isMaxAge(state)) {
            level.setBlock(cropPos, cropBlock.getStateForAge(cropBlock.getAge(state) + 1), 3);
            return true;
        }
        return false;
    }

    private static boolean isManagedCropMature(BlockState state, CropDefinition definition) {
        if (definition.cropBlock() instanceof net.minecraft.world.level.block.CropBlock cropBlock) {
            return cropBlock.isMaxAge(state);
        }
        return false;
    }

    private static boolean replantManagedCrop(ServerLevel level, BlockPos cropPos, CropDefinition definition, BlockPos chestPos) {
        if (definition.layoutType() == CropLayoutType.CHECKERBOARD) return true;
        if (!ContainerUtils.consumeItem(level, chestPos, new ItemStack(definition.seedItem()))) return false;
        level.setBlock(Objects.requireNonNull(cropPos), Objects.requireNonNull(definition.cropBlock().defaultBlockState()), 3);
        return true;
    }

    private static int harvestManagedStemFruits(ServerLevel level, BlockPos stemFarmlandPos, CropDefinition definition, BlockPos chestPos, int npcLevel) {
        Block fruitBlock = getFruitBlockForStem(definition.cropBlock());
        if (fruitBlock == null) return 0;

        int harvested = 0;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos fruitPos = stemFarmlandPos.above().relative(direction);
            BlockState fruitState = level.getBlockState(fruitPos);
            if (fruitState.getBlock() == fruitBlock && harvestCrop(level, fruitPos, fruitState, chestPos, npcLevel)) {
                harvested++;
            }
        }
        return harvested;
    }

    private static Block getFruitBlockForStem(Block stemBlock) {
        if (stemBlock == Blocks.MELON_STEM) return Blocks.MELON;
        if (stemBlock == Blocks.PUMPKIN_STEM) return Blocks.PUMPKIN;
        return null;
    }

    /**
     * 为单个农田盒保持耕地湿润
     */
    private static int keepFarmlandMoistForFarm(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        if (farmlandBoxPos == null || level == null || npc == null) return 0;

        // 关键修复：检查农田盒是否仍然存在
        BlockState farmlandBoxState = level.getBlockState(Objects.requireNonNull(farmlandBoxPos));
        if (!isFarmlandBoxBlock(farmlandBoxState.getBlock())) {
            return 0;
        }

        // 获取玩家选择的区域大小
        int areaSize = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedAreaSize(farmlandBoxPos);
        // 扩大搜索范围以覆盖所有可能的耕地位置（农田盒前方1格 + 区域大小 + 额外边距）
        int searchRadius = areaSize + 2;

        // 在农田盒周围指定范围内寻找耕地
        AABB searchArea = new AABB(farmlandBoxPos).inflate(searchRadius);
        int farmlandMoistened = 0;

        // 获取搜索区域的边界坐标
        BlockPos minPos = new BlockPos(
                (int) Math.floor(searchArea.minX),
                (int) Math.floor(searchArea.minY),
                (int) Math.floor(searchArea.minZ)
        );
        BlockPos maxPos = new BlockPos(
                (int) Math.floor(searchArea.maxX),
                (int) Math.floor(searchArea.maxY),
                (int) Math.floor(searchArea.maxZ)
        );

        // 获取搜索区域内的所有方块位置
        for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
            BlockPos safePos = Objects.requireNonNull(pos);
            BlockState state = level.getBlockState(safePos);

            // 检查是否是耕地
            if (state.getBlock() == Blocks.FARMLAND) {
                // 将耕地湿润度设为最大值7（确保土地始终保持湿润）
                BlockState moistState = Objects.requireNonNull(
                        state.setValue(Objects.requireNonNull(FarmBlock.MOISTURE), 7)
                );
                level.setBlock(safePos, moistState, 3);
                farmlandMoistened++;
            }
        }

        return farmlandMoistened;
    }

    /**
     * 为单个农田盒加速作物生长
     */
    private static int boostCropGrowthForFarm(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        if (farmlandBoxPos == null || level == null || npc == null) return 0;

        // 关键修复：检查农田盒是否仍然存在
        BlockState farmlandBoxState = level.getBlockState(Objects.requireNonNull(farmlandBoxPos));
        if (!isFarmlandBoxBlock(farmlandBoxState.getBlock())) {
            return 0;
        }

        // 获取玩家选择的区域大小
        int areaSize = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedAreaSize(farmlandBoxPos);
        // 扩大搜索范围以覆盖所有可能的作物位置（农田盒前方1格 + 区域大小 + 额外边距）
        int searchRadius = areaSize + 2;

        // 在农田盒周围指定范围内寻找作物
        AABB searchArea = new AABB(farmlandBoxPos).inflate(searchRadius);
        int cropsBoosted = 0;

        // 获取搜索区域的边界坐标
        BlockPos minPos = new BlockPos(
                (int) Math.floor(searchArea.minX),
                (int) Math.floor(searchArea.minY),
                (int) Math.floor(searchArea.minZ)
        );
        BlockPos maxPos = new BlockPos(
                (int) Math.floor(searchArea.maxX),
                (int) Math.floor(searchArea.maxY),
                (int) Math.floor(searchArea.maxZ)
        );

        // 获取搜索区域内的所有方块位置
        for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
            BlockPos safePos = Objects.requireNonNull(pos);
            BlockState state = level.getBlockState(safePos);
            Block block = state.getBlock();

            // 检查是否是作物（小麦、胡萝卜、马铃薯、西瓜茎等）
            if (isCropBlock(block)) {
                if (!ServerConfig.isFarmerCropGrowthBoostEnabled()) {
                    continue;
                }

                // 更高效的50%加速：每次有更高概率让作物生长
                if (level.random.nextFloat() < 0.7f) { // 70%概率加速
                    // 尝试让作物生长
                    if (block == Blocks.WHEAT) {
                        int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.CropBlock.AGE));
                        if (currentAge < 7) {
                            BlockState nextState = Objects.requireNonNull(
                                    state.setValue(Objects.requireNonNull(net.minecraft.world.level.block.CropBlock.AGE), currentAge + 1)
                            );
                            level.setBlock(safePos, nextState, 3);
                            cropsBoosted++;
                        }
                    } else if (block == Blocks.CARROTS) {
                        int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.CarrotBlock.AGE));
                        if (currentAge < 7) {
                            BlockState nextState = Objects.requireNonNull(
                                    state.setValue(Objects.requireNonNull(net.minecraft.world.level.block.CarrotBlock.AGE), currentAge + 1)
                            );
                            level.setBlock(safePos, nextState, 3);
                            cropsBoosted++;
                        }
                    } else if (block == Blocks.POTATOES) {
                        int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.PotatoBlock.AGE));
                        if (currentAge < 7) {
                            BlockState nextState = Objects.requireNonNull(
                                    state.setValue(Objects.requireNonNull(net.minecraft.world.level.block.PotatoBlock.AGE), currentAge + 1)
                            );
                            level.setBlock(safePos, nextState, 3);
                            cropsBoosted++;
                        }
                    } else if (block == Blocks.BEETROOTS) {
                        int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.BeetrootBlock.AGE));
                        if (currentAge < 3) {
                            BlockState nextState = Objects.requireNonNull(
                                    state.setValue(Objects.requireNonNull(net.minecraft.world.level.block.BeetrootBlock.AGE), currentAge + 1)
                            );
                            level.setBlock(safePos, nextState, 3);
                            cropsBoosted++;
                        }
                    } else if (block == Blocks.MELON_STEM || block == Blocks.PUMPKIN_STEM) {
                        int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.StemBlock.AGE));
                        if (currentAge < 7) {
                            BlockState nextState = Objects.requireNonNull(
                                    state.setValue(Objects.requireNonNull(net.minecraft.world.level.block.StemBlock.AGE), currentAge + 1)
                            );
                            level.setBlock(safePos, nextState, 3);
                            cropsBoosted++;
                        }
                    }
                }
            }
        }

        return cropsBoosted;
    }

    /**
     * 为单个农田盒收获和重新种植作物
     */
    private static int harvestAndReplantForFarm(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        if (farmlandBoxPos == null || level == null || npc == null) return 0;

        // 关键修复：检查农田盒是否仍然存在
        BlockState farmlandBoxState = level.getBlockState(farmlandBoxPos);
        if (!isFarmlandBoxBlock(farmlandBoxState.getBlock())) {
            return 0;
        }

        // 获取玩家选择的区域大小
        int areaSize = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedAreaSize(farmlandBoxPos);
        // 扩大搜索范围以覆盖所有可能的作物位置（农田盒前方1格 + 区域大小 + 额外边距）
        int searchRadius = areaSize + 2;

        // 在农田盒周围指定范围内寻找作物
        AABB searchArea = new AABB(farmlandBoxPos).inflate(searchRadius);
        int cropsHarvested = 0;

        // 获取绑定的箱子
        BlockPos boundChestPos = FarmlandManager.getBoundChestIfValid(level, farmlandBoxPos);

        // 检查容器是否存在
        if (boundChestPos == null) {
            return 0;
        }

        // 获取NPC等级
        int npcLevel = NPCDataManager.getNPCLevel(level.getServer(), npc.getUUID());

        // 获取搜索区域的边界坐标
        BlockPos minPos = new BlockPos(
                (int) Math.floor(searchArea.minX),
                (int) Math.floor(searchArea.minY),
                (int) Math.floor(searchArea.minZ)
        );
        BlockPos maxPos = new BlockPos(
                (int) Math.floor(searchArea.maxX),
                (int) Math.floor(searchArea.maxY),
                (int) Math.floor(searchArea.maxZ)
        );

        // 获取搜索区域内的所有方块位置
        for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
            BlockPos safePos = Objects.requireNonNull(pos);
            BlockState state = level.getBlockState(safePos);
            Block block = state.getBlock();

            // 检查是否是作物且已成熟
                if (isCropBlock(block) && isMatureCrop(state, block)) {
                    // 立即收获作物（传入NPC等级）
                    if (harvestCrop(level, safePos, state, boundChestPos, npcLevel)) {
                        cropsHarvested++;

                        // 立即重新种植作物
                        replantCrop(level, safePos, block, boundChestPos);
                    }
                }
        }

        return cropsHarvested;
    }

    /**
     * 清理无效的农田盒数据
     */
    private static void cleanInvalidFarmlandData(MinecraftServer server, List<BlockPos> invalidPositions) {
        if (server == null || invalidPositions.isEmpty()) return;

        for (BlockPos invalidPos : invalidPositions) {
            ServerLevel actualLevel = findLevelWithFarmlandBox(server, invalidPos);
            if (actualLevel != null) {
                continue;
            }
            FarmlandManager.invalidateWorkflow(server.overworld(), invalidPos, null, false);
        }
    }

    private static ServerLevel findLevelWithFarmlandBox(MinecraftServer server, BlockPos farmlandBoxPos) {
        if (server == null || farmlandBoxPos == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (isFarmlandBoxBlock(level.getBlockState(farmlandBoxPos).getBlock())) {
                return level;
            }
        }
        return null;
    }

    public static void clearTimers(BlockPos farmlandBoxPos) {
        farmlandLastMoistureTime.remove(farmlandBoxPos);
        farmlandLastGrowthTime.remove(farmlandBoxPos);
        farmlandLastHarvestTime.remove(farmlandBoxPos);
    }

    public static void invalidateFarmWorkflow(ServerLevel level, BlockPos farmlandBoxPos, CustomEntity npc, boolean workBlockRemoved) {
        FarmlandManager.invalidateWorkflow(level, farmlandBoxPos, npc, workBlockRemoved);
    }

    /**
     * 检查作物是否成熟
     */
    private static boolean isMatureCrop(BlockState state, Block block) {
        if (block == Blocks.WHEAT) {
            int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.CropBlock.AGE));
            return currentAge >= 7;
        } else if (block == Blocks.CARROTS) {
            int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.CarrotBlock.AGE));
            return currentAge >= 7;
        } else if (block == Blocks.POTATOES) {
            int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.PotatoBlock.AGE));
            return currentAge >= 7;
        } else if (block == Blocks.BEETROOTS) {
            int currentAge = state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.BeetrootBlock.AGE));
            return currentAge >= 3;
        } else if (block == Blocks.MELON_STEM || block == Blocks.PUMPKIN_STEM) {
            // 修复：藤蔓（茎）不应该被收获，它们只是生成西瓜/南瓜的载体
            // 即使成熟也不应该收获，只收获生成的方块
            return false;
        } else if (block == Blocks.MELON || block == Blocks.PUMPKIN) {
            // 修复：西瓜和南瓜方块总是成熟的（它们是由茎生成的）
            return true;
        }
        return false;
    }

    /**
     * 自动收获成熟作物并重新种植（使用AABB搜索区域，确保覆盖整个设置的范围）
     */
    /**
     * 检查是否是作物方块
     */
    private static boolean isCropBlock(Block block) {
        return block == Blocks.WHEAT ||
                block == Blocks.CARROTS ||
                block == Blocks.POTATOES ||
                block == Blocks.MELON_STEM ||
                block == Blocks.PUMPKIN_STEM ||
                block == Blocks.BEETROOTS ||
                block == Blocks.MELON ||
                block == Blocks.PUMPKIN;
    }

    /**
     * 收获作物（支持Container接口和IItemHandler Capability）
     */
    private static boolean harvestCrop(ServerLevel level, BlockPos pos, BlockState state, BlockPos chestPos, int npcLevel) {
        Block block = state.getBlock();

        // 根据作物类型获取收获物品（传入NPC等级以获得产量加成）
        List<ItemStack> harvestItems = getHarvestItems(block, level.random, npcLevel);
        if (harvestItems.isEmpty()) {
            return false;
        }

        List<ItemStack> remainingDrops = new ArrayList<>();
        for (ItemStack harvestItem : harvestItems) {
            int inserted = ContainerUtils.insertItem(level, chestPos, harvestItem);
            if (inserted < harvestItem.getCount()) {
                ItemStack remaining = harvestItem.copy();
                remaining.shrink(inserted);
                remainingDrops.add(remaining);
            }
        }

        // 先销毁作物，再把无法入库的剩余掉落到地面，避免“部分入库成功但剩余物品被吞”
        BlockPos safePos = Objects.requireNonNull(pos);
        level.destroyBlock(safePos, false);
        for (ItemStack remainingDrop : remainingDrops) {
            Block.popResource(level, safePos, Objects.requireNonNull(remainingDrop));
        }
        return true;
    }

    /**
     * 重新种植作物（支持Container接口和IItemHandler Capability）
     */
    private static boolean replantCrop(ServerLevel level, BlockPos pos, Block originalCrop, BlockPos chestPos) {
        // 修复：西瓜和南瓜方块不需要重新种植，因为茎还在
        if (originalCrop == Blocks.MELON || originalCrop == Blocks.PUMPKIN) {
            return true; // 直接返回成功，不需要重新种植
        }

        // 获取对应作物的种子
        ItemStack seeds = getSeedsForCrop(originalCrop);

        if (!seeds.isEmpty() && chestPos != null) {
            // 检查容器中是否有足够的种子
            if (hasItemInChest(level, chestPos, seeds.getItem())) {
                // 消耗种子并重新种植
                if (consumeSeedFromChest(level, chestPos, seeds.getItem())) {
                    // 重新种植作物
                    level.setBlock(
                            Objects.requireNonNull(pos),
                            Objects.requireNonNull(originalCrop.defaultBlockState()),
                            3
                    );
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 获取等级对应的产量加成倍数
     * 1级: 100%, 2级: 110%, 3级: 125%, 4级: 140%, 5级: 160%, 6级: 180%, 7级: 200%
     * 8级及以上: 每级额外增加10%，最高300%
     */
    private static float getYieldMultiplierByLevel(int level) {
        return switch (level) {
            case 1 -> 1.0f;
            case 2 -> 1.1f;
            case 3 -> 1.25f;
            case 4 -> 1.4f;
            case 5 -> 1.6f;
            case 6 -> 1.8f;
            case 7 -> 2.0f;
            default -> Math.min(3.0f, 2.0f + (level - 7) * 0.1f); // 8级及以上每级+10%，最高300%
        };
    }

    /**
     * 获取作物的收获物品（降低后的基础产量，带等级加成）
     */
    private static List<ItemStack> getHarvestItems(Block crop, net.minecraft.util.RandomSource random, int npcLevel) {
        List<ItemStack> items = new ArrayList<>();
        float multiplier = getYieldMultiplierByLevel(npcLevel);

        if (crop == Blocks.WHEAT) {
            // 降低后的小麦掉落：1-2个小麦（原为1-4），0-2个种子（原为0-3）
            int wheatCount = (int) ((1 + random.nextInt(2)) * multiplier); // 1-2个小麦 * 加成
            int seedCount = (int) (random.nextInt(3) * multiplier); // 0-2个种子 * 加成

            if (wheatCount > 0) {
                items.add(new ItemStack(Objects.requireNonNull(Items.WHEAT), wheatCount));
            }
            if (seedCount > 0) {
                items.add(new ItemStack(Objects.requireNonNull(Items.WHEAT_SEEDS), seedCount));
            }
        } else if (crop == Blocks.CARROTS) {
            // 降低后的胡萝卜掉落：1-2个胡萝卜（原为2-4）
            int carrotCount = (int) ((1 + random.nextInt(2)) * multiplier); // 1-2个胡萝卜 * 加成
            items.add(new ItemStack(Objects.requireNonNull(Items.CARROT), Math.max(1, carrotCount)));
        } else if (crop == Blocks.POTATOES) {
            // 降低后的马铃薯掉落：1-2个马铃薯（原为2-4），有1%几率掉落毒马铃薯
            int potatoCount = (int) ((1 + random.nextInt(2)) * multiplier); // 1-2个马铃薯 * 加成
            items.add(new ItemStack(Objects.requireNonNull(Items.POTATO), Math.max(1, potatoCount)));

            // 毒马铃薯几率也随等级提升（基础1%）
            float poisonChance = 0.01f * multiplier;
            if (random.nextFloat() < poisonChance) {
                items.add(new ItemStack(Objects.requireNonNull(Items.POISONOUS_POTATO), 1));
            }
        } else if (crop == Blocks.BEETROOTS) {
            // 降低后的甜菜根掉落：1个甜菜根，0-1个种子（原为0-3）
            int beetrootCount = (int) (1 * multiplier); // 1个甜菜根 * 加成
            int seedCount = (int) ((random.nextInt(2)) * multiplier); // 0-1个种子 * 加成

            items.add(new ItemStack(Objects.requireNonNull(Items.BEETROOT), Math.max(1, beetrootCount)));
            if (seedCount > 0) {
                items.add(new ItemStack(Objects.requireNonNull(Items.BEETROOT_SEEDS), seedCount));
            }
        } else if (crop == Blocks.MELON) {
            // 修复：西瓜方块掉落3-7个西瓜片 * 加成
            int melonSliceCount = (int) ((3 + random.nextInt(5)) * multiplier);
            items.add(new ItemStack(Objects.requireNonNull(Items.MELON_SLICE), Math.max(3, melonSliceCount)));
        } else if (crop == Blocks.PUMPKIN) {
            // 修复：南瓜方块掉落1个南瓜 * 加成
            int pumpkinCount = (int) (1 * multiplier);
            items.add(new ItemStack(Objects.requireNonNull(Items.PUMPKIN), Math.max(1, pumpkinCount)));
        } else {
            // 默认掉落
            int defaultCount = (int) (1 * multiplier);
            items.add(new ItemStack(Objects.requireNonNull(Items.WHEAT), Math.max(1, defaultCount)));
        }

        return items;
    }

    /**
     * 获取作物的种子
     */
    private static ItemStack getSeedsForCrop(Block crop) {
        if (crop == Blocks.WHEAT) {
            return new ItemStack(Objects.requireNonNull(Items.WHEAT_SEEDS));
        } else if (crop == Blocks.CARROTS) {
            return new ItemStack(Objects.requireNonNull(Items.CARROT));
        } else if (crop == Blocks.POTATOES) {
            return new ItemStack(Objects.requireNonNull(Items.POTATO));
        } else if (crop == Blocks.BEETROOTS) {
            return new ItemStack(Objects.requireNonNull(Items.BEETROOT_SEEDS));
        } else if (crop == Blocks.MELON_STEM) {
            return new ItemStack(Objects.requireNonNull(Items.MELON_SEEDS));
        } else if (crop == Blocks.PUMPKIN_STEM) {
            return new ItemStack(Objects.requireNonNull(Items.PUMPKIN_SEEDS));
        }
        return new ItemStack(Objects.requireNonNull(Items.WHEAT_SEEDS));
    }

    /**
     * 检查容器中是否有指定物品（支持Container接口和IItemHandler Capability）
     */
    private static boolean hasItemInChest(ServerLevel level, BlockPos chestPos, net.minecraft.world.item.Item item) {
        if (chestPos == null || item == null) return false;

        int count = ContainerUtils.countItem(level, chestPos, new ItemStack(item));
        return count > 0;
    }

    /**
     * 从容器中消耗种子（支持Container接口和IItemHandler Capability）
     */
    private static boolean consumeSeedFromChest(ServerLevel level, BlockPos chestPos, net.minecraft.world.item.Item seedItem) {
        if (chestPos == null || seedItem == null) return false;

        ItemStack toConsume = new ItemStack(seedItem, 1);
        return ContainerUtils.consumeItem(level, chestPos, toConsume);
    }
    /**
     * 发送聊天消息到指定城市群组（通过通知接口）
     */
    private static void sendChatMessage(MinecraftServer server, java.util.UUID cityId, Component message) {
        if (server == null || message == null) return;

        if (cityId != null) {
            CityMessageUtils.sendToCityGroup(server, cityId, message,
                    com.xiaoliang.simukraft.notification.MessageCategory.FARMING);
        }
    }

    /**
     * 尝试种植作物
     */
    private static boolean tryPlantCrop(ServerLevel level, BlockPos pos, Block cropBlock) {
        if (level == null || pos == null || cropBlock == null) return false;

        // 检查当前位置是否可以种植
        BlockState currentState = level.getBlockState(pos);
        if (!currentState.isAir()) return false;

        // 种植作物
        level.setBlock(
                Objects.requireNonNull(pos),
                Objects.requireNonNull(cropBlock.defaultBlockState()),
                3
        );
        return true;
    }

    @SuppressWarnings("unused")
    private static boolean replantConfiguredCrop(ServerLevel level, BlockPos farmlandBoxPos, BlockPos pos, BlockPos chestPos) {
        String configuredCrop = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedCrop(farmlandBoxPos);
        CropDefinition definition = CropRegistry.resolve(configuredCrop).orElse(null);
        if (definition == null || definition.layoutType() == CropLayoutType.CHECKERBOARD) {
            return false;
        }

        if (!tryPlantCrop(level, pos, definition.cropBlock())) {
            return false;
        }

        if (ContainerUtils.consumeItem(level, chestPos, new ItemStack(definition.seedItem()))) {
            return true;
        }

        level.setBlock(
                Objects.requireNonNull(pos),
                Objects.requireNonNull(Blocks.AIR.defaultBlockState()),
                3
        );
        return false;
    }

    /**
     * 检查是否是农田盒方块
     */
    private static boolean isFarmlandBoxBlock(Block block) {
        // 检查是否是农田盒方块
        return block instanceof NSUKFarmlandBoxBlock;
    }
}
