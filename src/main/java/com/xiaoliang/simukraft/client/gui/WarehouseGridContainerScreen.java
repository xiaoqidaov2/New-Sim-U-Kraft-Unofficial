package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.inventory.WarehouseGridMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 仓库网格容器界面
 * 参考 Refined Storage 的设计，直接绘制物品而不是使用槽位
 */
@SuppressWarnings({ "unused"})
public class WarehouseGridContainerScreen extends AbstractContainerScreen<WarehouseGridMenu> {

    private static final Logger LOGGER = LogManager.getLogger(WarehouseGridContainerScreen.class);

    @Nonnull
    private static final ResourceLocation GUI_TEXTURE = nn(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/container/generic_54.png"));

    // 客户端缓存的物品数据
    private List<ItemStack> warehouseItems = new ArrayList<>();
    // 实际物品数量（用于显示大数字）
    private List<Integer> actualCounts = new ArrayList<>();
    // 原始物品数据（用于搜索过滤）
    private List<ItemStack> originalItems = new ArrayList<>();
    private List<Integer> originalCounts = new ArrayList<>();

    // 仓库区域起始位置
    private static final int WAREHOUSE_X = 8;
    private static final int WAREHOUSE_Y = 18; // 恢复原来的位置
    
    // 搜索栏 - 移动到标题右侧
    private EditBox searchBox;
    private static final int SEARCH_WIDTH = 100;
    private static final int SEARCH_HEIGHT = 12;
    
    // 滚动条 - 覆盖在物品区域上方
    private int scrollOffset = 0; // 当前滚动偏移（行数）
    private static final int VISIBLE_ROWS = 6; // 可见行数（固定显示6行）
    private static final int SLOTS_PER_ROW = 9; // 每行9个物品
    private boolean isScrolling = false;
    // 滚动条放在物品区域右侧（覆盖在最上层）
    private static final int SCROLLBAR_X = 170; // 9个槽位 * 18 + 8 = 170, 稍微左移
    private static final int SCROLLBAR_Y = 18; // 与物品区域对齐
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int SCROLLBAR_HEIGHT = 108; // 6行 x 18像素
    
    // 自动刷新计时器
    private int refreshTimer = 0;
    private static final int REFRESH_INTERVAL = 20; // 每20 ticks (1秒) 刷新一次

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    public WarehouseGridContainerScreen(WarehouseGridMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176; // 标准宽度，不扩展
        this.imageHeight = 222;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 6;

        // 计算搜索栏位置（在标题右侧）
        int titleWidth = nn(this.font).width(nn(this.title));
        int searchX = this.leftPos + titleLabelX + titleWidth + 5; // 标题右侧 + 间距
        int searchY = this.topPos + titleLabelY - 2; // 与标题对齐

        // 创建搜索栏
        EditBox searchBox = new EditBox(nn(this.font), searchX, searchY, SEARCH_WIDTH, SEARCH_HEIGHT, nn(Component.literal("")));
        searchBox.setMaxLength(50);
        searchBox.setBordered(true);
        searchBox.setVisible(true);
        searchBox.setFocused(false);
        searchBox.setValue("");
        searchBox.setResponder(this::onSearchChanged);
        // 允许所有字符输入（包括英文、数字、中文等）
        searchBox.setFilter(s -> true);
        this.addWidget(searchBox);
        this.searchBox = searchBox;

        // 请求物品数据
        requestItems();
    }

    private void onSearchChanged(String searchText) {
        filterItems(searchText);
    }

    private void filterItems(String searchText) {
        if (searchText == null || searchText.isEmpty()) {
            // 恢复原始数据
            warehouseItems = new ArrayList<>(originalItems);
            actualCounts = new ArrayList<>(originalCounts);
        } else {
            // 过滤物品
            String lowerSearch = searchText.toLowerCase(Locale.ROOT);
            warehouseItems.clear();
            actualCounts.clear();
            
            for (int i = 0; i < originalItems.size(); i++) {
                ItemStack stack = originalItems.get(i);
                if (stack.isEmpty()) continue;
                
                String itemName = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
                if (itemName.contains(lowerSearch)) {
                    warehouseItems.add(stack);
                    actualCounts.add(originalCounts.get(i));
                }
            }
        }
        
        // 重置滚动位置
        scrollOffset = 0;

        // 更新槽位数据以匹配当前显示
        updateMenuSlotsForScroll();
    }

    private void requestItems() {
        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                new com.xiaoliang.simukraft.network.WarehouseGridRequestPacket(menu.getWarehousePos())
        );
    }

    public void receiveItems(List<ItemStack> items, List<BlockPos> positions, List<Integer> counts) {
        // 保存当前滚动位置
        int oldScrollOffset = scrollOffset;

        // 保存原始数据（不限制数量）
        this.originalItems = items != null ? new ArrayList<>(items) : new ArrayList<>();
        this.originalCounts = counts != null ? new ArrayList<>(counts) : new ArrayList<>();

        // 应用当前搜索过滤
        String currentSearch = searchBox != null ? searchBox.getValue() : "";
        if (currentSearch.isEmpty()) {
            this.warehouseItems = new ArrayList<>(originalItems);
            this.actualCounts = new ArrayList<>(originalCounts);
        } else {
            filterItems(currentSearch);
            return; // filterItems 已经更新了数据
        }

        // 恢复滚动位置（但要确保不超过新的最大行数）
        int totalRows = getTotalRows();
        int maxScroll = Math.max(0, totalRows - VISIBLE_ROWS);
        scrollOffset = Math.min(oldScrollOffset, maxScroll);

        // 更新槽位数据以匹配当前显示
        updateMenuSlotsForScroll();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        
        // 定时刷新物品数据
        refreshTimer++;
        if (refreshTimer >= REFRESH_INTERVAL) {
            refreshTimer = 0;
            requestItems();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 检查是否点击了搜索栏
        if (searchBox != null && searchBox.isMouseOver(mouseX, mouseY)) {
            searchBox.setFocused(true);
        } else if (searchBox != null) {
            searchBox.setFocused(false);
        }
        
        // 检查是否点击了滚动条
        if (isMouseOverScrollbar(mouseX, mouseY)) {
            isScrolling = true;
            updateScrollFromMouse(mouseY);
            return true;
        }
        
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isScrolling) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        isScrolling = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 检查鼠标是否在仓库区域内
        int warehouseLeft = this.leftPos + WAREHOUSE_X;
        int warehouseTop = this.topPos + WAREHOUSE_Y;
        int warehouseRight = warehouseLeft + SLOTS_PER_ROW * 18;
        int warehouseBottom = warehouseTop + VISIBLE_ROWS * 18;

        boolean inWarehouseArea = mouseX >= warehouseLeft && mouseX < warehouseRight &&
                                  mouseY >= warehouseTop && mouseY < warehouseBottom;

        // 检查鼠标是否在滚动条区域内
        boolean inScrollbarArea = isMouseOverScrollbar(mouseX, mouseY);

        // 只有在仓库区域或滚动条区域内才处理滚动
        if (inWarehouseArea || inScrollbarArea) {
            int totalRows = getTotalRows();
            int maxScroll = Math.max(0, totalRows - VISIBLE_ROWS);

            if (maxScroll > 0) {
                if (delta > 0) {
                    // 向上滚动（delta > 0）
                    scrollOffset = Math.max(0, scrollOffset - 1);
                } else if (delta < 0) {
                    // 向下滚动（delta < 0）
                    scrollOffset = Math.min(maxScroll, scrollOffset + 1);
                }
                // 滚动后更新槽位数据以同步显示和交互
                updateMenuSlotsForScroll();
                // LOGGER.info("Mouse scrolled: delta={}, scrollOffset={}", delta, scrollOffset);
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /**
     * 根据当前滚动位置更新菜单槽位数据
     * 确保槽位中的物品与显示的物品同步
     */
    private void updateMenuSlotsForScroll() {
        int startIndex = scrollOffset * SLOTS_PER_ROW;
        List<ItemStack> visibleItems = new ArrayList<>();

        // 收集当前可见的54个物品
        for (int i = 0; i < 54; i++) {
            int warehouseIndex = startIndex + i;
            if (warehouseIndex < warehouseItems.size()) {
                visibleItems.add(warehouseItems.get(warehouseIndex));
            } else {
                visibleItems.add(ItemStack.EMPTY);
            }
        }

        // 更新菜单的客户端物品数据
        menu.updateClientItems(visibleItems, menu.getContainerPositions());
    }

    private boolean isMouseOverScrollbar(double mouseX, double mouseY) {
        int x = this.leftPos + SCROLLBAR_X;
        int y = this.topPos + SCROLLBAR_Y;
        return mouseX >= x && mouseX < x + SCROLLBAR_WIDTH && 
               mouseY >= y && mouseY < y + SCROLLBAR_HEIGHT;
    }

    private void updateScrollFromMouse(double mouseY) {
        int scrollbarTop = this.topPos + SCROLLBAR_Y;
        int scrollbarBottom = scrollbarTop + SCROLLBAR_HEIGHT;

        double relativeY = mouseY - scrollbarTop;
        double ratio = relativeY / SCROLLBAR_HEIGHT;

        int maxScroll = Math.max(0, getTotalRows() - VISIBLE_ROWS);
        scrollOffset = (int) Math.round(ratio * maxScroll);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));

        // 拖动滚动条后更新槽位数据
        updateMenuSlotsForScroll();
    }

    private int getTotalRows() {
        return (int) Math.ceil(warehouseItems.size() / (double) SLOTS_PER_ROW);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 搜索框获得焦点时，所有可打印字符都传递给它
        if (searchBox != null && searchBox.isFocused()) {
            // 可打印字符范围（包括空格、英文字母、数字、符号等）
            if (codePoint >= 32) {
                return searchBox.charTyped(codePoint, modifiers);
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 搜索框获得焦点时，处理所有按键
        if (searchBox != null && searchBox.isFocused()) {
            // ESC键关闭搜索框
            if (keyCode == 256) {
                searchBox.setFocused(false);
                return true;
            }
            // 回车键关闭搜索框
            if (keyCode == 257) {
                searchBox.setFocused(false);
                return true;
            }
            // 其他所有按键都传递给搜索框处理
            return searchBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 绘制搜索栏
        if (searchBox != null) {
            searchBox.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // 绘制仓库物品（在槽位渲染之后）
        renderWarehouseItems(guiGraphics);

        // 绘制滚动条（覆盖在物品上方）
        renderScrollbar(guiGraphics);

        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private void renderScrollbar(GuiGraphics guiGraphics) {
        int x = this.leftPos + SCROLLBAR_X;
        int y = this.topPos + SCROLLBAR_Y;
        
        // 计算总行数和最大滚动值
        int totalRows = getTotalRows();
        int maxScroll = Math.max(0, totalRows - VISIBLE_ROWS);
        
        // 绘制滚动条背景（始终显示）
        guiGraphics.fill(x, y, x + SCROLLBAR_WIDTH, y + SCROLLBAR_HEIGHT, 0xFF000000);
        guiGraphics.fill(x + 1, y + 1, x + SCROLLBAR_WIDTH - 1, y + SCROLLBAR_HEIGHT - 1, 0xFF8B8B8B);
        
        // 只有需要滚动时才绘制滑块
        if (maxScroll > 0 && totalRows > 0) {
            // 计算滑块高度（根据可见比例）
            float visibleRatio = (float) VISIBLE_ROWS / totalRows;
            int sliderHeight = Math.max(15, (int) (SCROLLBAR_HEIGHT * visibleRatio));
            
            // 计算滑块位置
            float scrollRatio = maxScroll > 0 ? (float) scrollOffset / maxScroll : 0;
            int sliderY = y + (int) (scrollRatio * (SCROLLBAR_HEIGHT - sliderHeight));
            
            // 确保滑块在范围内
            sliderY = Math.max(y, Math.min(sliderY, y + SCROLLBAR_HEIGHT - sliderHeight));
            
            // 绘制滑块（更深的颜色）
            int sliderColor = isScrolling ? 0xFF808080 : 0xFF505050;
            guiGraphics.fill(x, sliderY, x + SCROLLBAR_WIDTH, sliderY + sliderHeight, sliderColor);
            
            // 调试信息
            // LOGGER.info("Scrollbar: totalRows={}, maxScroll={}, scrollOffset={}, sliderY={}", totalRows, maxScroll, scrollOffset, sliderY);
        }
    }
    
    /**
     * 绘制仓库物品和数量（9列完整显示）
     */
    private void renderWarehouseItems(GuiGraphics guiGraphics) {
        int startIndex = scrollOffset * SLOTS_PER_ROW;
        int endIndex = Math.min(startIndex + VISIBLE_ROWS * SLOTS_PER_ROW, warehouseItems.size());

        for (int i = startIndex; i < endIndex; i++) {
            ItemStack stack = warehouseItems.get(i);
            if (stack.isEmpty()) continue;

            // 计算显示位置（考虑滚动偏移）
            int displayIndex = i - startIndex;
            int row = displayIndex / SLOTS_PER_ROW;
            int col = displayIndex % SLOTS_PER_ROW;

            int x = this.leftPos + WAREHOUSE_X + col * 18;
            int y = this.topPos + WAREHOUSE_Y + row * 18;

            // 绘制物品
            guiGraphics.renderItem(stack, x, y);

            // 绘制数量（禁用默认装饰，使用自定义）
            int actualIndex = i;
            if (actualIndex < actualCounts.size()) {
                int count = actualCounts.get(actualIndex);
                if (count > 1) {
                    String countText = formatCount(count);
                    renderQuantity(guiGraphics, x, y, countText);
                }
            }
        }
    }
    
    /**
     * 绘制数量文本（右下角）
     */
    private void renderQuantity(GuiGraphics guiGraphics, int x, int y, String text) {
        // 计算文本位置（右下角）
        int textX = x + 19 - 2 - nn(this.font).width(nn(text));
        int textY = y + 6 + 3;
        
        // 绘制阴影文本
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 200);
        guiGraphics.drawString(nn(this.font), nn(text), textX, textY, 0xFFFFFF, true);
        guiGraphics.pose().popPose();
    }
    
    /**
     * 格式化数量为缩写形式
     */
    private String formatCount(int count) {
        if (count < 1000) {
            return String.valueOf(count);
        } else if (count < 1000000) {
            if (count < 10000) {
                return String.format("%.1fk", count / 1000.0);
            } else {
                return String.format("%dk", count / 1000);
            }
        } else {
            if (count < 10000000) {
                return String.format("%.1fM", count / 1000000.0);
            } else {
                return String.format("%dM", count / 1000000);
            }
        }
    }

    @Override
    protected void renderBg(@Nonnull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        
        // 绘制标准背景
        guiGraphics.blit(GUI_TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);
    }

    @Override
    protected void renderLabels(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(nn(this.font), nn(this.title), this.titleLabelX, this.titleLabelY, 4210752, false);
        guiGraphics.drawString(nn(this.font), nn(this.playerInventoryTitle), this.inventoryLabelX, this.inventoryLabelY, 4210752, false);
    }
}
