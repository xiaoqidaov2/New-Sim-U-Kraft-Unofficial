package com.xiaoliang.simukraft.farmland;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 农田盒区域
 * 用于表示农田盒的区域范围，包括最小和最大位置
 */
@SuppressWarnings("null")
public record FarmlandPlot(BlockPos minPos, BlockPos maxPos) {
    public FarmlandPlot {
        Objects.requireNonNull(minPos);
        Objects.requireNonNull(maxPos);
        int minX = Math.min(minPos.getX(), maxPos.getX());
        int minY = Math.min(minPos.getY(), maxPos.getY());
        int minZ = Math.min(minPos.getZ(), maxPos.getZ());
        int maxX = Math.max(minPos.getX(), maxPos.getX());
        int maxY = Math.max(minPos.getY(), maxPos.getY());
        int maxZ = Math.max(minPos.getZ(), maxPos.getZ());
        minPos = new BlockPos(minX, minY, minZ);
        maxPos = new BlockPos(maxX, maxY, maxZ);
    }

    public static FarmlandPlot fromCorners(BlockPos first, BlockPos second) {
        return new FarmlandPlot(first, second);
    }

    public static FarmlandPlot fromLegacy(BlockPos farmlandBoxPos, Direction facing, int areaSize) {
        Direction safeFacing = facing == null ? Direction.NORTH : facing;
        int safeSize = Math.max(1, areaSize);
        BlockPos startPos = Objects.requireNonNull(farmlandBoxPos).relative(safeFacing).below();
        Direction leftDir = safeFacing.getCounterClockWise();
        BlockPos endPos = startPos.relative(leftDir, safeSize - 1).relative(safeFacing, safeSize - 1);
        return new FarmlandPlot(startPos, endPos);
    }

    public int widthX() {
        return maxPos.getX() - minPos.getX() + 1;
    }

    public int heightY() {
        return maxPos.getY() - minPos.getY() + 1;
    }

    public int depthZ() {
        return maxPos.getZ() - minPos.getZ() + 1;
    }

    public int blockCount() {
        return widthX() * heightY() * depthZ();
    }

    public boolean intersects(FarmlandPlot other) {
        if (other == null) {
            return false;
        }
        return minPos.getX() <= other.maxPos.getX() && maxPos.getX() >= other.minPos.getX()
                && minPos.getZ() <= other.maxPos.getZ() && maxPos.getZ() >= other.minPos.getZ()
                && minPos.getY() <= other.maxPos.getY() && maxPos.getY() >= other.minPos.getY();
    }

    public void forEach(Consumer<BlockPos> consumer) {
        Objects.requireNonNull(consumer);
        for (int x = minPos.getX(); x <= maxPos.getX(); x++) {
            for (int y = minPos.getY(); y <= maxPos.getY(); y++) {
                for (int z = minPos.getZ(); z <= maxPos.getZ(); z++) {
                    consumer.accept(new BlockPos(x, y, z));
                }
            }
        }
    }

    public List<BlockPos> positions() {
        List<BlockPos> positions = new ArrayList<>();
        forEach(positions::add);
        return positions;
    }

    public boolean shouldPlantAt(BlockPos pos, CropLayoutType layoutType) {
        if (layoutType != CropLayoutType.CHECKERBOARD) {
            return true;
        }
        int dx = Math.abs(pos.getX() - minPos.getX());
        int dz = Math.abs(pos.getZ() - minPos.getZ());
        return (dx + dz) % 2 == 0;
    }

    public int countPlantingSlots(CropLayoutType layoutType) {
        int[] count = new int[]{0};
        forEach(pos -> {
            if (shouldPlantAt(pos, layoutType)) {
                count[0]++;
            }
        });
        return count[0];
    }
}
