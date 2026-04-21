package com.xiaoliang.simukraft.client.gui.ldlib;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Tuple;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * simukraft: 假的ModularUIGuiContainer
 * 用于LDLibMenuScreen中，满足LDLib Widget对getModularUIGui()的调用需求
 * 这个类不会实际显示，只是作为ModularUI的持有者
 */
@OnlyIn(Dist.CLIENT)
public class FakeModularUIGuiContainer extends ModularUIGuiContainer {

    public final ModularUI modularUI;
    public Widget lastFocus;
    public boolean focused;
    public int dragSplittingLimit;
    public int dragSplittingButton;
    @Nullable
    public List<Component> tooltipTexts;
    @Nullable
    public TooltipComponent tooltipComponent;
    @Nullable
    public Font tooltipFont;
    @Nullable
    public ItemStack tooltipStack = ItemStack.EMPTY;
    protected Tuple<Object, IGuiTexture> draggingElement;
    protected int pressedButton = -1;

    public FakeModularUIGuiContainer(ModularUI modularUI) {
        // 调用父类构造函数，但传入一个假的container
        super(modularUI, 0);
        this.modularUI = modularUI;
        // 重新设置modularUIGui，因为父类构造函数已经设置过了
        // 但我们不需要这样做，因为父类已经设置了
    }

    @Override
    public void setHoverTooltip(List<Component> tooltipTexts, ItemStack tooltipStack, @Nullable Font tooltipFont, @Nullable TooltipComponent tooltipComponent) {
        this.tooltipTexts = tooltipTexts;
        this.tooltipStack = tooltipStack;
        this.tooltipFont = tooltipFont;
        this.tooltipComponent = tooltipComponent;
    }

    @Override
    public boolean setDraggingElement(Object element, IGuiTexture renderer) {
        if (draggingElement != null) return false;
        draggingElement = new Tuple<>(element, renderer);
        return true;
    }

    @Override
    @Nullable
    public Object getDraggingElement() {
        if (draggingElement == null) return null;
        return draggingElement.getA();
    }

    @Override
    public void init() {
        // 不调用父类的init，避免创建实际的GUI
        this.imageWidth = modularUI.getWidth();
        this.imageHeight = modularUI.getHeight();
        this.modularUI.updateScreenSize(width, height);
    }

    @Override
    protected void renderBg(@Nonnull GuiGraphics graphics, float delta, int mouseX, int mouseY) {
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

    public boolean isButtonPressed(int button) {
        return pressedButton == button;
    }

    public void setPressedButton(int button) {
        this.pressedButton = button;
    }

    public boolean getQuickCrafting() {
        return false;
    }

    public Set<Slot> getQuickCraftSlots() {
        return Set.of();
    }

    public void superMouseClicked(double mouseX, double mouseY, int button) {
        // 空实现
    }

    public void superMouseReleased(double mouseX, double mouseY, int button) {
        // 空实现
    }

    public void superMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // 空实现
    }
}
