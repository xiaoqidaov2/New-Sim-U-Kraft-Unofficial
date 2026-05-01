package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.planning.PlanningTask;
import com.xiaoliang.simukraft.planning.PlanningTaskManager;
import com.xiaoliang.simukraft.utils.NPCDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.UUID;

public final class PlannerWorkController {
    private final CustomEntity npc;
    private final UUID npcId;
    private final PlannerStorageService storageService;
    private final PlannerNotificationService notificationService;
    private int workTimer;
    private BlockPos currentTargetPos;

    public PlannerWorkController(CustomEntity npc) {
        this.npc = npc;
        this.npcId = npc.getUUID();
        this.storageService = new PlannerStorageService();
        this.notificationService = new PlannerNotificationService(npc);
    }

    public CustomEntity npc() {
        return npc;
    }

    public UUID npcId() {
        return npcId;
    }

    public PlannerStorageService storageService() {
        return storageService;
    }

    public PlannerNotificationService notificationService() {
        return notificationService;
    }

    public BlockPos currentTargetPos() {
        return currentTargetPos;
    }

    public void setCurrentTargetPos(BlockPos currentTargetPos) {
        this.currentTargetPos = currentTargetPos;
    }

    public boolean tickWorkTimer(int requiredTicks, ServerLevel level) {
        workTimer++;
        if (workTimer < requiredTicks) {
            if (currentTargetPos != null && workTimer % 10 == 0) {
                showWorkingParticles(level, currentTargetPos);
            }
            return false;
        }
        workTimer = 0;
        return true;
    }

    public void reset() {
        currentTargetPos = null;
        workTimer = 0;
    }

    public void addPlannerXp(ServerLevel level) {
        if (level.getServer() != null && ServerConfig.isPlannerXpGainEnabled()) {
            NPCDataManager.addXp(level.getServer(), npcId, ServerConfig.getPlannerXpPerBlock());
        }
    }

    public void saveTask(ServerLevel level, PlanningTask task) {
        if (level.getServer() != null) {
            PlannerWorkService.INSTANCE.savePlanningTask(level.getServer(), task);
        }
    }

    public void completeTask(ServerLevel level, PlanningTask task) {
        UUID taskId = task.getTaskId();
        PlanningTaskManager.get(level).completeTask(taskId);
        if (level.getServer() != null) {
            PlannerWorkService.INSTANCE.removePlanningTask(level.getServer(), taskId);
        }
        notificationService.sendCompletionMessage(level, task);
        reset();
    }

    public Block resolveBlock(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return null;
        }
        String parsedId = blockId.replace("block.", "").replaceFirst("\\.", ":");
        net.minecraft.resources.ResourceLocation resourceLocation = net.minecraft.resources.ResourceLocation.tryParse(Objects.requireNonNull(parsedId));
        if (resourceLocation == null) {
            return null;
        }
        Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(resourceLocation);
        return block == Blocks.AIR ? null : block;
    }

    public boolean isBlockBlacklisted(Block block) {
        net.minecraft.resources.ResourceLocation blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
        return blockId != null && ServerConfig.isBlockBlacklistedForPlanning(blockId.toString());
    }

    public boolean isReplaceablePlant(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.GRASS ||
               block == Blocks.TALL_GRASS ||
               block == Blocks.FERN ||
               block == Blocks.LARGE_FERN ||
               block == Blocks.DEAD_BUSH ||
               block == Blocks.DANDELION ||
               block == Blocks.POPPY ||
               block == Blocks.BLUE_ORCHID ||
               block == Blocks.ALLIUM ||
               block == Blocks.AZURE_BLUET ||
               block == Blocks.RED_TULIP ||
               block == Blocks.ORANGE_TULIP ||
               block == Blocks.WHITE_TULIP ||
               block == Blocks.PINK_TULIP ||
               block == Blocks.OXEYE_DAISY ||
               block == Blocks.CORNFLOWER ||
               block == Blocks.LILY_OF_THE_VALLEY ||
               block == Blocks.WITHER_ROSE ||
               block == Blocks.SUNFLOWER ||
               block == Blocks.LILAC ||
               block == Blocks.ROSE_BUSH ||
               block == Blocks.PEONY ||
               block == Blocks.TALL_SEAGRASS ||
               block == Blocks.SEAGRASS ||
               block == Blocks.KELP ||
               block == Blocks.KELP_PLANT;
    }

    private void showWorkingParticles(ServerLevel level, BlockPos pos) {
        level.sendParticles(
                Objects.requireNonNull(ParticleTypes.CRIT),
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                3, 0.2, 0.2, 0.2, 0.0
        );
    }
}
