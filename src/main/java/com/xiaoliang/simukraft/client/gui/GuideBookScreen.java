package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.client.gui.GuiGraphics;
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
    private static final ResourceLocation TAB_CITY_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Simukraft.MOD_ID, "textures/gui/guide_book/tab_city.png");
    private static final ResourceLocation TAB_COMMERCE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Simukraft.MOD_ID, "textures/gui/guide_book/tab_commerce.png");
    private static final ResourceLocation TAB_LOGISTICS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Simukraft.MOD_ID, "textures/gui/guide_book/tab_logistics.png");
    private static final ResourceLocation TAB_RETURN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Simukraft.MOD_ID, "textures/gui/guide_book/tab_return.png");
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
    private static final int TAB_TEXTURE_WIDTH = 16;
    private static final int TAB_TEXTURE_HEIGHT = 9;
    private static final int RETURN_TEXTURE_WIDTH = 15;
    private static final int RETURN_TEXTURE_HEIGHT = 32;
    private static final int TAB_SCALE = 3;
    private static final float RETURN_SCALE = 1.8F;
    private static final int TAB_WIDTH = TAB_TEXTURE_WIDTH * TAB_SCALE;
    private static final int TAB_HEIGHT = TAB_TEXTURE_HEIGHT * TAB_SCALE;
    private static final int TAB_SPACING = 15;
    private static final int TAB_EXPOSED_WIDTH = 18;
    private static final int TAB_BASE_Y_OFFSET = 20;
    private static final int TAB_HOVER_SHIFT_X = 5;
    private static final int TAB_SELECTED_SHIFT_X = 7;
    private static final int TAB_HOVER_SHIFT_Y = -1;
    private static final int TAB_SELECTED_SHIFT_Y = -2;
    private static final float TAB_REVEAL_START_PROGRESS = 0.80F;
    private static final int TAB_HIDDEN_OFFSET_X = TAB_EXPOSED_WIDTH + 8;
    private static final int RETURN_WIDTH = Math.round(RETURN_TEXTURE_WIDTH * RETURN_SCALE);
    private static final int RETURN_HEIGHT = Math.round(RETURN_TEXTURE_HEIGHT * RETURN_SCALE);
    private static final int RETURN_TOP_OVERLAP = 7;
    private static final int RETURN_HOVER_SHIFT_Y = 1;
    private static final int TAB_SELECTED_MARKER_COLOR = 0xD6B11F;
    private static final int TAB_SELECTED_MARKER_BORDER_COLOR = 0xFFF1A8;

    private final ItemStack previewStack;
    private float alpha;
    private final float transitionSpeed = 0.05F;
    private int openTicks;
    private BookState bookState = BookState.COVER;
    private GuidePage currentPage = GuidePage.DIRECTORY;

    public GuideBookScreen(ItemStack previewStack) {
        super(nn(Component.translatable("item.simukraft.guide_book")));
        this.previewStack = nn(previewStack);
    }

    @Override
    protected void init() {
    }

    @Override
    public void tick() {
        super.tick();
        if (this.bookState == BookState.OPENING && this.openTicks < OPEN_TICKS) {
            this.openTicks++;
            if (this.openTicks >= OPEN_TICKS) {
                this.bookState = BookState.OPENED;
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
            renderBook(guiGraphics, easedProgress, mouseX, mouseY);
            renderBookText(guiGraphics, easedProgress, mouseX, mouseY);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderClosedCover(GuiGraphics guiGraphics) {
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
    }

    private void renderBook(GuiGraphics guiGraphics, float progress, int mouseX, int mouseY) {
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

        float tabRevealProgress = getTabRevealProgress(progress);
        if (tabRevealProgress > 0.0F) {
            int openedBookX = this.width / 2 - BOOK_WIDTH / 2;
            int openedBookY = this.height / 2 - BOOK_HEIGHT / 2;
            renderTabs(guiGraphics, openedBookX, openedBookY, mouseX, mouseY, tabRevealProgress);
            if (this.currentPage != GuidePage.DIRECTORY) {
                renderReturnButton(guiGraphics, openedBookX, openedBookY, mouseX, mouseY);
            }
        }

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

    }

    private void renderCoverPreview(GuiGraphics guiGraphics, int x, int y, int width, int height, float progress) {
        int centerX = x + width / 2;
        int titleColor = withAlpha(0xF7D44A, progress);
        int subtitleColor = withAlpha(0xFFE48A, progress);
        int accentColor = withAlpha(COVER_DECORATION_COLOR, progress);
        int iconSize = Math.max(20, Math.min(42, width - 24));
        int iconX = centerX - iconSize / 2;
        int iconY = y + 18;

        guiGraphics.fill(x + 10, y + 66, x + width - 10, y + 67, accentColor);
        guiGraphics.fill(x + 10, y + height - 22, x + width - 10, y + height - 21, accentColor);
        guiGraphics.blit(nn(COVER_ICON_TEXTURE), iconX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
        drawCoverTitleLine(guiGraphics, Component.translatable("gui.guide_book.cover_title"), centerX, y + 74, titleColor, 0.90F);
        drawCoverTitleLine(guiGraphics, Component.translatable("gui.guide_book.cover_title_en"), centerX, y + 89, subtitleColor, 0.82F);
        drawCoverTitleLine(guiGraphics, Component.translatable("gui.guide_book.cover_subtitle"), centerX, y + 102, subtitleColor, 0.76F);

        if (!this.previewStack.isEmpty()) {
            guiGraphics.renderItem(nn(this.previewStack), x + 10, y + height - 20);
        }
    }

    private void renderBookText(GuiGraphics guiGraphics, float progress, int mouseX, int mouseY) {
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

        switch (this.currentPage) {
            case DIRECTORY -> renderDirectoryPage(guiGraphics, font, leftPageX, rightPageX, pageY, textWidth,
                    titleColor, bodyColor, hintColor);
            case CITY -> renderContentPage(guiGraphics, font,
                    Component.translatable("gui.guide_book.city.left_title"),
                    Component.translatable("gui.guide_book.city.right_title"),
                    new Component[]{
                            Component.translatable("gui.guide_book.city.left_1"),
                            Component.translatable("gui.guide_book.city.left_2"),
                            Component.translatable("gui.guide_book.city.left_3")
                    },
                    new Component[]{
                            Component.translatable("gui.guide_book.city.right_1"),
                            Component.translatable("gui.guide_book.city.right_2"),
                            Component.translatable("gui.guide_book.city.right_3")
                    },
                    leftPageX, rightPageX, pageY, textWidth, titleColor, bodyColor);
            case COMMERCE -> renderContentPage(guiGraphics, font,
                    Component.translatable("gui.guide_book.commerce.left_title"),
                    Component.translatable("gui.guide_book.commerce.right_title"),
                    new Component[]{
                            Component.translatable("gui.guide_book.commerce.left_1"),
                            Component.translatable("gui.guide_book.commerce.left_2"),
                            Component.translatable("gui.guide_book.commerce.left_3")
                    },
                    new Component[]{
                            Component.translatable("gui.guide_book.commerce.right_1"),
                            Component.translatable("gui.guide_book.commerce.right_2"),
                            Component.translatable("gui.guide_book.commerce.right_3")
                    },
                    leftPageX, rightPageX, pageY, textWidth, titleColor, bodyColor);
            case LOGISTICS -> renderContentPage(guiGraphics, font,
                    Component.translatable("gui.guide_book.logistics.left_title"),
                    Component.translatable("gui.guide_book.logistics.right_title"),
                    new Component[]{
                            Component.translatable("gui.guide_book.logistics.left_1"),
                            Component.translatable("gui.guide_book.logistics.left_2"),
                            Component.translatable("gui.guide_book.logistics.left_3")
                    },
                    new Component[]{
                            Component.translatable("gui.guide_book.logistics.right_1"),
                            Component.translatable("gui.guide_book.logistics.right_2"),
                            Component.translatable("gui.guide_book.logistics.right_3")
                    },
                    leftPageX, rightPageX, pageY, textWidth, titleColor, bodyColor);
        }

        renderBookmarkTooltip(guiGraphics, font, bookX, bookY, mouseX, mouseY);
    }

    private void renderDirectoryPage(GuiGraphics guiGraphics, net.minecraft.client.gui.Font font,
                                     int leftPageX, int rightPageX, int pageY, int textWidth,
                                     int titleColor, int bodyColor, int hintColor) {
        var safeFont = nn(font);
        guiGraphics.drawString(safeFont, nn(Component.translatable("gui.guide_book.left_title")), leftPageX, pageY, titleColor, false);
        guiGraphics.drawString(safeFont, nn(Component.translatable("gui.guide_book.directory.right_title")), rightPageX, pageY, titleColor, false);

        int leftTextY = pageY + 20;
        leftTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.left_1")), leftPageX, leftTextY, textWidth, bodyColor, 4);
        leftTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.left_2")), leftPageX, leftTextY, textWidth, bodyColor, 4);
        drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.left_3")), leftPageX, leftTextY, textWidth, bodyColor, 0);

        int rightTextY = pageY + 20;
        rightTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.directory.city")), rightPageX, rightTextY, textWidth, bodyColor, 4);
        rightTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.directory.commerce")), rightPageX, rightTextY, textWidth, bodyColor, 4);
        rightTextY = drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.directory.logistics")), rightPageX, rightTextY, textWidth, bodyColor, 10);
        drawWrappedText(guiGraphics, nn(Component.translatable("gui.guide_book.directory.hint")), rightPageX, rightTextY, textWidth, hintColor, 0);
    }

    private void renderContentPage(GuiGraphics guiGraphics, net.minecraft.client.gui.Font font,
                                   Component leftTitle, Component rightTitle,
                                   Component[] leftLines, Component[] rightLines,
                                   int leftPageX, int rightPageX, int pageY, int textWidth,
                                   int titleColor, int bodyColor) {
        var safeFont = nn(font);
        guiGraphics.drawString(safeFont, nn(leftTitle), leftPageX, pageY, titleColor, false);
        guiGraphics.drawString(safeFont, nn(rightTitle), rightPageX, pageY, titleColor, false);

        int leftTextY = pageY + 20;
        for (int i = 0; i < leftLines.length; i++) {
            leftTextY = drawWrappedText(guiGraphics, nn(leftLines[i]), leftPageX, leftTextY, textWidth, bodyColor,
                    i == leftLines.length - 1 ? 0 : 4);
        }

        int rightTextY = pageY + 20;
        for (int i = 0; i < rightLines.length; i++) {
            rightTextY = drawWrappedText(guiGraphics, nn(rightLines[i]), rightPageX, rightTextY, textWidth, bodyColor,
                    i == rightLines.length - 1 ? 0 : 4);
        }
    }

    private void renderTabs(GuiGraphics guiGraphics, int bookX, int bookY, int mouseX, int mouseY, float revealProgress) {
        int tabX = getTabX(bookX);
        int baseY = bookY + TAB_BASE_Y_OFFSET;
        renderTabStack(guiGraphics, tabX, baseY, mouseX, mouseY, revealProgress);
    }

    private void renderTabStack(GuiGraphics guiGraphics, int tabX, int baseY, int mouseX, int mouseY, float revealProgress) {
        GuidePage[] order = new GuidePage[]{GuidePage.LOGISTICS, GuidePage.COMMERCE, GuidePage.CITY};
        for (GuidePage page : order) {
            if (page != this.currentPage) {
                renderTab(guiGraphics, tabX, getTabY(baseY, getTabIndex(page)), page, mouseX, mouseY, revealProgress,
                        getTabTexture(page));
            }
        }
        if (this.currentPage != GuidePage.DIRECTORY) {
            renderTab(guiGraphics, tabX, getTabY(baseY, getTabIndex(this.currentPage)), this.currentPage, mouseX, mouseY, revealProgress,
                    getTabTexture(this.currentPage));
        }
    }

    private void renderTab(GuiGraphics guiGraphics, int x, int y, GuidePage page, int mouseX, int mouseY,
                           float revealProgress, ResourceLocation texture) {
        int drawX = getTabDrawX(x, y, page, mouseX, mouseY, revealProgress);
        int drawY = getTabDrawY(x, y, page, mouseX, mouseY);

        drawBookmarkShadow(guiGraphics, drawX, drawY, TAB_WIDTH, TAB_HEIGHT, 0.20F);
        drawScaledTexture(guiGraphics, texture, drawX, drawY, TAB_TEXTURE_WIDTH, TAB_TEXTURE_HEIGHT, TAB_SCALE);

        if (this.currentPage == page) {
            drawSelectionMarker(guiGraphics, x - 6, y + 4, 6, TAB_HEIGHT - 8);
        }
    }

    private void renderReturnButton(GuiGraphics guiGraphics, int bookX, int bookY, int mouseX, int mouseY) {
        int returnX = getReturnButtonX(bookX);
        int returnY = getReturnButtonY(bookY);
        boolean hovered = isInside(mouseX, mouseY, returnX, returnY, RETURN_WIDTH, RETURN_HEIGHT);
        int drawY = returnY + (hovered ? RETURN_HOVER_SHIFT_Y : 0);

        drawBookmarkShadow(guiGraphics, returnX, drawY, RETURN_WIDTH, RETURN_HEIGHT, 0.18F);
        drawScaledTexture(guiGraphics, TAB_RETURN_TEXTURE, returnX, drawY,
                RETURN_TEXTURE_WIDTH, RETURN_TEXTURE_HEIGHT, RETURN_SCALE);
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

    private void drawScaledTexture(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y,
                                   int textureWidth, int textureHeight, float scale) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.blit(nn(texture), 0, 0, 0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
        guiGraphics.pose().popPose();
    }

    private void drawCoverTitleLine(GuiGraphics guiGraphics, Component text, int centerX, int y, int color, float scale) {
        var font = nn(this.font);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerX, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawCenteredString(font, nn(text), 0, 0, color);
        guiGraphics.pose().popPose();
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
        if (this.bookState == BookState.OPENED && button == 0 && handlePageNavigation(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handlePageNavigation(double mouseX, double mouseY) {
        int bookX = this.width / 2 - BOOK_WIDTH / 2;
        int bookY = this.height / 2 - BOOK_HEIGHT / 2;
        int tabX = getTabX(bookX);
        int baseY = bookY + TAB_BASE_Y_OFFSET;
        if (isInside(mouseX, mouseY, tabX, getTabY(baseY, 0), TAB_WIDTH, TAB_HEIGHT)) {
            this.currentPage = GuidePage.CITY;
            return true;
        }
        if (isInside(mouseX, mouseY, tabX, getTabY(baseY, 1), TAB_WIDTH, TAB_HEIGHT)) {
            this.currentPage = GuidePage.COMMERCE;
            return true;
        }
        if (isInside(mouseX, mouseY, tabX, getTabY(baseY, 2), TAB_WIDTH, TAB_HEIGHT)) {
            this.currentPage = GuidePage.LOGISTICS;
            return true;
        }
        if (this.currentPage != GuidePage.DIRECTORY) {
            int returnX = getReturnButtonX(bookX);
            int returnY = getReturnButtonY(bookY);
            if (isInside(mouseX, mouseY, returnX, returnY, RETURN_WIDTH, RETURN_HEIGHT)) {
                this.currentPage = GuidePage.DIRECTORY;
                return true;
            }
        }
        return false;
    }

    private int getReturnButtonX(int bookX) {
        return getRightPageLeftX(bookX) + 10;
    }

    private int getReturnButtonY(int bookY) {
        return getPageBottomY(bookY) - RETURN_TOP_OVERLAP;
    }

    private int getTabX(int bookX) {
        return getRightPageEdgeX(bookX) - (TAB_WIDTH - TAB_EXPOSED_WIDTH);
    }

    private int getPageBottomY(int bookY) {
        return bookY + BOOK_HEIGHT - 8;
    }

    private int getTabY(int baseY, int index) {
        return baseY + (TAB_HEIGHT + TAB_SPACING) * index;
    }

    private int getRightPageEdgeX(int bookX) {
        return bookX + BOOK_WIDTH - BOOK_MARGIN - 1;
    }

    private int getRightPageLeftX(int bookX) {
        int innerWidth = BOOK_WIDTH - BOOK_MARGIN * 2;
        int pageWidth = (innerWidth - PAGE_GAP) / 2;
        return bookX + BOOK_MARGIN + pageWidth + PAGE_GAP;
    }

    private int getTabDrawX(int x, int y, GuidePage page, int mouseX, int mouseY, float revealProgress) {
        boolean hovered = isInside(mouseX, mouseY, x, y, TAB_WIDTH, TAB_HEIGHT);
        boolean selected = this.currentPage == page;
        int animationOffset = Math.round(Mth.lerp(revealProgress, -TAB_HIDDEN_OFFSET_X, 0.0F));
        return x + animationOffset + (selected ? TAB_SELECTED_SHIFT_X : hovered ? TAB_HOVER_SHIFT_X : 0);
    }

    private int getTabDrawY(int x, int y, GuidePage page, int mouseX, int mouseY) {
        boolean hovered = isInside(mouseX, mouseY, x, y, TAB_WIDTH, TAB_HEIGHT);
        boolean selected = this.currentPage == page;
        return y + (selected ? TAB_SELECTED_SHIFT_Y : hovered ? TAB_HOVER_SHIFT_Y : 0);
    }

    private int getTabIndex(GuidePage page) {
        return switch (page) {
            case CITY -> 0;
            case COMMERCE -> 1;
            case LOGISTICS -> 2;
            default -> 0;
        };
    }

    private float getTabRevealProgress(float openProgress) {
        float normalized = Mth.clamp((openProgress - TAB_REVEAL_START_PROGRESS) / (1.0F - TAB_REVEAL_START_PROGRESS), 0.0F, 1.0F);
        return normalized * normalized * (3.0F - 2.0F * normalized);
    }

    private ResourceLocation getTabTexture(GuidePage page) {
        return switch (page) {
            case CITY -> TAB_CITY_TEXTURE;
            case COMMERCE -> TAB_COMMERCE_TEXTURE;
            case LOGISTICS -> TAB_LOGISTICS_TEXTURE;
            default -> TAB_CITY_TEXTURE;
        };
    }

    private void renderBookmarkTooltip(GuiGraphics guiGraphics, net.minecraft.client.gui.Font font, int bookX, int bookY,
                                       int mouseX, int mouseY) {
        Component tooltip = getHoveredBookmarkTooltip(bookX, bookY, mouseX, mouseY);
        if (tooltip != null) {
            guiGraphics.renderTooltip(nn(font), tooltip, mouseX, mouseY);
        }
    }

    @Nullable
    private Component getHoveredBookmarkTooltip(int bookX, int bookY, int mouseX, int mouseY) {
        int tabX = getTabX(bookX);
        int baseY = bookY + TAB_BASE_Y_OFFSET;
        if (isInside(mouseX, mouseY, tabX, getTabY(baseY, 0), TAB_WIDTH, TAB_HEIGHT)) {
            return Component.translatable("gui.guide_book.tooltip.city");
        }
        if (isInside(mouseX, mouseY, tabX, getTabY(baseY, 1), TAB_WIDTH, TAB_HEIGHT)) {
            return Component.translatable("gui.guide_book.tooltip.commerce");
        }
        if (isInside(mouseX, mouseY, tabX, getTabY(baseY, 2), TAB_WIDTH, TAB_HEIGHT)) {
            return Component.translatable("gui.guide_book.tooltip.logistics");
        }
        if (this.currentPage != GuidePage.DIRECTORY) {
            int returnX = getReturnButtonX(bookX);
            int returnY = getReturnButtonY(bookY);
            if (isInside(mouseX, mouseY, returnX, returnY, RETURN_WIDTH, RETURN_HEIGHT)) {
                return Component.translatable("gui.guide_book.tooltip.return");
            }
        }
        return null;
    }

    private void drawBookmarkShadow(GuiGraphics guiGraphics, int x, int y, int width, int height, float alphaValue) {
        guiGraphics.fill(x + 3, y + 3, x + width + 3, y + height + 3, withAlpha(0x000000, alphaValue));
    }

    private void drawSelectionMarker(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        drawPanel(guiGraphics, x, y, width, height,
                withAlpha(TAB_SELECTED_MARKER_BORDER_COLOR, 0.95F),
                withAlpha(TAB_SELECTED_MARKER_COLOR, 0.95F));
    }

    private void beginOpenAnimation() {
        if (this.bookState != BookState.COVER) {
            return;
        }
        this.currentPage = GuidePage.DIRECTORY;
        this.bookState = BookState.OPENING;
        this.openTicks = 0;
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private enum BookState {
        COVER,
        OPENING,
        OPENED
    }

    private enum GuidePage {
        DIRECTORY,
        CITY,
        COMMERCE,
        LOGISTICS
    }
}
