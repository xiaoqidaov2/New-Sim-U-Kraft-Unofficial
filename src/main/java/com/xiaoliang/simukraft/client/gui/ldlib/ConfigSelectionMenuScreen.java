package com.xiaoliang.simukraft.client.gui.ldlib;

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
import com.xiaoliang.simukraft.client.gui.ModConfigScreenLDLib;
import com.xiaoliang.simukraft.client.gui.ServerConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * simukraft: 配置选择界面 - 菜单版本
 * 使用LDLibMenuScreen基类，被其他模组识别为普通菜单而非容器
 */
@OnlyIn(Dist.CLIENT)
public class ConfigSelectionMenuScreen extends LDLibMenuScreen {

    private static final int COLOR_WINDOW_BG = 0xE6202020;
    private static final int COLOR_WINDOW_BORDER = 0xFF4A90A4;
    private static final int COLOR_HEADER_BG = 0xFF2D5A6B;
    private static final int COLOR_BUTTON_BG = 0xFF3A5A6A;
    private static final int COLOR_BUTTON_HOVER = 0xFF4A7A8A;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_NORMAL = 0xFFE0E0E0;
    private static final int COLOR_TEXT_DISABLED = 0xFF808080;

    private static final int WINDOW_WIDTH = 280;
    private static final int WINDOW_HEIGHT = 320;
    private static final int HEADER_HEIGHT = 40;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 24;
    private static final int BUTTON_SPACING = 8;

    public ConfigSelectionMenuScreen(Screen parent) {
        super(Component.literal("Simukraft 配置"), parent);
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
        ModularUI modularUI = new ModularUI(new Size(WINDOW_WIDTH, WINDOW_HEIGHT), new MenuUIHolder(), null);
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

        // 使用TextTexture + ImageWidget显示标题（仿照ModConfigScreenLDLib）
        TextTexture titleTexture = new TextTexture("Simukraft 配置", COLOR_TEXT_TITLE);
        titleTexture.setType(TextTexture.TextType.NORMAL);
        titleTexture.setWidth(WINDOW_WIDTH - 4);
        headerGroup.addWidget(new ImageWidget(0, 12, WINDOW_WIDTH - 4, 16, titleTexture));
        parent.addWidget(headerGroup);
    }

    private void createButtons(WidgetGroup parent) {
        int startX = (WINDOW_WIDTH - BUTTON_WIDTH) / 2;
        int startY = HEADER_HEIGHT + 20;
        Minecraft mc = Minecraft.getInstance();

        // 客户端配置按钮
        parent.addWidget(createButton(startX, startY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "客户端配置", clickData -> openClientConfig(), true));

        // 服务器配置按钮
        boolean hasServerPermission = mc.getSingleplayerServer() != null ||
                (mc.player != null && mc.player.hasPermissions(2));
        parent.addWidget(createButton(startX, startY + BUTTON_HEIGHT + BUTTON_SPACING,
                BUTTON_WIDTH, BUTTON_HEIGHT, "服务器配置", clickData -> openServerConfig(), hasServerPermission));

        // 检查更新按钮
        parent.addWidget(createButton(startX, startY + 2 * (BUTTON_HEIGHT + BUTTON_SPACING),
                BUTTON_WIDTH, BUTTON_HEIGHT, "检查更新", clickData -> openUpdateScreen(), true));

        // Bilibili按钮
        parent.addWidget(createButton(startX, startY + 3 * (BUTTON_HEIGHT + BUTTON_SPACING),
                (BUTTON_WIDTH - 4) / 2, BUTTON_HEIGHT, "Bilibili",
                clickData -> openUrl("https://space.bilibili.com/3546922073721320"), true));

        // mcmod按钮
        parent.addWidget(createButton(startX + (BUTTON_WIDTH + 4) / 2, startY + 3 * (BUTTON_HEIGHT + BUTTON_SPACING),
                (BUTTON_WIDTH - 4) / 2, BUTTON_HEIGHT, "mcmod",
                clickData -> openUrl("https://www.mcmod.cn/class/24995.html"), true));

        // 返回按钮
        parent.addWidget(createButton(startX, startY + 4 * (BUTTON_HEIGHT + BUTTON_SPACING) + 10,
                BUTTON_WIDTH, BUTTON_HEIGHT, "返回", clickData -> onClose(), true));
    }

    private ButtonWidget createButton(int x, int y, int width, int height,
                                       String text,
                                       java.util.function.Consumer<ClickData> callback,
                                       boolean active) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);
        button.setOnPressCallback(callback);
        button.setActive(active);

        // 文字颜色根据激活状态
        int textColor = active ? COLOR_TEXT_NORMAL : COLOR_TEXT_DISABLED;
        int hoverTextColor = active ? COLOR_TEXT_TITLE : COLOR_TEXT_DISABLED;

        // 正常状态背景+文字（仿照ModConfigScreenLDLib）
        TextTexture normalText = new TextTexture(text, textColor);
        normalText.setType(TextTexture.TextType.NORMAL);
        normalText.setWidth(width);
        button.setBackground(new GuiTextureGroup(
                new ColorRectTexture(active ? COLOR_BUTTON_BG : 0xFF404040).setRadius(4),
                new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(4),
                normalText
        ));

        // 悬停状态
        TextTexture hoverText = new TextTexture(text, hoverTextColor);
        hoverText.setType(TextTexture.TextType.NORMAL);
        hoverText.setWidth(width);
        button.setHoverTexture(new GuiTextureGroup(
                new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(4),
                new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(4),
                hoverText
        ));

        return button;
    }

    private void openClientConfig() {
        Minecraft.getInstance().setScreen(new ModConfigScreenLDLib(parent));
    }

    private void openServerConfig() {
        Minecraft.getInstance().setScreen(new ServerConfigScreen(parent));
    }

    private void openUpdateScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new com.xiaoliang.simukraft.client.gui.UpdateScreenLDLib(
            parent,
            com.xiaoliang.simukraft.client.update.UpdateHandler.getInstance().getUpdateChecker()
        ));
    }

    private void openUrl(String url) {
        try {
            net.minecraft.Util.getPlatform().openUri(nn(url));
        } catch (Exception e) {
            com.xiaoliang.simukraft.Simukraft.LOGGER.error("Failed to open URL: " + url, e);
        }
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }
}
