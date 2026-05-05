package com.xiaoliang.simukraft.job.jobs.farmer;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.core.services.AbstractWorkService;
import com.xiaoliang.simukraft.utils.NPCDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public class FarmerWorkService extends AbstractWorkService {

    public static final FarmerWorkService INSTANCE = new FarmerWorkService();
    
    private static final int MOISTURE_INTERVAL_MS = 1000;
    private static final int GROWTH_INTERVAL_MS = 15000;
    private static final int HARVEST_INTERVAL_MS = 15000;
    
    private final ConcurrentMap<BlockPos, Long> farmlandLastMoistureTime = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockPos, Long> farmlandLastGrowthTime = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockPos, Long> farmlandLastHarvestTime = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> farmerDailyXp = new ConcurrentHashMap<>();
    
    private final ConcurrentMap<BlockPos, Long> farmlandLastMoistureTime0 = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockPos, Long> farmlandLastGrowthTime0 = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockPos, Long> farmlandLastHarvestTime0 = new ConcurrentHashMap<>();
    
    @Override
    protected void onServerStart0(MinecraftServer server, ServerLevel level) {
        farmlandLastMoistureTime.clear();
        farmlandLastGrowthTime.clear();
        farmlandLastHarvestTime.clear();
        farmerDailyXp.clear();
        restoreAllFarmersWorkState(server);
    }

    public void restoreAllFarmersWorkState(MinecraftServer server) {
        if (server == null) return;

        var hiredEmployeeMap = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        for (ServerLevel level : server.getAllLevels()) {
            for (Map.Entry<BlockPos, UUID> entry : hiredEmployeeMap.entrySet()) {
                BlockPos farmlandBoxPos = entry.getKey();
                UUID npcUuid = entry.getValue();

                var farmlandBoxState = level.getBlockState(farmlandBoxPos);
                if (!isFarmlandBoxBlock(farmlandBoxState.getBlock())) {
                    continue;
                }

                CustomEntity npc = com.xiaoliang.simukraft.world.FarmlandHiredData.findNPCByUuid(server, npcUuid);
                if (npc != null && npc.isAlive()) {
                    restoreWorkState(npc, npcUuid, level);
                }
            }
        }
    }

    private boolean isFarmlandBoxBlock(Block block) {
        return block instanceof com.xiaoliang.simukraft.block.NSUKFarmlandBoxBlock;
    }

    public void clearTimers(BlockPos farmlandBoxPos) {
        if (farmlandBoxPos == null) return;
        farmlandLastMoistureTime.remove(farmlandBoxPos);
        farmlandLastGrowthTime.remove(farmlandBoxPos);
        farmlandLastHarvestTime.remove(farmlandBoxPos);
        farmlandLastMoistureTime0.remove(farmlandBoxPos);
        farmlandLastGrowthTime0.remove(farmlandBoxPos);
        farmlandLastHarvestTime0.remove(farmlandBoxPos);
    }

    public void handleContinuousWork(ServerLevel level) {
        if (level == null) return;

        var hiredEmployeeMap = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        for (Map.Entry<BlockPos, UUID> entry : hiredEmployeeMap.entrySet()) {
            BlockPos farmlandBoxPos = entry.getKey();
            UUID npcUuid = entry.getValue();

            var assignment = new com.xiaoliang.simukraft.employment.domain.EmploymentAssignment(
                    npcUuid,
                    level.dimension().location().toString(),
                    farmlandBoxPos,
                    com.xiaoliang.simukraft.employment.domain.WorkBlockType.FARMLAND_BOX,
                    com.xiaoliang.simukraft.employment.domain.JobType.FARMER,
                    com.xiaoliang.simukraft.employment.domain.EmploymentStatus.ASSIGNED,
                    0L,
                    System.currentTimeMillis()
            );
            CustomEntity npc = com.xiaoliang.simukraft.world.FarmlandHiredData.findNPCByUuid(level.getServer(), npcUuid);
            handleContinuousWork(new JobContext(level.getServer(), level, npc, assignment, null, level.getDayTime() % 24000L));
        }
    }
    
    @Override
    protected void onServerStop0(ServerLevel level) {
    }
    
    @Override
    protected void handleDailyXp0(ServerLevel level) {
        if (level == null) return;
        MinecraftServer server = level.getServer();
        long currentDay = level.getDayTime() / 24000;
        
        var hiredEmployeeMap = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        
        for (Map.Entry<BlockPos, UUID> entry : hiredEmployeeMap.entrySet()) {
            BlockPos farmlandBoxPos = entry.getKey();
            UUID npcUuid = entry.getValue();
            
            Long lastXpDay = farmerDailyXp.get(npcUuid);
            if (lastXpDay != null && lastXpDay == currentDay) {
                continue;
            }
            
            CustomEntity npc = com.xiaoliang.simukraft.world.FarmlandHiredData.findNPCByUuid(server, npcUuid);
            if (npc != null && npc.isAlive() && "farmer".equals(npc.getJob())) {
                boolean leveledUp = NPCDataManager.addXp(server, npcUuid, 5);
                farmerDailyXp.put(npcUuid, currentDay);
                
                Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.translatable("message.simukraft.npc.unknown");
                Component message;
                if (leveledUp) {
                    int newLevel = NPCDataManager.getNPCLevel(server, npcUuid);
                    message = Component.translatable("message.simukraft.npc.level_up.work_completed", npcName, newLevel);
                } else {
                    message = Component.translatable("message.simukraft.npc.work_completed", npcName);
                }
                
                sendMessageToMayor(server, level, farmlandBoxPos, message);
            }
        }
        
        farmerDailyXp.entrySet().removeIf(e -> e.getValue() < currentDay - 3);
    }
    
    private void sendMessageToMayor(MinecraftServer server, ServerLevel level, BlockPos farmlandBoxPos, Component message) {
        UUID cityId = getFarmlandBoxCityId(server, farmlandBoxPos);
        if (cityId == null) return;
        com.xiaoliang.simukraft.utils.CityMessageUtils.sendToMayorViaService(server, cityId,
                Component.translatable("notify.title.farming"),
                message,
                com.xiaoliang.simukraft.notification.MessageCategory.FARMING);
    }
    
    private UUID getFarmlandBoxCityId(MinecraftServer server, BlockPos farmlandBoxPos) {
        try {
            var worldDir = server.getWorldPath(Objects.requireNonNull(net.minecraft.world.level.storage.LevelResource.ROOT));
            var simukraftDir = worldDir.resolve(com.xiaoliang.simukraft.utils.FileUtils.MODE_DIR);
            var fruitDir = simukraftDir.resolve(com.xiaoliang.simukraft.utils.FileUtils.FRUIT_DIR);
            
            String fileName = farmlandBoxPos.getX() + "_" + farmlandBoxPos.getY() + "_" + farmlandBoxPos.getZ() + ".sk";
            var skFile = fruitDir.resolve(fileName);
            
            if (!java.nio.file.Files.exists(skFile)) {
                return null;
            }
            
            try (var reader = java.nio.file.Files.newBufferedReader(skFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("city_id: ")) {
                        String cityIdStr = line.substring("city_id: ".length()).trim();
                        return UUID.fromString(cityIdStr);
                    }
                }
            }
        } catch (Exception e) {
            if (com.xiaoliang.simukraft.config.ServerConfig.isDebugLogEnabled()) {
                com.xiaoliang.simukraft.Simukraft.LOGGER.debug("[FarmerWorkService] Failed to resolve cityId", e);
            }
        }
        return null;
    }
    
    @Override
    public void restoreWorkState(CustomEntity npc, UUID npcUuid, ServerLevel level) {
        if (npc == null || npcUuid == null || level == null) return;
        
        if (npc.getWorkSubState() == com.xiaoliang.simukraft.entity.WorkSubState.LUNCH_BREAK) {
            return;
        }

        BlockPos farmlandBoxPos = getFarmlandBoxForNpc(level.getServer(), npcUuid);
        if (farmlandBoxPos == null) return;

        if (!"farmer".equals(npc.getJob())) {
            npc.setJob("farmer");
        }
        if (npc.getWorkStatus() == WorkStatus.IDLE) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
        }
        
        BlockPos npcPos = npc.blockPosition();
        double distance = npcPos.distSqr(farmlandBoxPos);
        
        if (distance > 400) {
            BlockPos targetPos = findSafePositionNearFarmland(farmlandBoxPos, level);
            if (targetPos != null && !npc.moveToWithNewPathfinder(targetPos, 1.0D)) {
                npc.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
                npc.stopNewPathfinder();
            }
        }
    }
    
    private BlockPos getFarmlandBoxForNpc(MinecraftServer server, UUID npcUuid) {
        var hiredEmployeeMap = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        for (Map.Entry<BlockPos, UUID> entry : hiredEmployeeMap.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private BlockPos findSafePositionNearFarmland(BlockPos farmlandBoxPos, ServerLevel level) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                BlockPos checkPos = farmlandBoxPos.offset(x, 0, z);
                BlockPos abovePos = checkPos.above();
                BlockPos belowPos = checkPos.below();
                
                if (level.getBlockState(belowPos).isFaceSturdy(level, belowPos, net.minecraft.core.Direction.UP)
                        && level.isEmptyBlock(checkPos)
                        && level.isEmptyBlock(abovePos)) {
                    return checkPos;
                }
            }
        }
        return farmlandBoxPos.above();
    }
    
    @Override
    public void handleContinuousWork(JobContext context) {
        if (context == null || context.level() == null) return;
        
        ServerLevel level = context.level();
        MinecraftServer server = level.getServer();
        UUID npcUuid = context.assignment().npcUuid();
        BlockPos farmlandBoxPos = context.assignment().workplacePos();
        
        if (!dataInitialized) {
            dataInitialized = true;
        }
        
        long currentTime = System.currentTimeMillis();
        
        CustomEntity npc = com.xiaoliang.simukraft.world.FarmlandHiredData.findNPCByUuid(server, npcUuid);
        if (npc == null || !npc.isAlive() || !"farmer".equals(npc.getJob())) {
            return;
        }
        
        restoreWorkState(npc, npcUuid, level);
        
        farmlandLastMoistureTime.putIfAbsent(farmlandBoxPos, currentTime);
        farmlandLastGrowthTime.putIfAbsent(farmlandBoxPos, currentTime);
        farmlandLastHarvestTime.putIfAbsent(farmlandBoxPos, currentTime);
        
        boolean needMoisture = currentTime - farmlandLastMoistureTime.get(farmlandBoxPos) >= MOISTURE_INTERVAL_MS;
        boolean needGrowth = currentTime - farmlandLastGrowthTime.get(farmlandBoxPos) >= GROWTH_INTERVAL_MS;
        boolean needHarvest = currentTime - farmlandLastHarvestTime.get(farmlandBoxPos) >= HARVEST_INTERVAL_MS;
        
        if (needMoisture) farmlandLastMoistureTime.put(farmlandBoxPos, currentTime);
        if (needGrowth) farmlandLastGrowthTime.put(farmlandBoxPos, currentTime);
        if (needHarvest) farmlandLastHarvestTime.put(farmlandBoxPos, currentTime);
        
        if (needMoisture) {
            keepFarmlandMoist(npc, farmlandBoxPos, level);
        }
        
        if (needGrowth) {
            boostCropGrowth(npc, farmlandBoxPos, level);
        }
        
        if (needHarvest) {
            harvestAndReplant(npc, farmlandBoxPos, level);
        }
    }
    
    private void keepFarmlandMoist(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        var plot = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedPlot(farmlandBoxPos);
        if (plot == null) return;
        
        BlockPos min = plot.minPos();
        BlockPos max = plot.maxPos();
        
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                BlockPos pos = new BlockPos(x, min.getY(), z);
                var state = level.getBlockState(pos);
                if (state.getBlock() instanceof net.minecraft.world.level.block.FarmBlock) {
                    int moisture = state.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE);
                    if (moisture != 7) {
                        level.setBlockAndUpdate(pos, state.setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7));
                    }
                }
            }
        }
    }
    
    private void boostCropGrowth(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        var plot = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedPlot(farmlandBoxPos);
        if (plot == null) return;
        
        BlockPos min = plot.minPos();
        BlockPos max = plot.maxPos();
        
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = level.getBlockState(pos);
                    var block = state.getBlock();
                    
                    if (isMatureCrop(block)) {
                        level.scheduleTick(pos, block, 1);
                    }
                }
            }
        }
    }
    
    /**
     * 检查是否为普通作物（破坏式采集）
     */
    private boolean isMatureCrop(net.minecraft.world.level.block.Block block) {
        return block == net.minecraft.world.level.block.Blocks.WHEAT
                || block == net.minecraft.world.level.block.Blocks.CARROTS
                || block == net.minecraft.world.level.block.Blocks.POTATOES
                || block == net.minecraft.world.level.block.Blocks.BEETROOTS
                || block == net.minecraft.world.level.block.Blocks.PUMPKIN_STEM
                || block == net.minecraft.world.level.block.Blocks.MELON_STEM;
    }

    /**
     * 检查是否为右键采摘作物（如浆果、番茄等）
     * menglannnn: 识别需要右键采摘的作物类型
     */
    private boolean isRightClickHarvestCrop(net.minecraft.world.level.block.Block block) {
        String blockName = block.getDescriptionId();
        // 原版的浆果丛
        if (block == net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) {
            return true;
        }
        // 农夫乐事等模组的右键采摘作物通常包含这些关键词
        if (blockName.contains("berry") || blockName.contains("tomato") || blockName.contains("pepper")
                || blockName.contains("eggplant") || blockName.contains("cucumber") || blockName.contains("corn")) {
            return true;
        }
        // 检查是否为作物方块且有年龄属性（AGE）但不是普通作物
        if (block instanceof net.minecraft.world.level.block.BushBlock && !(block instanceof net.minecraft.world.level.block.CropBlock)) {
            return true;
        }
        return false;
    }

    /**
     * 检查右键采摘作物是否已成熟
     * menglannnn: 通过检查方块的AGE属性判断是否可收获
     */
    private boolean isRightClickCropMature(net.minecraft.world.level.block.state.BlockState state) {
        // 浆果丛的最大年龄是3
        if (state.getBlock() == net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) {
            return state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) >= 2;
        }
        // 其他作物检查AGE属性是否为最大值
        for (var property : state.getProperties()) {
            if (property.getName().equals("age")) {
                Comparable<?> value = state.getValue(property);
                if (value instanceof Integer age) {
                    // 获取最大可能值
                    int maxAge = 0;
                    for (var possibleValue : property.getPossibleValues()) {
                        if (possibleValue instanceof Integer intVal && intVal > maxAge) {
                            maxAge = intVal;
                        }
                    }
                    return age >= maxAge;
                }
            }
        }
        return false;
    }

    /**
     * 执行右键采摘收获
     * menglannnn: 模拟右键点击收获作物，不破坏方块
     */
    private void performRightClickHarvest(CustomEntity npc, BlockPos pos, ServerLevel level) {
        var state = level.getBlockState(pos);
        var block = state.getBlock();

        if (!isRightClickHarvestCrop(block) || !isRightClickCropMature(state)) {
            return;
        }

        // 直接生成掉落物并重置作物年龄（模拟右键收获效果）
        spawnHarvestDrops(npc, pos, level, block);
        resetCropAge(level, pos, state);
    }

    /**
     * 生成收获掉落物
     * menglannnn: 根据作物类型生成对应的收获物并存入箱子
     */
    private void spawnHarvestDrops(CustomEntity npc, BlockPos pos, ServerLevel level, net.minecraft.world.level.block.Block block) {
        // 获取绑定的箱子位置
        BlockPos farmlandBoxPos = getFarmlandBoxForNpc(level.getServer(), npc.getUUID());
        if (farmlandBoxPos == null) return;

        BlockPos chestPos = com.xiaoliang.simukraft.world.FarmlandHiredData.getBoundChest(farmlandBoxPos);

        // 根据作物类型确定掉落物
        java.util.List<net.minecraft.world.item.ItemStack> drops = new java.util.ArrayList<>();

        if (block == net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) {
            // 浆果丛掉落甜浆果（2-3个）
            int count = 2 + level.random.nextInt(2);
            drops.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SWEET_BERRIES, count));
        } else {
            // 其他模组作物，尝试从战利品表获取
            var dropsList = net.minecraft.world.level.block.Block.getDrops(
                    level.getBlockState(pos),
                    level,
                    pos,
                    null,
                    npc,
                    net.minecraft.world.item.ItemStack.EMPTY
            );
            drops.addAll(dropsList);
        }

        // 将掉落物存入箱子或丢在地上
        for (net.minecraft.world.item.ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                if (chestPos != null) {
                    // 尝试存入箱子
                    if (!insertIntoChest(level, chestPos, drop)) {
                        // 箱子满了，丢在地上
                        spawnItemAtPos(level, pos, drop);
                    }
                } else {
                    // 没有绑定箱子，丢在地上
                    spawnItemAtPos(level, pos, drop);
                }
            }
        }
    }

    /**
     * 重置作物年龄（用于右键采摘后）
     * menglannnn: 将作物年龄重置为0，让它继续生长
     */
    private void resetCropAge(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        var block = state.getBlock();

        // 浆果丛重置为年龄0
        if (block == net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) {
            level.setBlockAndUpdate(pos, state.setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 0));
            return;
        }

        // 其他作物尝试重置AGE属性
        for (var property : state.getProperties()) {
            if (property.getName().equals("age") && property.getPossibleValues().contains(0)) {
                var newState = state.setValue((net.minecraft.world.level.block.state.properties.IntegerProperty) property, 0);
                level.setBlockAndUpdate(pos, newState);
                return;
            }
        }
    }

    /**
     * 将物品存入箱子
     * menglannnn: 尝试将物品插入到指定位置的箱子中
     */
    /**
     * 将物品存入箱子
     * menglannnn: 尝试将物品插入到指定位置的箱子中
     */
    private boolean insertIntoChest(ServerLevel level, BlockPos chestPos, net.minecraft.world.item.ItemStack stack) {
        var blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            return false;
        }

        for (int i = 0; i < container.getContainerSize(); i++) {
            var slotStack = container.getItem(i);
            if (slotStack.isEmpty()) {
                container.setItem(i, stack.copy());
                return true;
            } else if (ItemStack.isSameItemSameTags(slotStack, stack)
                    && slotStack.getCount() + stack.getCount() <= slotStack.getMaxStackSize()) {
                slotStack.grow(stack.getCount());
                return true;
            }
        }
        return false;
    }

    /**
     * 在指定位置生成物品实体
     */
    private void spawnItemAtPos(ServerLevel level, BlockPos pos, net.minecraft.world.item.ItemStack stack) {
        var itemEntity = new net.minecraft.world.entity.item.ItemEntity(
                level,
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5,
                stack
        );
        itemEntity.setPickUpDelay(10);
        level.addFreshEntity(itemEntity);
    }

    /**
     * 主收获方法 - 支持普通破坏式采集和右键采摘
     * menglannnn: 统一处理两种采集方式
     */
    private void harvestAndReplant(CustomEntity npc, BlockPos farmlandBoxPos, ServerLevel level) {
        var plot = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedPlot(farmlandBoxPos);
        if (plot == null) return;

        String selectedCrop = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedCrop(farmlandBoxPos);
        if (selectedCrop == null) selectedCrop = "wheat";

        BlockPos min = plot.minPos();
        BlockPos max = plot.maxPos();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = level.getBlockState(pos);
                    var block = state.getBlock();

                    // 处理右键采摘作物
                    if (isRightClickHarvestCrop(block)) {
                        if (isRightClickCropMature(state)) {
                            performRightClickHarvest(npc, pos, level);
                        }
                    }
                    // 处理普通破坏式采集作物
                    else if (isMatureCrop(block)) {
                        level.destroyBlock(pos, false);

                        BlockPos soilPos = pos.below();
                        var soilState = level.getBlockState(soilPos);
                        if (soilState.getBlock() instanceof net.minecraft.world.level.block.FarmBlock) {
                            net.minecraft.world.item.ItemStack seed = getSeedStack(selectedCrop);
                            if (!seed.isEmpty()) {
                                level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                                level.setBlockAndUpdate(soilPos, net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState());
                            }
                        }
                    }
                }
            }
        }
    }
    
    private net.minecraft.world.item.ItemStack getSeedStack(String crop) {
        return switch (crop.toLowerCase()) {
            case "wheat" -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT_SEEDS);
            case "carrot" -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARROT);
            case "potato" -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTATO);
            case "beetroot" -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BEETROOT_SEEDS);
            case "pumpkin" -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PUMPKIN_SEEDS);
            case "melon" -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MELON_SEEDS);
            default -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT_SEEDS);
        };
    }
}
