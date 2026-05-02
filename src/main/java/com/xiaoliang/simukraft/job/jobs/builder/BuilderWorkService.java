package com.xiaoliang.simukraft.job.jobs.builder;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.core.services.AbstractWorkService;
import com.xiaoliang.simukraft.world.BuildBoxHiredData;
import com.xiaoliang.simukraft.world.ConstructionTaskData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public class BuilderWorkService extends AbstractWorkService {

    public static final BuilderWorkService INSTANCE = new BuilderWorkService();

    private static final int SAVE_INTERVAL_MS = 30000;
    private static final int TELEPORT_CHECK_DISTANCE = 64;

    private final ConcurrentMap<UUID, Long> lastSaveTime = new ConcurrentHashMap<>();

    public void startDailyWork(ServerLevel level) {
        if (level == null) return;

        long dayTime = level.getDayTime() % 24000L;
        boolean isWorkTime = dayTime >= 0 && dayTime < 12000;
        if (!isWorkTime) {
            return;
        }

        MinecraftServer server = level.getServer();
        Map<BlockPos, UUID> hiredBuilders = BuildBoxHiredData.loadHiredBuilders(server);

        if (hiredBuilders.isEmpty()) {
            return;
        }

        Map<UUID, ConstructionTaskData.TaskInfo> persistedTasks =
                ConstructionTaskData.loadTasks(server, hiredBuilders.values());

        var employmentService = com.xiaoliang.simukraft.employment.service.EmploymentServices.get(server);
        String dimensionId = level.dimension().location().toString();

        for (Map.Entry<BlockPos, UUID> entry : hiredBuilders.entrySet()) {
            BlockPos buildBoxPos = entry.getKey();
            UUID npcUuid = entry.getValue();

            CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);

            if (npc != null) {
                var existingAssignment = employmentService.findByNpc(npcUuid);
                if (existingAssignment.isEmpty()) {
                    var allAssignments = employmentService.listByCity(null);
                    boolean hasOtherJob = allAssignments.stream()
                            .filter(a -> a.npcUuid().equals(npcUuid))
                            .filter(a -> a.jobType() != com.xiaoliang.simukraft.employment.domain.JobType.BUILDER)
                            .anyMatch(a -> a.status() == com.xiaoliang.simukraft.employment.domain.EmploymentStatus.ASSIGNED);
                    if (!hasOtherJob) {
                        var hireResult = employmentService.hire(new com.xiaoliang.simukraft.employment.service.EmploymentCommands.HireCommand(
                            npcUuid,
                            dimensionId,
                            buildBoxPos,
                            com.xiaoliang.simukraft.employment.domain.WorkBlockType.BUILD_BOX,
                            com.xiaoliang.simukraft.employment.domain.JobType.BUILDER
                        ));
                        if (!hireResult.success()) {
                            Simukraft.LOGGER.warn("[BuilderWorkService] 创建v2雇佣记录失败 - NPC: {}, 原因: {}",
                                npcUuid.toString().substring(0, 8), hireResult.message());
                        }
                    }
                }

                restoreConstructionTaskIfNeeded(server, npc, buildBoxPos, hiredBuilders, persistedTasks.get(npcUuid));

                if (!"builder".equals(npc.getJob())) {
                    npc.setJob("builder");
                }

                boolean hasActiveTask = npc.getConstructionTask() != null
                        && !npc.getConstructionTask().isCompleted()
                        && npc.getConstructionTask().hasNextBlock();

                com.xiaoliang.simukraft.utils.NPCWorkResumeCoordinator.resumeBuilderWork(npc, buildBoxPos, hasActiveTask);
            }
        }
    }

    private void restoreConstructionTaskIfNeeded(MinecraftServer server, CustomEntity npc, BlockPos buildBoxPos,
                                                Map<BlockPos, UUID> hiredBuilders,
                                                ConstructionTaskData.TaskInfo taskInfo) {
        if (npc.getConstructionTask() != null) {
            return;
        }

        if (taskInfo == null) {
            return;
        }

        try {
            boolean isStillHired = hiredBuilders.values().stream()
                    .anyMatch(uuid -> uuid.equals(npc.getUUID()));

            if (!isStillHired) {
                ConstructionTaskData.removeTask(server, npc.getUUID());
                return;
            }

            ServerLevel level = server.overworld();
            BlockState buildBoxState = level.getBlockState(Objects.requireNonNull(taskInfo.buildBoxPos));
            if (buildBoxState.isAir()) {
                ConstructionTaskData.removeTask(server, npc.getUUID());
                return;
            }

            ConstructionTask tempTask = new ConstructionTask(
                Objects.requireNonNull(taskInfo.buildingName),
                Objects.requireNonNull(taskInfo.category),
                Objects.requireNonNull(taskInfo.startPos),
                Objects.requireNonNull(taskInfo.buildBoxPos),
                Objects.requireNonNull(taskInfo.facing),
                Objects.requireNonNull(taskInfo.displayName),
                taskInfo.cost,
                level
            );
            int totalBlocks = tempTask.getTotalBlocks();

            if (taskInfo.currentBlockIndex >= totalBlocks) {
                ConstructionTaskData.removeTask(server, npc.getUUID());
                return;
            }

            ConstructionTask task = tempTask;
            task.setCurrentBlockIndex(taskInfo.currentBlockIndex);
            npc.setConstructionTask(task);

            Simukraft.LOGGER.info("[BuilderWorkService] 成功恢复建造任?- NPC: {}, 建筑: {}, 进度: {}/{}",
                npc.getUUID().toString().substring(0, 8),
                taskInfo.displayName,
                taskInfo.currentBlockIndex,
                task.getTotalBlocks());

        } catch (Exception e) {
            Simukraft.LOGGER.error("[BuilderWorkService] 恢复建造任务失?- NPC: {}",
                npc.getUUID().toString().substring(0, 8), e);
        }
    }

    public void saveConstructionTask(MinecraftServer server, CustomEntity npc) {
        if (server == null || npc == null) return;

        ConstructionTask task = npc.getConstructionTask();
        if (task == null) {
            ConstructionTaskData.removeTask(server, npc.getUUID());
            return;
        }

        try {
            ConstructionTaskData.TaskInfo taskInfo = new ConstructionTaskData.TaskInfo(
                Objects.requireNonNull(task.getInternalBuildingName()),
                Objects.requireNonNull(task.getCategory()),
                Objects.requireNonNull(task.getStartPos()),
                Objects.requireNonNull(task.getBuildBoxPos()),
                Objects.requireNonNull(task.getFacing().getName()),
                Objects.requireNonNull(task.getDisplayName()),
                task.getCost(),
                task.getCurrentBlockIndex()
            );

            ConstructionTaskData.saveTask(server, npc.getUUID(), taskInfo);
        } catch (Exception e) {
            Simukraft.LOGGER.error("[BuilderWorkService] 保存建造任务失?- NPC: {}",
                npc.getUUID().toString().substring(0, 8), e);
        }
    }

    public void removeConstructionTask(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) return;
        ConstructionTaskData.removeTask(server, npcUuid);
    }

    public void autoDismissCompletedBuilder(ServerLevel level, CustomEntity npc, BlockPos buildBoxPos) {
        if (level == null || npc == null) {
            return;
        }

        MinecraftServer server = level.getServer();
        var employmentService = com.xiaoliang.simukraft.employment.service.EmploymentServices.get(server);
        var releaseResult = employmentService.fireByNpc(
                new com.xiaoliang.simukraft.employment.service.EmploymentCommands.FireByNpcCommand(npc.getUUID())
        );

        BlockPos syncPos = buildBoxPos;
        if (releaseResult.success() && releaseResult.assignment() != null) {
            syncPos = releaseResult.assignment().workplacePos();
            cleanupBuilderLegacyHireData(server, npc.getUUID(), syncPos);
            server.getPlayerList().getPlayers().forEach(player ->
                    com.xiaoliang.simukraft.network.NetworkManager.sendToPlayer(
                            new com.xiaoliang.simukraft.network.EmploymentStateChangedPacket(releaseResult.assignment()),
                            player
                    )
            );
        } else {
            cleanupBuilderLegacyHireData(server, npc.getUUID(), buildBoxPos);
        }

        npc.resetToIdle();
        BlockPos finalSyncPos = syncPos != null ? syncPos : npc.blockPosition();
        String npcName = npc.getFullName();
        server.getPlayerList().getPlayers().forEach(player ->
                com.xiaoliang.simukraft.network.NetworkManager.sendToPlayer(
                        new com.xiaoliang.simukraft.network.NPCWorkStatusPacket(
                                npc.getUUID(),
                                WorkStatus.IDLE,
                                finalSyncPos,
                                npcName,
                                "unemployed"
                        ),
                        player
                )
        );

        Simukraft.LOGGER.info("[BuilderWorkService] Builder {} auto dismissed after construction completed", npcName);
    }

    private void cleanupBuilderLegacyHireData(MinecraftServer server, UUID npcUuid, BlockPos buildBoxPos) {
        if (server == null || npcUuid == null) {
            return;
        }

        Map<BlockPos, UUID> hiredBuilders = BuildBoxHiredData.loadHiredBuilders(server);
        boolean buildersChanged = hiredBuilders.entrySet().removeIf(entry ->
                npcUuid.equals(entry.getValue()) || (buildBoxPos != null && buildBoxPos.equals(entry.getKey()))
        );
        if (buildersChanged) {
            BuildBoxHiredData.saveHiredBuilders(server, hiredBuilders);
        }
    }

    @Override
    protected void onServerStart0(MinecraftServer server, ServerLevel level) {
    }

    @Override
    protected void onServerStop0(ServerLevel level) {
    }

    @Override
    protected void handleDailyXp0(ServerLevel level) {
    }

    @Override
    public void restoreWorkState(CustomEntity npc, UUID npcUuid, ServerLevel level) {
        if (npc == null || npcUuid == null || level == null) return;

        if (!"builder".equals(npc.getJob())) {
            npc.setJob("builder");
        }

        ConstructionTask task = npc.getConstructionTask();
        if (task == null || task.isCompleted() || !task.hasNextBlock()) {
            return;
        }

        if (npc.getWorkStatus() == WorkStatus.IDLE) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
        }

        BlockPos buildBoxPos = task.getBuildBoxPos();
        if (buildBoxPos == null) return;

        BlockPos npcPos = npc.blockPosition();
        if (npcPos.distSqr(buildBoxPos) > TELEPORT_CHECK_DISTANCE * TELEPORT_CHECK_DISTANCE) {
            BlockPos safePos = findSafePositionNearBuildBox(buildBoxPos, level);
            if (safePos != null) {
                if (!npc.moveToWithNewPathfinder(safePos, 1.0D)) {
                    npc.teleportTo(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
                }
                npc.stopNewPathfinder();
            }
        }
    }

    private BlockPos findSafePositionNearBuildBox(BlockPos buildBoxPos, ServerLevel level) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                BlockPos checkPos = buildBoxPos.offset(x, 0, z);
                BlockPos abovePos = checkPos.above();
                BlockPos belowPos = checkPos.below();

                if (level.getBlockState(belowPos).isFaceSturdy(level, belowPos, net.minecraft.core.Direction.UP)
                        && level.isEmptyBlock(checkPos)
                        && level.isEmptyBlock(abovePos)) {
                    return checkPos;
                }
            }
        }
        return buildBoxPos.above();
    }

    @Override
    public void handleContinuousWork(JobContext context) {
        if (context == null || context.level() == null) return;

        ServerLevel level = context.level();
        MinecraftServer server = level.getServer();
        UUID npcUuid = context.assignment().npcUuid();

        if (!dataInitialized) {
            dataInitialized = true;
        }

        CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);
        if (npc == null || !npc.isAlive() || !"builder".equals(npc.getJob())) {
            return;
        }

        restoreWorkState(npc, npcUuid, level);

        ConstructionTask task = npc.getConstructionTask();
        if (task == null || task.isCompleted() || !task.hasNextBlock()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        Long lastSave = lastSaveTime.get(npcUuid);
        if (lastSave == null || currentTime - lastSave >= SAVE_INTERVAL_MS) {
            saveConstructionTask(server, npc);
            lastSaveTime.put(npcUuid, currentTime);
        }
    }
}