package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.notification.MessageCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 自行吃饭工作流管理器
 * 饥饿值过低时，将“去店里买东西吃”作为首要工作插入当前工作流。
 */
@SuppressWarnings("null")
public final class SelfFeedingManager {
    public static final int START_HUNGER_THRESHOLD = 4;
    public static final int FULL_HUNGER = 20;
    public static final int EAT_DURATION_TICKS = 200;
    // 10 分钟提醒一次，避免找不到食物商店时频繁刷屏。
    private static final long STRIKE_MESSAGE_COOLDOWN_TICKS = 12000L;

    private static final Map<UUID, BlockPos> previousWorkPositions = new ConcurrentHashMap<>();
    private static final Map<UUID, WorkStatus> previousWorkStatuses = new ConcurrentHashMap<>();
    private static final Map<UUID, String> previousJobs = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastStrikeMessageTicks = new ConcurrentHashMap<>();

    private SelfFeedingManager() {}

    public static void handleSelfFeeding(ServerLevel level) {
        if (level == null) {
            return;
        }

        for (CustomEntity npc : NPCTaskScheduler.getNPCsInLevel(level)) {
            if (npc == null || !npc.isAlive()) {
                continue;
            }

            if (shouldFinishSelfFeeding(npc)) {
                finishSelfFeeding(npc, level);
                continue;
            }

            if (shouldStartSelfFeeding(npc)) {
                startSelfFeeding(npc);
            }
        }
    }

    public static boolean shouldStartSelfFeeding(CustomEntity npc) {
        if (npc == null || !npc.isAlive()) {
            return false;
        }
        if (npc.isSleeping()) {
            return false;
        }
        if (npc.getHunger() > START_HUNGER_THRESHOLD) {
            return false;
        }

        WorkSubState subState = npc.getWorkSubState();
        if (subState == WorkSubState.RESTING || subState == WorkSubState.LUNCH_BREAK || subState == WorkSubState.BUYING_FOOD) {
            return false;
        }
        return true;
    }

    public static boolean isSelfFeedingActive(@Nullable CustomEntity npc) {
        return npc != null && npc.getWorkSubState() == WorkSubState.BUYING_FOOD;
    }

    /**
     * 统一判断是否应当暂停所有"拉回工作点/恢复工作控制器"逻辑。
     * 目前主要用于自行买饭阶段，避免工作方块与买饭寻路互相抢控制权。
     */
    public static boolean shouldBlockWorkPull(@Nullable CustomEntity npc) {
        return npc != null && npc.getWorkSubState() == WorkSubState.BUYING_FOOD;
    }

    public static boolean shouldFinishSelfFeeding(@Nullable CustomEntity npc) {
        return isSelfFeedingActive(npc) && npc.getHunger() >= FULL_HUNGER;
    }

    public static void onExternalFeed(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null) {
            return;
        }
        if (shouldFinishSelfFeeding(npc)) {
            finishSelfFeeding(npc, level);
        }
    }

    public static void startSelfFeeding(CustomEntity npc) {
        if (npc == null || isSelfFeedingActive(npc)) {
            return;
        }

        UUID npcId = npc.getUUID();
        previousWorkStatuses.putIfAbsent(npcId, npc.getWorkStatus());
        previousJobs.putIfAbsent(npcId, npc.getJob());

        BlockPos workPos = findWorkPosition(npc);
        if (workPos != null) {
            previousWorkPositions.putIfAbsent(npcId, workPos.immutable());
        }

        npc.stopAllMovement();
        npc.setWorking(false);
        npc.setWorkSubState(WorkSubState.BUYING_FOOD);
        npc.setStatusLabel("gui.npc.status.going_to_buy_food");
        npc.setWorkNeedDetail("");

        Simukraft.LOGGER.debug("[SelfFeedingManager] NPC {} 饥饿值过低，插入首要吃饭工作", npc.getFullName());
    }

    public static void onFoodSearchFailed(ServerLevel level, CustomEntity npc) {
        if (level == null || npc == null) {
            return;
        }

        if (!isSelfFeedingActive(npc)) {
            startSelfFeeding(npc);
        }

        npc.stopAllMovement();
        npc.setWorking(false);
        npc.setStatusLabel("gui.npc.status.too_hungry_on_strike");
        npc.setWorkNeedDetail("");

        long gameTime = level.getGameTime();
        Long lastSent = lastStrikeMessageTicks.get(npc.getUUID());
        if (lastSent != null && gameTime - lastSent < STRIKE_MESSAGE_COOLDOWN_TICKS) {
            return;
        }
        lastStrikeMessageTicks.put(npc.getUUID(), gameTime);

        String job = npc.getJob();
        boolean hasJob = job != null && !job.isBlank() && !"unemployed".equals(job);
        if (npc.getCityId() != null && hasJob) {
            CityMessageUtils.sendToCityGroup(
                    level.getServer(),
                    npc.getCityId(),
                    Component.translatable("message.simukraft.npc.too_hungry_on_strike", npc.getFullName()),
                    MessageCategory.CITIZEN
            );
        }
    }

    public static void finishSelfFeeding(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null) {
            return;
        }

        UUID npcId = npc.getUUID();
        BlockPos workPos = previousWorkPositions.get(npcId);
        WorkStatus previousWorkStatus = previousWorkStatuses.getOrDefault(npcId, npc.getWorkStatus());
        String previousJob = previousJobs.getOrDefault(npcId, npc.getJob());

        if (previousJob != null && !previousJob.isBlank()) {
            npc.setJob(previousJob);
        }

        npc.setStatusLabel(null);
        npc.setWorkNeedDetail("");
        npc.setWorkStatus(previousWorkStatus);

        if (previousWorkStatus == WorkStatus.WORKING) {
            npc.setWorkSubState(WorkSubState.WORKING);
            npc.setWorking(true);
            if (workPos != null) {
                spawnTeleportParticles(npc);
                npc.teleportTo(workPos.getX() + 0.5D, workPos.getY() + 1.0D, workPos.getZ() + 0.5D);
                spawnTeleportParticles(npc);
            }
            restoreJobSpecificWorkState(npc, npcId, previousJob, level);
        } else {
            npc.setWorkSubState(WorkSubState.NONE);
            npc.setWorking(false);
        }

        clearRuntimeData(npcId);
        Simukraft.LOGGER.debug("[SelfFeedingManager] NPC {} 已吃饱并恢复原工作", npc.getFullName());
    }

    private static void clearRuntimeData(UUID npcId) {
        if (npcId == null) {
            return;
        }
        previousWorkPositions.remove(npcId);
        previousWorkStatuses.remove(npcId);
        previousJobs.remove(npcId);
        lastStrikeMessageTicks.remove(npcId);
    }

    private static void restoreJobSpecificWorkState(CustomEntity npc, UUID npcId, String previousJob, ServerLevel level) {
        if (npc == null || previousJob == null || previousJob.isBlank() || level == null || level.getServer() == null) {
            return;
        }

        BlockPos workPos = previousWorkPositions.get(npcId);
        if (workPos == null) {
            workPos = findWorkPositionByJob(npcId, level.getServer(), previousJob);
        }
        if (workPos == null) {
            return;
        }

        if ("builder".equals(previousJob)) {
            com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.restoreWorkState(npc, npcId, level);
            return;
        }

        if ("planner".equals(previousJob)) {
            com.xiaoliang.simukraft.job.jobs.planner.PlannerWorkService.INSTANCE.restoreWorkState(npc, npcId, level);
            return;
        }

        if ("farmer".equals(previousJob)) {
            com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService.INSTANCE.restoreWorkState(npc, npcId, level);
            return;
        }

        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(previousJob)) {
            String buildingFileName = com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.getBuildingFileName(level, workPos);
            if (buildingFileName != null) {
                com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.restoreNpcAfterRest(npc, level, workPos, buildingFileName);
            }
            return;
        }

        String industrialBuildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(level, workPos);
        if (industrialBuildingFileName != null) {
            com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.restoreNpcAfterRest(npc, level, workPos, industrialBuildingFileName);
            return;
        }

        if ("warehouse_manager".equals(previousJob)) {
            com.xiaoliang.simukraft.job.jobs.warehousemanager.WarehouseManagerWorkService.INSTANCE.restoreWorkState(npc, npcId, level);
        }
    }

    private static void spawnTeleportParticles(CustomEntity npc) {
        if (!(npc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int i = 0; i < 20; i++) {
            serverLevel.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.PORTAL,
                    npc.getX(), npc.getY() + 1.0D, npc.getZ(),
                    1,
                    0.5D, 0.5D, 0.5D,
                    0.1D
            );
        }
    }

    @Nullable
    private static BlockPos findWorkPosition(CustomEntity npc) {
        if (npc == null) {
            return null;
        }
        MinecraftServer server = npc.level().getServer();
        if (server == null) {
            return null;
        }
        return findWorkPositionByJob(npc.getUUID(), server, npc.getJob());
    }

    @Nullable
    private static BlockPos findWorkPositionByJob(UUID npcId, MinecraftServer server, String job) {
        if (job == null || job.isBlank() || server == null || npcId == null) {
            return null;
        }

        BlockPos industrialPos = findIndustrialWorkPosition(npcId, server, job);
        if (industrialPos != null) {
            return industrialPos;
        }

        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job)) {
            return findCommercialWorkPosition(npcId, server);
        }

        return switch (job) {
            case "builder" -> findBuilderWorkPosition(npcId, server);
            case "planner" -> findPlannerWorkPosition(npcId, server);
            case "farmer" -> findFarmerWorkPosition(npcId);
            case "warehouse_manager" -> findWarehouseWorkPosition(npcId, server);
            default -> findFallbackWorkPosition(npcId, server);
        };
    }

    @Nullable
    private static BlockPos findFallbackWorkPosition(UUID npcId, MinecraftServer server) {
        BlockPos commercialPos = findCommercialWorkPosition(npcId, server);
        if (commercialPos != null) {
            return commercialPos;
        }
        return findIndustrialWorkPosition(npcId, server, null);
    }

    @Nullable
    private static BlockPos findBuilderWorkPosition(UUID npcId, MinecraftServer server) {
        var hires = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server);
        for (var entry : hires.entrySet()) {
            if (entry.getValue().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findPlannerWorkPosition(UUID npcId, MinecraftServer server) {
        var hires = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(server);
        for (var entry : hires.entrySet()) {
            if (entry.getValue().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findFarmerWorkPosition(UUID npcId) {
        if (npcId == null) {
            return null;
        }
        for (var entry : com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers().entrySet()) {
            if (npcId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findWarehouseWorkPosition(UUID npcId, MinecraftServer server) {
        if (server == null || npcId == null) {
            return null;
        }
        for (var entry : com.xiaoliang.simukraft.world.LogisticsHiredData.getServerBoxHiredNpcs(server).entrySet()) {
            if (npcId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findIndustrialWorkPosition(UUID npcId, MinecraftServer server, @Nullable String jobType) {
        var hiredEmployees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : hiredEmployees.entrySet()) {
            var hireInfo = entry.getValue();
            if (hireInfo == null || !hireInfo.getNpcUuid().equals(npcId)) {
                continue;
            }
            if (jobType != null && !jobType.equals(hireInfo.getJobType())) {
                continue;
            }
            if (entry.getKey() != null) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findCommercialWorkPosition(UUID npcId, MinecraftServer server) {
        var employment = com.xiaoliang.simukraft.employment.service.EmploymentServices.get(server)
                .findByNpc(npcId)
                .filter(assignment -> assignment.workBlockType() == com.xiaoliang.simukraft.employment.domain.WorkBlockType.COMMERCIAL_CONTROL_BOX)
                .map(com.xiaoliang.simukraft.employment.domain.EmploymentAssignment::workplacePos)
                .orElse(null);
        if (employment != null) {
            return employment;
        }

        var hiredEmployees = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        for (var entry : hiredEmployees.entrySet()) {
            if (entry.getValue().getNpcUuid().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
