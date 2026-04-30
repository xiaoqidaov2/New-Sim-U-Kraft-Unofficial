package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * GUI缩放管理类
 * 统一管理界面缩放，提供简洁的API
 */
public class GuiScaleManager {

    private static int originalScale = -1;
    private static final int DEFAULT_SCREEN_MARGIN = 16;

    /**
     * 从options.txt读取原始缩放值
     */
    public static int readOriginalScale() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return 2;
        }
        return minecraft.options.guiScale().get();
    }

    /**
     * 应用3x缩放，保存原始值
     */
    public static boolean apply3x() {
        // simukraft: 每次应用3x时都确保原始值已保存
        // 如果originalScale已经设置，保持原值；否则读取当前值
        if (originalScale < 0) {
            originalScale = readOriginalScale();
        }
        return setScaleIfNeeded(3);
    }

    /**
     * 优先使用 3x 缩放；若界面在当前分辨率下放不下，则自动降为 2x/1x。
     */
    public static boolean applyBestFitScale(int contentWidth, int contentHeight) {
        return applyBestFitScale(3, contentWidth, contentHeight, DEFAULT_SCREEN_MARGIN);
    }

    /**
     * 根据目标界面尺寸选择能完整显示的最大 GUI 缩放。
     */
    public static boolean applyBestFitScale(int preferredScale, int contentWidth, int contentHeight, int margin) {
        if (originalScale < 0) {
            originalScale = readOriginalScale();
        }
        // 直接按内容尺寸反推可用 guiScale，避免固定 3x 时界面超出屏幕。
        return setScaleIfNeeded(chooseBestScale(preferredScale, contentWidth, contentHeight, margin));
    }

    /**
     * 恢复原始缩放
     * 注意：不重置originalScale，以便嵌套界面返回时能保持原始值
     */
    public static boolean restore() {
        if (originalScale >= 0) {
            return setScaleIfNeeded(originalScale);
        }
        return false;
    }

    /**
     * 强制恢复原始缩放并重置状态
     * 用于最终关闭时清理状态
     */
    public static boolean forceRestore() {
        boolean changed = false;
        if (originalScale >= 0) {
            changed = setScaleIfNeeded(originalScale);
        }
        originalScale = -1;
        return changed;
    }

    /**
     * 重置状态（用于返回到非3x界面时）
     */
    public static void reset() {
        originalScale = -1;
    }

    /**
     * 设置指定缩放值
     */
    public static void setScale(int scale) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        Window window = mc.getWindow();
        if (window == null) {
            return;
        }
        int optionScale = Math.max(0, scale);
        mc.options.guiScale().set(optionScale);
        window.setGuiScale(window.calculateScale(optionScale, mc.isEnforceUnicode()));
    }

    public static int getCurrentScale() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return 2;
        }
        return Math.max(0, minecraft.options.guiScale().get());
    }

    public static boolean setScaleIfNeeded(int scale) {
        int optionScale = Math.max(0, scale);
        if (getCurrentScale() == optionScale) {
            return false;
        }
        setScale(optionScale);
        return true;
    }

    private static int chooseBestScale(int preferredScale, int contentWidth, int contentHeight, int margin) {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        int windowWidth = Math.max(1, window.getWidth());
        int windowHeight = Math.max(1, window.getHeight());
        int requiredWidth = Math.max(1, contentWidth + margin * 2);
        int requiredHeight = Math.max(1, contentHeight + margin * 2);

        // 从期望缩放开始向下尝试，保留"能完整放下界面"的最大缩放级别。
        for (int scale = Math.max(1, preferredScale); scale >= 1; scale--) {
            int scaledWidth = Math.max(1, windowWidth / scale);
            int scaledHeight = Math.max(1, windowHeight / scale);
            if (scaledWidth >= requiredWidth && scaledHeight >= requiredHeight) {
                return scale;
            }
        }
        return 1;
    }

    /**
     * 计算最佳缩放值（不应用）
     * 用于需要在静态方法中获取缩放值的场景
     */
    public static int calculateBestScale(int contentWidth, int contentHeight) {
        return chooseBestScale(3, contentWidth, contentHeight, DEFAULT_SCREEN_MARGIN);
    }

    /**
     * 处理ESC键返回，确保恢复缩放
     * 在Screen的keyPressed方法中调用
     *
     * @param keyCode   按键代码
     * @param onClose   关闭界面的回调（调用onClose方法）
     * @return          如果处理了ESC键返回true，否则返回false
     */
    public static boolean handleEscKey(int keyCode, Runnable onClose) {
        if (keyCode == 256) { // ESC键
            onClose.run();
            return true;
        }
        return false;
    }
}
