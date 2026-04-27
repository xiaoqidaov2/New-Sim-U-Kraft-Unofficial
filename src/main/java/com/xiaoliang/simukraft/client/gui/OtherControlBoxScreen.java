package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 其他控制盒GUI
 * 简单的控制盒界面，仅显示基本信息和拆除按钮
 */
public class OtherControlBoxScreen extends Screen {

    private final BlockPos controlBoxPos;

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    public OtherControlBoxScreen(BlockPos pos) {
        super(Component.translatable("gui.other_control_box.title"));
        this.controlBoxPos = pos;

        // 播放建筑盒打开界面音效
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
            .play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));
    }

    /**
     * 获取控制盒位置
     */
    public BlockPos getControlBoxPos() {
        return controlBoxPos;
    }

    @Override
    protected void init() {
        super.init();

        // 完成按钮
        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.done")),
                        button -> this.onClose())
                .bounds(5, 5, 45, 20)
                .build()));

        // simukraft: 拆除按钮（右上角）
        int demolishBtnWidth = 60;
        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.demolish")),
                        button -> this.onDemolishClicked())
                .bounds(this.width - demolishBtnWidth - 5, 5, demolishBtnWidth, 20)
                .build()));
    }

    /**
     * 点击拆除按钮处理
     */
    private void onDemolishClicked() {
        // 发送拆除请求到服务器
        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
            new com.xiaoliang.simukraft.network.DemolishBuildingPacket(controlBoxPos)
        );
        // 关闭界面
        this.onClose();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 黑色半透明背景 - 与建筑盒相同的背景
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        // 白色标题：建筑控制面板
        int titleColor = 0xFFFFFF;
        Component title = Component.translatable("gui.other_control_box.panel_title").withStyle(style -> style.withColor(titleColor));
        guiGraphics.drawCenteredString(nn(this.font), nn(title), this.width / 2, 10, titleColor);

        // 黄色文字内容
        int textColor = 0xFFF5F5A0;

        // 第一行：建筑信息
        Component line1 = Component.translatable("gui.other_control_box.info").withStyle(style -> style.withColor(textColor));
        guiGraphics.drawString(nn(this.font), nn(line1), 10, 35, textColor, false);

        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC键关闭界面
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
