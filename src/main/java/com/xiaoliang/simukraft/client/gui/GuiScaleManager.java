package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * GUI缩放管理类
 * 统一管理界面缩放，提供简洁的API
 */
public class GuiScaleManager {

    private static float originalScale = -1;

    /**
     * 从options.txt读取原始缩放值
     */
    public static int readOriginalScale() {
        try {
            File optionsFile = new File(Minecraft.getInstance().gameDirectory, "options.txt");
            if (optionsFile.exists()) {
                String content = new String(Files.readAllBytes(optionsFile.toPath()));
                for (String line : content.split("\n")) {
                    if (line.startsWith("guiScale:")) {
                        return Integer.parseInt(line.substring("guiScale:".length()).trim());
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            // 读取失败返回默认值
        }
        return 2;
    }

    /**
     * 应用3x缩放，保存原始值
     */
    public static void apply3x() {
        // simukraft: 每次应用3x时都确保原始值已保存
        // 如果originalScale已经设置，保持原值；否则读取当前值
        if (originalScale < 0) {
            originalScale = readOriginalScale();
        }
        setScale(3);
    }

    /**
     * 恢复原始缩放
     * 注意：不重置originalScale，以便嵌套界面返回时能保持原始值
     */
    public static void restore() {
        if (originalScale > 0) {
            setScale(Math.round(originalScale));
        }
    }

    /**
     * 强制恢复原始缩放并重置状态
     * 用于最终关闭时清理状态
     */
    public static void forceRestore() {
        if (originalScale > 0) {
            setScale(Math.round(originalScale));
        }
        originalScale = -1;
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
        Window window = mc.getWindow();
        mc.options.guiScale().set(scale);
        window.setGuiScale(scale);
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
