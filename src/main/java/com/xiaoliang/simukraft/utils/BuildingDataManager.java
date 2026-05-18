package com.xiaoliang.simukraft.utils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class BuildingDataManager {
    private static final Logger LOGGER = LogManager.getLogger();
    
    public static class BuildingInfo {
        private String name;
        private String size;
        private String amount;
        private String author;
        private String description;
        private String category;
        private String fileName;
        private String litematicFileName;
        private String jobType;
        private List<String> tags; // 建筑标签列表
        
        public BuildingInfo(String name, String size, String amount, String author, String description, 
                          String category, String fileName, String litematicFileName, String jobType, List<String> tags) {
            this.name = name;
            this.size = size;
            this.amount = amount;
            this.author = author;
            this.description = description;
            this.category = category;
            this.fileName = fileName;
            this.litematicFileName = litematicFileName;
            this.jobType = jobType;
            this.tags = tags != null ? tags : new ArrayList<>();
        }
        
        // Getters
        public String getName() { return name; }
        public String getSize() { return size; }
        public String getAmount() { return amount; }
        public String getAuthor() { return author; }
        public List<String> getTags() { return tags; }
        public String getDescription() { return description; }
        public String getCategory() { return category; }
        public String getFileName() { return fileName; }
        public String getLitematicFileName() { return litematicFileName; }
        public String getJobType() { return jobType; }
        
        @Override
        public String toString() {
            return name;
        }
    }
    
    private static final String BUILDING_ROOT_PATH = "assets/simukraft/building";
    private static String SIMUKRAFT_BUILDING_FOLDER;
    private static final String[] CATEGORIES = {"residential", "commercial", "industry", "public", "other"};

    // 建筑信息缓存 - 缓存建筑类别数据避免重复文件读取
    private static final Map<String, List<BuildingInfo>> BUILDING_CATEGORY_CACHE = new HashMap<>();
    private static final Map<String, BuildingInfo> BUILDING_FILE_CACHE = new HashMap<>();
    private static volatile boolean cacheInitialized = false;

    private static Path resolveProjectRoot(Path workingDir) {
        Path current = workingDir.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("src/main/resources").resolve(BUILDING_ROOT_PATH))
                    || Files.exists(current.resolve("build.gradle"))
                    || Files.exists(current.resolve("gradlew.bat"))) {
                return current;
            }
            current = current.getParent();
        }
        return workingDir.toAbsolutePath().normalize();
    }

    // 静态初始化块，在类加载时检查和复制建筑文件
    static {
        // 确定建筑文件的存储位置
        determineBuildingFolderLocation();
        // 检查和复制建筑文件
        checkAndCopyBuildingFiles();
    }
    
    // 确定建筑文件的存储位置
    private static void determineBuildingFolderLocation() {
        try {
            SIMUKRAFT_BUILDING_FOLDER = "simukraftbuilding";

            Path workingDir = Paths.get("").toAbsolutePath().normalize();
            LOGGER.info("[BuildingDataManager] 建筑文件将存储在当前工作目录: {}", workingDir.resolve(SIMUKRAFT_BUILDING_FOLDER));
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 确定建筑文件存储位置失败: {}", e.getMessage());
            // 失败时使用默认值
            SIMUKRAFT_BUILDING_FOLDER = "simukraftbuilding";
        }
    }
    
    private static Path findExistingCategoryPath(Path workingDir, Path projectRoot, String category) {
        Path[] possiblePaths = {
            workingDir.resolve(SIMUKRAFT_BUILDING_FOLDER).resolve(category),
            projectRoot.resolve("src/main/resources").resolve(BUILDING_ROOT_PATH).resolve(category),
            workingDir.resolve("..").resolve("src").resolve("main").resolve("resources").resolve(BUILDING_ROOT_PATH).resolve(category),
            projectRoot.resolve("build/resources/main").resolve(BUILDING_ROOT_PATH).resolve(category),
            Paths.get(System.getProperty("user.dir"), "src", "main", "resources", BUILDING_ROOT_PATH, category)
        };

        for (Path path : possiblePaths) {
            if (Files.exists(path) && Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    public static List<BuildingInfo> getBuildingsByCategory(String category) {
        // menglannnn: 检查缓存 - 修复缓存为空的问题
        // 如果缓存已初始化且该类别的缓存存在且不为空，直接返回
        if (cacheInitialized) {
            List<BuildingInfo> cached = BUILDING_CATEGORY_CACHE.get(category);
            if (cached != null && !cached.isEmpty()) {
                return new ArrayList<>(cached);
            }
            // 如果缓存为空列表，清除该类别的缓存重新加载
            if (cached != null && cached.isEmpty()) {
                BUILDING_CATEGORY_CACHE.remove(category);
                LOGGER.warn("[BuildingDataManager] 类别 {} 的缓存为空，重新加载", category);
            }
        }

        List<BuildingInfo> buildings = new ArrayList<>();

        try {
            Path workingDir = Paths.get("").toAbsolutePath();
            Path projectRoot = resolveProjectRoot(workingDir);
            Path targetPath = findExistingCategoryPath(workingDir, projectRoot, category);

            if (targetPath != null) {
                if (!Files.exists(targetPath)) {
                    LOGGER.warn("[BuildingDataManager] 目录不存在，尝试类路径: {}", targetPath);
                    List<BuildingInfo> fallbackBuildings = loadFromClasspathFallback(category);
                    buildings.addAll(fallbackBuildings);
                } else {
                    AtomicInteger parsedCount = new AtomicInteger(0);
                    AtomicInteger addedCount = new AtomicInteger(0);

                    Files.walk(targetPath, 1)
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".sk"))
                        .forEach(skFilePath -> {
                            parsedCount.incrementAndGet();
                            try {
                                BuildingInfo info = parseBuildingFile(skFilePath, category);
                                if (info != null) {
                                    buildings.add(info);
                                    addedCount.incrementAndGet();
                                }
                            } catch (Exception e) {
                                LOGGER.error("[BuildingDataManager] 解析文件 {} 失败: {}", skFilePath, e.getMessage());
                            }
                        });

                    LOGGER.info("[BuildingDataManager] 加载类别 {}: 处理 {} 个文件，成功 {} 个建筑", category, parsedCount.get(), addedCount.get());
                }
            } else {
                LOGGER.warn("[BuildingDataManager] 未找到类别 {} 的有效路径", category);
            }

            if (buildings.isEmpty()) {
                List<BuildingInfo> classpathBuildings = loadFromClasspathFallback(category);
                buildings.addAll(classpathBuildings);
            }

        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 加载类别 {} 失败: {}", category, e.getMessage());
            List<BuildingInfo> fallbackBuildings = loadFromClasspathFallback(category);
            buildings.addAll(fallbackBuildings);
        }
        
        // menglannnn: 存入缓存 - 修复缓存设置逻辑
        // 无论是否为空，都设置缓存标志，但空列表时记录警告
        if (!buildings.isEmpty()) {
            BUILDING_CATEGORY_CACHE.put(category, new ArrayList<>(buildings));
            cacheInitialized = true;
            LOGGER.info("[BuildingDataManager] 类别 {} 已缓存 {} 个建筑", category, buildings.size());
        } else {
            // 如果建筑列表为空，尝试从类路径加载作为最后的后备
            LOGGER.warn("[BuildingDataManager] 类别 {} 从文件系统加载为空，尝试类路径后备", category);
            List<BuildingInfo> classpathBuildings = loadFromClasspathFallback(category);
            if (!classpathBuildings.isEmpty()) {
                buildings.addAll(classpathBuildings);
                BUILDING_CATEGORY_CACHE.put(category, new ArrayList<>(buildings));
                LOGGER.info("[BuildingDataManager] 类别 {} 从类路径加载了 {} 个建筑", category, classpathBuildings.size());
            }
            // 即使为空也设置标志，避免无限重试
            cacheInitialized = true;
        }

        return buildings;
    }

    private static BuildingInfo parseBuildingData(Map<String, String> data, String category, String fileName) {
        String name = data.getOrDefault("name", Component.translatable("building.unknown.name").getString());
        String size = data.getOrDefault("size", Component.translatable("building.unknown.size").getString());
        String amount = data.getOrDefault("amount", Component.translatable("building.unknown.price").getString());
        String author = data.getOrDefault("author", Component.translatable("building.unknown.author").getString());
        String description = data.getOrDefault("description", Component.translatable("building.unknown.description").getString());
        String jobType = data.getOrDefault("job_type", null);

        List<String> tags = new ArrayList<>();
        String tagsStr = data.get("tags");
        if (tagsStr != null && !tagsStr.isEmpty()) {
            for (String tag : tagsStr.split(",")) {
                tags.add(tag.trim());
            }
        }

        String baseName = fileName.replace(".sk", "");
        String litematicFileName = baseName + ".litematic";
        return new BuildingInfo(name, size, amount, author, description, category,
                fileName, litematicFileName, jobType, tags);
    }

    private static Map<String, String> parseBuildingLines(List<String> lines) {
        Map<String, String> data = new HashMap<>();
        for (String line : lines) {
            line = line.trim();
            if (!line.contains(":")) {
                continue;
            }
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                String key = parts[0].trim().replace("\uFEFF", "").trim();
                String value = parts[1].trim();
                data.put(key, value);
            }
        }
        return data;
    }

    private static BuildingInfo parseBuildingFile(Path skFilePath, String category) throws IOException {
        try {
            List<String> lines = Files.readAllLines(skFilePath, StandardCharsets.UTF_8);
            Map<String, String> data = parseBuildingLines(lines);
            return parseBuildingData(data, category, skFilePath.getFileName().toString());
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 解析文件 {} 失败: {}", skFilePath, e.getMessage());
            throw e;
        }
    }

    private static List<String> listCategoryResourceFiles(String category) {
        // 资源清单只作为加速和兜底，最终结果要与 JAR/源码目录实际文件合并，
        // 避免新增建筑忘记登记到 _files.txt 时发布包漏复制。
        java.util.Set<String> mergedFileNames = new java.util.LinkedHashSet<>(loadCategoryManifest(category));
        String resourceDir = BUILDING_ROOT_PATH + "/" + category;

        try {
            URL resourceUrl = BuildingDataManager.class.getClassLoader().getResource(resourceDir);
            if (resourceUrl != null && "jar".equalsIgnoreCase(resourceUrl.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) resourceUrl.openConnection();
                try (java.util.jar.JarFile jarFile = connection.getJarFile()) {
                    jarFile.stream().forEach(entry -> {
                        String name = entry.getName();
                        if (!name.startsWith(resourceDir + "/") || entry.isDirectory()) {
                            return;
                        }
                        String relative = name.substring(resourceDir.length() + 1);
                        if (!relative.contains("/")) {
                            mergedFileNames.add(relative);
                        }
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 获取资源目录 '{}' 失败: {}", resourceDir, e.getMessage());
        }

        try {
            Path workingDir = Paths.get("").toAbsolutePath();
            Path projectRoot = resolveProjectRoot(workingDir);
            Path fallbackDir = projectRoot.resolve("src/main/resources").resolve(resourceDir);
            if (Files.exists(fallbackDir) && Files.isDirectory(fallbackDir)) {
                try (Stream<Path> pathStream = Files.list(fallbackDir)) {
                    pathStream.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .forEach(mergedFileNames::add);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 文件系统后备枚举资源目录 '{}' 失败: {}", resourceDir, e.getMessage());
        }

        return new ArrayList<>(mergedFileNames);
    }

    private static List<String> loadCategoryManifest(String category) {
        List<String> fileNames = new ArrayList<>();
        String manifestPath = BUILDING_ROOT_PATH + "/" + category + "/_files.txt";
        try (java.io.InputStream inputStream = BuildingDataManager.class.getClassLoader().getResourceAsStream(manifestPath)) {
            if (inputStream == null) {
                return fileNames;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String fileName = line.trim();
                    if (!fileName.isEmpty() && !fileName.startsWith("#")) {
                        fileNames.add(fileName);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 读取资源清单 '{}' 失败: {}", manifestPath, e.getMessage());
        }
        return fileNames;
    }

    private static List<BuildingInfo> loadFromClasspathFallback(String category) {
        List<BuildingInfo> buildings = new ArrayList<>();
        
        try {
            for (String fileName : listCategoryResourceFiles(category)) {
                if (!fileName.endsWith(".sk")) {
                    continue;
                }
                java.io.InputStream fileStream = BuildingDataManager.class.getClassLoader()
                    .getResourceAsStream(BUILDING_ROOT_PATH + "/" + category + "/" + fileName);
                if (fileStream != null) {
                    try {
                        BuildingInfo info = parseBuildingFromStream(fileStream, category, fileName);
                        if (info != null) {
                            buildings.add(info);
                        }
                    } finally {
                        fileStream.close();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] ERROR in classpath fallback: {}", e.getMessage());
        }
        
        return buildings;
    }
    
    private static BuildingInfo parseBuildingFromStream(java.io.InputStream inputStream, String category, String fileName) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8));

            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            reader.close();

            Map<String, String> data = parseBuildingLines(lines);
            return parseBuildingData(data, category, fileName);

        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] ERROR parsing from stream: {}", e.getMessage());
            return null;
        }
    }
    
    public static List<BuildingInfo> searchBuildings(String query) {
        List<BuildingInfo> results = new ArrayList<>();
        
        if (query == null || query.trim().isEmpty()) {
            return results;
        }
        
        query = query.toLowerCase().trim();
        
        for (String category : CATEGORIES) {
            List<BuildingInfo> buildings = getBuildingsByCategory(category);
            for (BuildingInfo building : buildings) {
                if (building.getName().toLowerCase().contains(query)) {
                    results.add(building);
                }
            }
        }
        
        return results;
    }
    
    public static BuildingInfo getBuildingInfo(String category, String fileName) {
        String cacheKey = category + "/" + fileName;
        if (cacheInitialized) {
            BuildingInfo cached = BUILDING_FILE_CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<BuildingInfo> buildings = getBuildingsByCategory(category);
        for (BuildingInfo building : buildings) {
            if (building.getFileName().equals(fileName)) {
                BUILDING_FILE_CACHE.put(cacheKey, building);
                return building;
            }
        }
        return null;
    }

    /**
     * 通过建筑显示名称获取文件名（menglannnn: 用于PlacedBuildingManager注册建筑时获取正确的NBT文件名）
     * @param category 建筑类别
     * @param displayName 建筑显示名称
     * @return 文件名（不含扩展名），找不到返回null
     */
    @Nullable
    public static String getFileNameByDisplayName(String category, String displayName) {
        List<BuildingInfo> buildings = getBuildingsByCategory(category);
        for (BuildingInfo building : buildings) {
            if (building.getName().equals(displayName)) {
                // 返回文件名（去掉.sk或.nbt扩展名）
                String fileName = building.getFileName();
                if (fileName != null) {
                    // 去掉.sk扩展名
                    if (fileName.endsWith(".sk")) {
                        return fileName.substring(0, fileName.length() - 3);
                    }
                    // 去掉.nbt扩展名
                    if (fileName.endsWith(".nbt")) {
                        return fileName.substring(0, fileName.length() - 4);
                    }
                }
                return fileName;
            }
        }
        return null;
    }

    /**
     * 清理建筑数据缓存
     */
    public static void clearCache() {
        BUILDING_CATEGORY_CACHE.clear();
        BUILDING_FILE_CACHE.clear();
        cacheInitialized = false;
        LOGGER.info("[BuildingDataManager] 建筑数据缓存已清理");
    }
    
    /**
     * menglannnn: 重新初始化缓存 - 修复先复制文件再加载缓存的顺序问题
     */
    public static void reloadCache() {
        clearCache();
        // 先重新复制建筑文件，确保文件存在
        checkAndCopyBuildingFiles();
        // 预加载所有类别的建筑数据到缓存
        for (String category : CATEGORIES) {
            getBuildingsByCategory(category);
        }
        LOGGER.info("[BuildingDataManager] 建筑数据缓存已重新加载");
    }

    /**
     * menglannnn: 检查和复制建筑文件到simukraftbuilding文件夹
     * 改为public供reload命令调用
     */
    public static void checkAndCopyBuildingFiles() {
        try {
            Path rootPath = Paths.get("").toAbsolutePath();
            Path simukraftBuildingPath;
            if (SIMUKRAFT_BUILDING_FOLDER.startsWith("/") || SIMUKRAFT_BUILDING_FOLDER.matches("[A-Za-z]:.*")) {
                simukraftBuildingPath = Paths.get(SIMUKRAFT_BUILDING_FOLDER);
            } else {
                simukraftBuildingPath = rootPath.resolve(SIMUKRAFT_BUILDING_FOLDER);
            }

            if (!Files.exists(simukraftBuildingPath)) {
                Files.createDirectories(simukraftBuildingPath);
            }

            for (String category : CATEGORIES) {
                Path categoryPath = simukraftBuildingPath.resolve(category);
                if (!Files.exists(categoryPath)) {
                    Files.createDirectories(categoryPath);
                }
            }

            for (String category : CATEGORIES) {
                copyBuildingFilesForCategory(category, simukraftBuildingPath);
            }
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 检查和复制建筑文件失败: {}", e.getMessage());
        }
    }

    // 复制指定类别的建筑文件
    private static void copyBuildingFilesForCategory(String category, Path simukraftBuildingPath) {
        try {
            Path workingDir = Paths.get("").toAbsolutePath();
            Path projectRoot = resolveProjectRoot(workingDir);

            Path[] possibleSourcePaths = {
                projectRoot.resolve("src/main/resources").resolve(BUILDING_ROOT_PATH).resolve(category),
                projectRoot.resolve("build/resources/main").resolve(BUILDING_ROOT_PATH).resolve(category),
                null,
            };

            Path sourcePath = null;
            boolean useClasspath = false;
            for (Path path : possibleSourcePaths) {
                if (path == null) {
                    useClasspath = true;
                    break;
                }
                if (Files.exists(path) && Files.isDirectory(path)) {
                    sourcePath = path;
                    break;
                }
            }

            Path targetPath = simukraftBuildingPath.resolve(category);
            Files.createDirectories(targetPath);

            if (sourcePath != null) {
                long[] copyCount = {0};
                Files.walk(sourcePath, 1)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".sk") || path.toString().endsWith(".nbt") || path.toString().endsWith(".json"))
                    .forEach(sourceFilePath -> {
                        try {
                            Path targetFilePath = targetPath.resolve(sourceFilePath.getFileName());
                            if (!Files.exists(targetFilePath) || Files.size(sourceFilePath) != Files.size(targetFilePath)) {
                                Files.copy(sourceFilePath, targetFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                copyCount[0]++;
                            }
                        } catch (Exception e) {
                            LOGGER.error("[BuildingDataManager] 复制文件失败: {}", e.getMessage());
                        }
                    });
            } else if (useClasspath) {
                copyFilesFromClasspath(category, targetPath);
            }
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 复制类别 '{}' 失败: {}", category, e.getMessage());
        }
    }
    
    // 从类路径复制建筑文件到目标路径
    private static void copyFilesFromClasspath(String category, Path targetPath) {
        try {
            Files.createDirectories(targetPath);

            int copyCount = 0;
            List<String> resourceFiles = listCategoryResourceFiles(category);
            if (resourceFiles.isEmpty()) {
                LOGGER.warn("[BuildingDataManager] 类别 '{}' 未枚举到任何资源文件，复制将跳过", category);
            }
            for (String fileName : resourceFiles) {
                String resourcePath = BUILDING_ROOT_PATH + "/" + category + "/" + fileName;
                Path targetFilePath = targetPath.resolve(fileName);

                if (Files.exists(targetFilePath)) {
                    continue;
                }

                java.io.InputStream inputStream = BuildingDataManager.class.getClassLoader().getResourceAsStream(resourcePath);
                if (inputStream != null) {
                    try {
                        Files.copy(inputStream, targetFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        copyCount++;
                    } catch (Exception e) {
                        LOGGER.error("[BuildingDataManager] 复制文件失败: {} -> {}: {}", resourcePath, targetFilePath, e.getMessage());
                    } finally {
                        inputStream.close();
                    }
                } else {
                    LOGGER.error("[BuildingDataManager] 无法从类路径加载资源: {}", resourcePath);
                }
            }

            LOGGER.info("[BuildingDataManager] 从类路径复制类别 '{}' 完成，共复制 {} 个文件", category, copyCount);

        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 从类路径复制建筑文件失败: {}", e.getMessage());
        }
    }

    @Nullable
    public static Path findBuildingNbtPath(String buildingName, String category) {
        String fileName = buildingName + ".nbt";

        try {
            if (SIMUKRAFT_BUILDING_FOLDER.startsWith("/") || SIMUKRAFT_BUILDING_FOLDER.matches("[A-Za-z]:.*")) {
                Path nbtPath = Paths.get(SIMUKRAFT_BUILDING_FOLDER, category, fileName);
                return Files.exists(nbtPath) ? nbtPath : null;
            }

            Path rootPath = Paths.get("").toAbsolutePath();
            Path nbtPath = rootPath.resolve(SIMUKRAFT_BUILDING_FOLDER).resolve(category).resolve(fileName);
            return Files.exists(nbtPath) ? nbtPath : null;
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 定位建筑NBT路径失败: {}/{} -> {}", category, fileName, e.getMessage());
            return null;
        }
    }

    @Nullable
    public static Long getBuildingNbtFileSize(String buildingName, String category) {
        Path nbtPath = findBuildingNbtPath(buildingName, category);
        if (nbtPath == null) {
            return null;
        }

        try {
            return Files.size(nbtPath);
        } catch (Exception e) {
            LOGGER.error("[BuildingDataManager] 获取建筑NBT大小失败: {}/{} -> {}", category, buildingName, e.getMessage());
            return null;
        }
    }

    public static CompoundTag loadBuildingData(String buildingName, String category) {
        try {
            // 构建NBT文件路径
            String fileName = buildingName + ".nbt";
            Path nbtPath;
            
            if (SIMUKRAFT_BUILDING_FOLDER.startsWith("/") || SIMUKRAFT_BUILDING_FOLDER.matches("[A-Za-z]:.*")) {
                // 绝对路径，直接使用
                nbtPath = Paths.get(SIMUKRAFT_BUILDING_FOLDER, category, fileName);
            } else {
                // 相对路径，与当前工作目录结合
                Path rootPath = Paths.get("").toAbsolutePath();
                nbtPath = rootPath.resolve(SIMUKRAFT_BUILDING_FOLDER).resolve(category).resolve(fileName);
            }
            
            if (!Files.exists(nbtPath)) {
                // 尝试从类路径加载作为后备
                java.io.InputStream inputStream = BuildingDataManager.class.getClassLoader()
                    .getResourceAsStream(BUILDING_ROOT_PATH + "/" + category + "/" + fileName);
                
                if (inputStream != null) {
                    return net.minecraft.nbt.NbtIo.readCompressed(inputStream);
                }
                
                LOGGER.error("建筑NBT文件不存在: {}", nbtPath.toAbsolutePath());
                return null;
            }
            
            return net.minecraft.nbt.NbtIo.readCompressed(java.util.Objects.requireNonNull(Files.newInputStream(nbtPath)));
            
        } catch (Exception e) {
            LOGGER.error("加载建筑NBT文件失败: {}", e.getMessage());
            return null;
        }
    }
    
    // 初始化方法，用于触发静态初始化块
    public static void init() {
        // 空方法，仅用于触发类的加载和静态初始化
        LOGGER.info("[BuildingDataManager] init() called, static initialization should be completed");
    }
    
    // 主方法，用于测试触发文件复制机制
    public static void main(String[] args) {
        LOGGER.info("Testing BuildingDataManager static initialization...");
        // 触发静态初始化块，执行文件复制
        LOGGER.info("Test completed, check if simukraftbuilding folder is generated");
    }
}
