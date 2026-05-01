package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.client.freecamera.FreeCameraManager;
import com.xiaoliang.simukraft.client.preview.AreaSelectionManager;
import com.xiaoliang.simukraft.client.preview.FarmlandAreaPreviewManager;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nonnull;

/**
 * 区域选择界面 - 自由相机模式下的 HUD 界面
 */
@SuppressWarnings("null")
public class AreaSelectionScreen extends Screen {
    private final BlockPos buildBoxPos;
    private final Screen parent;
    private final AreaSelectionManager.SelectionMode mode;
    private boolean mouseGrabbed = false;

    public AreaSelectionScreen(BlockPos buildBoxPos, Screen parent, AreaSelectionManager.SelectionMode mode) {
        super(Component.translatable("gui.plan_area.title"));
        this.buildBoxPos = buildBoxPos;
        this.parent = parent;
        this.mode = mode;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSoundEvents.BUILD_BOX_OPEN.get(), 1.0F));
    }

    @Override
    protected void init() {
        super.init();

        // 启动区域选择
        AreaSelectionManager.startSelection(mode);
        if (mode == AreaSelectionManager.SelectionMode.FARMLAND) {
            FarmlandAreaPreviewManager.stopPreview();
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
        // 透明背景，让玩家可以看到游戏世界和选择区域

        int centerX = this.width / 2;
        int y = 10;

        // 标题
        String modeKey = switch (mode) {
            case REPLACE -> "gui.area_selection.mode.replace";
            case FILL -> "gui.area_selection.mode.fill";
            case REMOVE -> "gui.area_selection.mode.remove";
            case FARMLAND -> "gui.area_selection.mode.farmland";
            default -> "";
        };

        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("gui.area_selection.title", Component.translatable(modeKey)),
            centerX,
            y,
            0xFFFFFF
        );

        y += 15;

        // 操作提示
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("gui.area_selection.hint.controls"),
            centerX,
            y,
            0xFFFFFF
        );

        y += 12;

        // 自由相机提示
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("gui.area_selection.hint.camera"),
            centerX,
            y,
            0x00FFFF
        );

        y += 15;

        // 显示点一信息
        BlockPos p1 = AreaSelectionManager.getPoint1();
        Component p1Component = p1 != null ?
            Component.translatable("gui.area_selection.point1.set", p1.getX(), p1.getY(), p1.getZ()) :
            Component.translatable("gui.area_selection.point1.not_set");
        guiGraphics.drawCenteredString(this.font, p1Component, centerX, y, 0xFFFFFF);

        y += 12;

        // 显示点二信息
        BlockPos p2 = AreaSelectionManager.getPoint2();
        Component p2Component = p2 != null ?
            Component.translatable("gui.area_selection.point2.set", p2.getX(), p2.getY(), p2.getZ()) :
            Component.translatable("gui.area_selection.point2.not_set");
        guiGraphics.drawCenteredString(this.font, p2Component, centerX, y, 0xFFFFFF);

        y += 12;

        // 显示区域信息
        if (AreaSelectionManager.hasBothPoints()) {
            int count = AreaSelectionManager.getSelectedArea().size();
            guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("gui.area_selection.selected_area", count),
                centerX,
                y,
                0x00FF00
            );
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC键退出
        if (keyCode == 256) {
            exitSelection();
            return true;
        }

        // Enter键确认选择
        if (keyCode == 257) {
            if (AreaSelectionManager.hasBothPoints()) {
                confirmSelection();
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 确认选择，根据模式打开相应的界面
     */
    private void confirmSelection() {
        if (!AreaSelectionManager.hasBothPoints()) return;

        BlockPos p1 = AreaSelectionManager.getPoint1();
        BlockPos p2 = AreaSelectionManager.getPoint2();
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }

        switch (mode) {
            case REMOVE -> {
                // 获取选区内的所有方块
                java.util.List<BlockPos> selectedBlocks = AreaSelectionManager.getSelectedArea();
                // 打开拆除确认界面
                minecraft.setScreen(new RemoveBlocksConfirmScreen(buildBoxPos, this, selectedBlocks));
            }
            case REPLACE -> {
                // 打开方块替换界面，需要选择一个箱子
                openBlockReplacementScreen(p1, p2);
            }
            case FILL -> {
                // 打开方块填充界面，需要选择一个箱子
                openBlockFillScreen(p1, p2);
            }
            case FARMLAND -> confirmFarmlandSelection(p1, p2);
            case LOGISTICS, NONE -> {
                return;
            }
        }
    }

    /**
     * 打开方块替换界面
     */
    private void openBlockReplacementScreen(BlockPos p1, BlockPos p2) {
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }

        // 释放鼠标和退出自由相机模式
        if (mouseGrabbed) {
            minecraft.mouseHandler.releaseMouse();
            mouseGrabbed = false;
        }
        FreeCameraManager.deactivate();
        AreaSelectionManager.endSelection();

        // 打开箱子选择界面，在建筑盒六个面紧贴范围内搜索箱子
        minecraft.setScreen(new BlockReplacementChestSelectScreen(p1, p2, buildBoxPos, parent));
    }

    /**
     * 打开方块填充界面
     */
    private void openBlockFillScreen(BlockPos p1, BlockPos p2) {
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }

        // 释放鼠标和退出自由相机模式
        if (mouseGrabbed) {
            minecraft.mouseHandler.releaseMouse();
            mouseGrabbed = false;
        }
        FreeCameraManager.deactivate();
        AreaSelectionManager.endSelection();

        // 打开箱子选择界面（填充模式），在建筑盒六个面紧贴范围内搜索箱子
        minecraft.setScreen(new BlockReplacementChestSelectScreen(p1, p2, buildBoxPos, parent,
                BlockReplacementChestSelectScreen.Mode.FILL));
    }

    private void confirmFarmlandSelection(BlockPos p1, BlockPos p2) {
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }

        FarmlandPlot plot = FarmlandPlot.fromCorners(p1, p2);
        BlockPos overlappingBox = FarmlandData.findOverlappingPlotOwner(buildBoxPos, plot);
        if (overlappingBox != null) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.simukraft.farming.area_overlap", overlappingBox.getX(), overlappingBox.getY(), overlappingBox.getZ()),
                        false
                );
            }
            return;
        }
        FarmlandData.setSelectedPlot(buildBoxPos, plot);
        FarmlandAreaPreviewManager.startPreview(buildBoxPos, plot);

        if (mouseGrabbed) {
            minecraft.mouseHandler.releaseMouse();
            mouseGrabbed = false;
        }
        FreeCameraManager.deactivate();
        AreaSelectionManager.endSelection();
        minecraft.setScreen(parent instanceof FarmlandBoxScreen ? parent : new FarmlandBoxScreen(buildBoxPos));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 处理鼠标点击选择点
        handleMouseClick(button);
        return true;
    }

    /**
     * 处理鼠标点击（用于区域选择）
     * 使用自由相机的位置和方向进行射线检测
     */
    private void handleMouseClick(int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 获取自由相机的位置和方向
        Vec3 cameraPos = FreeCameraManager.getPosition();
        float yaw = FreeCameraManager.getYaw();
        float pitch = FreeCameraManager.getPitch();

        // 计算视线方向
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);

        Vec3 lookDir = new Vec3(x, y, z).normalize();

        // 射线检测距离（增加检测距离到 100 格）
        double reachDistance = 100.0;
        Vec3 endPos = cameraPos.add(lookDir.x * reachDistance, lookDir.y * reachDistance, lookDir.z * reachDistance);

        // 执行射线检测
        BlockHitResult hitResult = mc.level.clip(new ClipContext(
            cameraPos,
            endPos,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            mc.player
        ));

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hitResult.getBlockPos();

            if (button == 0) { // 左键 - 点一（红色）
                AreaSelectionManager.setPoint1(pos);
            } else if (button == 1) { // 右键 - 点二（黄色）
                AreaSelectionManager.setPoint2(pos);
            }
        }
    }

    private void exitSelection() {
        Minecraft minecraft = this.minecraft;
        AreaSelectionManager.endSelection();
        FreeCameraManager.deactivate();

        // 释放鼠标
        if (minecraft != null && mouseGrabbed) {
            minecraft.mouseHandler.releaseMouse();
            mouseGrabbed = false;
        }

        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
