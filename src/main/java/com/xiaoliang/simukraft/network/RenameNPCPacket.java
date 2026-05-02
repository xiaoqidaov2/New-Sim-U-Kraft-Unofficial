package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class RenameNPCPacket {
    private final UUID npcUuid;
    private final String newName;

    public RenameNPCPacket(UUID npcUuid, String newName) {
        this.npcUuid = npcUuid;
        this.newName = newName;
    }

    public static void encode(RenameNPCPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.npcUuid);
        buffer.writeUtf(packet.newName, 32);
    }

    public static RenameNPCPacket decode(FriendlyByteBuf buffer) {
        UUID npcUuid = buffer.readUUID();
        String newName = buffer.readUtf(32);
        return new RenameNPCPacket(npcUuid, newName);
    }

    public static void handle(RenameNPCPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            MinecraftServer server = ctx.get().getSender().getServer();
            if (server != null) {
                try {
                    // 更新所有相关文件
                    updateNPCNameInDataFile(server, packet.npcUuid, packet.newName);  // npcdata.sk
                    updateNPCNameInJobFile(server, packet.npcUuid, packet.newName);   // jobdata.sk
                    updateNPCNameInNameManager(server, packet.npcUuid, packet.newName); // simukraft_names.dat
                    updateNPCNameInResidenceFiles(server, packet.npcUuid, packet.newName); // 住宅文件

                    // 更新内存中的NPC名称缓存
                    com.xiaoliang.simukraft.utils.NPCDataManager.updateNPCNameCache(packet.npcUuid, packet.newName);

                    // 更新实体NPC的名称（如果在线）
                    updateNPCEntityName(server, packet.npcUuid, packet.newName);

                    // 发送同步包给所有玩家，更新客户端显示
                    syncNPCNameToClients(server, packet.npcUuid, packet.newName);
                } catch (Exception e) {
                    Simukraft.LOGGER.error("[RenameNPCPacket] Failed to rename NPC: {}", e.getMessage(), e);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
    
    private static void updateNPCNameInDataFile(MinecraftServer server, UUID npcUuid, String newName) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path npcDir = worldDir.resolve("simukraft").resolve("npc");
            java.nio.file.Path npcFile = npcDir.resolve("npcdata.sk");
            
            if (!java.nio.file.Files.exists(npcFile)) {
                Simukraft.LOGGER.error("[RenameNPCPacket] NPC data file does not exist: {}", npcFile);
                return;
            }
            
            // 读取现有NPC数据
            com.google.gson.JsonArray npcArray;
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(npcFile, java.nio.charset.StandardCharsets.UTF_8)) {
                npcArray = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray();
            }
            
            // 查找并更新NPC名称
            boolean found = false;
            for (com.google.gson.JsonElement element : npcArray) {
                com.google.gson.JsonObject npcObj = element.getAsJsonObject();
                if (npcObj.has("uuid") && UUID.fromString(npcObj.get("uuid").getAsString()).equals(npcUuid)) {
                    npcObj.addProperty("name", newName);
                    found = true;
                    break;
                }
            }
            
            if (found) {
                // 写回文件
                try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(npcFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(npcArray, writer);
                }
            } else {
                Simukraft.LOGGER.error("[RenameNPCPacket] NPC with UUID {} not found in npcdata.sk", npcUuid);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to update NPC data file", e);
        }
    }
    
    private static void updateNPCEntityName(MinecraftServer server, UUID npcUuid, String newName) {

        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof com.xiaoliang.simukraft.entity.CustomEntity npc &&
                        entity.getUUID().equals(npcUuid)) {
                    npc.syncNameFromPacket(newName);
                    return;
                }
            }
        }
    }
    
    private static void updateNPCNameInJobFile(MinecraftServer server, UUID npcUuid, String newName) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path npcDir = worldDir.resolve("simukraft").resolve("npc");
            java.nio.file.Path jobFile = npcDir.resolve("jobdata.sk");
            
            if (!java.nio.file.Files.exists(jobFile)) {
                Simukraft.LOGGER.error("[RenameNPCPacket] Job data file does not exist: {}", jobFile);
                return;
            }
            
            // 读取现有职业数据
            com.google.gson.JsonArray jobArray;
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(jobFile, java.nio.charset.StandardCharsets.UTF_8)) {
                jobArray = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray();
            }
            
            // 查找并更新NPC名称
            boolean found = false;
            for (com.google.gson.JsonElement element : jobArray) {
                com.google.gson.JsonObject jobObj = element.getAsJsonObject();
                if (jobObj.has("uuid") && UUID.fromString(jobObj.get("uuid").getAsString()).equals(npcUuid)) {
                    jobObj.addProperty("name", newName);
                    found = true;
                    break;
                }
            }
            
            if (found) {
                // 写回文件
                try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(jobFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(jobArray, writer);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to update job data file", e);
        }
    }
    
    private static void updateNPCNameInNameManager(MinecraftServer server, UUID npcUuid, String newName) {
        try {
            // 获取NameManager实例
            net.minecraft.world.level.storage.DimensionDataStorage storage = server.overworld().getDataStorage();
            com.xiaoliang.simukraft.utils.NameManager nameManager = storage.computeIfAbsent(
                com.xiaoliang.simukraft.utils.NameManager::load, 
                com.xiaoliang.simukraft.utils.NameManager::new, 
                "simukraft_names"
            );
            
            if (nameManager != null) {

                nameManager.updateNPCName(npcUuid, newName);
                nameManager.setDirty();
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[RenameNPCPacket] Failed to update NameManager: {}", e.getMessage(), e);
        }
    }
    
    private static void updateNPCNameInResidenceFiles(MinecraftServer server, UUID npcUuid, String newName) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path simukraftDir = worldDir.resolve("simukraft");
            
            // 检查所有住宅文件夹
            java.nio.file.Path[] residenceDirs = {
                simukraftDir.resolve("residence"),
                simukraftDir.resolve("unit1"),
                simukraftDir.resolve("two_bedroom"),
                simukraftDir.resolve("tuff_residence"),
                simukraftDir.resolve("mushroom_house"),
                simukraftDir.resolve("giant_tree_house"),
                simukraftDir.resolve("kms_medium_house")
            };
            
            for (java.nio.file.Path residenceDir : residenceDirs) {
                if (java.nio.file.Files.exists(residenceDir) && java.nio.file.Files.isDirectory(residenceDir)) {
                    updateNPCNameInResidenceDir(residenceDir, npcUuid, newName);
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[RenameNPCPacket] Failed to update residence files: {}", e.getMessage(), e);
        }
    }
    
    private static void updateNPCNameInResidenceDir(java.nio.file.Path residenceDir, UUID npcUuid, String newName) {
        try {
            // 遍历目录中的所有.sk文件
            java.nio.file.Files.walk(residenceDir, 1)
                .filter(java.nio.file.Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".sk"))
                .forEach(file -> {
                    try {
                        // 读取文件内容（YAML格式）
                        java.util.List<String> lines = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
                        boolean updated = false;
                        UUID fileResidentUuid = null;
                        int residentNameLineIndex = -1;

                        // 第一遍：查找resident_uuid
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            if (line.startsWith("resident_uuid:")) {
                                String uuidStr = line.substring("resident_uuid:".length()).trim();
                                try {
                                    fileResidentUuid = UUID.fromString(uuidStr);
                                } catch (IllegalArgumentException e) {
                                    // UUID格式错误，忽略
                                }
                            }
                            if (line.startsWith("resident:")) {
                                residentNameLineIndex = i;
                            }
                        }

                        // 如果resident_uuid匹配，更新resident名称
                        if (fileResidentUuid != null && fileResidentUuid.equals(npcUuid) && residentNameLineIndex >= 0) {
                            // 保留原有的格式，只替换名称部分
                            String newLine = "resident: " + newName;
                            lines.set(residentNameLineIndex, newLine);
                            updated = true;
                        }

                        if (updated) {
                            // 写回文件
                            java.nio.file.Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        Simukraft.LOGGER.error("[RenameNPCPacket] Failed to process residence file: {} - {}", file, e.getMessage(), e);
                    }
                });
        } catch (Exception e) {
            Simukraft.LOGGER.error("[RenameNPCPacket] Failed to traverse residence directory: {} - {}", residenceDir, e.getMessage());
        }
    }
    
    private static void syncNPCNameToClients(MinecraftServer server, UUID npcUuid, String newName) {
        // 创建一个同步包发送给所有玩家
        SyncNPCNamePacket syncPacket = new SyncNPCNamePacket(npcUuid, newName);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NetworkManager.sendToPlayer(syncPacket, player);
        }
    }

    public UUID getNpcUuid() {
        return npcUuid;
    }

    public String getNewName() {
        return newName;
    }
}