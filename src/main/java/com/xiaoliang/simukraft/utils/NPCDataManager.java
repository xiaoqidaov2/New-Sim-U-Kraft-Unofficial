package com.xiaoliang.simukraft.utils;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.Objects;

public class NPCDataManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final String NPC_DIR = "npc";
    private static final String NPC_FILE = "npcdata.sk";
    private static final String JOB_FILE = "jobdata.sk";
    private static final String SKILL_FILE = "skilldata.sk";

    private static final int[] LEVEL_THRESHOLDS = {
        50, 150, 350, 650, 1150, 1850, 2850, 4050, 5550,
        7350, 9450, 11850, 14550, 17550, 20850, 24450, 28350, 32550, 37050
    };

    private static int getMaxLevel() {
        return com.xiaoliang.simukraft.config.ServerConfig.getNpcMaxLevel();
    }

    private static final Map<String, JobDataCache> jobDataCache = new ConcurrentHashMap<>();
    private static final Map<UUID, String> npcNameCache = new ConcurrentHashMap<>();
    private static final Map<UUID, SkillDataCache> skillDataCache = new ConcurrentHashMap<>();
    private static long lastCacheCleanTime = System.currentTimeMillis();
    private static final long CACHE_CLEAN_INTERVAL = 60000L;
    private static final long CACHE_ENTRY_TTL = 300000L;

    private static final BlockingQueue<SaveJobDataRequest> saveQueue = new LinkedBlockingQueue<>();
    private static final BlockingQueue<SaveSkillDataRequest> skillSaveQueue = new LinkedBlockingQueue<>();
    private static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NPCDataManager-SaveThread");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean saveThreadRunning = false;

    private static final class JobDataCache {
        final String status;
        final String job;
        final long timestamp;

        JobDataCache(String status, String job) {
            this.status = status;
            this.job = job;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_ENTRY_TTL;
        }
    }

    public static final class SkillDataCache {
        public final int level;
        public final int xp;
        public final long timestamp;

        public SkillDataCache(int level, int xp) {
            this.level = level;
            this.xp = xp;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_ENTRY_TTL;
        }
    }

    private record SaveJobDataRequest(MinecraftServer server, String npcId, String status, String job) {
    }

    private record SaveSkillDataRequest(MinecraftServer server, UUID npcUuid, int level, int xp) {
    }

    private static void startSaveThread() {
        if (saveThreadRunning) return;
        saveThreadRunning = true;
        saveExecutor.submit(() -> {
            while (saveThreadRunning) {
                try {
                    SaveJobDataRequest request = saveQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (request != null) {
                        saveJobDataSync(request.server(), request.npcId(), request.status(), request.job());
                    }
                    SaveSkillDataRequest skillRequest = skillSaveQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (skillRequest != null) {
                        saveSkillDataSync(skillRequest.server(), skillRequest.npcUuid(), skillRequest.level(), skillRequest.xp());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("异步保存NPC数据时发生错误", e);
                }
            }
        });
    }

    private static void cleanExpiredCache() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCacheCleanTime < CACHE_CLEAN_INTERVAL) return;

        lastCacheCleanTime = currentTime;
        jobDataCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        npcNameCache.entrySet().removeIf(entry -> currentTime - lastCacheCleanTime > CACHE_ENTRY_TTL);
        skillDataCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private static Path getWorldPath(MinecraftServer server) {
        Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
        return worldPath.toAbsolutePath().normalize();
    }

    public static void recordNPCData(MinecraftServer server, String npcId, String npcName, String skinName, String gender, UUID uuid, UUID cityId) {
        try {
            cleanExpiredCache();
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcDir)) {
                Files.createDirectories(npcDir);
            }

            JsonArray npcArray = new JsonArray();
            if (Files.exists(npcFile)) {
                try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                    npcArray = JsonParser.parseReader(reader).getAsJsonArray();
                } catch (JsonSyntaxException e) {
                    LOGGER.warn("Corrupted NPC data file, creating new one");
                }
            }

            JsonObject npc = new JsonObject();
            npc.addProperty("id", npcId);
            npc.addProperty("name", npcName);
            npc.addProperty("skin", skinName);
            npc.addProperty("gender", gender);
            npc.addProperty("uuid", uuid.toString());
            // 淇锛氫繚瀛樺煄甯侷D锛岃В鍐宠璺濆NPC鏃犳硶鏄剧ず鍦ㄩ泧浣ｅ垪琛ㄧ殑闂
            if (cityId != null) {
                npc.addProperty("cityId", cityId.toString());
            }

            npcArray.add(npc);

            try (Writer writer = Files.newBufferedWriter(npcFile, StandardCharsets.UTF_8)) {
                gson.toJson(npcArray, writer);
            }

            // 鍒濆鍖栬亴涓氭暟鎹?            initJobData(server, npcId, npcName, uuid);

            LOGGER.info("Recorded NPC data: {} | {} | {} | {} | {} | cityId={}", npcId, npcName, skinName, gender, uuid, cityId);
            System.out.println("[NPCDataManager] Successfully recorded: " + npcId + " | " + npcName + " | " + skinName + " | " + gender + " | " + uuid + " | cityId=" + cityId);
        } catch (Exception e) {
            LOGGER.error("Failed to record NPC data", e);
        }
    }

    /**
     * 璁板綍NPC鏁版嵁锛堝寘鍚勾榫勩€佺柧鐥呯姸鎬佸拰瀵垮懡锛?     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?     * @param npcId NPC ID
     * @param npcName NPC鍚嶇О
     * @param skinName 鐨偆鍚嶇О
     * @param gender 鎬у埆
     * @param uuid NPC UUID
     * @param cityId 鎵€灞炲煄甯侷D
     * @param age 骞撮緞
     * @param isSick 鏄惁鐢熺梾
     * @param lifespan 瀵垮懡
     */
    public static void recordNPCData(MinecraftServer server, String npcId, String npcName, String skinName, String gender, UUID uuid, UUID cityId, int age, boolean isSick, int lifespan) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcDir)) {
                Files.createDirectories(npcDir);
            }

            JsonArray npcArray = new JsonArray();
            if (Files.exists(npcFile)) {
                try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                    npcArray = JsonParser.parseReader(reader).getAsJsonArray();
                } catch (JsonSyntaxException e) {
                    LOGGER.warn("Corrupted NPC data file, creating new one");
                }
            }

            // 检查是否已存在该 NPC 的数据，存在则更新
            boolean exists = false;
            for (int i = 0; i < npcArray.size(); i++) {
                JsonObject existingNpc = npcArray.get(i).getAsJsonObject();
                if (existingNpc.get("uuid").getAsString().equals(uuid.toString())) {
                    // 鏇存柊鐜版湁鏁版嵁
                    existingNpc.addProperty("id", npcId);
                    existingNpc.addProperty("name", npcName);
                    existingNpc.addProperty("skin", skinName);
                    existingNpc.addProperty("gender", gender);
                    existingNpc.addProperty("age", age);
                    existingNpc.addProperty("isSick", isSick);
                    existingNpc.addProperty("lifespan", lifespan);
                    if (cityId != null) {
                        existingNpc.addProperty("cityId", cityId.toString());
                    }
                    npcArray.set(i, existingNpc);
                    exists = true;
                    break;
                }
            }

            // 不存在则新增记录
            if (!exists) {
                JsonObject npc = new JsonObject();
                npc.addProperty("id", npcId);
                npc.addProperty("name", npcName);
                npc.addProperty("skin", skinName);
                npc.addProperty("gender", gender);
                npc.addProperty("uuid", uuid.toString());
                npc.addProperty("age", age);
                npc.addProperty("isSick", isSick);
                npc.addProperty("lifespan", lifespan);
                if (cityId != null) {
                    npc.addProperty("cityId", cityId.toString());
                }
                npcArray.add(npc);
            }

            try (Writer writer = Files.newBufferedWriter(npcFile, StandardCharsets.UTF_8)) {
                gson.toJson(npcArray, writer);
            }

            // 鍒濆鍖栬亴涓氭暟鎹紙濡傛灉涓嶅瓨鍦級
            initJobData(server, npcId, npcName, uuid);

            LOGGER.info("Recorded NPC data with age, sickness and lifespan: {} | {} | age={} | isSick={} | lifespan={}", npcId, npcName, age, isSick, lifespan);
        } catch (Exception e) {
            LOGGER.error("Failed to record NPC data with age, sickness and lifespan", e);
        }
    }

    /**
     * 鑾峰彇鎸囧畾NPC鐨勫勾榫?     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?     * @param npcUuid NPC鐨刄UID
     * @return NPC骞撮緞锛屽鏋滄湭鎵惧埌杩斿洖18
     */
    public static int getNPCAge(MinecraftServer server, UUID npcUuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcFile = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR).resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return 18;
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            }

            for (JsonElement element : npcArray) {
                JsonObject npc = element.getAsJsonObject();
                if (npc.get("uuid").getAsString().equals(npcUuid.toString())) {
                    if (npc.has("age")) {
                        return npc.get("age").getAsInt();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC age", e);
        }
        return 18; // 榛樿骞撮緞
    }

    /**
     * 鑾峰彇鎸囧畾NPC鐨勭柧鐥呯姸鎬?     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?     * @param npcUuid NPC鐨刄UID
     * @return true琛ㄧず鐢熺梾锛宖alse琛ㄧず鍋ュ悍
     */
    public static boolean isNPCSick(MinecraftServer server, UUID npcUuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcFile = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR).resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return false;
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            }

            for (JsonElement element : npcArray) {
                JsonObject npc = element.getAsJsonObject();
                if (npc.get("uuid").getAsString().equals(npcUuid.toString())) {
                    if (npc.has("isSick")) {
                        return npc.get("isSick").getAsBoolean();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC sickness status", e);
        }
        return false; // 榛樿鍋ュ悍
    }

    /**
     * 鑾峰彇鎸囧畾NPC鐨勫鍛?     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?     * @param npcUuid NPC鐨刄UID
     * @return NPC瀵垮懡锛屽鏋滄湭鎵惧埌杩斿洖-1
     */
    public static int getNPCLifespan(MinecraftServer server, UUID npcUuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcFile = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR).resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return -1;
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            }

            for (JsonElement element : npcArray) {
                JsonObject npc = element.getAsJsonObject();
                if (npc.get("uuid").getAsString().equals(npcUuid.toString())) {
                    if (npc.has("lifespan")) {
                        return npc.get("lifespan").getAsInt();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC lifespan", e);
        }
        return -1; // 表示未找到
    }

    /**
     * 鏇存柊NPC骞撮緞
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?     * @param npcUuid NPC鐨刄UID
     * @param newAge 鏂扮殑骞撮緞
     */
    public static void updateNPCAge(MinecraftServer server, UUID npcUuid, int newAge) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return;
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            }

            // 查找并更新该 NPC 的年龄
            boolean updated = false;
            for (int i = 0; i < npcArray.size(); i++) {
                JsonObject npc = npcArray.get(i).getAsJsonObject();
                if (npc.get("uuid").getAsString().equals(npcUuid.toString())) {
                    npc.addProperty("age", newAge);
                    npcArray.set(i, npc);
                    updated = true;
                    break;
                }
            }

            if (updated) {
                try (Writer writer = Files.newBufferedWriter(npcFile, StandardCharsets.UTF_8)) {
                    gson.toJson(npcArray, writer);
                }
                LOGGER.debug("Updated NPC age: {} -> {} years", npcUuid, newAge);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to update NPC age", e);
        }
    }

    private static void initJobData(MinecraftServer server, String npcId, String npcName, UUID uuid) throws IOException {
        Path worldDir = getWorldPath(server);
        Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
        Path jobFile = npcDir.resolve(JOB_FILE);

        if (!Files.exists(npcDir)) {
            Files.createDirectories(npcDir);
        }

        JsonArray jobArray = new JsonArray();
        if (Files.exists(jobFile)) {
            try (Reader reader = Files.newBufferedReader(jobFile, StandardCharsets.UTF_8)) {
                jobArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted job data file, creating new one");
            }
        }

        // 检查是否已经存在该 NPC 的职业数据
        boolean exists = false;
        for (JsonElement element : jobArray) {
            JsonObject job = element.getAsJsonObject();
            if (job.get("id").getAsString().equals(npcId)) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            JsonObject job = new JsonObject();
            job.addProperty("id", npcId);
            job.addProperty("name", npcName);
            job.addProperty("uuid", uuid.toString());
            job.addProperty("status", "idle");
            job.addProperty("job", "unemployed");

            jobArray.add(job);

            try (Writer writer = Files.newBufferedWriter(jobFile, StandardCharsets.UTF_8)) {
                gson.toJson(jobArray, writer);
            }
        }

        // 鍒濆鍖栫啛缁冨害鏁版嵁
        initSkillData(server, uuid);
    }

    private static void initSkillData(MinecraftServer server, UUID uuid) throws IOException {
        Path worldDir = getWorldPath(server);
        Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
        Path skillFile = npcDir.resolve(SKILL_FILE);

        if (!Files.exists(npcDir)) {
            Files.createDirectories(npcDir);
        }

        JsonArray skillArray = new JsonArray();
        if (Files.exists(skillFile)) {
            try (Reader reader = Files.newBufferedReader(skillFile, StandardCharsets.UTF_8)) {
                skillArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted skill data file, creating new one");
            }
        }

        // 妫€鏌ユ槸鍚﹀凡瀛樺湪璇PC鐨勭啛缁冨害鏁版嵁
        boolean exists = false;
        for (JsonElement element : skillArray) {
            JsonObject skill = element.getAsJsonObject();
            if (skill.get("uuid").getAsString().equals(uuid.toString())) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            JsonObject skill = new JsonObject();
            skill.addProperty("uuid", uuid.toString());
            skill.addProperty("level", 1);
            skill.addProperty("xp", 0);

            skillArray.add(skill);

            try (Writer writer = Files.newBufferedWriter(skillFile, StandardCharsets.UTF_8)) {
                gson.toJson(skillArray, writer);
            }

            // 鏇存柊缂撳瓨
            skillDataCache.put(uuid, new SkillDataCache(1, 0));
        }
    }

    public static void saveJobData(MinecraftServer server, String npcId, String status, String job) {
        // 妫€鏌ョ紦瀛橈紝濡傛灉鏁版嵁娌℃湁鍙樺寲鍒欎笉淇濆瓨
        JobDataCache cached = jobDataCache.get(npcId);
        if (cached != null && cached.status.equals(status) && cached.job.equals(job)) {
            return; // 鏁版嵁鏈彉鍖栵紝璺宠繃淇濆瓨
        }

        // 鏇存柊缂撳瓨
        jobDataCache.put(npcId, new JobDataCache(status, job));

        // 鍚姩寮傛淇濆瓨绾跨▼锛堝鏋滄湭鍚姩锛?        startSaveThread();

        // 娣诲姞鍒板紓姝ヤ繚瀛橀槦鍒?        saveQueue.offer(new SaveJobDataRequest(server, npcId, status, job));
    }

    // 鍚屾淇濆瓨鏂规硶锛堝湪鍚庡彴绾跨▼涓墽琛岋級
    private static void saveJobDataSync(MinecraftServer server, String npcId, String status, String job) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path jobFile = npcDir.resolve(JOB_FILE);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcDir)) {
                Files.createDirectories(npcDir);
            }

            JsonArray jobArray = new JsonArray();
            if (Files.exists(jobFile)) {
                try (Reader reader = Files.newBufferedReader(jobFile, StandardCharsets.UTF_8)) {
                    jobArray = JsonParser.parseReader(reader).getAsJsonArray();
                } catch (JsonSyntaxException e) {
                    LOGGER.warn("Corrupted job data file, creating new one");
                }
            }

            // 检查是否已经存在该 NPC 的职业数据
            boolean exists = false;
            for (int i = 0; i < jobArray.size(); i++) {
                JsonObject jobObj = jobArray.get(i).getAsJsonObject();
                if (jobObj.get("id").getAsString().equals(npcId)) {
                    jobObj.addProperty("status", status);
                    jobObj.addProperty("job", job);
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                // 娣诲姞鏂版暟鎹紝灏濊瘯浠嶯PC鏁版嵁鏂囦欢涓幏鍙朥UID
                UUID uuid = null;
                if (Files.exists(npcFile)) {
                    try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                        JsonArray npcArray = JsonParser.parseReader(reader).getAsJsonArray();
                        for (JsonElement npcElement : npcArray) {
                            JsonObject npcObj = npcElement.getAsJsonObject();
                            if (npcObj.get("id").getAsString().equals(npcId)) {
                                if (npcObj.has("uuid")) {
                                    uuid = UUID.fromString(npcObj.get("uuid").getAsString());
                                }
                                break;
                            }
                        }
                    } catch (JsonSyntaxException e) {
                        LOGGER.warn("Corrupted NPC data file, cannot get UUID");
                    }
                }

                // 添加新数据
                JsonObject jobObj = new JsonObject();
                jobObj.addProperty("id", npcId);
                if (uuid != null) {
                    jobObj.addProperty("uuid", uuid.toString());
                }
                jobObj.addProperty("status", status);
                jobObj.addProperty("job", job);
                jobArray.add(jobObj);
            }

            try (Writer writer = Files.newBufferedWriter(jobFile, StandardCharsets.UTF_8)) {
                gson.toJson(jobArray, writer);
            }

            LOGGER.debug("Saved job data: {} | {} | {}", npcId, status, job);
        } catch (Exception e) {
            LOGGER.error("Failed to save job data", e);
        }
    }
    
    /**
     * Saves NPC job state regardless of NPC id.
     */
    public static void saveJobData(com.xiaoliang.simukraft.entity.CustomEntity npc) {
        LOGGER.debug(
                "[NPCDataManager] Begin saveJobData for npc={}",
                npc != null ? npc.getFullName() + " (UUID: " + npc.getUUID() + ")" : "null"
        );

        if (npc == null) {
            LOGGER.warn("[NPCDataManager] saveJobData skipped because npc is null");
            return;
        }

        if (npc.level().isClientSide) {
            LOGGER.debug("[NPCDataManager] saveJobData skipped on client side for npcId={}", npc.getNpcId());
            return;
        }
        
        String statusStr = npc.getWorkStatus() == com.xiaoliang.simukraft.entity.WorkStatus.IDLE ? "idle" : "working";
        
        // 无论 npcId 是否为 1，都保存工作状态
        String npcIdStr = "npc" + npc.getNpcId();
        LOGGER.debug(
                "[NPCDataManager] saveJobData args npcId={}, status={}, job={}",
                npcIdStr,
                statusStr,
                npc.getJob()
        );
        
        saveJobData(
                ((ServerLevel) npc.level()).getServer(),
                npcIdStr,
                statusStr,
                npc.getJob()
        );
        
        LOGGER.debug("[NPCDataManager] saveJobData completed for npcId={}", npcIdStr);
    }

    public static String[] getJobData(MinecraftServer server, String npcId) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path jobFile = npcDir.resolve(JOB_FILE);

            if (!Files.exists(jobFile)) {
                return new String[]{"idle", "unemployed"};
            }

            JsonArray jobArray;
            try (Reader reader = Files.newBufferedReader(jobFile, StandardCharsets.UTF_8)) {
                jobArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted job data file, returning default");
                return new String[]{"idle", "unemployed"};
            }

            for (JsonElement element : jobArray) {
                JsonObject jobObj = element.getAsJsonObject();
                if (jobObj.get("id").getAsString().equals(npcId)) {
                    return new String[]{
                            jobObj.get("status").getAsString(),
                            jobObj.get("job").getAsString()
                    };
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get job data for NPC: " + npcId, e);
        }
        return new String[]{"idle", "unemployed"};
    }
    
    /**
     * 鏍规嵁UUID浠巒pcdata.sk鏂囦欢涓幏鍙朜PC鍚嶇О
     */
    public static String getNPCNameByUUID(MinecraftServer server, UUID uuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return "鏈煡NPC";
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted NPC data file, returning default");
                return "鏈煡NPC";
            }

            for (JsonElement element : npcArray) {
                JsonObject npcObj = element.getAsJsonObject();
                if (npcObj.has("uuid") && UUID.fromString(npcObj.get("uuid").getAsString()).equals(uuid)) {
                    return npcObj.get("name").getAsString();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC name by UUID: " + uuid, e);
        }
        return "鏈煡NPC";
    }

    /**
     * 鏍规嵁UUID浠巒pcdata.sk鏂囦欢涓幏鍙朜PC ID
     */
    public static String getNPCIdByUUID(MinecraftServer server, UUID uuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return null;
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted NPC data file, returning null");
                return null;
            }

            for (JsonElement element : npcArray) {
                JsonObject npcObj = element.getAsJsonObject();
                if (npcObj.has("uuid") && UUID.fromString(npcObj.get("uuid").getAsString()).equals(uuid)) {
                    return npcObj.get("id").getAsString();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC ID by UUID: " + uuid, e);
        }
        return null;
    }

    /**
     * 鏍规嵁UUID浠巎obdata.sk鏂囦欢涓幏鍙朜PC鑱屼笟
     */
    public static String getJobByUUID(MinecraftServer server, UUID uuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path jobFile = npcDir.resolve(JOB_FILE);

            if (!Files.exists(jobFile)) {
                return "unemployed";
            }

            JsonArray jobArray;
            try (Reader reader = Files.newBufferedReader(jobFile, StandardCharsets.UTF_8)) {
                jobArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted job data file, returning default");
                return "unemployed";
            }

            for (JsonElement element : jobArray) {
                JsonObject jobObj = element.getAsJsonObject();
                if (jobObj.has("uuid") && UUID.fromString(jobObj.get("uuid").getAsString()).equals(uuid)) {
                    return jobObj.get("job").getAsString();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get job by UUID: " + uuid, e);
        }
        return "unemployed";
    }

    /**
     * 鏇存柊NPC鍚嶇О缂撳瓨
     */
    public static void updateNPCNameCache(UUID uuid, String newName) {
        npcNameCache.put(uuid, newName);
    }

    /**
     * Removes cached NPC name data.
     */
    public static void removeNPCFromCache(UUID uuid) {
        npcNameCache.remove(uuid);
        // 鐢变簬jobDataCache鐨勭粨鏋勯檺鍒讹紙浣跨敤npcId浣滀负key锛岃€屼笉鏄疷UID锛夛紝
        // 鎴戜滑鏃犳硶鐩存帴鏍规嵁UUID鍒犻櫎鐗瑰畾鐨勮亴涓氭暟鎹紦瀛樻潯鐩?        // 杩欎簺缂撳瓨浼氬湪鑷劧杩囨湡鍚庤娓呯悊
        LOGGER.debug("[NPCDataManager] Removed NPC name cache for uuid={}", uuid);
    }

    /**
     * 閫氳繃UUID鍒犻櫎NPC鏁版嵁
     */
    public static void removeNPCDataByUUID(MinecraftServer server, UUID uuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);
            Path jobFile = npcDir.resolve(JOB_FILE);

            String uuidStr = uuid.toString();
            boolean found = false;

            // 鍒犻櫎NPC鏁版嵁
            if (Files.exists(npcFile)) {
                JsonArray npcArray;
                try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                    npcArray = JsonParser.parseReader(reader).getAsJsonArray();
                } catch (JsonSyntaxException e) {
                    LOGGER.warn("Corrupted NPC data file, skipping removal");
                    return;
                }

                JsonArray newNpcArray = new JsonArray();
                for (JsonElement element : npcArray) {
                    JsonObject npc = element.getAsJsonObject();
                    if (!npc.has("uuid") || !npc.get("uuid").getAsString().equals(uuidStr)) {
                        newNpcArray.add(npc);
                    } else {
                        found = true;
                    }
                }

                try (Writer writer = Files.newBufferedWriter(npcFile, StandardCharsets.UTF_8)) {
                    gson.toJson(newNpcArray, writer);
                }
            }

            // 鍒犻櫎鑱屼笟鏁版嵁
            if (Files.exists(jobFile)) {
                JsonArray jobArray;
                try (Reader reader = Files.newBufferedReader(jobFile, StandardCharsets.UTF_8)) {
                    jobArray = JsonParser.parseReader(reader).getAsJsonArray();
                } catch (JsonSyntaxException e) {
                    LOGGER.warn("Corrupted job data file, skipping removal");
                    return;
                }

                JsonArray newJobArray = new JsonArray();
                for (JsonElement element : jobArray) {
                    JsonObject job = element.getAsJsonObject();
                    if (!job.has("uuid") || !job.get("uuid").getAsString().equals(uuidStr)) {
                        newJobArray.add(job);
                    }
                }

                try (Writer writer = Files.newBufferedWriter(jobFile, StandardCharsets.UTF_8)) {
                    gson.toJson(newJobArray, writer);
                }
            }

            if (found) {
                LOGGER.info("Successfully removed NPC and job data for UUID: {}", uuid);
            } else {
                LOGGER.warn("No NPC found with UUID: {}", uuid);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to remove NPC and job data for UUID: " + uuid, e);
        }
    }

    /**
     * 閫氳繃NPC ID鍒犻櫎NPC鏁版嵁
     */
    public static void removeNPCData(MinecraftServer server, String npcId) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);
            Path jobFile = npcDir.resolve(JOB_FILE);

            // 鍒犻櫎NPC鏁版嵁
            if (Files.exists(npcFile)) {
                JsonArray npcArray;
                try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                    npcArray = JsonParser.parseReader(reader).getAsJsonArray();
                } catch (JsonSyntaxException e) {
                    LOGGER.warn("Corrupted NPC data file, skipping removal");
                    return;
                }

                JsonArray newNpcArray = new JsonArray();
                for (JsonElement element : npcArray) {
                    JsonObject npc = element.getAsJsonObject();
                    if (!npc.get("id").getAsString().equals(npcId)) {
                        newNpcArray.add(npc);
                    }
                }

                try (Writer writer = Files.newBufferedWriter(npcFile, StandardCharsets.UTF_8)) {
                    gson.toJson(newNpcArray, writer);
                }
            }

            // 鍒犻櫎鑱屼笟鏁版嵁
            if (Files.exists(jobFile)) {
                JsonArray jobArray;
                try (Reader reader = Files.newBufferedReader(jobFile, StandardCharsets.UTF_8)) {
                    jobArray = JsonParser.parseReader(reader).getAsJsonArray();
                } catch (JsonSyntaxException e) {
                    LOGGER.warn("Corrupted job data file, skipping removal");
                    return;
                }

                JsonArray newJobArray = new JsonArray();
                for (JsonElement element : jobArray) {
                    JsonObject job = element.getAsJsonObject();
                    // 閫氳繃ID鎴朥UID鍒犻櫎
                    if (!job.get("id").getAsString().equals(npcId)) {
                        newJobArray.add(job);
                    }
                }

                try (Writer writer = Files.newBufferedWriter(jobFile, StandardCharsets.UTF_8)) {
                    gson.toJson(newJobArray, writer);
                }
            }

            LOGGER.info("Successfully removed NPC and job data for ID: {}", npcId);
        } catch (Exception e) {
            LOGGER.error("Failed to remove NPC and job data for ID: " + npcId, e);
        }
    }

    // ==================== 鐔熺粌搴︾郴缁熸柟娉?====================

    /**
     * 鑾峰彇NPC褰撳墠绛夌骇
     */
    public static int getNPCLevel(MinecraftServer server, UUID npcUuid) {
        // 先检查缓存
        SkillDataCache cached = skillDataCache.get(npcUuid);
        if (cached != null && !cached.isExpired()) {
            return cached.level;
        }

        // 从文件读取
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path skillFile = npcDir.resolve(SKILL_FILE);

            if (!Files.exists(skillFile)) {
                return 1;
            }

            JsonArray skillArray;
            try (Reader reader = Files.newBufferedReader(skillFile, StandardCharsets.UTF_8)) {
                skillArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted skill data file, returning default level");
                return 1;
            }

            for (JsonElement element : skillArray) {
                JsonObject skill = element.getAsJsonObject();
                if (skill.get("uuid").getAsString().equals(npcUuid.toString())) {
                    return skill.get("level").getAsInt();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC level for UUID: " + npcUuid, e);
        }
        return 1;
    }

    /**
     * 鑾峰彇NPC褰撳墠缁忛獙鍊?     */
    public static int getNPCXp(MinecraftServer server, UUID npcUuid) {
        // 先检查缓存
        SkillDataCache cached = skillDataCache.get(npcUuid);
        if (cached != null && !cached.isExpired()) {
            return cached.xp;
        }

        // 从文件读取
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path skillFile = npcDir.resolve(SKILL_FILE);

            if (!Files.exists(skillFile)) {
                return 50;
            }

            JsonArray skillArray;
            try (Reader reader = Files.newBufferedReader(skillFile, StandardCharsets.UTF_8)) {
                skillArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted skill data file, returning default xp");
                return 50;
            }

            for (JsonElement element : skillArray) {
                JsonObject skill = element.getAsJsonObject();
                if (skill.get("uuid").getAsString().equals(npcUuid.toString())) {
                    return skill.get("xp").getAsInt();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC xp for UUID: " + npcUuid, e);
        }
        return 50;
    }

    /**
     * 鑾峰彇NPC鐔熺粌搴︽暟鎹?     */
    public static SkillDataCache getNPCSkillData(MinecraftServer server, UUID npcUuid) {
        int level = getNPCLevel(server, npcUuid);
        int xp = getNPCXp(server, npcUuid);
        return new SkillDataCache(level, xp);
    }

    /**
     * 娣诲姞缁忛獙鍊煎苟妫€鏌ュ崌绾?     * @return 鏄惁鍗囩骇浜?     */
    public static boolean addXp(MinecraftServer server, UUID npcUuid, int xpToAdd) {
        int currentLevel = getNPCLevel(server, npcUuid);
        int currentXp = getNPCXp(server, npcUuid);

        int newXp = currentXp + xpToAdd;
        int newLevel = currentLevel;

        // 妫€鏌ユ槸鍚﹀崌绾?- LEVEL_THRESHOLDS[i] 琛ㄧず鍗囧埌 (i+2) 绾ф墍闇€鐨勬€荤粡楠屽€?        // 渚嬪锛歀EVEL_THRESHOLDS[0]=50 琛ㄧず鍗囧埌2绾ч渶瑕?0xp
        // LEVEL_THRESHOLDS[1]=150 琛ㄧず鍗囧埌3绾ч渶瑕?50xp
        int maxLevel = getMaxLevel();
        while (newLevel < maxLevel && newXp >= LEVEL_THRESHOLDS[newLevel - 1]) {
            newLevel++;
        }

        // 保存新数据
        saveSkillData(server, npcUuid, newLevel, newXp);

        return newLevel > currentLevel;
    }

    /**
     * 淇濆瓨鐔熺粌搴︽暟鎹?     */
    public static void saveSkillData(MinecraftServer server, UUID npcUuid, int level, int xp) {
        // 妫€鏌ョ紦瀛橈紝濡傛灉鏁版嵁娌℃湁鍙樺寲鍒欎笉淇濆瓨
        SkillDataCache cached = skillDataCache.get(npcUuid);
        if (cached != null && cached.level == level && cached.xp == xp) {
            return;
        }

        // 鏇存柊缂撳瓨
        skillDataCache.put(npcUuid, new SkillDataCache(level, xp));

        // 鍚姩寮傛淇濆瓨绾跨▼
        startSaveThread();

        // 添加到异步保存队列
        skillSaveQueue.offer(new SaveSkillDataRequest(server, npcUuid, level, xp));
    }

    // 同步保存熟练度数据，在后台线程中执行
    private static void saveSkillDataSync(MinecraftServer server, UUID npcUuid, int level, int xp) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path skillFile = npcDir.resolve(SKILL_FILE);

            if (!Files.exists(npcDir)) {
                Files.createDirectories(npcDir);
            }

            JsonArray skillArray = new JsonArray();
            if (Files.exists(skillFile)) {
                try (Reader reader = Files.newBufferedReader(skillFile, StandardCharsets.UTF_8)) {
                    skillArray = JsonParser.parseReader(reader).getAsJsonArray();
                } catch (JsonSyntaxException e) {
                    LOGGER.warn("Corrupted skill data file, creating new one");
                }
            }

            // 妫€鏌ユ槸鍚﹀凡瀛樺湪璇PC鐨勭啛缁冨害鏁版嵁
            boolean exists = false;
            for (int i = 0; i < skillArray.size(); i++) {
                JsonObject skill = skillArray.get(i).getAsJsonObject();
                if (skill.get("uuid").getAsString().equals(npcUuid.toString())) {
                    skill.addProperty("level", level);
                    skill.addProperty("xp", xp);
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                JsonObject skill = new JsonObject();
                skill.addProperty("uuid", npcUuid.toString());
                skill.addProperty("level", level);
                skill.addProperty("xp", xp);
                skillArray.add(skill);
            }

            try (Writer writer = Files.newBufferedWriter(skillFile, StandardCharsets.UTF_8)) {
                gson.toJson(skillArray, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save skill data for UUID: " + npcUuid, e);
        }
    }

    /**
     * 鑾峰彇鍗囩骇鍒颁笅涓€绾ф墍闇€鐨勭粡楠屽€?     */
    public static int getXpToNextLevel(int currentLevel) {
        int maxLevel = getMaxLevel();
        if (currentLevel >= maxLevel) {
            return 0;
        }
        return LEVEL_THRESHOLDS[currentLevel - 1];
    }

    /**
     * 鑾峰彇褰撳墠绛夌骇杩涘害鐧惧垎姣?     */
    public static float getLevelProgress(MinecraftServer server, UUID npcUuid) {
        int currentLevel = getNPCLevel(server, npcUuid);
        int currentXp = getNPCXp(server, npcUuid);
        int maxLevel = getMaxLevel();

        if (currentLevel >= maxLevel) {
            return 1.0f;
        }

        int prevThreshold = currentLevel > 1 ? LEVEL_THRESHOLDS[currentLevel - 2] : 0;
        int nextThreshold = LEVEL_THRESHOLDS[currentLevel - 1];
        int xpInLevel = currentXp - prevThreshold;
        int xpNeeded = nextThreshold - prevThreshold;

        return (float) xpInLevel / xpNeeded;
    }

    // ==================== V2瀛樺偍鏀噣鏂规硶 ====================

    /**
     * 鑾峰彇鎵€鏈塎PC鐨刄UID鍒楄〃
     * 浠巓pcdata.sk鏂囦欢涓鍙�     */
    public static List<UUID> getAllNPCUuids(MinecraftServer server) {
        List<UUID> uuids = new ArrayList<>();
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return uuids;
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted NPC data file, returning empty list");
                return uuids;
            }

            for (JsonElement element : npcArray) {
                JsonObject npcObj = element.getAsJsonObject();
                if (npcObj.has("uuid")) {
                    try {
                        uuids.add(UUID.fromString(npcObj.get("uuid").getAsString()));
                    } catch (IllegalArgumentException e) {
                        // 蹇界暐鏃犳晥鐨刄UID
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get all NPC UUIDs", e);
        }
        return uuids;
    }

    /**
     * 鏍规嵁UUID鑾峰彇NPC鍚嶇О
     */
    public static String getNPCName(MinecraftServer server, UUID uuid) {
        return getNPCNameByUUID(server, uuid);
    }

    /**
     * 鏍规嵁UUID鑾峰彇NPC鎵€灞炲煄甯俰D
     */
    public static String getNPCCityId(MinecraftServer server, UUID uuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return null;
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted NPC data file, returning null");
                return null;
            }

            for (JsonElement element : npcArray) {
                JsonObject npcObj = element.getAsJsonObject();
                if (npcObj.has("uuid") && UUID.fromString(npcObj.get("uuid").getAsString()).equals(uuid)) {
                    if (npcObj.has("cityId")) {
                        return npcObj.get("cityId").getAsString();
                    }
                    return null;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC city ID for UUID: " + uuid, e);
        }
        return null;
    }

    /**
     * 鏍规嵁UUID鑾峰彇NPC鐨偆璺緞
     */
    public static String getNPCSkinPath(MinecraftServer server, UUID uuid) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(NPC_DIR);
            Path npcFile = npcDir.resolve(NPC_FILE);

            if (!Files.exists(npcFile)) {
                return "";
            }

            JsonArray npcArray;
            try (Reader reader = Files.newBufferedReader(npcFile, StandardCharsets.UTF_8)) {
                npcArray = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (JsonSyntaxException e) {
                LOGGER.warn("Corrupted NPC data file, returning empty string");
                return "";
            }

            for (JsonElement element : npcArray) {
                JsonObject npcObj = element.getAsJsonObject();
                if (npcObj.has("uuid") && UUID.fromString(npcObj.get("uuid").getAsString()).equals(uuid)) {
                    if (npcObj.has("skin")) {
                        return npcObj.get("skin").getAsString();
                    }
                    return "";
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get NPC skin path for UUID: " + uuid, e);
        }
        return "";
    }
}
