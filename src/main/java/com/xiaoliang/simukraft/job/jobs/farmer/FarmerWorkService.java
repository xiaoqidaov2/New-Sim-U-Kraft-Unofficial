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
        
        if (!"farmer".equals(npc.getJob())) {
            npc.setJob("farmer");
        }
        
        if (npc.getWorkStatus() == WorkStatus.IDLE) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
        }
        
        BlockPos farmlandBoxPos = getFarmlandBoxForNpc(level.getServer(), npcUuid);
        if (farmlandBoxPos == null) return;
        
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
    
    private boolean isMatureCrop(net.minecraft.world.level.block.Block block) {
        return block == net.minecraft.world.level.block.Blocks.WHEAT
                || block == net.minecraft.world.level.block.Blocks.CARROTS
                || block == net.minecraft.world.level.block.Blocks.POTATOES
                || block == net.minecraft.world.level.block.Blocks.BEETROOTS
                || block == net.minecraft.world.level.block.Blocks.PUMPKIN_STEM
                || block == net.minecraft.world.level.block.Blocks.MELON_STEM;
    }
    
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
                    
                    if (isMatureCrop(block)) {
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