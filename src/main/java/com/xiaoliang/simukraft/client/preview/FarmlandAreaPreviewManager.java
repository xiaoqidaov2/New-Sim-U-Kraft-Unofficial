package com.xiaoliang.simukraft.client.preview;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 农田区域预览管理器
 * 用于在玩家选择农田区域大小时实时预览耕种范围
 * 预览区域根据玩家朝向动态计算，与实际的耕地放置逻辑保持一致
 */
@SuppressWarnings("null")
public class FarmlandAreaPreviewManager {
    private static final List<SchematicBlockData> previewBlocks = new ArrayList<>();
    private static BlockPos farmlandBoxPos = BlockPos.ZERO;
    private static int previewAreaSize = 0;
    private static Direction playerFacing = Direction.NORTH; // 玩家朝向
    private static FarmlandPlot previewPlot = null;
    private static boolean isPreviewActive = false;

    /**
     * 开始预览农田区域
     * @param boxPos 农田盒位置
     * @param areaSize 区域大小（边长）
     * @param facing 玩家朝向
     */
    public static void startPreview(BlockPos boxPos, int areaSize, Direction facing) {
        farmlandBoxPos = boxPos;
        previewAreaSize = areaSize;
        playerFacing = facing != null ? facing : Direction.NORTH;
        previewPlot = null;
        isPreviewActive = true;
        loadPreviewBlocks();
        Simukraft.LOGGER.info(Component.translatable("message.preview.farmland.start", boxPos, areaSize, areaSize, playerFacing).getString());
    }

    /**
     * 开始预览农田区域（使用默认朝向）
     * @param boxPos 农田盒位置
     * @param areaSize 区域大小（边长）
     */
    public static void startPreview(BlockPos boxPos, int areaSize) {
        startPreview(boxPos, areaSize, Direction.NORTH);
    }

    public static void startPreview(BlockPos boxPos, FarmlandPlot plot) {
        farmlandBoxPos = boxPos;
        previewPlot = plot;
        previewAreaSize = plot != null ? Math.max(plot.widthX(), plot.depthZ()) : 0;
        isPreviewActive = plot != null;
        loadPreviewBlocks();
    }

    /**
     * 更新预览区域大小
     * @param areaSize 新的区域大小
     */
    public static void updatePreviewSize(int areaSize) {
        if (!isPreviewActive || previewAreaSize == areaSize) return;
        previewAreaSize = areaSize;
        loadPreviewBlocks();
        Simukraft.LOGGER.info(Component.translatable("message.preview.farmland.update_size", areaSize, areaSize).getString());
    }

    /**
     * 更新玩家朝向
     * @param facing 新的朝向
     */
    public static void updateFacing(Direction facing) {
        if (!isPreviewActive || playerFacing == facing) return;
        playerFacing = facing != null ? facing : Direction.NORTH;
        loadPreviewBlocks();
        Simukraft.LOGGER.info(Component.translatable("message.preview.farmland.update_facing", facing).getString());
    }

    /**
     * 停止预览
     */
    public static void stopPreview() {
        isPreviewActive = false;
        previewBlocks.clear();
        previewAreaSize = 0;
        previewPlot = null;
        playerFacing = Direction.NORTH;
        Simukraft.LOGGER.info(Component.translatable("message.preview.farmland.stop").getString());
    }

    /**
     * 获取左方向（相对于玩家朝向）
     */
    private static Direction getLeftDirection(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> Direction.WEST;
        };
    }

    /**
     * 加载预览方块
     * 根据玩家朝向计算实际的耕地放置位置
     */
    private static void loadPreviewBlocks() {
        previewBlocks.clear();

        if (previewPlot != null) {
            loadPlotPreviewBlocks();
            return;
        }

        if (previewAreaSize <= 0 || farmlandBoxPos == null) return;

        // 计算起始位置：农田盒前方一格的地面（玩家面朝的方向）
        BlockPos startPos = farmlandBoxPos.relative(playerFacing).below();
        Direction leftDirection = getLeftDirection(playerFacing);

        // 使用玻璃方块作为边界标记
        BlockState farmlandBlock = Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
        BlockState startBlock = Blocks.GREEN_STAINED_GLASS.defaultBlockState(); // 起始位置用绿色标记
        BlockState cornerBlock = Blocks.RED_STAINED_GLASS.defaultBlockState();

        // 根据实际的耕地放置逻辑计算所有位置
        for (int row = 0; row < previewAreaSize; row++) {
            for (int col = 0; col < previewAreaSize; col++) {
                // 计算当前位置：从起始位置开始，向左移动col格，向前移动row格
                BlockPos currentPos = startPos
                    .relative(leftDirection, col)  // 向左移动
                    .relative(playerFacing, row);  // 向前移动（玩家面朝的方向）

                // 跳过农田盒位置
                if (currentPos.equals(farmlandBoxPos) || currentPos.equals(farmlandBoxPos.below())) {
                    continue;
                }

                // 确定方块类型
                BlockState state;
                boolean isCorner = (row == 0 && col == 0) || 
                                  (row == 0 && col == previewAreaSize - 1) || 
                                  (row == previewAreaSize - 1 && col == 0) || 
                                  (row == previewAreaSize - 1 && col == previewAreaSize - 1);
                boolean isStart = (row == 0 && col == 0);

                if (isStart) {
                    state = startBlock; // 起始位置用绿色
                } else if (isCorner) {
                    state = cornerBlock; // 角落用红色
                } else if (row == 0 || row == previewAreaSize - 1 || col == 0 || col == previewAreaSize - 1) {
                    state = farmlandBlock; // 边界用黄色
                } else {
                    // 内部区域也显示，但使用更透明的方块
                    state = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
                }

                previewBlocks.add(new SchematicBlockData(currentPos, state, 15728880, true));
            }
        }

        Simukraft.LOGGER.info(Component.translatable("message.preview.farmland.loaded", previewBlocks.size(), playerFacing).getString());
    }

    private static void loadPlotPreviewBlocks() {
        if (previewPlot == null) {
            return;
        }

        BlockState farmlandBlock = Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
        BlockState cornerBlock = Blocks.RED_STAINED_GLASS.defaultBlockState();
        BlockState insideBlock = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
        BlockPos min = previewPlot.minPos();
        BlockPos max = previewPlot.maxPos();

        previewPlot.forEach(pos -> {
            boolean corner = (pos.getX() == min.getX() || pos.getX() == max.getX())
                    && (pos.getY() == min.getY() || pos.getY() == max.getY())
                    && (pos.getZ() == min.getZ() || pos.getZ() == max.getZ());
            boolean edge = pos.getX() == min.getX() || pos.getX() == max.getX()
                    || pos.getY() == min.getY() || pos.getY() == max.getY()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ();
            BlockState state = corner ? cornerBlock : edge ? farmlandBlock : insideBlock;
            previewBlocks.add(new SchematicBlockData(pos, state, 15728880, true));
        });
    }

    /**
     * 获取当前预览的方块列表
     */
    public static List<SchematicBlockData> getPreviewBlocks() {
        return previewBlocks;
    }

    /**
     * 检查预览是否激活
     */
    public static boolean isPreviewActive() {
        return isPreviewActive;
    }

    /**
     * 获取当前预览的区域大小
     */
    public static int getPreviewAreaSize() {
        return previewAreaSize;
    }

    /**
     * 获取农田盒位置
     */
    public static BlockPos getFarmlandBoxPos() {
        return farmlandBoxPos;
    }

    /**
     * 获取当前朝向
     */
    public static Direction getPlayerFacing() {
        return playerFacing;
    }
}
