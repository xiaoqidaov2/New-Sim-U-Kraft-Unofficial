package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.item.ManifestItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * 清单界面 - 剪贴板风格
 * 显示建筑任务所需材料清单，支持勾选追踪、分页和自适应缩放
 */
@SuppressWarnings("null")
public class ManifestScreen extends Screen {

    private final ItemStack manifestStack;
    private List<ManifestItem.MaterialEntry> materials;
    private int currentPage = 0;
    private int itemsPerPage = 6;

    // 剪贴板尺寸
    private static final int CLIPBOARD_WIDTH = 200;
    private static final int CLIPBOARD_HEIGHT = 240;
    private static final int CLIP_TOP_HEIGHT = 24;
    private static final int CLIP_BOTTOM_HEIGHT = 16;
    private static final int CLIP_SIDE_WIDTH = 12;
    private static final int CONTENT_PADDING_X = 16;
    private static final int CONTENT_PADDING_TOP = 32;
    private static final int CONTENT_PADDING_BOTTOM = 24;

    // 行高和勾选框
    private static final int ROW_HEIGHT = 24;
    private static final int CHECKBOX_SIZE = 14;
    private static final int ITEM_ICON_SIZE = 16;

    // 颜色
    private static final int COLOR_PAPER = 0xFFF5F0E1;
    private static final int COLOR_PAPER_SHADOW = 0xFFE8E0D0;
    private static final int COLOR_CLIPBOARD = 0xFF8B6914;
    private static final int COLOR_CLIPBOARD_DARK = 0xFF6B4F0A;
    private static final int COLOR_CLIP_METAL = 0xFFAAAAAA;
    private static final int COLOR_CLIP_METAL_LIGHT = 0xFFCCCCCC;
    private static final int COLOR_CLIP_METAL_DARK = 0xFF888888;
    private static final int COLOR_TEXT_NORMAL = 0xFF333333;
    private static final int COLOR_TEXT_CHECKED = 0xFF888888;
    private static final int COLOR_CHECK_MARK = 0xFF00AA00;
    private static final int COLOR_STRIKETHROUGH = 0xFF999999;
    private static final int COLOR_CHECKBOX_BORDER = 0xFF666666;
    private static final int COLOR_TITLE = 0xFF5D4037;
    private static final int COLOR_PAGE_TEXT = 0xFF666666;

    // 勾选符号（粗体大号）
    private static final String CHECK_MARK = "✔";

    // 实际绘制位置
    private int clipboardX;
    private int clipboardY;
    private int contentX;
    private int contentY;
    private int contentWidth;
    private int contentHeight;

    // 翻页按钮
    private Button prevPageButton;
    private Button nextPageButton;

    public ManifestScreen(ItemStack manifestStack) {
        super(Component.translatable("gui.manifest.title"));
        this.manifestStack = manifestStack;
        this.materials = ManifestItem.getMaterials(manifestStack);
    }

    @Override
    protected void init() {
        super.init();

        // 计算剪贴板位置（居中）
        clipboardX = (this.width - CLIPBOARD_WIDTH) / 2;
        clipboardY = (this.height - CLIPBOARD_HEIGHT) / 2;
        contentX = clipboardX + CLIP_SIDE_WIDTH + CONTENT_PADDING_X;
        contentY = clipboardY + CLIP_TOP_HEIGHT + CONTENT_PADDING_TOP;
        contentWidth = CLIPBOARD_WIDTH - (CLIP_SIDE_WIDTH + CONTENT_PADDING_X) * 2;
        contentHeight = CLIPBOARD_HEIGHT - CLIP_TOP_HEIGHT - CLIP_BOTTOM_HEIGHT - CONTENT_PADDING_TOP - CONTENT_PADDING_BOTTOM;

        // 根据内容高度计算每页显示行数
        itemsPerPage = Math.max(4, contentHeight / ROW_HEIGHT);

        // 计算纸张区域位置（用于放置翻页按钮）
        int paperX = clipboardX + CLIP_SIDE_WIDTH;
        int paperY = clipboardY + CLIP_TOP_HEIGHT;
        int paperW = CLIPBOARD_WIDTH - CLIP_SIDE_WIDTH * 2;
        int paperH = CLIPBOARD_HEIGHT - CLIP_TOP_HEIGHT - CLIP_BOTTOM_HEIGHT;

        // 翻页按钮尺寸
        int pageButtonWidth = 23;
        int pageButtonHeight = 13;
        int pageButtonYOffset = paperH - pageButtonHeight - 4;

        // 上一页按钮（纸张左下角）
        this.prevPageButton = Button.builder(
                Component.literal("<"),
                button -> prevPage()
            ).pos(paperX + 4, paperY + pageButtonYOffset)
            .size(pageButtonWidth, pageButtonHeight)
            .build();

        // 下一页按钮（纸张右下角）
        this.nextPageButton = Button.builder(
                Component.literal(">"),
                button -> nextPage()
            ).pos(paperX + paperW - pageButtonWidth - 4, paperY + pageButtonYOffset)
            .size(pageButtonWidth, pageButtonHeight)
            .build();

        this.addRenderableWidget(this.prevPageButton);
        this.addRenderableWidget(this.nextPageButton);

        updatePageButtons();
    }

    /**
     * 更新翻页按钮可见性
     */
    private void updatePageButtons() {
        int maxPage = Math.max(0, (materials.size() - 1) / itemsPerPage);
        this.prevPageButton.visible = currentPage > 0;
        this.nextPageButton.visible = currentPage < maxPage && materials.size() > 0;
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 绘制半透明背景
        this.renderBackground(guiGraphics);

        // 绘制剪贴板
        renderClipboard(guiGraphics);

        // 绘制内容
        renderContent(guiGraphics, mouseX, mouseY);

        // 绘制按钮
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 绘制剪贴板背景
     */
    private void renderClipboard(GuiGraphics guiGraphics) {
        int x = clipboardX;
        int y = clipboardY;
        int w = CLIPBOARD_WIDTH;
        int h = CLIPBOARD_HEIGHT;

        // 外边框阴影
        guiGraphics.fill(x + 3, y + 3, x + w + 2, y + h + 2, 0x44000000);

        // 木板底板
        guiGraphics.fill(x, y, x + w, y + h, COLOR_CLIPBOARD);
        guiGraphics.renderOutline(x, y, w, h, COLOR_CLIPBOARD_DARK);

        // 木板纹理线条（水平）
        for (int i = 4; i < h - 4; i += 8) {
            guiGraphics.fill(x + 2, y + i, x + w - 2, y + i + 1, COLOR_CLIPBOARD_DARK);
        }

        // 纸张区域
        int paperX = x + CLIP_SIDE_WIDTH;
        int paperY = y + CLIP_TOP_HEIGHT;
        int paperW = w - CLIP_SIDE_WIDTH * 2;
        int paperH = h - CLIP_TOP_HEIGHT - CLIP_BOTTOM_HEIGHT;

        // 纸张底色
        guiGraphics.fill(paperX, paperY, paperX + paperW, paperY + paperH, COLOR_PAPER);
        // 纸张内阴影
        guiGraphics.renderOutline(paperX, paperY, paperW, paperH, COLOR_PAPER_SHADOW);
        guiGraphics.fill(paperX + 1, paperY + paperH - 1, paperX + paperW - 1, paperY + paperH, COLOR_PAPER_SHADOW);
        guiGraphics.fill(paperX + paperW - 1, paperY + 1, paperX + paperW, paperY + paperH - 1, COLOR_PAPER_SHADOW);

        // 顶部金属夹子
        int clipW = 60;
        int clipH = 20;
        int clipX = x + (w - clipW) / 2;
        int clipY = y + 4;

        // 夹子阴影
        guiGraphics.fill(clipX + 2, clipY + 2, clipX + clipW + 2, clipY + clipH + 2, 0x44000000);
        // 夹子主体
        guiGraphics.fill(clipX, clipY, clipX + clipW, clipY + clipH, COLOR_CLIP_METAL);
        guiGraphics.renderOutline(clipX, clipY, clipW, clipH, COLOR_CLIP_METAL_DARK);
        // 夹子高光
        guiGraphics.fill(clipX + 2, clipY + 2, clipX + clipW - 2, clipY + 5, COLOR_CLIP_METAL_LIGHT);
        // 夹子铆钉
        guiGraphics.fill(clipX + 10, clipY + 8, clipX + 14, clipY + 12, COLOR_CLIP_METAL_DARK);
        guiGraphics.fill(clipX + 11, clipY + 9, clipX + 13, clipY + 11, COLOR_CLIP_METAL_LIGHT);
        guiGraphics.fill(clipX + clipW - 14, clipY + 8, clipX + clipW - 10, clipY + 12, COLOR_CLIP_METAL_DARK);
        guiGraphics.fill(clipX + clipW - 13, clipY + 9, clipX + clipW - 11, clipY + 11, COLOR_CLIP_METAL_LIGHT);

        // 底部翻页角
        int cornerSize = 12;
        int cornerX = x + w - CLIP_SIDE_WIDTH - cornerSize - 2;
        int cornerY = y + h - CLIP_BOTTOM_HEIGHT - cornerSize - 2;
        guiGraphics.fill(cornerX, cornerY + cornerSize, cornerX + cornerSize, cornerY + cornerSize + 1, COLOR_PAPER_SHADOW);
        guiGraphics.fill(cornerX + cornerSize, cornerY, cornerX + cornerSize + 1, cornerY + cornerSize + 1, COLOR_PAPER_SHADOW);
    }

    /**
     * 绘制内容区域
     */
    private void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 标题（无阴影）
        String buildingName = ManifestItem.getBuildingName(manifestStack);
        Component title = buildingName.isEmpty()
                ? Component.translatable("gui.manifest.empty_title")
                : Component.literal(buildingName);
        int titleWidth = this.font.width(title);
        int titleX = contentX + (contentWidth - titleWidth) / 2;
        guiGraphics.drawString(this.font, title, titleX, contentY - 20, COLOR_TITLE, false);

        // 材料列表
        if (materials.isEmpty()) {
            Component emptyText = Component.translatable("gui.manifest.no_materials");
            int emptyWidth = this.font.width(emptyText);
            guiGraphics.drawString(this.font, emptyText,
                    contentX + (contentWidth - emptyWidth) / 2, contentY + 30, 0xFF999999, false);
        } else {
            int startIndex = currentPage * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, materials.size());

            for (int i = startIndex; i < endIndex; i++) {
                int rowIndex = i - startIndex;
                int rowY = contentY + rowIndex * ROW_HEIGHT;
                renderMaterialRow(guiGraphics, contentX, rowY, contentWidth,
                        materials.get(i), mouseX, mouseY);
            }

            // 页码（无阴影）
            int maxPage = Math.max(1, (materials.size() - 1) / itemsPerPage + 1);
            Component pageText = Component.literal("第" + (currentPage + 1) + "页/共" + maxPage + "页");
            int pageWidth = this.font.width(pageText);
            guiGraphics.drawString(this.font, pageText,
                    contentX + (contentWidth - pageWidth) / 2,
                    contentY + itemsPerPage * ROW_HEIGHT + 4, COLOR_PAGE_TEXT, false);
        }
    }

    /**
     * 渲染材料行
     */
    private void renderMaterialRow(GuiGraphics guiGraphics, int x, int y, int width,
                                   ManifestItem.MaterialEntry entry, int mouseX, int mouseY) {
        boolean isHovered = mouseX >= x && mouseX <= x + width &&
                mouseY >= y && mouseY <= y + ROW_HEIGHT;
        boolean isChecked = entry.checked();

        // 行悬停效果
        if (isHovered && !isChecked) {
            guiGraphics.fill(x, y, x + width, y + ROW_HEIGHT, 0x11000000);
        }

        int currentX = x;

        // 勾选框（小方块样式）
        int checkboxY = y + (ROW_HEIGHT - CHECKBOX_SIZE) / 2;
        // 勾选框背景
        guiGraphics.fill(currentX, checkboxY, currentX + CHECKBOX_SIZE, checkboxY + CHECKBOX_SIZE, COLOR_PAPER);
        guiGraphics.renderOutline(currentX, checkboxY, CHECKBOX_SIZE, CHECKBOX_SIZE, COLOR_CHECKBOX_BORDER);

        if (isChecked) {
            // 绘制绿色勾选符号（居中）
            int checkX = currentX + (CHECKBOX_SIZE - this.font.width(CHECK_MARK)) / 2;
            int checkY = checkboxY - 1;
            guiGraphics.drawString(this.font, CHECK_MARK, checkX, checkY, COLOR_CHECK_MARK, false);
        }

        currentX += CHECKBOX_SIZE + 6;

        // 物品图标
        ItemStack itemStack = createItemStack(entry.itemId());
        int iconY = y + (ROW_HEIGHT - ITEM_ICON_SIZE) / 2;
        guiGraphics.renderItem(itemStack, currentX, iconY);

        currentX += ITEM_ICON_SIZE + 4;

        // 物品名称
        Component name = getItemDisplayName(entry.itemId());
        int textColor = isChecked ? COLOR_TEXT_CHECKED : COLOR_TEXT_NORMAL;
        int textY = y + (ROW_HEIGHT - 8) / 2;

        // 如果已勾选，绘制横线
        if (isChecked) {
            int nameWidth = this.font.width(name);
            guiGraphics.drawString(this.font, name, currentX, textY, textColor, false);
            // 横线
            guiGraphics.fill(currentX, textY + 4, currentX + nameWidth, textY + 5, COLOR_STRIKETHROUGH);
        } else {
            guiGraphics.drawString(this.font, name, currentX, textY, textColor, false);
        }

        // 数量（右对齐，无阴影）
        Component count = Component.literal("x" + entry.count());
        int countWidth = this.font.width(count);
        int countX = x + width - countWidth;
        guiGraphics.drawString(this.font, count, countX, textY, textColor, false);
    }

    /**
     * 根据物品ID创建ItemStack
     */
    @Nonnull
    private ItemStack createItemStack(@Nonnull String itemId) {
        try {
            var location = ResourceLocation.tryParse(itemId);
            if (location == null) {
                return new ItemStack(Items.BARRIER);
            }
            var item = ForgeRegistries.ITEMS.getValue(location);
            if (item != null && item != Items.AIR) {
                return new ItemStack(item);
            }
            var block = ForgeRegistries.BLOCKS.getValue(location);
            if (block != null) {
                return new ItemStack(block.asItem());
            }
        } catch (Exception ignored) {
        }
        return new ItemStack(Items.BARRIER);
    }

    /**
     * 获取物品显示名称
     */
    @Nonnull
    private Component getItemDisplayName(@Nonnull String itemId) {
        ItemStack stack = createItemStack(itemId);
        if (!stack.isEmpty()) {
            return stack.getHoverName();
        }
        return Component.literal(itemId);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int startIndex = currentPage * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, materials.size());

            for (int i = startIndex; i < endIndex; i++) {
                int rowIndex = i - startIndex;
                int rowY = contentY + rowIndex * ROW_HEIGHT;

                // 检查是否点击了行区域
                if (mouseX >= contentX && mouseX <= contentX + contentWidth &&
                        mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT) {
                    toggleChecked(i);
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 切换勾选状态
     */
    private void toggleChecked(int index) {
        ManifestItem.MaterialEntry entry = materials.get(index);
        boolean newChecked = !entry.checked();
        ManifestItem.setChecked(manifestStack, index, newChecked);
        materials = ManifestItem.getMaterials(manifestStack);
    }

    private void prevPage() {
        if (currentPage > 0) {
            currentPage--;
            updatePageButtons();
        }
    }

    private void nextPage() {
        int maxPage = Math.max(0, (materials.size() - 1) / itemsPerPage);
        if (currentPage < maxPage) {
            currentPage++;
            updatePageButtons();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        super.onClose();
        Minecraft.getInstance().setScreen(null);
    }
}
