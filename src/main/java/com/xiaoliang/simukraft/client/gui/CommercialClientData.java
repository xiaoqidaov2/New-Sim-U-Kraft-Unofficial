package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class CommercialClientData {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<BlockPos, UUID> hiredEmployeeUuids = new HashMap<>();
    private static final Map<BlockPos, CustomEntity> hiredEmployees = new HashMap<>();
    private static final Map<UUID, String> npcNames = new HashMap<>();
    private static final Map<BlockPos, String> jobTypes = new HashMap<>();
    private static final Map<BlockPos, String> buildingFileNames = new HashMap<>();
    private static final Map<BlockPos, Map<String, CommercialHiredData.StockInfo>> stockData = new HashMap<>();
    private static boolean dataLoaded = false;

    public static class HireInfo {
        public final UUID npcUuid;
        public final String jobType;
        public final String buildingFileName;

        public HireInfo(UUID npcUuid, String jobType, String buildingFileName) {
            this.npcUuid = npcUuid;
            this.jobType = jobType;
            this.buildingFileName = buildingFileName;
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
    }

    public static void loadHiredEmployees(MinecraftServer server) {
        if (server == null) return;

        hiredEmployeeUuids.clear();
        hiredEmployees.clear();
        jobTypes.clear();
        buildingFileNames.clear();

        Map<BlockPos, CommercialHiredData.CommercialHireInfo> loadedData = CommercialHiredData.loadHiredEmployees(server);

        for (Map.Entry<BlockPos, CommercialHiredData.CommercialHireInfo> entry : loadedData.entrySet()) {
            BlockPos commercialPos = entry.getKey();
            CommercialHiredData.CommercialHireInfo info = entry.getValue();
            UUID npcUuid = info.getNpcUuid();
            String jobType = info.getJobType();
            String buildingFileName = info.getBuildingFileName();

            CustomEntity npc = findNPCByUuid(npcUuid, server);
            if (npc != null) {
                hiredEmployeeUuids.put(commercialPos, npcUuid);
                hiredEmployees.put(commercialPos, npc);
                jobTypes.put(commercialPos, jobType);
                buildingFileNames.put(commercialPos, buildingFileName);

                npcNames.put(npcUuid, npc.getFullName());

                npc.setJob(jobType);
                npc.setWorkStatus(WorkStatus.WORKING);
                setupJobItems(npc, jobType, buildingFileName);

                LOGGER.debug("CommercialClientData: 加载商业建筑员工数据，位置={}, NPC UUID={}, 名称={}, 职位={}, 建筑={}", commercialPos, npcUuid, npc.getFullName(), jobType, buildingFileName);
            } else {
                String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                npcNames.put(npcUuid, npcName);
                hiredEmployeeUuids.put(commercialPos, npcUuid);
                hiredEmployees.put(commercialPos, null);
                jobTypes.put(commercialPos, jobType);
                buildingFileNames.put(commercialPos, buildingFileName);
                LOGGER.debug("CommercialClientData: 加载商业建筑员工数据，位置={}, NPC UUID={}, 名称={}, 职位={}, 建筑={} (实体不存在)", commercialPos, npcUuid, npcName, jobType, buildingFileName);
            }
        }

        LOGGER.info("CommercialClientData: 共加载 {} 个商业建筑雇佣数据", hiredEmployees.size());
    }

    private static CustomEntity findNPCByUuid(UUID uuid, MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            for (CustomEntity npc : level.getEntitiesOfClass(CustomEntity.class, new AABB(-30000000, -30000000, -30000000, 30000000, 30000000, 30000000))) {
                if (npc.getUUID().equals(uuid)) {
                    return npc;
                }
            }
        }
        return null;
    }

    private static void setupJobItems(CustomEntity npc, String jobType, String buildingFileName) {
        // 从配置获取手持物品
        CommercialBuildingConfig config = getConfig(buildingFileName);
        if (config != null && config.getHeldItem() != null && !config.getHeldItem().isEmpty()) {
            try {
                net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(safeString(config.getHeldItem()));
                if (loc != null) {
                    net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(loc);
                    if (item != null && item != net.minecraft.world.item.Items.AIR) {
                        npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(nn(item)));
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("CommercialClientData: 无法加载手持物品: {}", config.getHeldItem());
            }
        }
        // 默认手持物品
        npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(nn(Items.GOLD_INGOT)));
    }

    public static void setHiredEmployee(BlockPos commercialPos, CustomEntity npc, String jobType) {
        hiredEmployees.put(commercialPos, npc);
        jobTypes.put(commercialPos, jobType);
        if (npc != null) {
            UUID npcUuid = npc.getUUID();
            hiredEmployeeUuids.put(commercialPos, npcUuid);
            npcNames.put(npcUuid, npc.getFullName());
            LOGGER.debug("CommercialClientData: 设置雇佣员工（客户端），位置={}, NPC UUID={}, 名称={}, 职位={}", commercialPos, npcUuid, npc.getFullName(), jobType);
        }
    }

    public static void setHiredEmployee(BlockPos commercialPos, UUID npcUuid, String jobType) {
        setHiredEmployee(commercialPos, npcUuid, jobType, null);
    }

    public static void setHiredEmployee(BlockPos commercialPos, UUID npcUuid, String jobType, String buildingFileName) {
        var minecraft = Minecraft.getInstance();
        CustomEntity npc = null;

        if (minecraft.level != null) {
            for (var entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof CustomEntity && entity.getUUID().equals(npcUuid)) {
                    npc = (CustomEntity) entity;
                    npcNames.put(npcUuid, npc.getFullName());
                    LOGGER.debug("CommercialClientData: 通过UUID设置雇佣员工，找到NPC实体，名称: {}", npc.getFullName());
                    break;
                }
            }
        }

        hiredEmployeeUuids.put(commercialPos, npcUuid);
        hiredEmployees.put(commercialPos, npc);
        jobTypes.put(commercialPos, jobType);
        if (buildingFileName != null && !buildingFileName.isEmpty()) {
            buildingFileNames.put(commercialPos, buildingFileName);
        }

        if (npc == null) {
            var server = minecraft.getSingleplayerServer();
            if (server != null) {
                String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                npcNames.put(npcUuid, npcName);
                LOGGER.debug("CommercialClientData: 通过UUID设置雇佣员工，未找到NPC实体，从npcdata获取名称: {}", npcName);
            }
        }

        LOGGER.debug("CommercialClientData: 通过UUID设置雇佣员工（客户端）后 - hiredEmployees: {}, hiredEmployeeUuids: {}, jobTypes: {}, npcNames: {}, buildingFileNames: {}", hiredEmployees, hiredEmployeeUuids, jobTypes, npcNames, buildingFileNames);
    }

    public static void setBuildingFileName(BlockPos commercialPos, String buildingFileName) {
        if (buildingFileName != null && !buildingFileName.isEmpty()) {
            buildingFileNames.put(commercialPos, buildingFileName);
            LOGGER.debug("CommercialClientData: 设置建筑文件名，位置={}, 文件名={}", commercialPos, buildingFileName);
        }
    }

    public static String getBuildingFileName(BlockPos commercialPos) {
        return buildingFileNames.get(commercialPos);
    }

    public static void setJobType(BlockPos commercialPos, String jobType) {
        if (jobType != null && !jobType.isEmpty()) {
            jobTypes.put(commercialPos, jobType);
            LOGGER.debug("CommercialClientData: 设置职业类型，位置={}, 职业={}", commercialPos, jobType);
        }
    }

    public static void clearHiredEmployee(BlockPos commercialPos) {
        UUID npcUuid = hiredEmployeeUuids.remove(commercialPos);
        if (npcUuid != null) {
            npcNames.remove(npcUuid);
            LOGGER.debug("CommercialClientData: 清除雇佣员工（客户端），位置={}, NPC UUID={}", commercialPos, npcUuid);
        }
        hiredEmployees.remove(commercialPos);
        jobTypes.remove(commercialPos);
        buildingFileNames.remove(commercialPos);
    }

    public static boolean hasHiredEmployee(BlockPos commercialPos) {
        syncLoadedData();
        return hiredEmployees.containsKey(commercialPos);
    }

    public static CustomEntity getHiredEmployee(BlockPos commercialPos) {
        syncLoadedData();
        return hiredEmployees.get(commercialPos);
    }

    public static UUID getHiredEmployeeUUID(BlockPos commercialPos) {
        syncLoadedData();
        return hiredEmployeeUuids.get(commercialPos);
    }

    public static String getJobType(BlockPos commercialPos) {
        syncLoadedData();
        return jobTypes.get(commercialPos);
    }

    public static Map<BlockPos, CustomEntity> getAllHiredEmployees() {
        return new HashMap<>(hiredEmployees);
    }

    public static Map<BlockPos, HireInfo> getAllHiredEmployeeUuids() {
        syncLoadedData();
        Map<BlockPos, HireInfo> result = new HashMap<>();
        for (Map.Entry<BlockPos, UUID> entry : hiredEmployeeUuids.entrySet()) {
            BlockPos pos = entry.getKey();
            UUID npcUuid = entry.getValue();
            String jobType = jobTypes.getOrDefault(pos, "shopkeeper");
            String buildingFileName = buildingFileNames.getOrDefault(pos, "");
            result.put(pos, new HireInfo(npcUuid, jobType, buildingFileName));
        }
        return result;
    }

    public static Map<BlockPos, String> getAllJobTypes() {
        return new HashMap<>(jobTypes);
    }

    public static void syncLoadedData() {
        var minecraft = Minecraft.getInstance();
        var server = minecraft.getSingleplayerServer();

        // 如果数据未加载或数据为空，则重新加载
        if (server != null && (!dataLoaded || hiredEmployeeUuids.isEmpty())) {
            // 清空旧数据
            hiredEmployeeUuids.clear();
            hiredEmployees.clear();
            jobTypes.clear();
            buildingFileNames.clear();
            npcNames.clear();
            stockData.clear();

            // 加载雇佣数据
            Map<BlockPos, CommercialHiredData.CommercialHireInfo> loadedData = CommercialHiredData.loadHiredEmployees(server);
            for (Map.Entry<BlockPos, CommercialHiredData.CommercialHireInfo> entry : loadedData.entrySet()) {
                BlockPos commercialPos = entry.getKey();
                CommercialHiredData.CommercialHireInfo info = entry.getValue();
                UUID npcUuid = info.getNpcUuid();
                String jobType = info.getJobType();
                String buildingFileName = info.getBuildingFileName();

                hiredEmployeeUuids.put(commercialPos, npcUuid);
                jobTypes.put(commercialPos, jobType);
                buildingFileNames.put(commercialPos, buildingFileName);

                if (npcUuid != null) {
                    CustomEntity npc = findNPCByUuid(npcUuid, server);
                    if (npc != null) {
                        hiredEmployees.put(commercialPos, npc);
                        npcNames.put(npcUuid, npc.getFullName());
                    } else {
                        String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                        npcNames.put(npcUuid, npcName);
                        hiredEmployees.put(commercialPos, null);
                    }
                }
            }

            // 加载库存数据
            CommercialHiredData.loadStockData(server);
            for (BlockPos pos : CommercialHiredData.getAllStockPositions()) {
                Map<String, CommercialHiredData.StockInfo> stock = CommercialHiredData.getAllStockForPos(pos);
                if (!stock.isEmpty()) {
                    stockData.put(pos, new HashMap<>(stock));
                }
            }

            dataLoaded = true;
            LOGGER.info("CommercialClientData: 已加载 {} 个商业建筑员工数据和 {} 个库存数据", hiredEmployeeUuids.size(), stockData.size());
        }

        // 同步实体引用
        if (server != null && !hiredEmployeeUuids.isEmpty()) {
            for (Map.Entry<BlockPos, UUID> entry : hiredEmployeeUuids.entrySet()) {
                BlockPos pos = entry.getKey();
                UUID npcUuid = entry.getValue();

                if (hiredEmployees.get(pos) == null && npcUuid != null) {
                    CustomEntity npc = findNPCByUuid(npcUuid, server);
                    if (npc != null) {
                        hiredEmployees.put(pos, npc);
                        npcNames.put(npcUuid, npc.getFullName());
                        String jobType = jobTypes.get(pos);
                        String buildingFileName = buildingFileNames.get(pos);
                        if (jobType != null && jobType.equals(npc.getJob())) {
                            npc.setWorkStatus(WorkStatus.WORKING);
                            setupJobItems(npc, jobType, buildingFileName);
                        }
                    }
                }
            }
        }
    }

    /**
     * 重置数据加载状态（在客户端断开连接时调用）
     */
    public static void resetDataLoaded() {
        dataLoaded = false;
        hiredEmployeeUuids.clear();
        hiredEmployees.clear();
        jobTypes.clear();
        buildingFileNames.clear();
        npcNames.clear();
        stockData.clear();
        LOGGER.debug("CommercialClientData: 数据已重置");
    }

    public static void fireEmployee(CustomEntity npc) {
        UUID npcUuid = npc.getUUID();
        fireEmployeeByUUID(npcUuid);
    }

    public static void fireEmployeeByUUID(UUID npcUuid) {
        Set<BlockPos> positionsToClear = new HashSet<>();

        for (var entry : hiredEmployeeUuids.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                positionsToClear.add(entry.getKey());
            }
        }

        for (BlockPos commercialPos : positionsToClear) {
            clearHiredEmployee(commercialPos);
        }

        npcNames.remove(npcUuid);

        LOGGER.debug("CommercialClientData: 通过UUID解雇员工，NPC UUID={}", npcUuid);
    }

    public static Map<String, CommercialHiredData.StockInfo> getStock(BlockPos pos) {
        return stockData.getOrDefault(pos, new HashMap<>());
    }

    public static CommercialHiredData.StockInfo getStockItem(BlockPos pos, String itemId) {
        Map<String, CommercialHiredData.StockInfo> posStock = stockData.get(pos);
        if (posStock != null) {
            return posStock.get(itemId);
        }
        return null;
    }

    public static void setStock(BlockPos pos, Map<String, CommercialHiredData.StockInfo> stock) {
        stockData.put(pos, stock);
    }

    public static void updateStock(BlockPos pos, String itemId, CommercialHiredData.StockInfo stockInfo) {
        stockData.computeIfAbsent(pos, k -> new HashMap<>()).put(itemId, stockInfo);
    }

    public static void clearStock(BlockPos pos) {
        stockData.remove(pos);
    }

    public static boolean hasStockData(BlockPos pos) {
        return stockData.containsKey(pos);
    }

    public static String getJobName(String buildingFileName) {
        if (buildingFileName == null || buildingFileName.isEmpty()) {
            return null;
        }
        CommercialBuildingConfig config = getConfig(buildingFileName);
        if (config != null) {
            return config.getJobName();
        }
        return null;
    }

    public static String getBuildingName(String buildingFileName) {
        if (buildingFileName == null || buildingFileName.isEmpty()) {
            return null;
        }
        CommercialBuildingConfig config = getConfig(buildingFileName);
        if (config != null) {
            return config.getBuildingName();
        }
        return null;
    }

    public static String getJobNameByJobType(String jobType) {
        if (jobType == null || jobType.isEmpty()) {
            return null;
        }
        // 遍历所有配置，查找匹配的 jobType
        for (CommercialBuildingConfig config : com.xiaoliang.simukraft.building.CommercialBuildingManager.getAllConfigs()) {
            if (jobType.equals(config.getJobType())) {
                return config.getJobName();
            }
        }
        return null;
    }

    public static String getJobNameByJobType(String jobType, String buildingFileName) {
        if (jobType == null || jobType.isEmpty()) {
            return null;
        }
        // 优先从指定建筑配置中查找
        if (buildingFileName != null && !buildingFileName.isEmpty()) {
            CommercialBuildingConfig config = getConfig(buildingFileName);
            if (config != null && jobType.equals(config.getJobType())) {
                return config.getJobName();
            }
        }
        // 如果没有指定建筑或配置不匹配，遍历所有配置
        return getJobNameByJobType(jobType);
    }

    public static CommercialBuildingConfig getConfig(String buildingFileName) {
        if (buildingFileName == null || buildingFileName.isEmpty()) {
            return null;
        }
        // 移除.sk扩展名（如果有）并转为小写
        String buildingId = buildingFileName.replace(".sk", "").toLowerCase();
        return com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(buildingId);
    }

    public static CommercialBuildingConfig.ShopMode getShopMode(String buildingFileName) {
        CommercialBuildingConfig config = getConfig(buildingFileName);
        if (config != null) {
            return config.getShopMode();
        }
        return CommercialBuildingConfig.ShopMode.NPC_SELL;
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(@Nullable String value) {
        return nn(value);
    }
}
