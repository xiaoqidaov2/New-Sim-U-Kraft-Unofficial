package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class DeleteNPCPacket {
    private final UUID npcUuid;
    
    public DeleteNPCPacket(UUID npcUuid) {
        this.npcUuid = npcUuid;
    }
    
    public DeleteNPCPacket(FriendlyByteBuf buf) {
        this.npcUuid = buf.readUUID();
    }
    
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(npcUuid);
    }
    
    public static void handle(DeleteNPCPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 服务器端处理删除逻辑
            Simukraft.LOGGER.info("[DeleteNPCPacket] Received delete NPC request: NPC={}", packet.npcUuid);
            
            // 修复：检查发送者是否为null
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                Simukraft.LOGGER.error("[DeleteNPCPacket] Sender is null, cannot process delete request");
                return;
            }
            
            MinecraftServer server = sender.getServer();
            if (server != null) {
                try {
                    // 检查权限：只有市长或官员可以删除NPC
                    if (!canManageNPC(server, sender, packet.npcUuid)) {
                        sender.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("message.simukraft.npc.delete.no_permission")
                                .withStyle(net.minecraft.ChatFormatting.RED),
                            false
                        );
                        Simukraft.LOGGER.error("[DeleteNPCPacket] Player {} has no permission to delete NPC {}", sender.getName().getString(), packet.npcUuid);
                        return;
                    }
                    
                    // 从所有相关文件中删除NPC数据
                    deleteNPCFromDataFile(server, packet.npcUuid);  // npcdata.sk
                    deleteNPCFromJobFile(server, packet.npcUuid);   // jobdata.sk
                    deleteNPCFromNameManager(server, packet.npcUuid); // simukraft_names.dat
                    deleteNPCFromResidenceFiles(server, packet.npcUuid); // 住宅文件
                    
                    // 删除内存中的NPC数据缓存
                    com.xiaoliang.simukraft.utils.NPCDataManager.removeNPCFromCache(packet.npcUuid);
                    
                    // 删除实体NPC（如果在线）
                    deleteNPCEntity(server, packet.npcUuid);
                    
                    // 发送同步包给所有玩家，更新客户端显示
                        syncNPCDeleteToClients(server, packet.npcUuid);
                        
                        // 更新城市人口数据并通知HUD刷新
                        updateCityPopulationAndHUD(server, packet.npcUuid);
                        
                        Simukraft.LOGGER.info("[DeleteNPCPacket] NPC deleted successfully: {}", packet.npcUuid);
                } catch (Exception e) {
                    Simukraft.LOGGER.error("[DeleteNPCPacket] Failed to delete NPC: {}", e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
    
    /**
     * 检查玩家是否有权限管理NPC（市长或官员）
     */
    private static boolean canManageNPC(MinecraftServer server, ServerPlayer player, UUID npcUuid) {
        try {
            ServerLevel level = server.overworld();
            com.xiaoliang.simukraft.world.CityData cityData = com.xiaoliang.simukraft.world.CityData.get(level);
            
            // 查找包含该NPC的城市
            String playerName = player.getName().getString();
            for (com.xiaoliang.simukraft.world.CityData.CityInfo city : cityData.getAllCities()) {
                if (city.getCitizenIds().contains(npcUuid)) {
                    // 检查玩家是否是该城市的市长或官员（使用玩家名）
                    return city.canManageCity(playerName);
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[DeleteNPCPacket] Error checking permissions: {}", e.getMessage());
        }
        return false;
    }
    
    private static void deleteNPCFromDataFile(MinecraftServer server, UUID npcUuid) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path npcDir = worldDir.resolve("simukraft").resolve("npc");
            java.nio.file.Path dataFile = npcDir.resolve("npcdata.sk");
            
            if (!java.nio.file.Files.exists(dataFile)) {
                Simukraft.LOGGER.error("[DeleteNPCPacket] NPC data file does not exist: {}", dataFile);
                return;
            }
            
            // 读取现有NPC数据
            com.google.gson.JsonArray npcArray;
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(dataFile, java.nio.charset.StandardCharsets.UTF_8)) {
                npcArray = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray();
            }
            
            // 查找并删除NPC数据
            boolean found = false;
            com.google.gson.JsonArray newArray = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement element : npcArray) {
                com.google.gson.JsonObject npcObj = element.getAsJsonObject();
                if (npcObj.has("uuid") && UUID.fromString(npcObj.get("uuid").getAsString()).equals(npcUuid)) {
                    found = true;
                    Simukraft.LOGGER.info("[DeleteNPCPacket] Deleted NPC from npcdata.sk: {}", npcUuid);
                } else {
                    newArray.add(npcObj);
                }
            }
            
            if (found) {
                // 写入更新后的数据
                try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(dataFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                    gson.toJson(newArray, writer);
                }
            } else {
                Simukraft.LOGGER.error("[DeleteNPCPacket] NPC not found in npcdata.sk: {}", npcUuid);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[DeleteNPCPacket] Failed to process npcdata.sk: {}", e.getMessage());
        }
    }
    
    private static void deleteNPCFromJobFile(MinecraftServer server, UUID npcUuid) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path npcDir = worldDir.resolve("simukraft").resolve("npc");
            java.nio.file.Path jobFile = npcDir.resolve("jobdata.sk");
            
            if (!java.nio.file.Files.exists(jobFile)) {
                Simukraft.LOGGER.error("[DeleteNPCPacket] Job data file does not exist: {}", jobFile);
                return;
            }
            
            // 读取现有职业数据
            com.google.gson.JsonArray jobArray;
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(jobFile, java.nio.charset.StandardCharsets.UTF_8)) {
                jobArray = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray();
            }
            
            // 查找并删除NPC职业数据
            boolean found = false;
            com.google.gson.JsonArray newArray = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement element : jobArray) {
                com.google.gson.JsonObject jobObj = element.getAsJsonObject();
                if (jobObj.has("uuid") && UUID.fromString(jobObj.get("uuid").getAsString()).equals(npcUuid)) {
                    found = true;
                    Simukraft.LOGGER.info("[DeleteNPCPacket] Deleted NPC from jobdata.sk: {}", npcUuid);
                } else {
                    newArray.add(jobObj);
                }
            }
            
            if (found) {
                // 写入更新后的数据
                try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(jobFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                    gson.toJson(newArray, writer);
                }
            } else {
                Simukraft.LOGGER.error("[DeleteNPCPacket] NPC not found in jobdata.sk: {}", npcUuid);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[DeleteNPCPacket] Failed to process jobdata.sk: {}", e.getMessage());
        }
    }
    
    private static void deleteNPCFromNameManager(MinecraftServer server, UUID npcUuid) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path nameFile = worldDir.resolve("simukraft").resolve("simukraft_names.dat");
            
            if (!java.nio.file.Files.exists(nameFile)) {
                Simukraft.LOGGER.error("[DeleteNPCPacket] Name manager file does not exist: {}", nameFile);
                return;
            }
            
            // 读取现有名称数据
            com.google.gson.JsonObject nameData;
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(nameFile, java.nio.charset.StandardCharsets.UTF_8)) {
                nameData = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            }
            
            // 查找并删除NPC名称数据
            boolean found = false;
            for (String key : nameData.keySet()) {
                com.google.gson.JsonObject nameObj = nameData.getAsJsonObject(key);
                if (nameObj.has("uuid") && UUID.fromString(nameObj.get("uuid").getAsString()).equals(npcUuid)) {
                    nameData.remove(key);
                    found = true;
                    Simukraft.LOGGER.info("[DeleteNPCPacket] Deleted NPC from simukraft_names.dat: {}", npcUuid);
                    break;
                }
            }
            
            if (found) {
                // 写入更新后的数据
                try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(nameFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                    gson.toJson(nameData, writer);
                }
            } else {
                Simukraft.LOGGER.error("[DeleteNPCPacket] NPC not found in simukraft_names.dat: {}", npcUuid);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[DeleteNPCPacket] Failed to process simukraft_names.dat: {}", e.getMessage());
        }
    }
    
    private static void deleteNPCFromResidenceFiles(MinecraftServer server, UUID npcUuid) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path residenceDir = worldDir.resolve("simukraft").resolve("residence");
            
            if (!java.nio.file.Files.exists(residenceDir)) {
                Simukraft.LOGGER.error("[DeleteNPCPacket] Residence directory does not exist: {}", residenceDir);
                return;
            }
            
            // 遍历所有住宅文件
            java.nio.file.Files.list(residenceDir)
                .filter(path -> path.toString().endsWith(".sk"))
                .forEach(file -> {
                    try {
                        // 读取住宅文件内容（YAML格式）
                        List<String> lines = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
                        boolean modified = false;
                        
                        // 查找并删除包含该NPC UUID的行
                        List<String> newLines = new ArrayList<>();
                        for (String line : lines) {
                            if (line.trim().startsWith("resident_uuid:") && line.contains(npcUuid.toString())) {
                                modified = true;
                                Simukraft.LOGGER.info("[DeleteNPCPacket] Deleted NPC from residence file: {} - {}", file.getFileName(), npcUuid);
                                // 同时删除对应的居民名字行
                                continue;
                            }
                            if (line.trim().startsWith("resident:")) {
                                // 检查下一行是否是我们要删除的UUID
                                int currentIndex = lines.indexOf(line);
                                if (currentIndex + 1 < lines.size() && 
                                    lines.get(currentIndex + 1).trim().startsWith("resident_uuid:") && 
                                    lines.get(currentIndex + 1).contains(npcUuid.toString())) {
                                    // 跳过居民名字行，因为对应的UUID行将被删除
                                    continue;
                                }
                            }
                            newLines.add(line);
                        }
                        
                        // 写入更新后的数据
                        if (modified) {
                            try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                                for (String line : newLines) {
                                    writer.write(line);
                                    writer.write("\n");
                                }
                            }
                        }
                    } catch (Exception e) {
                        Simukraft.LOGGER.error("[DeleteNPCPacket] Failed to process residence file: {} - {}", file, e.getMessage());
                        e.printStackTrace();
                    }
                });
        } catch (Exception e) {
            Simukraft.LOGGER.error("[DeleteNPCPacket] Failed to traverse residence directory: {}", e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void deleteNPCEntity(MinecraftServer server, UUID npcUuid) {
        // 在所有世界中查找并删除NPC实体
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof com.xiaoliang.simukraft.entity.CustomEntity && 
                    entity.getUUID().equals(npcUuid)) {
                    entity.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                    Simukraft.LOGGER.info("[DeleteNPCPacket] Removed NPC entity: {}", npcUuid);
                    return;
                }
            }
        }
        Simukraft.LOGGER.info("[DeleteNPCPacket] NPC entity not found online: {}", npcUuid);
    }
    
    private static void syncNPCDeleteToClients(MinecraftServer server, UUID npcUuid) {
        // 创建同步包
        SyncNPCDeletePacket syncPacket = new SyncNPCDeletePacket(npcUuid);
        
        // 发送给所有在线玩家
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NetworkManager.sendToPlayer(syncPacket, player);
        }
        
        Simukraft.LOGGER.info("[DeleteNPCPacket] Sent NPC delete sync packets to all players: {}", npcUuid);
    }
    
    private static void updateCityPopulationAndHUD(MinecraftServer server, UUID npcUuid) {
        try {
            // 获取服务器主世界
            ServerLevel level = server.overworld();
            
            // 获取城市数据
            com.xiaoliang.simukraft.world.CityData cityData = com.xiaoliang.simukraft.world.CityData.get(level);
            
            // 查找包含该NPC的城市
            for (com.xiaoliang.simukraft.world.CityData.CityInfo city : cityData.getAllCities()) {
                if (city.getCitizenIds().contains(npcUuid)) {
                    // 从城市中移除该NPC（使用removeCitizen方法，因为getCitizenIds()返回的是副本）
                    city.removeCitizen(npcUuid);
                    Simukraft.LOGGER.info("[DeleteNPCPacket] Removed NPC from city: {} - {}", city.getCityName(), npcUuid);
                    Simukraft.LOGGER.info("[DeleteNPCPacket] City population: {}", city.getCitizenIds().size());
                    
                    // 标记数据为脏，Minecraft会自动保存
                    cityData.setDirty();
                    
                    // 更新世界人口
                    com.xiaoliang.simukraft.world.PopulationData populationData = com.xiaoliang.simukraft.world.PopulationData.get(level);
                    populationData.removePopulation(level);
                    Simukraft.LOGGER.info("[DeleteNPCPacket] World population -1, current: {}", populationData.getPopulation());
                    
                    // 获取当前天数
                    com.xiaoliang.simukraft.world.SimukraftWorldData worldData = com.xiaoliang.simukraft.world.SimukraftWorldData.get(level);
                    int currentDay = worldData.getCurrentDay();
                    
                    // 发送HUD数据给所有在线玩家
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        NetworkManager.sendHUDDataToPlayer(
                            currentDay,
                            populationData.getPopulation(),
                            city.getCityName(),
                            city.getFunds(),
                            city.getCitizenIds().size(),
                            player
                        );
                    }
                    
                    Simukraft.LOGGER.info("[DeleteNPCPacket] HUD data sent to all players, city population: {}", city.getCitizenIds().size());
                    break;
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[DeleteNPCPacket] Failed to update city population: {}", e.getMessage());
            e.printStackTrace();
        }
    }
    
    public UUID getNpcUuid() {
        return npcUuid;
    }
}