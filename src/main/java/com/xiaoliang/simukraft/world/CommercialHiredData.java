package com.xiaoliang.simukraft.world;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.employment.bridge.EmploymentLegacyBridge;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import com.xiaoliang.simukraft.employment.service.LegacyJobTypeMapper;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.utils.FileUtils;
import com.xiaoliang.simukraft.utils.NPCEntityLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CommercialHiredData {
    private static final Gson gson = new Gson();
    private static final String COMMERCIAL_STOCK_DATA_FILE = "commercial_stock.json";

    // 存储每个商业建筑位置的库存数据
    private static final Map<BlockPos, Map<String, StockInfo>> stockData = new ConcurrentHashMap<>();
    
    // 缓存雇佣数据，避免每 tick 读取文件
    private static final Map<BlockPos, CommercialHireInfo> hiredEmployeesCache = new ConcurrentHashMap<>();
    private static boolean hiredEmployeesLoaded = false;
    private static long hiredEmployeesSourceLastModified = Long.MIN_VALUE;

    private static Path getWorldPath(MinecraftServer server) {
        Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
        // 转换为绝对路径并规范化，避免相对路径问题
        return worldPath.toAbsolutePath().normalize();
    }

    public static class CommercialHireInfo {
        private final BlockPos position;
        private final UUID npcUuid;
        private final String jobType;
        private final String buildingFileName;
        private final String buildingName;

        public CommercialHireInfo() {
            this.position = null;
            this.npcUuid = null;
            this.jobType = null;
            this.buildingFileName = null;
            this.buildingName = null;
        }

        public CommercialHireInfo(BlockPos position, UUID npcUuid, String jobType, String buildingFileName, String buildingName) {
            this.position = position;
            this.npcUuid = npcUuid;
            this.jobType = jobType;
            this.buildingFileName = buildingFileName;
            this.buildingName = buildingName;
        }

        public UUID getNpcUuid() {
            return npcUuid;
        }

        public String getJobType() {
            return jobType;
        }

        public String getBuildingFileName() {
            return buildingFileName;
        }

        public String getBuildingName() {
            return buildingName;
        }

        public BlockPos getPosition() {
            return position;
        }
    }

    public static class StockInfo {
        private final String itemId;
        private int currentStock;
        private int maxStock;          // 最大库存
        private long lastRestockTime;
        private int dailyBoughtAmount; // 今日已收购数量（用于maxBuyAmount限制）
        private int maxBuyAmount;      // 每天最大收购数量
        private int dailySoldAmount;   // 今日已售出数量（用于限制每日销售上限）
        private long lastSaleDay;      // 最后销售日期（用于重置每日销售计数）

        public StockInfo() {
            this.itemId = null;
            this.currentStock = 0;
            this.maxStock = 0;
            this.lastRestockTime = 0;
            this.dailyBoughtAmount = 0;
            this.maxBuyAmount = 0;
            this.dailySoldAmount = 0;
            this.lastSaleDay = 0;
        }

        public StockInfo(String itemId, int currentStock, long lastRestockTime) {
            this.itemId = itemId;
            this.currentStock = currentStock;
            this.maxStock = 0;
            this.lastRestockTime = lastRestockTime;
            this.dailyBoughtAmount = 0;
            this.maxBuyAmount = 0;
            this.dailySoldAmount = 0;
            this.lastSaleDay = 0;
        }

        public StockInfo(String itemId, int currentStock, int maxStock, long lastRestockTime, int dailyBoughtAmount, int maxBuyAmount) {
            this.itemId = itemId;
            this.currentStock = currentStock;
            this.maxStock = maxStock;
            this.lastRestockTime = lastRestockTime;
            this.dailyBoughtAmount = dailyBoughtAmount;
            this.maxBuyAmount = maxBuyAmount;
            this.dailySoldAmount = 0;
            this.lastSaleDay = 0;
        }

        public StockInfo(String itemId, int currentStock, int maxStock, long lastRestockTime, int dailyBoughtAmount, int maxBuyAmount, int dailySoldAmount, long lastSaleDay) {
            this.itemId = itemId;
            this.currentStock = currentStock;
            this.maxStock = maxStock;
            this.lastRestockTime = lastRestockTime;
            this.dailyBoughtAmount = dailyBoughtAmount;
            this.maxBuyAmount = maxBuyAmount;
            this.dailySoldAmount = dailySoldAmount;
            this.lastSaleDay = lastSaleDay;
        }

        public String getItemId() {
            return itemId;
        }

        public int getCurrentStock() {
            return currentStock;
        }

        public void setCurrentStock(int currentStock) {
            this.currentStock = currentStock;
        }

        public int getMaxStock() {
            return maxStock;
        }

        public void setMaxStock(int maxStock) {
            this.maxStock = maxStock;
        }

        public long getLastRestockTime() {
            return lastRestockTime;
        }

        public void setLastRestockTime(long lastRestockTime) {
            this.lastRestockTime = lastRestockTime;
        }

        public int getDailyBoughtAmount() {
            return dailyBoughtAmount;
        }

        public void setDailyBoughtAmount(int dailyBoughtAmount) {
            this.dailyBoughtAmount = dailyBoughtAmount;
        }

        public int getMaxBuyAmount() {
            return maxBuyAmount;
        }

        public void setMaxBuyAmount(int maxBuyAmount) {
            this.maxBuyAmount = maxBuyAmount;
        }

        public int getDailySoldAmount() {
            return dailySoldAmount;
        }

        public void setDailySoldAmount(int dailySoldAmount) {
            this.dailySoldAmount = dailySoldAmount;
        }

        public long getLastSaleDay() {
            return lastSaleDay;
        }

        public void setLastSaleDay(long lastSaleDay) {
            this.lastSaleDay = lastSaleDay;
        }

        /**
         * 检查并更新每日销售计数
         * @param currentDay 当前游戏天数
         * @param amount 要销售的数量
         * @param maxDailySale 每日最大销售数量
         * @return 是否可以销售
         */
        public boolean checkAndUpdateDailySale(long currentDay, int amount, int maxDailySale) {
            // 如果是新的一天，重置计数
            if (currentDay != lastSaleDay) {
                dailySoldAmount = 0;
                lastSaleDay = currentDay;
            }
            // 检查是否超过每日上限
            if (dailySoldAmount + amount > maxDailySale) {
                return false;
            }
            dailySoldAmount += amount;
            return true;
        }

        /**
         * 获取今日剩余可销售数量
         * @param currentDay 当前游戏天数
         * @param maxDailySale 每日最大销售数量
         * @return 剩余可销售数量
         */
        public int getRemainingDailySale(long currentDay, int maxDailySale) {
            if (currentDay != lastSaleDay) {
                return maxDailySale;
            }
            return Math.max(0, maxDailySale - dailySoldAmount);
        }

        /**
         * 检查是否可以收购（库存未满且未达到每日上限）
         * @param amount 要收购的数量（按组）
         * @return 是否可以收购
         */
        public boolean canBuy(int amount) {
            return currentStock + amount * 64 <= maxStock && dailyBoughtAmount + amount <= maxBuyAmount;
        }

        /**
         * 增加库存（玩家出售时调用）
         * @param amount 增加的数量（按组）
         */
        public void addStock(int amount) {
            this.currentStock += amount * 64;
            this.dailyBoughtAmount += amount;
        }

        /**
         * 减少库存（玩家购买时调用）
         * @param amount 减少的数量（按组）
         * @return 是否成功
         */
        public boolean removeStock(int amount) {
            // amount 已经是实际个数（零售模式下直接是个数，批发模式下已经乘以64）
            if (currentStock < amount) {
                return false;
            }
            currentStock -= amount;
            return true;
        }

        /**
         * 补货
         * @param restockAmount 补货数量（按组）
         * @return 实际补货数量（按组）
         */
        public int restock(int restockAmount) {
            int availableSpace = (maxStock - currentStock) / 64;
            int actualRestock = Math.min(restockAmount, availableSpace);
            currentStock += actualRestock * 64;
            return actualRestock;
        }

        /**
         * 重置每日收购数量
         */
        public void resetDailyBoughtAmount() {
            this.dailyBoughtAmount = 0;
        }

        /**
         * 获取剩余可收购数量（按组）
         */
        public int getRemainingBuyAmount() {
            int remainingSpace = (maxStock - currentStock) / 64;
            int remainingDaily = maxBuyAmount - dailyBoughtAmount;
            return Math.min(remainingSpace, remainingDaily);
        }
    }

    public static void saveHiredEmployees(MinecraftServer server, Map<BlockPos, CommercialHireInfo> hiredEmployees) {
        Map<BlockPos, EmploymentLegacyBridge.AssignmentInput> desiredAssignments = new HashMap<>();
        for (Map.Entry<BlockPos, CommercialHireInfo> entry : hiredEmployees.entrySet()) {
            CommercialHireInfo info = entry.getValue();
            if (info == null || info.getNpcUuid() == null) {
                continue;
            }
            String hint = inferJobHint(info.getJobType(), info.getBuildingFileName());
            desiredAssignments.put(entry.getKey(), new EmploymentLegacyBridge.AssignmentInput(
                    info.getNpcUuid(),
                    LegacyJobTypeMapper.fromLegacy(info.getJobType(), hint)
            ));
        }
        EmploymentLegacyBridge.saveAssignmentsByWorkBlock(server, WorkBlockType.COMMERCIAL_CONTROL_BOX, desiredAssignments);
        // 清除缓存，确保下次读取到最新数据
        clearHiredEmployeesCache();
    }

    public static Map<BlockPos, CommercialHireInfo> loadHiredEmployees(MinecraftServer server) {
        long sourceLastModified = getEmploymentDataLastModified(server);

        // 仅在底层统一雇佣仓储未变化时复用缓存，避免控制盒看到过期雇佣关系。
        if (shouldReuseHiredEmployeesCache(hiredEmployeesLoaded, hiredEmployeesSourceLastModified, sourceLastModified)) {
            return new HashMap<>(hiredEmployeesCache);
        }

        hiredEmployeesCache.clear();
        for (Map.Entry<BlockPos, com.xiaoliang.simukraft.employment.domain.EmploymentAssignment> entry :
                EmploymentLegacyBridge.loadLatestByWorkBlock(server, WorkBlockType.COMMERCIAL_CONTROL_BOX).entrySet()) {
            BlockPos pos = entry.getKey();
            var assignment = entry.getValue();
            // 同 IndustrialHiredData：过滤掉已 RELEASED 的记录，避免商业建筑反复对死 NPC findByUuid 造成卡顿。
            if (!assignment.isAssigned()) {
                continue;
            }

            String buildingFileName = FileUtils.normalizeBuildingFileName(FileUtils.readCommercialBuildingFileNameCached(server, pos));
            String buildingName = buildingFileName != null
                    ? CommercialBuildingManager.getBuildingDisplayName(buildingFileName)
                    : "";
            String jobType = resolveDisplayJobType(assignment.jobType(), buildingFileName);

            hiredEmployeesCache.put(pos, new CommercialHireInfo(
                    pos,
                    assignment.npcUuid(),
                    jobType,
                    buildingFileName != null ? buildingFileName : "",
                    buildingName != null ? buildingName : ""
            ));
        }
        hiredEmployeesSourceLastModified = sourceLastModified;
        hiredEmployeesLoaded = true;
        return new HashMap<>(hiredEmployeesCache);
    }

    /**
     * 清除雇佣数据缓存，下次获取时将重新加载
     */
    public static void clearHiredEmployeesCache() {
        hiredEmployeesLoaded = false;
        hiredEmployeesSourceLastModified = Long.MIN_VALUE;
        hiredEmployeesCache.clear();
    }

    static boolean shouldReuseHiredEmployeesCache(boolean loaded, long cachedSourceLastModified, long currentSourceLastModified) {
        return loaded && cachedSourceLastModified == currentSourceLastModified;
    }

    private static long getEmploymentDataLastModified(MinecraftServer server) {
        try {
            Path dataFile = getWorldPath(server).resolve(FileUtils.MODE_DIR).resolve("employment_assignments_v2.json");
            return Files.exists(dataFile) ? Files.getLastModifiedTime(dataFile).toMillis() : Long.MIN_VALUE;
        } catch (Exception e) {
            Simukraft.LOGGER.warn("[CommercialHiredData] Failed to inspect employment data timestamp", e);
            return Long.MIN_VALUE;
        }
    }

    public static void saveStockData(MinecraftServer server) {
        JsonObject data = new JsonObject();
        Map<BlockPos, Map<String, StockInfo>> stockSnapshot = new HashMap<>();

        for (Map.Entry<BlockPos, Map<String, StockInfo>> entry : stockData.entrySet()) {
            stockSnapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        for (Map.Entry<BlockPos, Map<String, StockInfo>> entry : stockSnapshot.entrySet()) {
            String posKey = entry.getKey().toString();
            JsonObject items = new JsonObject();

            for (Map.Entry<String, StockInfo> stockEntry : entry.getValue().entrySet()) {
                JsonObject stockJson = new JsonObject();
                stockJson.addProperty("itemId", stockEntry.getValue().itemId);
                stockJson.addProperty("currentStock", stockEntry.getValue().currentStock);
                stockJson.addProperty("maxStock", stockEntry.getValue().maxStock);
                stockJson.addProperty("lastRestockTime", stockEntry.getValue().lastRestockTime);
                stockJson.addProperty("dailyBoughtAmount", stockEntry.getValue().dailyBoughtAmount);
                stockJson.addProperty("maxBuyAmount", stockEntry.getValue().maxBuyAmount);
                items.add(stockEntry.getKey(), stockJson);
            }

            data.add(posKey, items);
        }

        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(COMMERCIAL_STOCK_DATA_FILE);

            if (!Files.exists(simukraftDir)) {
                Files.createDirectories(simukraftDir);
            }

            try (java.io.Writer writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[CommercialHiredData] Failed to save commercial stock data", e);
        }
    }

    public static void loadStockData(MinecraftServer server) {
        stockData.clear();

        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(COMMERCIAL_STOCK_DATA_FILE);

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
                        JsonObject items = entry.getValue().getAsJsonObject();
                        Map<String, StockInfo> posStockData = new ConcurrentHashMap<>();

                        for (Map.Entry<String, com.google.gson.JsonElement> stockEntry : items.entrySet()) {
                            JsonObject stockJson = stockEntry.getValue().getAsJsonObject();
                            String itemId = stockJson.get("itemId").getAsString();
                            int currentStock = stockJson.get("currentStock").getAsInt();
                            int maxStock = stockJson.has("maxStock") ? stockJson.get("maxStock").getAsInt() : 0;
                            long lastRestockTime = stockJson.get("lastRestockTime").getAsLong();
                            int dailyBoughtAmount = stockJson.has("dailyBoughtAmount") ? stockJson.get("dailyBoughtAmount").getAsInt() : 0;
                            int maxBuyAmount = stockJson.has("maxBuyAmount") ? stockJson.get("maxBuyAmount").getAsInt() : 0;
                            posStockData.put(stockEntry.getKey(), new StockInfo(itemId, currentStock, maxStock, lastRestockTime, dailyBoughtAmount, maxBuyAmount));
                        }

                        stockData.put(pos, posStockData);
                    } catch (Exception e) {
                        Simukraft.LOGGER.warn("[CommercialHiredData] Failed to parse commercial stock data entry: {}", entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[CommercialHiredData] Failed to load commercial stock data", e);
        }
    }

    public static CustomEntity findNPCByUuid(MinecraftServer server, UUID uuid) {
        return NPCEntityLocator.findNpc(server, uuid, true);
    }

    public static String getJobType(MinecraftServer server, BlockPos pos) {
        try {
            if (server == null) {
                Simukraft.LOGGER.warn("[CommercialHiredData] Server is null in getJobType");
                return null;
            }
            Map<BlockPos, CommercialHireInfo> hiredEmployees = loadHiredEmployees(server);
            CommercialHireInfo hireInfo = hiredEmployees.get(pos);
            if (hireInfo != null) {
                return hireInfo.getJobType();
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[CommercialHiredData] Failed to get jobType for pos {}", pos, e);
        }
        return null;
    }

    public static StockInfo getStock(BlockPos pos, String itemId) {
        Map<String, StockInfo> posStockData = stockData.get(pos);
        if (posStockData == null) {
            return null;
        }
        return posStockData.get(itemId);
    }

    public static void updateStock(BlockPos pos, String itemId, int newStock, long restockTime) {
        stockData.computeIfAbsent(pos, k -> new ConcurrentHashMap<>())
                .put(itemId, new StockInfo(itemId, newStock, restockTime));
    }

    public static void updateStockFull(BlockPos pos, String itemId, int currentStock, int maxStock,
                                       long lastRestockTime, int dailyBoughtAmount, int maxBuyAmount) {
        stockData.computeIfAbsent(pos, k -> new ConcurrentHashMap<>())
                .put(itemId, new StockInfo(itemId, currentStock, maxStock, lastRestockTime, dailyBoughtAmount, maxBuyAmount));
    }

    public static Map<String, StockInfo> getAllStockAtPos(BlockPos pos) {
        return copyStockMap(pos);
    }

    public static Set<BlockPos> getAllStockPositions() {
        return new HashSet<>(stockData.keySet());
    }

    public static Map<String, StockInfo> getAllStockForPos(BlockPos pos) {
        return copyStockMap(pos);
    }

    public static void removeStock(BlockPos pos, String itemId) {
        Map<String, StockInfo> posStockData = stockData.get(pos);
        if (posStockData != null) {
            posStockData.remove(itemId);
            if (posStockData.isEmpty()) {
                stockData.remove(pos);
            }
        }
    }

    private static Map<String, StockInfo> copyStockMap(BlockPos pos) {
        Map<String, StockInfo> posStockData = stockData.get(pos);
        return posStockData == null ? new HashMap<>() : new HashMap<>(posStockData);
    }

    private static BlockPos parseBlockPos(String posString) {
        try {
            String[] parts = posString.substring(8, posString.length() - 1).split(",");
            int x = Integer.parseInt(parts[0].split("=")[1].trim());
            int y = Integer.parseInt(parts[1].split("=")[1].trim());
            int z = Integer.parseInt(parts[2].split("=")[1].trim());
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            Simukraft.LOGGER.warn("[CommercialHiredData] Failed to parse BlockPos: {}", posString);
            return new BlockPos(0, 0, 0);
        }
    }

    private static String resolveDisplayJobType(com.xiaoliang.simukraft.employment.domain.JobType jobType, String buildingFileName) {
        String configJobType = readJobTypeFromConfig(buildingFileName);
        return configJobType != null ? configJobType : LegacyJobTypeMapper.toLegacy(jobType);
    }

    private static String readJobTypeFromConfig(String buildingFileName) {
        if (buildingFileName == null || buildingFileName.isBlank()) {
            return null;
        }
        var config = CommercialBuildingManager.getConfig(buildingFileName.replace(".sk", "").toLowerCase());
        if (config == null || config.getJobType() == null || config.getJobType().isBlank()) {
            return null;
        }
        return config.getJobType();
    }

    private static String inferJobHint(String jobType, String buildingFileName) {
        // 优先从JSON配置文件读取建筑类型
        if (buildingFileName != null && !buildingFileName.isBlank()) {
            var config = CommercialBuildingManager.getConfig(buildingFileName.replace(".sk", "").toLowerCase());
            if (config != null) {
                String configBuildingType = config.getBuildingName();
                if (configBuildingType != null && !configBuildingType.isBlank()) {
                    return configBuildingType;
                }
            }
        }
        // 默认返回通用商业建筑类型
        return "commercial";
    }
}
