package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.client.freecamera.FreeCameraManager;
import com.xiaoliang.simukraft.client.preview.BuildingPreviewManager;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.StartConstructionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;

@SuppressWarnings("null")
public class BuildingPreviewScreen extends Screen {
    private final BuildingListScreen.BuildingResponseInfo building;
    private final Screen parent;
    private final BlockPos buildBoxPos;

    private boolean mouseGrabbed = false;

    public BuildingPreviewScreen(BuildingListScreen.BuildingResponseInfo building, Screen parent, BlockPos buildBoxPos) {
        super(Component.translatable("gui.building_preview.title"));
        this.building = building;
        this.parent = parent;
        this.buildBoxPos = buildBoxPos;
    }

    @Override
    protected void init() {
        super.init();

        // 计算预览初始原点：建筑盒东北方向（+X, +Z）
        BlockPos previewOrigin = buildBoxPos.offset(1, 0, 1);

        // 启动预览
        String fileNameWithoutExt = building.fileName().replaceFirst("\\.[^.]+$", "");
        BuildingPreviewManager.startPreview(
            building.name(),
            building.category(),
            fileNameWithoutExt,
            building.size(),
            previewOrigin
        );

        // 设置当前玩家为预览放置者（menglannnn: 用于限制侵入检测仅放置者可见）
        if (this.minecraft != null && this.minecraft.player != null) {
            BuildingBoundsRenderer.setPreviewPlayerId(this.minecraft.player.getUUID());
        }

        // 激活自由相机
        FreeCameraManager.activate();
    }

    @Override
    public void added() {
        super.added();
        // 在屏幕添加后捕获鼠标
        if (this.minecraft != null) {
            this.minecraft.mouseHandler.grabMouse();
            mouseGrabbed = true;
        }
    }

    @Override
    public void removed() {
        super.removed();
        // 确保在屏幕移除时释放鼠标
        if (this.minecraft != null && mouseGrabbed) {
            this.minecraft.mouseHandler.releaseMouse();
            mouseGrabbed = false;
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 完全透明背景
        // 不渲染任何背景，让玩家可以看到游戏世界和预览图

        // 渲染提示信息
        int centerX = this.width / 2;
        int y = 10;

        // 标题
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("gui.building_preview.title_with_name", building.name()),
            centerX,
            y,
            0xFFFFFF
        );

        y += 15;

        // 操作提示 - 预览图移动
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("gui.building_preview.hint.preview_move"),
            centerX,
            y,
            0xFFFF00
        );

        y += 12;

        // 操作提示 - 自由相机
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("gui.building_preview.hint.camera"),
            centerX,
            y,
            0x00FFFF
        );

        y += 12;

        // 其他操作
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("gui.building_preview.hint.actions"),
            centerX,
            y,
            0xFFAA00
        );

        y += 12;

        // 当前位置信息
        BlockPos origin = BuildingPreviewManager.getPreviewOrigin();
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("gui.building_preview.origin_info", origin.getX(), origin.getY(), origin.getZ(), BuildingPreviewManager.getRotation()),
            centerX,
            y,
            0x00FF00
        );

        y += 12;

        if (BuildingPreviewManager.isRangeOnlyPreview()) {
            guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("gui.building_preview.range_only_mode"),
                centerX,
                y,
                0xFFAA00
            );

            y += 12;

            guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("gui.building_preview.estimated_volume", BuildingPreviewManager.getEstimatedVolume()),
                centerX,
                y,
                0x00FFFF
            );
        } else {
            guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("gui.building_preview.block_count", BuildingPreviewManager.getBlockCount()),
                centerX,
                y,
                0x00FFFF
            );
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC键退出
        if (keyCode == 256) {
            exitPreview();
            return true;
        }

        // 方向键移动预览图（基于相机方向）
        switch (keyCode) {
            case 265: // UP - 向前（基于相机朝向）
                BuildingPreviewManager.movePreviewRelativeToCamera(0, 1);
                return true;
            case 264: // DOWN - 向后（基于相机朝向）
                BuildingPreviewManager.movePreviewRelativeToCamera(0, -1);
                return true;
            case 263: // LEFT - 向左（基于相机朝向）
                BuildingPreviewManager.movePreviewRelativeToCamera(-1, 0);
                return true;
            case 262: // RIGHT - 向右（基于相机朝向）
                BuildingPreviewManager.movePreviewRelativeToCamera(1, 0);
                return true;
            case 334: // PLUS/ADD - 向上
            case 61:  // =/+ key
                BuildingPreviewManager.movePreviewVertical(1);
                return true;
            case 333: // MINUS/SUBTRACT - 向下
            case 45:  // - key
                BuildingPreviewManager.movePreviewVertical(-1);
                return true;
            case 82:  // R - 旋转
                BuildingPreviewManager.rotatePreview();
                return true;
            case 257: // ENTER - 开始建造
                // 检查整个建筑范围是否都在领地内
                if (!BuildingPreviewManager.isEntireBuildingInCityTerritory()) {
                    // 显示错误提示（使用红色）
                    if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.displayClientMessage(
                            Component.translatable("message.simukraft.construction.outside_city"), true);
                    }
                    return true; // 消耗按键但不执行建造
                }
                startConstruction();
                return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void exitPreview() {
        var minecraft = this.minecraft;
        BuildingPreviewManager.clearPreview();
        FreeCameraManager.deactivate();

        // 清除预览放置者标识（menglannnn: 退出预览后不再显示侵入检测）
        BuildingBoundsRenderer.setPreviewPlayerId(null);

        // 释放鼠标
        if (minecraft != null && mouseGrabbed) {
            minecraft.mouseHandler.releaseMouse();
            mouseGrabbed = false;
        }

        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private void startConstruction() {
        var minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }

        // 发送建造请求到服务器
        BlockPos previewOrigin = BuildingPreviewManager.getPreviewOrigin();
        int rotation = BuildingPreviewManager.getRotation();

        // 使用文件名（去掉扩展名）而不是显示名称，以便服务器正确匹配建筑
        String fileNameWithoutExt = building.fileName().replaceFirst("\\.[^.]+$", "");

        NetworkManager.INSTANCE.sendToServer(new StartConstructionPacket(
            fileNameWithoutExt,
            building.category(),
            buildBoxPos,
            previewOrigin,
            rotation
        ));

        // 关闭预览并清除所有界面
        BuildingPreviewManager.clearPreview();
        FreeCameraManager.deactivate();

        // 清除预览放置者标识（menglannnn: 放置完成后不再显示侵入检测）
        BuildingBoundsRenderer.setPreviewPlayerId(null);

        // 释放鼠标
        if (mouseGrabbed) {
            minecraft.mouseHandler.releaseMouse();
            mouseGrabbed = false;
        }

        // 关闭所有界面（返回游戏）
        minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
