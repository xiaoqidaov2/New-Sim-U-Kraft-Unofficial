package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * 指南书ui
 * 使用轻量绘制实现蓝色书皮和打开过渡
 */
public class GuideBookScreen extends Screen {
    private static final ResourceLocation COVER_ICON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Simukraft.MOD_ID, "textures/gui/guide_book_cover_icon.png");
    private static final int COVER_FRONT_BORDER_COLOR = 0x081B38;
    private static final int COVER_FRONT_FILL_COLOR = 0x1B447D;
    private static final int COVER_BACK_FILL_COLOR = 0x173867;
    private static final int COVER_SPINE_COLOR = 0x0E2B55;
    private static final int COVER_DECORATION_COLOR = 0xA9D0FF;
    private static final int BOOK_WIDTH = 332;
    private static final int BOOK_HEIGHT = 214;
    private static final int CLOSED_BOOK_WIDTH = 174;
    private static final int CLOSED_BOOK_HEIGHT = 232;
    private static final int BOOK_MARGIN = 12;
    private static final int PAGE_GAP = 10;
    private static final int OPEN_TICKS = 16;
    private static final int CLOSE_BUTTON_WIDTH = 56;
    private static final int CLOSE_BUTTON_HEIGHT = 20;

    private final ItemStack previewStack;
    private float alpha;
    private final float transitionSpeed = 0.05F;
    private int openTicks;
    private Button closeButton;
    private BookState bookState = BookState.COVER;

    public GuideBookScreen(ItemStack previewStack) {
        super(nn(Component.translatable("item.simukraft.guide_book")));
        this.previewStack = nn(previewStack);
    }

    @Override
    protected void init() {
        int buttonX = this.width / 2 + BOOK_WIDTH / 2 - CLOSE_BUTTON_WIDTH - 10;
        int buttonY = this.height / 2 + BOOK_HEIGHT / 2 - CLOSE_BUTTON_HEIGHT - 8;
        this.closeButton = this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.guide_book.close")),
                        button -> this.onClose())
                .pos(buttonX, buttonY)
                .size(CLOSE_BUTTON_WIDTH, CLOSE_BUTTON_HEIGHT)
                .build()));
        updateCloseButtonState();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.bookState == BookState.OPENING && this.openTicks < OPEN_TICKS) {
            this.openTicks++;
            if (this.openTicks >= OPEN_TICKS) {
                this.bookState = BookState.OPENED;
                updateCloseButtonState();
            }
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.alpha < 1.0F) {
            this.alpha = Math.min(1.0F, this.alpha + this.transitionSpeed);
        }

        drawBackground(guiGraphics);

        if (this.bookState == BookState.COVER) {
            renderClosedCover(guiGraphics);
        } else {
            float openProgress = this.bookState == BookState.OPENED
                    ? 1.0F
                    : Mth.clamp((this.openTicks + partialTick) / OPEN_TICKS, 0.0F, 1.0F);
            float easedProgress = 1.0F - (1.0F - openProgress) * (1.0F - openProgress);
            renderBook(guiGraphics, easedProgress);
            renderBookText(guiGraphics, easedProgress);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderClosedCover(GuiGraphics guiGraphics) {
        var font = nn(this.font);
        int bookX = this.width / 2 - CLOSED_BOOK_WIDTH / 2;
        int bookY = this.height / 2 - CLOSED_BOOK_HEIGHT / 2;
        int bookRight = bookX + CLOSED_BOOK_WIDTH;
        int bookBottom = bookY + CLOSED_BOOK_HEIGHT;

        guiGraphics.fill(bookX + 8, bookY + 10, bookRight + 10, bookBottom + 12, withAlpha(0x000000, 0.34F * this.alpha));
        drawPanel(guiGraphics, bookX, bookY, CLOSED_BOOK_WIDTH, CLOSED_BOOK_HEIGHT,
                withAlpha(COVER_FRONT_BORDER_COLOR, this.alpha), withAlpha(COVER_FRONT_FILL_COLOR, this.alpha));

        int spineWidth = 18;
        guiGraphics.fill(bookX + 6, bookY + 4, bookX + 6 + spineWidth, bookBottom - 4, withAlpha(COVER_SPINE_COLOR, this.alpha));
        guiGraphics.fill(bookX + 11, bookY + 12, bookX + 13, bookBottom - 12, withAlpha(COVER_DECORATION_COLOR, 0.85F * this.alpha));
        guiGraphics.fill(bookX + 26, bookY + 18, bookRight - 20, bookY + 20, withAlpha(COVER_DECORATION_COLOR, 0.70F * this.alpha));
        guiGraphics.fill(bookX + 26, bookBottom - 24, bookRight - 20, bookBottom - 22, withAlpha(COVER_DECORATION_COLOR, 0.70F * this.alpha));

        renderCoverPreview(guiGraphics, bookX + 22, bookY + 10, CLOSED_BOOK_WIDTH - 34, CLOSED_BOOK_HEIGHT - 20, this.alpha);

        int hintColor = withAlpha(0xD9E9FF, 0.92F * this.alpha);
        guiGraphics.drawCenteredString(font, nn(Component.translatable("gui.guide_book.cover_subtitle")),
                this.width / 2, bookBottom + 10, hintColor);
        guiGraphics.drawCenteredString(font, nn(Component.translatable("gui.guide_book.open_hint")),
                this.width / 2, bookBottom + 24, hintColor);
    }

    private void renderBook(GuiGraphics guiGraphics, float progress) {
        int currentWidth = Math.round(Mth.lerp(progress, BOOK_WIDTH * 0.68F, BOOK_WIDTH));
        int currentHeight = Math.round(Mth.lerp(progress, BOOK_HEIGHT * 0.82F, BOOK_HEIGHT));
        int bookX = this.width / 2 - currentWidth / 2;
        int bookY = this.height / 2 - currentHeight / 2;
        int bookRight = bookX + currentWidth;
        int bookBottom = bookY + currentHeight;
        int centerX = this.width / 2;

        int shadowAlpha = withAlpha(0x000000, 0.35F * progress);
        guiGraphics.fill(bookX + 6, bookY + 8, bookRight + 8, bookBottom + 10, shadowAlpha);

        drawPanel(guiGraphics, bookX + 8, bookY + 6, currentWidth - 16, currentHeight - 12,
                withAlpha(0xA09274, 0.45F * progress), withAlpha(0xE7DCC3, 0.28F * progress));

        int innerY = bookY + BOOK_MARGIN;
        int innerWidth = currentWidth - BOOK_MARGIN * 2;
        int innerHeight = currentHeight - BOOK_MARGIN * 2;
        int centerGapLeft = centerX - PAGE_GAP / 2;
        int centerGapRight = centerX + PAGE_GAP / 2;
        int pageWidth = Math.max(10, (innerWidth - PAGE_GAP) / 2);
        int visiblePageWidth = Math.max(4, Math.round(pageWidth * progress));

        guiGraphics.fill(centerX - 3, innerY - 2, centerX + 3, innerY + innerHeight + 2, withAlpha(COVER_SPINE_COLOR, progress));
        guiGraphics.fill(centerX - 1, innerY - 2, centerX + 1, innerY + innerHeight + 2, withAlpha(COVER_DECORATION_COLOR, progress));

        int leftPageX = centerGapLeft - visiblePageWidth;
        int rightPageX = centerGapRight;
        drawPanel(guiGraphics, leftPageX, innerY, visiblePageWidth, innerHeight,
                withAlpha(0xC2B79B, progress), withAlpha(0xF4EBD5, progress));
        drawPanel(guiGraphics, rightPageX, innerY, visiblePageWidth, innerHeight,
                withAlpha(0xC2B79B, progress), withAlpha(0xF7EFD9, progress));

        for (int i = 0; i < 7; i++) {
            int lineY = innerY + 24 + i * 20;
            int lineColor = withAlpha(0xD5C7A8, 0.55F * progress);
            guiGraphics.fill(leftPageX + 12, lineY, leftPageX + visiblePageWidth - 12, lineY + 1, lineColor);
            guiGraphics.fill(rightPageX + 12, lineY, rightPageX + visiblePageWidth - 12, lineY + 1, lineColor);
        }

        float coverProgress = Mth.clamp(progress / 0.92F, 0.0F, 1.0F);
        int coverWidth = Math.max(16, Math.round(Mth.lerp(coverProgress, pageWidth + 18.0F, 18.0F)));
        int leftCoverClosedX = centerGapLeft - coverWidth;
        int rightCoverClosedX = centerGapRight;
        int leftCoverOpenX = bookX - 4;
        int rightCoverOpenX = bookRight - coverWidth + 4;
        int leftCoverX = Math.round(Mth.lerp(coverProgress, leftCoverClosedX, leftCoverOpenX));
        int rightCoverX = Math.round(Mth.lerp(coverProgress, rightCoverClosedX, rightCoverOpenX));

        drawPanel(guiGraphics, leftCoverX, innerY - 4, coverWidth, innerHeight + 8,
                withAlpha(COVER_FRONT_BORDER_COLOR, progress), withAlpha(COVER_BACK_FILL_COLOR, progress));
        guiGraphics.fill(leftCoverX + 6, innerY + 14, leftCoverX + coverWidth - 6, innerY + 16,
                withAlpha(COVER_DECORATION_COLOR, 0.42F * progress));

        drawPanel(guiGraphics, rightCoverX, innerY - 4, coverWidth, innerHeight + 8,
                withAlpha(COVER_FRONT_BORDER_COLOR, progress), withAlpha(COVER_FRONT_FILL_COLOR, progress));
        guiGraphics.fill(rightCoverX + 8, innerY + 10, rightCoverX + coverWidth - 8, innerY + 12,
                withAlpha(COVER_DECORATION_COLOR, 0.75F * progress));

    }

    private void renderCoverPreview(GuiGraphics guiGraphics, int x, int y, int width, int height, float progress) {
        var font = nn(this.font);
        int centerX = x + width / 2;
        int titleColor = withAlpha(0xF3F8FF, progress);
        int subtitleColor = withAlpha(0xD7E6FB, progress);
        int accentColor = withAlpha(COVER_DECORATION_COLOR, progress);
        int iconSize = Math.max(20, Math.min(42, width - 24));
        int iconX = centerX - iconSize / 2;
        int iconY = y + 18;

        guiGraphics.fill(x + 10, y + 66, x + width - 10, y + 67, accentColor);
        guiGraphics.fill(x + 10, y + height - 22, x + width - 10, y + height - 21, accentColor);
        guiGraphics.blit(nn(COVER_ICON_TEXTURE), iconX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
        guiGraphics.drawCenteredString(font, nn(Component.translatable("gui.guide_book.cover_title")), centerX, y + 74, titleColor);
        guiGraphics.drawCenteredString(font, nn(Component.translatable("gui.guide_book.cover_title_en")), centerX, y + 90, subtitleColor);

        if (!this.previewStack.isEmpty()) {
            guiGraphics.renderItem(nn(this.previewStack), x + 10, y + height - 20);
        }
    }

    private void renderBookText(GuiGraphics guiGraphics, float progress) {
        if (progress < 0.45F) {
            return;
        }

        var font = nn(this.font);
        float textProgress = Mth.clamp((progress - 0.45F) / 0.55F, 0.0F, 1.0F);
        int centerX = this.width / 2;
        int bookX = centerX - BOOK_WIDTH / 2;
        int bookY = this.height / 2 - BOOK_HEIGHT / 2;
        int leftPageX = bookX + BOOK_MARGIN + 12;
        int rightPageX = centerX + PAGE_GAP / 2 + 12;
        int pageY = bookY + BOOK_MARGIN + 14;
        int textWidth = (BOOK_WIDTH - BOOK_MARGIN * 2 - PAGE_GAP) / 2 - 24;

        int titleColor = withAlpha(0x23406D, textProgress);
        int bodyColor = withAlpha(0x3E3427, textProgress);
        int hintColor = withAlpha(0x5A7298, textProgress);

        guiGraphics.drawString(font, nn(Component.translatable("gui.guide_book.left_title")), leftPageX, pageY, titleColor, false);
        guiGraphics.drawString(font, nn(Component.translatable("gui.guide_book.right_title")), rightPageX, pageY, titleColor, false);

        int leftTextY = pageY + 20;
        leftTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.left_1")), leftPageX, leftTextY, textWidth, bodyColor, 4);
        leftTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.left_2")), leftPageX, leftTextY, textWidth, bodyColor, 4);
        drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.left_3")), leftPageX, leftTextY, textWidth, bodyColor, 0);

        int rightTextY = pageY + 20;
        rightTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.right_1")), rightPageX, rightTextY, textWidth, bodyColor, 4);
        rightTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.right_2")), rightPageX, rightTextY, textWidth, bodyColor, 4);
        rightTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.right_3")), rightPageX, rightTextY, textWidth, bodyColor, 10);
        drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.hint")), rightPageX, rightTextY, textWidth, hintColor, 0);
    }

    private int drawWrappedText(GuiGraphics guiGraphics, Component component, int x, int y, int width, int color, int bottomPadding) {
        var font = nn(this.font);
        List<FormattedCharSequence> lines = nn(font.split(nn(component), width));
        int currentY = y;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(font, nn(line), x, currentY, color, false);
            currentY += 12;
        }
        return currentY + bottomPadding;
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    private void drawPanel(GuiGraphics guiGraphics, int x, int y, int width, int height, int borderColor, int fillColor) {
        if (width <= 0 || height <= 0) {
            return;
        }
        guiGraphics.fill(x, y, x + width, y + height, fillColor);
        guiGraphics.fill(x, y, x + width, y + 2, borderColor);
        guiGraphics.fill(x, y + height - 2, x + width, y + height, borderColor);
        guiGraphics.fill(x, y + 2, x + 2, y + height - 2, borderColor);
        guiGraphics.fill(x + width - 2, y + 2, x + width, y + height - 2, borderColor);
    }

    private void drawBackground(GuiGraphics guiGraphics) {
        int backgroundColor = withAlpha(0x000000, 0.78F * this.alpha);
        guiGraphics.fillGradient(0, 0, this.width, this.height, backgroundColor, backgroundColor);
    }

    private int withAlpha(int rgb, float alphaValue) {
        int alphaInt = Mth.clamp((int) (alphaValue * 255.0F), 0, 255);
        return alphaInt << 24 | rgb;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.bookState == BookState.COVER && (button == 0 || button == 1)) {
            beginOpenAnimation();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void beginOpenAnimation() {
        if (this.bookState != BookState.COVER) {
            return;
        }
        this.bookState = BookState.OPENING;
        this.openTicks = 0;
        updateCloseButtonState();
    }

    private void updateCloseButtonState() {
        if (this.closeButton == null) {
            return;
        }
        boolean opened = this.bookState == BookState.OPENED;
        this.closeButton.visible = opened;
        this.closeButton.active = opened;
    }

    private enum BookState {
        COVER,
        OPENING,
        OPENED
    }
}
