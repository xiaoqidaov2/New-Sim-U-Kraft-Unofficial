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
import com.xiaoliang.simukraft.client.update.GiteeUpdateChecker;
import com.xiaoliang.simukraft.client.update.UpdateInfo;
import com.xiaoliang.simukraft.client.update.UpdateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新界面 - LDLib版本
 * simukraft: 使用LDLibMenuScreen基类，继承自原版Screen而非容器界面
 * 避免被其他模组误识别为箱子容器，解决主菜单玩家为Null的问题
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class UpdateScreenLDLib extends LDLibMenuScreen {
    private final GiteeUpdateChecker updateChecker;
    private static UpdateScreenLDLib currentInstance;

    // 颜色配置
    private static final int COLOR_WINDOW_BG = 0xE6202020;
    private static final int COLOR_WINDOW_BORDER = 0xFF4A90A4;
    private static final int COLOR_HEADER_BG = 0xFF2D5A6B;
    private static final int COLOR_BUTTON_BG = 0xFF3A5A6A;
    private static final int COLOR_BUTTON_HOVER = 0xFF4A7A8A;
    private static final int COLOR_TEXT_NORMAL = 0xFFE0E0E0;
    private static final int COLOR_TEXT_GOLD = 0xFFFFD700;
    private static final int COLOR_TEXT_GREEN = 0xFF00FF00;
    private static final int COLOR_TEXT_RED = 0xFFFF4444;
    private static final int COLOR_TEXT_YELLOW = 0xFFFFFF00;
    private static final int COLOR_PROGRESS_BG = 0xFF444444;
    private static final int COLOR_PROGRESS_FILL = 0xFF00AA00;
    private static final int COLOR_PROGRESS_BORDER = 0xFF666666;

    // 窗口尺寸
    private static final int WINDOW_WIDTH = 400;
    private static final int WINDOW_HEIGHT = 380;
    private static final int HEADER_HEIGHT = 50;
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 24;

    // 状态
    private String statusMessage = "";
    private int statusColor = COLOR_TEXT_NORMAL;
    private List<Component> changelogLines = new ArrayList<>();

    public UpdateScreenLDLib(Screen parent, GiteeUpdateChecker updateChecker) {
        super(Component.literal("更新检查"), parent);
        this.updateChecker = updateChecker;
        currentInstance = this;
        parseChangelog();
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
        return new UpdateUIHolder(parent, updateChecker).createModularUI();
    }

    @Override
    public void onClose() {
        // simukraft: 恢复原始缩放并重置状态
        GuiScaleManager.forceRestore();
        currentInstance = null;
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);

        // simukraft: 下载过程中在渲染时动态绘制进度信息
        if (UpdateManager.getInstance().getState() == UpdateManager.UpdateState.DOWNLOADING) {
            renderDownloadProgress(graphics);
        }
    }

    /**
     * 渲染下载进度信息（动态更新）
     * simukraft: 在LDLibMenuScreen.render()之后绘制，覆盖在UI上方
     */
    private void renderDownloadProgress(GuiGraphics graphics) {
        // simukraft: 进度条相对于GUI窗口的位置
        int barX = guiLeft + (WINDOW_WIDTH - 200) / 2;
        int barY = guiTop + HEADER_HEIGHT + 205;
        int barW = 200;
        int barH = 12;

        float progress = UpdateManager.getInstance().getDownloadProgress();
        long downloadedBytes = UpdateManager.getInstance().getDownloadedBytes();
        long totalBytes = UpdateManager.getInstance().getTotalBytes();
        long downloadSpeed = UpdateManager.getInstance().getDownloadSpeed();

        // simukraft: 绘制进度条边框
        graphics.fill(barX - 1, barY - 1, barX + barW + 1, barY, COLOR_PROGRESS_BORDER);
        graphics.fill(barX - 1, barY + barH, barX + barW + 1, barY + barH + 1, COLOR_PROGRESS_BORDER);
        graphics.fill(barX - 1, barY, barX, barY + barH, COLOR_PROGRESS_BORDER);
        graphics.fill(barX + barW, barY, barX + barW + 1, barY + barH, COLOR_PROGRESS_BORDER);

        // simukraft: 绘制进度条背景
        graphics.fill(barX, barY, barX + barW, barY + barH, COLOR_PROGRESS_BG);

        // simukraft: 绘制进度条填充
        int fillWidth = (int) (barW * progress);
        if (fillWidth > 0) {
            graphics.fill(barX, barY, barX + fillWidth, barY + barH, COLOR_PROGRESS_FILL);
        }

        // simukraft: 绘制百分比文字
        String percentText = String.format("%.0f%%", progress * 100);
        graphics.drawString(Minecraft.getInstance().font, percentText,
                barX + barW / 2 - 10, barY + barH + 5, COLOR_TEXT_NORMAL);

        // simukraft: 绘制下载大小
        String sizeText = formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes);
        graphics.drawString(Minecraft.getInstance().font, sizeText,
                barX, barY + barH + 18, 0xFFAAAAAA);

        // simukraft: 绘制下载速度
        String speedText = formatBytes(downloadSpeed) + "/s";
        graphics.drawString(Minecraft.getInstance().font, speedText,
                barX + barW / 2, barY + barH + 18, COLOR_TEXT_GREEN);
    }

    /**
     * 解析更新日志
     */
    private void parseChangelog() {
        changelogLines.clear();
        UpdateInfo updateInfo = updateChecker.getLatestUpdate();
        if (updateInfo == null || updateInfo.body() == null) return;

        String[] lines = updateInfo.body().split("\\r?\\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("# ")) {
                changelogLines.add(Component.literal(line.substring(2)).withStyle(s -> s.withBold(true).withColor(0xFFD700)));
            } else if (line.startsWith("## ")) {
                changelogLines.add(Component.literal(line.substring(3)).withStyle(s -> s.withBold(true).withColor(0xFFA500)));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                changelogLines.add(Component.literal("  - " + line.substring(2)).withStyle(s -> s.withColor(0xEEEEEE)));
            } else {
                changelogLines.add(Component.literal(line).withStyle(s -> s.withColor(0xCCCCCC)));
            }
        }
    }

    /**
     * 格式化字节大小
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * UI持有者类
     * simukraft: 内部类负责创建ModularUI和Widget布局
     */
    private class UpdateUIHolder implements IUIHolder {
        private final Screen parent;
        private final GiteeUpdateChecker updateChecker;

        public UpdateUIHolder(Screen parent, GiteeUpdateChecker updateChecker) {
            this.parent = parent;
            this.updateChecker = updateChecker;
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

            // 主窗口背景
            WidgetGroup windowGroup = new WidgetGroup();
            windowGroup.setSelfPosition(0, 0);
            windowGroup.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
            windowGroup.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_WINDOW_BG).setRadius(10),
                    new ColorBorderTexture(2, COLOR_WINDOW_BORDER).setRadius(10)
            ));
            rootGroup.addWidget(windowGroup);

            createHeader(windowGroup);
            createVersionInfo(windowGroup);
            createChangelogBox(windowGroup);
            createProgressBar(windowGroup);
            createStatusText(windowGroup);
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

            // 标题
            String titleText = updateChecker.isNewerVersionAvailable() ? "发现新版本" : "已经是最新版本";
            int titleColor = updateChecker.isNewerVersionAvailable() ? COLOR_TEXT_GOLD : COLOR_TEXT_GREEN;
            TextTexture titleTexture = new TextTexture(titleText, titleColor);
            titleTexture.setType(TextTexture.TextType.NORMAL);
            headerGroup.addWidget(new ImageWidget(0, 15, WINDOW_WIDTH - 4, 16, titleTexture));

            parent.addWidget(headerGroup);
        }

        private void createVersionInfo(WidgetGroup parent) {
            UpdateInfo updateInfo = updateChecker.getLatestUpdate();
            if (updateInfo == null) return;

            boolean hasNewer = updateChecker.isNewerVersionAvailable();
            String versionText = hasNewer
                    ? "当前版本: " + updateChecker.getCurrentVersion() + " -> 新版本: " + updateInfo.getVersionNumber()
                    : "当前版本: " + updateChecker.getCurrentVersion() + " (最新: " + updateInfo.getVersionNumber() + ")";

            TextTexture versionTexture = new TextTexture(versionText, 0xFFAAAAAA);
            versionTexture.setType(TextTexture.TextType.NORMAL);
            parent.addWidget(new ImageWidget(0, HEADER_HEIGHT + 10, WINDOW_WIDTH, 12, versionTexture));

            // 发布日期
            if (!updateInfo.getFormattedDate().isEmpty()) {
                TextTexture dateTexture = new TextTexture("发布日期: " + updateInfo.getFormattedDate(), 0xFF888888);
                dateTexture.setType(TextTexture.TextType.NORMAL);
                parent.addWidget(new ImageWidget(0, HEADER_HEIGHT + 24, WINDOW_WIDTH, 12, dateTexture));
            }
        }

        private void createChangelogBox(WidgetGroup parent) {
            int boxX = 20;
            int boxY = HEADER_HEIGHT + 45;
            int boxW = WINDOW_WIDTH - 40;
            int boxH = 140;

            // 更新日志背景框
            WidgetGroup changelogGroup = new WidgetGroup();
            changelogGroup.setSelfPosition(boxX, boxY);
            changelogGroup.setSize(boxW, boxH);
            changelogGroup.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(0x88000000).setRadius(5),
                    new ColorBorderTexture(1, 0xFF888888).setRadius(5)
            ));

            // 更新日志内容
            UpdateInfo updateInfo = updateChecker.getLatestUpdate();
            if (updateInfo != null && updateInfo.body() != null) {
                String[] lines = updateInfo.body().split("\\r?\\n");
                int lineY = 8;
                int lineHeight = 14;
                int maxLines = boxH / lineHeight - 1;

                for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
                    String line = lines[i];
                    if (line.trim().isEmpty()) continue;

                    int color = 0xFFCCCCCC;
                    String displayText = line;

                    if (line.startsWith("# ")) {
                        color = 0xFFFFD700;
                        displayText = line.substring(2);
                    } else if (line.startsWith("## ")) {
                        color = 0xFFFFA500;
                        displayText = line.substring(3);
                    } else if (line.startsWith("- ") || line.startsWith("* ")) {
                        displayText = "  - " + line.substring(2);
                    }

                    TextTexture lineTexture = new TextTexture(displayText, color);
                    lineTexture.setType(TextTexture.TextType.NORMAL);
                    changelogGroup.addWidget(new ImageWidget(10, lineY, boxW - 20, 12, lineTexture));
                    lineY += lineHeight;
                }
            }

            parent.addWidget(changelogGroup);
        }

        private void createProgressBar(WidgetGroup parent) {
            UpdateManager.UpdateState state = UpdateManager.getInstance().getState();
            if (state != UpdateManager.UpdateState.DOWNLOADING) return;

            int barX = (WINDOW_WIDTH - 200) / 2;
            int barY = HEADER_HEIGHT + 200;
            int barW = 200;
            int barH = 12;

            // 进度条背景
            WidgetGroup progressGroup = new WidgetGroup();
            progressGroup.setSelfPosition(barX, barY);
            progressGroup.setSize(barW, barH + 30);

            // 背景
            WidgetGroup bgWidget = new WidgetGroup();
            bgWidget.setSelfPosition(0, 0);
            bgWidget.setSize(barW, barH);
            bgWidget.setBackground(new ColorRectTexture(COLOR_PROGRESS_BG).setRadius(3));
            progressGroup.addWidget(bgWidget);

            // simukraft: 进度条填充和文字在 renderDownloadProgress 中动态绘制

            parent.addWidget(progressGroup);
        }

        private void createStatusText(WidgetGroup parent) {
            if (currentInstance == null || currentInstance.statusMessage.isEmpty()) return;

            int statusY = HEADER_HEIGHT + 195;
            if (UpdateManager.getInstance().getState() == UpdateManager.UpdateState.DOWNLOADING) {
                statusY = HEADER_HEIGHT + 250;
            }

            TextTexture statusTexture = new TextTexture(currentInstance.statusMessage, currentInstance.statusColor);
            statusTexture.setType(TextTexture.TextType.NORMAL);
            parent.addWidget(new ImageWidget(0, statusY, WINDOW_WIDTH, 12, statusTexture));
        }

        private void createButtons(WidgetGroup parent) {
            int buttonY = WINDOW_HEIGHT - 40;
            int centerX = WINDOW_WIDTH / 2;

            UpdateManager.UpdateState state = UpdateManager.getInstance().getState();
            boolean hasNewerVersion = updateChecker.isNewerVersionAvailable();

            // 根据状态显示不同按钮
            if (state == UpdateManager.UpdateState.IDLE ||
                state == UpdateManager.UpdateState.UPDATE_AVAILABLE ||
                state == UpdateManager.UpdateState.ERROR) {

                if (hasNewerVersion) {
                    // 下载按钮
                    parent.addWidget(createButton(centerX - BUTTON_WIDTH - 10, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                            "下载更新", clickData -> startDownload()));
                }
            } else if (state == UpdateManager.UpdateState.DOWNLOAD_COMPLETE) {
                // 安装按钮
                parent.addWidget(createButton(centerX - BUTTON_WIDTH - 10, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                        "安装更新", clickData -> startInstall()));
            } else if (state == UpdateManager.UpdateState.INSTALL_COMPLETE) {
                // 重启按钮
                parent.addWidget(createButton(centerX - BUTTON_WIDTH - 10, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                        "重启游戏", clickData -> restartGame()));
            }

            // 返回按钮
            parent.addWidget(createButton(centerX + 10, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                    "返回", clickData -> closeScreen()));
        }

        private ButtonWidget createButton(int x, int y, int width, int height,
                                           String text,
                                           java.util.function.Consumer<ClickData> callback) {
            ButtonWidget button = new ButtonWidget();
            button.setSelfPosition(x, y);
            button.setSize(width, height);
            button.setOnPressCallback(callback);

            // 文字纹理
            TextTexture textTexture = new TextTexture(text, COLOR_TEXT_NORMAL);
            textTexture.setType(TextTexture.TextType.NORMAL);
            textTexture.setWidth(width);

            // 正常状态背景+文字
            button.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(5),
                    textTexture
            ));

            // 悬停状态
            TextTexture hoverTextTexture = new TextTexture(text, COLOR_TEXT_NORMAL);
            hoverTextTexture.setType(TextTexture.TextType.NORMAL);
            hoverTextTexture.setWidth(width);
            button.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(5),
                    hoverTextTexture
            ));

            return button;
        }

        private void startDownload() {
            if (currentInstance == null) return;

            UpdateInfo updateInfo = updateChecker.getLatestUpdate();
            if (updateInfo == null || !updateInfo.hasDownloadUrl()) {
                currentInstance.statusMessage = "错误: 没有可用的下载链接";
                currentInstance.statusColor = COLOR_TEXT_RED;
                return;
            }

            UpdateManager.getInstance().setCurrentUpdate(updateInfo);

            // simukraft: 先设置下载中状态
            currentInstance.statusMessage = "正在下载更新...";
            currentInstance.statusColor = COLOR_TEXT_YELLOW;

            // simukraft: 立即刷新界面显示下载状态
            Minecraft.getInstance().setScreen(new UpdateScreenLDLib(parent, updateChecker));

            UpdateManager.getInstance().downloadUpdate().thenAccept(success -> {
                Minecraft.getInstance().execute(() -> {
                    if (success) {
                        currentInstance.statusMessage = "下载完成！点击安装更新";
                        currentInstance.statusColor = COLOR_TEXT_GREEN;
                    } else {
                        currentInstance.statusMessage = "下载失败: " + UpdateManager.getInstance().getErrorMessage();
                        currentInstance.statusColor = COLOR_TEXT_RED;
                    }
                    // 刷新界面
                    Minecraft.getInstance().setScreen(new UpdateScreenLDLib(parent, updateChecker));
                });
            });
        }

        private void startInstall() {
            if (currentInstance == null) return;

            if (UpdateManager.getInstance().installUpdate()) {
                currentInstance.statusMessage = "安装完成！请重启游戏";
                currentInstance.statusColor = COLOR_TEXT_GREEN;
            } else {
                currentInstance.statusMessage = "安装失败: " + UpdateManager.getInstance().getErrorMessage();
                currentInstance.statusColor = COLOR_TEXT_RED;
            }
            // 刷新界面
            Minecraft.getInstance().setScreen(new UpdateScreenLDLib(parent, updateChecker));
        }

        private void restartGame() {
            UpdateManager.getInstance().restartGame();
        }

        private void closeScreen() {
            if (currentInstance != null) {
                currentInstance.onClose();
            }
        }

        @Override public boolean isInvalid() { return false; }
        @Override public boolean isRemote() { return true; }
        @Override public void markAsDirty() {}
    }
}
