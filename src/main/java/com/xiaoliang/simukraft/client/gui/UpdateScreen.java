package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoliang.simukraft.client.update.GiteeUpdateChecker;
import com.xiaoliang.simukraft.client.update.UpdateInfo;
import com.xiaoliang.simukraft.client.update.UpdateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class UpdateScreen extends Screen {

    private final Screen parent;
    private final GiteeUpdateChecker updateChecker;
    private UpdateInfo updateInfo;
    private Button downloadButton;
    private Button installButton;
    private Button restartButton;
    private Button backButton;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;
    private List<Component> changelogLines = new ArrayList<>();
    private float downloadProgress = 0.0f;
    private int scrollOffset = 0;

    @Nonnull
    public static final CubeMap PANORAMA_RESOURCES = createPanoramaResources();
    @Nonnull
    public static final PanoramaRenderer PANORAMA = createPanoramaRenderer();

    @Nonnull
    private static final ResourceLocation LOGO_TEXTURE = nn(ResourceLocation.fromNamespaceAndPath(com.xiaoliang.simukraft.Simukraft.MOD_ID, "logo.png"));

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static CubeMap createPanoramaResources() {
        return nn(new CubeMap(nn(ResourceLocation.fromNamespaceAndPath(com.xiaoliang.simukraft.Simukraft.MOD_ID, "textures/background/panorama"))));
    }

    @Nonnull
    private static PanoramaRenderer createPanoramaRenderer() {
        return nn(new PanoramaRenderer(PANORAMA_RESOURCES));
    }

    public UpdateScreen(Screen parent, GiteeUpdateChecker updateChecker) {
        super(Component.translatable("gui.update.title"));
        this.parent = parent;
        this.updateChecker = updateChecker;
        this.updateInfo = updateChecker.getLatestUpdate();
    }

    @Override
    protected void init() {
        super.init();
        parseChangelog();

        int centerX = this.width / 2;
        int buttonWidth = Math.max(100, (int) (this.width * 0.15));
        int buttonHeight = Math.max(20, (int) (this.height * 0.05));
        int buttonY = this.height - buttonHeight - 10;

        downloadButton = nn(Button.builder(nn(Component.translatable("gui.update.download")), btn -> startDownload())
                .bounds(centerX - buttonWidth - 5, buttonY, buttonWidth, buttonHeight).build());
        installButton = nn(Button.builder(nn(Component.translatable("gui.update.install")), btn -> startInstall())
                .bounds(centerX - buttonWidth - 5, buttonY, buttonWidth, buttonHeight).build());
        restartButton = nn(Button.builder(nn(Component.translatable("gui.update.restart")), btn -> restartGame())
                .bounds(centerX - buttonWidth - 5, buttonY, buttonWidth, buttonHeight).build());
        backButton = nn(Button.builder(nn(Component.translatable("gui.back")), btn -> backToConfig())
                .bounds(centerX + 5, buttonY, buttonWidth, buttonHeight).build());

        updateButtons();
        addRenderableWidget(backButton);
    }

    private void updateButtons() {
        if (downloadButton != null) {
            removeWidget(downloadButton);
        }
        if (installButton != null) {
            removeWidget(installButton);
        }
        if (restartButton != null) {
            removeWidget(restartButton);
        }

        UpdateManager.UpdateState state = UpdateManager.getInstance().getState();
        boolean hasNewerVersion = updateChecker.isNewerVersionAvailable();

        switch (state) {
            case IDLE, UPDATE_AVAILABLE, ERROR -> {
                // 只有当有新版本时才显示下载按钮
                if (hasNewerVersion) {
                    addRenderableWidget(downloadButton);
                }
            }
            case CHECKING, DOWNLOADING, INSTALLING -> {
                // 下载中不显示任何操作按钮
            }
            case DOWNLOAD_COMPLETE -> addRenderableWidget(installButton);
            case INSTALL_COMPLETE -> addRenderableWidget(restartButton);
        }
    }

    private void parseChangelog() {
        changelogLines.clear();
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

    private void startDownload() {
        // 检查是否有下载URL
        if (updateInfo == null || !updateInfo.hasDownloadUrl()) {
            statusMessage = "错误: 没有可用的下载链接";
            statusColor = 0xFF4444;
            com.xiaoliang.simukraft.Simukraft.LOGGER.error("No download URL available. updateInfo: {}, hasUrl: {}",
                updateInfo, updateInfo != null ? updateInfo.hasDownloadUrl() : "N/A");
            return;
        }

        com.xiaoliang.simukraft.Simukraft.LOGGER.info("Starting download from UpdateScreen. URL: {}", updateInfo.downloadUrl());

        // 将更新信息设置到 UpdateManager
        UpdateManager.getInstance().setCurrentUpdate(updateInfo);

        UpdateManager.getInstance().downloadUpdate().thenAccept(success -> {
            Minecraft.getInstance().execute(() -> {
                if (success) {
                    statusMessage = "下载完成！点击安装更新";
                    statusColor = 0x00FF00;
                } else {
                    statusMessage = "下载失败: " + UpdateManager.getInstance().getErrorMessage();
                    statusColor = 0xFF4444;
                }
                updateButtons();
            });
        });
        statusMessage = "正在下载更新...";
        statusColor = 0xFFFF00;
        updateButtons();
    }

    private void startInstall() {
        if (UpdateManager.getInstance().installUpdate()) {
            statusMessage = "安装完成！请重启游戏";
            statusColor = 0x00FF00;
        } else {
            statusMessage = "安装失败: " + UpdateManager.getInstance().getErrorMessage();
            statusColor = 0xFF4444;
        }
        updateButtons();
    }

    private void restartGame() {
        UpdateManager.getInstance().restartGame();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        var font = nn(this.font);
        PANORAMA.render(partialTick, 1.0f);
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);
        renderLogo(guiGraphics);

        int titleY = (int) (this.height * 0.12);
        int versionY = (int) (this.height * 0.18);
        int dateY = (int) (this.height * 0.22);

        // 检查是否真的需要更新
        boolean hasNewerVersion = updateChecker.isNewerVersionAvailable();

        Component title;
        Component versionInfo;
        if (hasNewerVersion) {
            title = Component.translatable("gui.update.new_version_available")
                    .withStyle(s -> s.withBold(true).withColor(0xFFD700));
            versionInfo = Component.literal("当前版本: " + updateChecker.getCurrentVersion() +
                    " -> 新版本: " + updateInfo.getVersionNumber())
                    .withStyle(s -> s.withColor(0xAAAAAA));
        } else {
            title = Component.literal("已经是最新版本")
                    .withStyle(s -> s.withBold(true).withColor(0x00FF00));
            versionInfo = Component.literal("当前版本: " + updateChecker.getCurrentVersion() +
                    " (最新: " + updateInfo.getVersionNumber() + ")")
                    .withStyle(s -> s.withColor(0xAAAAAA));
        }
        guiGraphics.drawString(font, nn(title), (this.width - font.width(title)) / 2, titleY, 0xFFFFFF, true);

        if (updateInfo != null) {
            guiGraphics.drawString(font, nn(versionInfo), (this.width - font.width(versionInfo)) / 2, versionY, 0xFFFFFF, true);

            if (!updateInfo.getFormattedDate().isEmpty()) {
                Component dateInfo = Component.literal("发布日期: " + updateInfo.getFormattedDate())
                        .withStyle(s -> s.withColor(0x888888));
                guiGraphics.drawString(font, nn(dateInfo), (this.width - font.width(dateInfo)) / 2, dateY, 0xFFFFFF, true);
            }
        }

        int boxW = (int) (this.width * 0.4);
        int boxH = (int) (this.height * 0.35);
        int boxX = (this.width - boxW) / 2;
        int boxY = (int) (this.height * 0.28);
        guiGraphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0x88000000);
        guiGraphics.fill(boxX, boxY, boxX + boxW, boxY + 1, 0xFF888888);
        guiGraphics.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, 0xFF888888);
        guiGraphics.fill(boxX, boxY, boxX + 1, boxY + boxH, 0xFF888888);
        guiGraphics.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, 0xFF888888);

        int lineY = boxY + (int) (this.height * 0.015);
        int lineHeight = Math.max(10, (int) (this.height * 0.018));
        int maxLines = Math.max(3, boxH / lineHeight - 1);
        for (int i = scrollOffset; i < Math.min(changelogLines.size(), scrollOffset + maxLines); i++) {
            Component line = nn(changelogLines.get(i));
            guiGraphics.drawString(font, line, boxX + (int) (this.width * 0.015), lineY, 0xFFFFFF, false);
            lineY += lineHeight;
        }

        if (!statusMessage.isEmpty()) {
            Component status = Component.literal(statusMessage).withStyle(s -> s.withColor(statusColor));
            guiGraphics.drawString(font, nn(status), (this.width - font.width(status)) / 2,
                    boxY + boxH + (int) (this.height * 0.015), 0xFFFFFF, true);
        }

        UpdateManager.UpdateState state = UpdateManager.getInstance().getState();
        if (state == UpdateManager.UpdateState.DOWNLOADING) {
            downloadProgress = UpdateManager.getInstance().getDownloadProgress();
            long downloadedBytes = UpdateManager.getInstance().getDownloadedBytes();
            long totalBytes = UpdateManager.getInstance().getTotalBytes();
            long downloadSpeed = UpdateManager.getInstance().getDownloadSpeed();

            int barWidth = (int) (this.width * 0.25);
            int barHeight = Math.max(8, (int) (this.height * 0.012));
            int barX = (this.width - barWidth) / 2;
            int barY = boxY + boxH + (int) (this.height * 0.05);

            // 进度条背景
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF444444);
            // 进度条填充
            guiGraphics.fill(barX, barY, barX + (int)(barWidth * downloadProgress), barY + barHeight, 0xFF00AA00);
            // 进度条边框
            guiGraphics.fill(barX, barY, barX + barWidth, barY + 1, 0xFF888888);
            guiGraphics.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, 0xFF888888);
            guiGraphics.fill(barX, barY, barX + 1, barY + barHeight, 0xFF888888);
            guiGraphics.fill(barX + barWidth - 1, barY, barX + barWidth, barY + barHeight, 0xFF888888);

            // 百分比文字
            Component progressText = Component.literal(String.format("%.0f%%", downloadProgress * 100))
                    .withStyle(s -> s.withColor(0xFFFFFF));
            int percentY = barY + barHeight + 8;
            guiGraphics.drawString(font, nn(progressText), (this.width - font.width(progressText)) / 2, percentY, 0xFFFFFF, true);

            // 下载大小和速度
            String sizeText = formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes);
            String speedText = formatBytes(downloadSpeed) + "/s";

            Component sizeComponent = Component.literal(sizeText).withStyle(s -> s.withColor(0xAAAAAA));
            Component speedComponent = Component.literal(speedText).withStyle(s -> s.withColor(0x00FF00));

            int textY = percentY + 15;
            guiGraphics.drawString(font, nn(sizeComponent), barX, textY, 0xFFFFFF, false);
            guiGraphics.drawString(font, nn(speedComponent), barX + barWidth - font.width(speedComponent), textY, 0xFFFFFF, false);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderLogo(GuiGraphics guiGraphics) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, LOGO_TEXTURE);

        int logoWidth = (int) (this.width * 0.25);
        int logoHeight = (int) (logoWidth * 0.3); // 保持宽高比
        int logoX = (this.width - logoWidth) / 2;
        int logoY = (int) (this.height * 0.02);

        guiGraphics.blit(LOGO_TEXTURE, logoX, logoY, 0, 0, logoWidth, logoHeight, logoWidth, logoHeight);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        } else if (delta < 0) {
            if (scrollOffset < Math.max(0, changelogLines.size() - 6)) {
                scrollOffset++;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        nn(Minecraft.getInstance()).setScreen(parent);
    }

    /**
     * 返回到配置选择界面
     */
    private void backToConfig() {
        nn(Minecraft.getInstance()).setScreen(
            new com.xiaoliang.simukraft.client.gui.ldlib.ConfigSelectionMenuScreen(parent)
        );
    }

    /**
     * 格式化字节大小为可读字符串
     */
    private String formatBytes(long bytes) {
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
}
