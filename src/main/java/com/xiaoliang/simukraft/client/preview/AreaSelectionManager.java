package com.xiaoliang.simukraft.client.preview;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 区域选择管理器
 * 用于管理规划区域的两点框选功能
 */
public class AreaSelectionManager {
    private static BlockPos point1 = null; // 左键点击的点（红色）
    private static BlockPos point2 = null; // 右键点击的点（黄色）
    private static boolean isSelecting = false;
    private static SelectionMode mode = SelectionMode.NONE;

    public enum SelectionMode {
        NONE,
        REPLACE,    // 替换方块
        FILL,       // 填充方块
        REMOVE,     // 拆除方块
        LOGISTICS,  // 物流系统选区（选箱子/木桶）
        FARMLAND    // 农田盒自由矩形选区
    }

    /**
     * 开始区域选择
     */
    public static void startSelection(SelectionMode selectionMode) {
        mode = selectionMode;
        isSelecting = true;
        point1 = null;
        point2 = null;
        Simukraft.LOGGER.info(Component.translatable("message.preview.area_selection.start", selectionMode).getString());
    }

    /**
     * 结束区域选择
     */
    public static void endSelection() {
        isSelecting = false;
        mode = SelectionMode.NONE;
        point1 = null;
        point2 = null;
        Simukraft.LOGGER.info(Component.translatable("message.preview.area_selection.end").getString());
    }

    /**
     * 设置点一（左键点击）
     */
    public static void setPoint1(BlockPos pos) {
        point1 = pos;
        Simukraft.LOGGER.info(Component.translatable("message.preview.area_selection.set_point1", pos).getString());
    }

    /**
     * 设置点二（右键点击）
     */
    public static void setPoint2(BlockPos pos) {
        point2 = pos;
        Simukraft.LOGGER.info(Component.translatable("message.preview.area_selection.set_point2", pos).getString());
    }

    /**
     * 获取点一
     */
    public static BlockPos getPoint1() {
        return point1;
    }

    /**
     * 获取点二
     */
    public static BlockPos getPoint2() {
        return point2;
    }

    /**
     * 是否正在选择
     */
    public static boolean isSelecting() {
        return isSelecting;
    }

    /**
     * 获取当前选择模式
     */
    public static SelectionMode getMode() {
        return mode;
    }

    /**
     * 是否两点都已设置
     */
    public static boolean hasBothPoints() {
        return point1 != null && point2 != null;
    }

    /**
     * 获取选择区域内的所有方块位置
     */
    public static List<BlockPos> getSelectedArea() {
        List<BlockPos> positions = new ArrayList<>();
        if (!hasBothPoints()) return positions;

        int minX = Math.min(point1.getX(), point2.getX());
        int maxX = Math.max(point1.getX(), point2.getX());
        int minY = Math.min(point1.getY(), point2.getY());
        int maxY = Math.max(point1.getY(), point2.getY());
        int minZ = Math.min(point1.getZ(), point2.getZ());
        int maxZ = Math.max(point1.getZ(), point2.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return positions;
    }

    /**
     * 获取预览方块列表
     * 点一：红色玻璃
     * 点二：黄色玻璃
     * 其他：灰色玻璃（仅显示边框）
     */
    public static List<SchematicBlockData> getPreviewBlocks() {
        List<SchematicBlockData> blocks = new ArrayList<>();

        if (!isSelecting) return blocks;

        // 点一：红色玻璃
        if (point1 != null) {
            blocks.add(new SchematicBlockData(point1, Blocks.RED_STAINED_GLASS.defaultBlockState(), 15728880, true));
        }

        // 点二：黄色玻璃
        if (point2 != null) {
            blocks.add(new SchematicBlockData(point2, Blocks.YELLOW_STAINED_GLASS.defaultBlockState(), 15728880, true));
        }

        // 如果有两点，显示边框（灰色玻璃）
        if (hasBothPoints()) {
            int minX = Math.min(point1.getX(), point2.getX());
            int maxX = Math.max(point1.getX(), point2.getX());
            int minY = Math.min(point1.getY(), point2.getY());
            int maxY = Math.max(point1.getY(), point2.getY());
            int minZ = Math.min(point1.getZ(), point2.getZ());
            int maxZ = Math.max(point1.getZ(), point2.getZ());

            // 只渲染边框，避免渲染太多方块
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        // 跳过点一和点二
                        if (pos.equals(point1) || pos.equals(point2)) continue;

                        // 只渲染边框
                        if (x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ) {
                            blocks.add(new SchematicBlockData(pos, Blocks.GRAY_STAINED_GLASS.defaultBlockState(), 15728880, true));
                        }
                    }
                }
            }
        }

        return blocks;
    }
}
