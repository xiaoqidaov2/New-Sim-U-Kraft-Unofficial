package com.xiaoliang.simukraft.job.core;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.job.api.JobRuntimeState;
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public final class JobRuntimeStateStore {
    private static final String DATA_NAME = "simukraft_job_runtime_states";

    private final Map<UUID, JobRuntimeState> states = new ConcurrentHashMap<>();
    private long totalUpdates;
    private long lastSaveTime;

    public JobRuntimeState get(UUID npcUuid) {
        if (npcUuid == null) {
            return JobRuntimeState.IDLE;
        }
        return states.getOrDefault(npcUuid, JobRuntimeState.IDLE);
    }

    public void set(UUID npcUuid, JobRuntimeState state) {
        if (npcUuid == null || state == null) {
            return;
        }
        states.put(npcUuid, state);
        totalUpdates++;
    }

    public void clear(UUID npcUuid) {
        if (npcUuid != null) {
            states.remove(npcUuid);
        }
    }

    public void clearAll() {
        states.clear();
    }

    public int size() {
        return states.size();
    }

    public void loadFromLevel(ServerLevel level) {
        try {
            JobRuntimeStateData data = level.getDataStorage().computeIfAbsent(
                    JobRuntimeStateData::load,
                    JobRuntimeStateData::new,
                    DATA_NAME
            );
            states.clear();
            states.putAll(data.getStates());
            Simukraft.LOGGER.info("[JobRuntimeStateStore] Loaded {} job runtime states", states.size());
        } catch (Exception e) {
            Simukraft.LOGGER.error("[JobRuntimeStateStore] Failed to load job runtime states", e);
        }
    }

    public void saveToLevel(ServerLevel level) {
        try {
            JobRuntimeStateData data = level.getDataStorage().computeIfAbsent(
                    JobRuntimeStateData::load,
                    JobRuntimeStateData::new,
                    DATA_NAME
            );
            data.setStates(new HashMap<>(states));
            data.setDirty();
            lastSaveTime = System.currentTimeMillis();
            Simukraft.LOGGER.debug("[JobRuntimeStateStore] Saved {} job runtime states", states.size());
        } catch (Exception e) {
            Simukraft.LOGGER.error("[JobRuntimeStateStore] Failed to save job runtime states", e);
        }
    }

    public long getTotalUpdates() {
        return totalUpdates;
    }

    public long getLastSaveTime() {
        return lastSaveTime;
    }

    private static class JobRuntimeStateData extends SavedData {
        private static final Codec<Map<UUID, JobRuntimeState>> CODEC =
                Codec.unboundedMap(Codec.STRING.xmap(UUID::fromString, UUID::toString), JobRuntimeState.CODEC);

        private Map<UUID, JobRuntimeState> states = new HashMap<>();

        public JobRuntimeStateData() {
        }

        public static JobRuntimeStateData load(CompoundTag tag) {
            JobRuntimeStateData data = new JobRuntimeStateData();
            data.deserialize(tag);
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CODEC.encodeStart(NbtOps.INSTANCE, states)
                    .resultOrPartial(e -> Simukraft.LOGGER.error("Failed to encode job state: {}", e))
                    .ifPresent(nbt -> tag.put("States", nbt));
            return tag;
        }

        private void deserialize(CompoundTag tag) {
            if (tag.contains("States")) {
                CODEC.parse(NbtOps.INSTANCE, tag.get("States"))
                        .resultOrPartial(e -> Simukraft.LOGGER.error("Failed to decode job state: {}", e))
                        .ifPresent(states -> this.states = states);
            }
        }

        public Map<UUID, JobRuntimeState> getStates() {
            return states;
        }

        public void setStates(Map<UUID, JobRuntimeState> states) {
            this.states = states;
            setDirty();
        }
    }
}
