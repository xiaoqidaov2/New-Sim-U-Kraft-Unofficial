package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public final class NpcChunkLoadManager {
    private static final Map<UUID, NpcChunkLocation> activeNpcChunks = new ConcurrentHashMap<>();
    private static final Map<String, Map<Long, Set<UUID>>> forcedChunkOwners = new ConcurrentHashMap<>();

    private NpcChunkLoadManager() {
    }

    public static void updateNpcLocation(CustomEntity npc) {
        if (npc == null || !(npc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        UUID npcUuid = npc.getUUID();
        ResourceKey<Level> dimension = serverLevel.dimension();
        ChunkPos chunkPos = npc.chunkPosition();
        NpcChunkLocation newLocation = new NpcChunkLocation(dimension.location().toString(), chunkPos.x, chunkPos.z);
        NpcChunkLocation oldLocation = activeNpcChunks.put(npcUuid, newLocation);

        NpcChunkSavedData data = NpcChunkSavedData.get(serverLevel);
        data.put(npcUuid, newLocation);

        if (!ServerConfig.shouldForceLoadNpcChunks() || npc.isRemoved() || npc.isDeadOrDying()) {
            releaseNpcChunk(serverLevel.getServer(), npcUuid, oldLocation != null ? oldLocation : newLocation, false);
            return;
        }

        if (oldLocation != null && !oldLocation.equals(newLocation)) {
            releaseNpcChunk(serverLevel.getServer(), npcUuid, oldLocation, false);
        }
        forceNpcChunk(serverLevel, npcUuid, newLocation);
    }

    public static void onNpcRemoved(CustomEntity npc, Entity.RemovalReason reason) {
        if (npc == null || !(npc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        UUID npcUuid = npc.getUUID();
        NpcChunkLocation location = activeNpcChunks.remove(npcUuid);
        if (location == null) {
            location = new NpcChunkLocation(serverLevel.dimension().location().toString(), npc.chunkPosition().x, npc.chunkPosition().z);
        }

        if (reason != null && reason.shouldDestroy()) {
            NpcChunkSavedData.get(serverLevel).remove(npcUuid);
        } else {
            NpcChunkSavedData.get(serverLevel).put(npcUuid, location);
        }
        releaseNpcChunk(serverLevel.getServer(), npcUuid, location, false);
    }

    public static void restoreForcedChunks(MinecraftServer server) {
        if (server == null) {
            return;
        }
        releaseAllForcedChunks(server);
        activeNpcChunks.clear();

        if (!ServerConfig.shouldForceLoadNpcChunks()) {
            return;
        }

        NpcChunkSavedData data = NpcChunkSavedData.get(server.overworld());
        for (Map.Entry<UUID, NpcChunkLocation> entry : data.getLocations().entrySet()) {
            ServerLevel level = getLevel(server, entry.getValue());
            if (level == null) {
                continue;
            }
            activeNpcChunks.put(entry.getKey(), entry.getValue());
            forceNpcChunk(level, entry.getKey(), entry.getValue());
        }
    }

    public static void releaseAllForcedChunks(MinecraftServer server) {
        if (server == null) {
            forcedChunkOwners.clear();
            return;
        }

        for (Map.Entry<String, Map<Long, Set<UUID>>> dimensionEntry : forcedChunkOwners.entrySet()) {
            ServerLevel level = getLevel(server, dimensionEntry.getKey());
            if (level == null) {
                continue;
            }
            for (Long chunkKeyObj : dimensionEntry.getValue().keySet()) {
                long chunkKey = chunkKeyObj.longValue();
                ChunkPos chunkPos = new ChunkPos(chunkKey);
                if (level.getForcedChunks().contains(chunkKey)) {
                    level.setChunkForced(chunkPos.x, chunkPos.z, false);
                }
            }
        }
        forcedChunkOwners.clear();
    }

    private static void forceNpcChunk(ServerLevel level, UUID npcUuid, NpcChunkLocation location) {
        Map<Long, Set<UUID>> dimensionChunks = forcedChunkOwners.computeIfAbsent(location.dimensionId(), key -> new ConcurrentHashMap<>());
        long chunkKey = ChunkPos.asLong(location.chunkX(), location.chunkZ());
        Set<UUID> owners = dimensionChunks.computeIfAbsent(chunkKey, key -> ConcurrentHashMap.newKeySet());
        owners.add(npcUuid);
        if (!level.getForcedChunks().contains(chunkKey)) {
            level.setChunkForced(location.chunkX(), location.chunkZ(), true);
        }
        level.getChunk(location.chunkX(), location.chunkZ());
    }

    private static void releaseNpcChunk(MinecraftServer server, UUID npcUuid, @Nullable NpcChunkLocation location, boolean removeSavedLocation) {
        if (server == null || location == null) {
            return;
        }

        Map<Long, Set<UUID>> dimensionChunks = forcedChunkOwners.get(location.dimensionId());
        if (dimensionChunks == null) {
            return;
        }

        long chunkKey = ChunkPos.asLong(location.chunkX(), location.chunkZ());
        Set<UUID> owners = dimensionChunks.get(chunkKey);
        if (owners == null) {
            return;
        }

        owners.remove(npcUuid);
        if (!owners.isEmpty()) {
            return;
        }

        dimensionChunks.remove(chunkKey);
        ServerLevel level = getLevel(server, location);
        if (level != null && level.getForcedChunks().contains(chunkKey)) {
            level.setChunkForced(location.chunkX(), location.chunkZ(), false);
        }
        if (removeSavedLocation && level != null) {
            NpcChunkSavedData.get(level).remove(npcUuid);
        }
    }

    @Nullable
    private static ServerLevel getLevel(MinecraftServer server, NpcChunkLocation location) {
        return getLevel(server, location.dimensionId());
    }

    @Nullable
    private static ServerLevel getLevel(MinecraftServer server, String dimensionId) {
        ResourceLocation resourceLocation = ResourceLocation.tryParse(dimensionId);
        if (resourceLocation == null) {
            return null;
        }
        ResourceKey<Level> dimensionKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, resourceLocation);
        return server.getLevel(dimensionKey);
    }

    public record NpcChunkLocation(String dimensionId, int chunkX, int chunkZ) {
    }

    public static class NpcChunkSavedData extends SavedData {
        private static final String DATA_NAME = "simukraft_npc_chunk_locations";
        private final Map<UUID, NpcChunkLocation> locations = new HashMap<>();

        public Map<UUID, NpcChunkLocation> getLocations() {
            return Map.copyOf(locations);
        }

        public void put(UUID npcUuid, NpcChunkLocation location) {
            NpcChunkLocation oldLocation = locations.put(npcUuid, location);
            if (!location.equals(oldLocation)) {
                setDirty();
            }
        }

        public void remove(UUID npcUuid) {
            if (locations.remove(npcUuid) != null) {
                setDirty();
            }
        }

        @Override
        public CompoundTag save(@Nonnull CompoundTag tag) {
            ListTag list = new ListTag();
            for (Map.Entry<UUID, NpcChunkLocation> entry : locations.entrySet()) {
                CompoundTag locationTag = new CompoundTag();
                locationTag.putUUID("uuid", entry.getKey());
                locationTag.putString("dimension", entry.getValue().dimensionId());
                locationTag.putInt("chunkX", entry.getValue().chunkX());
                locationTag.putInt("chunkZ", entry.getValue().chunkZ());
                list.add(locationTag);
            }
            tag.put("locations", list);
            return tag;
        }

        public static NpcChunkSavedData load(CompoundTag tag) {
            NpcChunkSavedData data = new NpcChunkSavedData();
            ListTag list = tag.getList("locations", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag locationTag = list.getCompound(i);
                if (!locationTag.hasUUID("uuid")) {
                    continue;
                }
                UUID npcUuid = locationTag.getUUID("uuid");
                String dimension = locationTag.getString("dimension");
                int chunkX = locationTag.getInt("chunkX");
                int chunkZ = locationTag.getInt("chunkZ");
                if (!dimension.isBlank()) {
                    data.locations.put(npcUuid, new NpcChunkLocation(dimension, chunkX, chunkZ));
                }
            }
            return data;
        }

        public static NpcChunkSavedData get(ServerLevel level) {
            MinecraftServer server = level.getServer();
            ServerLevel overworld = server != null ? server.overworld() : level;
            DimensionDataStorage storage = overworld.getDataStorage();
            return storage.computeIfAbsent(
                    NpcChunkSavedData::load,
                    NpcChunkSavedData::new,
                    DATA_NAME
            );
        }
    }
}
