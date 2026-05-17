package com.xiaoliang.simukraft.building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Locale;
import java.util.Objects;

/**
 * 控制盒数据管理器
 * 统一管理所有类型控制盒的sk文件读写
 */
public class ControlBoxDataManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final @Nonnull LevelResource WORLD_ROOT = Objects.requireNonNull(LevelResource.ROOT);
    
    private static final String RESIDENCE_DIR = "residence";
    private static final String COMMERCIAL_DIR = "commercial";
    private static final String INDUSTRIAL_DIR = "industrial";
    private static final String OTHER_DIR = "other";
    
    /**
     * 控制盒数据类
     */
    public static class ControlBoxData {
        public BlockPos position;
        public String type;
        public String world;
        public UUID residentUuid;  // 居民UUID
        public String buildingName; // 建筑中文名称（从sk文件读取）
        public String buildingFileName; // 建筑文件名（如 "mill"）
        public UUID cityId; // 城市ID
        public String selectedRecipe; // 当前选择的配方ID（工业建筑使用）
        public boolean homeTeleportToAbove; // 住宅NPC回家传送到控制盒上方
        
        public ControlBoxData(BlockPos position, String type, String world) {
            this.position = position;
            this.type = type;
            this.world = world;
            this.homeTeleportToAbove = true;
        }
        
        public ControlBoxData(BlockPos position, String type, String world, UUID residentUuid, UUID cityId) {
            this(position, type, world);
            this.residentUuid = residentUuid;
            this.cityId = cityId;
        }
    }
    
    /**
     * 从建筑的sk文件中读取建筑名称
     * 优先从 simukraftbuilding 文件夹读取，如果不存在则从 JAR 资源读取
     * @param buildingFileName 建筑文件名（如 "u1"）
     * @param category 建筑类别（如 "residential"）
     * @return 建筑中文名称，如果读取失败则返回文件名
     */
    public static String getBuildingNameFromSkFile(String buildingFileName, String category) {
        if (buildingFileName == null || buildingFileName.isEmpty()) {
            return "未知建筑";
        }
        
        // 首先尝试从 simukraftbuilding 文件夹读取（用户导入的文件）
        try {
            String userFilePath = String.format("simukraftbuilding/%s/%s.sk", category, buildingFileName.toLowerCase());
            File userFile = new File(userFilePath);
            if (userFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(userFile), Charset.forName("UTF-8")))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("name:")) {
                            String name = line.substring(5).trim();
                            LOGGER.debug("[ControlBoxDataManager] 从用户sk文件读取建筑名称: {} -> {}", buildingFileName, name);
                            return name;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ControlBoxDataManager] 从用户sk文件读取失败: {}, {}", buildingFileName, e.getMessage());
        }
        
        // 如果用户文件不存在或读取失败，尝试从 JAR 资源读取
        try {
            // 构建sk文件路径: assets/simukraft/building/{category}/{filename}.sk
            String resourcePath = String.format("assets/simukraft/building/%s/%s.sk", category, buildingFileName.toLowerCase());
            
            // 使用ClassLoader读取资源文件
            InputStream is = ControlBoxDataManager.class.getClassLoader().getResourceAsStream(resourcePath);
            if (is == null) {
                LOGGER.error("[ControlBoxDataManager] 找不到sk文件: {}", resourcePath);
                return buildingFileName;
            }
            
            // 读取文件内容，查找name字段
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("name:")) {
                        String name = line.substring(5).trim();
                        LOGGER.debug("[ControlBoxDataManager] 从资源sk文件读取建筑名称: {} -> {}", buildingFileName, name);
                        return name;
                    }
                }
            }
            
            // 如果没有找到name字段，返回文件名
            return buildingFileName;
            
        } catch (Exception e) {
            LOGGER.error("[ControlBoxDataManager] 读取sk文件失败: {}, {}", buildingFileName, e.getMessage());
            return buildingFileName;
        }
    }

    /**
     * 从建筑的sk文件中读取建筑尺寸
     * @param buildingFileName 建筑文件名
     * @param category 建筑类别
     * @return 建筑尺寸字符串（如 "10x10x10"），读取失败返回null
     */
    public static String getBuildingSizeFromSkFile(String buildingFileName, String category) {
        String size = readBuildingSkField(buildingFileName, category, "size:");
        if (size != null) {
            LOGGER.debug("[ControlBoxDataManager] 从sk文件读取建筑尺寸: {} -> {}", buildingFileName, size);
        }
        return size;
    }

    /**
     * 从建筑sk文件读取住宅回家传送点的NBT坐标。
     * 字段为空或不存在时返回null，让旧建筑继续使用控制盒附近传送逻辑。
     */
    public static BlockPos getHomeTeleportNbtPosFromSkFile(String buildingFileName, String category) {
        String value = readBuildingSkField(buildingFileName, category, "home_teleport:");
        if (value == null || value.isBlank()) {
            value = readBuildingSkField(buildingFileName, category, "teleport:");
        }
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            String[] parts = value.replace("，", ",").split(",");
            if (parts.length != 3) {
                LOGGER.warn("[ControlBoxDataManager] sk传送点格式错误: {} -> {}", buildingFileName, value);
                return null;
            }
            return new BlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            );
        } catch (NumberFormatException e) {
            LOGGER.warn("[ControlBoxDataManager] sk传送点解析失败: {} -> {}", buildingFileName, value);
            return null;
        }
    }

    private static String readBuildingSkField(String buildingFileName, String category, String fieldPrefix) {
        if (buildingFileName == null || buildingFileName.isEmpty() || fieldPrefix == null || fieldPrefix.isEmpty()) {
            return null;
        }

        try {
            String userFilePath = String.format("simukraftbuilding/%s/%s.sk", category, buildingFileName.toLowerCase());
            File userFile = new File(userFilePath);
            if (userFile.exists()) {
                String value = readFieldFromSkFile(userFile, fieldPrefix);
                if (value != null) {
                    return value;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[ControlBoxDataManager] 从用户sk文件读取字段失败: {}, {}, {}", buildingFileName, fieldPrefix, e.getMessage());
        }

        try {
            String resourcePath = String.format("assets/simukraft/building/%s/%s.sk", category, buildingFileName.toLowerCase());
            InputStream is = ControlBoxDataManager.class.getClassLoader().getResourceAsStream(resourcePath);
            if (is == null) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")))) {
                return readFieldFromReader(reader, fieldPrefix);
            }
        } catch (Exception e) {
            LOGGER.debug("[ControlBoxDataManager] 从资源sk文件读取字段失败: {}, {}, {}", buildingFileName, fieldPrefix, e.getMessage());
        }

        return null;
    }

    private static String readFieldFromSkFile(File file, String fieldPrefix) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")))) {
            return readFieldFromReader(reader, fieldPrefix);
        }
    }

    private static String readFieldFromReader(BufferedReader reader, String fieldPrefix) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith(fieldPrefix)) {
                return line.substring(fieldPrefix.length()).trim();
            }
        }
        return null;
    }

    /**
     * 从建筑的sk文件中读取租金/价格
     * @param buildingFileName 建筑文件名
     * @param category 建筑类别
     * @return 租金/价格，读取失败则返回0.0
     */
    public static double getRentFromSkFile(String buildingFileName, String category) {
        if (buildingFileName == null || buildingFileName.isEmpty()) {
            return 0.0;
        }
        
        LOGGER.debug("[ControlBoxDataManager] getRentFromSkFile called with: buildingFileName={}, category={}", buildingFileName, category);
        
        // 首先尝试从 simukraftbuilding 文件夹读取（用户导入的文件）
        try {
            String userFilePath = String.format("simukraftbuilding/%s/%s.sk", category, buildingFileName.toLowerCase());
            File userFile = new File(userFilePath);
            LOGGER.debug("[ControlBoxDataManager] 尝试读取用户文件: {}, 存在: {}", userFilePath, userFile.exists());
            
            if (userFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(userFile), Charset.forName("UTF-8")))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("amount:")) {
                            String amountStr = line.substring(7).trim();
                            // 移除"元"后缀
                            amountStr = amountStr.replace("元", "").trim();
                            try {
                                double amount = Double.parseDouble(amountStr);
                                LOGGER.debug("[ControlBoxDataManager] 从用户sk文件读取租金: {} -> {}元", buildingFileName, amount);
                                return amount;
                            } catch (NumberFormatException e) {
                                LOGGER.error("[ControlBoxDataManager] 解析金额失败: '{}', 错误: {}", amountStr, e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ControlBoxDataManager] 从用户sk文件读取租金失败: {}, {}", buildingFileName, e.getMessage());
        }
        
        // 如果用户文件不存在或读取失败，尝试从 JAR 资源读取
        try {
            // 构建sk文件路径: assets/simukraft/building/{category}/{filename}.sk
            String resourcePath = String.format("assets/simukraft/building/%s/%s.sk", category, buildingFileName.toLowerCase());
            LOGGER.debug("[ControlBoxDataManager] 尝试读取资源文件: {}", resourcePath);
            
            // 使用ClassLoader读取资源文件
            InputStream is = ControlBoxDataManager.class.getClassLoader().getResourceAsStream(resourcePath);
            if (is == null) {
                LOGGER.error("[ControlBoxDataManager] 找不到sk文件: {}，将尝试BuildingDataManager", resourcePath);
            } else {
                LOGGER.debug("[ControlBoxDataManager] 成功打开资源文件: {}", resourcePath);
                
                // 读取文件内容，查找amount字段
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String originalLine = line;
                        line = line.trim();
                        LOGGER.debug("[ControlBoxDataManager] 读取行: {} -> 修剪后: {}", originalLine, line);
                        if (line.startsWith("amount:")) {
                            String amountStr = line.substring(7).trim();
                            LOGGER.debug("[ControlBoxDataManager] 找到amount字段，原始值: {}", amountStr);
                            // 移除"元"后缀
                            amountStr = amountStr.replace("元", "").trim();
                            LOGGER.debug("[ControlBoxDataManager] 移除元后: {}", amountStr);
                            try {
                                double amount = Double.parseDouble(amountStr);
                                LOGGER.debug("[ControlBoxDataManager] 从资源sk文件读取租金: {} -> {}元", buildingFileName, amount);
                                return amount;
                            } catch (NumberFormatException e) {
                                LOGGER.error("[ControlBoxDataManager] 解析金额失败: '{}', 错误: {}", amountStr, e.getMessage());
                                // 解析失败，继续尝试BuildingDataManager
                            }
                        }
                    }
                    LOGGER.debug("[ControlBoxDataManager] 未找到amount字段，尝试从BuildingDataManager读取");
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ControlBoxDataManager] 读取资源租金失败: {}, {}", buildingFileName, e.getMessage());
        }
        
        // 如果以上方法都失败，尝试从BuildingDataManager读取
        try {
            // BuildingDataManager使用的category与ControlBoxDataManager一致：residential/commercial/industry
            com.xiaoliang.simukraft.utils.BuildingDataManager.BuildingInfo buildingInfo = 
                com.xiaoliang.simukraft.utils.BuildingDataManager.getBuildingInfo(category, buildingFileName + ".sk");
            
            if (buildingInfo != null) {
                String amountStr = buildingInfo.getAmount();
                if (amountStr != null && !amountStr.isEmpty()) {
                    // 移除"元"后缀
                    amountStr = amountStr.replace("元", "").trim();
                    try {
                        double amount = Double.parseDouble(amountStr);
                        LOGGER.debug("[ControlBoxDataManager] 从BuildingDataManager读取租金: {} -> {}元", buildingFileName, amount);
                        return amount;
                    } catch (NumberFormatException e) {
                        LOGGER.error("[ControlBoxDataManager] 解析BuildingDataManager金额失败: '{}', 错误: {}", amountStr, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ControlBoxDataManager] 从BuildingDataManager读取租金失败: " + buildingFileName + ", " + e.getMessage());
        }
        
        System.err.println("[ControlBoxDataManager] 无法读取租金，返回0: " + buildingFileName);
        return 0;
    }
    
    /**
     * 写入住宅控制盒数据
     * @param server 服务器实例
     * @param pos 控制盒位置
     * @param buildingFileName 建筑文件名（如 "u1"）
     * @param residentUuid 居民UUID（可为null）
     * @param cityId 城市ID（可为null）
     */
    public static void writeResidentialControlBox(MinecraftServer server, BlockPos pos, String buildingFileName, UUID residentUuid, UUID cityId) {
        String buildingDisplayName = getBuildingNameFromSkFile(buildingFileName, "residential");
        double originalRent = getRentFromSkFile(buildingFileName, "residential");
        double rent = originalRent / 2.0;
        writeResidentialControlBoxWithRent(server, pos, "residential_control_box", buildingDisplayName, buildingFileName,
                RESIDENCE_DIR, residentUuid, cityId, rent, true);
    }
    
    /**
     * 写入住宅控制盒数据（兼容旧版本，不包含cityId）
     */
    public static void writeResidentialControlBox(MinecraftServer server, BlockPos pos, String buildingFileName, UUID residentUuid) {
        writeResidentialControlBox(server, pos, buildingFileName, residentUuid, null);
    }
    
    /**
     * 写入商业控制盒数据（包含租金）
     */
    public static void writeCommercialControlBox(MinecraftServer server, BlockPos pos, String buildingFileName, UUID residentUuid, UUID cityId) {
        String buildingDisplayName = getBuildingNameFromSkFile(buildingFileName, "commercial");
        // 从建筑配置文件读取租金（amount字段）
        double rent = getRentFromSkFile(buildingFileName, "commercial");
        writeCommercialControlBoxWithRent(server, pos, "commercial_control_box", buildingDisplayName, buildingFileName, COMMERCIAL_DIR, residentUuid, cityId, rent);
    }
    
    /**
     * 写入工业控制盒数据
     */
    public static void writeIndustrialControlBox(MinecraftServer server, BlockPos pos, String buildingFileName, UUID residentUuid, UUID cityId) {
        String buildingDisplayName = getBuildingNameFromSkFile(buildingFileName, "industry");
        writeControlBox(server, pos, "industrial_control_box", buildingDisplayName, buildingFileName, INDUSTRIAL_DIR, residentUuid, cityId);
    }
    
    /**
     * 写入其他类型控制盒数据
     */
    public static void writeOtherControlBox(MinecraftServer server, BlockPos pos, String buildingName, UUID residentUuid, UUID cityId) {
        writeOtherControlBox(server, pos, buildingName, null, residentUuid, cityId);
    }

    /**
     * 写入其他类型控制盒数据
     */
    public static void writeOtherControlBox(MinecraftServer server, BlockPos pos, String buildingName,
                                            String buildingFileName, UUID residentUuid, UUID cityId) {
        writeControlBox(server, pos, "other_control_box", buildingName, buildingFileName, OTHER_DIR, residentUuid, cityId);
    }
    
    /**
     * 写入住宅控制盒数据（包含租金）
     */
    private static void writeResidentialControlBoxWithRent(MinecraftServer server, BlockPos pos, String type,
                                       String buildingName, String buildingFileName, String subDir, UUID residentUuid,
                                       UUID cityId, double rent, boolean homeTeleportToAbove) {
        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);
            Files.createDirectories(controlBoxDir);
            
            // 文件名：x_y_z.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = controlBoxDir.resolve(fileName);
            
            // 使用UTF-8编码写入文件
            try (BufferedWriter writer = Files.newBufferedWriter(skFile, Charset.forName("UTF-8"))) {
                writer.write("position: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "\n");
                writer.write("type: " + type + "\n");
                writer.write("world: " + worldPath.getFileName().toString() + "\n");
                writer.write("building_name: " + buildingName + "\n");
                if (buildingFileName != null && !buildingFileName.isEmpty()) {
                    writer.write("building_file_name: " + buildingFileName + "\n");
                }
                writer.write(String.format(Locale.US, "rent:%.2f元\n", rent));
                if (residentUuid != null) {
                    writer.write("resident_uuid: " + residentUuid.toString() + "\n");
                }
                if (cityId != null) {
                    writer.write("cityid: " + cityId.toString() + "\n");
                }
                writer.write("home_teleport_to_above: " + homeTeleportToAbove + "\n");
            }
            
            LOGGER.info("[ControlBoxDataManager] 写入住宅控制盒数据: {}, 建筑: {}, 租金: {}元", 
                skFile.toAbsolutePath(), buildingName, String.format(Locale.US, "%.2f", rent));
            
        } catch (IOException e) {
            LOGGER.error("[ControlBoxDataManager] 写入控制盒数据失败: {}", e.getMessage());
        }
    }
    
    /**
     * 写入商业控制盒数据（包含租金/amount字段）
     */
    private static void writeCommercialControlBoxWithRent(MinecraftServer server, BlockPos pos, String type, 
                                       String buildingName, String buildingFileName, String subDir, UUID residentUuid, UUID cityId, double rent) {
        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);
            Files.createDirectories(controlBoxDir);
            
            // 文件名：x_y_z.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = controlBoxDir.resolve(fileName);
            
            // 使用UTF-8编码写入文件
            try (BufferedWriter writer = Files.newBufferedWriter(skFile, Charset.forName("UTF-8"))) {
                writer.write("position: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "\n");
                writer.write("type: " + type + "\n");
                writer.write("world: " + worldPath.getFileName().toString() + "\n");
                writer.write("building_name: " + buildingName + "\n");
                if (buildingFileName != null && !buildingFileName.isEmpty()) {
                    writer.write("building_file_name: " + buildingFileName + "\n");
                }
                // 写入租金（amount字段，用于企业税计算）
                writer.write(String.format(Locale.US, "amount: %.2f元\n", rent));
                if (residentUuid != null) {
                    writer.write("resident_uuid: " + residentUuid.toString() + "\n");
                }
                if (cityId != null) {
                    writer.write("cityid: " + cityId.toString() + "\n");
                }
            }
            
            LOGGER.info("[ControlBoxDataManager] 写入商业控制盒数据: {}, 建筑: {}, 租金/amount: {}元", 
                skFile.toAbsolutePath(), buildingName, String.format(Locale.US, "%.2f", rent));
            
        } catch (IOException e) {
            LOGGER.error("[ControlBoxDataManager] 写入商业控制盒数据失败: {}", e.getMessage());
        }
    }
    
    /**
     * 通用写入控制盒数据方法
     */
    private static void writeControlBox(MinecraftServer server, BlockPos pos, String type,
                                       String buildingName, String buildingFileName, String subDir, UUID residentUuid,
                                       UUID cityId) {
        writeControlBox(server, pos, type, buildingName, buildingFileName, subDir, residentUuid, cityId, null);
    }

    /**
     * 通用写入控制盒数据方法
     */
    private static void writeControlBox(MinecraftServer server, BlockPos pos, String type,
                                       String buildingName, String buildingFileName, String subDir, UUID residentUuid,
                                       UUID cityId, Boolean homeTeleportToAbove) {
        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);
            Files.createDirectories(controlBoxDir);
            
            // 文件名：x_y_z.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            File skFile = controlBoxDir.resolve(fileName).toFile();
            
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(skFile), java.nio.charset.StandardCharsets.UTF_8))) {
                writer.write("position: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "\n");
                writer.write("type: " + type + "\n");
                writer.write("world: " + worldPath.getFileName().toString() + "\n");
                writer.write("building_name: " + buildingName + "\n");
                if (buildingFileName != null && !buildingFileName.isEmpty()) {
                    writer.write("building_file_name: " + buildingFileName + "\n");
                }
                if (residentUuid != null) {
                    writer.write("resident_uuid: " + residentUuid.toString() + "\n");
                }
                if (cityId != null) {
                    writer.write("cityid: " + cityId.toString() + "\n");
                }
                if ("residence".equals(subDir) && homeTeleportToAbove != null) {
                    writer.write("home_teleport_to_above: " + homeTeleportToAbove + "\n");
                }
            }
            
            LOGGER.info("[ControlBoxDataManager] 写入控制盒数据: {}, 建筑: {}, 文件名: {}", 
                skFile.getAbsolutePath(), buildingName, buildingFileName);
            
        } catch (IOException e) {
            LOGGER.error("[ControlBoxDataManager] 写入控制盒数据失败: {}", e.getMessage());
        }
    }
    
    /**
     * 读取控制盒数据
     * @param server 服务器实例
     * @param pos 控制盒位置
     * @param type 控制盒类型（residential/commercial/industrial/other）
     * @return 控制盒数据，如果不存在返回null
     */
    public static ControlBoxData readControlBox(MinecraftServer server, BlockPos pos, String type) {
        String subDir = getSubDirByType(type);
        if (subDir == null) return null;
        
        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            File skFile = controlBoxDir.resolve(fileName).toFile();
            
            if (!skFile.exists()) {
                return null;
            }
            
            return parseControlBoxFile(skFile, pos);
            
        } catch (Exception e) {
            System.err.println("[ControlBoxDataManager] 读取控制盒数据失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 删除控制盒数据
     * @param server 服务器实例
     * @param pos 控制盒位置
     * @param type 控制盒类型
     */
    public static void deleteControlBox(MinecraftServer server, BlockPos pos, String type) {
        String subDir = getSubDirByType(type);
        if (subDir == null) return;

        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = controlBoxDir.resolve(fileName);

            LOGGER.debug("[ControlBoxDataManager] 尝试删除控制盒数据: {}", skFile.toAbsolutePath());

            if (Files.exists(skFile)) {
                Files.delete(skFile);
                LOGGER.info("[ControlBoxDataManager] 成功删除控制盒数据: {}", skFile.toAbsolutePath());
            } else {
                LOGGER.debug("[ControlBoxDataManager] 控制盒数据文件不存在: {}", skFile.toAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.error("[ControlBoxDataManager] 删除控制盒数据失败: {}", e.getMessage());
        }
    }
    
    /**
     * 更新居民UUID
     * @param server 服务器实例
     * @param pos 控制盒位置
     * @param type 控制盒类型
     * @param residentUuid 新的居民UUID
     */
    public static void updateResidentUuid(MinecraftServer server, BlockPos pos, String type, UUID residentUuid) {
        ControlBoxData data = readControlBox(server, pos, type);
        if (data != null) {
            data.residentUuid = residentUuid;
            writeControlBox(server, pos, data.type, data.buildingName, data.buildingFileName, getSubDirByType(type), residentUuid, data.cityId, data.homeTeleportToAbove);
        }
    }
    
    /**
     * 获取所有控制盒数据
     * @param server 服务器实例
     * @param type 控制盒类型
     * @return 控制盒数据列表
     */
    public static List<ControlBoxData> getAllControlBoxes(MinecraftServer server, String type) {
        List<ControlBoxData> result = new ArrayList<>();
        String subDir = getSubDirByType(type);
        if (subDir == null) {
            LOGGER.error("[ControlBoxDataManager] 未知的控制盒类型: {}", type);
            return result;
        }
        
        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);
            
            LOGGER.debug("[ControlBoxDataManager] 查找控制盒目录: {}", controlBoxDir.toAbsolutePath());
            
            if (!Files.exists(controlBoxDir)) {
                LOGGER.debug("[ControlBoxDataManager] 控制盒目录不存在: {}", controlBoxDir);
                return result;
            }
            
            File[] files = controlBoxDir.toFile().listFiles((dir, name) -> name.endsWith(".sk"));
            LOGGER.debug("[ControlBoxDataManager] 在 {} 中找到 {} 个.sk文件", subDir, (files != null ? files.length : 0));
            
            if (files != null) {
                for (File file : files) {
                    LOGGER.debug("[ControlBoxDataManager] 解析文件: {}", file.getName());
                    // 从文件名解析位置
                    String fileName = file.getName().replace(".sk", "");
                    String[] parts = fileName.split("_");
                    if (parts.length == 3) {
                        try {
                            BlockPos pos = new BlockPos(
                                Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2])
                            );
                            ControlBoxData data = parseControlBoxFile(file, pos);
                            if (data != null) {
                                LOGGER.debug("[ControlBoxDataManager] 成功解析: {}, buildingFileName={}", 
                                    file.getName(), data.buildingFileName);
                                result.add(data);
                            } else {
                                LOGGER.error("[ControlBoxDataManager] 解析失败: {}", file.getName());
                            }
                        } catch (NumberFormatException e) {
                            LOGGER.error("[ControlBoxDataManager] 文件名格式错误: {}", file.getName());
                        }
                    } else {
                        LOGGER.error("[ControlBoxDataManager] 文件名格式不正确: {}", file.getName());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ControlBoxDataManager] 获取控制盒列表失败: {}", e.getMessage());
        }
        
        LOGGER.debug("[ControlBoxDataManager] 总共返回 {} 个控制盒数据", result.size());
        return result;
    }
    
    /**
     * 解析控制盒文件
     */
    private static ControlBoxData parseControlBoxFile(File file, BlockPos pos) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            ControlBoxData data = new ControlBoxData(pos, "", "");

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("type:")) {
                    data.type = line.substring(5).trim();
                } else if (line.startsWith("world:")) {
                    data.world = line.substring(6).trim();
                } else if (line.startsWith("resident_uuid:")) {
                    String uuidStr = line.substring(14).trim();
                    try {
                        data.residentUuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        // UUID格式错误，忽略
                    }
                } else if (line.startsWith("cityid:")) {
                    String uuidStr = line.substring(7).trim();
                    try {
                        data.cityId = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        // UUID格式错误，忽略
                    }
                } else if (line.startsWith("building_name:")) {
                    data.buildingName = line.substring(14).trim();
                } else if (line.startsWith("building_file_name:")) {
                    data.buildingFileName = line.substring(19).trim();
                } else if (line.startsWith("selected_recipe:")) {
                    data.selectedRecipe = line.substring(16).trim();
                } else if (line.startsWith("home_teleport_to_above:")) {
                    data.homeTeleportToAbove = Boolean.parseBoolean(line.substring(23).trim());
                }
            }

            return data;
        } catch (IOException e) {
            System.err.println("[ControlBoxDataManager] 解析控制盒文件失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 更新工业建筑控制盒的配方选择
     * @param server 服务器实例
     * @param pos 控制盒位置
     * @param recipeId 配方ID
     */
    public static void updateSelectedRecipe(MinecraftServer server, BlockPos pos, String recipeId) {
        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            Path industrialDir = worldPath.resolve("simukraft").resolve(INDUSTRIAL_DIR);
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            if (!Files.exists(skFile)) {
                System.err.println("[ControlBoxDataManager] 工业控制盒文件不存在: " + skFile.toAbsolutePath());
                return;
            }

            // 读取现有文件内容
            List<String> lines = Files.readAllLines(skFile, java.nio.charset.StandardCharsets.UTF_8);
            List<String> newLines = new ArrayList<>();
            boolean foundRecipe = false;

            for (String line : lines) {
                if (line.trim().startsWith("selected_recipe:")) {
                    // 更新现有配方行
                    newLines.add("selected_recipe: " + recipeId);
                    foundRecipe = true;
                } else {
                    newLines.add(line);
                }
            }

            // 如果没有找到配方行，添加一个新的
            if (!foundRecipe) {
                newLines.add("selected_recipe: " + recipeId);
            }

            // 写回文件
            Files.write(skFile, newLines, java.nio.charset.StandardCharsets.UTF_8);

            LOGGER.info("[ControlBoxDataManager] 更新配方选择: {}, 配方: {}", skFile.toAbsolutePath(), recipeId);

        } catch (Exception e) {
            LOGGER.error("[ControlBoxDataManager] 更新配方选择失败: {}", e.getMessage());
        }
    }

    /**
     * 获取住宅NPC回家时是否优先传送到控制盒上方。
     * 旧档没有该字段时默认返回 true，保持兼容当前行为。
     */
    public static boolean isResidentialHomeTeleportToAbove(MinecraftServer server, BlockPos pos) {
        ControlBoxData data = readControlBox(server, pos, "residential");
        return data == null || data.homeTeleportToAbove;
    }

    /**
     * 更新住宅控制盒的回家传送方向开关。
     */
    public static void updateResidentialHomeTeleportMode(MinecraftServer server, BlockPos pos, boolean homeTeleportToAbove) {
        ControlBoxData data = readControlBox(server, pos, "residential");
        if (data == null) {
            return;
        }
        data.homeTeleportToAbove = homeTeleportToAbove;
        writeControlBox(server, pos, data.type, data.buildingName, data.buildingFileName, RESIDENCE_DIR,
                data.residentUuid, data.cityId, data.homeTeleportToAbove);
    }

    /**
     * 获取工业建筑控制盒的配方选择
     * @param server 服务器实例
     * @param pos 控制盒位置
     * @return 配方ID，如果没有选择则返回null
     */
    public static String getSelectedRecipe(MinecraftServer server, BlockPos pos) {
        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            Path industrialDir = worldPath.resolve("simukraft").resolve(INDUSTRIAL_DIR);
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            if (!Files.exists(skFile)) {
                return null;
            }

            List<String> lines = Files.readAllLines(skFile, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("selected_recipe:")) {
                    return line.substring(16).trim();
                }
            }

        } catch (Exception e) {
            System.err.println("[ControlBoxDataManager] 读取配方选择失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 根据类型获取子目录
     */
    private static String getSubDirByType(String type) {
        return switch (type) {
            case "residential", "residential_control_box" -> RESIDENCE_DIR;
            case "commercial", "commercial_control_box" -> COMMERCIAL_DIR;
            case "industrial", "industrial_control_box" -> INDUSTRIAL_DIR;
            case "public", "public_control_box" -> "public";
            case "other", "other_control_box" -> OTHER_DIR;
            default -> null;
        };
    }
}
