package com.xiaoliang.simukraft.world;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.employment.bridge.EmploymentLegacyBridge;
import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import com.xiaoliang.simukraft.utils.FileUtils;
import com.xiaoliang.simukraft.utils.NPCTaskScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FarmlandHiredData {
    private static final Gson gson = new Gson();
    private static final String FARMLAND_CROP_DATA_FILE = "farmland_selected_crops.json";
    private static final String FARMLAND_AREA_DATA_FILE = "farmland_selected_areas.json";
    private static final String FARMLAND_PLOT_DATA_FILE = "farmland_selected_plots.json";
    private static final String FARMLAND_CHEST_BINDING_FILE = "farmland_bound_chests.json";
    
    // 存储农田盒的雇佣农民数据
    private static final Map<BlockPos, UUID> hiredFarmers = new ConcurrentHashMap<>();
    // 存储农田盒的选中作物数据
    private static final Map<BlockPos, String> selectedCrops = new ConcurrentHashMap<>();
    // 存储农田盒的选中区域大小数据
    private static final Map<BlockPos, Integer> selectedAreas = new ConcurrentHashMap<>();
    // 存储农田盒的真实矩形地块数据
    private static final Map<BlockPos, FarmlandPlot> selectedPlots = new ConcurrentHashMap<>();
    // 存储农田盒绑定的箱子坐标
    private static final Map<BlockPos, BlockPos> boundChests = new ConcurrentHashMap<>();
    
    // 保存雇佣农民数据
    public static void saveHiredFarmers(MinecraftServer server) {
        EmploymentLegacyBridge.saveAssignments(server, WorkBlockType.FARMLAND_BOX, JobType.FARMER, getHiredFarmers());
    }

    // 加载雇佣农民数据
    public static void loadHiredFarmers(MinecraftServer server) {
        hiredFarmers.clear();
        hiredFarmers.putAll(EmploymentLegacyBridge.loadAssignments(server, WorkBlockType.FARMLAND_BOX, JobType.FARMER));
    }

    private static Path getWorldPath(MinecraftServer server) {
        Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
        return worldPath.toAbsolutePath().normalize();
    }
    
    // 保存选中作物数据
    public static void saveSelectedCrops(MinecraftServer server) {
        JsonObject data = new JsonObject();
        
        for (Map.Entry<BlockPos, String> entry : selectedCrops.entrySet()) {
            String posKey = entry.getKey().toString();
            data.addProperty(posKey, entry.getValue());
        }

        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(FARMLAND_CROP_DATA_FILE);
            
            if (!Files.exists(simukraftDir)) {
                Files.createDirectories(simukraftDir);
            }
            
            try (java.io.Writer writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to save farmland selected crops: {}", e.getMessage());
        }
    }
    
    // 加载选中作物数据
    public static void loadSelectedCrops(MinecraftServer server) {
        selectedCrops.clear();
        
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(FARMLAND_CROP_DATA_FILE);
            
            if (!Files.exists(dataFile)) {
                return;
            }

            JsonObject data;
            try (java.io.Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
                data = gson.fromJson(reader, JsonObject.class);
            }

            if (data != null) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
                    try {
                        BlockPos pos = parseBlockPos(entry.getKey());
                        String crop = entry.getValue().getAsString();
                        selectedCrops.put(pos, crop);
                    } catch (Exception e) {
                        Simukraft.LOGGER.warn("Failed to parse farmland crop data entry: {}", entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to load farmland selected crops: {}", e.getMessage());
        }
    }
    
    // 保存选中区域大小数据
    public static void saveSelectedAreas(MinecraftServer server) {
        JsonObject data = new JsonObject();
        
        for (Map.Entry<BlockPos, Integer> entry : selectedAreas.entrySet()) {
            String posKey = entry.getKey().toString();
            data.addProperty(posKey, entry.getValue());
        }

        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(FARMLAND_AREA_DATA_FILE);
            
            if (!Files.exists(simukraftDir)) {
                Files.createDirectories(simukraftDir);
            }
            
            try (java.io.Writer writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to save farmland selected areas: {}", e.getMessage());
        }
    }

    // 保存绑定箱子数据
    public static void saveSelectedPlots(MinecraftServer server) {
        JsonObject data = new JsonObject();

        for (Map.Entry<BlockPos, FarmlandPlot> entry : selectedPlots.entrySet()) {
            JsonObject plotData = new JsonObject();
            FarmlandPlot plot = entry.getValue();
            plotData.addProperty("min", plot.minPos().toString());
            plotData.addProperty("max", plot.maxPos().toString());
            data.add(entry.getKey().toString(), plotData);
        }

        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(FARMLAND_PLOT_DATA_FILE);

            if (!Files.exists(simukraftDir)) {
                Files.createDirectories(simukraftDir);
            }

            try (java.io.Writer writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to save farmland selected plots: {}", e.getMessage());
        }
    }

    public static void loadSelectedPlots(MinecraftServer server) {
        selectedPlots.clear();

        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(FARMLAND_PLOT_DATA_FILE);

            if (!Files.exists(dataFile)) {
                return;
            }

            JsonObject data;
            try (java.io.Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
                data = gson.fromJson(reader, JsonObject.class);
            }

            if (data != null) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
                    try {
                        BlockPos farmlandPos = parseBlockPos(entry.getKey());
                        JsonObject plotData = entry.getValue().getAsJsonObject();
                        BlockPos minPos = parseBlockPos(plotData.get("min").getAsString());
                        BlockPos maxPos = parseBlockPos(plotData.get("max").getAsString());
                        if (farmlandPos != null && minPos != null && maxPos != null) {
                            selectedPlots.put(farmlandPos, new FarmlandPlot(minPos, maxPos));
                        }
                    } catch (Exception e) {
                        Simukraft.LOGGER.warn("Failed to parse farmland plot data entry: {}", entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to load farmland selected plots: {}", e.getMessage());
        }
    }

    // 保存绑定箱子数据
    public static void saveBoundChests(MinecraftServer server) {
        JsonObject data = new JsonObject();
        
        for (Map.Entry<BlockPos, BlockPos> entry : boundChests.entrySet()) {
            String posKey = entry.getKey().toString();
            data.addProperty(posKey, entry.getValue().toString());
        }

        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(FARMLAND_CHEST_BINDING_FILE);
            
            if (!Files.exists(simukraftDir)) {
                Files.createDirectories(simukraftDir);
            }
            
            try (java.io.Writer writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to save farmland bound chests: {}", e.getMessage());
        }
    }
    
    // 加载绑定箱子数据
    public static void loadBoundChests(MinecraftServer server) {
        boundChests.clear();
        
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(FARMLAND_CHEST_BINDING_FILE);
            
            if (!Files.exists(dataFile)) {
                return;
            }

            JsonObject data;
            try (java.io.Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
                data = gson.fromJson(reader, JsonObject.class);
            }

            if (data != null) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
                    try {
                        BlockPos farmlandPos = parseBlockPos(entry.getKey());
                        BlockPos chestPos = parseBlockPos(entry.getValue().getAsString());
                        boundChests.put(farmlandPos, chestPos);
                    } catch (Exception e) {
                        Simukraft.LOGGER.warn("Failed to parse farmland chest binding entry: {}", entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to load farmland bound chests: {}", e.getMessage());
        }
    }
    
    // 加载选中区域大小数据
    public static void loadSelectedAreas(MinecraftServer server) {
        selectedAreas.clear();
        
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(FARMLAND_AREA_DATA_FILE);
            
            if (!Files.exists(dataFile)) {
                return;
            }

            JsonObject data;
            try (java.io.Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
                data = gson.fromJson(reader, JsonObject.class);
            }

            if (data != null) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
                    try {
                        BlockPos pos = parseBlockPos(entry.getKey());
                        int areaSize = entry.getValue().getAsInt();
                        selectedAreas.put(pos, areaSize);
                    } catch (Exception e) {
                        Simukraft.LOGGER.warn("Failed to parse farmland area data entry: {}", entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to load farmland selected areas: {}", e.getMessage());
        }
    }
    
    // 获取雇佣农民数据
    public static Map<BlockPos, UUID> getHiredFarmers() {
        return new HashMap<>(hiredFarmers);
    }
    
    // 设置雇佣农民数据
    public static void setHiredFarmer(BlockPos farmlandBoxPos, UUID npcUuid) {
        hiredFarmers.put(farmlandBoxPos, npcUuid);
    }

    public static boolean hasHiredFarmer(BlockPos farmlandBoxPos) {
        return hiredFarmers.containsKey(farmlandBoxPos);
    }

    public static UUID getHiredFarmer(BlockPos farmlandBoxPos) {
        return hiredFarmers.get(farmlandBoxPos);
    }
    
    // 清除雇佣农民数据
    public static void clearHiredFarmer(BlockPos farmlandBoxPos) {
        hiredFarmers.remove(farmlandBoxPos);
    }
    
    // 获取选中作物数据
    public static Map<BlockPos, String> getSelectedCrops() {
        return new HashMap<>(selectedCrops);
    }
    
    // 设置选中作物数据
    public static void setSelectedCrop(BlockPos farmlandBoxPos, String crop) {
        selectedCrops.put(farmlandBoxPos, crop);
    }

    public static String getSelectedCrop(BlockPos farmlandBoxPos) {
        return selectedCrops.getOrDefault(farmlandBoxPos, "wheat");
    }
    
    // 清除选中作物数据
    public static void clearSelectedCrop(BlockPos farmlandBoxPos) {
        selectedCrops.remove(farmlandBoxPos);
    }
    
    // 获取选中区域大小数据
    public static Map<BlockPos, Integer> getSelectedAreas() {
        return new HashMap<>(selectedAreas);
    }
    
    // 设置选中区域大小数据
    public static void setSelectedArea(BlockPos farmlandBoxPos, int areaSize) {
        selectedAreas.put(farmlandBoxPos, areaSize);
    }
    
    // 清除选中区域大小数据
    public static void clearSelectedArea(BlockPos farmlandBoxPos) {
        selectedAreas.remove(farmlandBoxPos);
    }

    public static Map<BlockPos, FarmlandPlot> getSelectedPlots() {
        return new HashMap<>(selectedPlots);
    }

    public static boolean hasSelectedPlot(BlockPos farmlandBoxPos) {
        return selectedPlots.containsKey(farmlandBoxPos);
    }

    public static FarmlandPlot getSelectedPlot(BlockPos farmlandBoxPos) {
        return selectedPlots.get(farmlandBoxPos);
    }

    public static void setSelectedPlot(BlockPos farmlandBoxPos, FarmlandPlot plot) {
        if (plot != null) {
            selectedPlots.put(farmlandBoxPos, plot);
        }
    }

    public static BlockPos findOverlappingPlotOwner(BlockPos farmlandBoxPos, FarmlandPlot plot) {
        if (farmlandBoxPos == null || plot == null) {
            return null;
        }
        for (Map.Entry<BlockPos, FarmlandPlot> entry : selectedPlots.entrySet()) {
            BlockPos otherBoxPos = entry.getKey();
            if (farmlandBoxPos.equals(otherBoxPos)) {
                continue;
            }
            FarmlandPlot otherPlot = entry.getValue();
            if (otherPlot != null && plot.intersects(otherPlot)) {
                return otherBoxPos;
            }
        }
        return null;
    }

    public static boolean hasOverlappingPlot(BlockPos farmlandBoxPos, FarmlandPlot plot) {
        return findOverlappingPlotOwner(farmlandBoxPos, plot) != null;
    }

    public static void clearSelectedPlot(BlockPos farmlandBoxPos) {
        selectedPlots.remove(farmlandBoxPos);
    }

    // 获取绑定箱子数据
    public static BlockPos getBoundChest(BlockPos farmlandBoxPos) {
        return boundChests.get(farmlandBoxPos);
    }

    // 设置绑定箱子数据
    public static void setBoundChest(BlockPos farmlandBoxPos, BlockPos chestPos) {
        boundChests.put(farmlandBoxPos, chestPos);
    }

    // 清除绑定箱子数据
    public static void clearBoundChest(BlockPos farmlandBoxPos) {
        boundChests.remove(farmlandBoxPos);
    }

    // 获取所有绑定数据
    public static Map<BlockPos, BlockPos> getBoundChests() {
        return new HashMap<>(boundChests);
    }

    // 获取指定农田盒的选中区域大小
    public static int getSelectedAreaSize(BlockPos farmlandBoxPos) {
        return selectedAreas.getOrDefault(farmlandBoxPos, 10); // 默认10x10区域
    }
    
    // 保存所有农田盒数据
    public static void saveAllFarmlandData(MinecraftServer server) {
        saveHiredFarmers(server);
        saveSelectedCrops(server);
        saveSelectedAreas(server);
        saveSelectedPlots(server);
        saveBoundChests(server);
    }
    
    // 加载所有农田盒数据
    public static void loadAllFarmlandData(MinecraftServer server) {
        loadHiredFarmers(server);
        loadSelectedCrops(server);
        loadSelectedAreas(server);
        loadSelectedPlots(server);
        loadBoundChests(server);
    }
    
    private static BlockPos parseBlockPos(String posString) {
        try {
            // 处理转义字符：将 \u003d 转换为 =
            String unescapedString = posString.replace("\\u003d", "=");
            
            // 格式: BlockPos{x=123, y=64, z=456}
            String[] parts = unescapedString.substring(8, unescapedString.length() - 1).split(",");
            int x = Integer.parseInt(parts[0].split("=")[1].trim());
            int y = Integer.parseInt(parts[1].split("=")[1].trim());
            int z = Integer.parseInt(parts[2].split("=")[1].trim());
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            Simukraft.LOGGER.warn("Failed to parse BlockPos: {}", posString);
            return null;
        }
    }
    
    public static CustomEntity findNPCByUuid(MinecraftServer server, UUID uuid) {
        for (CustomEntity npc : NPCTaskScheduler.getAllNPCs(server)) {
            if (uuid.equals(npc.getUUID())) {
                return npc;
            }
        }
        return null;
    }
}
