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
 * NSUK-银行控制箱GUI
 * 简洁的界面，只有标题、完成按钮和背景
 */
public class BankControlBoxScreen extends Screen {

    private final BlockPos controlBoxPos;

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    public BankControlBoxScreen(BlockPos pos) {
        super(Component.translatable("gui.bank_control_box.title"));
        this.controlBoxPos = pos;

        // 播放建筑盒打开界面音效
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
            .play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));
    }

    @Override
    protected void init() {
        super.init();

        // 完成按钮 - 左上角，与住宅控制箱一致
        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.done")),
                        button -> this.onClose())
                .bounds(5, 5, 45, 20)
                .build()));
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 黑色半透明背景
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        // 白色标题 - 顶部居中，与住宅控制箱一致
        int titleColor = 0xFFFFFF;
        Component title = Component.translatable("gui.bank_control_box.panel_title").withStyle(style -> style.withColor(titleColor));
        guiGraphics.drawCenteredString(nn(this.font), nn(title), this.width / 2, 10, titleColor);

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
