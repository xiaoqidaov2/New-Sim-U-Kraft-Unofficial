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
 * 可视化拖拽调整HUD位置 - 改进版
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class HUDPositionEditorScreen extends Screen {

    private final Screen parent;
    
    // 使用绝对像素坐标而不是偏移值
    private int hudAbsoluteX, hudAbsoluteY;
    private boolean isDragging = false;
    private int dragStartMouseX, dragStartMouseY;
    private int dragStartHudX, dragStartHudY;
    
    // 按钮
    private Button saveButton;
    private Button resetButton;
    private Button cancelButton;
    
    // 预览文本
    private String previewText;
    private int previewTextWidth;

    public HUDPositionEditorScreen(Screen parent) {
        super(Component.translatable("gui.hud_editor.title"));
        this.parent = parent;

        // simukraft: 进入此界面时恢复原始缩放
        GuiScaleManager.restore();

        // 创建预览文本
        this.previewText = createPreviewText();
    }
    
    @Override
    protected void init() {
        super.init();
        
        // 计算预览文本宽度
        this.previewTextWidth = this.font.width(previewText);
        
        // 将配置中的偏移值转换为绝对坐标
        calculateAbsolutePositionFromConfig();
        
        // 底部按钮 - 放在屏幕上方避免被物品栏遮挡
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonY = this.height - 100; // 距离底部100像素，避免物品栏
        int centerX = this.width / 2;
        int spacing = 20; // 增加按钮间距

        // 保存按钮
        this.saveButton = Button.builder(
                Component.translatable("gui.hud_editor.save"),
                button -> saveAndClose()
        ).bounds(centerX - buttonWidth * 3 / 2 - spacing, buttonY, buttonWidth, buttonHeight).build();

        // 重置按钮
        this.resetButton = Button.builder(
                Component.translatable("gui.hud_editor.reset"),
                button -> resetPosition()
        ).bounds(centerX - buttonWidth / 2, buttonY, buttonWidth, buttonHeight).build();

        // 取消按钮
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
        
        // 确保在屏幕范围内
        hudAbsoluteX = clamp(hudAbsoluteX, 0, this.width - previewTextWidth);
        hudAbsoluteY = clamp(hudAbsoluteY, 0, this.height - this.font.lineHeight);
    }
    
    /**
     * 将绝对坐标转换为配置值（锚点+偏移）
     * 简化处理：统一使用TOP_LEFT锚点，直接存储绝对坐标作为偏移
     */
    private void saveAbsolutePositionToConfig() {
        // 切换到TOP_LEFT锚点，直接存储绝对坐标
        ClientConfig.HUD_ANCHOR.set(ClientConfig.Anchor.TOP_LEFT.name());
        ClientConfig.HUD_POS_X.set(hudAbsoluteX);
        ClientConfig.HUD_POS_Y.set(hudAbsoluteY);
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
                "拖拽HUD预览调整位置",
                this.width / 2,
                40,
                0xAAAAAA
        );
        
        // 渲染当前位置信息
        guiGraphics.drawCenteredString(
                this.font,
                String.format("X: %d, Y: %d", hudAbsoluteX, hudAbsoluteY),
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
        
        // 渲染网格线帮助对齐
        renderGridLines(guiGraphics);
        
        // 渲染按钮
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
    
    private void renderGridLines(GuiGraphics guiGraphics) {
        // 渲染淡色网格线帮助对齐
        int gridColor = 0x22FFFFFF;
        int gridSize = 50;
        
        // 垂直线
        for (int x = 0; x < this.width; x += gridSize) {
            guiGraphics.fill(x, 0, x + 1, this.height, gridColor);
        }
        
        // 水平线
        for (int y = 0; y < this.height; y += gridSize) {
            guiGraphics.fill(0, y, this.width, y + 1, gridColor);
        }
        
        // 中心线
        guiGraphics.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x44FF0000);
        guiGraphics.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x44FF0000);
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
            
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    private void saveAndClose() {
        saveAbsolutePositionToConfig();
        onClose();
    }

    private void resetPosition() {
        // 重置到屏幕右上角默认位置
        hudAbsoluteX = this.width - previewTextWidth - 5;
        hudAbsoluteY = 5;
    }

    @Override
    public void onClose() {
        // simukraft: 返回上级菜单前重新应用3x缩放
        GuiScaleManager.apply3x();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
