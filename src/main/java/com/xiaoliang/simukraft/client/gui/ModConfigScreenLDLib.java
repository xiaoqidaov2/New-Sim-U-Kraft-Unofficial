package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;

/**
 * 客户端配置界面 - LDLib版本
 * 简化为单个按钮入口，点击进入HUD位置调整界面
 * 使用固定3x缩放渲染
 */
@OnlyIn(Dist.CLIENT)
public class ModConfigScreenLDLib extends ModularUIGuiContainer {

    // 颜色定义
    private static final int COLOR_WINDOW_BG = 0xE6202020;
    private static final int COLOR_WINDOW_BORDER = 0xFF4A90A4;
    private static final int COLOR_HEADER_BG = 0xFF2D5A6B;
    private static final int COLOR_BUTTON_BG = 0xFF3A5A6A;
    private static final int COLOR_BUTTON_HOVER = 0xFF4A7A8A;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_NORMAL = 0xFFE0E0E0;

    // 布局常量
    private static final int WINDOW_WIDTH = 280;
    private static final int WINDOW_HEIGHT = 180;
    private static final int HEADER_HEIGHT = 36;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 28;

    private final Screen parent;
    private static ModConfigScreenLDLib currentInstance;

    public ModConfigScreenLDLib(Screen parent) {
        super(createHolderAndUI(parent), 0);
        this.parent = parent;
        currentInstance = this;
        // simukraft: 根据窗口尺寸选择能完整显示的最大缩放
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
    }

    private static ModularUI createHolderAndUI(Screen parent) {
        return new ConfigUIHolder().createModularUI();
    }

    @Override
    public void onClose() {
        // simukraft: 恢复原始缩放并重置状态（返回到非3x界面）
        GuiScaleManager.forceRestore();
        currentInstance = null;
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void init() {
        super.init();
        // simukraft: 初始化时重新应用可完整显示的最佳缩放
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // simukraft: 使用GuiScaleManager统一处理ESC键
        if (GuiScaleManager.handleEscKey(keyCode, this::onClose)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private static class ConfigUIHolder implements IUIHolder {

        public ConfigUIHolder() {
        }

        @Override
        public ModularUI createUI(Player entityPlayer) {
            return createModularUI();
        }

        public ModularUI createModularUI() {
            ModularUI modularUI = new ModularUI(new Size(WINDOW_WIDTH, WINDOW_HEIGHT), this, null);
            WidgetGroup rootGroup = new WidgetGroup();
            rootGroup.setSelfPosition(0, 0);
            rootGroup.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);

            WidgetGroup windowGroup = new WidgetGroup();
            windowGroup.setSelfPosition(0, 0);
            windowGroup.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
            windowGroup.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_WINDOW_BG).setRadius(10),
                    new ColorBorderTexture(2, COLOR_WINDOW_BORDER).setRadius(10)
            ));
            rootGroup.addWidget(windowGroup);

            createHeader(windowGroup);
            createButtons(windowGroup);

            modularUI.widget(rootGroup);
            modularUI.initWidgets();
            return modularUI;
        }

        private void createHeader(WidgetGroup parent) {
            WidgetGroup headerGroup = new WidgetGroup();
            headerGroup.setSelfPosition(2, 2);
            headerGroup.setSize(WINDOW_WIDTH - 4, HEADER_HEIGHT - 4);
            headerGroup.setBackground(new ColorRectTexture(COLOR_HEADER_BG).setRadius(8));

            TextTexture titleTexture = new TextTexture("客户端配置", COLOR_TEXT_TITLE);
            titleTexture.setType(TextTexture.TextType.NORMAL);
            headerGroup.addWidget(new ImageWidget(0, 10, WINDOW_WIDTH - 4, 16, titleTexture));
            parent.addWidget(headerGroup);
        }

        private void createButtons(WidgetGroup parent) {
            int centerX = (WINDOW_WIDTH - BUTTON_WIDTH) / 2;
            int startY = HEADER_HEIGHT + 30;

            parent.addWidget(createButton(centerX, startY, BUTTON_WIDTH, BUTTON_HEIGHT,
                    "调整HUD位置", clickData -> openHUDEditor()));

            parent.addWidget(createButton(centerX, startY + BUTTON_HEIGHT + 20, BUTTON_WIDTH, BUTTON_HEIGHT,
                    "返回", clickData -> closeScreen()));
        }

        private ButtonWidget createButton(int x, int y, int width, int height,
                                           String text,
                                           java.util.function.Consumer<ClickData> callback) {
            ButtonWidget button = new ButtonWidget();
            button.setSelfPosition(x, y);
            button.setSize(width, height);
            button.setOnPressCallback(callback);

            // 正常状态背景+文字
            TextTexture normalText = new TextTexture(text, COLOR_TEXT_NORMAL);
            normalText.setType(TextTexture.TextType.NORMAL);
            normalText.setWidth(width);
            button.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(4),
                    new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(4),
                    normalText
            ));

            // 悬停状态
            TextTexture hoverText = new TextTexture(text, COLOR_TEXT_TITLE);
            hoverText.setType(TextTexture.TextType.NORMAL);
            hoverText.setWidth(width);
            button.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(4),
                    new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(4),
                    hoverText
            ));
            return button;
        }

        private void openHUDEditor() {
            if (currentInstance != null) {
                Minecraft.getInstance().setScreen(new HUDPositionEditorScreen(currentInstance));
            }
        }

        private void closeScreen() {
            // simukraft: 触发onClose来恢复缩放
            if (currentInstance != null) {
                currentInstance.onClose();
            }
        }

        @Override public boolean isInvalid() { return false; }
        @Override public boolean isRemote() { return true; }
        @Override public void markAsDirty() {}
    }
}
