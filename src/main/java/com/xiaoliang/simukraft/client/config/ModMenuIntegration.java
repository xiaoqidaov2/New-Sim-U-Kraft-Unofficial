package com.xiaoliang.simukraft.client.config;

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
import com.xiaoliang.simukraft.client.gui.GuiScaleManager;
import com.xiaoliang.simukraft.client.gui.ModConfigScreenLDLib;
import com.xiaoliang.simukraft.client.gui.ServerConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * ModMenu集成 - LDLib版本
 * 使用LDLib Widget实现配置选择界面
 * 使用固定3x缩放渲染
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class ModMenuIntegration {

    @SuppressWarnings("removal")
    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new ConfigSelectionScreenLDLib(parent)
                )
        );
    }

    public static class ConfigSelectionScreenLDLib extends ModularUIGuiContainer {
        private final Screen parent;
        private static ConfigSelectionScreenLDLib currentInstance;

        private static final int COLOR_WINDOW_BG = 0xE6202020;
        private static final int COLOR_WINDOW_BORDER = 0xFF4A90A4;
        private static final int COLOR_HEADER_BG = 0xFF2D5A6B;
        private static final int COLOR_BUTTON_BG = 0xFF3A5A6A;
        private static final int COLOR_BUTTON_HOVER = 0xFF4A7A8A;
        private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
        private static final int COLOR_TEXT_NORMAL = 0xFFE0E0E0;

        private static final int WINDOW_WIDTH = 280;
        private static final int WINDOW_HEIGHT = 320;
        private static final int HEADER_HEIGHT = 40;
        private static final int BUTTON_WIDTH = 200;
        private static final int BUTTON_HEIGHT = 24;
        private static final int BUTTON_SPACING = 8;

        public ConfigSelectionScreenLDLib(Screen parent) {
            super(createHolderAndUI(parent), 0);
            this.parent = parent;
            currentInstance = this;
            GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
        }

        private static ModularUI createHolderAndUI(Screen parent) {
            return new ConfigUIHolder(parent).createModularUI();
        }

        @Override
        public void onClose() {
            GuiScaleManager.forceRestore();
            currentInstance = null;
            Minecraft.getInstance().setScreen(parent);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
            super.render(graphics, mouseX, mouseY, partialTicks);
        }

        private static class ConfigUIHolder implements IUIHolder {
            private final Screen parent;

            public ConfigUIHolder(Screen parent) {
                this.parent = parent;
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

                TextTexture titleTexture = new TextTexture("Simukraft 配置", COLOR_TEXT_TITLE);
                titleTexture.setType(TextTexture.TextType.NORMAL);
                headerGroup.addWidget(new ImageWidget(0, 12, WINDOW_WIDTH - 4, 16, titleTexture));
                parent.addWidget(headerGroup);
            }

            private void createButtons(WidgetGroup parent) {
                int startX = (WINDOW_WIDTH - BUTTON_WIDTH) / 2;
                int startY = HEADER_HEIGHT + 20;
                Minecraft mc = Minecraft.getInstance();

                parent.addWidget(createButton(startX, startY, BUTTON_WIDTH, BUTTON_HEIGHT,
                        "客户端配置", clickData -> openClientConfig()));

                boolean hasServerPermission = mc.getSingleplayerServer() != null ||
                        (mc.player != null && mc.player.hasPermissions(2));
                ButtonWidget serverButton = createButton(startX, startY + BUTTON_HEIGHT + BUTTON_SPACING,
                        BUTTON_WIDTH, BUTTON_HEIGHT, "服务器配置", clickData -> openServerConfig());
                serverButton.setActive(hasServerPermission);
                parent.addWidget(serverButton);

                parent.addWidget(createButton(startX, startY + 2 * (BUTTON_HEIGHT + BUTTON_SPACING),
                        BUTTON_WIDTH, BUTTON_HEIGHT, "检查更新", clickData -> openUpdateScreen()));

                parent.addWidget(createButton(startX, startY + 3 * (BUTTON_HEIGHT + BUTTON_SPACING),
                        (BUTTON_WIDTH - 4) / 2, BUTTON_HEIGHT, "Bilibili",
                        clickData -> openUrl("https://space.bilibili.com/3546922073721320")));

                parent.addWidget(createButton(startX + (BUTTON_WIDTH + 4) / 2, startY + 3 * (BUTTON_HEIGHT + BUTTON_SPACING),
                        (BUTTON_WIDTH - 4) / 2, BUTTON_HEIGHT, "mcmod",
                        clickData -> openUrl("https://www.mcmod.cn/class/24995.html")));

                parent.addWidget(createButton(startX, startY + 4 * (BUTTON_HEIGHT + BUTTON_SPACING) + 10,
                        BUTTON_WIDTH, BUTTON_HEIGHT, "返回", clickData -> closeScreen()));
            }

            private ButtonWidget createButton(int x, int y, int width, int height,
                                               String text,
                                               java.util.function.Consumer<ClickData> callback) {
                ButtonWidget button = new ButtonWidget();
                button.setSelfPosition(x, y);
                button.setSize(width, height);
                button.setOnPressCallback(callback);
                button.setButtonTexture(new GuiTextureGroup(
                        new ColorRectTexture(COLOR_BUTTON_BG).setRadius(4),
                        new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(4)
                ), new TextTexture(text, COLOR_TEXT_NORMAL).setType(TextTexture.TextType.NORMAL));
                button.setHoverTexture(new GuiTextureGroup(
                        new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(4),
                        new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(4)
                ), new TextTexture(text, COLOR_TEXT_TITLE).setType(TextTexture.TextType.NORMAL));
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

            private void closeScreen() {
                // simukraft: 调用onClose来恢复缩放
                if (currentInstance != null) {
                    currentInstance.onClose();
                }
            }

            private void openUrl(String url) {
                try {
                    net.minecraft.Util.getPlatform().openUri(url);
                } catch (Exception e) {
                    com.xiaoliang.simukraft.Simukraft.LOGGER.error("Failed to open URL: " + url, e);
                }
            }

            @Override public boolean isInvalid() { return false; }
            @Override public boolean isRemote() { return true; }
            @Override public void markAsDirty() {}
        }
    }
}
