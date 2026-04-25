package com.xiaoliang.simukraft.client.map;

import java.util.Arrays;

/**
 * 单个区域（512x512 方块，即 32x32 区块）的地图数据。
 * 参考 FTB Chunks 的 MapRegionData，但简化为独立实现。
 *
 * 数据布局:
 *  - height[512*512]:    每个方块位置最高非空气方块高度 (short)
 *  - color[512*512]:     每个方块位置对应的 ARGB 颜色 (int)
 *  - flags[512*512]:     标志位：bit0=水面, bit1-4=光照等级
 */
public class SimuMapRegionData {

    public static final int SIZE = 512;
    public static final int AREA = SIZE * SIZE;
    public static final short HEIGHT_UNKNOWN = Short.MIN_VALUE;

    /** 最高非空气方块高度 */
    public final short[] height = new short[AREA];

    /** 每个方块位置的 ARGB 渲染颜色 */
    public final int[] color = new int[AREA];

    /** 标志位: bit0=水面, bit1-4=光照等级(0-15) */
    public final short[] flags = new short[AREA];

    /** 此数据是否已被修改（需要重新渲染） */
    private boolean dirty = true;

    /** 此数据对应的区域坐标 */
    public final int regionX;
    public final int regionZ;

    public SimuMapRegionData(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        Arrays.fill(height, HEIGHT_UNKNOWN);
    }

    /** 获取指定方块位置的数组索引 */
    public static int index(int localX, int localZ) {
        return (localX & 0x1FF) + (localZ & 0x1FF) * SIZE;
    }

    /** 设置指定位置的数据 */
    public void setData(int localX, int localZ, short h, int argbColor, boolean water, int light) {
        int idx = index(localX, localZ);
        short f = (short) ((water ? 1 : 0) | ((light & 0xF) << 1));
        if (height[idx] == h && color[idx] == argbColor && flags[idx] == f) {
            return;
        }
        height[idx] = h;
        color[idx] = argbColor;
        flags[idx] = f;
        dirty = true;
    }

    /** 获取指定位置的高度 */
    public short getHeight(int localX, int localZ) {
        return height[index(localX, localZ)];
    }

    /** 获取指定位置的颜色 */
    public int getColor(int localX, int localZ) {
        return color[index(localX, localZ)];
    }

    /** 检查指定位置是否为水面 */
    public boolean isWater(int localX, int localZ) {
        return (flags[index(localX, localZ)] & 1) != 0;
    }

    /** 获取指定位置的光照等级 */
    public int getLight(int localX, int localZ) {
        return (flags[index(localX, localZ)] >> 1) & 0xF;
    }

    /** 是否有未渲染的修改 */
    public boolean isDirty() {
        return dirty;
    }

    /** 标记为已渲染 */
    public void clearDirty() {
        dirty = false;
    }

    /** 标记为需要重新渲染 */
    public void markDirty() {
        dirty = true;
    }

    /** 检查该区域是否完全空白 */
    public boolean isEmpty() {
        for (short h : height) {
            if (h != HEIGHT_UNKNOWN) return false;
        }
        return true;
    }

    /** 获取此区域中第一个世界方块的 X 坐标 */
    public int worldBlockX() {
        return regionX * SIZE;
    }

    /** 获取此区域中第一个世界方块的 Z 坐标 */
    public int worldBlockZ() {
        return regionZ * SIZE;
    }
}
