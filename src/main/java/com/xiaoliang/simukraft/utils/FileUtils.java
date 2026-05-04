package com.xiaoliang.simukraft.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class FileUtils {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();
    
    // 建筑缓存数据类
    public static class BuildingCacheEntry {
        private final String buildingFileName;  // JSON配置文件名（如 JJJ）
        private final String jobType;           // 工作类型
        private final String selectedRecipe;    // 选中的配方
        private final Path skFilePath;          // SK文件路径
        private JsonObject jsonConfig;          // JSON配置内容
        private final String cityId;            // 城市ID
        
        public BuildingCacheEntry(String buildingFileName, String jobType, String selectedRecipe, 
                                  Path skFilePath, String cityId) {
            this.buildingFileName = buildingFileName;
            this.jobType = jobType;
            this.selectedRecipe = selectedRecipe;
            this.skFilePath = skFilePath;
            this.cityId = cityId;
        }
        
        public String getBuildingFileName() { return buildingFileName; }
        public String getJobType() { return jobType; }
        public String getSelectedRecipe() { return selectedRecipe; }
        public Path getSkFilePath() { return skFilePath; }
        public JsonObject getJsonConfig() { return jsonConfig; }
        public void setJsonConfig(JsonObject jsonConfig) { this.jsonConfig = jsonConfig; }
        public String getCityId() { return cityId; }
        
        @Override
        public String toString() {
            return String.format("BuildingCacheEntry{file=%s, job=%s, recipe=%s}", 
                buildingFileName, jobType, selectedRecipe);
        }
    }
    
    // 建筑缓存 - 键: "commercial:x,y,z" 或 "industrial:x,y,z", 值: BuildingCacheEntry
    private static final Map<String, BuildingCacheEntry> BUILDING_CACHE = new ConcurrentHashMap<>();
    
    // 兼容旧代码的SK文件缓存 - 只存储building_file_name
    @Deprecated
    private static final Map<String, String> SK_FILE_CACHE = new ConcurrentHashMap<>();
    
    private static boolean cacheInitialized = false;
    public static final String MODE_FILE = "data.sk";
    public static final String MODE_DIR = "simukraft";
    public static final String NPC_DIR = "npc";
    public static final String BUSINESS_DIR = "business";
    public static final String CR_DIR = "cr";
    public static final String MEATSHOP_DIR = "meatshop"; // 添加肉铺文件夹常量
    public static final String FRUIT_DIR = "fruit"; // 添加fruit文件夹常量
    public static final String WHEAT_DIR = "wheat"; // 添加wheat文件夹常量
    public static final String INDUSTRIAL_DIR = "industrial"; // 添加工业文件夹常量
    public static final String COMMERCIAL_DIR = "commercial"; // 添加商业文件夹常量

    public static String getWorldId(MinecraftServer server) {
        try {
            Path worldPath = getWorldPath(server);
            String worldName = worldPath.getFileName().toString();

            BasicFileAttributes attrs = Files.readAttributes(worldPath, BasicFileAttributes.class);
            FileTime creationTime = attrs.creationTime();

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(
                    new Date(creationTime.to(TimeUnit.MILLISECONDS)));

            String safeName = worldName.replaceAll("[\\\\/:*?\"<>|]", "_");
            String worldId = safeName + "_" + timeStamp;

            LOGGER.debug("Generated world ID: {}", worldId);
            return worldId;
        } catch (Exception e) {
            String fallbackId = "world_" + UUID.randomUUID().toString().substring(0, 8);
            LOGGER.error("Error generating world ID, using fallback: {}", fallbackId, e);
            return fallbackId;
        }
    }

    private static Path getWorldPath(MinecraftServer server) throws Exception {
        Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
        // 转换为绝对路径并规范化，避免相对路径问题
        return worldPath.toAbsolutePath().normalize();
    }

    // 简化模式系统，直接返回普通模式，移除所有模式文件处理
    public static boolean hasModeSelected(MinecraftServer server) {
        // 总是返回true，表示已经选择了默认的普通模式
        return true;
    }

    public static void createModeFile(int mode, MinecraftServer server) throws IOException {
        // 简化实现，不再创建模式文件
        LOGGER.debug("Mode system simplified, no mode file created");
    }

    public static int getCurrentMode(MinecraftServer server) {
        // 总是返回普通模式
        return 1;
    }

    public static boolean isCreativeMode(MinecraftServer server) {
        // 总是返回false，只使用普通模式
        return false;
    }

    public static boolean isNormalMode(MinecraftServer server) {
        // 总是返回true，只使用普通模式
        return true;
    }

    public static File getModeFile(MinecraftServer server) {
        try {
            Path worldDir = getWorldPath(server);
            File modeFile = worldDir.resolve(MODE_DIR)
                    .resolve(MODE_FILE)
                    .toFile();

            return modeFile;
        } catch (Exception e) {
            LOGGER.error("Failed to resolve mode file path", e);
            return null;
        }
    }

    /**
     * 创建业务文件夹结构
     */
    public static void createBusinessFolders(MinecraftServer server) {
        try {
            // 避免在服务器端加载客户端专用类
            if (server == null) {
                LOGGER.warn("服务器实例为null，无法创建业务文件夹结构");
                return;
            }
            
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path businessDir = simukraftDir.resolve(BUSINESS_DIR);
            Path crDir = businessDir.resolve(CR_DIR);

            // 创建文件夹结构
            Files.createDirectories(crDir);
            LOGGER.info("创建业务文件夹结构: {}", crDir);
        } catch (Exception e) {
            LOGGER.error("创建业务文件夹失败", e);
        } catch (Error e) {
            // 捕获NoClassDefFoundError等错误，避免服务器崩溃
            LOGGER.error("创建业务文件夹时发生严重错误", e);
        }
    }

    /**
     * 为建材商店控制箱创建.sk文件
     */
    public static void createBuildingMaterialStoreFile(MinecraftServer server, BlockPos pos) {
        createBuildingMaterialStoreFile(server, pos, null);
    }

    /**
     * 为建材商店控制箱创建.sk文件（带城市ID）
     */
    public static void createBuildingMaterialStoreFile(MinecraftServer server, BlockPos pos, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path businessDir = simukraftDir.resolve(BUSINESS_DIR);
            Path crDir = businessDir.resolve(CR_DIR);

            // 确保文件夹存在
            Files.createDirectories(crDir);

            // 创建文件名：位置坐标.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = crDir.resolve(fileName);

            // 创建文件并写入内容
            try (BufferedWriter writer = Files.newBufferedWriter(skFile)) {
                writer.write("coordinate: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")\n");
                writer.write("type: building_material_store\n");
                if (cityId != null) {
                    writer.write("city_id: " + cityId + "\n");
                }
            }
            LOGGER.info("为建材商店控制箱创建.sk文件: {}" + (cityId != null ? " (城市: " + cityId.toString().substring(0, 8) + ")" : ""), skFile);
        } catch (Exception e) {
            LOGGER.error("创建建材商店.sk文件失败", e);
        }
    }

    /**
     * 删除建材商店控制箱的.sk文件
     */
    public static void deleteBuildingMaterialStoreFile(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path businessDir = simukraftDir.resolve(BUSINESS_DIR);
            Path crDir = businessDir.resolve(CR_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = crDir.resolve(fileName);

            // 删除文件
            if (Files.exists(skFile)) {
                Files.delete(skFile);
                LOGGER.info("删除建材商店控制箱.sk文件: {}", skFile);
            }
        } catch (Exception e) {
            LOGGER.error("删除建材商店.sk文件失败", e);
        }
    }

    /**
     * 检查业务文件夹是否存在
     */
    public static boolean businessFoldersExist(MinecraftServer server) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path businessDir = simukraftDir.resolve(BUSINESS_DIR);
            Path crDir = businessDir.resolve(CR_DIR);

            return Files.exists(crDir) && Files.isDirectory(crDir);
        } catch (Exception e) {
            LOGGER.error("检查业务文件夹失败", e);
            return false;
        }
    }

    /**
     * 为牛肉农场控制箱创建.sk文件
     */
    public static void createBeefFarmFile(MinecraftServer server, BlockPos pos) {
        createBeefFarmFile(server, pos, null);
    }

    /**
     * 为牛肉农场控制箱创建.sk文件（带城市ID）
     */
    public static void createBeefFarmFile(MinecraftServer server, BlockPos pos, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path meatshopDir = simukraftDir.resolve(MEATSHOP_DIR);

            // 确保文件夹存在
            Files.createDirectories(meatshopDir);

            // 创建文件名：位置坐标.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = meatshopDir.resolve(fileName);

            // 创建文件并写入内容
            try (BufferedWriter writer = Files.newBufferedWriter(skFile)) {
                writer.write("coordinate: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")\n");
                if (cityId != null) {
                    writer.write("city_id: " + cityId + "\n");
                }
            }

            LOGGER.info("为牛肉农场控制箱创建.sk文件: {}" + (cityId != null ? " (城市: " + cityId.toString().substring(0, 8) + ")" : ""), skFile);
        } catch (Exception e) {
            LOGGER.error("创建牛肉农场.sk文件失败", e);
        }
    }
    
    /**
     * 删除牛肉农场控制箱的.sk文件
     */
    public static void deleteBeefFarmFile(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path meatshopDir = simukraftDir.resolve(MEATSHOP_DIR);
    
            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = meatshopDir.resolve(fileName);
    
            // 删除文件
            if (Files.exists(skFile)) {
                Files.delete(skFile);
                LOGGER.info("删除牛肉农场控制箱.sk文件: {}", skFile);
            }
        } catch (Exception e) {
            LOGGER.error("删除牛肉农场.sk文件失败", e);
        }
    }
    
    /**
     * 检查肉铺文件夹是否存在
     */
    public static boolean meatshopFolderExist(MinecraftServer server) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path meatshopDir = simukraftDir.resolve(MEATSHOP_DIR);
    
            return Files.exists(meatshopDir) && Files.isDirectory(meatshopDir);
        } catch (Exception e) {
            LOGGER.error("检查肉铺文件夹失败", e);
            return false;
        }
    }

    /**
     * 获取牛肉农场SK文件列表
     */
    public static java.util.List<java.nio.file.Path> getBeefFarmSkFiles(net.minecraft.server.MinecraftServer server) {
        java.util.List<java.nio.file.Path> skFiles = new java.util.ArrayList<>();
        
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path meatshopDir = simukraftDir.resolve(MEATSHOP_DIR);
            
            if (!Files.exists(meatshopDir) || !Files.isDirectory(meatshopDir)) {
                return skFiles;
            }
            
            // 查找所有.sk文件
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(meatshopDir, "*.sk")) {
                for (Path skFile : stream) {
                    if (Files.isRegularFile(skFile)) {
                        skFiles.add(skFile);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("获取牛肉农场SK文件列表失败", e);
        }
        
        return skFiles;
    }

    /**
     * 为农田盒创建.sk文件
     */
    public static void createFarmlandBoxFile(MinecraftServer server, BlockPos pos) {
        createFarmlandBoxFile(server, pos, null);
    }

    /**
     * 为农田盒创建.sk文件（带城市ID）
     */
    public static void createFarmlandBoxFile(MinecraftServer server, BlockPos pos, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path fruitDir = simukraftDir.resolve(FRUIT_DIR);

            // 确保文件夹存在
            Files.createDirectories(fruitDir);

            // 创建文件名：位置坐标.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = fruitDir.resolve(fileName);

            // 创建文件并写入内容
            try (BufferedWriter writer = Files.newBufferedWriter(skFile)) {
                writer.write("coordinate: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")\n");
                writer.write("type: farmland_box\n");
                writer.write("created: " + new Date() + "\n");
                if (cityId != null) {
                    writer.write("city_id: " + cityId + "\n");
                }
            }

            LOGGER.info("为农田盒创建.sk文件: {}" + (cityId != null ? " (城市: " + cityId.toString().substring(0, 8) + ")" : ""), skFile);
        } catch (Exception e) {
            LOGGER.error("创建农田盒.sk文件失败", e);
        }
    }
    
    /**
     * 删除农田盒的.sk文件
     */
    public static void deleteFarmlandBoxFile(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path fruitDir = simukraftDir.resolve(FRUIT_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = fruitDir.resolve(fileName);

            // 删除文件
            if (Files.exists(skFile)) {
                Files.delete(skFile);
                LOGGER.info("删除农田盒.sk文件: {}", skFile);
            }
        } catch (Exception e) {
            LOGGER.error("删除农田盒.sk文件失败", e);
        }
    }

    /**
     * 更新农田盒.sk文件的城市ID
     */
    public static void updateFarmlandBoxCityId(MinecraftServer server, BlockPos pos, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path fruitDir = simukraftDir.resolve(FRUIT_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = fruitDir.resolve(fileName);

            // 检查文件是否存在
            if (!Files.exists(skFile)) {
                LOGGER.warn("农田盒.sk文件不存在，无法更新城市ID: {}", skFile);
                return;
            }

            // 读取现有内容
            String coordinate = null;
            String type = null;
            String created = null;

            try (BufferedReader reader = Files.newBufferedReader(skFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("coordinate:")) {
                        coordinate = line;
                    } else if (line.startsWith("type:")) {
                        type = line;
                    } else if (line.startsWith("created:")) {
                        created = line;
                    } else if (line.startsWith("city_id:")) {
                        // 跳过旧的城市ID行
                    }
                }
            }

            // 写入更新后的内容
            try (BufferedWriter writer = Files.newBufferedWriter(skFile)) {
                if (coordinate != null) writer.write(coordinate + "\n");
                if (type != null) writer.write(type + "\n");
                if (created != null) writer.write(created + "\n");
                if (cityId != null) {
                    writer.write("city_id: " + cityId + "\n");
                }
            }

            LOGGER.info("更新农田盒.sk文件的城市ID: {} -> {}", skFile,
                cityId != null ? cityId.toString().substring(0, 8) : "无");
        } catch (Exception e) {
            LOGGER.error("更新农田盒.sk文件城市ID失败", e);
        }
    }
    
    /**
     * 检查fruit文件夹是否存在
     */
    public static boolean fruitFolderExist(MinecraftServer server) {
        try {
            // 避免在服务器端加载客户端专用类
            if (server == null) {
                LOGGER.warn("服务器实例为null，无法检查fruit文件夹");
                return false;
            }
            
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path fruitDir = simukraftDir.resolve(FRUIT_DIR);
    
            return Files.exists(fruitDir) && Files.isDirectory(fruitDir);
        } catch (Exception e) {
            LOGGER.error("检查fruit文件夹失败", e);
            return false;
        } catch (Error e) {
            // 捕获NoClassDefFoundError等错误，避免服务器崩溃
            LOGGER.error("检查fruit文件夹时发生严重错误", e);
            return false;
        }
    }

    /**
     * 获取农田盒SK文件列表
     */
    public static java.util.List<java.nio.file.Path> getFarmlandBoxSkFiles(net.minecraft.server.MinecraftServer server) {
        java.util.List<java.nio.file.Path> skFiles = new java.util.ArrayList<>();
        
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path fruitDir = simukraftDir.resolve(FRUIT_DIR);
            
            if (!Files.exists(fruitDir) || !Files.isDirectory(fruitDir)) {
                return skFiles;
            }
            
            // 查找所有.sk文件
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(fruitDir, "*.sk")) {
                for (Path skFile : stream) {
                    if (Files.isRegularFile(skFile)) {
                        skFiles.add(skFile);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("获取农田盒SK文件列表失败", e);
        }
        
        return skFiles;
    }

    /**
     * 为水果店控制箱创建.sk文件
     */
    public static void createFruitShopFile(MinecraftServer server, BlockPos pos) {
        createFruitShopFile(server, pos, null);
    }

    /**
     * 为水果店控制箱创建.sk文件（带城市ID）
     * 同时在business/cr和wheat两个文件夹下生成坐标文件
     */
    public static void createFruitShopFile(MinecraftServer server, BlockPos pos, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            
            // 第一个文件夹：business/cr
            Path businessDir = simukraftDir.resolve(BUSINESS_DIR);
            Path crDir = businessDir.resolve(CR_DIR);
            
            // 第二个文件夹：wheat
            Path wheatDir = simukraftDir.resolve(WHEAT_DIR);

            // 确保两个文件夹都存在
            Files.createDirectories(crDir);
            Files.createDirectories(wheatDir);

            // 创建文件名：位置坐标.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            
            // 在business/cr文件夹下创建文件
            Path crSkFile = crDir.resolve(fileName);
            createFruitShopSkFile(crSkFile, pos, cityId);
            
            // 在wheat文件夹下创建文件
            Path wheatSkFile = wheatDir.resolve(fileName);
            createFruitShopSkFile(wheatSkFile, pos, cityId);

            LOGGER.info("为水果店控制箱创建.sk文件: business/cr: {}, wheat: {}" + (cityId != null ? " (城市: " + cityId.toString().substring(0, 8) + ")" : ""), 
                       crSkFile, wheatSkFile);
        } catch (Exception e) {
            LOGGER.error("创建水果店.sk文件失败", e);
        }
    }
    
    /**
     * 创建水果店控制箱的.sk文件内容
     */
    private static void createFruitShopSkFile(Path skFile, BlockPos pos, UUID cityId) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(skFile)) {
            writer.write("coordinate: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")\n");
            writer.write("type: fruit_shop\n");
            writer.write("created: " + new Date() + "\n");
            if (cityId != null) {
                writer.write("city_id: " + cityId + "\n");
            }
        }
    }

    /**
     * 删除水果店控制箱的.sk文件
     * 同时删除business/cr和wheat两个文件夹下的坐标文件
     */
    public static void deleteFruitShopFile(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            
            // 第一个文件夹：business/cr
            Path businessDir = simukraftDir.resolve(BUSINESS_DIR);
            Path crDir = businessDir.resolve(CR_DIR);
            
            // 第二个文件夹：wheat
            Path wheatDir = simukraftDir.resolve(WHEAT_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            
            // 删除business/cr文件夹下的文件
            Path crSkFile = crDir.resolve(fileName);
            if (Files.exists(crSkFile)) {
                Files.delete(crSkFile);
                LOGGER.info("删除水果店控制箱.sk文件: business/cr: {}", crSkFile);
            }
            
            // 删除wheat文件夹下的文件
            Path wheatSkFile = wheatDir.resolve(fileName);
            if (Files.exists(wheatSkFile)) {
                Files.delete(wheatSkFile);
                LOGGER.info("删除水果店控制箱.sk文件: wheat: {}", wheatSkFile);
            }
        } catch (Exception e) {
            LOGGER.error("删除水果店.sk文件失败", e);
        }
    }

    /**
     * 为面包店控制箱创建.sk文件
     */
    public static void createBakeryFile(MinecraftServer server, BlockPos pos) {
        createBakeryFile(server, pos, null);
    }

    /**
     * 为面包店控制箱创建.sk文件（带城市ID）
     */
    public static void createBakeryFile(MinecraftServer server, BlockPos pos, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path businessDir = simukraftDir.resolve(BUSINESS_DIR);
            Path crDir = businessDir.resolve(CR_DIR);

            // 确保文件夹存在
            Files.createDirectories(crDir);

            // 创建文件名：位置坐标.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = crDir.resolve(fileName);

            // 创建文件并写入内容
            try (BufferedWriter writer = Files.newBufferedWriter(skFile)) {
                writer.write("coordinate: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")\n");
                writer.write("type: bakery\n");
                if (cityId != null) {
                    writer.write("city_id: " + cityId + "\n");
                }
            }
            LOGGER.info("为面包店控制箱创建.sk文件: {}" + (cityId != null ? " (城市: " + cityId.toString().substring(0, 8) + ")" : ""), skFile);
        } catch (Exception e) {
            LOGGER.error("创建面包店.sk文件失败", e);
        }
    }

    /**
     * 删除面包店控制箱的.sk文件
     */
    public static void deleteBakeryFile(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path businessDir = simukraftDir.resolve(BUSINESS_DIR);
            Path crDir = businessDir.resolve(CR_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = crDir.resolve(fileName);

            // 删除文件
            if (Files.exists(skFile)) {
                Files.delete(skFile);
                LOGGER.info("删除面包店控制箱.sk文件: {}", skFile);
            }
        } catch (Exception e) {
            LOGGER.error("删除面包店.sk文件失败", e);
        }
    }

    /**
     * 为工业建筑创建.sk文件
     */
    public static void createIndustrialFile(MinecraftServer server, BlockPos pos, String buildingFileName, String jobType, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path industrialDir = simukraftDir.resolve(INDUSTRIAL_DIR);

            // 确保文件夹存在
            Files.createDirectories(industrialDir);

            // 创建文件名：位置坐标.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            // 创建文件并写入内容
            try (BufferedWriter writer = Files.newBufferedWriter(skFile)) {
                writer.write("position: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")\n");
                writer.write("building_file_name: " + buildingFileName + "\n");
                writer.write("job_type: " + jobType + "\n");
                if (cityId != null) {
                    writer.write("city_id: " + cityId + "\n");
                }
            }

            LOGGER.info("为工业建筑创建.sk文件: {}" + (cityId != null ? " (城市: " + cityId.toString().substring(0, 8) + ")" : ""), skFile);
        } catch (Exception e) {
            LOGGER.error("创建工业建筑.sk文件失败", e);
        }
    }

    /**
     * 删除工业建筑的.sk文件
     */
    public static void deleteIndustrialFile(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path industrialDir = simukraftDir.resolve(INDUSTRIAL_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            // 删除文件
            if (Files.exists(skFile)) {
                Files.delete(skFile);
                LOGGER.info("删除工业建筑.sk文件: {}", skFile);
            }
        } catch (Exception e) {
            LOGGER.error("删除工业建筑.sk文件失败", e);
        }
    }

    /**
     * 读取指定位置的 job_type（服务器端）
     */
    public static String readIndustrialJobType(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path industrialDir = simukraftDir.resolve(INDUSTRIAL_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            // 检查文件是否存在
            if (!Files.exists(skFile)) {
                return null;
            }

            // 读取文件内容
            try (BufferedReader reader = Files.newBufferedReader(skFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("job_type:")) {
                        return line.substring("job_type:".length()).trim();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("读取工业建筑 job_type 失败", e);
        }
        return null;
    }

    /**
     * 读取指定商业建筑位置的 job_type（服务器端）
     */
    public static String readCommercialJobType(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path commercialDir = simukraftDir.resolve(COMMERCIAL_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = commercialDir.resolve(fileName);

            // 检查文件是否存在
            if (!Files.exists(skFile)) {
                return null;
            }

            // 读取文件内容
            try (BufferedReader reader = Files.newBufferedReader(skFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("job_type:")) {
                        return line.substring("job_type:".length()).trim();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("读取商业建筑 job_type 失败", e);
        }
        return null;
    }

    /**
     * 读取指定工业建筑位置的 building_file_name
     */
    public static String readIndustrialBuildingFileName(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path industrialDir = simukraftDir.resolve(INDUSTRIAL_DIR);

            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            if (!Files.exists(skFile)) {
                return null;
            }

            try (BufferedReader reader = Files.newBufferedReader(skFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("building_file_name:")) {
                        return normalizeBuildingFileName(line.substring("building_file_name:".length()).trim());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("读取工业建筑 building_file_name 失败", e);
        }
        return null;
    }

    /**
     * 读取指定商业建筑位置的 building_file_name
     */
    public static String readCommercialBuildingFileName(MinecraftServer server, BlockPos pos) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path commercialDir = simukraftDir.resolve(COMMERCIAL_DIR);

            // 构建文件名
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = commercialDir.resolve(fileName);

            // 检查文件是否存在
            if (!Files.exists(skFile)) {
                return null;
            }

            // 读取文件内容
            try (BufferedReader reader = Files.newBufferedReader(skFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("building_file_name:")) {
                        return normalizeBuildingFileName(line.substring("building_file_name:".length()).trim());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("读取商业建筑 building_file_name 失败", e);
        }
        return null;
    }

    /**
     * 规范化建筑配置文件名，统一去掉 .sk 后缀和首尾空白
     */
    public static String normalizeBuildingFileName(String buildingFileName) {
        if (buildingFileName == null) {
            return null;
        }

        String normalized = buildingFileName.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.toLowerCase(Locale.ROOT).endsWith(".sk")) {
            normalized = normalized.substring(0, normalized.length() - 3).trim();
        }

        return normalized.isEmpty() ? null : normalized;
    }

    // ==================== SK文件缓存机制 ====================

    /**
     * 初始化SK文件缓存 - 在游戏启动时调用
     */
    public static void initSkFileCache(MinecraftServer server) {
        if (cacheInitialized) {
            LOGGER.info("[FileUtils] SK文件缓存已初始化，跳过");
            return;
        }
        reloadSkFileCache(server);
        cacheInitialized = true;
    }

    /**
     * 重新加载SK文件缓存 - 供/simukraft reload指令调用
     * 同时缓存SK文件信息和对应的JSON配置
     */
    public static void reloadSkFileCache(MinecraftServer server) {
        SK_FILE_CACHE.clear();
        BUILDING_CACHE.clear();
        
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            
            LOGGER.info("[FileUtils] 开始加载建筑缓存，世界目录: {}", worldDir);
            
            // 加载商业建筑SK文件
            Path commercialDir = simukraftDir.resolve(COMMERCIAL_DIR);
            if (Files.exists(commercialDir)) {
                LOGGER.info("[FileUtils] 加载商业建筑缓存，目录: {}", commercialDir);
                loadBuildingCacheFromDirectory(commercialDir, "commercial");
            } else {
                LOGGER.warn("[FileUtils] 商业建筑目录不存在: {}", commercialDir);
            }
            
            // 加载工业建筑SK文件
            Path industrialDir = simukraftDir.resolve(INDUSTRIAL_DIR);
            if (Files.exists(industrialDir)) {
                LOGGER.info("[FileUtils] 加载工业建筑缓存，目录: {}", industrialDir);
                loadBuildingCacheFromDirectory(industrialDir, "industrial");
            } else {
                LOGGER.warn("[FileUtils] 工业建筑目录不存在: {}", industrialDir);
            }
            
            LOGGER.info("[FileUtils] 建筑缓存已重新加载，共缓存 {} 个建筑", BUILDING_CACHE.size());
        } catch (Exception e) {
            LOGGER.error("[FileUtils] 重新加载建筑缓存失败", e);
        }
    }

    /**
     * 从目录加载所有建筑到缓存（同时加载SK和JSON）
     */
    private static void loadBuildingCacheFromDirectory(Path directory, String type) {
        int loadedCount = 0;
        try (var stream = Files.newDirectoryStream(directory, "*.sk")) {
            for (Path filePath : stream) {
                String fileName = filePath.getFileName().toString();
                // 解析文件名中的坐标 (x_y_z.sk)
                String[] parts = fileName.replace(".sk", "").split("_");
                if (parts.length == 3) {
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int y = Integer.parseInt(parts[1]);
                        int z = Integer.parseInt(parts[2]);
                        String cacheKey = type + ":" + x + "," + y + "," + z;
                        
                        // 读取SK文件完整信息
                        BuildingCacheEntry entry = readBuildingInfoFromSkFile(filePath, cacheKey);
                        if (entry != null) {
                            // 同时加载JSON配置
                            JsonObject jsonConfig = loadJsonConfig(entry.getBuildingFileName(), type);
                            if (jsonConfig != null) {
                                entry.setJsonConfig(jsonConfig);
                            }
                            
                            // 存入新缓存
                            BUILDING_CACHE.put(cacheKey, entry);
                            
                            // 兼容旧缓存
                            SK_FILE_CACHE.put(cacheKey, entry.getBuildingFileName());
                            
                            loadedCount++;
                            LOGGER.debug("[FileUtils] 已缓存建筑 [{}] => {}", cacheKey, entry.getBuildingFileName());
                        }
                    } catch (NumberFormatException e) {
                        LOGGER.warn("[FileUtils] 文件名格式不正确，跳过: {}", fileName);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[FileUtils] 加载{}目录建筑缓存失败: {}", type, directory, e);
        }
        LOGGER.info("[FileUtils] 从{}目录加载了 {} 个建筑缓存", type, loadedCount);
    }

    /**
     * 从SK文件读取完整的建筑信息
     */
    private static BuildingCacheEntry readBuildingInfoFromSkFile(Path skFile, String cacheKey) {
        String buildingFileName = null;
        String jobType = null;
        String selectedRecipe = null;
        String cityId = null;
        
        try (BufferedReader reader = Files.newBufferedReader(skFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("building_file_name:")) {
                    buildingFileName = normalizeBuildingFileName(line.substring("building_file_name:".length()).trim());
                } else if (line.startsWith("job_type:")) {
                    jobType = line.substring("job_type:".length()).trim();
                } else if (line.startsWith("selected_recipe:")) {
                    selectedRecipe = line.substring("selected_recipe:".length()).trim();
                } else if (line.startsWith("cityid:")) {
                    cityId = line.substring("cityid:".length()).trim();
                }
            }
        } catch (Exception e) {
            LOGGER.error("[FileUtils] 读取SK文件失败: {}", skFile, e);
            return null;
        }
        
        if (buildingFileName == null) {
            LOGGER.warn("[FileUtils] SK文件缺少building_file_name: {}", skFile);
            return null;
        }
        
        return new BuildingCacheEntry(buildingFileName, jobType, selectedRecipe, skFile, cityId);
    }

    /**
     * 加载JSON配置文件
     */
    private static JsonObject loadJsonConfig(String buildingFileName, String type) {
        try {
            // 确定JSON文件路径
            String category = type.equals("industrial") ? "industry" : 
                             type.equals("commercial") ? "commercial" : "other";
            
            // 尝试多个可能的路径
            Path[] possiblePaths = {
                Paths.get("simukraftbuilding", category, buildingFileName + ".json"),
                Paths.get("simukraftbuilding", category, buildingFileName.toLowerCase() + ".json"),
                Paths.get("simukraftbuilding", category, buildingFileName.toUpperCase() + ".json"),
            };
            
            for (Path jsonPath : possiblePaths) {
                if (Files.exists(jsonPath)) {
                    try (BufferedReader reader = Files.newBufferedReader(jsonPath)) {
                        JsonObject config = GSON.fromJson(reader, JsonObject.class);
                        LOGGER.debug("[FileUtils] 已加载JSON配置: {}", jsonPath);
                        return config;
                    }
                }
            }
            
            LOGGER.warn("[FileUtils] 未找到JSON配置文件: {} (类型: {})", buildingFileName, type);
            return null;
        } catch (Exception e) {
            LOGGER.error("[FileUtils] 加载JSON配置失败: {} (类型: {})", buildingFileName, type, e);
            return null;
        }
    }

    // ==================== 新的建筑缓存读取方法 ====================
    
    /**
     * 从缓存获取完整的建筑配置信息（包含SK和JSON）
     * 如果缓存为null，会尝试直接从硬盘读取作为备用方案
     * @param type 建筑类型: "commercial" 或 "industrial"
     * @param pos 建筑位置
     * @return BuildingCacheEntry 包含完整的建筑配置，如果不存在返回null
     */
    public static BuildingCacheEntry getBuildingCacheEntry(String type, BlockPos pos) {
        if (!cacheInitialized) {
            LOGGER.warn("[FileUtils] 缓存未初始化，无法获取建筑配置");
            return null;
        }
        String cacheKey = type + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        BuildingCacheEntry entry = BUILDING_CACHE.get(cacheKey);
        
        // 缓存未命中，尝试直接从硬盘读取
        if (entry == null) {
            entry = loadBuildingFromDisk(type, pos, cacheKey);
        }
        
        LOGGER.debug("[FileUtils] 读取建筑缓存 [{}] => {}", cacheKey, entry);
        return entry;
    }
    
    /**
     * 从硬盘直接读取建筑信息（备用方案）
     * @param type 建筑类型: "commercial" 或 "industrial"
     * @param pos 建筑位置
     * @param cacheKey 缓存键
     * @return BuildingCacheEntry 如果文件不存在返回null
     */
    private static BuildingCacheEntry loadBuildingFromDisk(String type, BlockPos pos, String cacheKey) {
        try {
            // 获取世界目录
            Path worldDir = null;
            for (Map.Entry<String, BuildingCacheEntry> e : BUILDING_CACHE.entrySet()) {
                if (e.getValue() != null && e.getValue().getSkFilePath() != null) {
                    worldDir = e.getValue().getSkFilePath().getParent().getParent().getParent();
                    break;
                }
            }
            
            if (worldDir == null) {
                return null;
            }
            
            // 构建SK文件路径
            String dirName = type.equals("industrial") ? INDUSTRIAL_DIR : COMMERCIAL_DIR;
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = worldDir.resolve(MODE_DIR).resolve(dirName).resolve(fileName);
            
            if (!Files.exists(skFile)) {
                return null;
            }
            
            // 读取SK文件
            BuildingCacheEntry entry = readBuildingInfoFromSkFile(skFile, cacheKey);
            if (entry != null) {
                LOGGER.info("[FileUtils] 缓存未命中，从硬盘读取建筑配置: {} => {}", cacheKey, entry.getBuildingFileName());
                // 同时更新缓存，避免下次再次读取硬盘
                BUILDING_CACHE.put(cacheKey, entry);
            }
            return entry;
        } catch (Exception e) {
            LOGGER.error("[FileUtils] 从硬盘读取建筑配置失败: {} at {}", type, pos, e);
            return null;
        }
    }
    
    /**
     * 从缓存获取商业建筑的完整配置
     */
    public static BuildingCacheEntry getCommercialBuildingCacheEntry(BlockPos pos) {
        return getBuildingCacheEntry("commercial", pos);
    }
    
    /**
     * 从缓存获取工业建筑的完整配置
     */
    public static BuildingCacheEntry getIndustrialBuildingCacheEntry(BlockPos pos) {
        return getBuildingCacheEntry("industrial", pos);
    }
    
    /**
     * 从缓存获取商业建筑的building_file_name (兼容旧方法)
     * 如果缓存为null，会尝试直接从硬盘读取作为备用方案
     */
    public static String readCommercialBuildingFileNameCached(MinecraftServer server, BlockPos pos) {
        if (!cacheInitialized) {
            LOGGER.warn("[FileUtils] 缓存未初始化，正在初始化...");
            initSkFileCache(server);
        }
        String cacheKey = "commercial:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        
        // 优先从新缓存获取
        BuildingCacheEntry entry = BUILDING_CACHE.get(cacheKey);
        if (entry != null) {
            LOGGER.debug("[FileUtils] 读取商业建筑缓存 [{}] => {} (来自新缓存)", cacheKey, entry.getBuildingFileName());
            return entry.getBuildingFileName();
        }
        
        // 兼容旧缓存
        String result = SK_FILE_CACHE.get(cacheKey);
        if (result != null) {
            LOGGER.debug("[FileUtils] 读取商业建筑缓存 [{}] => {} (来自旧缓存)", cacheKey, result);
            return result;
        }
        
        // 缓存未命中，尝试从硬盘读取
        entry = loadBuildingFromDisk("commercial", pos, cacheKey);
        if (entry != null) {
            return entry.getBuildingFileName();
        }
        
        return null;
    }

    /**
     * 从缓存获取工业建筑的building_file_name (兼容旧方法)
     * 如果缓存为null，会尝试直接从硬盘读取作为备用方案
     */
    public static String readIndustrialBuildingFileNameCached(MinecraftServer server, BlockPos pos) {
        if (!cacheInitialized) {
            LOGGER.warn("[FileUtils] 缓存未初始化，正在初始化...");
            initSkFileCache(server);
        }
        String cacheKey = "industrial:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        
        // 优先从新缓存获取
        BuildingCacheEntry entry = BUILDING_CACHE.get(cacheKey);
        if (entry != null) {
            return entry.getBuildingFileName();
        }
        
        // 兼容旧缓存
        String result = SK_FILE_CACHE.get(cacheKey);
        if (result != null) {
            return result;
        }
        
        // 缓存未命中，尝试从硬盘读取
        entry = loadBuildingFromDisk("industrial", pos, cacheKey);
        if (entry != null) {
            return entry.getBuildingFileName();
        }
        
        return null;
    }

    /**
     * 更新或添加缓存条目（当新建建筑时调用）
     * 同时更新新旧缓存
     */
    public static void updateSkFileCache(String type, BlockPos pos, String buildingFileName) {
        String cacheKey = type + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        if (buildingFileName != null) {
            SK_FILE_CACHE.put(cacheKey, buildingFileName);
            
            // 同时更新新缓存 - 创建简化版的 entry
            BuildingCacheEntry entry = new BuildingCacheEntry(buildingFileName, null, null, null, null);
            // 尝试加载JSON配置
            JsonObject jsonConfig = loadJsonConfig(buildingFileName, type);
            if (jsonConfig != null) {
                entry.setJsonConfig(jsonConfig);
            }
            BUILDING_CACHE.put(cacheKey, entry);
            
            LOGGER.debug("[FileUtils] 更新缓存 [{}] => {}", cacheKey, buildingFileName);
        } else {
            SK_FILE_CACHE.remove(cacheKey);
            BUILDING_CACHE.remove(cacheKey);
            LOGGER.debug("[FileUtils] 移除缓存 [{}]", cacheKey);
        }
    }

    /**
     * 从缓存移除条目（当删除建筑时调用）
     * 同时移除新旧缓存
     */
    public static void removeFromSkFileCache(String type, BlockPos pos) {
        String cacheKey = type + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        SK_FILE_CACHE.remove(cacheKey);
        BUILDING_CACHE.remove(cacheKey);
        LOGGER.debug("[FileUtils] 从缓存移除建筑 [{}]", cacheKey);
    }
    
    /**
     * 获取缓存统计信息（用于调试）
     */
    public static String getCacheStats() {
        return String.format("BuildingCache: %d entries, LegacyCache: %d entries", 
            BUILDING_CACHE.size(), SK_FILE_CACHE.size());
    }
    
    /**
     * 检查指定位置的建筑是否在缓存中
     */
    public static boolean isBuildingCached(String type, BlockPos pos) {
        String cacheKey = type + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        return BUILDING_CACHE.containsKey(cacheKey) || SK_FILE_CACHE.containsKey(cacheKey);
    }
}
