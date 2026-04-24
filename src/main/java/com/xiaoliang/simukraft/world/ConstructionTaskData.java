package com.xiaoliang.simukraft.world;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 建造任务数据管理器
 * 用于持久化存储建筑师的建造任务信息
 * 解决局域网开放模式下NPC休息后建造任务丢失的问题
 */
public class ConstructionTaskData {
    private static final Gson gson = new Gson();
    private static final String FILE_NAME = "construction_tasks.json";
    private static final String MODE_DIR = "simukraft";

    /**
     * 建造任务信息类
     */
    public static class TaskInfo {
        public final String buildingName;
        public final String category;
        public final BlockPos startPos;
        public final BlockPos buildBoxPos;
        public final String facing;
        public final String displayName;
        public final double cost;
        public final int currentBlockIndex;

        public TaskInfo(String buildingName, String category, BlockPos startPos,
                       BlockPos buildBoxPos, String facing, String displayName,
                       double cost, int currentBlockIndex) {
            this.buildingName = buildingName;
            this.category = category;
            this.startPos = startPos;
            this.buildBoxPos = buildBoxPos;
            this.facing = facing;
            this.displayName = displayName;
            this.cost = cost;
            this.currentBlockIndex = currentBlockIndex;
        }
    }

    /**
     * 保存建造任务
     * @param server 服务器实例
     * @param npcUuid NPC的UUID
     * @param taskInfo 任务信息
     */
    public static void saveTask(MinecraftServer server, UUID npcUuid, TaskInfo taskInfo) {
        if (server == null || npcUuid == null || taskInfo == null) return;

        try {
            Map<UUID, TaskInfo> allTasks = loadAllTasks(server);
            allTasks.put(npcUuid, taskInfo);
            saveAllTasks(server, allTasks);
            Simukraft.LOGGER.info("[ConstructionTaskData] 保存建造任务 - NPC: {}, 建筑: {}",
                npcUuid.toString().substring(0, 8), taskInfo.displayName);
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ConstructionTaskData] 保存建造任务失败", e);
        }
    }

    /**
     * 加载建造任务
     * @param server 服务器实例
     * @param npcUuid NPC的UUID
     * @return 任务信息，如果没有找到返回null
     */
    public static TaskInfo loadTask(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) return null;

        try {
            Map<UUID, TaskInfo> allTasks = loadAllTasks(server);
            return allTasks.get(npcUuid);
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ConstructionTaskData] 加载建造任务失败", e);
            return null;
        }
    }

    /**
     * 批量加载建造任务，避免对同一个JSON文件重复解析。
     */
    public static Map<UUID, TaskInfo> loadTasks(MinecraftServer server, Collection<UUID> npcUuids) {
        Map<UUID, TaskInfo> result = new HashMap<>();
        if (server == null || npcUuids == null || npcUuids.isEmpty()) {
            return result;
        }

        try {
            Map<UUID, TaskInfo> allTasks = loadAllTasks(server);
            for (UUID npcUuid : npcUuids) {
                if (npcUuid == null) {
                    continue;
                }
                TaskInfo taskInfo = allTasks.get(npcUuid);
                if (taskInfo != null) {
                    result.put(npcUuid, taskInfo);
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ConstructionTaskData] 批量加载建造任务失败", e);
        }

        return result;
    }

    /**
     * 移除建造任务
     * @param server 服务器实例
     * @param npcUuid NPC的UUID
     */
    public static void removeTask(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) return;

        try {
            Map<UUID, TaskInfo> allTasks = loadAllTasks(server);
            if (allTasks.remove(npcUuid) != null) {
                saveAllTasks(server, allTasks);
                Simukraft.LOGGER.info("[ConstructionTaskData] 移除建造任务 - NPC: {}",
                    npcUuid.toString().substring(0, 8));
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ConstructionTaskData] 移除建造任务失败", e);
        }
    }

    /**
     * 检查是否有建造任务
     * @param server 服务器实例
     * @param npcUuid NPC的UUID
     * @return 如果有任务返回true
     */
    public static boolean hasTask(MinecraftServer server, UUID npcUuid) {
        return loadTask(server, npcUuid) != null;
    }

    private static Path getWorldPath(MinecraftServer server) {
        Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
        return worldPath.toAbsolutePath().normalize();
    }

    /**
     * 加载所有建造任务
     */
    private static Map<UUID, TaskInfo> loadAllTasks(MinecraftServer server) {
        Map<UUID, TaskInfo> result = new HashMap<>();

        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path dataFile = simukraftDir.resolve(FILE_NAME);

            if (!Files.exists(dataFile) || Files.size(dataFile) == 0L) {
                return result;
            }

            JsonObject data;
            try (var reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
                data = JsonParser.parseReader(reader).getAsJsonObject();
            }

            for (String key : data.keySet()) {
                try {
                    UUID npcUuid = UUID.fromString(key);
                    JsonObject taskObj = data.getAsJsonObject(key);

                    String buildingName = taskObj.get("buildingName").getAsString();
                    String category = taskObj.get("category").getAsString();
                    String displayName = taskObj.get("displayName").getAsString();
                    String facing = taskObj.get("facing").getAsString();
                    double cost = taskObj.get("cost").getAsDouble();
                    int currentBlockIndex = taskObj.get("currentBlockIndex").getAsInt();
                    // 不再调用normalizeBuildingFileId，直接使用存储的buildingName
                    // 避免每次加载任务时读取SK文件

                    BlockPos startPos = new BlockPos(
                        taskObj.get("startPosX").getAsInt(),
                        taskObj.get("startPosY").getAsInt(),
                        taskObj.get("startPosZ").getAsInt()
                    );

                    BlockPos buildBoxPos = new BlockPos(
                        taskObj.get("buildBoxPosX").getAsInt(),
                        taskObj.get("buildBoxPosY").getAsInt(),
                        taskObj.get("buildBoxPosZ").getAsInt()
                    );

                    TaskInfo taskInfo = new TaskInfo(
                        buildingName, category, startPos, buildBoxPos,
                        facing, displayName, cost, currentBlockIndex
                    );

                    result.put(npcUuid, taskInfo);
                } catch (Exception e) {
                    Simukraft.LOGGER.error("[ConstructionTaskData] 解析任务数据失败: {}", key, e);
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ConstructionTaskData] 加载所有任务失败", e);
        }

        return result;
    }

    // 注意：normalizeBuildingFileId 和 resolveBuildingFileId 方法已移除
    // 因为它们会导致每次加载任务时读取SK文件
    // 现在直接使用存储的buildingName，数据已经在保存时正确存储

    /**
     * 保存所有建造任务
     */
    private static void saveAllTasks(MinecraftServer server, Map<UUID, TaskInfo> tasks) {
        try {
            Path worldDir = getWorldPath(server);
            Path simukraftDir = worldDir.resolve(MODE_DIR);
            Path dataFile = simukraftDir.resolve(FILE_NAME);

            if (!Files.exists(simukraftDir)) {
                Files.createDirectories(simukraftDir);
            }

            JsonObject data = new JsonObject();

            for (Map.Entry<UUID, TaskInfo> entry : tasks.entrySet()) {
                TaskInfo task = entry.getValue();
                JsonObject taskObj = new JsonObject();

                taskObj.addProperty("buildingName", task.buildingName);
                taskObj.addProperty("category", task.category);
                taskObj.addProperty("displayName", task.displayName);
                taskObj.addProperty("facing", task.facing);
                taskObj.addProperty("cost", task.cost);
                taskObj.addProperty("currentBlockIndex", task.currentBlockIndex);

                taskObj.addProperty("startPosX", task.startPos.getX());
                taskObj.addProperty("startPosY", task.startPos.getY());
                taskObj.addProperty("startPosZ", task.startPos.getZ());

                taskObj.addProperty("buildBoxPosX", task.buildBoxPos.getX());
                taskObj.addProperty("buildBoxPosY", task.buildBoxPos.getY());
                taskObj.addProperty("buildBoxPosZ", task.buildBoxPos.getZ());

                data.add(entry.getKey().toString(), taskObj);
            }

            try (var writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ConstructionTaskData] 保存所有任务失败", e);
        }
    }
}
