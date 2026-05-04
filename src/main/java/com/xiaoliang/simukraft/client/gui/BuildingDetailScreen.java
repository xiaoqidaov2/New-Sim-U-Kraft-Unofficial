package com.xiaoliang.simukraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import javax.annotation.Nonnull;
import java.util.Objects;

public class BuildingDetailScreen extends AbstractTransitionScreen {
    private final BuildingListScreen.BuildingResponseInfo building;
    private final Screen parent;
    private final BlockPos buildBoxPos;

    public BuildingDetailScreen(BuildingListScreen.BuildingResponseInfo building, Screen parent, BlockPos buildBoxPos) {
        super(Component.translatable("gui.building_detail.title", building.name()));
        this.building = building;
        this.parent = parent;
        this.buildBoxPos = buildBoxPos;
    }

    @Override
    protected void init() {
        super.init();

        final Minecraft minecraft = this.minecraft;
        int centerX = this.width / 2;
        int buttonY = this.height - 35;
        int buttonWidth = 60;
        int buttonSpacing = 20;

        // 返回按钮 - 居中左侧
        Button backButton = nn(Button.builder(
                nn(Component.translatable("gui.back")),
                button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(parent);
                    }
                }
            ).bounds(centerX - buttonWidth - buttonSpacing / 2, buttonY, buttonWidth, 20)
            .build());
        this.addRenderableWidget(backButton);

        // 预览按钮 - 居中右侧
        Button previewButton = nn(Button.builder(
                nn(Component.translatable("gui.button.preview")),
                button -> {
                    if (minecraft != null) {
                        System.out.println("[BuildingDetailScreen] Opening preview for: " + building.name());
                        minecraft.setScreen(new BuildingPreviewScreen(building, this, buildBoxPos));
                    }
                })
            .bounds(centerX + buttonSpacing / 2, buttonY, buttonWidth, 20)
            .build());
        this.addRenderableWidget(previewButton);
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics safeGuiGraphics = nn(guiGraphics);
        var font = nn(this.font);

        // 渲染半透明背景
        safeGuiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        int centerX = this.width / 2;

        // 绘制标题
        safeGuiGraphics.drawCenteredString(
            font,
            nn(Component.translatable("gui.building_detail.title")),
            centerX,
            25,
            0xFFFFFF
        );

        // 绘制建筑师状态
        safeGuiGraphics.drawCenteredString(
            font,
            nn(Component.translatable("gui.building_detail.status_architect")),
            centerX,
            45,
            0xFFADD8E6
        );

        // 绘制建筑信息区域
        int infoStartY = 75;
        int lineHeight = 20;

        // 建筑名称
        safeGuiGraphics.drawCenteredString(
            font,
            nn(Component.translatable("gui.building.name", building.name())),
            centerX,
            infoStartY,
            0xFFFF00
        );

        // 建筑描述
        safeGuiGraphics.drawCenteredString(
            font,
            nn(Component.translatable("gui.building.description", building.description())),
            centerX,
            infoStartY + lineHeight,
            0xFFFF00
        );

        // 作者
        safeGuiGraphics.drawCenteredString(
            font,
            nn(Component.translatable("gui.building.author", building.author())),
            centerX,
            infoStartY + lineHeight * 2,
            0xFFFF00
        );

        // 费用
        safeGuiGraphics.drawCenteredString(
            font,
            nn(Component.translatable("gui.building.cost", building.amount())),
            centerX,
            infoStartY + lineHeight * 3,
            0xFFFF00
        );

        // 尺寸
        safeGuiGraphics.drawCenteredString(
            font,
            nn(Component.translatable("gui.building.size", building.size())),
            centerX,
            infoStartY + lineHeight * 4,
            0xFFFF00
        );

        super.render(safeGuiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Minecraft minecraft = this.minecraft;
        if (keyCode == 256) { // ESC键
            if (minecraft != null) {
                minecraft.setScreen(parent);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }
}
