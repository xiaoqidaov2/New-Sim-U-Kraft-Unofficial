package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.ControlBoxDataManager;
import com.xiaoliang.simukraft.building.MedicalBuildingConfig;
import com.xiaoliang.simukraft.building.MedicalBuildingManager;
import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.employment.service.EmploymentCommands;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.Gender;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.init.ModEntities;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.BaseBuildingHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public final class NPCFamilyManager {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final String PREGNANCY_STAGE_PREGNANT = "pregnant";
    public static final String PREGNANCY_STAGE_LABOR = "labor";

    private static final String STATUS_WITH_SPOUSE = "gui.npc.status.with_spouse";
    private static final String STATUS_PREGNANT_HOME = "gui.npc.status.pregnant_home";
    private static final String STATUS_IN_LABOR = "gui.npc.status.in_labor";

    private static final int NIGHT_AFFECTION_CHECK_TIME = 13000;
    private static final int NIGHT_AFFECTION_CHECK_WINDOW = 100;
    private static final double NIGHT_AFFECTION_CHANCE = 0.30D;
    private static final double PREGNANCY_CHANCE = 0.40D;
    private static final int HEART_DELAY_TICKS = 120;
    private static final int HEART_INTERVAL_TICKS = 20;
    private static final int AFFECTION_DURATION_TICKS = 600;
    private static final long AFFECTION_NOT_STARTED = -1L;
    private static final long HOSPITAL_CACHE_TTL_TICKS = 1200L;
    private static final long HOSPITAL_QUEUE_MESSAGE_INTERVAL_TICKS = 200L;
    private static final long LABOR_AFTER_FULL_DAYS = 10L;
    private static final long LABOR_BIRTH_TICKS = 600L;
    private static final double DEBUG_RADIUS = 16.0D;
    private static final double DEBUG_INTIMACY_TRIGGER_RADIUS = 3.0D;
    private static final double INTIMACY_PARTNER_CLOSE_DISTANCE_SQR = 2.25D;
    private static final double INTIMACY_TARGET_REACHED_DISTANCE_SQR = 1.0D;
    private static final double LABOR_READY_RANGE_SQR = 144.0D;
    private static final double DOCTOR_READY_DISTANCE_SQR = 6.25D;
    private static final UUID HOSPITAL_CACHE_FALLBACK_KEY = new UUID(0L, 0L);

    private static final Map<UUID, IntimacySession> activeSessions = new ConcurrentHashMap<>();
    private static final Set<UUID> pregnancyManagedNpcUuids = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, CachedHospitalTarget> hospitalTargetCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> laborStartGameTimes = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> laborBedAssignments = new ConcurrentHashMap<>();
    private static final Map<String, Long> hospitalQueueMessageTimes = new ConcurrentHashMap<>();
    private static boolean nightAffectionTriggered = false;
    private static long lastPregnancyDayCheck = Long.MIN_VALUE;

    static {
        registerCleanupHandlers();
    }

    private static void registerCleanupHandlers() {
        GlobalResourceCleaner.registerCleanableResource("NPCFamilyManager-activeSessions", () -> {
            activeSessions.clear();
            Simukraft.LOGGER.debug("[NPCFamilyManager] 已清理 activeSessions");
        });

        GlobalResourceCleaner.registerCleanableResource("NPCFamilyManager-pregnancyManaged", () -> {
            pregnancyManagedNpcUuids.clear();
            Simukraft.LOGGER.debug("[NPCFamilyManager] 已清理 pregnancyManagedNpcUuids");
        });

        GlobalResourceCleaner.registerCleanableResource("NPCFamilyManager-hospitalCache", () -> {
            hospitalTargetCache.clear();
            laborStartGameTimes.clear();
            laborBedAssignments.clear();
            hospitalQueueMessageTimes.clear();
            Simukraft.LOGGER.debug("[NPCFamilyManager] 已清理医院相关缓存");
        });
    }

    public static void cleanupAllCaches() {
        activeSessions.clear();
        pregnancyManagedNpcUuids.clear();
        hospitalTargetCache.clear();
        laborStartGameTimes.clear();
        laborBedAssignments.clear();
        hospitalQueueMessageTimes.clear();
        nightAffectionTriggered = false;
        lastPregnancyDayCheck = Long.MIN_VALUE;
        
        Simukraft.LOGGER.info("[NPCFamilyManager] 所有缓存已清理");
    }

    private NPCFamilyManager() {
    }

    public record FamilyPair(CustomEntity maleNpc, CustomEntity femaleNpc) {
    }

    private record HospitalTarget(ServerLevel level,
                                  BlockPos controlBoxPos,
                                  @Nullable String buildingName,
                                  @Nullable String buildingFileName,
                                  boolean canParturition) {
    }

    private record CachedHospitalTarget(@Nullable HospitalTarget target, long cachedAtGameTime) {
    }

    private record IntimacySession(UUID maleUuid,
                                   UUID femaleUuid,
                                   ResourceKey<Level> levelKey,
                                   BlockPos maleStandPos,
                                   BlockPos femaleStandPos,
                                   long affectionStartGameTime,
                                   boolean pregnancyGuaranteed) {
    }

    public static void onServerStart() {
        activeSessions.clear();
        pregnancyManagedNpcUuids.clear();
        hospitalTargetCache.clear();
        laborStartGameTimes.clear();
        laborBedAssignments.clear();
        hospitalQueueMessageTimes.clear();
        nightAffectionTriggered = false;
        lastPregnancyDayCheck = Long.MIN_VALUE;
    }

    public static boolean isNpcBusyWithFamily(UUID npcUuid) {
        if (npcUuid == null) {
            return false;
        }
        if (pregnancyManagedNpcUuids.contains(npcUuid)) {
            return true;
        }
        for (IntimacySession session : activeSessions.values()) {
            if (session != null && (npcUuid.equals(session.maleUuid()) || npcUuid.equals(session.femaleUuid()))) {
                return true;
            }
        }
        return false;
    }

    public static void tickServer(MinecraftServer server, ServerLevel overworld) {
        if (server == null || overworld == null) {
            return;
        }

        tickIntimacySessions(server);
        detectNightAffection(server, overworld);

        if (overworld.getGameTime() % 20L == 0L) {
            maintainLoadedPregnancyNpcs(server);
        }

        long currentDay = overworld.getDayTime() / 24000L;
        if (currentDay != lastPregnancyDayCheck) {
            lastPregnancyDayCheck = currentDay;
            advancePregnancyByDay(server, currentDay);
        }
    }

    @Nullable
    public static FamilyPair forceIntimacyNearPlayer(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        MinecraftServer server = player.getServer();
        List<CustomEntity> nearbyNpcs = new ArrayList<>(
                player.serverLevel().getEntitiesOfClass(CustomEntity.class, player.getBoundingBox().inflate(DEBUG_INTIMACY_TRIGGER_RADIUS))
        );
        nearbyNpcs.sort((left, right) -> Double.compare(left.distanceToSqr(player), right.distanceToSqr(player)));

        for (CustomEntity npc : nearbyNpcs) {
            UUID spouseUuid = NPCDataManager.getNPCSpouseUuid(server, npc.getUUID());
            if (spouseUuid == null) {
                continue;
            }
            CustomEntity spouseNpc = BaseBuildingHiredData.findNPCByUuid(server, spouseUuid);
            if (spouseNpc == null || spouseNpc == npc) {
                continue;
            }

            FamilyPair pair = toFamilyPair(npc, spouseNpc);
            if (pair == null || !isValidMarriedPair(server, pair.maleNpc(), pair.femaleNpc(), false)) {
                continue;
            }

            clearFamilyState(server, pair.maleNpc().getUUID());
            clearFamilyState(server, pair.femaleNpc().getUUID());
            return startIntimacySession(server, pair.maleNpc(), pair.femaleNpc(), false, false) ? pair : null;
        }
        return null;
    }

    @Nullable
    public static CustomEntity forcePregnancyNearPlayer(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        CustomEntity femaleNpc = findNearbyMarriedFemale(player, false);
        if (femaleNpc == null) {
            return null;
        }
        if (!setPregnant(player.getServer(), femaleNpc.getUUID())) {
            return null;
        }
        return femaleNpc;
    }

    @Nullable
    public static CustomEntity forceLaborNearPlayer(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        CustomEntity femaleNpc = findNearbyMarriedFemale(player, true);
        if (femaleNpc == null) {
            return null;
        }
        if (!enterLabor(player.getServer(), femaleNpc.getUUID())) {
            return null;
        }
        return femaleNpc;
    }

    @Nullable
    public static CustomEntity forceBirthNearPlayer(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        MinecraftServer server = player.getServer();
        CustomEntity femaleNpc = findNearbyMarriedFemale(player, true);
        if (femaleNpc == null) {
            return null;
        }

        NPCDataManager.NPCPregnancyData pregnancyData = NPCDataManager.getNPCPregnancyData(server, femaleNpc.getUUID());
        if (pregnancyData == null) {
            return null;
        }

        if (!PREGNANCY_STAGE_LABOR.equalsIgnoreCase(pregnancyData.stage())) {
            if (!enterLabor(server, femaleNpc.getUUID())) {
                return null;
            }
        }

        keepNpcInLabor(server, femaleNpc);
        completeBirth(server, femaleNpc);
        return femaleNpc;
    }

    public static CustomEntity clearFamilyStateNearPlayer(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        List<CustomEntity> nearbyNpcs = new ArrayList<>(
                player.serverLevel().getEntitiesOfClass(CustomEntity.class, player.getBoundingBox().inflate(DEBUG_RADIUS))
        );
        nearbyNpcs.sort((left, right) -> Double.compare(left.distanceToSqr(player), right.distanceToSqr(player)));
        for (CustomEntity npc : nearbyNpcs) {
            UUID spouseUuid = NPCDataManager.getNPCSpouseUuid(player.getServer(), npc.getUUID());
            boolean hasFamilyState = spouseUuid != null
                    || NPCDataManager.isNPCPregnantOrInLabor(player.getServer(), npc.getUUID())
                    || isNpcBusyWithFamily(npc.getUUID());
            if (!hasFamilyState) {
                continue;
            }
            clearFamilyState(player.getServer(), npc.getUUID());
            return npc;
        }
        return null;
    }

    private static void detectNightAffection(MinecraftServer server, ServerLevel overworld) {
        long dayTime = overworld.getDayTime() % 24000L;
        if (dayTime >= NIGHT_AFFECTION_CHECK_TIME
                && dayTime < NIGHT_AFFECTION_CHECK_TIME + NIGHT_AFFECTION_CHECK_WINDOW) {
            if (!nightAffectionTriggered) {
                nightAffectionTriggered = true;
                tryRandomNightAffection(server);
            }
            return;
        }

        if (dayTime >= NIGHT_AFFECTION_CHECK_TIME + NIGHT_AFFECTION_CHECK_WINDOW || dayTime < 12000L) {
            nightAffectionTriggered = false;
        }
    }

    private static void tryRandomNightAffection(MinecraftServer server) {
        if (server == null) {
            return;
        }

        List<CustomEntity> loadedNpcs = new ArrayList<>(NPCTaskScheduler.getAllNPCs(server));
        if (loadedNpcs.size() < 2) {
            return;
        }

        Map<UUID, CustomEntity> loadedByUuid = new ConcurrentHashMap<>();
        for (CustomEntity npc : loadedNpcs) {
            loadedByUuid.put(npc.getUUID(), npc);
        }

        Map<UUID, UUID> spouseMap = NPCDataManager.getAllNPCSpouseUuids(server);
        List<FamilyPair> candidates = new ArrayList<>();
        for (CustomEntity femaleNpc : loadedNpcs) {
            if (femaleNpc.getGender() != Gender.FEMALE) {
                continue;
            }
            UUID spouseUuid = spouseMap.get(femaleNpc.getUUID());
            if (spouseUuid == null) {
                continue;
            }
            CustomEntity maleNpc = loadedByUuid.get(spouseUuid);
            if (!isValidMarriedPair(server, maleNpc, femaleNpc, true)) {
                continue;
            }
            candidates.add(new FamilyPair(maleNpc, femaleNpc));
        }

        if (candidates.isEmpty()) {
            return;
        }

        Collections.shuffle(candidates);
        FamilyPair selected = candidates.get(0);
        if (selected.femaleNpc().getRandom().nextDouble() >= NIGHT_AFFECTION_CHANCE) {
            return;
        }

        if (startIntimacySession(server, selected.maleNpc(), selected.femaleNpc(), false, true)) {
            LOGGER.info("[NPCFamilyManager] 夜间贴贴开始: {} 与 {}",
                    selected.maleNpc().getFullName(), selected.femaleNpc().getFullName());
        }
    }

    private static boolean isValidMarriedPair(MinecraftServer server,
                                              @Nullable CustomEntity maleNpc,
                                              @Nullable CustomEntity femaleNpc,
                                              boolean requireHospital) {
        if (server == null || maleNpc == null || femaleNpc == null) {
            return false;
        }
        if (!maleNpc.isAlive() || !femaleNpc.isAlive() || maleNpc.isRemoved() || femaleNpc.isRemoved()) {
            return false;
        }
        if (maleNpc.getGender() != Gender.MALE || femaleNpc.getGender() != Gender.FEMALE) {
            return false;
        }
        if (isNpcBusyWithFamily(maleNpc.getUUID()) || isNpcBusyWithFamily(femaleNpc.getUUID())) {
            return false;
        }
        if (NPCDataManager.isNPCPregnantOrInLabor(server, femaleNpc.getUUID())) {
            return false;
        }
        UUID maleSpouse = NPCDataManager.getNPCSpouseUuid(server, maleNpc.getUUID());
        if (!Objects.equals(maleSpouse, femaleNpc.getUUID())) {
            return false;
        }
        return !requireHospital || hasHospitalForCity(server, femaleNpc.getCityId());
    }

    private static boolean startIntimacySession(MinecraftServer server,
                                                CustomEntity maleNpc,
                                                CustomEntity femaleNpc,
                                                boolean pregnancyGuaranteed,
                                                boolean requireHospital) {
        if (!isValidMarriedPair(server, maleNpc, femaleNpc, requireHospital)) {
            return false;
        }

        if (!(maleNpc.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos targetHome = resolveAffectionHome(server, maleNpc, femaleNpc);
        if (level == null || targetHome == null) {
            return false;
        }

        BlockPos femaleStandPos = findNearbyStandPos(level, targetHome, Direction.SOUTH);
        BlockPos maleStandPos = findCompanionStandPos(level, femaleStandPos);
        if (maleStandPos == null || femaleStandPos == null) {
            return false;
        }

        IntimacySession session = new IntimacySession(
                maleNpc.getUUID(),
                femaleNpc.getUUID(),
                level.dimension(),
                maleStandPos,
                femaleStandPos,
                AFFECTION_NOT_STARTED,
                pregnancyGuaranteed
        );
        activeSessions.put(femaleNpc.getUUID(), session);

        prepareNpcForFamilySession(maleNpc, true);
        prepareNpcForFamilySession(femaleNpc, true);
        guideNpcToStandPos(maleNpc, maleStandPos);
        guideNpcToStandPos(femaleNpc, femaleStandPos);
        return true;
    }

    private static void tickIntimacySessions(MinecraftServer server) {
        if (server == null || activeSessions.isEmpty()) {
            return;
        }

        List<UUID> finishedSessions = new ArrayList<>();
        for (Map.Entry<UUID, IntimacySession> entry : activeSessions.entrySet()) {
            IntimacySession session = entry.getValue();
            if (session == null) {
                finishedSessions.add(entry.getKey());
                continue;
            }

            ServerLevel level = server.getLevel(session.levelKey());
            CustomEntity maleNpc = BaseBuildingHiredData.findNPCByUuid(server, session.maleUuid());
            CustomEntity femaleNpc = BaseBuildingHiredData.findNPCByUuid(server, session.femaleUuid());
            if (level == null || maleNpc == null || femaleNpc == null || !maleNpc.isAlive() || !femaleNpc.isAlive()) {
                finishedSessions.add(entry.getKey());
                continue;
            }

            prepareNpcForFamilySession(maleNpc, false);
            prepareNpcForFamilySession(femaleNpc, false);
            guideNpcToStandPos(maleNpc, session.maleStandPos());
            guideNpcToStandPos(femaleNpc, session.femaleStandPos());
            maleNpc.getLookControl().setLookAt(femaleNpc, 30.0F, 30.0F);
            femaleNpc.getLookControl().setLookAt(maleNpc, 30.0F, 30.0F);

            long affectionStartGameTime = session.affectionStartGameTime();
            if (affectionStartGameTime == AFFECTION_NOT_STARTED) {
                if (!arePartnersReadyForAffection(maleNpc, femaleNpc, session)) {
                    continue;
                }
                affectionStartGameTime = level.getGameTime();
                activeSessions.put(entry.getKey(), new IntimacySession(
                        session.maleUuid(),
                        session.femaleUuid(),
                        session.levelKey(),
                        session.maleStandPos(),
                        session.femaleStandPos(),
                        affectionStartGameTime,
                        session.pregnancyGuaranteed()
                ));
                maleNpc.getNavigation().stop();
                femaleNpc.getNavigation().stop();
            }

            long elapsed = level.getGameTime() - affectionStartGameTime;
            if (elapsed >= HEART_DELAY_TICKS && elapsed % HEART_INTERVAL_TICKS == 0L) {
                spawnHeartParticles(level, maleNpc);
                spawnHeartParticles(level, femaleNpc);
            }

            if (elapsed >= AFFECTION_DURATION_TICKS) {
                boolean pregnant = session.pregnancyGuaranteed()
                        || femaleNpc.getRandom().nextDouble() < PREGNANCY_CHANCE;
                if (pregnant) {
                    setPregnant(server, femaleNpc.getUUID());
                }
                clearFamilySessionNpcState(maleNpc);
                clearFamilySessionNpcState(femaleNpc);
                finishedSessions.add(entry.getKey());
            }
        }

        for (UUID femaleUuid : finishedSessions) {
            activeSessions.remove(femaleUuid);
        }
    }

    private static void maintainLoadedPregnancyNpcs(MinecraftServer server) {
        if (server == null) {
            return;
        }

        pregnancyManagedNpcUuids.clear();
        for (CustomEntity npc : NPCTaskScheduler.getAllNPCs(server)) {
            NPCDataManager.NPCPregnancyData data = NPCDataManager.getNPCPregnancyData(server, npc.getUUID());
            if (data == null) {
                clearLaborRuntimeState(npc.getUUID());
                continue;
            }
            if (PREGNANCY_STAGE_LABOR.equalsIgnoreCase(data.stage())) {
                pregnancyManagedNpcUuids.add(npc.getUUID());
                keepNpcInLabor(server, npc);
                tickLaborProgress(server, npc);
            } else {
                clearLaborRuntimeState(npc.getUUID());
                keepPregnantNpcAtHome(server, npc);
            }
        }
    }

    private static void advancePregnancyByDay(MinecraftServer server, long currentDay) {
        if (server == null) {
            return;
        }

        for (UUID npcUuid : NPCDataManager.getAllNPCUuids(server)) {
            NPCDataManager.NPCPregnancyData data = NPCDataManager.getNPCPregnancyData(server, npcUuid);
            if (data == null || !PREGNANCY_STAGE_PREGNANT.equalsIgnoreCase(data.stage())) {
                continue;
            }
            if (data.startDay() >= 0L && currentDay - data.startDay() >= LABOR_AFTER_FULL_DAYS) {
                enterLabor(server, npcUuid);
            }
        }
    }

    private static boolean setPregnant(MinecraftServer server, UUID femaleUuid) {
        if (server == null || femaleUuid == null) {
            return false;
        }
        long currentDay = server.overworld() != null ? server.overworld().getDayTime() / 24000L : 0L;
        if (!NPCDataManager.setNPCPregnancyData(server, femaleUuid, PREGNANCY_STAGE_PREGNANT, currentDay)) {
            return false;
        }

        pregnancyManagedNpcUuids.remove(femaleUuid);
        CustomEntity femaleNpc = BaseBuildingHiredData.findNPCByUuid(server, femaleUuid);
        if (femaleNpc != null) {
            resetNpcEmploymentState(server, femaleNpc);
            keepPregnantNpcAtHome(server, femaleNpc);
        }
        LOGGER.info("[NPCFamilyManager] NPC {} 进入怀孕状态", NPCDataManager.getNPCNameByUUID(server, femaleUuid));
        return true;
    }

    private static boolean enterLabor(MinecraftServer server, UUID femaleUuid) {
        if (server == null || femaleUuid == null) {
            return false;
        }
        NPCDataManager.NPCPregnancyData current = NPCDataManager.getNPCPregnancyData(server, femaleUuid);
        long startDay = current != null ? current.startDay() : (server.overworld() != null ? server.overworld().getDayTime() / 24000L : 0L);
        if (!NPCDataManager.setNPCPregnancyData(server, femaleUuid, PREGNANCY_STAGE_LABOR, startDay)) {
            return false;
        }

        pregnancyManagedNpcUuids.add(femaleUuid);
        laborStartGameTimes.remove(femaleUuid);
        CustomEntity femaleNpc = BaseBuildingHiredData.findNPCByUuid(server, femaleUuid);
        if (femaleNpc != null) {
            resetNpcEmploymentState(server, femaleNpc);
            keepNpcInLabor(server, femaleNpc);
            if (femaleNpc.level() instanceof ServerLevel serverLevel) {
                NPCVoiceManager.playPregnantVoice(serverLevel, femaleNpc);
            }
        }
        LOGGER.info("[NPCFamilyManager] NPC {} 进入临产状态", NPCDataManager.getNPCNameByUUID(server, femaleUuid));
        return true;
    }

    @Nullable
    private static CustomEntity findNearbyMarriedFemale(ServerPlayer player, boolean requirePregnant) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        List<CustomEntity> nearbyNpcs = new ArrayList<>(
                player.serverLevel().getEntitiesOfClass(CustomEntity.class, player.getBoundingBox().inflate(DEBUG_RADIUS))
        );
        nearbyNpcs.sort((left, right) -> Double.compare(left.distanceToSqr(player), right.distanceToSqr(player)));

        for (CustomEntity npc : nearbyNpcs) {
            if (npc.getGender() != Gender.FEMALE) {
                continue;
            }
            UUID spouseUuid = NPCDataManager.getNPCSpouseUuid(player.getServer(), npc.getUUID());
            if (spouseUuid == null) {
                continue;
            }
            boolean pregnant = NPCDataManager.isNPCPregnantOrInLabor(player.getServer(), npc.getUUID());
            if (requirePregnant && !pregnant) {
                continue;
            }
            if (!requirePregnant && pregnant) {
                continue;
            }
            return npc;
        }
        return null;
    }

    @Nullable
    private static FamilyPair toFamilyPair(CustomEntity firstNpc, CustomEntity secondNpc) {
        if (firstNpc == null || secondNpc == null) {
            return null;
        }
        if (firstNpc.getGender() == Gender.MALE && secondNpc.getGender() == Gender.FEMALE) {
            return new FamilyPair(firstNpc, secondNpc);
        }
        if (firstNpc.getGender() == Gender.FEMALE && secondNpc.getGender() == Gender.MALE) {
            return new FamilyPair(secondNpc, firstNpc);
        }
        return null;
    }

    @Nullable
    private static BlockPos resolveAffectionHome(MinecraftServer server, CustomEntity maleNpc, CustomEntity femaleNpc) {
        BlockPos femaleHome = NPCRestHandler.getFamilyHomePosition(femaleNpc, server);
        if (femaleHome != null) {
            return femaleHome;
        }
        return NPCRestHandler.getFamilyHomePosition(maleNpc, server);
    }

    @Nullable
    private static BlockPos findNearbyStandPos(ServerLevel level, BlockPos center, Direction preferredDirection) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(center.relative(preferredDirection));
        candidates.add(center.relative(preferredDirection).above());
        candidates.add(center.relative(preferredDirection.getClockWise()));
        candidates.add(center.relative(preferredDirection.getCounterClockWise()));
        candidates.add(center.above());

        for (BlockPos pos : candidates) {
            if (isStandable(level, pos)) {
                return pos;
            }
        }

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -1, -4), center.offset(4, 3, 4))) {
            BlockPos immutable = pos.immutable();
            if (isStandable(level, immutable)) {
                return immutable;
            }
        }
        if (level.isLoaded(center.above()) && level.getBlockState(center.above()).canBeReplaced()) {
            return center.above();
        }
        return null;
    }

    @Nullable
    private static BlockPos findCompanionStandPos(ServerLevel level, BlockPos anchorPos) {
        if (level == null || anchorPos == null) {
            return null;
        }
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            BlockPos candidate = anchorPos.relative(direction);
            if (isStandable(level, candidate)) {
                return candidate;
            }
        }
        return findNearbyStandPos(level, anchorPos, Direction.NORTH);
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return false;
        }
        return level.getBlockState(pos).canBeReplaced()
                && level.getBlockState(pos.above()).canBeReplaced()
                && !level.getBlockState(pos.below()).isAir();
    }

    private static void prepareNpcForFamilySession(CustomEntity npc, boolean resetMovement) {
        if (resetMovement) {
            npc.stopNewPathfinder();
            npc.getNavigation().stop();
        }
        npc.setWorking(true);
        npc.setNoAi(false);
        npc.setTarget(null);
        npc.setWorkSubState(WorkSubState.WORKING);
        if (npc.getWorkStatus() != WorkStatus.IDLE) {
            npc.setWorkStatus(WorkStatus.IDLE);
        }
        npc.setStatusLabel(STATUS_WITH_SPOUSE);
    }

    private static void guideNpcToStandPos(CustomEntity npc, BlockPos targetPos) {
        if (npc == null || targetPos == null) {
            return;
        }
        double distanceSqr = npc.blockPosition().distSqr(targetPos);
        if (distanceSqr > 144.0D) {
            npc.teleportTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D);
            return;
        }
        if (distanceSqr > 2.25D) {
            if (!npc.isPathfindingTo(targetPos)) {
                npc.moveToWithNewPathfinder(targetPos);
            }
        } else {
            npc.getNavigation().stop();
        }
    }

    private static boolean arePartnersReadyForAffection(CustomEntity maleNpc,
                                                        CustomEntity femaleNpc,
                                                        IntimacySession session) {
        return maleNpc != null
                && femaleNpc != null
                && session != null
                && hasReachedStandPos(maleNpc, session.maleStandPos())
                && hasReachedStandPos(femaleNpc, session.femaleStandPos())
                && maleNpc.distanceToSqr(femaleNpc) <= INTIMACY_PARTNER_CLOSE_DISTANCE_SQR;
    }

    private static boolean hasReachedStandPos(CustomEntity npc, BlockPos targetPos) {
        return npc != null
                && targetPos != null
                && npc.blockPosition().distSqr(targetPos) <= INTIMACY_TARGET_REACHED_DISTANCE_SQR;
    }

    private static void clearFamilySessionNpcState(CustomEntity npc) {
        if (npc == null) {
            return;
        }
        npc.setWorking(false);
        npc.setWorkSubState(WorkSubState.NONE);
        npc.setStatusLabel(null);
    }

    private static void keepPregnantNpcAtHome(MinecraftServer server, CustomEntity npc) {
        if (server == null || npc == null) {
            return;
        }
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos homePos = NPCRestHandler.getFamilyHomePosition(npc, server);
        if (homePos != null) {
            // 怀孕期间白天固定在家，晚上交回休息系统让其正常睡觉。
            if (NPCRestHandler.shouldStartResting(level, npc)) {
                npc.setStatusLabel(STATUS_PREGNANT_HOME);
                npc.setWorking(false);
                npc.setNoAi(false);
                return;
            }
            moveNpcIntoManagedArea(npc, homePos);
        }
        npc.setWorking(false);
        npc.setNoAi(false);
        npc.setStatusLabel(STATUS_PREGNANT_HOME);
    }

    private static void keepNpcInLabor(MinecraftServer server, CustomEntity npc) {
        if (server == null || npc == null) {
            return;
        }
        npc.setWorking(false);
        npc.setStatusLabel(STATUS_IN_LABOR);
        npc.setNoAi(false);

        HospitalTarget hospital = findHospitalTarget(server, npc.getCityId());
        if (hospital == null) {
            keepPregnantNpcAtHome(server, npc);
            npc.setStatusLabel(STATUS_IN_LABOR);
            return;
        }

        if (!(npc.level() instanceof ServerLevel npcLevel) || npcLevel != hospital.level()) {
            // 当前婚育流程默认在主世界执行，若维度不一致则仅更新状态避免错误传送。
            return;
        }

        ensureHospitalAreaLoaded(hospital.level(), hospital.controlBoxPos());
        npc.stopNewPathfinder();
        npc.getNavigation().stop();
        npc.setTarget(null);

        BlockPos bedPos = NPCRestHandler.findFamilyBed(hospital.level(), hospital.controlBoxPos(), npc);
        bedPos = resolveLaborBed(hospital, npc, bedPos);
        if (bedPos != null) {
            moveNpcIntoManagedArea(npc, bedPos);
            if (npc.blockPosition().distSqr(bedPos) > 6.25D) {
                npc.setNoAi(false);
                npc.setStatusLabel(STATUS_IN_LABOR);
                return;
            }
            NPCRestHandler.tryStartSleepingForFamily(npc, bedPos, hospital.level());
            boolean doctorReady = guideDoctorToLaborBed(server, hospital, bedPos);
            npc.setNoAi(doctorReady && npc.isSleeping());
            npc.setStatusLabel(STATUS_IN_LABOR);
            return;
        }

        laborBedAssignments.remove(npc.getUUID());
        notifyHospitalQueueIfNeeded(server, npc, hospital);
        BlockPos standPos = findNearbyStandPos(hospital.level(), hospital.controlBoxPos(), Direction.NORTH);
        moveNpcIntoManagedArea(npc, standPos != null ? standPos : hospital.controlBoxPos());
        npc.setNoAi(false);
        npc.setStatusLabel(STATUS_IN_LABOR);
    }

    private static void tickLaborProgress(MinecraftServer server, CustomEntity npc) {
        if (server == null || npc == null || !(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (!isReadyForBirth(server, npc)) {
            laborStartGameTimes.remove(npc.getUUID());
            return;
        }

        long startGameTime = laborStartGameTimes.computeIfAbsent(npc.getUUID(), key -> level.getGameTime());
        if (level.getGameTime() - startGameTime < LABOR_BIRTH_TICKS) {
            return;
        }
        completeBirth(server, npc);
    }

    private static boolean isReadyForBirth(MinecraftServer server, CustomEntity npc) {
        if (server == null || npc == null || !(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        HospitalTarget hospital = findHospitalTarget(server, npc.getCityId());
        if (hospital == null) {
            return true;
        }
        if (level != hospital.level() || npc.blockPosition().distSqr(hospital.controlBoxPos()) > LABOR_READY_RANGE_SQR) {
            return false;
        }

        BlockPos bedPos = resolveLaborBed(hospital, npc, laborBedAssignments.get(npc.getUUID()));
        if (bedPos == null || !npc.isSleeping()) {
            return false;
        }
        return guideDoctorToLaborBed(server, hospital, bedPos);
    }

    private static void completeBirth(MinecraftServer server, CustomEntity motherNpc) {
        if (server == null || motherNpc == null || !(motherNpc.level() instanceof ServerLevel level)) {
            return;
        }

        UUID cityId = motherNpc.getCityId();
        if (cityId == null) {
            LOGGER.warn("[NPCFamilyManager] 分娩失败，母亲 {} 没有城市归属", motherNpc.getFullName());
            laborStartGameTimes.remove(motherNpc.getUUID());
            return;
        }

        BlockPos childSpawnPos = resolveChildSpawnPos(server, motherNpc);
        try {
            CustomEntity childNpc = new CustomEntity(ModEntities.CUSTOM_ENTITY.get(), level);
            childNpc.setPos(childSpawnPos.getX() + 0.5D, childSpawnPos.getY(), childSpawnPos.getZ() + 0.5D);
            childNpc.setCityId(cityId);
            long currentDay = server.overworld() != null ? server.overworld().getDayTime() / 24000L : 0L;
            childNpc.markAsNewborn(currentDay);
            childNpc.initializeName();

            CityData.get(level).addCitizenToCity(cityId, childNpc.getUUID(), level);
            level.addFreshEntity(childNpc);
            NPCVoiceManager.playBirthVoice(level, childSpawnPos);
            LOGGER.info("[NPCFamilyManager] NPC {} 在诊所分娩成功，孩子 {}", motherNpc.getFullName(), childNpc.getFullName());
        } catch (Exception e) {
            LOGGER.error("[NPCFamilyManager] NPC {} 分娩生成孩子失败", motherNpc.getFullName(), e);
            return;
        }

        NPCDataManager.setNPCPregnancyData(server, motherNpc.getUUID(), "none", -1L);
        pregnancyManagedNpcUuids.remove(motherNpc.getUUID());
        clearLaborRuntimeState(motherNpc.getUUID());
        resetNpcToUnemployed(server, motherNpc.getUUID(), motherNpc);

        BlockPos homePos = NPCRestHandler.getFamilyHomePosition(motherNpc, server);
        if (homePos != null) {
            moveNpcIntoManagedArea(motherNpc, homePos);
        }
    }

    private static BlockPos resolveChildSpawnPos(MinecraftServer server, CustomEntity motherNpc) {
        if (server == null || motherNpc == null || !(motherNpc.level() instanceof ServerLevel level)) {
            return motherNpc != null ? motherNpc.blockPosition() : BlockPos.ZERO;
        }

        HospitalTarget hospital = findHospitalTarget(server, motherNpc.getCityId());
        if (hospital != null && hospital.level() == level) {
            BlockPos hospitalStandPos = findNearbyStandPos(level, hospital.controlBoxPos(), Direction.SOUTH);
            if (hospitalStandPos != null) {
                return hospitalStandPos;
            }
        }

        BlockPos motherStandPos = findNearbyStandPos(level, motherNpc.blockPosition(), Direction.SOUTH);
        return motherStandPos != null ? motherStandPos : motherNpc.blockPosition();
    }

    private static void moveNpcIntoManagedArea(CustomEntity npc, BlockPos targetPos) {
        if (npc == null || targetPos == null) {
            return;
        }
        double distanceSqr = npc.blockPosition().distSqr(targetPos);
        if (distanceSqr > 49.0D) {
            npc.teleportTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D);
        } else if (distanceSqr > 4.0D) {
            npc.moveToWithNewPathfinder(targetPos);
        } else {
            npc.getNavigation().stop();
        }
    }

    private static void ensureHospitalAreaLoaded(ServerLevel level, BlockPos anchorPos) {
        if (level == null || anchorPos == null) {
            return;
        }
        ChunkPos chunkPos = new ChunkPos(anchorPos);
        level.getChunk(chunkPos.x, chunkPos.z);
    }

    private static void resetNpcEmploymentState(MinecraftServer server, CustomEntity npc) {
        if (server == null || npc == null) {
            return;
        }
        try {
            releaseEmploymentAndSync(server, npc.getUUID());
        } catch (Exception e) {
            LOGGER.warn("[NPCFamilyManager] 清理怀孕NPC雇佣关系失败: {}", npc.getFullName(), e);
        }
        resetNpcToUnemployed(server, npc.getUUID(), npc);
    }

    private static void clearFamilyState(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) {
            return;
        }

        UUID spouseUuid = NPCDataManager.getNPCSpouseUuid(server, npcUuid);
        List<UUID> targets = new ArrayList<>();
        targets.add(npcUuid);
        if (spouseUuid != null && !spouseUuid.equals(npcUuid)) {
            targets.add(spouseUuid);
        }

        activeSessions.entrySet().removeIf(entry -> {
            IntimacySession session = entry.getValue();
            return session != null && (targets.contains(session.maleUuid()) || targets.contains(session.femaleUuid()));
        });

        for (UUID targetUuid : targets) {
            pregnancyManagedNpcUuids.remove(targetUuid);
            clearLaborRuntimeState(targetUuid);
            NPCDataManager.setNPCPregnancyData(server, targetUuid, "none", -1L);
            CustomEntity npc = BaseBuildingHiredData.findNPCByUuid(server, targetUuid);
            releaseEmploymentAndSync(server, targetUuid);
            resetNpcToUnemployed(server, targetUuid, npc);
        }
    }

    private static void releaseEmploymentAndSync(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) {
            return;
        }

        try {
            var service = EmploymentServices.get(server);
            var result = service.fireByNpc(new EmploymentCommands.FireByNpcCommand(npcUuid));
            EmploymentAssignment assignment = result.assignment();
            if (!result.success() || assignment == null) {
                return;
            }
            com.xiaoliang.simukraft.network.EmploymentCommandPacket.applyFireSideEffectsAndBroadcast(server, assignment, false);
        } catch (Exception e) {
            LOGGER.warn("[NPCFamilyManager] 同步清理雇佣状态失败 npcUuid={}", npcUuid, e);
        }
    }

    private static void resetNpcToUnemployed(MinecraftServer server, UUID npcUuid, @Nullable CustomEntity npc) {
        if (server == null || npcUuid == null) {
            return;
        }
        if (npc == null) {
            String npcId = NPCDataManager.getNPCIdByUUID(server, npcUuid);
            if (npcId != null) {
                NPCDataManager.saveJobData(server, npcId, "idle", "unemployed");
            }
            return;
        }

        if (npc.isSleeping()) {
            npc.stopSleeping();
        }
        npc.setNoAi(false);
        npc.setWorking(false);
        npc.setTarget(null);
        npc.setStatusLabel(null);
        npc.setWorkStatus(WorkStatus.IDLE);
        npc.setJob("unemployed");
        npc.resetToIdle();
        NPCDataManager.saveJobData(npc);
    }

    private static void spawnHeartParticles(ServerLevel level, CustomEntity npc) {
        level.sendParticles(
                ParticleTypes.HEART,
                npc.getX(),
                npc.getY() + 1.2D,
                npc.getZ(),
                4,
                0.35D,
                0.25D,
                0.35D,
                0.02D
        );
    }

    private static boolean hasHospitalForCity(MinecraftServer server, @Nullable UUID cityId) {
        return findHospitalTarget(server, cityId) != null;
    }

    @Nullable
    private static HospitalTarget findHospitalTarget(MinecraftServer server, @Nullable UUID cityId) {
        if (server == null) {
            return null;
        }

        UUID cacheKey = cityId != null ? cityId : HOSPITAL_CACHE_FALLBACK_KEY;
        long gameTime = server.overworld() != null ? server.overworld().getGameTime() : 0L;
        CachedHospitalTarget cached = hospitalTargetCache.get(cacheKey);
        if (cached != null && gameTime - cached.cachedAtGameTime() < HOSPITAL_CACHE_TTL_TICKS) {
            return cached.target();
        }

        List<ControlBoxDataManager.ControlBoxData> allBoxes = new ArrayList<>();
        allBoxes.addAll(ControlBoxDataManager.getAllControlBoxes(server, "commercial_control_box"));
        allBoxes.addAll(ControlBoxDataManager.getAllControlBoxes(server, "industrial_control_box"));
        allBoxes.addAll(ControlBoxDataManager.getAllControlBoxes(server, "other_control_box"));

        HospitalTarget fallbackHospital = null;
        for (ControlBoxDataManager.ControlBoxData box : allBoxes) {
            HospitalTarget target = resolveHospitalTarget(server, box, true);
            if (target == null) {
                continue;
            }

            if (cityId != null && Objects.equals(cityId, box.cityId)) {
                hospitalTargetCache.put(cacheKey, new CachedHospitalTarget(target, gameTime));
                return target;
            }
            if (fallbackHospital == null) {
                fallbackHospital = target;
            }
        }
        hospitalTargetCache.put(cacheKey, new CachedHospitalTarget(fallbackHospital, gameTime));
        return fallbackHospital;
    }

    @Nullable
    private static HospitalTarget resolveHospitalTarget(MinecraftServer server,
                                                        @Nullable ControlBoxDataManager.ControlBoxData box,
                                                        boolean requireParturition) {
        if (box == null) {
            return null;
        }

        ServerLevel level = resolveLevel(server, box.world);
        if (level == null) {
            return null;
        }

        MedicalBuildingConfig config = MedicalBuildingManager.getConfig(box.buildingFileName);
        if (config != null) {
            if (requireParturition && !config.canParturition()) {
                return null;
            }
            String buildingName = config.buildingName();
            if (buildingName == null || buildingName.isBlank()) {
                buildingName = box.buildingName;
            }
            return new HospitalTarget(level, box.position, buildingName, box.buildingFileName, config.canParturition());
        }

        if (!isLegacyMedicalControlBox(box)) {
            return null;
        }
        return new HospitalTarget(level, box.position, resolveHospitalName(box, null), box.buildingFileName, true);
    }

    private static boolean isLegacyMedicalControlBox(ControlBoxDataManager.ControlBoxData box) {
        if (box == null) {
            return false;
        }

        String category = resolveControlBoxCategory(box.type);
        List<String> candidates = new ArrayList<>();
        if (box.buildingName != null && !box.buildingName.isBlank()) {
            candidates.add(box.buildingName.toLowerCase());
        }
        if (box.buildingFileName != null && !box.buildingFileName.isBlank()) {
            String fileName = box.buildingFileName.toLowerCase();
            candidates.add(fileName);
            if (category != null) {
                String resolvedName = ControlBoxDataManager.getBuildingNameFromSkFile(fileName, category);
                if (resolvedName != null && !resolvedName.isBlank()) {
                    candidates.add(resolvedName.toLowerCase());
                }
            }
        }

        for (String value : candidates) {
            if (value.contains("医院")
                    || value.contains("诊所")
                    || value.contains("hospital")
                    || value.contains("clinic")
                    || value.contains("medical")) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos resolveLaborBed(HospitalTarget hospital, CustomEntity npc, @Nullable BlockPos preferredBedPos) {
        if (hospital == null || npc == null) {
            return null;
        }
        if (isValidHospitalBed(hospital.level(), preferredBedPos)) {
            laborBedAssignments.put(npc.getUUID(), preferredBedPos);
            return preferredBedPos;
        }

        BlockPos foundBed = NPCRestHandler.findFamilyBed(hospital.level(), hospital.controlBoxPos(), npc);
        if (foundBed != null) {
            laborBedAssignments.put(npc.getUUID(), foundBed);
        }
        return foundBed;
    }

    private static boolean isValidHospitalBed(ServerLevel level, @Nullable BlockPos bedPos) {
        if (level == null || bedPos == null || !level.isLoaded(bedPos)) {
            return false;
        }
        return level.getBlockState(bedPos).getBlock() instanceof BedBlock;
    }

    private static boolean guideDoctorToLaborBed(MinecraftServer server, HospitalTarget hospital, BlockPos bedPos) {
        if (server == null || hospital == null || bedPos == null) {
            return false;
        }
        EmploymentAssignment assignment = EmploymentServices.get(server)
                .findByWorkplaceAndJob(hospital.level().dimension().location().toString(), hospital.controlBoxPos(), JobType.DOCTOR)
                .orElse(null);
        if (assignment == null) {
            return false;
        }

        CustomEntity doctor = NPCEntityLocator.findNpc(server, assignment.npcUuid(), hospital.controlBoxPos(), false);
        if (doctor == null || doctor.level() != hospital.level()) {
            return false;
        }

        BlockPos doctorTarget = findNearbyStandPos(hospital.level(), bedPos, Direction.NORTH);
        if (doctorTarget == null) {
            doctorTarget = hospital.controlBoxPos();
        }

        double distanceSqr = doctor.blockPosition().distSqr(doctorTarget);
        if (distanceSqr > DOCTOR_READY_DISTANCE_SQR) {
            doctor.setNoAi(false);
            moveNpcIntoManagedArea(doctor, doctorTarget);
            return false;
        }

        doctor.stopNewPathfinder();
        doctor.getNavigation().stop();
        return true;
    }

    private static void notifyHospitalQueueIfNeeded(MinecraftServer server, CustomEntity npc, HospitalTarget hospital) {
        if (server == null || npc == null || hospital == null || npc.getCityId() == null) {
            return;
        }
        long gameTime = hospital.level().getGameTime();
        String queueKey = hospital.level().dimension().location() + ":" + hospital.controlBoxPos().asLong();
        long lastMessageTime = hospitalQueueMessageTimes.getOrDefault(queueKey, Long.MIN_VALUE);
        if (gameTime - lastMessageTime < HOSPITAL_QUEUE_MESSAGE_INTERVAL_TICKS) {
            return;
        }

        hospitalQueueMessageTimes.put(queueKey, gameTime);
        Component message = Component.translatable(
                "message.simukraft.medical.queue_full",
                resolveHospitalName(null, hospital)
        );
        CityMessageUtils.sendToCityGroup(server, npc.getCityId(), message);
    }

    private static String resolveHospitalName(@Nullable ControlBoxDataManager.ControlBoxData box,
                                              @Nullable HospitalTarget hospital) {
        if (hospital != null && hospital.buildingName() != null && !hospital.buildingName().isBlank()) {
            return hospital.buildingName();
        }
        if (box != null) {
            MedicalBuildingConfig config = MedicalBuildingManager.getConfig(box.buildingFileName);
            if (config != null && config.buildingName() != null && !config.buildingName().isBlank()) {
                return config.buildingName();
            }
            if (box.buildingName != null && !box.buildingName.isBlank()) {
                return box.buildingName;
            }
        }
        return "医院";
    }

    private static void clearLaborRuntimeState(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        laborStartGameTimes.remove(npcUuid);
        laborBedAssignments.remove(npcUuid);
    }

    @Nullable
    private static String resolveControlBoxCategory(@Nullable String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return switch (type) {
            case "commercial_control_box" -> "commercial";
            case "industrial_control_box" -> "industry";
            case "residential_control_box" -> "residential";
            default -> null;
        };
    }

    @Nullable
    private static ServerLevel resolveLevel(MinecraftServer server, @Nullable String levelId) {
        if (server == null) {
            return null;
        }
        if (levelId == null || levelId.isBlank()) {
            return server.overworld();
        }
        ResourceLocation location = ResourceLocation.tryParse(levelId);
        if (location == null) {
            return server.overworld();
        }
        return server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location));
    }
}
