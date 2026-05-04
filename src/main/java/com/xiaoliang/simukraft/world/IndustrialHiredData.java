package com.xiaoliang.simukraft.world;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.IndustrialBuildingManager;
import com.xiaoliang.simukraft.employment.bridge.EmploymentLegacyBridge;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import com.xiaoliang.simukraft.employment.service.LegacyJobTypeMapper;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.utils.FileUtils;
import com.xiaoliang.simukraft.utils.NPCTaskScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class IndustrialHiredData {
    private static final Gson gson = new Gson();
    private static final String ANIMAL_GENERATION_DATA_FILE = "industrial_animal_generation.json";
    
    // 存储每个工业建筑位置的动物生成状态
    private static final Map<BlockPos, Map<String, AnimalGenerationData>> animalGenerationData = new HashMap<>();
    
    public static class AnimalGenerationData {
        public boolean hasGenerated;
        public int count;
        public long lastCheckTime;
        
        public AnimalGenerationData(boolean hasGenerated, int count, long lastCheckTime) {
            this.hasGenerated = hasGenerated;
            this.count = count;
            this.lastCheckTime = lastCheckTime;
        }
    }

    public static class IndustrialHireInfo {
        private final BlockPos position;
        private final UUID npcUuid;
        private final String jobType;
        private final String buildingFileName;
        private final String buildingName;

        public IndustrialHireInfo() {
            this.position = null;
            this.npcUuid = null;
            this.jobType = null;
            this.buildingFileName = null;
            this.buildingName = null;
        }

        public IndustrialHireInfo(BlockPos position, UUID npcUuid, String jobType, String buildingFileName, String buildingName) {
            this.position = position;
            this.npcUuid = npcUuid;
            this.jobType = jobType;
            this.buildingFileName = buildingFileName;
            this.buildingName = buildingName;
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

        public String getBuildingName() {
            return buildingName;
        }

        public BlockPos getPosition() {
            return position;
        }
    }

    public static void saveHiredEmployees(MinecraftServer server, Map<BlockPos, IndustrialHireInfo> hiredEmployees) {
        Map<BlockPos, EmploymentLegacyBridge.AssignmentInput> desiredAssignments = new HashMap<>();
        for (Map.Entry<BlockPos, IndustrialHireInfo> entry : hiredEmployees.entrySet()) {
            IndustrialHireInfo info = entry.getValue();
            if (info == null || info.getNpcUuid() == null) {
                continue;
            }
            String hint = inferJobHint(info.getJobType(), info.getBuildingFileName());
            desiredAssignments.put(entry.getKey(), new EmploymentLegacyBridge.AssignmentInput(
                    info.getNpcUuid(),
                    LegacyJobTypeMapper.fromLegacy(info.getJobType(), hint)
            ));
        }
        EmploymentLegacyBridge.saveAssignmentsByWorkBlock(server, WorkBlockType.INDUSTRIAL_CONTROL_BOX, desiredAssignments);
    }

    public static Map<BlockPos, IndustrialHireInfo> loadHiredEmployees(MinecraftServer server) {
        Map<BlockPos, IndustrialHireInfo> hiredEmployees = new HashMap<>();
        for (Map.Entry<BlockPos, com.xiaoliang.simukraft.employment.domain.EmploymentAssignment> entry :
                EmploymentLegacyBridge.loadLatestByWorkBlock(server, WorkBlockType.INDUSTRIAL_CONTROL_BOX).entrySet()) {
            BlockPos pos = entry.getKey();
            var assignment = entry.getValue();
            // 只把当前还在岗的雇佣关系暴露给 V1 视图——RELEASED（NPC 死亡 / 解雇）的记录不应该再出现，
            // 否则 IndustrialWorkHandler.handleDailyWork 会一直找不到 NPC 实体并刷屏 warn（#23 卡顿）。
            if (!assignment.isAssigned()) {
                continue;
            }

            String buildingFileName = FileUtils.normalizeBuildingFileName(FileUtils.readIndustrialBuildingFileNameCached(server, pos));
            String buildingName = buildingFileName != null
                    ? IndustrialBuildingManager.getBuildingDisplayName(buildingFileName)
                    : "";
            String jobType = resolveDisplayJobType(assignment.jobType(), buildingFileName);

            hiredEmployees.put(pos, new IndustrialHireInfo(
                    pos,
                    assignment.npcUuid(),
                    jobType,
                    buildingFileName != null ? buildingFileName : "",
                    buildingName != null ? buildingName : ""
            ));
        }
        return hiredEmployees;
    }

    public static CustomEntity findNPCByUuid(MinecraftServer server, UUID uuid) {
        for (CustomEntity npc : NPCTaskScheduler.getAllNPCs(server)) {
            if (uuid.equals(npc.getUUID())) {
                return npc;
            }
        }
        return null;
    }

    private static Path getWorldPath(MinecraftServer server) {
        Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
        return worldPath.toAbsolutePath().normalize();
    }

    private static BlockPos parseBlockPos(String posString) {
        try {
            String[] parts = posString.substring(8, posString.length() - 1).split(",");
            int x = Integer.parseInt(parts[0].split("=")[1].trim());
            int y = Integer.parseInt(parts[1].split("=")[1].trim());
            int z = Integer.parseInt(parts[2].split("=")[1].trim());
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            Simukraft.LOGGER.warn("[IndustrialHiredData] Failed to parse BlockPos: {}", posString);
            return new BlockPos(0, 0, 0);
        }
    }

    // ========== 动物生成数据管理方法 ==========
    
    public static boolean hasGeneratedAnimals(BlockPos pos, String animalType) {
        Map<String, AnimalGenerationData> posData = animalGenerationData.get(pos);
        if (posData == null) return false;
        AnimalGenerationData data = posData.get(animalType);
        return data != null && data.hasGenerated;
    }
    
    public static void markAnimalsGenerated(BlockPos pos, String animalType, int count) {
        animalGenerationData.computeIfAbsent(pos, k -> new HashMap<>())
                .put(animalType, new AnimalGenerationData(true, count, System.currentTimeMillis()));
    }
    
    public static void saveAnimalGenerationData(MinecraftServer server) {
        JsonObject data = new JsonObject();
        
        for (Map.Entry<BlockPos, Map<String, AnimalGenerationData>> entry : animalGenerationData.entrySet()) {
            String posKey = entry.getKey().toString();
            JsonObject animalTypes = new JsonObject();
            
            for (Map.Entry<String, AnimalGenerationData> animalEntry : entry.getValue().entrySet()) {
                JsonObject animalData = new JsonObject();
                animalData.addProperty("hasGenerated", animalEntry.getValue().hasGenerated);
                animalData.addProperty("count", animalEntry.getValue().count);
                animalData.addProperty("lastCheckTime", animalEntry.getValue().lastCheckTime);
                animalTypes.add(animalEntry.getKey(), animalData);
            }
            
            data.add(posKey, animalTypes);
        }
        
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(ANIMAL_GENERATION_DATA_FILE);
            
            if (!Files.exists(simukraftDir)) {
                Files.createDirectories(simukraftDir);
            }
            
            try (java.io.Writer writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[IndustrialHiredData] Failed to save animal generation data", e);
        }
    }
    
    public static void loadAnimalGenerationData(MinecraftServer server) {
        animalGenerationData.clear();
        
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            Path dataFile = simukraftDir.resolve(ANIMAL_GENERATION_DATA_FILE);
            
            if (!Files.exists(dataFile)) {
                return;
            }
            
            JsonObject data;
            try (java.io.Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
                data = gson.fromJson(reader, JsonObject.class);
            }
            
            if (data != null) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
                    try {
                        BlockPos pos = parseBlockPos(entry.getKey());
                        JsonObject animalTypes = entry.getValue().getAsJsonObject();
                        Map<String, AnimalGenerationData> posData = new HashMap<>();
                        
                        for (Map.Entry<String, com.google.gson.JsonElement> animalEntry : animalTypes.entrySet()) {
                            JsonObject animalData = animalEntry.getValue().getAsJsonObject();
                            boolean hasGenerated = animalData.get("hasGenerated").getAsBoolean();
                            int count = animalData.get("count").getAsInt();
                            long lastCheckTime = animalData.get("lastCheckTime").getAsLong();
                            posData.put(animalEntry.getKey(), new AnimalGenerationData(hasGenerated, count, lastCheckTime));
                        }
                        
                        animalGenerationData.put(pos, posData);
                    } catch (Exception e) {
                        Simukraft.LOGGER.warn("[IndustrialHiredData] Failed to parse animal generation data entry: {}", entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[IndustrialHiredData] Failed to load animal generation data", e);
        }
    }
    
    public static String getJobType(MinecraftServer server, BlockPos pos) {
        // 从雇佣数据中查找该位置的jobType
        try {
            if (server == null) {
                Simukraft.LOGGER.warn("[IndustrialHiredData] Server is null in getJobType");
                return null;
            }
            // 加载雇佣数据
            Map<BlockPos, IndustrialHireInfo> hiredEmployees = loadHiredEmployees(server);
            IndustrialHireInfo hireInfo = hiredEmployees.get(pos);
            if (hireInfo != null) {
                return hireInfo.getJobType();
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[IndustrialHiredData] Failed to get jobType for pos {}", pos, e);
        }
        return null;
    }

    private static String resolveDisplayJobType(com.xiaoliang.simukraft.employment.domain.JobType jobType, String buildingFileName) {
        String configJobType = readJobTypeFromConfig(buildingFileName);
        return configJobType != null ? configJobType : LegacyJobTypeMapper.toLegacy(jobType);
    }

    private static String readJobTypeFromConfig(String buildingFileName) {
        if (buildingFileName == null || buildingFileName.isBlank()) {
            return null;
        }
        var config = IndustrialBuildingManager.getConfig(buildingFileName.replace(".sk", "").toLowerCase());
        if (config == null || config.getJobType() == null || config.getJobType().isBlank()) {
            return null;
        }
        return config.getJobType();
    }

    private static String inferJobHint(String jobType, String buildingFileName) {
        if ("shepherd".equals(jobType)) {
            return "wool_farm";
        }
        if ("butcher".equals(jobType)) {
            return "beef_farm";
        }
        String configJobType = readJobTypeFromConfig(buildingFileName);
        if ("shepherd".equals(configJobType)) {
            return "wool_farm";
        }
        if ("butcher".equals(configJobType)) {
            return "beef_farm";
        }
        return "industrial";
    }
}
