package com.xiaoliang.simukraft.client.gui.ldlib;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Tuple;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * simukraft: 假的ModularUIGuiContainer
 * 用于LDLibMenuScreen中，满足LDLib Widget对getModularUIGui()的调用需求
 * 这个类不会实际显示，只是作为ModularUI的持有者
 */
@OnlyIn(Dist.CLIENT)
public class FakeModularUIGuiContainer extends ModularUIGuiContainer {

    public FakeModularUIGuiContainer(@Nonnull ModularUI modularUI) {
        // 调用父类构造函数，但传入一个假的container
        super(Objects.requireNonNull(modularUI), 0);
    }

    @Override
    public void setHoverTooltip(List<Component> tooltipTexts, ItemStack tooltipStack, @Nullable Font tooltipFont, @Nullable TooltipComponent tooltipComponent) {
        this.tooltipTexts = nn(tooltipTexts);
        this.tooltipStack = nn(tooltipStack);
        this.tooltipFont = tooltipFont;
        this.tooltipComponent = tooltipComponent;
    }

    @Override
    public boolean setDraggingElement(Object element, IGuiTexture renderer) {
        if (draggingElement != null) return false;
        draggingElement = new Tuple<Object, IGuiTexture>(nn(element), nn(renderer));
        return true;
    }

    @Override
    @Nullable
    public Object getDraggingElement() {
        if (draggingElement == null) return null;
        return nn(draggingElement.getA());
    }

    @Override
    public void init() {
        // 不调用父类的init，避免创建实际的GUI
        ModularUI ui = nn(this.modularUI);
        this.imageWidth = ui.getWidth();
        this.imageHeight = ui.getHeight();
        ui.updateScreenSize(width, height);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        // 空实现，不渲染背景
    }

    @Override
    public void removed() {
        // 不调用父类的removed，避免关闭容器
    }

    @Override
    public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 空实现，不渲染
    }

    @Override
    protected void renderLabels(@Nonnull GuiGraphics graphics, int mouseX, int mouseY) {
        // 空实现
    }

    @Override
    protected void renderTooltip(@Nonnull GuiGraphics graphics, int x, int y) {
        // 空实现
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // 空实现，不关闭
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }
}
