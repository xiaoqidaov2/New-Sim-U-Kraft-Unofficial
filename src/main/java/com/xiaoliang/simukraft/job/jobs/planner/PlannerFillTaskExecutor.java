package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.planning.PlanningTask;
import com.xiaoliang.simukraft.utils.BlockNameTranslator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;

public final class PlannerFillTaskExecutor implements PlannerTaskExecutor {
    @Override
    public void process(ServerLevel level, PlannerWorkController controller, PlanningTask task) {
        if (!controller.tickWorkTimer(ServerConfig.getPlannerFillSpeed(getPlannerLevel(level, controller)), level)) {
            return;
        }

        String targetBlockId = task.getTargetBlockId();
        if (targetBlockId == null || targetBlockId.isEmpty()) {
            Simukraft.LOGGER.error("[PlannerFillTaskExecutor] Fill task has no target block, cancelled");
            task.setStatus(PlanningTask.TaskStatus.CANCELLED);
            controller.completeTask(level, task);
            return;
        }

        Block targetBlock = controller.resolveBlock(targetBlockId);
        if (targetBlock == null) {
            Simukraft.LOGGER.error("[PlannerFillTaskExecutor] 无法解析填充目标方块: {}", targetBlockId);
            task.setStatus(PlanningTask.TaskStatus.CANCELLED);
            controller.completeTask(level, task);
            return;
        }

        ResourceLocation targetBlockIdLoc = ForgeRegistries.BLOCKS.getKey(targetBlock);
        if (targetBlockIdLoc != null && ServerConfig.isBlockBlacklistedForPlanning(targetBlockIdLoc.toString())) {
            if (ServerConfig.shouldLogSkippedBlocks()) {
                Simukraft.LOGGER.error("[PlannerFillTaskExecutor] 填充目标方块在黑名单中，取消任务: {}", targetBlockIdLoc);
            }
            task.setStatus(PlanningTask.TaskStatus.CANCELLED);
            controller.completeTask(level, task);
            return;
        }

        BlockPos targetPos = null;
        BlockState targetState = null;
        while ((targetPos = task.getNextBlock()) != null) {
            targetState = level.getBlockState(targetPos);
            if (targetState.isAir() || controller.isReplaceablePlant(targetState)) {
                break;
            }
            task.markCurrentBlockComplete();
        }

        if (targetPos == null) {
            controller.completeTask(level, task);
            return;
        }

        controller.setCurrentTargetPos(targetPos);
        ItemStack requiredItem = new ItemStack(Objects.requireNonNull(targetBlock.asItem()));
        boolean consumed = controller.storageService().consumeItemFromNearbyChest(level, task.getBuildBoxPos(), requiredItem);

        if (!consumed) {
            controller.notificationService().sendMaterialInsufficientMessage(level, task, BlockNameTranslator.getBlockComponent(targetBlock));
            return;
        }

        if (targetState != null && controller.isReplaceablePlant(targetState)) {
            level.destroyBlock(targetPos, false);
        }

        level.setBlock(targetPos, Objects.requireNonNull(targetBlock.defaultBlockState()), 3);
        controller.addPlannerXp(level);
        task.markCurrentBlockComplete();
        controller.saveTask(level, task);

        if (task.getStatus() == PlanningTask.TaskStatus.COMPLETED) {
            controller.completeTask(level, task);
        }
    }

    private int getPlannerLevel(ServerLevel level, PlannerWorkController controller) {
        return com.xiaoliang.simukraft.utils.NPCDataManager.getNPCLevel(level.getServer(), controller.npcId());
    }
}
