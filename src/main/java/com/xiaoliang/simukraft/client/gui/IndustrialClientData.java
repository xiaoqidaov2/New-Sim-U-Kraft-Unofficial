package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.world.IndustrialHiredData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

@SuppressWarnings("null")
public class IndustrialClientData {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private static final Map<BlockPos, UUID> hiredEmployeeUuids = new HashMap<>();
    private static final Map<BlockPos, CustomEntity> hiredEmployees = new HashMap<>();
    private static final Map<UUID, String> npcNames = new HashMap<>();
    private static final Map<BlockPos, String> jobTypes = new HashMap<>();
    private static final Map<BlockPos, String> buildingFileNames = new HashMap<>();
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

        Map<BlockPos, IndustrialHiredData.IndustrialHireInfo> loadedData = IndustrialHiredData.loadHiredEmployees(server);

        for (Map.Entry<BlockPos, IndustrialHiredData.IndustrialHireInfo> entry : loadedData.entrySet()) {
            BlockPos industrialPos = entry.getKey();
            IndustrialHiredData.IndustrialHireInfo info = entry.getValue();
            UUID npcUuid = info.getNpcUuid();
            String jobType = info.getJobType();

            CustomEntity npc = findNPCByUuid(npcUuid, server);
            if (npc != null) {
                hiredEmployeeUuids.put(industrialPos, npcUuid);
                hiredEmployees.put(industrialPos, npc);
                jobTypes.put(industrialPos, jobType);

                npcNames.put(npcUuid, npc.getFullName());

                npc.setJob(jobType);
                npc.setWorkStatus(WorkStatus.WORKING);
                setupJobItems(npc, jobType);

                LOGGER.debug("IndustrialClientData: 加载工业建筑员工数据，位置={}, NPC UUID={}, 名称={}, 职位={}", industrialPos, npcUuid, npc.getFullName(), jobType);
            } else {
                String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                npcNames.put(npcUuid, npcName);
                hiredEmployeeUuids.put(industrialPos, npcUuid);
                hiredEmployees.put(industrialPos, null);
                jobTypes.put(industrialPos, jobType);
                LOGGER.debug("IndustrialClientData: 加载工业建筑员工数据，位置={}, NPC UUID={}, 名称={}, 职位={} (实体不存在)", industrialPos, npcUuid, npcName, jobType);
            }
        }

        LOGGER.info("IndustrialClientData: 共加载 {} 个工业建筑雇佣数据", hiredEmployees.size());
    }

    private static CustomEntity findNPCByUuid(UUID uuid, MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (CustomEntity npc : level.getEntitiesOfClass(CustomEntity.class, new AABB(-30000000, -30000000, -30000000, 30000000, 30000000, 30000000))) {
                if (npc.getUUID().equals(uuid)) {
                    return npc;
                }
            }
        }
        return null;
    }

    private static void setupJobItems(CustomEntity npc, String jobType) {
        switch (jobType) {
            case "engineer":
                npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                break;
            case "technician":
                npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.REDSTONE));
                break;
            case "worker":
                npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
                break;
            default:
                npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT));
                break;
        }
    }

    public static void setHiredEmployee(BlockPos industrialPos, CustomEntity npc, String jobType) {
        hiredEmployees.put(industrialPos, npc);
        jobTypes.put(industrialPos, jobType);
        if (npc != null) {
            UUID npcUuid = npc.getUUID();
            hiredEmployeeUuids.put(industrialPos, npcUuid);
            npcNames.put(npcUuid, npc.getFullName());
            // 注意：不再自动保存到服务器，服务器端的保存由 NPCWorkStatusPacket 处理
            LOGGER.debug("IndustrialClientData: 设置雇佣员工（客户端），位置={}, NPC UUID={}, 名称={}, 职位={}", industrialPos, npcUuid, npc.getFullName(), jobType);
        }
    }

    public static void setHiredEmployee(BlockPos industrialPos, UUID npcUuid, String jobType) {
        setHiredEmployee(industrialPos, npcUuid, jobType, null);
    }

    public static void setHiredEmployee(BlockPos industrialPos, UUID npcUuid, String jobType, String buildingFileName) {
        var minecraft = Minecraft.getInstance();
        CustomEntity npc = null;

        if (minecraft.level != null) {
            for (var entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof CustomEntity && entity.getUUID().equals(npcUuid)) {
                    npc = (CustomEntity) entity;
                    npcNames.put(npcUuid, npc.getFullName());
                    LOGGER.debug("IndustrialClientData: 通过UUID设置雇佣员工，找到NPC实体，名称: {}", npc.getFullName());
                    break;
                }
            }
        }

        hiredEmployeeUuids.put(industrialPos, npcUuid);
        hiredEmployees.put(industrialPos, npc);
        jobTypes.put(industrialPos, jobType);
        if (buildingFileName != null && !buildingFileName.isEmpty()) {
            buildingFileNames.put(industrialPos, buildingFileName);
        }

        if (npc == null) {
            var server = minecraft.getSingleplayerServer();
            if (server != null) {
                String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                npcNames.put(npcUuid, npcName);
                LOGGER.debug("IndustrialClientData: 通过UUID设置雇佣员工，未找到NPC实体，从npcdata获取名称: {}", npcName);
            }
        }

        // 注意：不再自动保存到服务器，服务器端的保存由 NPCWorkStatusPacket 处理
        LOGGER.debug("IndustrialClientData: 通过UUID设置雇佣员工（客户端）后 - hiredEmployees: {}, hiredEmployeeUuids: {}, jobTypes: {}, npcNames: {}, buildingFileNames: {}", hiredEmployees, hiredEmployeeUuids, jobTypes, npcNames, buildingFileNames);
    }

    public static void setBuildingFileName(BlockPos industrialPos, String buildingFileName) {
        if (buildingFileName != null && !buildingFileName.isEmpty()) {
            buildingFileNames.put(industrialPos, buildingFileName);
            LOGGER.debug("IndustrialClientData: 设置建筑文件名，位置={}, 文件名={}", industrialPos, buildingFileName);
        }
    }

    public static String getBuildingFileName(BlockPos industrialPos) {
        return buildingFileNames.get(industrialPos);
    }

    public static void clearHiredEmployee(BlockPos industrialPos) {
        UUID npcUuid = hiredEmployeeUuids.remove(industrialPos);
        if (npcUuid != null) {
            npcNames.remove(npcUuid);
            LOGGER.debug("IndustrialClientData: 清除雇佣员工（客户端），位置={}, NPC UUID={}", industrialPos, npcUuid);
        }
        hiredEmployees.remove(industrialPos);
        jobTypes.remove(industrialPos);
        buildingFileNames.remove(industrialPos);
        // 注意：不再自动保存到服务器，服务器端的保存由 NPCWorkStatusPacket 处理
    }

    public static boolean hasHiredEmployee(BlockPos industrialPos) {
        syncLoadedData();
        return hiredEmployees.containsKey(industrialPos);
    }

    public static CustomEntity getHiredEmployee(BlockPos industrialPos) {
        syncLoadedData();
        return hiredEmployees.get(industrialPos);
    }

    public static UUID getHiredEmployeeUUID(BlockPos industrialPos) {
        syncLoadedData();
        return hiredEmployeeUuids.get(industrialPos);
    }

    public static String getJobType(BlockPos industrialPos) {
        syncLoadedData();
        return jobTypes.get(industrialPos);
    }

    public static Map<BlockPos, CustomEntity> getAllHiredEmployees() {
        return new HashMap<>(hiredEmployees);
    }

    public static Map<BlockPos, HireInfo> getAllHiredEmployeeUuids() {
        Map<BlockPos, HireInfo> result = new HashMap<>();
        for (Map.Entry<BlockPos, UUID> entry : hiredEmployeeUuids.entrySet()) {
            BlockPos pos = entry.getKey();
            UUID npcUuid = entry.getValue();
            String jobType = jobTypes.getOrDefault(pos, "worker");
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

        if (!dataLoaded && server != null) {
            Map<BlockPos, IndustrialHiredData.IndustrialHireInfo> loadedData = IndustrialHiredData.loadHiredEmployees(server);
            for (Map.Entry<BlockPos, IndustrialHiredData.IndustrialHireInfo> entry : loadedData.entrySet()) {
                BlockPos industrialPos = entry.getKey();
                IndustrialHiredData.IndustrialHireInfo info = entry.getValue();
                UUID npcUuid = info.getNpcUuid();
                String jobType = info.getJobType();

                if (!hiredEmployeeUuids.containsKey(industrialPos)) {
                    hiredEmployeeUuids.put(industrialPos, npcUuid);
                    jobTypes.put(industrialPos, jobType);

                    if (npcUuid != null) {
                        CustomEntity npc = findNPCByUuid(npcUuid, server);
                        if (npc != null) {
                            hiredEmployees.put(industrialPos, npc);
                            npcNames.put(npcUuid, npc.getFullName());
                        } else {
                            String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                            npcNames.put(npcUuid, npcName);
                            hiredEmployees.put(industrialPos, null);
                        }
                    }
                }
            }
            dataLoaded = true;
        }

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
                        if (jobType != null && jobType.equals(npc.getJob())) {
                            npc.setWorkStatus(WorkStatus.WORKING);
                            setupJobItems(npc, jobType);
                        }
                    } else {
                        String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                        npcNames.put(npcUuid, npcName);
                        hiredEmployees.put(pos, null);
                        LOGGER.debug("IndustrialClientData: 重新同步员工数据，位置={}, 名称={} (实体不存在)", pos, npcName);
                    }
                }
            }
        }
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

        for (BlockPos industrialPos : positionsToClear) {
            clearHiredEmployee(industrialPos);
        }

        npcNames.remove(npcUuid);

        LOGGER.debug("IndustrialClientData: 通过UUID解雇员工，NPC UUID={}", npcUuid);
    }

    /**
     * 从工业建筑配置文件中获取职业名称
     * @param buildingFileName 建筑配置文件名（如 "mill"）
     * @return 职业名称，如果找不到则返回 null
     */
    public static String getJobName(String buildingFileName) {
        if (buildingFileName == null || buildingFileName.isEmpty()) {
            return null;
        }
        // 从 IndustrialBuildingManager 获取配置
        com.xiaoliang.simukraft.building.IndustrialBuildingConfig config = 
            com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfig(buildingFileName);
        if (config != null) {
            return config.getJobName();
        }
        return null;
    }

    /**
     * 从工业建筑配置文件中获取建筑名称
     * @param buildingFileName 建筑配置文件名（如 "mill"）
     * @return 建筑名称，如果找不到则返回 null
     */
    public static String getBuildingName(String buildingFileName) {
        if (buildingFileName == null || buildingFileName.isEmpty()) {
            return null;
        }
        // 从 IndustrialBuildingManager 获取配置
        com.xiaoliang.simukraft.building.IndustrialBuildingConfig config = 
            com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfig(buildingFileName);
        if (config != null) {
            return config.getBuildingName();
        }
        return null;
    }

    /**
     * 根据职业类型（jobType）从工业建筑配置中获取职业显示名称
     * @param jobType 职业类型（如 "miller"）
     * @return 职业显示名称（如 "磨坊工人"），如果找不到则返回 null
     */
    public static String getJobNameByJobType(String jobType) {
        if (jobType == null || jobType.isEmpty()) {
            return null;
        }
        // 从 IndustrialBuildingManager 查找对应职业类型的配置
        java.util.List<com.xiaoliang.simukraft.building.IndustrialBuildingConfig> configs = 
            com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfigsByJobType(jobType);
        if (!configs.isEmpty()) {
            return configs.get(0).getJobName();
        }
        return null;
    }
}
