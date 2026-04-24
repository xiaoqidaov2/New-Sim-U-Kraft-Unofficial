package com.xiaoliang.simukraft.client.gui.guidebook;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 指南书ui
 * 支持 JSON 配置、多页翻页、物品引用
 */
@SuppressWarnings("null")
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
    // 翻页按钮
    private static final int PAGE_BUTTON_WIDTH = 16;
    private static final int PAGE_BUTTON_HEIGHT = 16;
    private static final int PAGE_BUTTON_OFFSET_X = 20;
    private static final int PAGE_BUTTON_OFFSET_Y = 180;

    private final ItemStack previewStack;
    private float alpha;
    private final float transitionSpeed = 0.05F;
    private int openTicks;
    private BookState bookState = BookState.COVER;
    private GuideBookPage currentChapter;
    private String currentChapterId = "directory";
    private int currentPageIndex = 0; // 当前章节内的页码

    // 翻页按钮
    private Button prevPageButton;
    private Button nextPageButton;

    // 链接点击区域列表（每帧清空重绘时重新收集）
    private final List<LinkClickArea> linkClickAreas = new ArrayList<>();

    /**
     * 链接点击区域记录
     */
    private record LinkClickArea(int x, int y, int width, int height, String target) {
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    public GuideBookScreen(ItemStack previewStack) {
        super(nn(Component.literal(GuideBookLang.get("guidebook.item.name"))));
        this.previewStack = nn(previewStack);
        this.currentChapter = GuideBookLoader.getIndexPage();
        if (this.currentChapter != null) {
            this.currentChapterId = this.currentChapter.getId();
        }
    }

    @Override
    protected void init() {
        super.init();

        // 加载指南书独立语言文件
        if (!GuideBookLang.isLoaded() && this.minecraft != null && this.minecraft.getResourceManager() != null) {
            GuideBookLang.load(this.minecraft.getResourceManager());
        }

        // 如果页面未加载，尝试手动加载
        if (!GuideBookLoader.isLoaded() && this.minecraft != null && this.minecraft.getResourceManager() != null) {
            GuideBookLoader.loadIfEmpty(this.minecraft.getResourceManager());
        }

        // 重新获取页面数据（确保资源已加载）
        if (this.currentChapter == null) {
            this.currentChapter = GuideBookLoader.getIndexPage();
            if (this.currentChapter != null) {
                this.currentChapterId = this.currentChapter.getId();
            }
        }
        
        // 设置图片加载完成回调，自动刷新界面
        GuideBookImageCache.setOnImageLoadedCallback((url) -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    // 触发界面重绘
                    this.init(this.minecraft, this.width, this.height);
                });
            }
        });

        int bookX = this.width / 2 - BOOK_WIDTH / 2;
        int bookY = this.height / 2 - BOOK_HEIGHT / 2;

        // 上一页按钮
        this.prevPageButton = Button.builder(Component.literal(GuideBookLang.get("guidebook.button.prev")), (btn) -> turnPage(-1))
                .pos(bookX + PAGE_BUTTON_OFFSET_X, bookY + PAGE_BUTTON_OFFSET_Y)
                .size(PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)
                .build();

        // 下一页按钮
        this.nextPageButton = Button.builder(Component.literal(GuideBookLang.get("guidebook.button.next")), (btn) -> turnPage(1))
                .pos(bookX + BOOK_WIDTH - PAGE_BUTTON_OFFSET_X - PAGE_BUTTON_WIDTH, bookY + PAGE_BUTTON_OFFSET_Y)
                .size(PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)
                .build();

        this.addRenderableWidget(this.prevPageButton);
        this.addRenderableWidget(this.nextPageButton);

        updatePageButtons();
    }
    
    @Override
    public void onClose() {
        // 清理图片加载回调
        GuideBookImageCache.setOnImageLoadedCallback(null);
        super.onClose();
    }

    private void turnPage(int direction) {
        if (this.currentChapter == null) return;

        int newIndex = this.currentPageIndex + direction;

        // 在当前章节内翻页
        if (newIndex >= 0 && newIndex < this.currentChapter.getTotalPages()) {
            this.currentPageIndex = newIndex;
            updatePageButtons();
            return;
        }

        // 向后翻页：当前章节结束，进入下一章节
        if (direction > 0 && newIndex >= this.currentChapter.getTotalPages()) {
            navigateToNextChapter();
            return;
        }

        // 向前翻页：当前章节开始，进入上一章节
        if (direction < 0 && newIndex < 0) {
            navigateToPrevChapter();
        }
    }

    /**
     * 导航到下一章节
     */
    private void navigateToNextChapter() {
        List<GuideBookPage> childPages = GuideBookLoader.getChildPages("directory");
        int currentTabIndex = this.currentChapter.getTabIndex();

        // 找到下一个 tab_index 更大的章节
        GuideBookPage nextChapter = null;
        for (GuideBookPage page : childPages) {
            if (page.getTabIndex() > currentTabIndex) {
                if (nextChapter == null || page.getTabIndex() < nextChapter.getTabIndex()) {
                    nextChapter = page;
                }
            }
        }

        if (nextChapter != null) {
            this.currentChapter = nextChapter;
            this.currentChapterId = nextChapter.getId();
            this.currentPageIndex = 0; // 新章节第一页
            updatePageButtons();
        }
    }

    /**
     * 导航到上一章节
     */
    private void navigateToPrevChapter() {
        List<GuideBookPage> childPages = GuideBookLoader.getChildPages("directory");
        int currentTabIndex = this.currentChapter.getTabIndex();

        // 找到上一个 tab_index 更小的章节
        GuideBookPage prevChapter = null;
        for (GuideBookPage page : childPages) {
            if (page.getTabIndex() < currentTabIndex) {
                if (prevChapter == null || page.getTabIndex() > prevChapter.getTabIndex()) {
                    prevChapter = page;
                }
            }
        }

        if (prevChapter != null) {
            this.currentChapter = prevChapter;
            this.currentChapterId = prevChapter.getId();
            this.currentPageIndex = prevChapter.getTotalPages() - 1; // 上一章节最后一页
            updatePageButtons();
        }
    }

    private void updatePageButtons() {
        if (this.currentChapter == null) {
            this.prevPageButton.visible = false;
            this.nextPageButton.visible = false;
            return;
        }

        List<GuideBookPage> childPages = GuideBookLoader.getChildPages("directory");
        int currentTabIndex = this.currentChapter.getTabIndex();

        // 检查是否有上一章节
        boolean hasPrevChapter = childPages.stream().anyMatch(p -> p.getTabIndex() < currentTabIndex);
        // 检查是否有下一章节
        boolean hasNextChapter = childPages.stream().anyMatch(p -> p.getTabIndex() > currentTabIndex);

        // 第一页且有上一章节时，显示上一页按钮（可返回上一章节）
        this.prevPageButton.visible = this.bookState == BookState.OPENED &&
                (this.currentPageIndex > 0 || (this.currentPageIndex == 0 && hasPrevChapter));

        // 最后一页且有下一章节时，显示下一页按钮（可进入下一章节）
        this.nextPageButton.visible = this.bookState == BookState.OPENED &&
                (this.currentPageIndex < this.currentChapter.getTotalPages() - 1 ||
                 (this.currentPageIndex == this.currentChapter.getTotalPages() - 1 && hasNextChapter));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.bookState == BookState.OPENING && this.openTicks < OPEN_TICKS) {
            this.openTicks++;
            if (this.openTicks >= OPEN_TICKS) {
                this.bookState = BookState.OPENED;
                updatePageButtons();
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

        // 渲染页码
        if (this.bookState == BookState.OPENED && this.currentChapter != null) {
            renderPageNumber(guiGraphics);
        }
    }

    private void renderPageNumber(GuiGraphics guiGraphics) {
        if (this.currentChapter == null) return;

        // 目录页不显示页码
        if (this.currentChapter.isDirectory()) return;

        // 计算全局页码
        int[] globalPageInfo = calculateGlobalPageNumber();
        int globalPageNumber = globalPageInfo[0];
        int totalGlobalPages = globalPageInfo[1];

        String pageText = globalPageNumber + " / " + totalGlobalPages;
        int centerX = this.width / 2;
        int bookY = this.height / 2 - BOOK_HEIGHT / 2;
        int pageNumberY = bookY + BOOK_HEIGHT - 20;

        int color = 0x5A4A3A;
        guiGraphics.drawCenteredString(this.font, pageText, centerX, pageNumberY, color);
    }

    /**
     * 计算全局页码信息
     * @return [当前全局页码, 总全局页数]
     */
    private int[] calculateGlobalPageNumber() {
        List<GuideBookPage> childPages = GuideBookLoader.getChildPages("directory");

        int globalPageNumber = 0;
        int totalGlobalPages = 0;

        // 先计算总页数
        for (GuideBookPage page : childPages) {
            totalGlobalPages += page.getTotalPages();
        }

        // 计算当前页码
        int currentTabIndex = this.currentChapter.getTabIndex();
        for (GuideBookPage page : childPages) {
            if (page.getTabIndex() < currentTabIndex) {
                // 累加之前所有章节的页数
                globalPageNumber += page.getTotalPages();
            } else if (page.getTabIndex() == currentTabIndex) {
                // 当前章节，加上当前页码（1-based）
                globalPageNumber += this.currentPageIndex + 1;
                break;
            }
        }

        return new int[]{globalPageNumber, totalGlobalPages};
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
            if (this.currentChapter != null && !this.currentChapter.isDirectory()) {
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
        drawCoverTitleLine(guiGraphics, Component.literal(GuideBookLang.get("guidebook.cover.title")), centerX, y + 74, titleColor, 0.90F);
        drawCoverTitleLine(guiGraphics, Component.literal(GuideBookLang.get("guidebook.cover.title_en")), centerX, y + 89, subtitleColor, 0.82F);
        drawCoverTitleLine(guiGraphics, Component.literal(GuideBookLang.get("guidebook.cover.subtitle")), centerX, y + 102, subtitleColor, 0.76F);

        if (!this.previewStack.isEmpty()) {
            guiGraphics.renderItem(nn(this.previewStack), x + 10, y + height - 20);
        }
    }

    private void renderBookText(GuiGraphics guiGraphics, float progress, int mouseX, int mouseY) {
        if (progress < 0.45F || this.currentChapter == null) {
            return;
        }

        var font = nn(this.font);
        int centerX = this.width / 2;
        int bookX = centerX - BOOK_WIDTH / 2;
        int bookY = this.height / 2 - BOOK_HEIGHT / 2;
        int leftPageX = bookX + BOOK_MARGIN + 12;
        int rightPageX = centerX + PAGE_GAP / 2 + 12;
        int pageY = bookY + BOOK_MARGIN + 14;
        int textWidth = (BOOK_WIDTH - BOOK_MARGIN * 2 - PAGE_GAP) / 2 - 24;

        // 测试：使用不透明颜色
        int titleColor = 0xFF23406D;
        int bodyColor = 0xFF3E3427;
        int hintColor = 0xFF5A7298;

        // 获取当前页数据
        GuideBookPage.BookPage currentPage = this.currentChapter.getPage(this.currentPageIndex);

        // 使用 JSON 数据渲染页面
        renderPageContent(guiGraphics, font, currentPage, leftPageX, rightPageX, pageY, textWidth,
                titleColor, bodyColor, hintColor);

        renderBookmarkTooltip(guiGraphics, font, bookX, bookY, mouseX, mouseY);
    }

    private void renderPageContent(GuiGraphics guiGraphics, net.minecraft.client.gui.Font font,
                                   GuideBookPage.BookPage page, int leftPageX, int rightPageX, int pageY, int textWidth,
                                   int titleColor, int bodyColor, int hintColor) {
        var safeFont = nn(font);

        // 清空链接点击区域（每帧重新收集）
        this.linkClickAreas.clear();

        // 渲染标题
        Component leftTitle = page.getLeftTitle();
        Component rightTitle = page.getRightTitle();
        guiGraphics.drawString(safeFont, nn(leftTitle), leftPageX, pageY, titleColor, false);
        guiGraphics.drawString(safeFont, nn(rightTitle), rightPageX, pageY, titleColor, false);

        // 渲染左侧内容
        int leftTextY = pageY + 20;
        List<GuideBookPage.PageElement> leftElements = page.getLeftElements();
        for (int i = 0; i < leftElements.size(); i++) {
            GuideBookPage.PageElement element = leftElements.get(i);
            leftTextY = renderElement(guiGraphics, element, leftPageX, leftTextY, textWidth, bodyColor, hintColor, true);
        }

        // 渲染右侧内容
        int rightTextY = pageY + 20;
        List<GuideBookPage.PageElement> rightElements = page.getRightElements();
        for (int i = 0; i < rightElements.size(); i++) {
            GuideBookPage.PageElement element = rightElements.get(i);
            rightTextY = renderElement(guiGraphics, element, rightPageX, rightTextY, textWidth, bodyColor, hintColor, false);
        }
    }

    private int renderElement(GuiGraphics guiGraphics, GuideBookPage.PageElement element,
                              int x, int y, int textWidth, int bodyColor, int hintColor, boolean isLeftPage) {
        // 如果是图片类型，渲染图片
        if (element.isImage()) {
            return renderImageElement(guiGraphics, element, x, y, textWidth);
        }

        // 如果是物品类型，渲染物品图标
        if (element.isItem() || element.hasItem()) {
            ItemStack stack = element.getItemStack();
            if (!stack.isEmpty()) {
                guiGraphics.renderItem(stack, x, y);
                guiGraphics.renderItemDecorations(this.font, stack, x, y);
                return y + 20 + element.getSpacing(); // 物品高度 + 间距
            }
        }

        // 处理链接类型
        if (element.isLink()) {
            return renderLinkElement(guiGraphics, element, x, y, textWidth, element.getSpacing());
        }

        // 渲染普通文本
        int color = element.isHint() ? hintColor : bodyColor;
        return drawWrappedText(guiGraphics, nn(element.getContent()), x, y, textWidth, color, element.getSpacing());
    }

    /**
     * 渲染图片元素，支持本地 ResourceLocation 和远程 URL
     */
    private int renderImageElement(GuiGraphics guiGraphics, GuideBookPage.PageElement element,
                                   int x, int y, int maxWidth) {
        String imageUrl = element.getImageUrl();
        if (imageUrl == null || imageUrl.isEmpty()) {
            return y + element.getSpacing();
        }

        GuideBookImageCache.ImageEntry entry = GuideBookImageCache.getOrLoad(imageUrl);

        int drawWidth = element.getImageWidth();
        int drawHeight = element.getImageHeight();

        // 限制最大宽度不超过页面宽度
        if (drawWidth > maxWidth) {
            float scale = (float) maxWidth / drawWidth;
            drawWidth = maxWidth;
            drawHeight = Math.round(drawHeight * scale);
        }

        if (entry.loaded && entry.texture != null) {
            // 图片已加载，正常渲染
            guiGraphics.blit(entry.texture, x, y, 0, 0, drawWidth, drawHeight, drawWidth, drawHeight);
        } else {
            // 图片加载中或失败，显示占位框
            int placeholderColor = 0xFFCCCCCC;
            int borderColor = 0xFF999999;
            guiGraphics.fill(x, y, x + drawWidth, y + drawHeight, placeholderColor);
            guiGraphics.fill(x, y, x + drawWidth, y + 1, borderColor);
            guiGraphics.fill(x, y + drawHeight - 1, x + drawWidth, y + drawHeight, borderColor);
            guiGraphics.fill(x, y, x + 1, y + drawHeight, borderColor);
            guiGraphics.fill(x + drawWidth - 1, y, x + drawWidth, y + drawHeight, borderColor);

            // 显示加载中提示
            String loadingText = entry.loaded ? "[图片加载失败]" : "[图片加载中...]";
            int textColor = 0xFF666666;
            int textX = x + (drawWidth - this.font.width(loadingText)) / 2;
            int textY = y + (drawHeight - this.font.lineHeight) / 2;
            guiGraphics.drawString(this.font, loadingText, textX, textY, textColor, false);
        }

        return y + drawHeight + element.getSpacing();
    }

    /**
     * 渲染链接元素（带下划线和点击区域）
     */
    private int renderLinkElement(GuiGraphics guiGraphics, GuideBookPage.PageElement element,
                                  int x, int y, int textWidth, int spacing) {
        Component text = element.getContent();
        String target = element.getTarget();
        int linkColor = 0xFF0066CC; // 蓝色链接

        // 获取文本尺寸
        int textWidth_actual = this.font.width(text);
        int textHeight = this.font.lineHeight;

        // 渲染带下划线的文本
        guiGraphics.drawString(this.font, nn(text), x, y, linkColor, false);

        // 绘制下划线
        guiGraphics.fill(x, y + textHeight, x + textWidth_actual, y + textHeight + 1, linkColor);

        // 记录点击区域（如果 target 有效）
        if (target != null && !target.isEmpty()) {
            this.linkClickAreas.add(new LinkClickArea(x, y, textWidth_actual, textHeight, target));
        }

        return y + textHeight + spacing;
    }

    private void renderTabs(GuiGraphics guiGraphics, int bookX, int bookY, int mouseX, int mouseY, float revealProgress) {
        int tabX = getTabX(bookX);
        int baseY = bookY + TAB_BASE_Y_OFFSET;
        renderTabStack(guiGraphics, tabX, baseY, mouseX, mouseY, revealProgress);
    }

    private void renderTabStack(GuiGraphics guiGraphics, int tabX, int baseY, int mouseX, int mouseY, float revealProgress) {
        List<GuideBookPage> childPages = GuideBookLoader.getChildPages("directory");

        // 渲染非当前章节的标签
        for (GuideBookPage page : childPages) {
            if (!page.getId().equals(this.currentChapterId)) {
                renderTab(guiGraphics, tabX, getTabY(baseY, page.getTabIndex()), page, mouseX, mouseY, revealProgress,
                        getTabTexture(page));
            }
        }

        // 渲染当前章节的标签（如果在子页面中）
        if (this.currentChapter != null && !this.currentChapter.isDirectory()) {
            renderTab(guiGraphics, tabX, getTabY(baseY, this.currentChapter.getTabIndex()), this.currentChapter, mouseX, mouseY, revealProgress,
                    getTabTexture(this.currentChapter));
        }
    }

    private void renderTab(GuiGraphics guiGraphics, int x, int y, GuideBookPage page, int mouseX, int mouseY,
                           float revealProgress, ResourceLocation texture) {
        int drawX = getTabDrawX(x, y, page, mouseX, mouseY, revealProgress);
        int drawY = getTabDrawY(x, y, page, mouseX, mouseY);

        drawBookmarkShadow(guiGraphics, drawX, drawY, TAB_WIDTH, TAB_HEIGHT, 0.20F);

        // 选中标签的高亮边框已移除
        // boolean selected = page.getId().equals(this.currentChapterId);
        // if (selected) {
        //     drawSelectionMarker(guiGraphics, drawX - 2, drawY - 2, TAB_WIDTH + 4, TAB_HEIGHT + 4);
        // }

        guiGraphics.blit(nn(texture), drawX, drawY, 0, 0, TAB_WIDTH, TAB_HEIGHT, TAB_WIDTH, TAB_HEIGHT);
    }

    private void renderReturnButton(GuiGraphics guiGraphics, int bookX, int bookY, int mouseX, int mouseY) {
        int x = getReturnButtonX(bookX);
        int y = getReturnButtonY(bookY);
        boolean hovered = isInside(mouseX, mouseY, x, y, RETURN_WIDTH, RETURN_HEIGHT);
        int drawY = y + (hovered ? RETURN_HOVER_SHIFT_Y : 0);

        drawBookmarkShadow(guiGraphics, x, drawY, RETURN_WIDTH, RETURN_HEIGHT, 0.22F);
        guiGraphics.blit(nn(TAB_RETURN_TEXTURE), x, drawY, 0, 0,
                RETURN_WIDTH, RETURN_HEIGHT, RETURN_WIDTH, RETURN_HEIGHT);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.bookState == BookState.COVER) {
            beginOpenAnimation();
            return true;
        }

        int bookX = this.width / 2 - BOOK_WIDTH / 2;
        int bookY = this.height / 2 - BOOK_HEIGHT / 2;

        // 检查返回按钮
        if (this.currentChapter != null && !this.currentChapter.isDirectory()) {
            int returnX = getReturnButtonX(bookX);
            int returnY = getReturnButtonY(bookY);
            if (isInside(mouseX, mouseY, returnX, returnY, RETURN_WIDTH, RETURN_HEIGHT)) {
                navigateToChapter("directory");
                return true;
            }
        }

        // 检查标签点击
        int tabX = getTabX(bookX);
        int baseY = bookY + TAB_BASE_Y_OFFSET;
        List<GuideBookPage> childPages = GuideBookLoader.getChildPages("directory");
        for (GuideBookPage page : childPages) {
            int tabY = getTabY(baseY, page.getTabIndex());
            if (isInside(mouseX, mouseY, tabX, tabY, TAB_WIDTH, TAB_HEIGHT)) {
                navigateToChapter(page.getId());
                return true;
            }
        }

        // 检查链接点击（仅在目录页）
        if (this.currentChapter != null && this.currentChapter.isDirectory()) {
            int mx = (int) mouseX;
            int my = (int) mouseY;
            for (LinkClickArea link : this.linkClickAreas) {
                if (link.contains(mx, my)) {
                    navigateToChapter(link.target());
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void navigateToChapter(String chapterId) {
        GuideBookLoader.getPage(chapterId).ifPresent(chapter -> {
            this.currentChapter = chapter;
            this.currentChapterId = chapterId;
            this.currentPageIndex = 0; // 重置到第一页
            updatePageButtons();
        });
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            if (this.bookState == BookState.OPENED && this.currentChapter != null && !this.currentChapter.isDirectory()) {
                navigateToChapter("directory");
                return true;
            }
        }
        // 左右箭头翻页
        if (keyCode == 263) { // Left arrow
            turnPage(-1);
            return true;
        }
        if (keyCode == 262) { // Right arrow
            turnPage(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int getTabX(int bookX) {
        return getRightPageEdgeX(bookX) - TAB_EXPOSED_WIDTH;
    }

    private int getTabY(int baseY, int index) {
        return baseY + index * (TAB_HEIGHT + TAB_SPACING);
    }

    private int getReturnButtonX(int bookX) {
        return getRightPageLeftX(bookX) + 10;
    }

    private int getReturnButtonY(int bookY) {
        return bookY + BOOK_MARGIN - RETURN_TOP_OVERLAP;
    }

    private int getRightPageEdgeX(int bookX) {
        return bookX + BOOK_WIDTH - BOOK_MARGIN - 1;
    }

    private int getRightPageLeftX(int bookX) {
        int innerWidth = BOOK_WIDTH - BOOK_MARGIN * 2;
        int pageWidth = (innerWidth - PAGE_GAP) / 2;
        return bookX + BOOK_MARGIN + pageWidth + PAGE_GAP;
    }

    private int getTabDrawX(int x, int y, GuideBookPage page, int mouseX, int mouseY, float revealProgress) {
        boolean hovered = isInside(mouseX, mouseY, x, y, TAB_WIDTH, TAB_HEIGHT);
        boolean selected = page.getId().equals(this.currentChapterId);
        int animationOffset = Math.round(Mth.lerp(revealProgress, -TAB_HIDDEN_OFFSET_X, 0.0F));
        return x + animationOffset + (selected ? TAB_SELECTED_SHIFT_X : hovered ? TAB_HOVER_SHIFT_X : 0);
    }

    private int getTabDrawY(int x, int y, GuideBookPage page, int mouseX, int mouseY) {
        boolean hovered = isInside(mouseX, mouseY, x, y, TAB_WIDTH, TAB_HEIGHT);
        boolean selected = page.getId().equals(this.currentChapterId);
        return y + (selected ? TAB_SELECTED_SHIFT_Y : hovered ? TAB_HOVER_SHIFT_Y : 0);
    }

    private float getTabRevealProgress(float openProgress) {
        float normalized = Mth.clamp((openProgress - TAB_REVEAL_START_PROGRESS) / (1.0F - TAB_REVEAL_START_PROGRESS), 0.0F, 1.0F);
        return normalized * normalized * (3.0F - 2.0F * normalized);
    }

    private ResourceLocation getTabTexture(GuideBookPage page) {
        String icon = page.getTabIcon();
        if (icon != null && !icon.isEmpty()) {
            return ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, "textures/gui/guide_book/" + icon + ".png");
        }
        // 默认图标
        return switch (page.getId()) {
            case "city" -> TAB_CITY_TEXTURE;
            case "commerce" -> TAB_COMMERCE_TEXTURE;
            case "logistics" -> TAB_LOGISTICS_TEXTURE;
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

        List<GuideBookPage> childPages = GuideBookLoader.getChildPages("directory");
        for (GuideBookPage page : childPages) {
            int tabY = getTabY(baseY, page.getTabIndex());
            if (isInside(mouseX, mouseY, tabX, tabY, TAB_WIDTH, TAB_HEIGHT)) {
                return Component.literal(GuideBookLang.get("guidebook.tooltip." + page.getId()));
            }
        }

        if (this.currentChapter != null && !this.currentChapter.isDirectory()) {
            int returnX = getReturnButtonX(bookX);
            int returnY = getReturnButtonY(bookY);
            if (isInside(mouseX, mouseY, returnX, returnY, RETURN_WIDTH, RETURN_HEIGHT)) {
                return Component.literal(GuideBookLang.get("guidebook.tooltip.return"));
            }
        }
        return null;
    }

    private void drawBookmarkShadow(GuiGraphics guiGraphics, int x, int y, int width, int height, float alphaValue) {
        guiGraphics.fill(x + 3, y + 3, x + width + 3, y + height + 3, withAlpha(0x000000, alphaValue));
    }

    private void beginOpenAnimation() {
        if (this.bookState != BookState.COVER) {
            return;
        }
        // 确保有索引页面
        if (this.currentChapter == null) {
            this.currentChapter = GuideBookLoader.getIndexPage();
            if (this.currentChapter != null) {
                this.currentChapterId = this.currentChapter.getId();
            }
        }
        this.bookState = BookState.OPENING;
        this.openTicks = 0;
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private void drawBackground(GuiGraphics guiGraphics) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xF0101010);
    }

    private void drawPanel(GuiGraphics guiGraphics, int x, int y, int width, int height, int borderColor, int fillColor) {
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fillColor);
        guiGraphics.fill(x, y, x + width, y + 1, borderColor);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, borderColor);
        guiGraphics.fill(x, y, x + 1, y + height, borderColor);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, borderColor);
    }

    private void drawCoverTitleLine(GuiGraphics guiGraphics, Component text, int centerX, int y, int color, float scale) {
        var font = nn(this.font);
        int textWidth = font.width(text);
        float scaledWidth = textWidth * scale;
        int drawX = Math.round(centerX - scaledWidth / 2);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(drawX, y, 0);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(font, nn(text), 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private int drawWrappedText(GuiGraphics guiGraphics, Component text, int x, int y, int maxWidth, int color, int spacing) {
        var font = nn(this.font);
        List<FormattedCharSequence> lines = font.split(text, maxWidth);
        int lineHeight = font.lineHeight;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(font, line, x, y, color, false);
            y += lineHeight;
        }
        return y + spacing;
    }

    private int withAlpha(int color, float alpha) {
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        return (a << 24) | (color & 0xFFFFFF);
    }

    private static <T> T nn(@Nullable T obj) {
        return Objects.requireNonNull(obj);
    }

    private enum BookState {
        COVER,
        OPENING,
        OPENED
    }
}
