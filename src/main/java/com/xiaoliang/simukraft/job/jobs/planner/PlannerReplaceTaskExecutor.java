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

import java.util.List;
import java.util.Map; 
import java.util.Objects;

public final class PlannerReplaceTaskExecutor implements PlannerTaskExecutor {
    @Override
    public void process(ServerLevel level, PlannerWorkController controller, PlanningTask task) {
        if (!controller.tickWorkTimer(ServerConfig.getPlannerReplaceSpeed(getPlannerLevel(level, controller)), level)) {
            return;
        }

        Map<String, String> replacementMap = task.getReplacementMap();
        if (replacementMap == null || replacementMap.isEmpty()) {
            Simukraft.LOGGER.error("[PlannerReplaceTaskExecutor] Replacement task has no mapping, cancelled");
            task.setStatus(PlanningTask.TaskStatus.CANCELLED);
            controller.completeTask(level, task);
            return;
        }

        BlockPos targetPos = null;
        BlockState currentState = null;
        String currentBlockId = null;

        while ((targetPos = task.getNextBlock()) != null) {
            currentState = level.getBlockState(targetPos);
            Block currentBlock = currentState.getBlock();
            currentBlockId = currentBlock.getDescriptionId();
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(currentBlock);
            if (blockId != null && ServerConfig.isBlockBlacklistedForPlanning(blockId.toString())) {
                task.markCurrentBlockComplete();
                continue;
            }
            if (replacementMap.containsKey(currentBlockId)) {
                break;
            }
            task.markCurrentBlockComplete();
        }

        if (targetPos == null) {
            controller.completeTask(level, task);
            return;
        }

        controller.setCurrentTargetPos(targetPos);
        String targetBlockId = replacementMap.get(currentBlockId);
        if (targetBlockId == null) {
            task.markCurrentBlockComplete();
            return;
        }

        Block targetBlock = controller.resolveBlock(targetBlockId);
        if (targetBlock == null) {
            Simukraft.LOGGER.error("[PlannerReplaceTaskExecutor] 无法解析目标方块: {}", targetBlockId);
            task.markCurrentBlockComplete();
            return;
        }

        ItemStack requiredItem = new ItemStack(Objects.requireNonNull(targetBlock.asItem()));
        boolean consumed = controller.storageService().consumeItemFromNearbyChest(level, task.getBuildBoxPos(), requiredItem);
        if (!consumed) {
            controller.notificationService().sendMaterialInsufficientMessage(level, task, BlockNameTranslator.getBlockComponent(targetBlock));
            return;
        }

        List<ItemStack> drops = Block.getDrops(Objects.requireNonNull(currentState), Objects.requireNonNull(level), targetPos, null);
        level.destroyBlock(targetPos, false);
        level.setBlock(targetPos, Objects.requireNonNull(targetBlock.defaultBlockState()), 3);

        if (ServerConfig.shouldDropItemsOnRemove() && !drops.isEmpty()) {
            if (ServerConfig.shouldStoreItemsInChest()) {
                boolean allStored = controller.storageService().storeItemsInNearbyChest(level, task.getBuildBoxPos(), drops);
                if (!allStored) {
                    controller.notificationService().sendChestFullMessage(level, task);
                }
            } else {
                for (ItemStack item : drops) {
                    if (!item.isEmpty()) {
                        Block.popResource(level, targetPos, item);
                    }
                }
            }
        }

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
