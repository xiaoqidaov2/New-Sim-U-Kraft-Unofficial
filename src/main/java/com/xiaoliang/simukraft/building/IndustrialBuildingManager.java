package com.xiaoliang.simukraft.building;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.xiaoliang.simukraft.building.IndustrialBuildingConfig.MaterialRequirement;
import com.xiaoliang.simukraft.building.IndustrialBuildingConfig.ProductOutput;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 工业建筑配置管理器
 * 负责从JSON和SK文件加载工业建筑配置
 * 
 * 工作流程：
 * 1. 首次启动：将JAR内的默认配置复制到 simukraftbuilding/industry/
 * 2. 后续启动：从 simukraftbuilding/industry/ 读取配置（允许用户修改）
 */
public class IndustrialBuildingManager {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson gson = new Gson();
    private static final Map<String, IndustrialBuildingConfig> buildingConfigs = new HashMap<>();
    private static boolean initialized = false;
    
    // 用户配置文件路径（游戏目录下）- 由 BuildingDataManager 复制文件到此目录
    private static final String USER_CONFIG_PATH = "simukraftbuilding/industry";
    
    /**
     * 初始化并加载所有工业建筑配置
     * @param server 服务器实例，如果为null则只从JAR资源复制默认配置
     */
    public static void init(MinecraftServer server) {
        if (initialized) {
            return;
        }
        
        buildingConfigs.clear();
        
        // 显式触发公共建筑数据初始化，避免通过反射制造不必要的加载链。
        com.xiaoliang.simukraft.utils.BuildingDataManager.init();
        
        // 从用户目录加载配置
        loadConfigsFromUserDir();
        
        initialized = true;
        LOGGER.info("[IndustrialBuildingManager] 已加载 {} 个工业建筑配置", buildingConfigs.size());
    }
    
    /**
     * 从用户目录加载配置
     */
    private static void loadConfigsFromUserDir() {
        try {
            Path userConfigDir = new File(USER_CONFIG_PATH).toPath();
            if (!Files.exists(userConfigDir)) {
                LOGGER.warn("[IndustrialBuildingManager] 用户配置目录不存在: {}", USER_CONFIG_PATH);
                return;
            }
            
            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = Files.newDirectoryStream(userConfigDir, "*.sk")) {
                for (java.nio.file.Path filePath : stream) {
                    String fileName = filePath.getFileName().toString();
                    String buildingId = fileName.substring(0, fileName.lastIndexOf('.'));
                    
                    // 加载SK文件
                    IndustrialBuildingConfig config = loadSkFileFromDisk(filePath.toFile());
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
            LOGGER.error("[IndustrialBuildingManager] 加载配置失败", e);
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
    public static Collection<IndustrialBuildingConfig> getAllConfigs() {
        return buildingConfigs.values();
    }
    
    /**
     * 根据建筑ID获取配置
     */
    public static IndustrialBuildingConfig getConfig(String buildingId) {
        if (buildingId == null || buildingId.isBlank()) {
            return null;
        }
        return buildingConfigs.get(buildingId.toLowerCase());
    }
    
    /**
     * 根据工作类型获取配置列表
     */
    public static List<IndustrialBuildingConfig> getConfigsByJobType(String jobType) {
        List<IndustrialBuildingConfig> result = new ArrayList<>();
        if (jobType == null || jobType.isBlank()) {
            return result;
        }

        String normalizedJobType = jobType.trim();
        for (IndustrialBuildingConfig config : buildingConfigs.values()) {
            if (config == null) {
                continue;
            }

            String configJobType = config.getJobType();
            if (configJobType == null || configJobType.isBlank()) {
                LOGGER.warn("[IndustrialBuildingManager] 跳过缺少 jobType 的配置: {}", config.getBuildingId());
                continue;
            }

            if (configJobType.equalsIgnoreCase(normalizedJobType)) {
                result.add(config);
            }
        }
        return result;
    }
    
    /**
     * 从磁盘加载SK文件
     */
    private static IndustrialBuildingConfig loadSkFileFromDisk(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String buildingId = file.getName().substring(0, file.getName().lastIndexOf('.'));
            return parseSkFile(buildingId, reader);
        } catch (Exception e) {
            LOGGER.error("[IndustrialBuildingManager] 加载SK文件失败: {}", file.getAbsolutePath(), e);
            return null;
        }
    }
    
    /**
     * 解析SK文件内容
     */
    private static IndustrialBuildingConfig parseSkFile(String buildingId, BufferedReader reader) throws IOException {
        IndustrialBuildingConfig config = new IndustrialBuildingConfig();
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
            }
        }
        
        // 设置默认值
        if (config.getJobName() == null) {
            config.setJobName(config.getBuildingName() + "工人");
        }
        
        return config;
    }
    
    /**
     * 从磁盘加载JSON配置
     */
    private static void loadJsonConfigFromDisk(IndustrialBuildingConfig config, File file) {
        try (Reader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);
            parseJsonConfig(config, json);
        } catch (Exception e) {
            LOGGER.error("[IndustrialBuildingManager] 加载JSON配置失败: {}", file.getAbsolutePath(), e);
        }
    }
    
    /**
     * 解析JSON配置
     */
    private static void parseJsonConfig(IndustrialBuildingConfig config, JsonObject json) {
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
        
        // 原料配置
        if (json.has("materials")) {
            JsonArray materials = json.getAsJsonArray("materials");
            for (JsonElement elem : materials) {
                JsonObject mat = elem.getAsJsonObject();
                String itemId = mat.get("item").getAsString();
                int count = mat.has("count") ? mat.get("count").getAsInt() : 1;
                MaterialRequirement req = new MaterialRequirement(itemId, count);
                if (mat.has("consume")) {
                    req.setConsume(mat.get("consume").getAsBoolean());
                }
                config.addMaterial(req);
            }
        }
        
        // 产物配置
        if (json.has("products")) {
            JsonArray products = json.getAsJsonArray("products");
            for (JsonElement elem : products) {
                JsonObject prod = elem.getAsJsonObject();
                String itemId = prod.get("item").getAsString();
                int baseAmount = prod.has("baseAmount") ? prod.get("baseAmount").getAsInt() : 1;
                int randomRange = prod.has("randomRange") ? prod.get("randomRange").getAsInt() : 0;
                ProductOutput output = new ProductOutput(itemId, baseAmount, randomRange);
                if (prod.has("probability")) {
                    output.setProbability(prod.get("probability").getAsDouble());
                }
                if (prod.has("ignoreMultiplier")) {
                    output.setIgnoreMultiplier(prod.get("ignoreMultiplier").getAsBoolean());
                }
                config.addProduct(output);
            }
        }
        
        // 多配方配置
        if (json.has("recipes")) {
            JsonArray recipes = json.getAsJsonArray("recipes");
            for (JsonElement elem : recipes) {
                JsonObject recipeJson = elem.getAsJsonObject();
                String recipeId = recipeJson.has("recipeId") ? recipeJson.get("recipeId").getAsString() : "recipe_" + config.getRecipes().size();
                String recipeName = recipeJson.has("recipeName") ? recipeJson.get("recipeName").getAsString() : recipeId;
                
                RecipeConfig recipe = new RecipeConfig(recipeId, recipeName);
                
                // 配方手持物品（可选）
                if (recipeJson.has("heldItem")) {
                    recipe.setHeldItem(recipeJson.get("heldItem").getAsString());
                }
                
                // 配方工作时间（可选）
                if (recipeJson.has("workTime")) {
                    JsonObject workTime = recipeJson.getAsJsonObject("workTime");
                    if (workTime.has("start")) {
                        recipe.setWorkStartTime(workTime.get("start").getAsInt());
                    }
                    if (workTime.has("end")) {
                        recipe.setWorkEndTime(workTime.get("end").getAsInt());
                    }
                }
                
                // 配方原料
                if (recipeJson.has("materials")) {
                    JsonArray materials = recipeJson.getAsJsonArray("materials");
                    for (JsonElement matElem : materials) {
                        JsonObject mat = matElem.getAsJsonObject();
                        String itemId = mat.get("item").getAsString();
                        int count = mat.has("count") ? mat.get("count").getAsInt() : 1;
                        MaterialRequirement req = new MaterialRequirement(itemId, count);
                        if (mat.has("consume")) {
                            req.setConsume(mat.get("consume").getAsBoolean());
                        }
                        recipe.addMaterial(req);
                    }
                }
                
                // 配方产物
                if (recipeJson.has("products")) {
                    JsonArray products = recipeJson.getAsJsonArray("products");
                    for (JsonElement prodElem : products) {
                        JsonObject prod = prodElem.getAsJsonObject();
                        String itemId = prod.get("item").getAsString();
                        int baseAmount = prod.has("baseAmount") ? prod.get("baseAmount").getAsInt() : 1;
                        int randomRange = prod.has("randomRange") ? prod.get("randomRange").getAsInt() : 0;
                        ProductOutput output = new ProductOutput(itemId, baseAmount, randomRange);
                        if (prod.has("probability")) {
                            output.setProbability(prod.get("probability").getAsDouble());
                        }
                        if (prod.has("ignoreMultiplier")) {
                            output.setIgnoreMultiplier(prod.get("ignoreMultiplier").getAsBoolean());
                        }
                        recipe.addProduct(output);
                    }
                }
                
                config.addRecipe(recipe);
            }
        }
        
        // 生物生成配置
        if (json.has("spawnEntity")) {
            JsonObject entity = json.getAsJsonObject("spawnEntity");
            config.setSpawnEntity(entity.has("enabled") && entity.get("enabled").getAsBoolean());
            if (entity.has("type")) {
                config.setEntityType(entity.get("type").getAsString());
            }
            if (entity.has("count")) {
                config.setEntityCount(entity.get("count").getAsInt());
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
        IndustrialBuildingConfig config = getConfig(buildingId);
        return config != null ? config.getBuildingName() : buildingId;
    }
    
    /**
     * 获取工作显示名称
     */
    public static String getJobDisplayName(String buildingId) {
        IndustrialBuildingConfig config = getConfig(buildingId);
        return config != null ? config.getJobName() : "工人";
    }
    
    /**
     * 获取用户配置目录路径
     */
    public static String getUserConfigPath() {
        return USER_CONFIG_PATH;
    }
}
