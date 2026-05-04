package com.xiaoliang.simukraft.config;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerConfig {
    public static final ForgeConfigSpec SPEC;

    private static final Map<String, Object> configCache = new ConcurrentHashMap<>();

    // ==================== 黑名单配置 ====================
    public static final ForgeConfigSpec.ConfigValue<List<String>> PLANNING_BLOCK_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<String>> CONSTRUCTION_BLOCK_BLACKLIST;

    // ==================== 材料配置 ====================
    // 普通模式 - 启用材料通类匹配
    public static final ForgeConfigSpec.ConfigValue<Boolean> ENABLE_MATERIAL_CATEGORY_MATCHING;
    // 普通模式 - 基础材料列表（单个材料）
    public static final ForgeConfigSpec.ConfigValue<List<String>> BASIC_MATERIALS;
    // 普通模式 - 通类匹配组配置（格式: tagName:item1,item2,item3）
    public static final ForgeConfigSpec.ConfigValue<List<String>> MATERIAL_CATEGORY_GROUPS;
    // 专家模式 - 跳过列表（专家模式下不需要的材料）
    public static final ForgeConfigSpec.ConfigValue<List<String>> EXPERT_MODE_SKIP_LIST;

    // ==================== 规划师工作配置 ====================
    public static final ForgeConfigSpec.ConfigValue<Integer> PLANNER_REMOVE_SPEED_BASE;
    public static final ForgeConfigSpec.ConfigValue<Integer> PLANNER_REPLACE_SPEED_BASE;
    public static final ForgeConfigSpec.ConfigValue<Integer> PLANNER_FILL_SPEED_BASE;
    public static final ForgeConfigSpec.ConfigValue<Boolean> PLANNER_DROP_ITEMS_ON_REMOVE;
    public static final ForgeConfigSpec.ConfigValue<Boolean> PLANNER_STORE_ITEMS_IN_CHEST;
    public static final ForgeConfigSpec.ConfigValue<Integer> PLANNER_CHEST_SEARCH_RANGE;
    public static final ForgeConfigSpec.ConfigValue<Integer> PLANNER_WARNING_COOLDOWN;
    public static final ForgeConfigSpec.ConfigValue<Integer> PLANNER_REST_CHECK_INTERVAL;
    public static final ForgeConfigSpec.ConfigValue<Boolean> PLANNER_ENABLE_XP_GAIN;
    public static final ForgeConfigSpec.ConfigValue<Integer> PLANNER_XP_PER_BLOCK;

    // ==================== 建筑师工作配置 ====================
    // menglan: 使用每秒放置方块数，内部自动转换为每tick小数
    public static final ForgeConfigSpec.ConfigValue<Double> BUILDER_BLOCKS_PER_SECOND;
    public static final ForgeConfigSpec.ConfigValue<Integer> BUILDER_CHEST_SEARCH_RANGE;
    public static final ForgeConfigSpec.ConfigValue<Integer> BUILDER_WARNING_COOLDOWN;
    public static final ForgeConfigSpec.ConfigValue<Boolean> BUILDER_ENABLE_XP_GAIN;
    public static final ForgeConfigSpec.ConfigValue<Integer> BUILDER_XP_PER_BLOCK;
    public static final ForgeConfigSpec.ConfigValue<Integer> BUILDER_CHUNK_LOAD_WAIT_TICKS;

    // ==================== NPC等级配置 ====================
    public static final ForgeConfigSpec.ConfigValue<Integer> NPC_MAX_LEVEL;
    public static final ForgeConfigSpec.ConfigValue<Integer> NPC_SPEED_BONUS_PER_LEVEL;
    public static final ForgeConfigSpec.ConfigValue<Integer> NPC_MIN_SPEED_TICKS;

    // ==================== 集成配置 ====================
    public static final ForgeConfigSpec.ConfigValue<Boolean> ENABLE_OPAC_CLAIMS;

    // ==================== 农田配置 ====================
    public static final ForgeConfigSpec.ConfigValue<Boolean> FARMER_ENABLE_CROP_GROWTH_BOOST;

    // ==================== 通用配置 ====================
    public static final ForgeConfigSpec.ConfigValue<Boolean> ENABLE_BLACKLIST_PROTECTION;
    public static final ForgeConfigSpec.ConfigValue<Boolean> LOG_BLACKLIST_SKIPPED_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<Boolean> ENABLE_DEBUG_LOG;
    // 创造模式 - 不需要材料和金钱
    public static final ForgeConfigSpec.ConfigValue<Boolean> ENABLE_CREATIVE_MODE;
    // 专家模式 - 已移到通用配置
    public static final ForgeConfigSpec.ConfigValue<Boolean> ENABLE_EXPERT_MODE;

    // 默认配置值（简化版本，详细配置在外部文件中）
    private static final List<String> DEFAULT_BLACKLIST = new ArrayList<>();
    private static final List<String> DEFAULT_BASIC_MATERIALS = new ArrayList<>();
    private static final List<String> DEFAULT_CATEGORY_GROUPS = new ArrayList<>();
    private static final List<String> DEFAULT_EXPERT_SKIP_LIST = new ArrayList<>();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // ==================== 通用配置 ====================
        builder.push("general");

        ENABLE_BLACKLIST_PROTECTION = builder
                .comment("是否启用方块黑名单保护",
                        "禁用后规划师和建筑师将可以处理所有方块")
                .define("enableBlacklistProtection", true);

        LOG_BLACKLIST_SKIPPED_BLOCKS = builder
                .comment("是否在日志中记录被跳过的黑名单方块")
                .define("logSkippedBlocks", true);

        ENABLE_DEBUG_LOG = builder
                .comment("是否启用调试日志（会输出更多详细信息）")
                .define("enableDebugLog", false);

        ENABLE_CREATIVE_MODE = builder
                .comment("是否启用创造模式",
                        "开启后建筑师建造不需要材料和金钱",
                        "与专家模式互斥，同时开启时创造模式优先")
                .define("enableCreativeMode", false);

        ENABLE_EXPERT_MODE = builder
                .comment("是否启用专家模式",
                        "专家模式下建筑师需要所有材料（除了跳过列表中的）",
                        "普通模式下使用基础材料列表和通类匹配组")
                .define("enableExpertMode", false);

        builder.pop();

        // ==================== NPC等级系统配置 ====================
        builder.push("npc_leveling");

        NPC_MAX_LEVEL = builder
                .comment("NPC最高等级（默认20级）")
                .defineInRange("maxLevel", 20, 1, 20);

        NPC_SPEED_BONUS_PER_LEVEL = builder
                .comment("每升一级减少的工作时间（tick）")
                .defineInRange("speedBonusPerLevel", 5, 0, 50);

        NPC_MIN_SPEED_TICKS = builder
                .comment("NPC工作的最短时间（tick），不会被等级提升而减少")
                .defineInRange("minSpeedTicks", 5, 1, 100);

        builder.pop();

        // ==================== 材料配置 ====================
        builder.push("materials");

        ENABLE_MATERIAL_CATEGORY_MATCHING = builder
                .comment("普通模式下是否启用材料通类匹配",
                        "启用后可以使用通类匹配组中的任意材料替代")
                .define("enableMaterialCategoryMatching", true);

        BASIC_MATERIALS = builder
                .comment("普通模式 - 基础材料列表",
                        "建筑师建造时需要的单个材料",
                        "格式: modid:item_name")
                .define("basicMaterials", new ArrayList<>(DEFAULT_BASIC_MATERIALS));

        MATERIAL_CATEGORY_GROUPS = builder
                .comment("普通模式 - 通类匹配组配置",
                        "格式: 组名|组头|组员",
                        "匹配材料: 用于匹配建筑图纸中的材料（可以是单个材料或标签，可以为空）",
                        "输入材料: 玩家可以使用的替代材料列表",
                        "如果匹配材料为空（||），则输入材料列表中的任何材料都可以互相替代",
                        "例如: oak_wood|minecraft:oak_planks|minecraft:oak_stairs,minecraft:oak_slab,minecraft:oak_planks",
                        "例如: concrete||minecraft:white_concrete,minecraft:orange_concrete,... (所有混凝土可以互相替代)")
                .define("materialCategoryGroups", new ArrayList<>(DEFAULT_CATEGORY_GROUPS));

        EXPERT_MODE_SKIP_LIST = builder
                .comment("专家模式 - 跳过列表",
                        "专家模式下不需要的材料（默认与黑名单相同）",
                        "格式: modid:block_name")
                .define("expertModeSkipList", new ArrayList<>(DEFAULT_EXPERT_SKIP_LIST));

        builder.pop();

        // ==================== 规划师配置 ====================
        builder.push("planning");

        PLANNING_BLOCK_BLACKLIST = builder
                .comment("规划师无法处理的方块黑名单（拆除/替换/填充）",
                        "格式: modid:block_name",
                        "默认包含基岩、命令方块、屏障等保护方块，以及本模组的所有控制盒和建筑盒")
                .define("blockBlacklist", new ArrayList<>(DEFAULT_BLACKLIST));

        // 速度配置
        PLANNER_REMOVE_SPEED_BASE = builder
                .comment("规划师基础拆除速度（tick/方块），等级1的速度")
                .defineInRange("removeSpeedBase", 40, 5, 200);

        PLANNER_REPLACE_SPEED_BASE = builder
                .comment("规划师基础替换速度（tick/方块）")
                .defineInRange("replaceSpeedBase", 40, 5, 200);

        PLANNER_FILL_SPEED_BASE = builder
                .comment("规划师基础填充速度（tick/方块）")
                .defineInRange("fillSpeedBase", 40, 5, 200);

        // 物品处理配置
        PLANNER_DROP_ITEMS_ON_REMOVE = builder
                .comment("拆除方块时是否掉落物品")
                .define("dropItemsOnRemove", true);

        PLANNER_STORE_ITEMS_IN_CHEST = builder
                .comment("是否将拆除/替换获得的物品存入附近箱子",
                        "如果关闭，物品将掉落在地上")
                .define("storeItemsInChest", true);

        PLANNER_CHEST_SEARCH_RANGE = builder
                .comment("搜索箱子的范围（格）")
                .defineInRange("chestSearchRange", 3, 1, 20);

        // 消息与冷却配置
        PLANNER_WARNING_COOLDOWN = builder
                .comment("材料不足警告的冷却时间（秒）")
                .defineInRange("warningCooldownSeconds", 20, 1, 300);

        PLANNER_REST_CHECK_INTERVAL = builder
                .comment("休息状态检查间隔（tick，20tick=1秒）")
                .defineInRange("restCheckInterval", 100, 1, 200);

        // 经验配置
        PLANNER_ENABLE_XP_GAIN = builder
                .comment("规划师是否获得经验")
                .define("enableXpGain", true);

        PLANNER_XP_PER_BLOCK = builder
                .comment("每处理一个方块获得的经验值")
                .defineInRange("xpPerBlock", 1, 0, 100);

        builder.pop();

        // ==================== 建筑师配置 ====================
        builder.push("construction");

        CONSTRUCTION_BLOCK_BLACKLIST = builder
                .comment("建筑师无法处理的方块黑名单（建造时跳过）",
                        "格式: modid:block_name",
                        "默认包含基岩、命令方块、屏障等保护方块，以及本模组的所有控制盒和建筑盒")
                .define("blockBlacklist", new ArrayList<>(DEFAULT_BLACKLIST));

        // 速度配置 - menglan: 使用每秒放置方块数，更直观
        BUILDER_BLOCKS_PER_SECOND = builder
                .comment("建筑师每秒放置方块数（1级=1个，20级=5个，线性增长）",
                        "实际速度会根据等级自动计算")
                .defineInRange("blocksPerSecond", 1.0, 0.1, 20.0);

        BUILDER_CHEST_SEARCH_RANGE = builder
                .comment("建筑师搜索材料的箱子范围（格）")
                .defineInRange("chestSearchRange", 3, 1, 20);

        // 消息与冷却配置
        BUILDER_WARNING_COOLDOWN = builder
                .comment("材料不足警告的冷却时间（秒）")
                .defineInRange("warningCooldownSeconds", 20, 1, 300);

        // 经验配置
        BUILDER_ENABLE_XP_GAIN = builder
                .comment("建筑师是否获得经验")
                .define("enableXpGain", true);

        BUILDER_XP_PER_BLOCK = builder
                .comment("每放置一个方块获得的经验值")
                .defineInRange("xpPerBlock", 1, 0, 100);

        // 区块加载配置
        BUILDER_CHUNK_LOAD_WAIT_TICKS = builder
                .comment("建筑师等待区块加载的最大tick数",
                        "工作流按需强加载后等待区块就绪的最大tick数")
                .defineInRange("chunkLoadWaitTicks", 60, 10, 200);

        builder.pop();

        // ==================== 农田配置 ====================
        builder.push("farmland");

        FARMER_ENABLE_CROP_GROWTH_BOOST = builder
                .comment("农民是否启用作物加速生长",
                        "关闭后作物将以正常速度生长")
                .define("enableCropGrowthBoost", true);

        builder.pop();

        // ==================== 集成配置 ====================
        builder.push("integration");

        ENABLE_OPAC_CLAIMS = builder
                .comment("Whether to enable Open Parties And Claims chunk claiming integration",
                        "When enabled, city chunks will be automatically claimed via OPAC using the mayor's player UUID")
                .define("enableOpacClaims", true);

        builder.pop();

        SPEC = builder.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getCached(String key, ForgeConfigSpec.ConfigValue<T> configValue) {
        return (T) configCache.computeIfAbsent(key, k -> configValue.get());
    }

    private static <T> void setCached(String key, ForgeConfigSpec.ConfigValue<T> configValue, T value) {
        configCache.put(key, value);
        configValue.set(value);
        SPEC.save();
    }

    public static void clearCache() {
        configCache.clear();
    }

    /**
     * 重置所有配置为默认值
     */
    public static void resetToDefaults() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("simukraft-common.toml");
        
        try {
            // 首先尝试从运行目录的 defaultconfigs 复制
            Path defaultConfigPath = FMLPaths.GAMEDIR.get().resolve("defaultconfigs/simukraft-common.toml");
            
            if (Files.exists(defaultConfigPath)) {
                // 从运行目录的 defaultconfigs 复制
                Files.copy(defaultConfigPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Simukraft.LOGGER.info("[ServerConfig] 已从 defaultconfigs 复制默认配置: {} -> {}", defaultConfigPath, configPath);
            } else {
                // 从 JAR 包内提取默认配置
                Simukraft.LOGGER.info("[ServerConfig] 运行目录 defaultconfigs 不存在，尝试从 JAR 包提取默认配置");
                extractDefaultConfigFromJar(configPath);
            }
        } catch (IOException e) {
            Simukraft.LOGGER.error("[ServerConfig] 重置配置文件失败", e);
        }
        
        Simukraft.LOGGER.info("[ServerConfig] 配置已重置，请重启游戏以应用新配置");
    }
    
    /**
     * 从 JAR 包内提取默认配置文件
     */
    private static void extractDefaultConfigFromJar(Path targetPath) throws IOException {
        // 获取当前类的类加载器
        ClassLoader classLoader = ServerConfig.class.getClassLoader();
        String resourcePath = "defaultconfigs/simukraft-common.toml";
        
        // 尝试从类路径读取资源
        java.io.InputStream is = classLoader.getResourceAsStream(resourcePath);
        
        if (is == null) {
            // 尝试使用绝对路径
            is = ServerConfig.class.getResourceAsStream("/" + resourcePath);
        }
        
        if (is != null) {
            // 确保目标目录存在
            Files.createDirectories(targetPath.getParent());
            
            // 复制文件
            Files.copy(is, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            is.close();
            
            Simukraft.LOGGER.info("[ServerConfig] 已从 JAR 包提取默认配置到: {}", targetPath);
        } else {
            Simukraft.LOGGER.error("[ServerConfig] 无法在 JAR 包中找到默认配置: {}", resourcePath);
            Simukraft.LOGGER.warn("[ServerConfig] 将删除现有配置，Forge 将使用代码默认值重新生成");
            Files.deleteIfExists(targetPath);
        }
    }
    
    /**
     * 尝试从 defaultconfigs 重新加载配置
     * 当当前配置为空时调用
     * @return 返回重新加载的材料组列表，如果失败返回空列表
     */
    private static List<String> tryReloadFromDefaultConfig() {
        try {
            Path configPath = FMLPaths.CONFIGDIR.get().resolve("simukraft-common.toml");
            Path defaultConfigPath = FMLPaths.GAMEDIR.get().resolve("defaultconfigs/simukraft-common.toml");
            
            // 首先尝试从运行目录的 defaultconfigs 复制
            if (Files.exists(defaultConfigPath)) {
                Files.copy(defaultConfigPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Simukraft.LOGGER.info("[ServerConfig] 已从运行目录 defaultconfigs 重新加载配置");
            } else {
                // 从 JAR 包提取
                Simukraft.LOGGER.info("[ServerConfig] 运行目录 defaultconfigs 不存在，尝试从 JAR 包提取默认配置");
                extractDefaultConfigFromJar(configPath);
            }
            
            // 直接从文件读取 materialCategoryGroups
            if (Files.exists(configPath)) {
                com.electronwill.nightconfig.core.file.FileConfig fileConfig = 
                    com.electronwill.nightconfig.core.file.FileConfig.of(configPath.toFile());
                fileConfig.load();
                
                List<String> groups = fileConfig.getOrElse("materials.materialCategoryGroups", new ArrayList<>());
                fileConfig.close();
                
                if (!groups.isEmpty()) {
                    Simukraft.LOGGER.debug("[ServerConfig] 成功从文件读取到 {} 个材料组", groups.size());
                    return groups;
                }
            }
            
            Simukraft.LOGGER.warn("[ServerConfig] 无法从文件读取材料组配置");
            return new ArrayList<>();
            
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ServerConfig] 从 defaultconfigs 重新加载配置失败", e);
            return new ArrayList<>();
        }
    }

    // ==================== 通用方法 ====================
    
    /**
     * 从配置文件读取指定路径的字符串列表
     * @param path 配置路径（如 "materials.materialCategoryGroups"）
     * @return 配置列表，如果失败返回空列表
     */
    private static List<String> loadListFromConfigFile(String path) {
        try {
            Path configPath = FMLPaths.CONFIGDIR.get().resolve("simukraft-common.toml");
            
            if (!Files.exists(configPath)) {
                // 尝试从 defaultconfigs 复制
                Path defaultConfigPath = FMLPaths.GAMEDIR.get().resolve("defaultconfigs/simukraft-common.toml");
                if (Files.exists(defaultConfigPath)) {
                    Files.copy(defaultConfigPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    extractDefaultConfigFromJar(configPath);
                }
            }
            
            if (Files.exists(configPath)) {
                com.electronwill.nightconfig.core.file.FileConfig fileConfig = 
                    com.electronwill.nightconfig.core.file.FileConfig.of(configPath.toFile());
                fileConfig.load();
                
                List<String> result = fileConfig.getOrElse(path, new ArrayList<>());
                fileConfig.close();
                
                return result;
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ServerConfig] 从文件读取配置失败 [{}]", path, e);
        }
        return new ArrayList<>();
    }

    public static boolean isBlacklistProtectionEnabled() {
        return getCached("enableBlacklistProtection", ENABLE_BLACKLIST_PROTECTION);
    }

    public static boolean shouldLogSkippedBlocks() {
        return getCached("logSkippedBlocks", LOG_BLACKLIST_SKIPPED_BLOCKS);
    }

    public static boolean isDebugLogEnabled() {
        return getCached("enableDebugLog", ENABLE_DEBUG_LOG);
    }

    public static boolean isOpacClaimsEnabled() {
        return getCached("enableOpacClaims", ENABLE_OPAC_CLAIMS);
    }

    // ==================== 黑名单检查方法 ====================

    public static boolean isBlockBlacklistedForPlanning(String blockId) {
        if (!isBlacklistProtectionEnabled()) {
            return false;
        }
        List<String> blacklist = getCached("planningBlacklist", PLANNING_BLOCK_BLACKLIST);
        if (blacklist == null || blacklist.isEmpty()) {
            return false;
        }
        return blacklist.contains(blockId);
    }

    public static boolean isBlockBlacklistedForConstruction(String blockId) {
        if (!isBlacklistProtectionEnabled()) {
            return false;
        }
        List<String> blacklist = getCached("constructionBlacklist", CONSTRUCTION_BLOCK_BLACKLIST);
        if (blacklist == null || blacklist.isEmpty()) {
            return false;
        }
        return blacklist.contains(blockId);
    }

    // ==================== 规划师配置获取方法 ====================

    public static int getPlannerRemoveSpeed(int level) {
        int baseSpeed = getCached("plannerRemoveSpeedBase", PLANNER_REMOVE_SPEED_BASE);
        int bonus = getCached("npcSpeedBonusPerLevel", NPC_SPEED_BONUS_PER_LEVEL) * (level - 1);
        return Math.max(getCached("npcMinSpeedTicks", NPC_MIN_SPEED_TICKS), baseSpeed - bonus);
    }

    public static int getPlannerReplaceSpeed(int level) {
        int baseSpeed = getCached("plannerReplaceSpeedBase", PLANNER_REPLACE_SPEED_BASE);
        int bonus = getCached("npcSpeedBonusPerLevel", NPC_SPEED_BONUS_PER_LEVEL) * (level - 1);
        return Math.max(getCached("npcMinSpeedTicks", NPC_MIN_SPEED_TICKS), baseSpeed - bonus);
    }

    public static int getPlannerFillSpeed(int level) {
        int baseSpeed = getCached("plannerFillSpeedBase", PLANNER_FILL_SPEED_BASE);
        int bonus = getCached("npcSpeedBonusPerLevel", NPC_SPEED_BONUS_PER_LEVEL) * (level - 1);
        return Math.max(getCached("npcMinSpeedTicks", NPC_MIN_SPEED_TICKS), baseSpeed - bonus);
    }

    public static boolean shouldDropItemsOnRemove() {
        return getCached("plannerDropItemsOnRemove", PLANNER_DROP_ITEMS_ON_REMOVE);
    }

    public static boolean shouldStoreItemsInChest() {
        return getCached("plannerStoreItemsInChest", PLANNER_STORE_ITEMS_IN_CHEST);
    }

    public static int getPlannerChestSearchRange() {
        return getCached("plannerChestSearchRange", PLANNER_CHEST_SEARCH_RANGE);
    }

    public static long getPlannerWarningCooldownMs() {
        return getCached("plannerWarningCooldown", PLANNER_WARNING_COOLDOWN) * 1000L;
    }

    public static int getPlannerRestCheckInterval() {
        return getCached("plannerRestCheckInterval", PLANNER_REST_CHECK_INTERVAL);
    }

    public static boolean isPlannerXpGainEnabled() {
        return getCached("plannerEnableXpGain", PLANNER_ENABLE_XP_GAIN);
    }

    public static int getPlannerXpPerBlock() {
        return getCached("plannerXpPerBlock", PLANNER_XP_PER_BLOCK);
    }

    // ==================== 建筑师配置获取方法 ====================

    /**
     * menglan: 获取建筑师每秒放置方块数（根据等级线性增长）
     * @param level NPC等级
     * @return 每秒放置方块数
     */
    public static double getBuilderBlocksPerSecond(int level) {
        double baseSpeed = getCached("builderBlocksPerSecond", BUILDER_BLOCKS_PER_SECOND);
        int maxLevel = getCached("npcMaxLevel", NPC_MAX_LEVEL);
        float progress = (float) (level - 1) / (maxLevel - 1);
        return baseSpeed * (1 + progress * 4); // 1级=1倍, 20级=5倍
    }

    /**
     * menglan: 获取建筑师每tick放置方块数（小数）
     * @param level NPC等级
     * @return 每tick放置方块数
     */
    public static double getBuilderBlocksPerTickDouble(int level) {
        return getBuilderBlocksPerSecond(level) / 20.0; // 20 tick = 1秒
    }

    /**
     * 检查是否启用了创造模式（不需要材料和金钱）
     * 创造模式优先于专家模式
     */
    public static boolean isCreativeModeEnabled() {
        return getCached("enableCreativeMode", ENABLE_CREATIVE_MODE);
    }

    /**
     * 检查建筑师是否需要材料
     * 创造模式下不需要材料
     */
    public static boolean isBuilderRequireMaterials() {
        // 创造模式下不需要材料
        return !isCreativeModeEnabled();
    }

    /**
     * 检查建筑师是否需要金钱
     * 创造模式下不需要金钱
     */
    public static boolean isBuilderRequireMoney() {
        // 创造模式下不需要金钱
        return !isCreativeModeEnabled();
    }

    public static int getBuilderChestSearchRange() {
        return getCached("builderChestSearchRange", BUILDER_CHEST_SEARCH_RANGE);
    }

    public static long getBuilderWarningCooldownMs() {
        return getCached("builderWarningCooldown", BUILDER_WARNING_COOLDOWN) * 1000L;
    }

    public static boolean isBuilderXpGainEnabled() {
        return getCached("builderEnableXpGain", BUILDER_ENABLE_XP_GAIN);
    }

    public static int getBuilderXpPerBlock() {
        return getCached("builderXpPerBlock", BUILDER_XP_PER_BLOCK);
    }

    public static int getBuilderChunkLoadWaitTicks() {
        return getCached("builderChunkLoadWaitTicks", BUILDER_CHUNK_LOAD_WAIT_TICKS);
    }

    // ==================== NPC等级配置获取方法 ====================

    public static int getNpcMaxLevel() {
        return getCached("npcMaxLevel", NPC_MAX_LEVEL);
    }

    public static int getNpcSpeedBonusPerLevel() {
        return getCached("npcSpeedBonusPerLevel", NPC_SPEED_BONUS_PER_LEVEL);
    }

    public static int getNpcMinSpeedTicks() {
        return getCached("npcMinSpeedTicks", NPC_MIN_SPEED_TICKS);
    }

    // ==================== 材料配置获取方法 ====================

    public static boolean isExpertModeEnabled() {
        return getCached("enableExpertMode", ENABLE_EXPERT_MODE);
    }

    public static boolean isMaterialCategoryMatchingEnabled() {
        return getCached("enableMaterialCategoryMatching", ENABLE_MATERIAL_CATEGORY_MATCHING);
    }

    // ==================== 农田配置获取方法 ====================

    public static boolean isFarmerCropGrowthBoostEnabled() {
        return getCached("farmerEnableCropGrowthBoost", FARMER_ENABLE_CROP_GROWTH_BOOST);
    }

    public static List<String> getBasicMaterials() {
        List<String> materials = getCached("basicMaterials", BASIC_MATERIALS);
        if (materials == null || materials.isEmpty()) {
            materials = loadListFromConfigFile("materials.basicMaterials");
        }
        return materials;
    }

    public static void setBasicMaterials(List<String> materials) {
        setCached("basicMaterials", BASIC_MATERIALS, materials);
    }

    public static List<String> getMaterialCategoryGroups() {
        List<String> groups = getCached("materialCategoryGroups", MATERIAL_CATEGORY_GROUPS);
        if (groups == null || groups.isEmpty()) {
            groups = loadListFromConfigFile("materials.materialCategoryGroups");
        }
        return groups;
    }

    public static void setMaterialCategoryGroups(List<String> groups) {
        setCached("materialCategoryGroups", MATERIAL_CATEGORY_GROUPS, groups);
    }

    public static List<String> getExpertModeSkipList() {
        List<String> skipList = getCached("expertModeSkipList", EXPERT_MODE_SKIP_LIST);
        if (skipList == null || skipList.isEmpty()) {
            skipList = loadListFromConfigFile("materials.expertModeSkipList");
        }
        return skipList;
    }

    public static void setExpertModeSkipList(List<String> skipList) {
        setCached("expertModeSkipList", EXPERT_MODE_SKIP_LIST, skipList);
    }

    /**
     * 材料组信息类，包含组头和组员
     */
    public static class MaterialGroupInfo {
        private final String groupName;
        private final List<String> headers;
        private final List<String> members;

        public MaterialGroupInfo(String groupName, List<String> headers, List<String> members) {
            this.groupName = groupName;
            this.headers = headers;
            this.members = members;
        }

        public String getGroupName() { return groupName; }
        public List<String> getHeaders() { return headers; }
        public List<String> getMembers() { return members; }

        /**
         * 获取组内所有材料（组头+组员）
         */
        public List<String> getAllMaterials() {
            List<String> all = new ArrayList<>(headers);
            all.addAll(members);
            return all;
        }

        /**
         * 检查材料是否是组头
         */
        public boolean isHeader(String material) {
            return headers.contains(material);
        }

        /**
         * 检查材料是否是组员
         */
        public boolean isMember(String material) {
            return members.contains(material);
        }

        /**
         * 检查材料是否在组内
         */
        public boolean contains(String material) {
            return headers.contains(material) || members.contains(material);
        }
    }

    /**
     * 解析通类匹配组（新格式：组名|组头1,组头2|组员1,组员2,组员3）
     * 兼容旧格式：组名:材料1,材料2,材料3（所有材料都视为组头）
     * @return 返回组名到组信息的映射
     */
    public static Map<String, MaterialGroupInfo> parseMaterialCategoryGroups() {
        Map<String, MaterialGroupInfo> result = new HashMap<>();
        List<String> groups = getCached("materialCategoryGroups", MATERIAL_CATEGORY_GROUPS);
        
        if (groups == null || groups.isEmpty()) {
            groups = tryReloadFromDefaultConfig();
        }

        for (String group : groups) {
            if (group == null || group.trim().isEmpty()) continue;

            // 优先使用 | 作为分隔符（新格式）
            if (group.contains("|")) {
                String[] parts = group.split("\\|", 3);
                if (parts.length >= 2) {
                    String groupName = parts[0].trim();
                    List<String> headers = new ArrayList<>();
                    List<String> members = new ArrayList<>();

                    if (parts.length >= 2 && !parts[1].trim().isEmpty()) {
                        // 组头
                        String[] headerItems = parts[1].split(",");
                        for (String item : headerItems) {
                            String trimmed = item.trim();
                            if (!trimmed.isEmpty()) {
                                headers.add(trimmed);
                            }
                        }
                    }

                    if (parts.length >= 3 && !parts[2].trim().isEmpty()) {
                        // 组员
                        String[] memberItems = parts[2].split(",");
                        for (String item : memberItems) {
                            String trimmed = item.trim();
                            if (!trimmed.isEmpty()) {
                                members.add(trimmed);
                            }
                        }
                    }

                    if (!groupName.isEmpty() && (!headers.isEmpty() || !members.isEmpty())) {
                        result.put(groupName, new MaterialGroupInfo(groupName, headers, members));
                    }
                }
            } else {
                // 兼容旧格式：使用 : 作为分隔符
                String[] parts = group.split(":", 3);
                if (parts.length >= 2) {
                    String groupName = parts[0].trim();
                    List<String> headers = new ArrayList<>();
                    List<String> members = new ArrayList<>();

                    // 旧格式只支持组头，不支持组员
                    String[] items = parts[1].split(",");
                    for (String item : items) {
                        String trimmed = item.trim();
                        if (!trimmed.isEmpty()) {
                            headers.add(trimmed);
                        }
                    }

                    if (!groupName.isEmpty() && !headers.isEmpty()) {
                        result.put(groupName, new MaterialGroupInfo(groupName, headers, members));
                    }
                }
            }
        }

        return result;
    }

    /**
     * 获取组的可替代材料列表（用于材料匹配）
     * 有组头时：只能使用组头的材料来匹配组员
     * 没有组头时：组员之间可以互相匹配
     * @param material 材料ID
     * @return 可替代的材料列表
     */
    public static List<String> getAlternativeMaterials(String material) {
        Map<String, MaterialGroupInfo> groups = parseMaterialCategoryGroups();
        for (MaterialGroupInfo group : groups.values()) {
            // 检查材料是否在组内（可以是组头或组员）
            if (group.contains(material)) {
                if (!group.getHeaders().isEmpty()) {
                    // 有组头时：组头可以匹配组内所有材料
                    // 组员只能被组头匹配（返回组员自己）
                    if (group.isHeader(material)) {
                        return group.getAllMaterials();
                    } else {
                        return Collections.singletonList(material);
                    }
                } else {
                    // 没有组头时：组员之间可以互相匹配
                    return group.getAllMaterials();
                }
            }
        }
        return Collections.singletonList(material);
    }

    /**
     * 检查材料是否可以被替代
     * 有组头时：组头可以匹配组员（组员可以被组头替代）
     * 没有组头时：组员之间可以互相匹配
     * @param requiredMaterial 需要的材料
     * @param providedMaterial 提供的材料
     * @return 如果可以替代返回true
     */
    public static boolean canMaterialReplace(String requiredMaterial, String providedMaterial) {
        if (requiredMaterial.equals(providedMaterial)) {
            return true;
        }

        Map<String, MaterialGroupInfo> groups = parseMaterialCategoryGroups();
        for (MaterialGroupInfo group : groups.values()) {
            // 检查需要的材料是否在组内
            if (group.contains(requiredMaterial)) {
                if (!group.getHeaders().isEmpty()) {
                    // 有组头时：组头可以匹配组员
                    // 提供的材料必须是组头，需要的材料可以在组内（包括组头和组员）
                    if (group.isHeader(providedMaterial) && group.contains(requiredMaterial)) {
                        return true;
                    }
                } else {
                    // 没有组头时：组员之间可以互相匹配
                    // 需要的材料和提供的材料都必须在组内
                    if (group.contains(providedMaterial)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检查材料是否在通类匹配组中
     * @param material 材料ID
     * @return 如果材料在任何通类匹配组中返回true
     */
    public static boolean isMaterialInCategoryGroup(String material) {
        Map<String, MaterialGroupInfo> groups = parseMaterialCategoryGroups();
        for (MaterialGroupInfo group : groups.values()) {
            if (group.contains(material)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取材料所属的通类匹配组
     * @param material 材料ID
     * @return 组名列表，如果没有找到返回空列表
     */
    public static List<String> getMaterialCategoryGroups(String material) {
        List<String> result = new ArrayList<>();
        Map<String, MaterialGroupInfo> groups = parseMaterialCategoryGroups();
        for (Map.Entry<String, MaterialGroupInfo> entry : groups.entrySet()) {
            if (entry.getValue().contains(material)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * 获取材料所属的材料组信息
     * @param material 材料ID
     * @return MaterialGroupInfo，如果没有找到返回null
     */
    public static MaterialGroupInfo getMaterialGroup(String material) {
        Map<String, MaterialGroupInfo> groups = parseMaterialCategoryGroups();
        for (MaterialGroupInfo group : groups.values()) {
            if (group.contains(material)) {
                return group;
            }
        }
        return null;
    }
}
