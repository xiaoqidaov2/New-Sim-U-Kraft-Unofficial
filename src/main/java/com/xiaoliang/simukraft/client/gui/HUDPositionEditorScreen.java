package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.client.ClientSimukraftData;
import com.xiaoliang.simukraft.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * HUD位置编辑器
 * 6区域分区拖拽：左上/右上/左下/右下/上中/下中
 * 拖拽到对应区域自动切换锚点，使用与屏幕边缘的相对位置
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class HUDPositionEditorScreen extends Screen {

    // HUD绝对坐标
    private int hudAbsoluteX, hudAbsoluteY;
    private boolean isDragging = false;
    private int dragStartMouseX, dragStartMouseY;
    private int dragStartHudX, dragStartHudY;

    // 当前检测到的锚点区域（拖拽时实时更新）
    private ClientConfig.Anchor currentAnchor = ClientConfig.Anchor.TOP_RIGHT;

    // 按钮
    private Button saveButton;
    private Button resetButton;
    private Button cancelButton;

    // 预览文本
    private String previewText;
    private int previewTextWidth;

    // 区域分割线位置（屏幕宽高的1/3和2/3处）
    private int regionX1, regionX2, regionY2;

    public HUDPositionEditorScreen(Screen parent) {
        super(Component.translatable("gui.hud_editor.title"));
        // simukraft: 进入此界面时恢复原始缩放
        GuiScaleManager.restore();
        this.previewText = createPreviewText();
    }

    @Override
    protected void init() {
        super.init();

        this.previewTextWidth = this.font.width(previewText);

        // 计算区域分割线（屏幕平分为6个区域）
        // 水平方向：左/中/右三等分
        regionX1 = this.width / 3;
        regionX2 = this.width * 2 / 3;
        // 垂直方向：上/下两等分
        regionY2 = this.height / 2;

        // 从配置加载并计算绝对坐标
        calculateAbsolutePositionFromConfig();

        // 底部按钮
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonY = this.height - 100;
        int centerX = this.width / 2;
        int spacing = 20;

        this.saveButton = Button.builder(
                Component.translatable("gui.hud_editor.save"),
                button -> saveAndClose()
        ).bounds(centerX - buttonWidth * 3 / 2 - spacing, buttonY, buttonWidth, buttonHeight).build();

        this.resetButton = Button.builder(
                Component.translatable("gui.hud_editor.reset"),
                button -> resetPosition()
        ).bounds(centerX - buttonWidth / 2, buttonY, buttonWidth, buttonHeight).build();

        this.cancelButton = Button.builder(
                Component.translatable("gui.hud_editor.cancel"),
                button -> onClose()
        ).bounds(centerX + buttonWidth / 2 + spacing, buttonY, buttonWidth, buttonHeight).build();

        this.addRenderableWidget(this.saveButton);
        this.addRenderableWidget(this.resetButton);
        this.addRenderableWidget(this.cancelButton);
    }

    private String createPreviewText() {
        int currentDay = ClientSimukraftData.getCurrentDay();
        String cityName = ClientSimukraftData.getCurrentCityName();
        double funds = ClientSimukraftData.getCurrentCityFunds();
        int worldPopulation = ClientSimukraftData.getCurrentPopulation();
        int cityPopulation = ClientSimukraftData.getCurrentCityPopulation();

        String[] weekdays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String weekDay = weekdays[(Math.max(1, currentDay) - 1) % 7];

        if (cityName != null && !cityName.isEmpty()) {
            return String.format("%s | 资金: %.2f | %s | 世界人口: %d | 城市人口: %d",
                    cityName, funds, weekDay, worldPopulation, cityPopulation);
        } else {
            return String.format("%s | 世界人口: %d", weekDay, worldPopulation);
        }
    }

    /**
     * 根据配置的锚点和偏移值计算绝对坐标
     */
    private void calculateAbsolutePositionFromConfig() {
        ClientConfig.Anchor anchor = ClientConfig.getAnchor();
        this.currentAnchor = anchor;
        int offsetX = ClientConfig.getPosX();
        int offsetY = ClientConfig.getPosY();

        switch (anchor) {
            case TOP_LEFT -> {
                hudAbsoluteX = offsetX;
                hudAbsoluteY = offsetY;
            }
            case TOP_RIGHT -> {
                hudAbsoluteX = this.width - previewTextWidth + offsetX;
                hudAbsoluteY = offsetY;
            }
            case BOTTOM_LEFT -> {
                hudAbsoluteX = offsetX;
                hudAbsoluteY = this.height - 10 + offsetY;
            }
            case BOTTOM_RIGHT -> {
                hudAbsoluteX = this.width - previewTextWidth + offsetX;
                hudAbsoluteY = this.height - 10 + offsetY;
            }
            case TOP_CENTER -> {
                hudAbsoluteX = (this.width - previewTextWidth) / 2 + offsetX;
                hudAbsoluteY = offsetY;
            }
            case BOTTOM_CENTER -> {
                hudAbsoluteX = (this.width - previewTextWidth) / 2 + offsetX;
                hudAbsoluteY = this.height - 10 + offsetY;
            }
            default -> {
                hudAbsoluteX = this.width - previewTextWidth - 5;
                hudAbsoluteY = 5;
            }
        }

        hudAbsoluteX = clamp(hudAbsoluteX, 0, this.width - previewTextWidth);
        hudAbsoluteY = clamp(hudAbsoluteY, 0, this.height - this.font.lineHeight);
    }

    /**
     * 根据HUD中心点位置检测所在区域，返回对应锚点
     * 屏幕分为6个区域：左上/右上/左下/右下/上中/下中
     */
    private ClientConfig.Anchor detectAnchorFromPosition(int centerX, int centerY) {
        boolean left = centerX < regionX1;
        boolean right = centerX >= regionX2;
        boolean top = centerY < regionY2;

        if (top && left) return ClientConfig.Anchor.TOP_LEFT;
        if (top && right) return ClientConfig.Anchor.TOP_RIGHT;
        if (!top && left) return ClientConfig.Anchor.BOTTOM_LEFT;
        if (!top && right) return ClientConfig.Anchor.BOTTOM_RIGHT;
        if (top) return ClientConfig.Anchor.TOP_CENTER;
        return ClientConfig.Anchor.BOTTOM_CENTER;
    }

    /**
     * 根据锚点和绝对坐标计算相对偏移值
     */
    private int[] calculateOffsetFromAnchor(ClientConfig.Anchor anchor, int absX, int absY) {
        int offsetX, offsetY;
        switch (anchor) {
            case TOP_LEFT -> {
                offsetX = absX;
                offsetY = absY;
            }
            case TOP_RIGHT -> {
                offsetX = absX - (this.width - previewTextWidth);
                offsetY = absY;
            }
            case BOTTOM_LEFT -> {
                offsetX = absX;
                offsetY = absY - (this.height - 10);
            }
            case BOTTOM_RIGHT -> {
                offsetX = absX - (this.width - previewTextWidth);
                offsetY = absY - (this.height - 10);
            }
            case TOP_CENTER -> {
                offsetX = absX - (this.width - previewTextWidth) / 2;
                offsetY = absY;
            }
            case BOTTOM_CENTER -> {
                offsetX = absX - (this.width - previewTextWidth) / 2;
                offsetY = absY - (this.height - 10);
            }
            default -> {
                offsetX = absX;
                offsetY = absY;
            }
        }
        return new int[]{offsetX, offsetY};
    }

    /**
     * 保存配置：使用当前检测到的锚点和相对偏移
     */
    private void saveAbsolutePositionToConfig() {
        int[] offsets = calculateOffsetFromAnchor(currentAnchor, hudAbsoluteX, hudAbsoluteY);
        ClientConfig.HUD_ANCHOR.set(currentAnchor.name());
        ClientConfig.HUD_POS_X.set(offsets[0]);
        ClientConfig.HUD_POS_Y.set(offsets[1]);
        ClientConfig.SPEC.save();
        ClientConfig.clearCache();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 渲染半透明背景
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);

        // 渲染区域分割线（6区域网格）
        renderRegionLines(guiGraphics);

        // 渲染标题
        guiGraphics.drawCenteredString(
                Objects.requireNonNull(this.font),
                this.title,
                this.width / 2,
                20,
                0xFFFFFF
        );

        // 渲染提示文字
        guiGraphics.drawCenteredString(
                this.font,
                "拖拽HUD到对应区域自动切换锚点",
                this.width / 2,
                40,
                0xAAAAAA
        );

        // 渲染当前锚点信息
        String anchorName = switch (currentAnchor) {
            case TOP_LEFT -> "左上";
            case TOP_RIGHT -> "右上";
            case BOTTOM_LEFT -> "左下";
            case BOTTOM_RIGHT -> "右下";
            case TOP_CENTER -> "上中";
            case BOTTOM_CENTER -> "下中";
        };
        guiGraphics.drawCenteredString(
                this.font,
                String.format("当前锚点: %s | X: %d, Y: %d", anchorName, hudAbsoluteX, hudAbsoluteY),
                this.width / 2,
                55,
                0xFFFFAA
        );

        // 渲染HUD背景框
        int padding = 4;
        guiGraphics.renderOutline(
                hudAbsoluteX - padding,
                hudAbsoluteY - padding,
                previewTextWidth + padding * 2,
                this.font.lineHeight + padding * 2,
                isDragging ? 0xFF00FF00 : 0xFF4A90A4
        );
        guiGraphics.fill(
                hudAbsoluteX - padding,
                hudAbsoluteY - padding,
                hudAbsoluteX + previewTextWidth + padding,
                hudAbsoluteY + this.font.lineHeight + padding,
                0x66000000
        );

        // 渲染HUD预览文本
        guiGraphics.drawString(this.font, previewText, hudAbsoluteX, hudAbsoluteY, 0xFFFFFF, true);

        // 渲染拖拽提示
        if (isMouseOverHUD(mouseX, mouseY) || isDragging) {
            guiGraphics.drawString(this.font, "✋", hudAbsoluteX + previewTextWidth / 2 - 5, hudAbsoluteY - 20, 0xFFFF00, true);
        }

        // 渲染按钮
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 渲染6区域分割线和高亮
     * 6区域布局：左上/右上/左下/右下/上中/下中
     */
    private void renderRegionLines(GuiGraphics guiGraphics) {
        int lineColor = 0x44FFFFFF;
        int activeColor = 0x88FFAA00;

        // 垂直分割线
        guiGraphics.fill(regionX1, 0, regionX1 + 1, this.height, lineColor);
        guiGraphics.fill(regionX2, 0, regionX2 + 1, this.height, lineColor);

        // 水平分割线（只有一条，在regionY2处分割上下）
        guiGraphics.fill(0, regionY2, this.width, regionY2 + 1, lineColor);

        // 高亮当前锚点所在区域
        int highlightX, highlightY, highlightW, highlightH;
        switch (currentAnchor) {
            case TOP_LEFT -> {
                highlightX = 0; highlightY = 0;
                highlightW = regionX1; highlightH = regionY2;
            }
            case TOP_RIGHT -> {
                highlightX = regionX2; highlightY = 0;
                highlightW = this.width - regionX2; highlightH = regionY2;
            }
            case BOTTOM_LEFT -> {
                highlightX = 0; highlightY = regionY2;
                highlightW = regionX1; highlightH = this.height - regionY2;
            }
            case BOTTOM_RIGHT -> {
                highlightX = regionX2; highlightY = regionY2;
                highlightW = this.width - regionX2; highlightH = this.height - regionY2;
            }
            case TOP_CENTER -> {
                highlightX = regionX1; highlightY = 0;
                highlightW = regionX2 - regionX1; highlightH = regionY2;
            }
            case BOTTOM_CENTER -> {
                highlightX = regionX1; highlightY = regionY2;
                highlightW = regionX2 - regionX1; highlightH = this.height - regionY2;
            }
            default -> {
                highlightX = 0; highlightY = 0;
                highlightW = 0; highlightH = 0;
            }
        }
        if (highlightW > 0 && highlightH > 0) {
            guiGraphics.fill(highlightX, highlightY, highlightX + highlightW, highlightY + highlightH, activeColor);
        }
    }

    private boolean isMouseOverHUD(int mouseX, int mouseY) {
        int padding = 6;
        return mouseX >= hudAbsoluteX - padding &&
               mouseX <= hudAbsoluteX + previewTextWidth + padding &&
               mouseY >= hudAbsoluteY - padding &&
               mouseY <= hudAbsoluteY + this.font.lineHeight + padding;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOverHUD((int) mouseX, (int) mouseY)) {
            isDragging = true;
            dragStartMouseX = (int) mouseX;
            dragStartMouseY = (int) mouseY;
            dragStartHudX = hudAbsoluteX;
            dragStartHudY = hudAbsoluteY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (isDragging) {
            // 计算新的绝对位置（基于拖拽起点）
            int newX = dragStartHudX + (int) (mouseX - dragStartMouseX);
            int newY = dragStartHudY + (int) (mouseY - dragStartMouseY);

            // 限制在屏幕范围内
            hudAbsoluteX = clamp(newX, 0, this.width - previewTextWidth);
            hudAbsoluteY = clamp(newY, 0, this.height - this.font.lineHeight);

            // 实时检测所在区域并切换锚点（使用HUD中心点）
            int centerX = hudAbsoluteX + previewTextWidth / 2;
            int centerY = hudAbsoluteY + this.font.lineHeight / 2;
            currentAnchor = detectAnchorFromPosition(centerX, centerY);

            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    private void saveAndClose() {
        saveAbsolutePositionToConfig();
        // simukraft: 保存后直接返回到主界面，不经过上级菜单
        GuiScaleManager.forceRestore();
        Minecraft.getInstance().setScreen(null);
    }

    private void resetPosition() {
        // simukraft: 重置为右上角锚点，偏移5,5
        ClientConfig.HUD_ANCHOR.set(ClientConfig.Anchor.TOP_RIGHT.name());
        ClientConfig.HUD_POS_X.set(-5);
        ClientConfig.HUD_POS_Y.set(5);
        ClientConfig.SPEC.save();
        ClientConfig.clearCache();
        // 重新计算绝对坐标
        calculateAbsolutePositionFromConfig();
    }

    @Override
    public void onClose() {
        // simukraft: 所有关闭方式都直接返回到主界面，不经过上级菜单
        GuiScaleManager.forceRestore();
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
