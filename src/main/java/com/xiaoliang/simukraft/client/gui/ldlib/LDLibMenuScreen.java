package com.xiaoliang.simukraft.client.gui.ldlib;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.xiaoliang.simukraft.client.gui.GuiScaleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * simukraft: LDLib菜单屏幕基类
 * 继承自原版Screen而非AbstractContainerScreen，使其他模组将其识别为普通菜单界面
 * 内部使用LDLib的ModularUI进行渲染
 *
 * 原理：
 * 1. 继承Screen避免被识别为容器界面（如箱子）
 * 2. 内部持有ModularUI实例处理LDLib渲染
 * 3. 手动转发鼠标/键盘事件到LDLib组件
 * 4. 支持GuiScaleManager自动缩放
 *
 * 注意：由于LDLib的Widget内部会调用getModularUIGui()，我们在init()中创建一个假的ModularUIGuiContainer
 * 来满足LDLib的要求，但这个假的容器不会实际显示或使用
 */
@OnlyIn(Dist.CLIENT)
public abstract class LDLibMenuScreen extends Screen {

    protected ModularUI modularUI;
    protected final Screen parent;
    protected int guiLeft;
    protected int guiTop;

    protected LDLibMenuScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /**
     * 创建ModularUI，子类必须实现
     */
    protected abstract ModularUI createModularUI();

    /**
     * 获取UI宽度
     */
    protected abstract int getUIWidth();

    /**
     * 获取UI高度
     */
    protected abstract int getUIHeight();

    /**
     * 是否启用自动缩放，默认true
     * 子类可以覆盖此方法禁用缩放
     */
    protected boolean enableAutoScale() {
        return true;
    }

    /**
     * 获取首选缩放级别，默认3
     * 子类可以覆盖此方法调整首选缩放
     */
    protected int getPreferredScale() {
        return 3;
    }

    @Override
    protected void init() {
        // simukraft: 在super.init()之前应用缩放，确保尺寸计算正确
        if (enableAutoScale()) {
            GuiScaleManager.applyBestFitScale(getPreferredScale(), getUIWidth(), getUIHeight(), 16);
        }

        super.init();

        updateLayoutMetrics();

        // 创建ModularUI
        if (this.modularUI == null) {
            this.modularUI = createModularUI();
        }

        // 初始化LDLib组件
        if (this.modularUI != null) {
            // 创建一个假的ModularUIGuiContainer来满足LDLib的要求
            // 这不会实际显示，只是为了让Widget可以调用getModularUIGui()而不返回null
            if (this.modularUI.getModularUIGui() == null) {
                new FakeModularUIGuiContainer(Objects.requireNonNull(this.modularUI));
            }
            this.modularUI.initWidgets();
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 渲染背景（半透明黑色）
        this.renderBackground(graphics);

        // 转换鼠标坐标到UI相对坐标
        int uiMouseX = mouseX - guiLeft;
        int uiMouseY = mouseY - guiTop;

        // 使用LDLib渲染
        if (modularUI != null) {
            // simukraft: 设置渲染状态，与ModularUIGuiContainer保持一致
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);

            graphics.pose().pushPose();
            graphics.pose().translate(guiLeft, guiTop, 0);

            // 获取根组件并渲染
            WidgetGroup mainGroup = modularUI.mainGroup;
            if (mainGroup != null) {
                // 渲染背景和文字（LabelWidget在drawInBackground中渲染文字）
                mainGroup.drawInBackground(graphics, uiMouseX, uiMouseY, partialTicks);
                // 渲染前景（工具提示等）
                mainGroup.drawInForeground(graphics, uiMouseX, uiMouseY, partialTicks);
            }

            graphics.pose().popPose();

            // 恢复渲染状态
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        }

        // 渲染工具提示
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public void resize(@Nonnull Minecraft minecraft, int width, int height) {
        if (enableAutoScale()) {
            GuiScaleManager.applyBestFitScale(getPreferredScale(), getUIWidth(), getUIHeight(), 16);
        }
        super.resize(minecraft, width, height);
        updateLayoutMetrics();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (modularUI != null) {
            int uiMouseX = (int) mouseX - guiLeft;
            int uiMouseY = (int) mouseY - guiTop;

            WidgetGroup mainGroup = modularUI.mainGroup;
            if (mainGroup != null) {
                // 检查是否在UI范围内并处理点击
                if (uiMouseX >= 0 && uiMouseX < getUIWidth() &&
                    uiMouseY >= 0 && uiMouseY < getUIHeight()) {
                    mainGroup.mouseClicked(uiMouseX, uiMouseY, button);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (modularUI != null) {
            int uiMouseX = (int) mouseX - guiLeft;
            int uiMouseY = (int) mouseY - guiTop;
            WidgetGroup mainGroup = modularUI.mainGroup;
            if (mainGroup != null) {
                mainGroup.mouseReleased(uiMouseX, uiMouseY, button);
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (modularUI != null) {
            int uiMouseX = (int) mouseX - guiLeft;
            int uiMouseY = (int) mouseY - guiTop;
            WidgetGroup mainGroup = modularUI.mainGroup;
            if (mainGroup != null) {
                mainGroup.mouseDragged(uiMouseX, uiMouseY, button, dragX, dragY);
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // simukraft: 滚轮事件直接传递给父类处理
        // LDLib的WidgetGroup没有标准的滚轮处理方法
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (modularUI != null) {
            WidgetGroup mainGroup = modularUI.mainGroup;
            if (mainGroup != null) {
                mainGroup.keyReleased(keyCode, scanCode, modifiers);
            }
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (modularUI != null) {
            WidgetGroup mainGroup = modularUI.mainGroup;
            if (mainGroup != null && mainGroup.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        if (modularUI != null) {
            WidgetGroup mainGroup = modularUI.mainGroup;
            if (mainGroup != null) {
                mainGroup.updateScreen();
            }
        }
    }

    @Override
    public void onClose() {
        // simukraft: 恢复原始缩放
        if (enableAutoScale()) {
            GuiScaleManager.restore();
        }

        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // simukraft: 使用GuiScaleManager统一处理ESC键，确保恢复缩放
        if (enableAutoScale() && GuiScaleManager.handleEscKey(keyCode, this::onClose)) {
            return true;
        }

        if (modularUI != null) {
            WidgetGroup mainGroup = modularUI.mainGroup;
            if (mainGroup != null && mainGroup.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 获取父屏幕
     */
    public Screen getParent() {
        return parent;
    }

    /**
     * 获取GUI左侧位置
     */
    public int getGuiLeft() {
        return guiLeft;
    }

    /**
     * 获取GUI顶部位置
     */
    public int getGuiTop() {
        return guiTop;
    }

    /**
     * 获取ModularUI实例
     */
    public ModularUI getModularUI() {
        return modularUI;
    }

    private void updateLayoutMetrics() {
        Minecraft mc = Minecraft.getInstance();
        this.width = mc.getWindow().getGuiScaledWidth();
        this.height = mc.getWindow().getGuiScaledHeight();
        this.guiLeft = (this.width - getUIWidth()) / 2;
        this.guiTop = (this.height - getUIHeight()) / 2;
    }

    /**
     * 基础UIHolder实现
     */
    protected static class MenuUIHolder implements IUIHolder {
        @Override
        public ModularUI createUI(Player entityPlayer) {
            return null;
        }

        @Override
        public boolean isInvalid() {
            return false;
        }

        @Override
        public boolean isRemote() {
            return true;
        }

        @Override
        public void markAsDirty() {
        }
    }
}
