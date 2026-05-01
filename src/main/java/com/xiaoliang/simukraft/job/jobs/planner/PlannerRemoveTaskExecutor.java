package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.planning.PlanningTask;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Objects;

public final class PlannerRemoveTaskExecutor implements PlannerTaskExecutor {
    @Override
    public void process(ServerLevel level, PlannerWorkController controller, PlanningTask task) {
        if (!controller.tickWorkTimer(ServerConfig.getPlannerRemoveSpeed(getPlannerLevel(level, controller)), level)) {
            return;
        }

        BlockPos targetPos = null;
        while ((targetPos = task.getNextBlock()) != null) {
            BlockState state = level.getBlockState(targetPos);
            if (state.isAir()) {
                task.markCurrentBlockComplete();
                continue;
            }
            Block block = state.getBlock();
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
            if (blockId != null && ServerConfig.isBlockBlacklistedForPlanning(blockId.toString())) {
                task.markCurrentBlockComplete();
                continue;
            }
            break;
        }

        if (targetPos == null) {
            controller.completeTask(level, task);
            return;
        }

        controller.setCurrentTargetPos(targetPos);
        BlockState blockState = level.getBlockState(targetPos);
        List<ItemStack> drops = Block.getDrops(Objects.requireNonNull(blockState), level, targetPos, null);

        if (!ServerConfig.shouldDropItemsOnRemove()) {
            level.destroyBlock(targetPos, false);
        } else {
            level.destroyBlock(targetPos, false);
            if (!drops.isEmpty() && ServerConfig.shouldStoreItemsInChest()) {
                boolean allStored = controller.storageService().storeItemsInNearbyChest(level, task.getBuildBoxPos(), drops);
                if (!allStored) {
                    controller.notificationService().sendChestFullMessage(level, task);
                }
            } else if (!drops.isEmpty()) {
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
