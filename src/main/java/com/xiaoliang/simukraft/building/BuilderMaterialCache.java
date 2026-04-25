package com.xiaoliang.simukraft.building;

import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.utils.MaterialManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 建筑材料缓存。
 * 低频扫描容器内容，建造时只按缓存命中的槽位定点扣料。
 */
@SuppressWarnings("null")
public class BuilderMaterialCache {
    private static final long CONTAINER_DISCOVERY_INTERVAL_TICKS = 20L;
    private static final long INVENTORY_REFRESH_INTERVAL_TICKS = 20L;
    private static final long FALLBACK_CHECK_INTERVAL_TICKS = 60L;

    @Nonnull
    private final BlockPos buildBoxPos;
    @Nonnull
    private final List<BlockPos> cachedContainerPositions = new ArrayList<>();
    @Nonnull
    private final Map<String, Integer> cachedItemTotals = new HashMap<>();
    @Nonnull
    private final Map<String, ArrayDeque<CachedSlotRef>> cachedItemSlots = new HashMap<>();
    @Nonnull
    private final Map<BlockPos, ContainerSignature> containerSignatures = new HashMap<>();

    private long lastContainerDiscoveryTick = Long.MIN_VALUE;
    private long lastInventoryRefreshTick = Long.MIN_VALUE;
    private long lastFallbackCheckTick = Long.MIN_VALUE;
    private boolean inventoryDirty = true;

    public BuilderMaterialCache(@Nonnull BlockPos buildBoxPos) {
        this.buildBoxPos = Objects.requireNonNull(buildBoxPos);
    }

    public boolean tryConsume(@Nonnull ServerLevel level, @Nonnull BlockState state) {
        refreshContainerPositionsIfNeeded(level);
        checkFallbackIfNeeded(level);
        refreshInventoryIfNeeded(level, false);

        ConsumeResult firstAttempt = consumeFromCache(level, state);
        if (firstAttempt == ConsumeResult.CONSUMED) {
            return true;
        }

        if (firstAttempt == ConsumeResult.DESYNCED || shouldForceRefresh(level)) {
            refreshInventoryIfNeeded(level, true);
            return consumeFromCache(level, state) == ConsumeResult.CONSUMED;
        }

        return false;
    }

    public void markDirty() {
        inventoryDirty = true;
    }

    public boolean tracksContainer(@Nonnull ServerLevel level, @Nonnull BlockPos containerPos) {
        refreshContainerPositionsIfNeeded(level);
        return cachedContainerPositions.contains(Objects.requireNonNull(containerPos.immutable()));
    }

    private boolean shouldForceRefresh(@Nonnull ServerLevel level) {
        return inventoryDirty || level.getGameTime() - lastInventoryRefreshTick >= INVENTORY_REFRESH_INTERVAL_TICKS;
    }

    private void refreshContainerPositionsIfNeeded(@Nonnull ServerLevel level) {
        long gameTime = level.getGameTime();
        if (!cachedContainerPositions.isEmpty() &&
            gameTime - lastContainerDiscoveryTick < CONTAINER_DISCOVERY_INTERVAL_TICKS) {
            return;
        }

        List<BlockPos> refreshedPositions = discoverContainers(level);
        if (!refreshedPositions.equals(cachedContainerPositions)) {
            cachedContainerPositions.clear();
            cachedContainerPositions.addAll(refreshedPositions);
            containerSignatures.keySet().retainAll(refreshedPositions);
            inventoryDirty = true;
        }
        lastContainerDiscoveryTick = gameTime;
    }

    private void refreshInventoryIfNeeded(@Nonnull ServerLevel level, boolean forceRefresh) {
        long gameTime = level.getGameTime();
        if (!forceRefresh && !inventoryDirty &&
            gameTime - lastInventoryRefreshTick < INVENTORY_REFRESH_INTERVAL_TICKS) {
            return;
        }

        rebuildInventoryCache(level);
        lastInventoryRefreshTick = gameTime;
        inventoryDirty = false;
    }

    @Nonnull
    private List<BlockPos> discoverContainers(@Nonnull ServerLevel level) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos checkPos = Objects.requireNonNull(buildBoxPos.relative(direction));
            if (!level.isLoaded(checkPos) || !ContainerUtils.isContainer(level, checkPos)) {
                continue;
            }
            positions.add(Objects.requireNonNull(checkPos.immutable()));
            BlockPos otherHalf = getOtherChestHalf(level, checkPos);
            if (otherHalf != null) {
                positions.add(Objects.requireNonNull(otherHalf.immutable()));
            }
        }
        return Objects.requireNonNull(List.copyOf(positions));
    }

    private void rebuildInventoryCache(@Nonnull ServerLevel level) {
        cachedItemTotals.clear();
        cachedItemSlots.clear();
        containerSignatures.clear();

        for (BlockPos containerPos : cachedContainerPositions) {
            if (!level.isLoaded(containerPos) || !ContainerUtils.isContainer(level, containerPos)) {
                inventoryDirty = true;
                continue;
            }

            List<ContainerUtils.ContainerSlotSnapshot> slotSnapshots =
                ContainerUtils.snapshotContainerSlots(level, containerPos);
            containerSignatures.put(Objects.requireNonNull(containerPos.immutable()), buildSignature(slotSnapshots));
            for (ContainerUtils.ContainerSlotSnapshot slotSnapshot : slotSnapshots) {
                String itemId = MaterialManager.getItemId(slotSnapshot.stack());
                if (itemId.isEmpty() || slotSnapshot.stack().isEmpty()) {
                    continue;
                }

                cachedItemTotals.merge(itemId, slotSnapshot.stack().getCount(), Integer::sum);
                cachedItemSlots
                    .computeIfAbsent(itemId, ignored -> new ArrayDeque<>())
                    .addLast(new CachedSlotRef(
                        Objects.requireNonNull(containerPos.immutable()),
                        slotSnapshot.slot(),
                        slotSnapshot.usesItemHandler(),
                        itemId,
                        slotSnapshot.stack().getCount()
                    ));
            }
        }
    }

    private void checkFallbackIfNeeded(@Nonnull ServerLevel level) {
        long gameTime = level.getGameTime();
        if (gameTime - lastFallbackCheckTick < FALLBACK_CHECK_INTERVAL_TICKS) {
            return;
        }

        for (BlockPos containerPos : cachedContainerPositions) {
            if (!level.isLoaded(containerPos) || !ContainerUtils.isContainer(level, containerPos)) {
                inventoryDirty = true;
                continue;
            }

            ContainerSignature latestSignature = generateContainerSignature(level, containerPos);
            ContainerSignature previousSignature = containerSignatures.get(containerPos);
            if (!latestSignature.equals(previousSignature)) {
                containerSignatures.put(Objects.requireNonNull(containerPos.immutable()), latestSignature);
                inventoryDirty = true;
            }
        }

        lastFallbackCheckTick = gameTime;
    }

    @Nonnull
    private ContainerSignature generateContainerSignature(@Nonnull ServerLevel level, @Nonnull BlockPos containerPos) {
        return buildSignature(ContainerUtils.snapshotContainerSlots(level, containerPos));
    }

    @Nonnull
    private ContainerSignature buildSignature(@Nonnull List<ContainerUtils.ContainerSlotSnapshot> slotSnapshots) {
        int totalItems = 0;
        int nonEmptySlots = 0;
        for (ContainerUtils.ContainerSlotSnapshot slotSnapshot : slotSnapshots) {
            if (slotSnapshot.stack().isEmpty()) {
                continue;
            }
            totalItems += slotSnapshot.stack().getCount();
            nonEmptySlots++;
        }
        return new ContainerSignature(totalItems, nonEmptySlots);
    }

    @Nonnull
    private ConsumeResult consumeFromCache(@Nonnull ServerLevel level, @Nonnull BlockState state) {
        Set<String> acceptedItemIds = MaterialManager.getAcceptedItemIds(state);
        if (acceptedItemIds.isEmpty()) {
            return ConsumeResult.MISSING;
        }

        int cachedAmount = 0;
        for (String itemId : acceptedItemIds) {
            cachedAmount += cachedItemTotals.getOrDefault(itemId, 0);
        }
        if (cachedAmount <= 0) {
            return ConsumeResult.MISSING;
        }

        for (String itemId : acceptedItemIds) {
            ArrayDeque<CachedSlotRef> slotRefs = cachedItemSlots.get(itemId);
            if (slotRefs == null || slotRefs.isEmpty()) {
                continue;
            }

            while (!slotRefs.isEmpty()) {
                CachedSlotRef slotRef = slotRefs.peekFirst();
                if (slotRef.remainingCount <= 0) {
                    slotRefs.removeFirst();
                    continue;
                }

                if (!level.isLoaded(slotRef.containerPos) || !ContainerUtils.isContainer(level, slotRef.containerPos)) {
                    inventoryDirty = true;
                    return ConsumeResult.DESYNCED;
                }

                boolean consumed = ContainerUtils.consumeSingleItemAtSlot(
                    level,
                    slotRef.containerPos,
                    slotRef.slot,
                    slotRef.usesItemHandler,
                    stack -> itemId.equals(MaterialManager.getItemId(stack))
                        && MaterialManager.canUseItemForBlock(stack, state)
                );
                if (!consumed) {
                    inventoryDirty = true;
                    return ConsumeResult.DESYNCED;
                }

                slotRef.remainingCount--;
                decrementTotal(itemId);
                if (slotRef.remainingCount <= 0) {
                    slotRefs.removeFirst();
                }
                return ConsumeResult.CONSUMED;
            }
        }

        return ConsumeResult.DESYNCED;
    }

    private void decrementTotal(@Nonnull String itemId) {
        Integer current = cachedItemTotals.get(itemId);
        if (current == null) {
            return;
        }
        if (current <= 1) {
            cachedItemTotals.remove(itemId);
        } else {
            cachedItemTotals.put(itemId, current - 1);
        }
    }

    @Nullable
    private static BlockPos getOtherChestHalf(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE)) {
            return null;
        }

        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = Objects.requireNonNull(pos.relative(direction));
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof ChestBlock && neighborState.hasProperty(ChestBlock.TYPE)) {
                return neighborPos;
            }
        }
        return null;
    }

    private enum ConsumeResult {
        CONSUMED,
        MISSING,
        DESYNCED
    }

    private static final class CachedSlotRef {
        @Nonnull
        private final BlockPos containerPos;
        private final int slot;
        private final boolean usesItemHandler;
        @Nonnull
        @SuppressWarnings("unused")
        private final String itemId;
        private int remainingCount;

        private CachedSlotRef(@Nonnull BlockPos containerPos, int slot, boolean usesItemHandler,
                              @Nonnull String itemId, int remainingCount) {
            this.containerPos = Objects.requireNonNull(containerPos);
            this.slot = slot;
            this.usesItemHandler = usesItemHandler;
            this.itemId = Objects.requireNonNull(itemId);
            this.remainingCount = remainingCount;
        }
    }

    private static final class ContainerSignature {
        private final int totalItems;
        private final int nonEmptySlots;

        private ContainerSignature(int totalItems, int nonEmptySlots) {
            this.totalItems = totalItems;
            this.nonEmptySlots = nonEmptySlots;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ContainerSignature that)) {
                return false;
            }
            return totalItems == that.totalItems && nonEmptySlots == that.nonEmptySlots;
        }

        @Override
        public int hashCode() {
            return Objects.hash(totalItems, nonEmptySlots);
        }
    }
}
