package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.utils.ContainerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("null")
public final class PlannerStorageService {
    private static final long CONTAINER_CACHE_DURATION_MS = 30000;

    private final List<BlockPos> cachedContainerPositions = new ArrayList<>();
    private long lastContainerCheckTime;

    public boolean consumeItemFromNearbyChest(ServerLevel level, BlockPos sourcePos, ItemStack requiredItem) {
        updateContainerCache(level, sourcePos);

        for (BlockPos pos : cachedContainerPositions) {
            if (tryConsumeFromContainer(level, pos, requiredItem)) {
                return true;
            }
        }
        return false;
    }

    public boolean storeItemsInNearbyChest(ServerLevel level, BlockPos sourcePos, List<ItemStack> items) {
        List<ItemStack> remainingItems = new ArrayList<>();
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                remainingItems.add(item.copy());
            }
        }

        if (remainingItems.isEmpty()) {
            return true;
        }

        updateContainerCache(level, sourcePos);

        for (BlockPos pos : cachedContainerPositions) {
            if (tryInsertToContainer(level, pos, remainingItems)) {
                return true;
            }

            if (remainingItems.stream().allMatch(ItemStack::isEmpty)) {
                return true;
            }
        }

        int droppedCount = 0;
        for (ItemStack item : remainingItems) {
            if (!item.isEmpty()) {
                Block.popResource(Objects.requireNonNull(level), sourcePos, item);
                droppedCount++;
            }
        }
        return droppedCount == 0;
    }

    private void updateContainerCache(ServerLevel level, BlockPos sourcePos) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastContainerCheckTime < CONTAINER_CACHE_DURATION_MS && !cachedContainerPositions.isEmpty()) {
            return;
        }

        cachedContainerPositions.clear();
        Direction[] directions = new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

        for (Direction dir : directions) {
            BlockPos pos = sourcePos.relative(dir);
            if (ContainerUtils.isContainer(level, pos)) {
                cachedContainerPositions.add(pos);
                BlockPos otherHalf = getOtherChestHalf(level, pos);
                if (otherHalf != null && !cachedContainerPositions.contains(otherHalf)) {
                    cachedContainerPositions.add(otherHalf);
                }
            }
        }
        lastContainerCheckTime = currentTime;
    }

    private boolean tryConsumeFromContainer(ServerLevel level, BlockPos pos, ItemStack requiredItem) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }

        var cap = blockEntity.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            net.minecraftforge.items.IItemHandler handler = cap.get();
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (!stack.isEmpty() && net.minecraftforge.items.ItemHandlerHelper.canItemStacksStack(stack, requiredItem)) {
                    ItemStack extracted = handler.extractItem(i, 1, false);
                    if (!extracted.isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        if (blockEntity instanceof net.minecraft.world.Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && net.minecraftforge.items.ItemHandlerHelper.canItemStacksStack(stack, requiredItem)) {
                    ItemStack toConsume = requiredItem.copy();
                    toConsume.setCount(1);
                    if (ContainerUtils.consumeItem(level, pos, toConsume)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean tryInsertToContainer(ServerLevel level, BlockPos pos, List<ItemStack> items) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }

        var cap = blockEntity.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            net.minecraftforge.items.IItemHandler handler = cap.get();
            for (ItemStack item : items) {
                if (item.isEmpty()) continue;

                for (int i = 0; i < handler.getSlots() && !item.isEmpty(); i++) {
                    ItemStack remaining = handler.insertItem(i, item, false);
                    item.setCount(remaining.getCount());
                }
            }
            return items.stream().allMatch(ItemStack::isEmpty);
        }

        if (blockEntity instanceof net.minecraft.world.Container container) {
            for (ItemStack item : items) {
                if (item.isEmpty()) continue;

                for (int i = 0; i < container.getContainerSize() && !item.isEmpty(); i++) {
                    ItemStack slotStack = container.getItem(i);
                    if (slotStack.isEmpty()) {
                        container.setItem(i, item.copy());
                        item.setCount(0);
                    } else if (net.minecraftforge.items.ItemHandlerHelper.canItemStacksStack(slotStack, item)) {
                        int maxStack = Math.min(slotStack.getMaxStackSize(), container.getMaxStackSize());
                        int canAdd = maxStack - slotStack.getCount();
                        if (canAdd > 0) {
                            int toAdd = Math.min(canAdd, item.getCount());
                            slotStack.grow(toAdd);
                            item.shrink(toAdd);
                        }
                    }
                }
            }
            return items.stream().allMatch(ItemStack::isEmpty);
        }
        return false;
    }

    @javax.annotation.Nullable
    private BlockPos getOtherChestHalf(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return null;
        }

        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = pos.relative(dir);
            var neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof ChestBlock) {
                if (state.hasProperty(ChestBlock.TYPE) && neighborState.hasProperty(ChestBlock.TYPE)) {
                    return neighborPos;
                }
            }
        }
        return null;
    }
}
