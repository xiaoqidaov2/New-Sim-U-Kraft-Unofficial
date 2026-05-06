package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.building.IndustrialBuildingConfig;
import com.xiaoliang.simukraft.building.IndustrialBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC午休管理器（menglannnn: 完全仿造休息的写法）
 * 午休时间：6000-7000 tick（中午12:00-15:00，游戏时间约1分钟）
 * 午休期间NPC可以闲逛或去购买食物
 *
 * 午休规则：
 * - 农民：固定午休
 * - 仓库管理员：固定午休
 * - 建筑师：固定午休
 * - 规划师：固定午休
 * - 工商业：根据配置文件决定是否午休（默认true）
 *
 * 状态持久化：使用NPC的NBT数据（workSubState字段）
 */
@SuppressWarnings("null")
public class LunchBreakManager {

    public static final int LUNCH_BREAK_START = 6000;
    public static final int LUNCH_BREAK_END = 7000;

    private static final Set<String> FIXED_LUNCH_BREAK_JOBS = Set.of(
        "farmer",
        "warehouse_manager",
        "builder",
        "planner"
    );

    private static final Set<String> CONFIGURABLE_LUNCH_BREAK_JOBS = Set.of(
        "industrial"
    );

    private static final Map<UUID, BlockPos> lunchBreakWorkPositions = new ConcurrentHashMap<>();
    private static final Map<UUID, WorkStatus> lunchBreakPreviousWorkStatus = new ConcurrentHashMap<>();
    private static final Map<UUID, String> lunchBreakPreviousJob = new ConcurrentHashMap<>();

    public static boolean isLunchBreakTime(long dayTime) {
        long timeOfDay = dayTime % 24000L;
        return timeOfDay >= LUNCH_BREAK_START && timeOfDay < LUNCH_BREAK_END;
    }

    public static boolean shouldHaveLunchBreak(CustomEntity npc) {
        if (npc == null) return false;

        String job = npc.getJob();
        if (job == null || job.isEmpty()) return false;

        if (FIXED_LUNCH_BREAK_JOBS.contains(job)) {
            return true;
        }

        if (CONFIGURABLE_LUNCH_BREAK_JOBS.contains(job)) {
            return shouldIndustrialHaveLunchBreak(npc);
        }

        if (isCommercialJob(job)) {
            return shouldCommercialHaveLunchBreak(npc);
        }

        return false;
    }

    private static boolean shouldIndustrialHaveLunchBreak(CustomEntity npc) {
        BlockPos workPos = findIndustrialWorkPosition(npc.getUUID(), npc.level().getServer());
        if (workPos == null) return true;

        IndustrialBuildingConfig config = getIndustrialConfig(npc.level().getServer(), workPos);
        if (config == null) return true;

        String selectedRecipeId = com.xiaoliang.simukraft.building.ControlBoxDataManager.getSelectedRecipe(
            npc.level().getServer(), workPos);

        return config.isHasLunchBreakForRecipe(selectedRecipeId);
    }

    private static boolean shouldCommercialHaveLunchBreak(CustomEntity npc) {
        BlockPos workPos = findCommercialWorkPosition(npc.getUUID(), npc.level().getServer());
        if (workPos == null) return true;

        CommercialBuildingConfig config = getCommercialConfig(npc.level().getServer(), workPos);
        if (config == null) return true;

        return config.isHasLunchBreak();
    }

    private static boolean isCommercialJob(String job) {
        return CommercialBuildingManager.isCommercialJobType(job);
    }

    public static boolean shouldStartLunchBreak(CustomEntity npc, long dayTime) {
        if (npc == null) return false;
        if (!isLunchBreakTime(dayTime)) return false;
        if (npc.getWorkSubState() == WorkSubState.LUNCH_BREAK
                || npc.getWorkSubState() == WorkSubState.BUYING_FOOD) {
            return false;
        }
        if (npc.getWorkStatus() != WorkStatus.WORKING) return false;

        return shouldHaveLunchBreak(npc);
    }

    public static boolean shouldEndLunchBreak(CustomEntity npc, long dayTime) {
        if (npc == null) return false;
        if (isLunchBreakTime(dayTime)) return false;
        if (npc.getWorkSubState() != WorkSubState.LUNCH_BREAK) return false;

        return true;
    }

    public static void startLunchBreak(CustomEntity npc, BlockPos workPos) {
        if (npc == null || workPos == null) return;

        UUID npcId = npc.getUUID();
        lunchBreakWorkPositions.put(npcId, workPos.immutable());
        lunchBreakPreviousWorkStatus.put(npcId, npc.getWorkStatus());
        lunchBreakPreviousJob.put(npcId, npc.getJob());

        npc.setWorkSubState(WorkSubState.LUNCH_BREAK);
        npc.setStatusLabel("gui.npc.status.lunch_break");

        stopAllNPCActivities(npc);

        Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 开始午休，工作位置: {}",
            npc.getFullName(), workPos);
    }

    private static void stopAllNPCActivities(CustomEntity npc) {
        if (npc == null) return;

        npc.setWorking(false);
        npc.setNoAi(false);
        npc.setTarget(null);
        npc.setLastHurtByMob(null);

        if (npc.getConstructionTask() != null) {
            npc.setConstructionTask(null);
        }

        com.xiaoliang.simukraft.entity.ai.NPCBoundaryManager boundaryManager = npc.getBoundaryManager();
        if (boundaryManager != null) {
            boundaryManager.clearRestrictedArea();
        }

        Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 的所有活动已停止，已设置为可移动状态", npc.getFullName());
    }

    public static void endLunchBreak(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null) return;

        UUID npcId = npc.getUUID();
        BlockPos workPos = lunchBreakWorkPositions.get(npcId);
        WorkStatus previousWorkStatus = lunchBreakPreviousWorkStatus.getOrDefault(npcId, WorkStatus.WORKING);
        String previousJob = lunchBreakPreviousJob.getOrDefault(npcId, npc.getJob());

        if (workPos == null) {
            workPos = findWorkPosition(npc);
        }

        stopSleepingIfNeeded(npc);
        npc.setWorking(false);

        if (previousWorkStatus == WorkStatus.WORKING && previousJob != null && !previousJob.isBlank() && !"unemployed".equals(previousJob)) {
            if (workPos != null) {
                double distance = npc.position().distanceTo(new net.minecraft.world.phys.Vec3(workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5));
                boolean preferImmediateTeleport = CommercialBuildingManager.isCommercialJobType(previousJob)
                    || findIndustrialWorkPosition(npcId, level.getServer()) != null;

                if (distance > 10.0 || (preferImmediateTeleport && distance > 3.0)) {
                    Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 午休结束后距离工作岗位{}格，直接传送回工作岗位: {}",
                        npc.getFullName(), distance, workPos);
                    spawnTeleportParticles(npc);
                    npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);
                    spawnTeleportParticles(npc);
                    restoreWorkStatus(npc, npcId, previousWorkStatus, previousJob, level);
                } else if (distance > 3.0) {
                    Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 午休结束后开始寻路返回工作岗位: {}",
                        npc.getFullName(), workPos);
                    restoreWorkStatus(npc, npcId, previousWorkStatus, previousJob, level);
                    npc.setStatusLabel("gui.npc.status.going_to_work");
                    clearLunchBreakData(npcId, false);
                    com.xiaoliang.simukraft.entity.ai.NPCBoundaryManager boundaryManager = npc.getBoundaryManager();
                    if (boundaryManager != null) {
                        boundaryManager.clearRestrictedArea();
                    }
                    npc.setWorkSubState(WorkSubState.RESTING);
                    npc.moveToWithNewPathfinder(workPos, 1.0D);
                    return;
                } else {
                    restoreWorkStatus(npc, npcId, previousWorkStatus, previousJob, level);
                }
            } else {
                restoreWorkStatus(npc, npcId, previousWorkStatus, previousJob, level);
            }
        } else {
            npc.setWorkStatus(WorkStatus.IDLE);
            npc.setWorkSubState(WorkSubState.NONE);
            npc.setWorking(false);
            npc.setStatusLabel(null);
        }

        com.xiaoliang.simukraft.entity.ai.NPCBoundaryManager boundaryManager = npc.getBoundaryManager();
        if (boundaryManager != null) {
            boundaryManager.clearRestrictedArea();
        }

        clearLunchBreakData(npcId, true);

        Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 结束午休，恢复原状态: {}，原职业: {}",
            npc.getFullName(), previousWorkStatus, previousJob);
    }

    private static void restoreWorkStatus(CustomEntity npc, UUID npcId, WorkStatus previousWorkStatus, String previousJob, ServerLevel level) {
        stopSleepingIfNeeded(npc);

        if (previousJob != null && !previousJob.isBlank()) {
            npc.setJob(previousJob);
        }

        npc.setWorkStatus(previousWorkStatus);

        if (previousWorkStatus == WorkStatus.WORKING) {
            npc.setWorkSubState(WorkSubState.WORKING);
            npc.setWorking(true);
            restoreJobSpecificWorkState(npc, npcId, previousJob, level);
        } else {
            npc.setWorkSubState(WorkSubState.NONE);
            npc.setWorking(false);
        }

        npc.setStatusLabel(null);

        if ("builder".equals(previousJob) && level != null && level.getServer() != null) {
            npc.setConstructionTask(null);
            BlockPos buildBoxPos = findBuilderWorkPosition(npcId, level.getServer());
            if (buildBoxPos != null) {
                restoreBuilderTaskFromJson(level.getServer(), npc, buildBoxPos);
            }
        }

        clearLunchBreakData(npcId, true);
    }

    private static void restoreJobSpecificWorkState(CustomEntity npc, UUID npcId, String previousJob, ServerLevel level) {
        if (npc == null || previousJob == null || previousJob.isBlank() || level == null || level.getServer() == null) {
            return;
        }

        BlockPos workPos = findWorkPositionByJob(npcId, level.getServer(), previousJob);
        if (workPos == null) {
            return;
        }

        if ("builder".equals(previousJob)) {
            return;
        }

        if ("farmer".equals(previousJob)) {
            com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService.INSTANCE.restoreWorkState(npc, npcId, level);
            return;
        }

        if (CommercialBuildingManager.isCommercialJobType(previousJob)) {
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

    private static void stopSleepingIfNeeded(CustomEntity npc) {
        if (npc == null) {
            return;
        }
        if (npc.isSleeping()) {
            npc.stopSleeping();
        }
    }

    private static void clearLunchBreakData(UUID npcId, boolean clearRuntimeWorkPos) {
        if (npcId == null) {
            return;
        }
        lunchBreakPreviousWorkStatus.remove(npcId);
        lunchBreakPreviousJob.remove(npcId);
        if (clearRuntimeWorkPos) {
            lunchBreakWorkPositions.remove(npcId);
        }
    }

    private static void spawnTeleportParticles(CustomEntity npc) {
        if (npc.level() instanceof ServerLevel serverLevel) {
            for (int i = 0; i < 20; i++) {
                serverLevel.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.PORTAL,
                    npc.getX(), npc.getY() + 1.0, npc.getZ(),
                    1,
                    0.5, 0.5, 0.5,
                    0.1
                );
            }
        }
    }

    private static void restoreBuilderTaskFromJson(MinecraftServer server, CustomEntity npc, BlockPos buildBoxPos) {
        if (server == null || npc == null || buildBoxPos == null) return;

        if (npc.getConstructionTask() != null) {
            return;
        }

        com.xiaoliang.simukraft.world.ConstructionTaskData.TaskInfo taskInfo =
            com.xiaoliang.simukraft.world.ConstructionTaskData.loadTask(server, npc.getUUID());
        if (taskInfo == null) {
            Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 没有保存的建造任务", npc.getFullName());
            return;
        }

        try {
            if (taskInfo.buildingName == null || taskInfo.category == null ||
                taskInfo.startPos == null || taskInfo.buildBoxPos == null ||
                taskInfo.facing == null || taskInfo.displayName == null) {
                Simukraft.LOGGER.warn("[LunchBreakManager] 建造任务数据不完整，无法恢复 - NPC: {}",
                    npc.getFullName());
                return;
            }

            ServerLevel level = server.overworld();
            net.minecraft.world.level.block.state.BlockState buildBoxState = level.getBlockState(taskInfo.buildBoxPos);
            if (buildBoxState.isAir()) {
                Simukraft.LOGGER.warn("[LunchBreakManager] 建筑盒已不存在，移除建造任务 - NPC: {}",
                    npc.getFullName());
                com.xiaoliang.simukraft.world.ConstructionTaskData.removeTask(server, npc.getUUID());
                return;
            }

            com.xiaoliang.simukraft.building.ConstructionTask task = new com.xiaoliang.simukraft.building.ConstructionTask(
                taskInfo.buildingName,
                taskInfo.category,
                taskInfo.startPos,
                taskInfo.buildBoxPos,
                taskInfo.facing,
                taskInfo.displayName,
                taskInfo.cost,
                level
            );

            task.setCurrentBlockIndex(taskInfo.currentBlockIndex);
            npc.setConstructionTask(task);

            Simukraft.LOGGER.debug("[LunchBreakManager] 成功恢复建造任务 - NPC: {}, 建筑: {}, 进度: {}/{}",
                npc.getFullName(),
                taskInfo.displayName,
                taskInfo.currentBlockIndex,
                task.getTotalBlocks());
        } catch (Exception e) {
            Simukraft.LOGGER.error("[LunchBreakManager] 恢复建造任务时出错 - NPC: {}", npc.getFullName(), e);
        }
    }

    public static void restoreLunchBreakState(CustomEntity npc) {
        if (npc == null) return;

        if (npc.getWorkSubState() == WorkSubState.LUNCH_BREAK) {
            UUID npcId = npc.getUUID();
            if (!lunchBreakWorkPositions.containsKey(npcId)) {
                BlockPos workPos = findWorkPosition(npc);
                if (workPos != null) {
                    lunchBreakWorkPositions.put(npcId, workPos);
                    Simukraft.LOGGER.debug("[LunchBreakManager] 恢复NPC {} 的午休工作位置: {}",
                        npc.getFullName(), workPos);
                }
            }
            lunchBreakPreviousWorkStatus.putIfAbsent(npcId, WorkStatus.WORKING);
            lunchBreakPreviousJob.putIfAbsent(npcId, npc.getJob());
        }
    }

    @Nullable
    public static BlockPos getWorkPosition(UUID npcId) {
        return lunchBreakWorkPositions.get(npcId);
    }

    public static void handleLunchBreak(ServerLevel level) {
        if (level == null) return;

        long dayTime = level.getDayTime();

        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof CustomEntity npc)) continue;

            restoreLunchBreakState(npc);

            if (shouldStartLunchBreak(npc, dayTime)) {
                BlockPos workPos = findWorkPosition(npc);
                if (workPos != null) {
                    startLunchBreak(npc, workPos);
                }
            }

            if (shouldEndLunchBreak(npc, dayTime)) {
                endLunchBreak(npc, level);
            }
        }
    }

    @Nullable
    private static BlockPos findWorkPosition(CustomEntity npc) {
        if (npc == null) return null;

        String job = npc.getJob();
        UUID npcId = npc.getUUID();
        MinecraftServer server = npc.level().getServer();
        if (server == null) return null;

        return findWorkPositionByJob(npcId, server, job);
    }

    @Nullable
    private static BlockPos findWorkPositionByJob(UUID npcId, MinecraftServer server, String job) {
        if (job == null || server == null || npcId == null) {
            return null;
        }

        return switch (job) {
            case "builder" -> findBuilderWorkPosition(npcId, server);
            case "planner" -> findPlannerWorkPosition(npcId, server);
            case "farmer" -> findFarmerWorkPosition(npcId);
            case "warehouse_manager" -> findWarehouseWorkPosition(npcId, server);
            case "industrial" -> findIndustrialWorkPosition(npcId, server);
            default -> findCommercialWorkPosition(npcId, server);
        };
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
        Map<BlockPos, UUID> hiredFarmers = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        for (Map.Entry<BlockPos, UUID> entry : hiredFarmers.entrySet()) {
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
        Map<BlockPos, UUID> hiredManagers = com.xiaoliang.simukraft.world.LogisticsHiredData.getServerBoxHiredNpcs(server);
        for (Map.Entry<BlockPos, UUID> entry : hiredManagers.entrySet()) {
            if (npcId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findIndustrialWorkPosition(UUID npcId, MinecraftServer server) {
        var hiredEmployees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : hiredEmployees.entrySet()) {
            var hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcId)) {
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

    @Nullable
    private static IndustrialBuildingConfig getIndustrialConfig(MinecraftServer server, BlockPos workPos) {
        if (server == null || workPos == null) return null;
        String buildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(server.overworld(), workPos);
        if (buildingFileName == null) return null;
        return IndustrialBuildingManager.getConfig(buildingFileName);
    }

    @Nullable
    private static CommercialBuildingConfig getCommercialConfig(MinecraftServer server, BlockPos workPos) {
        if (server == null || workPos == null) return null;
        String buildingFileName = com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.getBuildingFileName(server.overworld(), workPos);
        if (buildingFileName == null) return null;
        return CommercialBuildingManager.getConfig(buildingFileName);
    }
}
