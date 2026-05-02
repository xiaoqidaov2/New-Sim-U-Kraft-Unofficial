package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.employment.bridge.EmploymentLegacyBridge;
import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.world.CityPermissionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 请求雇员列表数据包
 * 客户端请求服务器发送当前城市的雇佣数据
 * 从V2雇佣存储中读取所有有工作的NPC
 */
@SuppressWarnings( "unused")
public class EmployeeListRequestPacket {

    public EmployeeListRequestPacket() {
    }

    public EmployeeListRequestPacket(FriendlyByteBuf buf) {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            MinecraftServer server = player.getServer();
            if (server == null) return;

            // 使用权限管理器获取玩家城市ID和检查权限（使用玩家名）
            CityPermissionManager permManager = CityPermissionManager.getInstance();
            String playerName = player.getName().getString();
            UUID playerCityId = permManager.getPlayerCityId(player.serverLevel(), playerName);

            if (playerCityId == null || !permManager.canManageCity(player.serverLevel(), playerName)) {
                // 玩家没有城市或没有管理权限，返回空列表
                NetworkManager.sendToPlayer(new EmployeeListResponsePacket(new HashMap<>()), player);
                return;
            }

            // 从V2雇佣存储读取所有有工作的NPC
            Map<UUID, EmployeeData> employees = loadEmployeesFromV2Storage(server, playerCityId);

            // 发送响应给客户端
            NetworkManager.sendToPlayer(new EmployeeListResponsePacket(employees), player);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 从V2雇佣存储加载所有有工作的NPC（排除空闲的）
     */
    private static Map<UUID, EmployeeData> loadEmployeesFromV2Storage(MinecraftServer server, UUID playerCityId) {
        Map<UUID, EmployeeData> employees = new HashMap<>();

        try {
            // 从V2存储获取所有已雇佣的NPC
            Map<UUID, EmploymentAssignment> hiredNPCs = EmploymentLegacyBridge.loadAllHiredNPCs(server);

            for (Map.Entry<UUID, EmploymentAssignment> entry : hiredNPCs.entrySet()) {
                UUID npcUuid = entry.getKey();
                EmploymentAssignment assignment = entry.getValue();

                // 检查NPC是否属于玩家城市
                if (!isNPCInCity(server, npcUuid, playerCityId)) {
                    continue;
                }

                BlockPos workplacePos = assignment.workplacePos();
                String workplaceType;
                String job;
                String buildingFileName = null;

                // 根据工作方块类型确定workplaceType和job
                if (assignment.workBlockType() == com.xiaoliang.simukraft.employment.domain.WorkBlockType.INDUSTRIAL_CONTROL_BOX) {
                    workplaceType = "industrial";
                    // 从IndustrialHiredData获取正确的jobType
                    String actualJobType = getIndustrialJobType(server, npcUuid);
                    job = actualJobType != null ? actualJobType : "unknown";
                    buildingFileName = getIndustrialBuildingFileName(server, npcUuid, job);
                } else if (assignment.workBlockType() == com.xiaoliang.simukraft.employment.domain.WorkBlockType.COMMERCIAL_CONTROL_BOX) {
                    workplaceType = "commercial";
                    // 从CommercialHiredData获取正确的jobType
                    String actualJobType = getCommercialJobType(server, npcUuid);
                    job = actualJobType != null ? actualJobType : "unknown";
                    buildingFileName = getCommercialBuildingFileName(server, npcUuid, job);
                } else {
                    // 对于其他类型，使用原来的逻辑
                    job = assignment.jobType() != null ? assignment.jobType().name().toLowerCase() : "unknown";
                    workplaceType = getWorkplaceTypeByJob(job);
                }

                employees.put(npcUuid, new EmployeeData(npcUuid, job, workplacePos, workplaceType, buildingFileName));
            }

        } catch (Exception e) {
            Simukraft.LOGGER.error("[EmployeeListRequestPacket] Failed to load job data from V2 storage", e);
        }

        return employees;
    }

    /**
     * 从商业建筑数据获取正确的jobType
     */
    private static String getCommercialJobType(MinecraftServer server, UUID npcUuid) {
        var employees = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        for (var entry : employees.entrySet()) {
            com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid)) {
                return hireInfo.getJobType();
            }
        }
        return null;
    }

    /**
     * 从工业建筑数据获取正确的jobType
     */
    private static String getIndustrialJobType(MinecraftServer server, UUID npcUuid) {
        var employees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : employees.entrySet()) {
            com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid)) {
                return hireInfo.getJobType();
            }
        }
        return null;
    }

    /**
     * 获取NPC的工作地点
     */
    private static BlockPos getWorkplacePos(MinecraftServer server, UUID npcUuid, String job) {
        // 根据职业从各工作方块数据查找工作地点
        // 首先检查是否是商业建筑职业（统一使用CommercialBuildingManager）
        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job)) {
            return findWorkplaceFromCommercial(server, npcUuid, job);
        }
        
        BlockPos pos = switch (job) {
            case "builder", "planner" -> findWorkplaceFromBuildBox(server, npcUuid);
            case "shepherd" -> findWorkplaceFromWoolFarm(server, npcUuid);
            case "butcher" -> findWorkplaceFromBeefFarm(server, npcUuid);
            case "farmer" -> findWorkplaceFromFarmland(server, npcUuid);
            case "warehouse_manager" -> findWorkplaceFromWarehouse(server, npcUuid);
            default -> null; // 未知类型，需要进一步判断
        };
        
        // 如果找到已知类型的工作地点，直接返回
        if (pos != null) {
            return pos;
        }
        
        // 检查是否是工业建筑职业
        var industrialConfigs = com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfigsByJobType(job);
        if (!industrialConfigs.isEmpty()) {
            return findWorkplaceFromIndustrial(server, npcUuid, job);
        }
        
        // 默认为工业建筑查找
        return findWorkplaceFromIndustrial(server, npcUuid, job);
    }

    /**
     * 从商业建筑数据中查找工作地点（通用）
     */
    private static BlockPos findWorkplaceFromCommercial(MinecraftServer server, UUID npcUuid, String jobType) {
        var employees = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        for (var entry : employees.entrySet()) {
            com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid) && jobType.equals(hireInfo.getJobType())) {
                return entry.getKey();
            }
        }
        return BlockPos.ZERO;
    }

    /**
     * 从工业建筑数据中查找工作地点（通用）
     */
    private static BlockPos findWorkplaceFromIndustrial(MinecraftServer server, UUID npcUuid, String jobType) {
        var employees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : employees.entrySet()) {
            com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid) && jobType.equals(hireInfo.getJobType())) {
                return entry.getKey();
            }
        }
        return BlockPos.ZERO;
    }

    /**
     * 获取工业建筑的配置文件名
     */
    private static String getIndustrialBuildingFileName(MinecraftServer server, UUID npcUuid, String jobType) {
        var employees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : employees.entrySet()) {
            com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid) && jobType.equals(hireInfo.getJobType())) {
                return hireInfo.getBuildingFileName();
            }
        }
        return null;
    }

    private static BlockPos findWorkplaceFromBuildBox(MinecraftServer server, UUID npcUuid) {
        var builders = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server);
        for (var entry : builders.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        var planners = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(server);
        for (var entry : planners.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        return BlockPos.ZERO;
    }

    private static BlockPos findWorkplaceFromWoolFarm(MinecraftServer server, UUID npcUuid) {
        var employees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : employees.entrySet()) {
            com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid) && "shepherd".equals(hireInfo.getJobType())) {
                return entry.getKey();
            }
        }
        return BlockPos.ZERO;
    }

    private static BlockPos findWorkplaceFromBeefFarm(MinecraftServer server, UUID npcUuid) {
        var employees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : employees.entrySet()) {
            com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid) && "butcher".equals(hireInfo.getJobType())) {
                return entry.getKey();
            }
        }
        return BlockPos.ZERO;
    }

    private static BlockPos findWorkplaceFromFarmland(MinecraftServer server, UUID npcUuid) {
        com.xiaoliang.simukraft.world.FarmlandHiredData.loadAllFarmlandData(server);
        var farmers = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        for (var entry : farmers.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        return BlockPos.ZERO;
    }



    private static BlockPos findWorkplaceFromWarehouse(MinecraftServer server, UUID npcUuid) {
        BlockPos pos = com.xiaoliang.simukraft.world.LogisticsHiredData.findByNpcUuid(server, npcUuid);
        return pos != null ? pos : BlockPos.ZERO;
    }

    /**
     * 检查NPC是否属于指定城市
     */
    private static boolean isNPCInCity(MinecraftServer server, UUID npcUuid, UUID cityId) {
        CustomEntity npc = findNPCByUuid(server, npcUuid);
        if (npc != null) {
            String npcCityIdStr = npc.getCityIdString();
            if (npcCityIdStr != null && !npcCityIdStr.isEmpty()) {
                try {
                    UUID npcCityId = UUID.fromString(npcCityIdStr);
                    return npcCityId.equals(cityId);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 根据UUID查找NPC实体
     */
    private static CustomEntity findNPCByUuid(MinecraftServer server, UUID uuid) {
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof CustomEntity npc && entity.getUUID().equals(uuid)) {
                    return npc;
                }
            }
        }
        return null;
    }

    /**
     * 雇员数据内部类
     */
    public static class EmployeeData {
        public final UUID uuid;
        public final String job;
        public final net.minecraft.core.BlockPos workplacePos;
        public final String workplaceType;
        public final String buildingFileName; // 工业建筑配置文件名

        public EmployeeData(UUID uuid, String job, net.minecraft.core.BlockPos workplacePos, String workplaceType) {
            this(uuid, job, workplacePos, workplaceType, null);
        }

        public EmployeeData(UUID uuid, String job, net.minecraft.core.BlockPos workplacePos, String workplaceType, String buildingFileName) {
            this.uuid = uuid;
            this.job = job;
            this.workplacePos = workplacePos;
            this.workplaceType = workplaceType;
            this.buildingFileName = buildingFileName;
        }
    }

    /**
     * 根据职业获取工作地点类型
     */
    private static String getWorkplaceTypeByJob(String job) {
        // 首先检查是否是商业建筑职业（统一使用CommercialBuildingManager）
        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job)) {
            return "commercial";
        }
        
        String type = switch (job) {
            case "builder", "planner" -> "build_box";
            case "shepherd" -> "wool_farm";
            case "butcher" -> "beef_farm";
            case "farmer" -> "farmland";
            case "warehouse_manager" -> "warehouse";
            default -> null; // 未知类型，需要进一步判断
        };
        
        // 如果已知类型，直接返回
        if (type != null) {
            return type;
        }
        
        // 检查是否是工业建筑职业
        var industrialConfigs = com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfigsByJobType(job);
        if (!industrialConfigs.isEmpty()) {
            return "industrial";
        }
        
        // 默认为工业建筑
        return "industrial";
    }

    /**
     * 检查是否为商业建筑职业
     * 统一使用CommercialBuildingManager检查，避免硬编码
     */
    private static boolean isCommercialJob(String job) {
        return com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job);
    }

    /**
     * 获取商业建筑的配置文件名
     */
    private static String getCommercialBuildingFileName(MinecraftServer server, UUID npcUuid, String jobType) {
        var employees = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        for (var entry : employees.entrySet()) {
            com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid) && jobType.equals(hireInfo.getJobType())) {
                return hireInfo.getBuildingFileName();
            }
        }
        return null;
    }
}
