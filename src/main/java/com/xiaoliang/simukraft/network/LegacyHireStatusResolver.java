package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import com.xiaoliang.simukraft.employment.service.LegacyJobTypeMapper;
import com.xiaoliang.simukraft.world.BuildBoxHiredData;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import com.xiaoliang.simukraft.world.IndustrialHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

@SuppressWarnings("null")
final class LegacyHireStatusResolver {
    private LegacyHireStatusResolver() {
    }

    static WorkBlockHireStatus resolveWorkBlockStatus(MinecraftServer server, ServerPlayer player, BlockPos workBlockPos, String workBlockType) {
        String dimensionId = player.serverLevel().dimension().location().toString();
        var service = EmploymentServices.get(server);

        // 对于商业建筑，优先从 CommercialHiredData 读取原始 jobType（支持自定义职业）
        // 检查是否是商业建筑类型（从JSON配置或通用类型）
        boolean isCommercial = "commercial".equals(workBlockType) || 
            com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(workBlockType) != null;
        if (isCommercial) {
            // 从控制盒数据文件读取建筑文件名（即使未雇佣也需要）
            String buildingFileName = readBuildingFileNameFromServer(server, workBlockPos);
            var employment = service.findByWorkplace(dimensionId, workBlockPos);
            if (employment.isPresent()) {
                var assignment = employment.get();
                String jobTypeFromConfig = readJobTypeFromCommercialConfig(server, workBlockPos, buildingFileName);
                String legacyJobType = (jobTypeFromConfig != null && !jobTypeFromConfig.isBlank())
                        ? jobTypeFromConfig
                        : LegacyJobTypeMapper.toLegacy(assignment.jobType());
                return new WorkBlockHireStatus(
                        assignment.npcUuid(),
                        resolveNpcName(server, assignment.npcUuid()),
                        legacyJobType,
                        buildingFileName
                );
            }

            Map<BlockPos, CommercialHiredData.CommercialHireInfo> commercialData = CommercialHiredData.loadHiredEmployees(server);
            CommercialHiredData.CommercialHireInfo commercialInfo = commercialData.get(workBlockPos);

            if (commercialInfo != null) {
                // 如果雇佣数据中有建筑文件名，优先使用
                String infoBuildingFileName = commercialInfo.getBuildingFileName();
                if (infoBuildingFileName != null && !infoBuildingFileName.isEmpty()) {
                    buildingFileName = infoBuildingFileName;
                }
                return new WorkBlockHireStatus(
                    commercialInfo.getNpcUuid(),
                    resolveNpcName(server, commercialInfo.getNpcUuid()),
                    commercialInfo.getJobType(),  // 使用原始的 jobType
                    buildingFileName
                );
            }

            // 未雇佣时，从建筑配置文件读取 jobType
            String jobTypeFromConfig = readJobTypeFromCommercialConfig(server, workBlockPos, buildingFileName);
            return new WorkBlockHireStatus(null, null, jobTypeFromConfig, buildingFileName);
        }

        // 对于工业建筑，从控制盒数据文件读取建筑文件名
        String industrialBuildingFileName = "";
        if ("wool_farm".equals(workBlockType) || "beef_farm".equals(workBlockType) || "industrial".equals(workBlockType)) {
            industrialBuildingFileName = readIndustrialBuildingFileNameFromServer(server, workBlockPos);
        }

        var employment = service.findByWorkplace(dimensionId, workBlockPos);
        if (employment.isPresent()) {
            var assignment = employment.get();
            return new WorkBlockHireStatus(
                    assignment.npcUuid(),
                    resolveNpcName(server, assignment.npcUuid()),
                    LegacyJobTypeMapper.toLegacy(assignment.jobType()),
                    industrialBuildingFileName
            );
        }

        UUID employeeUuid = null;
        String legacyJobType = "worker";

        switch (workBlockType) {
            case "wool_farm", "beef_farm", "industrial" -> {
                Map<BlockPos, IndustrialHiredData.IndustrialHireInfo> industrialData = IndustrialHiredData.loadHiredEmployees(server);
                IndustrialHiredData.IndustrialHireInfo info = industrialData.get(workBlockPos);
                if (info != null) {
                    employeeUuid = info.getNpcUuid();
                    legacyJobType = info.getJobType();
                    // 如果IndustrialHireInfo中有建筑文件名，使用它
                    if (info.getBuildingFileName() != null && !info.getBuildingFileName().isEmpty()) {
                        industrialBuildingFileName = info.getBuildingFileName();
                    }
                }
            }
            default -> {
                return new WorkBlockHireStatus(null, null, "worker", industrialBuildingFileName);
            }
        }

        if (employeeUuid == null) {
            return new WorkBlockHireStatus(null, null, legacyJobType, industrialBuildingFileName);
        }

        return new WorkBlockHireStatus(employeeUuid, resolveNpcName(server, employeeUuid), legacyJobType, industrialBuildingFileName);
    }

    static BuildBoxHireStatus resolveBuildBoxStatus(MinecraftServer server, ServerPlayer player, BlockPos buildBoxPos) {
        String dimensionId = player.serverLevel().dimension().location().toString();
        var service = EmploymentServices.get(server);
        UUID builderUuid = service.findByWorkplaceAndJob(dimensionId, buildBoxPos, JobType.BUILDER)
                .map(com.xiaoliang.simukraft.employment.domain.EmploymentAssignment::npcUuid)
                .orElse(null);
        UUID plannerUuid = service.findByWorkplaceAndJob(dimensionId, buildBoxPos, JobType.PLANNER)
                .map(com.xiaoliang.simukraft.employment.domain.EmploymentAssignment::npcUuid)
                .orElse(null);

        Map<BlockPos, UUID> hiredBuilders = BuildBoxHiredData.loadHiredBuilders(server);
        Map<BlockPos, UUID> hiredPlanners = BuildBoxHiredData.loadHiredPlanners(server);

        if (builderUuid == null) {
            builderUuid = hiredBuilders.get(buildBoxPos);
            if (builderUuid != null) {
            }
        }
        if (plannerUuid == null) {
            plannerUuid = hiredPlanners.get(buildBoxPos);
            if (plannerUuid != null) {
            }
        }

        return new BuildBoxHireStatus(
                builderUuid,
                plannerUuid,
                resolveNpcName(server, builderUuid),
                resolveNpcName(server, plannerUuid)
        );
    }

    // Query path intentionally does not backfill legacy -> v2 to avoid recreating fired relationships.

    private static String resolveNpcName(MinecraftServer server, UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        var npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);
        if (npc != null) {
            return npc.getFullName();
        }
        return com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
    }

    /**
     * 从服务器端控制盒数据文件读取建筑文件名
     */
    private static String readBuildingFileNameFromServer(MinecraftServer server, BlockPos pos) {
        try {
            java.nio.file.Path worldPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path commercialDir = worldPath.resolve("simukraft").resolve("commercial");
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            java.nio.file.Path skFile = commercialDir.resolve(fileName);

            if (java.nio.file.Files.exists(skFile)) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(skFile, java.nio.charset.Charset.forName("UTF-8"));
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("building_file_name:")) {
                        return line.substring(19).trim();
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[LegacyHireStatusResolver] 读取建筑文件名失败", e);
        }
        return "";
    }

    /**
     * 从服务器端工业建筑数据文件读取建筑文件名
     */
    private static String readIndustrialBuildingFileNameFromServer(MinecraftServer server, BlockPos pos) {
        try {
            java.nio.file.Path worldPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path industrialDir = worldPath.resolve("simukraft").resolve("industrial");
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            java.nio.file.Path skFile = industrialDir.resolve(fileName);

            if (java.nio.file.Files.exists(skFile)) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(skFile, java.nio.charset.Charset.forName("UTF-8"));
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("building_file_name:")) {
                        return line.substring(19).trim();
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[LegacyHireStatusResolver] 读取工业建筑文件名失败", e);
        }
        return "";
    }

    /**
     * 从商业建筑配置文件中读取 jobType
     */
    private static String readJobTypeFromCommercialConfig(MinecraftServer server, BlockPos pos, String buildingFileName) {
        try {
            // 如果提供了建筑文件名，直接从配置管理器获取
            if (buildingFileName != null && !buildingFileName.isEmpty()) {
                var config = com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(
                    buildingFileName.replace(".sk", "").toLowerCase()
                );
                if (config != null && config.getJobType() != null) {
                    return config.getJobType();
                }
            }

            // 否则从控制盒数据文件读取建筑文件名，再获取配置
            String fileName = readBuildingFileNameFromServer(server, pos);
            if (fileName != null && !fileName.isEmpty()) {
                var config = com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(
                    fileName.replace(".sk", "").toLowerCase()
                );
                if (config != null && config.getJobType() != null) {
                    return config.getJobType();
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[LegacyHireStatusResolver] 读取商业建筑 jobType 失败", e);
        }
        return "shopkeeper"; // 默认返回 shopkeeper
    }

    record WorkBlockHireStatus(UUID employeeUuid, String employeeName, String legacyJobType, String buildingFileName) {
        WorkBlockHireStatus(UUID employeeUuid, String employeeName, String legacyJobType) {
            this(employeeUuid, employeeName, legacyJobType, "");
        }
    }

    record BuildBoxHireStatus(UUID builderUuid, UUID plannerUuid, String builderName, String plannerName) {
    }
}
