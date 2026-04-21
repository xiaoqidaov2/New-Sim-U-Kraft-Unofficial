package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.client.gui.ldlib.LDLibMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * simukraft: 客户端配置界面 - LDLib菜单版本
 * 继承LDLibMenuScreen，被其他模组识别为普通菜单而非容器
 * 支持主菜单使用，无需玩家实体
 */
@OnlyIn(Dist.CLIENT)
public class ModConfigScreenLDLib extends LDLibMenuScreen {

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

    private static ModConfigScreenLDLib currentInstance;

    public ModConfigScreenLDLib(Screen parent) {
        super(Component.literal("客户端配置"), parent);
        currentInstance = this;
    }

    @Override
    protected int getUIWidth() {
        return WINDOW_WIDTH;
    }

    @Override
    protected int getUIHeight() {
        return WINDOW_HEIGHT;
    }

    @Override
    protected ModularUI createModularUI() {
        return new ConfigUIHolder().createModularUI();
    }

    @Override
    public void onClose() {
        currentInstance = null;
        super.onClose();
    }

    private void openHUDEditor() {
        Minecraft.getInstance().setScreen(new HUDPositionEditorScreen(this));
    }

    private void closeScreen() {
        onClose();
    }

    private static class ConfigUIHolder implements IUIHolder {

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
                    "调整HUD位置", clickData -> {
                        if (currentInstance != null) {
                            currentInstance.openHUDEditor();
                        }
                    }));

            parent.addWidget(createButton(centerX, startY + BUTTON_HEIGHT + 20, BUTTON_WIDTH, BUTTON_HEIGHT,
                    "返回", clickData -> {
                        if (currentInstance != null) {
                            currentInstance.closeScreen();
                        }
                    }));
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

        @Override public boolean isInvalid() { return false; }
        @Override public boolean isRemote() { return true; }
        @Override public void markAsDirty() {}
    }
}
