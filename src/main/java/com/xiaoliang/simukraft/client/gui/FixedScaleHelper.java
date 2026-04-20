package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * 固定缩放帮助类
 * 用于让界面以固定缩放倍数渲染，无视原版界面尺寸设置
 * 
 * 使用方法：
 * 1. 在Screen的构造函数或创建UI前调用 applyFixedScale()
 * 2. 在render()中调用 applyFixedScale() 保持缩放
 * 3. 在onClose()中调用 restoreOriginalScale() 恢复
 */
public class FixedScaleHelper {

    // 目标固定缩放倍数
    public static final float TARGET_SCALE = 3.0f;

    // 保存的原始缩放值
    private static float originalScale = -1;

    /**
     * 应用固定缩放
     * 在创建UI前调用，确保布局计算使用正确的缩放
     */
    public static void applyFixedScale() {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();

        // 首次调用时保存原始缩放
        if (originalScale < 0) {
            originalScale = mc.options.guiScale().get().floatValue();
        }

        // 设置目标缩放
        mc.options.guiScale().set(Math.round(TARGET_SCALE));
        window.setGuiScale(TARGET_SCALE);
    }

    /**
     * 恢复原始缩放
     * 在关闭界面时调用，恢复用户的界面尺寸设置
     */
    public static void restoreOriginalScale() {
        if (originalScale > 0) {
            Minecraft mc = Minecraft.getInstance();
            Window window = mc.getWindow();

            int scale = Math.round(originalScale);
            mc.options.guiScale().set(scale);
            window.setGuiScale(originalScale);

            // 重置，下次打开时重新记录
            originalScale = -1;
        }
    }

    /**
     * 获取当前是否已应用固定缩放
     */
    public static boolean isFixedScaleApplied() {
        return originalScale > 0;
    }

    /**
     * 获取原始缩放值
     */
    public static float getOriginalScale() {
        return originalScale;
    }
}
