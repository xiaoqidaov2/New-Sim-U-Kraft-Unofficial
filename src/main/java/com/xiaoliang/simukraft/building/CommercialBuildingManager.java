package com.xiaoliang.simukraft.building;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig.TradeItem;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商业建筑配置管理器
 * 负责从JSON和SK文件加载商业建筑配置
 * 
 * 工作流程：
 * 1. 首次启动：将JAR内的默认配置复制到 simukraftbuilding/commercial/
 * 2. 后续启动：从 simukraftbuilding/commercial/ 读取配置（允许用户修改）
 */
public class CommercialBuildingManager {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson gson = new Gson();
    private static final Map<String, CommercialBuildingConfig> buildingConfigs = new ConcurrentHashMap<>();
    private static boolean initialized = false;
    
    // 默认配置文件路径（JAR资源内）
    private static final String DEFAULT_CONFIG_PATH = "assets/simukraft/building/commercial";
    // 用户配置文件路径（游戏目录下）
    private static final String USER_CONFIG_PATH = "simukraftbuilding/commercial";
    // 标记文件，用于判断是否首次启动
    private static final String FIRST_RUN_MARKER = ".initialized";
    
    // 已知的商业建筑ID列表（用于从类路径复制）
    private static final String[] KNOWN_BUILDING_IDS = {
        "JCSD", "RP", "SGD", "MBD", "YY", "YH"
    };
    
    /**
     * 初始化并加载所有商业建筑配置
     * @param server 服务器实例，如果为null则只从JAR资源复制默认配置
     */
    public static void init(MinecraftServer server) {
        if (initialized) {
            return;
        }
        
        buildingConfigs.clear();
        
        // 获取用户配置目录
        Path userConfigDir = new File(USER_CONFIG_PATH).toPath();
        
        // 检查是否首次启动（目录不存在或标记文件不存在都视为首次启动）
        boolean isFirstRun = !Files.exists(userConfigDir) || !Files.exists(userConfigDir.resolve(FIRST_RUN_MARKER));
        
        if (isFirstRun) {
            LOGGER.info("[CommercialBuildingManager] 首次启动，复制默认配置到用户目录");
            copyDefaultConfigsToUserDir();
        }
        
        // 从用户目录加载配置
        loadConfigsFromUserDir();
        
        initialized = true;
        LOGGER.info("[CommercialBuildingManager] 已加载 {} 个商业建筑配置", buildingConfigs.size());
    }
    
    /**
     * 将JAR内的默认配置复制到用户目录
     * 支持开发环境（文件系统）和生产环境（JAR）
     */
    private static void copyDefaultConfigsToUserDir() {
        try {
            // 创建用户配置目录
            Path userConfigDir = new File(USER_CONFIG_PATH).toPath();
            if (!Files.exists(userConfigDir)) {
                Files.createDirectories(userConfigDir);
                LOGGER.info("[CommercialBuildingManager] 创建用户配置目录: {}", userConfigDir.toAbsolutePath());
            }
            
            // 使用已知的建筑ID列表复制文件
            // 这种方法在开发环境和生产环境都有效
            int copyCount = 0;
            for (String buildingId : KNOWN_BUILDING_IDS) {
                boolean copied = copyBuildingFiles(buildingId, userConfigDir);
                if (copied) {
                    copyCount++;
                }
            }
            
            LOGGER.info("[CommercialBuildingManager] 共复制 {} 个建筑配置", copyCount);
            
            // 创建首次启动标记文件
            createFirstRunMarker(userConfigDir);
            
        } catch (Exception e) {
            LOGGER.error("[CommercialBuildingManager] 复制默认配置失败", e);
        }
    }
    
    /**
     * 复制单个建筑的所有配置文件
     * @param buildingId 建筑ID
     * @param userConfigDir 目标目录
     * @return 是否成功复制了SK文件
     */
    private static boolean copyBuildingFiles(String buildingId, Path userConfigDir) {
        boolean skCopied = false;
        
        // 复制SK文件
        String skFileName = buildingId + ".sk";
        try (InputStream is = CommercialBuildingManager.class.getClassLoader()
                .getResourceAsStream(DEFAULT_CONFIG_PATH + "/" + skFileName)) {
            if (is != null) {
                Path targetSkFile = userConfigDir.resolve(skFileName);
                Files.copy(is, targetSkFile, StandardCopyOption.REPLACE_EXISTING);
                skCopied = true;
            }
        } catch (Exception e) {
            LOGGER.error("[CommercialBuildingManager] 复制SK文件失败: {}", skFileName, e);
        }
        
        // 复制JSON文件
        String jsonFileName = buildingId + ".json";
        try (InputStream is = CommercialBuildingManager.class.getClassLoader()
                .getResourceAsStream(DEFAULT_CONFIG_PATH + "/" + jsonFileName)) {
            if (is != null) {
                Path targetJsonFile = userConfigDir.resolve(jsonFileName);
                Files.copy(is, targetJsonFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.error("[CommercialBuildingManager] 复制JSON文件失败: {}", jsonFileName, e);
        }
        
        // 复制NBT文件（如果存在）
        String nbtFileName = buildingId + ".nbt";
        try (InputStream is = CommercialBuildingManager.class.getClassLoader()
                .getResourceAsStream(DEFAULT_CONFIG_PATH + "/" + nbtFileName)) {
            if (is != null) {
                Path targetNbtFile = userConfigDir.resolve(nbtFileName);
                Files.copy(is, targetNbtFile, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[CommercialBuildingManager] 复制NBT文件: " + nbtFileName);
            }
        } catch (Exception e) {
            // NBT文件是可选的，不报错
        }
        
        return skCopied;
    }
    
    /**
     * 创建首次启动标记文件
     */
    private static void createFirstRunMarker(Path userConfigDir) {
        try {
            Path markerFile = userConfigDir.resolve(FIRST_RUN_MARKER);
            Files.write(markerFile, Collections.singletonList("Commercial building configs initialized"));
        } catch (Exception e) {
            LOGGER.error("[CommercialBuildingManager] 创建标记文件失败", e);
        }
    }
    
    /**
     * 从用户目录加载配置
     */
    private static void loadConfigsFromUserDir() {
        try {
            Path userConfigDir = new File(USER_CONFIG_PATH).toPath();
            if (!Files.exists(userConfigDir)) {
                LOGGER.warn("[CommercialBuildingManager] 用户配置目录不存在: {}", USER_CONFIG_PATH);
                return;
            }
            
            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = Files.newDirectoryStream(userConfigDir, "*.sk")) {
                for (java.nio.file.Path filePath : stream) {
                    String fileName = filePath.getFileName().toString();
                    String buildingId = fileName.substring(0, fileName.lastIndexOf('.'));

                    // 加载SK文件
                    CommercialBuildingConfig config = loadSkFileFromDisk(filePath.toFile());
                    if (config != null) {
                        config.setBuildingId(buildingId);

                        // 尝试加载对应的JSON文件
                        File jsonFile = userConfigDir.resolve(buildingId + ".json").toFile();
                        if (jsonFile.exists()) {
                            loadJsonConfigFromDisk(config, jsonFile);
                        }

                        buildingConfigs.put(buildingId.toLowerCase(), config);
                    }
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("[CommercialBuildingManager] 加载配置失败", e);
        }
    }
    
    /**
     * 重新加载配置
     */
    public static void reload(MinecraftServer server) {
        initialized = false;
        init(server);
    }
    
    /**
     * 获取所有建筑配置
     */
    public static Collection<CommercialBuildingConfig> getAllConfigs() {
        return buildingConfigs.values();
    }
    
    /**
     * 根据建筑ID获取配置
     */
    public static CommercialBuildingConfig getConfig(String buildingId) {
        if (buildingId == null || buildingId.isBlank()) {
            return null;
        }
        String normalizedId = buildingId.toLowerCase();
        CommercialBuildingConfig config = buildingConfigs.get(normalizedId);
        // 仅在尚未初始化时触发一次延迟加载，避免配置缺失时反复全量重载
        if (config == null && !initialized) {
            init(null);
            config = buildingConfigs.get(normalizedId);
        }
        return config;
    }
    
    /**
     * 根据工作类型获取配置列表
     */
    public static List<CommercialBuildingConfig> getConfigsByJobType(String jobType) {
        List<CommercialBuildingConfig> result = new ArrayList<>();
        if (jobType == null || jobType.isBlank()) {
            return result;
        }

        String normalizedJobType = jobType.trim();
        for (CommercialBuildingConfig config : buildingConfigs.values()) {
            if (config == null) {
                continue;
            }

            String configJobType = config.getJobType();
            if (configJobType == null || configJobType.isBlank()) {
                LOGGER.warn("[CommercialBuildingManager] 跳过缺少 jobType 的配置: {}", config.getBuildingId());
                continue;
            }

            if (configJobType.equalsIgnoreCase(normalizedJobType)) {
                result.add(config);
            }
        }
        return result;
    }
    
    /**
     * 根据商店模式获取配置列表
     */
    public static List<CommercialBuildingConfig> getConfigsByShopMode(CommercialBuildingConfig.ShopMode shopMode) {
        List<CommercialBuildingConfig> result = new ArrayList<>();
        for (CommercialBuildingConfig config : buildingConfigs.values()) {
            if (config.getShopMode() == shopMode) {
                result.add(config);
            }
        }
        return result;
    }

    /**
     * 检查指定的jobType是否是商业建筑职业
     * 统一使用此方法检查，避免硬编码
     */
    public static boolean isCommercialJobType(String jobType) {
        if (jobType == null || jobType.isBlank()) {
            return false;
        }
        if (!initialized) {
            init(null);
        }
        // 通过查找是否有对应的商业建筑配置来判断
        List<CommercialBuildingConfig> configs = getConfigsByJobType(jobType);
        return !configs.isEmpty();
    }
    
    /**
     * 从磁盘加载SK文件
     */
    private static CommercialBuildingConfig loadSkFileFromDisk(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String buildingId = file.getName().substring(0, file.getName().lastIndexOf('.'));
            return parseSkFile(buildingId, reader);
        } catch (Exception e) {
            LOGGER.error("[CommercialBuildingManager] 加载SK文件失败: {}", file.getAbsolutePath(), e);
            return null;
        }
    }
    
    /**
     * 解析SK文件内容
     */
    private static CommercialBuildingConfig parseSkFile(String buildingId, BufferedReader reader) throws IOException {
        CommercialBuildingConfig config = new CommercialBuildingConfig();
        config.setBuildingId(buildingId);
        
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            
            int colonIndex = line.indexOf(':');
            if (colonIndex == -1) continue;
            
            String key = line.substring(0, colonIndex).trim();
            String value = line.substring(colonIndex + 1).trim();
            
            switch (key) {
                case "name" -> config.setBuildingName(value);
                case "job_type" -> config.setJobType(value);
                case "job_name" -> config.setJobName(value);
                case "size" -> config.setSize(value);
                case "amount" -> {
                    try {
                        value = value.replace("元", "").trim();
                        config.setAmount(Double.parseDouble(value));
                    } catch (NumberFormatException ignored) {}
                }
                case "author" -> config.setAuthor(value);
                case "description" -> config.setDescription(value);
                case "held_item" -> config.setHeldItem(value);
                case "shop_mode" -> config.setShopMode(value);
                case "work_block_hint" -> config.setWorkBlockHint(value);
            }
        }
        
        // 设置默认值
        if (config.getJobName() == null) {
            config.setJobName(config.getBuildingName() + "商人");
        }
        
        return config;
    }
    
    /**
     * 从磁盘加载JSON配置
     */
    private static void loadJsonConfigFromDisk(CommercialBuildingConfig config, File file) {
        try (Reader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);
            parseJsonConfig(config, json);
        } catch (Exception e) {
            LOGGER.error("[CommercialBuildingManager] 加载JSON配置失败: {}", file.getAbsolutePath(), e);
        }
    }
    
    /**
     * 解析JSON配置
     */
    private static void parseJsonConfig(CommercialBuildingConfig config, JsonObject json) {
        // 基础信息
        if (json.has("jobType")) {
            config.setJobType(json.get("jobType").getAsString());
        }
        if (json.has("jobName")) {
            config.setJobName(json.get("jobName").getAsString());
        }
        if (json.has("heldItem")) {
            config.setHeldItem(json.get("heldItem").getAsString());
        }
        if (json.has("shopMode")) {
            config.setShopMode(json.get("shopMode").getAsString());
        }
        if (json.has("workBlockHint")) {
            config.setWorkBlockHint(json.get("workBlockHint").getAsString());
        }

        // 工作时间
        if (json.has("workTime")) {
            JsonObject workTime = json.getAsJsonObject("workTime");
            if (workTime.has("start")) {
                config.setWorkStartTime(workTime.get("start").getAsInt());
            }
            if (workTime.has("end")) {
                config.setWorkEndTime(workTime.get("end").getAsInt());
            }
        }
        
        // 补货时间点（优先使用新的 restockTime，兼容旧的 restockInterval）
        if (json.has("restockTime")) {
            config.setRestockTime(json.get("restockTime").getAsInt());
        }
        
        // 交易物品配置（NPC出售给玩家）
        if (json.has("trades")) {
            JsonArray trades = json.getAsJsonArray("trades");
            for (JsonElement elem : trades) {
                JsonObject trade = elem.getAsJsonObject();
                String itemId = trade.get("item").getAsString();
                double buyPrice = trade.has("buyPrice") ? trade.get("buyPrice").getAsDouble() : 0.0;
                double sellPrice = trade.has("sellPrice") ? trade.get("sellPrice").getAsDouble() : 0.0;
                int maxStock = trade.has("maxStock") ? trade.get("maxStock").getAsInt() : 64;
                int restockAmount = trade.has("restockAmount") ? trade.get("restockAmount").getAsInt() : 32;
                TradeItem tradeItem = new TradeItem(itemId, buyPrice, sellPrice, maxStock, restockAmount);
                // 解析原料需求字段（可选）
                if (trade.has("requiredMaterial")) {
                    tradeItem.setRequiredMaterial(trade.get("requiredMaterial").getAsString());
                }
                if (trade.has("requiredMaterialCount")) {
                    tradeItem.setRequiredMaterialCount(trade.get("requiredMaterialCount").getAsInt());
                }
                // 解析零售模式字段（可选）
                if (trade.has("retail")) {
                    tradeItem.setRetail(trade.get("retail").getAsBoolean());
                }
                config.addTrade(tradeItem);
            }
        }

        // 收购物品配置（NPC从玩家购买）
        if (json.has("buyTrades")) {
            JsonArray buyTrades = json.getAsJsonArray("buyTrades");
            for (JsonElement elem : buyTrades) {
                JsonObject buyTrade = elem.getAsJsonObject();
                String itemId = buyTrade.get("item").getAsString();
                double buyPrice = buyTrade.has("buyPrice") ? buyTrade.get("buyPrice").getAsDouble() : 0.0;
                int maxBuyAmount = buyTrade.has("maxBuyAmount") ? buyTrade.get("maxBuyAmount").getAsInt() : 64;
                CommercialBuildingConfig.BuyTradeItem buyTradeItem = new CommercialBuildingConfig.BuyTradeItem(itemId, buyPrice, maxBuyAmount);
                config.addBuyTrade(buyTradeItem);
            }
        }

        // 原料需求配置（新增）
        if (json.has("requireMaterialsForSale")) {
            config.setRequireMaterialsForSale(json.get("requireMaterialsForSale").getAsBoolean());
        }
        
        if (json.has("materials")) {
            JsonArray materials = json.getAsJsonArray("materials");
            for (JsonElement elem : materials) {
                JsonObject material = elem.getAsJsonObject();
                String itemId = material.get("item").getAsString();
                int count = material.has("count") ? material.get("count").getAsInt() : 1;
                CommercialBuildingConfig.MaterialRequirement req = new CommercialBuildingConfig.MaterialRequirement(itemId, count);
                if (material.has("consume")) {
                    req.setConsume(material.get("consume").getAsBoolean());
                }
                config.addMaterial(req);
            }
        }

        // 消息配置
        if (json.has("messages")) {
            JsonObject messages = json.getAsJsonObject("messages");
            for (java.util.Map.Entry<String, JsonElement> entry : messages.entrySet()) {
                config.getMessages().put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }
    
    /**
     * 检查是否存在指定建筑的配置
     */
    public static boolean hasConfig(String buildingId) {
        return buildingConfigs.containsKey(buildingId.toLowerCase());
    }
    
    /**
     * 获取建筑显示名称
     */
    public static String getBuildingDisplayName(String buildingId) {
        CommercialBuildingConfig config = getConfig(buildingId);
        return config != null ? config.getBuildingName() : buildingId;
    }
    
    /**
     * 获取工作显示名称
     */
    public static String getJobDisplayName(String buildingId) {
        CommercialBuildingConfig config = getConfig(buildingId);
        return config != null ? config.getJobName() : "商人";
    }
    
    /**
     * 获取用户配置目录路径
     */
    public static String getUserConfigPath() {
        return USER_CONFIG_PATH;
    }
}
