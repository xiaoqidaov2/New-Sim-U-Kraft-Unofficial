package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 商业建筑的店内箱子交付桥接。
 * 目前只给建材商店使用，避免直接改动原有商业库存模型。
 */
public final class CommercialStorageHelper {
    private static final int SEARCH_RADIUS_XZ = 5;
    private static final int SEARCH_RADIUS_Y = 2;
    private static final String BUILDING_ID_JCSD = "JCSD";
    private static final String JOB_TYPE_BUILDING_MATERIAL = "building_material_merchant";

    private CommercialStorageHelper() {
    }

    public static boolean isBuildingMaterialStore(CommercialBuildingConfig config) {
        if (config == null) {
            return false;
        }
        String buildingId = config.getBuildingId();
        String jobType = config.getJobType();
        return (buildingId != null && BUILDING_ID_JCSD.equalsIgnoreCase(buildingId))
            || (jobType != null && JOB_TYPE_BUILDING_MATERIAL.equalsIgnoreCase(jobType));
    }

    public static boolean canStoreItemsInNearbyContainers(ServerLevel level, BlockPos centerPos, List<ItemStack> stacks) {
        if (level == null || centerPos == null || stacks == null || stacks.isEmpty()) {
            return false;
        }

        ServerLevel safeLevel = Objects.requireNonNull(level);
        BlockPos safeCenterPos = Objects.requireNonNull(centerPos);
        List<ContainerSnapshot> snapshots = collectContainerSnapshots(safeLevel, safeCenterPos);
        if (snapshots.isEmpty()) {
            return false;
        }

        for (ItemStack stack : stacks) {
            int remaining = simulateInsert(snapshots, stack);
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean storeItemsInNearbyContainers(ServerLevel level, BlockPos centerPos, List<ItemStack> stacks) {
        if (level == null || centerPos == null || stacks == null || stacks.isEmpty()) {
            return false;
        }

        ServerLevel safeLevel = Objects.requireNonNull(level);
        BlockPos safeCenterPos = Objects.requireNonNull(centerPos);
        List<BlockPos> containerPositions = collectNearbyContainerPositions(safeLevel, safeCenterPos);
        if (containerPositions.isEmpty()) {
            return false;
        }

        for (ItemStack originalStack : stacks) {
            ItemStack remainingStack = originalStack.copy();

            for (BlockPos containerPos : containerPositions) {
                if (remainingStack.isEmpty()) {
                    break;
                }

                ItemStack attemptStack = remainingStack.copy();
                int inserted = ContainerUtils.insertItem(safeLevel, containerPos, attemptStack);
                if (inserted <= 0) {
                    continue;
                }

                remainingStack.shrink(inserted);
            }

            if (!remainingStack.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static int simulateInsert(List<ContainerSnapshot> snapshots, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        int remaining = stack.getCount();

        for (ContainerSnapshot snapshot : snapshots) {
            remaining = snapshot.mergeIntoExistingStacks(stack, remaining);
            if (remaining <= 0) {
                return 0;
            }
        }

        for (ContainerSnapshot snapshot : snapshots) {
            remaining = snapshot.fillEmptySlots(stack, remaining);
            if (remaining <= 0) {
                return 0;
            }
        }

        return remaining;
    }

    private static List<ContainerSnapshot> collectContainerSnapshots(ServerLevel level, BlockPos centerPos) {
        ServerLevel safeLevel = Objects.requireNonNull(level);
        BlockPos safeCenterPos = Objects.requireNonNull(centerPos);
        List<ContainerSnapshot> snapshots = new ArrayList<>();
        for (BlockPos containerPos : collectNearbyContainerPositions(safeLevel, safeCenterPos)) {
            BlockPos safeContainerPos = Objects.requireNonNull(containerPos);
            BlockEntity blockEntity = safeLevel.getBlockEntity(safeContainerPos);
            if (blockEntity == null || blockEntity.isRemoved()) {
                continue;
            }

            ContainerSnapshot snapshot = ContainerSnapshot.fromBlockEntity(blockEntity);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    private static List<BlockPos> collectNearbyContainerPositions(ServerLevel level, BlockPos centerPos) {
        if (level == null || centerPos == null) {
            return Collections.emptyList();
        }

        ServerLevel safeLevel = Objects.requireNonNull(level);
        BlockPos safeCenterPos = Objects.requireNonNull(centerPos);
        List<BlockPos> positions = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS_XZ; dx <= SEARCH_RADIUS_XZ; dx++) {
            for (int dy = -SEARCH_RADIUS_Y; dy <= SEARCH_RADIUS_Y; dy++) {
                for (int dz = -SEARCH_RADIUS_XZ; dz <= SEARCH_RADIUS_XZ; dz++) {
                    BlockPos checkPos = Objects.requireNonNull(safeCenterPos.offset(dx, dy, dz));
                    boolean loaded = safeLevel.isLoaded(checkPos);
                    if (!loaded) {
                        continue;
                    }
                    boolean container = ContainerUtils.isContainer(safeLevel, checkPos);
                    if (!container) {
                        continue;
                    }
                    positions.add(checkPos);
                }
            }
        }
        return positions;
    }

    private static final class ContainerSnapshot {
        private final List<ItemStack> slots;

        private ContainerSnapshot(List<ItemStack> slots) {
            this.slots = slots;
        }

        private static ContainerSnapshot fromBlockEntity(BlockEntity blockEntity) {
            BlockEntity safeBlockEntity = Objects.requireNonNull(blockEntity);
            if (safeBlockEntity instanceof Container container) {
                List<ItemStack> slots = new ArrayList<>(container.getContainerSize());
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    slots.add(stack == null ? ItemStack.EMPTY : stack.copy());
                }
                return new ContainerSnapshot(slots);
            }

            Optional<IItemHandler> capability = safeBlockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
            IItemHandler handler = capability.orElse(null);
            if (handler != null) {
                List<ItemStack> slots = new ArrayList<>(handler.getSlots());
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack stack = handler.getStackInSlot(i);
                    slots.add(stack == null ? ItemStack.EMPTY : stack.copy());
                }
                return new ContainerSnapshot(slots);
            }

            return null;
        }

        private int mergeIntoExistingStacks(ItemStack template, int remaining) {
            for (int i = 0; i < slots.size() && remaining > 0; i++) {
                ItemStack existing = slots.get(i);
                if (existing.isEmpty() || !ItemHandlerHelper.canItemStacksStack(existing, template)) {
                    continue;
                }

                int space = Math.max(0, existing.getMaxStackSize() - existing.getCount());
                if (space <= 0) {
                    continue;
                }

                int toAdd = Math.min(space, remaining);
                existing.grow(toAdd);
                remaining -= toAdd;
            }
            return remaining;
        }

        private int fillEmptySlots(ItemStack template, int remaining) {
            for (int i = 0; i < slots.size() && remaining > 0; i++) {
                ItemStack existing = slots.get(i);
                if (!existing.isEmpty()) {
                    continue;
                }

                int toAdd = Math.min(template.getMaxStackSize(), remaining);
                ItemStack newStack = template.copy();
                newStack.setCount(toAdd);
                slots.set(i, newStack);
                remaining -= toAdd;
            }
            return remaining;
        }
    }
}
