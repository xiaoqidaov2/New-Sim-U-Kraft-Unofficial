package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.xiaoliang.simukraft.client.ClientSimukraftData;
import com.xiaoliang.simukraft.client.gui.components.UpgradeCanvas;
import com.xiaoliang.simukraft.world.CityUpgradeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

@SuppressWarnings("null")
public class CityUpgradeScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation UPGRADE_BG_TEXTURE = nn(ResourceLocation.fromNamespaceAndPath("simukraft", "textures/gui/updatabg.png"));
    private static final ResourceLocation PANEL_BG_TEXTURE = nn(ResourceLocation.fromNamespaceAndPath("simukraft", "textures/gui/tan.png"));
    private static final int SCALE_FIT_WIDTH = 720;
    private static final int SCALE_FIT_HEIGHT = 420;
    private static final int COLOR_TEXT = 0xFF4F3928;
    private static final int COLOR_TEXT_MUTED = 0xFF7B6346;
    private static final int COLOR_COMPLETE = 0xFF6D7C3E;
    private static final int COLOR_AVAILABLE = 0xFF7E8F40;
    private static final int COLOR_LOCKED = 0xFF9C6A55;
    private static final int COLOR_WARN = 0xFF9A6B2C;
    private static final int PANEL_MIN_WIDTH = 270;
    private static final int PANEL_MAX_WIDTH = 330;
    private static final int PANEL_CONTENT_LEFT = 44;
    private static final int PANEL_CONTENT_RIGHT = 28;
    private static final int PANEL_CONTENT_TOP = 18;
    private static final int PANEL_CONTENT_BOTTOM = 74;
    private static final int HEADER_HEIGHT = 48;
    private static final int FOOTER_HEIGHT = 42;

    private final BlockPos cityCorePos;
    private UpgradeCanvas upgradeCanvas;
    private final int cityLevel;
    private Button submitButton;
    private Button cancelButton;
    private Button backButton;
    @Nullable
    private Component feedbackMessage;
    private int feedbackColor = COLOR_TEXT;
    private boolean pendingUpgradeRequest;
    private int panelWidth;
    private int targetPanelX;
    private int currentPanelX;
    private final int animationSpeed = 18;

    public CityUpgradeScreen(BlockPos cityCorePos, int cityLevel) {
        super(Component.translatable("gui.city_upgrade.title"));
        this.cityCorePos = cityCorePos;
        this.cityLevel = cityLevel;
        applyPreferredScale();
    }

    @Override
    protected void init() {
        applyPreferredScale();
        super.init();
        panelWidth = Math.max(PANEL_MIN_WIDTH, Math.min(PANEL_MAX_WIDTH, this.width / 3));
        int canvasX = 18;
        int canvasY = HEADER_HEIGHT + 12;
        int canvasWidth = this.width - 36;
        int canvasHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT - 24;
        this.upgradeCanvas = new UpgradeCanvas(canvasX, canvasY, canvasWidth, canvasHeight, this, cityCorePos, cityLevel);
        this.addRenderableWidget(upgradeCanvas);
        currentPanelX = this.width;
        targetPanelX = this.width;
        int actionWidth = Math.max(88, (panelWidth - 52) / 2);
        submitButton = nn(Button.builder(nn(Component.translatable("gui.city_upgrade.submit")), button -> this.handleSubmit())
                .pos(this.width + 20, this.height - 38).size(actionWidth, 22).build());
        cancelButton = nn(Button.builder(nn(Component.translatable("gui.city_upgrade.cancel")), button -> this.handleCancel())
                .pos(this.width + 30 + actionWidth, this.height - 38).size(actionWidth, 22).build());
        backButton = nn(Button.builder(nn(Component.translatable("gui.city_upgrade.back")), button -> this.closeScreen())
                .pos(18, this.height - 32).size(88, 22).build());
        this.addRenderableWidget(submitButton);
        this.addRenderableWidget(cancelButton);
        this.addRenderableWidget(backButton);
        submitButton.visible = false;
        cancelButton.visible = false;
        applyButtonStyle(submitButton, false);
        applyButtonStyle(cancelButton, false);
        applyButtonStyle(backButton, false);
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        renderScreenBackground(guiGraphics);
        updateAnimation();
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        renderHeader(guiGraphics);
        renderFooter(guiGraphics);
        renderRightPanel(guiGraphics);
        renderButtonFrame(guiGraphics, backButton, false);
        if (submitButton != null && submitButton.visible) {
            renderButtonFrame(guiGraphics, submitButton, !submitButton.active);
            submitButton.render(guiGraphics, mouseX, mouseY, partialTicks);
        }
        if (cancelButton != null && cancelButton.visible) {
            renderButtonFrame(guiGraphics, cancelButton, false);
            cancelButton.render(guiGraphics, mouseX, mouseY, partialTicks);
        }
    }

    private void renderScreenBackground(GuiGraphics guiGraphics) {
        RenderSystem.setShaderTexture(0, UPGRADE_BG_TEXTURE);
        guiGraphics.blit(UPGRADE_BG_TEXTURE, 0, 0, 0, 0, this.width, this.height, this.width, this.height);
    }

    private void applyButtonStyle(Button button, boolean disabled) {
        button.setAlpha(disabled ? 0.55F : 1.0F);
    }

    private void renderButtonFrame(GuiGraphics guiGraphics, @Nullable Button button, boolean disabled) {
        if (button == null || !button.visible) {
            return;
        }
    }

    private void renderHeader(GuiGraphics guiGraphics) {
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_upgrade.title")), 30, 24, 0xFFFFFFFF, false);
        guiGraphics.drawString(nn(this.font), nn(Component.literal("Lv." + cityLevel)), this.width - 78, 24, COLOR_AVAILABLE, false);
        renderLegend(guiGraphics, 150, 24);
    }

    private void renderFooter(GuiGraphics guiGraphics) {
        if (feedbackMessage != null && upgradeCanvas.getSelectedMarker() == null) {
            int x = 118;
            int y = this.height - 31;
            for (FormattedCharSequence line : this.font.split(nn(feedbackMessage), this.width - 150)) {
                guiGraphics.drawString(nn(this.font), nn(line), x, y, feedbackColor, false);
                y += this.font.lineHeight + 1;
                if (y > this.height - 16) {
                    break;
                }
            }
        }
    }

    private void renderLegend(GuiGraphics guiGraphics, int x, int y) {
        drawLegendItem(guiGraphics, x, y, COLOR_COMPLETE, Component.translatable("gui.city_upgrade.status_completed"));
        drawLegendItem(guiGraphics, x + 82, y, COLOR_AVAILABLE, Component.translatable("gui.city_upgrade.status_available"));
        drawLegendItem(guiGraphics, x + 164, y, COLOR_LOCKED, Component.translatable("gui.city_upgrade.status_locked"));
    }

    private void drawLegendItem(GuiGraphics guiGraphics, int x, int y, int color, Component text) {
        guiGraphics.fill(x, y + 3, x + 7, y + 10, color);
        guiGraphics.drawString(nn(this.font), nn(text), x + 11, y + 2, COLOR_TEXT_MUTED, false);
    }

    private void updateAnimation() {
        UpgradeCanvas.MapMarker selectedMarker = upgradeCanvas.getSelectedMarker();
        targetPanelX = selectedMarker != null ? this.width - panelWidth - 18 : this.width;
        if (currentPanelX != targetPanelX) {
            if (currentPanelX < targetPanelX) {
                currentPanelX = Math.min(targetPanelX, currentPanelX + animationSpeed);
            } else {
                currentPanelX = Math.max(targetPanelX, currentPanelX - animationSpeed);
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        UpgradeCanvas.MapMarker selectedMarker = upgradeCanvas.getSelectedMarker();
        boolean isVisible = selectedMarker != null && currentPanelX <= targetPanelX + 8;
        boolean isUpgradeable = selectedMarker != null && parseUpgradeLevel(selectedMarker) == cityLevel + 1;
        int actionWidth = Math.max(88, (panelWidth - 52) / 2);
        int buttonY = this.height - 38;
        submitButton.visible = isVisible;
        cancelButton.visible = isVisible;
        submitButton.active = isUpgradeable && !pendingUpgradeRequest;
        cancelButton.active = !pendingUpgradeRequest;
        applyButtonStyle(submitButton, !submitButton.active);
        applyButtonStyle(cancelButton, !cancelButton.active);
        submitButton.setPosition(currentPanelX + 20, buttonY);
        cancelButton.setPosition(currentPanelX + 30 + actionWidth, buttonY);
    }

    private void renderRightPanel(GuiGraphics guiGraphics) {
        UpgradeCanvas.MapMarker selectedMarker = upgradeCanvas.getSelectedMarker();
        if (selectedMarker == null && currentPanelX >= this.width) {
            return;
        }
        int panelY = 16;
        int panelBottom = this.height - 16;
        renderPanelTexture(guiGraphics, currentPanelX, panelY, panelWidth, panelBottom - panelY);
        if (selectedMarker == null) {
            return;
        }
        int contentLeft = currentPanelX + PANEL_CONTENT_LEFT;
        int contentRight = currentPanelX + panelWidth - PANEL_CONTENT_RIGHT;
        int contentTop = panelY + PANEL_CONTENT_TOP;
        int contentBottom = panelBottom - PANEL_CONTENT_BOTTOM;
        int upgradeLevel = parseUpgradeLevel(selectedMarker);
        CityUpgradeManager.CityUpgrade upgrade = CityUpgradeManager.getInstance().getUpgrade(upgradeLevel);
        int y = contentTop;
        y = renderPanelTitle(guiGraphics, upgradeLevel, upgrade, contentLeft, contentRight, y);
        y = renderStatusCard(guiGraphics, upgradeLevel, contentLeft, contentRight, y);
        y = renderDescriptionCard(guiGraphics, upgrade, contentLeft, contentRight, y, contentBottom);
        y = renderRequirementsCard(guiGraphics, upgrade, contentLeft, contentRight, y, contentBottom);
        y = renderUnlockCard(guiGraphics, upgrade, contentLeft, contentRight, y, contentBottom);
        renderFeedback(guiGraphics, contentLeft, contentRight, y, panelBottom - 60);
    }

    private int renderPanelTitle(GuiGraphics guiGraphics, int upgradeLevel, @Nullable CityUpgradeManager.CityUpgrade upgrade, int contentLeft, int contentRight, int y) {
        int titleColor = getLevelColor(upgradeLevel);
        guiGraphics.drawString(nn(this.font), nn(Component.literal("Lv." + upgradeLevel)), contentLeft, y, titleColor, false);
        String name = upgrade != null ? upgrade.name() : getUpgradeNameFromMarker();
        int levelTextWidth = this.font.width(nn(Component.literal("Lv." + upgradeLevel)));
        guiGraphics.drawString(nn(this.font), nn(Component.literal(safeString(name))), Math.min(contentLeft + levelTextWidth + 16, contentRight - 40), y, 0xFFFFFFFF, false);
        return y + 20;
    }

    private int renderStatusCard(GuiGraphics guiGraphics, int upgradeLevel, int contentLeft, int contentRight, int y) {
        int x = contentLeft;
        int width = Math.max(1, contentRight - contentLeft);
        drawCard(guiGraphics, x, y, width, 30);
        Component status = getStatusText(upgradeLevel);
        int color = getLevelColor(upgradeLevel);
        guiGraphics.drawString(nn(this.font), nn(status), x + 10, y + 10, color, false);
        return y + 38;
    }

    private int renderDescriptionCard(GuiGraphics guiGraphics, @Nullable CityUpgradeManager.CityUpgrade upgrade, int contentLeft, int contentRight, int y, int contentBottom) {
        if (upgrade == null || upgrade.description().isEmpty()) {
            return y;
        }
        int x = contentLeft;
        int width = Math.max(1, contentRight - contentLeft);
        int textWidth = width - 20;
        int lineCount = Math.min(4, this.font.split(nn(Component.literal(safeString(upgrade.description()))), textWidth).size());
        int height = 22 + lineCount * (this.font.lineHeight + 2);
        if (y + height > contentBottom) {
            return contentBottom;
        }
        drawCard(guiGraphics, x, y, width, height);
        guiGraphics.drawString(nn(this.font), nn(Component.literal("城市阶段")), x + 10, y + 8, COLOR_WARN, false);
        drawWrapped(guiGraphics, Component.literal(safeString(upgrade.description())), x + 10, y + 22, textWidth, COLOR_TEXT, y + height - 6);
        return y + height + 8;
    }

    private int renderRequirementsCard(GuiGraphics guiGraphics, @Nullable CityUpgradeManager.CityUpgrade upgrade, int contentLeft, int contentRight, int y, int contentBottom) {
        if (upgrade == null) {
            return y;
        }
        CityUpgradeManager.Requirements requirements = upgrade.requirements();
        int x = contentLeft;
        int width = Math.max(1, contentRight - contentLeft);
        int height = calculateRequirementsHeight(requirements);
        if (y + height > contentBottom) {
            height = Math.max(34, contentBottom - y);
        }
        if (height <= 34) {
            return contentBottom;
        }
        drawCard(guiGraphics, x, y, width, height);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_upgrade.requirements")), x + 10, y + 8, COLOR_WARN, false);
        int rowY = y + 24;
        net.minecraft.world.entity.player.Player player = Minecraft.getInstance().player;
        if (requirements.population() > 0) {
            int current = ClientSimukraftData.getCurrentCityPopulation();
            rowY = drawRequirementText(guiGraphics, Component.translatable("gui.city_upgrade.requirement_population", current, requirements.population()), current >= requirements.population(), x + 14, rowY);
        }
        rowY = drawRequirementItem(guiGraphics, new ItemStack(nn(Items.OAK_LOG)), countItemsInInventory(player, Items.OAK_LOG), requirements.wood(), x + 14, rowY);
        rowY = drawRequirementItem(guiGraphics, new ItemStack(nn(Items.COBBLESTONE)), countItemsInInventory(player, Items.COBBLESTONE), requirements.cobblestone(), x + 14, rowY);
        rowY = drawRequirementItem(guiGraphics, new ItemStack(nn(Items.IRON_INGOT)), countItemsInInventory(player, Items.IRON_INGOT), requirements.ironIngot(), x + 14, rowY);
        rowY = drawRequirementItem(guiGraphics, new ItemStack(nn(Items.GOLD_INGOT)), countItemsInInventory(player, Items.GOLD_INGOT), requirements.goldIngot(), x + 14, rowY);
        rowY = drawRequirementItem(guiGraphics, new ItemStack(nn(Items.DIAMOND)), countItemsInInventory(player, Items.DIAMOND), requirements.diamond(), x + 14, rowY);
        rowY = drawRequirementItem(guiGraphics, new ItemStack(nn(Items.LAPIS_LAZULI)), countItemsInInventory(player, Items.LAPIS_LAZULI), requirements.lapisLazuli(), x + 14, rowY);
        if (requirements.funds() > 0.0) {
            double currentFunds = ClientSimukraftData.getCurrentCityFunds();
            Component text = Component.translatable("gui.city_upgrade.requirement_funds", safeString(String.format(Locale.US, "%.2f", currentFunds)), requirements.funds());
            drawRequirementText(guiGraphics, text, currentFunds >= requirements.funds(), x + 14, rowY);
        }
        return Math.min(contentBottom, y + height + 8);
    }

    private int renderUnlockCard(GuiGraphics guiGraphics, @Nullable CityUpgradeManager.CityUpgrade upgrade, int contentLeft, int contentRight, int y, int contentBottom) {
        if (upgrade == null) {
            return y;
        }
        String unlockText = formatUnlockText(upgrade);
        if (unlockText.isEmpty()) {
            return y;
        }
        int x = contentLeft;
        int width = Math.max(1, contentRight - contentLeft);
        int textWidth = width - 20;
        int lineCount = Math.min(6, this.font.split(nn(Component.literal(unlockText)), textWidth).size());
        int height = 26 + lineCount * (this.font.lineHeight + 3);
        if (y + height > contentBottom) {
            height = Math.max(26, contentBottom - y);
        }
        if (height <= 26) {
            return contentBottom;
        }
        drawCard(guiGraphics, x, y, width, height);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_upgrade.unlocks")), x + 10, y + 8, COLOR_WARN, false);
        drawWrapped(guiGraphics, Component.literal(unlockText), x + 10, y + 24, textWidth, COLOR_TEXT, y + height - 6);
        return Math.min(contentBottom, y + height + 8);
    }

    private String formatUnlockText(CityUpgradeManager.CityUpgrade upgrade) {
        int level = upgrade.level();
        if (level == 0) {
            return "创建城市，获得初始 9 个区块";
        }
        if (level == 1) {
            return "解锁城市升级功能";
        }
        if (level >= 2 && level <= 10) {
            int maxChunks = (2 * level + 1) * (2 * level + 1);
            int prevMaxChunks = level == 2 ? 9 : (2 * (level - 1) + 1) * (2 * (level - 1) + 1);
            int newChunks = maxChunks - prevMaxChunks;
            return String.format("• 可拥有区块: %d → %d (+%d)", prevMaxChunks, maxChunks, newChunks);
        }
        if (level == 11) {
            return "• 可拥有区块: 无限制";
        }
        return "";
    }

    private void renderFeedback(GuiGraphics guiGraphics, int contentLeft, int contentRight, int y, int feedbackTopLimit) {
        if (feedbackMessage == null) {
            return;
        }
        int x = contentLeft;
        int width = Math.max(1, contentRight - contentLeft);
        int top = Math.min(Math.max(y, feedbackTopLimit), feedbackTopLimit);
        drawCard(guiGraphics, x, top, width, 42);
        drawWrapped(guiGraphics, feedbackMessage, x + 10, top + 10, width - 20, feedbackColor, top + 35);
    }

    private int calculateRequirementsHeight(CityUpgradeManager.Requirements requirements) {
        int rows = 0;
        if (requirements.population() > 0) rows++;
        if (requirements.wood() > 0) rows++;
        if (requirements.cobblestone() > 0) rows++;
        if (requirements.ironIngot() > 0) rows++;
        if (requirements.goldIngot() > 0) rows++;
        if (requirements.diamond() > 0) rows++;
        if (requirements.lapisLazuli() > 0) rows++;
        if (requirements.funds() > 0.0) rows++;
        return 34 + rows * 22;
    }

    private int drawRequirementText(GuiGraphics guiGraphics, Component text, boolean pass, int x, int y) {
        guiGraphics.fill(x, y + 3, x + 7, y + 10, pass ? COLOR_AVAILABLE : COLOR_LOCKED);
        guiGraphics.drawString(nn(this.font), nn(text), x + 16, y + 2, pass ? COLOR_AVAILABLE : COLOR_LOCKED, false);
        return y + 22;
    }

    private int drawRequirementItem(GuiGraphics guiGraphics, ItemStack itemStack, int current, int required, int x, int y) {
        if (required <= 0) {
            return y;
        }
        boolean pass = current >= required;
        guiGraphics.renderItem(nn(itemStack), x, y - 2);
        guiGraphics.drawString(nn(this.font), nn(Component.literal(current + " / " + required)), x + 24, y + 2, pass ? COLOR_AVAILABLE : COLOR_LOCKED, false);
        return y + 22;
    }

    private void drawCard(GuiGraphics guiGraphics, int x, int y, int width, int height) {
    }

    private void renderPanelTexture(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderTexture(0, PANEL_BG_TEXTURE);
        guiGraphics.blit(PANEL_BG_TEXTURE, x, y, 0, 0, width, height, width, height);
        RenderSystem.disableBlend();
    }

    private void drawWrapped(GuiGraphics guiGraphics, Component text, int x, int y, int width, int color, int maxY) {
        for (FormattedCharSequence line : this.font.split(nn(text), width)) {
            if (y > maxY) {
                break;
            }
            guiGraphics.drawString(nn(this.font), nn(line), x, y, color, false);
            y += this.font.lineHeight + 2;
        }
    }

    private Component getStatusText(int upgradeLevel) {
        if (upgradeLevel <= cityLevel) {
            return Component.translatable("gui.city_upgrade.status_completed");
        }
        if (upgradeLevel == cityLevel + 1) {
            return Component.translatable("gui.city_upgrade.status_available");
        }
        return Component.translatable("gui.city_upgrade.status_locked");
    }

    private int getLevelColor(int upgradeLevel) {
        if (upgradeLevel <= cityLevel) {
            return COLOR_COMPLETE;
        }
        if (upgradeLevel == cityLevel + 1) {
            return COLOR_AVAILABLE;
        }
        return COLOR_LOCKED;
    }

    private int parseUpgradeLevel(UpgradeCanvas.MapMarker marker) {
        String hoverText = marker.getHoverText();
        if (!hoverText.isEmpty()) {
            try {
                return Integer.parseInt(hoverText.substring(0, hoverText.indexOf(":")));
            } catch (Exception e) {
                LOGGER.error("解析升级等级失败", e);
            }
        }
        return 0;
    }

    private String getUpgradeNameFromMarker() {
        UpgradeCanvas.MapMarker selectedMarker = upgradeCanvas.getSelectedMarker();
        if (selectedMarker == null) {
            return "";
        }
        String hoverText = selectedMarker.getHoverText();
        int index = hoverText.indexOf(":");
        return index >= 0 ? hoverText.substring(index + 1) : hoverText;
    }

    private int countItemsInInventory(net.minecraft.world.entity.player.Player player, net.minecraft.world.item.Item item) {
        if (player == null) return 0;
        int count = 0;
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void handleSubmit() {
        UpgradeCanvas.MapMarker selectedMarker = upgradeCanvas.getSelectedMarker();
        if (selectedMarker == null) {
            feedbackMessage = Component.translatable("message.simukraft.city_upgrade.invalid_target");
            feedbackColor = COLOR_LOCKED;
            return;
        }
        int upgradeLevel = parseUpgradeLevel(selectedMarker);
        if (upgradeLevel != cityLevel + 1) {
            feedbackMessage = Component.translatable("message.simukraft.city_upgrade.invalid_target");
            feedbackColor = COLOR_LOCKED;
            return;
        }
        pendingUpgradeRequest = true;
        feedbackMessage = Component.translatable("gui.city_upgrade.pending");
        feedbackColor = COLOR_WARN;
        com.xiaoliang.simukraft.network.CityUpgradeRequestPacket packet = new com.xiaoliang.simukraft.network.CityUpgradeRequestPacket(cityCorePos, upgradeLevel);
        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(packet);
    }

    private void handleCancel() {
        pendingUpgradeRequest = false;
        feedbackMessage = null;
        upgradeCanvas.setSelectedMarker(null);
    }

    public void handleUpgradeResult(boolean success, Component message) {
        pendingUpgradeRequest = false;
        feedbackMessage = message;
        feedbackColor = success ? COLOR_AVAILABLE : COLOR_LOCKED;
        if (success) {
            closeScreen();
        }
    }

    @Override
    public void onClose() {
        this.closeScreen();
    }

    private void closeScreen() {
        GuiScaleManager.forceRestore();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new CityManagementScreen(cityCorePos));
        }
    }

    private void applyPreferredScale() {
        GuiScaleManager.applyBestFitScale(4, SCALE_FIT_WIDTH, SCALE_FIT_HEIGHT, 12);
    }

    @Override
    public void resize(@Nonnull Minecraft minecraft, int width, int height) {
        applyPreferredScale();
        super.resize(minecraft, width, height);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (GuiScaleManager.handleEscKey(keyCode, this::onClose)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(@Nullable String value) {
        return nn(value);
    }
}
