package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 统一定位和恢复 NPC 实体。
 * 优先返回已加载实体；未加载时短时强制加载最后位置/住宅区块，避免雇佣系统把“区块卸载”误判成 NPC 丢失。
 */
@SuppressWarnings("null")
public final class NPCEntityLocator {
    private static final long RESTORE_RETRY_INTERVAL_TICKS = 40L;
    private static final ConcurrentMap<UUID, Long> LAST_RESTORE_ATTEMPT_TICKS = new ConcurrentHashMap<>();

    private NPCEntityLocator() {
    }

    @Nullable
    public static CustomEntity findLoadedNpc(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) {
            return null;
        }

        for (ServerLevel level : server.getAllLevels()) {
            var entity = level.getEntity(npcUuid);
            if (entity instanceof CustomEntity npc && npc.isAlive() && !npc.isRemoved()) {
                return npc;
            }
        }
        return null;
    }

    @Nullable
    public static CustomEntity findNpc(MinecraftServer server, UUID npcUuid, boolean restoreIfMissing) {
        return findNpc(server, npcUuid, null, restoreIfMissing);
    }

    @Nullable
    public static CustomEntity findNpc(MinecraftServer server, UUID npcUuid, @Nullable BlockPos fallbackPos, boolean restoreIfMissing) {
        CustomEntity loadedNpc = findLoadedNpc(server, npcUuid);
        if (loadedNpc != null || !restoreIfMissing || server == null || npcUuid == null) {
            return loadedNpc;
        }
        if (!shouldAttemptRestore(server, npcUuid)) {
            return null;
        }
        return tryRestoreNpc(server, npcUuid, fallbackPos);
    }

    @Nullable
    public static CustomEntity tryRestoreNpc(MinecraftServer server, UUID npcUuid) {
        return tryRestoreNpc(server, npcUuid, null);
    }

    @Nullable
    public static CustomEntity tryRestoreNpc(MinecraftServer server, UUID npcUuid, @Nullable BlockPos fallbackPos) {
        if (server == null || npcUuid == null) {
            return null;
        }

        CustomEntity loadedNpc = findLoadedNpc(server, npcUuid);
        if (loadedNpc != null) {
            return loadedNpc;
        }

        LAST_RESTORE_ATTEMPT_TICKS.put(npcUuid, getCurrentTick(server));

        NPCDataManager.NPCLastKnownLocation lastKnownLocation = NPCDataManager.getNPCLastKnownLocation(server, npcUuid);
        if (lastKnownLocation != null) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(lastKnownLocation.dimensionId());
            if (dimensionId != null) {
                ServerLevel sourceLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
                CustomEntity restored = tryLoadNpcFromChunk(sourceLevel, lastKnownLocation.blockPos(), npcUuid, "最后位置");
                if (restored != null) {
                    return restored;
                }
            }
        }

        BlockPos residencePos = ResidentManager.getNPCResidenceControlBoxPos(server, npcUuid);
        if (residencePos != null) {
            CustomEntity restored = tryLoadNpcFromChunk(server.overworld(), residencePos, npcUuid, "住宅位置");
            if (restored != null) {
                return restored;
            }
        }

        if (fallbackPos != null) {
            CustomEntity restored = tryLoadNpcFromChunk(server.overworld(), fallbackPos, npcUuid, "工作位置");
            if (restored != null) {
                return restored;
            }
        }

        return null;
    }

    private static boolean shouldAttemptRestore(MinecraftServer server, UUID npcUuid) {
        long currentTick = getCurrentTick(server);
        Long lastTick = LAST_RESTORE_ATTEMPT_TICKS.get(npcUuid);
        return lastTick == null || currentTick < 0L || currentTick - lastTick >= RESTORE_RETRY_INTERVAL_TICKS;
    }

    private static long getCurrentTick(MinecraftServer server) {
        if (server == null) {
            return -1L;
        }
        ServerLevel overworld = server.overworld();
        return overworld != null ? overworld.getGameTime() : -1L;
    }

    @Nullable
    private static CustomEntity tryLoadNpcFromChunk(@Nullable ServerLevel level, @Nullable BlockPos blockPos, UUID npcUuid, String reason) {
        if (level == null || blockPos == null || npcUuid == null) {
            return null;
        }

        ChunkPos chunkPos = new ChunkPos(blockPos);
        boolean forcedChunk = false;
        try {
            if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
                level.setChunkForced(chunkPos.x, chunkPos.z, true);
                forcedChunk = true;
            }
            level.getChunk(chunkPos.x, chunkPos.z);

            var entity = level.getEntity(npcUuid);
            if (entity instanceof CustomEntity npc && npc.isAlive() && !npc.isRemoved()) {
                if (Simukraft.LOGGER.isDebugEnabled()) {
                    Simukraft.LOGGER.debug("[NPCEntityLocator] 已按{}恢复NPC实体 uuid={}, pos={}", reason, npcUuid, blockPos);
                }
                return npc;
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCEntityLocator] 按{}恢复NPC实体失败 uuid={}, pos={}", reason, npcUuid, blockPos, e);
        } finally {
            if (forcedChunk) {
                level.setChunkForced(chunkPos.x, chunkPos.z, false);
            }
        }
        return null;
    }
}
