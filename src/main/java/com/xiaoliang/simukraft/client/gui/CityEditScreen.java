package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import java.util.Objects;

public class CityEditScreen extends AbstractTransitionScreen {
    private final BlockPos cityCorePos;

    public CityEditScreen(BlockPos cityCorePos) {
        super(Component.translatable("gui.city_edit.title"));
        GuiScaleManager.applyFixedScale(2);
        this.cityCorePos = cityCorePos;
        playOpenSound();
    }

    @Override
    protected void init() {
        GuiScaleManager.applyFixedScale(2);
        super.init();
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // 创建返回按钮
        Button backButton = nn(Button.builder(
            nn(Component.translatable("gui.city_edit.back")),
            button -> this.closeScreen()
        ).pos(centerX - 50, centerY + 60).size(100, 20).build());
        this.addRenderableWidget(backButton);
        
        // 创建删除城市按钮（红色）
        Button deleteButton = nn(Button.builder(
            nn(Component.translatable("gui.city_edit.delete_city")),
            button -> this.openDeleteConfirmation()
        ).pos(centerX - 50, centerY + 90).size(100, 20).build());
        this.addRenderableWidget(deleteButton);
    }
    
    private void openDeleteConfirmation() {
        // 打开确认界面
        ConfirmationScreen.openForCityDelete(
            cityCorePos,
            confirmed -> {
                if (confirmed) {
                    // 用户确认删除，发送删除请求
                    System.out.println("[CityEditScreen] 用户确认删除城市: " + cityCorePos);
                    com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                        new com.xiaoliang.simukraft.network.DeleteCityPacket(cityCorePos)
                    );
                    // 关闭当前界面
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(null);
                    }
                }
            }
        );
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics safeGuiGraphics = nn(guiGraphics);
        var font = nn(this.font);
        super.render(safeGuiGraphics, mouseX, mouseY, partialTicks);
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // 渲染标题
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_edit.title")), centerX - 50, centerY - 80, 0xFFFFFF);
        
        // 渲染功能区域占位符
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_edit.placeholder")), centerX - 80, centerY - 20, 0x888888);
    }

    @Override
    public void onClose() {
        this.closeScreen();
    }

    private void closeScreen() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new CityManagementScreen(cityCorePos));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void resize(@Nonnull Minecraft minecraft, int width, int height) {
        GuiScaleManager.applyFixedScale(2);
        super.resize(minecraft, width, height);
    }

    private static void playOpenSound() {
        Minecraft.getInstance().getSoundManager().play(
                nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F))
        );
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }
}
