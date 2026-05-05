package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.CommercialBuyPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestStockSyncPacket;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一商业建筑购买界面 - 使用LDLib框架
 * 面向玩家出售模式
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings({"null"})
public class CommercialBuyScreen extends ModularUIGuiContainer {

    // ==================== 布局常量 ====================
    // 使用相对比例，基于 GUI 缩放后的实际尺寸计算

    private static final float WINDOW_WIDTH_RATIO = 0.65f;      // 窗口宽度占屏幕宽度的比例
    private static final float WINDOW_HEIGHT_RATIO = 0.7f;      // 窗口高度占屏幕高度的比例
    private static final float HEADER_HEIGHT_RATIO = 0.15f;     // 标题栏高度占窗口高度的比例
    private static final float FOOTER_HEIGHT_RATIO = 0.15f;     // 底部高度占窗口高度的比例
    // 最小/最大尺寸限制
    private static final int MIN_WINDOW_WIDTH = 300;
    private static final int MIN_WINDOW_HEIGHT = 240;
    private static final int MAX_WINDOW_WIDTH = 600;
    private static final int MAX_WINDOW_HEIGHT = 480;

    // 颜色定义
    private static final int COLOR_WINDOW_BG = 0xFF2A2A2A;
    private static final int COLOR_WINDOW_BORDER = 0xFF555555;
    private static final int COLOR_HEADER_BG = 0xFF1A3A5A;
    private static final int COLOR_ROW_EVEN = 0x18FFFFFF;
    private static final int COLOR_ROW_OUT_OF_STOCK = 0x44440000;
    private static final int COLOR_BUTTON_BG = 0xFF3A3A3A;
    private static final int COLOR_BUTTON_HOVER = 0xFF4A4A4A;
    private static final int COLOR_BUTTON_BORDER = 0xFFADD8E6;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_HEADER = 0xFF88AABB;
    private static final int COLOR_TEXT_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_TEXT_GRAY = 0xFF777777;
    private static final int COLOR_TEXT_GOLD = 0xFFFFCC66;
    private static final int COLOR_TEXT_GREEN = 0xFF66FF88;
    private static final int COLOR_TEXT_YELLOW = 0xFFFFFF66;
    private static final int COLOR_TEXT_RED = 0xFFFF6666;

    // ==================== 成员变量 ====================

    private final BuyUIHolder holder;

    // ==================== 构造函数 ====================

    public CommercialBuyScreen(BlockPos pos, String buildingFileName) {
        super(createHolderAndUI(pos, buildingFileName), 0);
        this.holder = ((ModularUI) this.modularUI).holder instanceof BuyUIHolder
                ? (BuyUIHolder) ((ModularUI) this.modularUI).holder
                : null;

        playOpenSound();
    }

    private void playOpenSound() {
        Minecraft.getInstance().getSoundManager().play(
                nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 渲染背景遮罩
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);
        super.render(nn(guiGraphics), mouseX, mouseY, partialTicks);
    }

    @Nonnull
    private static <T> T nn(T value) {
        return java.util.Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(String value) {
        return nn(value);
    }

    // ==================== 尺寸计算工具方法 ====================

    private static int calculateWindowWidth(Minecraft mc) {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int width = (int)(screenWidth * WINDOW_WIDTH_RATIO);
        return Math.max(MIN_WINDOW_WIDTH, Math.min(MAX_WINDOW_WIDTH, width));
    }

    private static int calculateWindowHeight(Minecraft mc) {
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int height = (int)(screenHeight * WINDOW_HEIGHT_RATIO);
        return Math.max(MIN_WINDOW_HEIGHT, Math.min(MAX_WINDOW_HEIGHT, height));
    }

    // 公共方法：当收到服务器库存同步时调用
    public void onStockSyncReceived() {
        if (holder != null) {
            holder.updateStockFromClientData();
        }
    }

    private static ModularUI createHolderAndUI(BlockPos pos, String buildingFileName) {
        BuyUIHolder holder = new BuyUIHolder(pos, buildingFileName);
        return holder.createModularUI();
    }

    // ==================== UI 创建 ====================

    private static ModularUI createUI(BuyUIHolder holder) {
        Minecraft mc = Minecraft.getInstance();

        // 计算动态尺寸
        int windowWidth = calculateWindowWidth(mc);
        int windowHeight = calculateWindowHeight(mc);
        int headerHeight = Math.max(40, (int)(windowHeight * HEADER_HEIGHT_RATIO));
        int footerHeight = Math.max(40, (int)(windowHeight * FOOTER_HEIGHT_RATIO));
        int listHeight = windowHeight - headerHeight - footerHeight;

        ModularUI modularUI = new ModularUI(new Size(windowWidth, windowHeight), holder, nn(mc.player));

        // 根容器 - LDLib 会自动居中，所以位置设为 (0, 0)
        WidgetGroup rootGroup = new WidgetGroup();
        rootGroup.setSelfPosition(0, 0);
        rootGroup.setSize(windowWidth, windowHeight);

        // 主窗口背景（带圆角）
        WidgetGroup windowGroup = new WidgetGroup();
        windowGroup.setSelfPosition(0, 0);
        windowGroup.setSize(windowWidth, windowHeight);
        windowGroup.setBackground(new GuiTextureGroup(
                new ColorRectTexture(COLOR_WINDOW_BG).setRadius(8),
                new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(8)
        ));
        rootGroup.addWidget(windowGroup);

        // 标题栏背景
        WidgetGroup headerGroup = new WidgetGroup();
        headerGroup.setSelfPosition(0, 0);
        headerGroup.setSize(windowWidth, headerHeight);
        headerGroup.setBackground(new ColorRectTexture(COLOR_HEADER_BG).setRadius(8));
        windowGroup.addWidget(headerGroup);

        // 标题
        TextTexture titleTexture = new TextTexture(safeString(holder.buildingName), COLOR_TEXT_TITLE);
        titleTexture.setType(TextTexture.TextType.NORMAL);
        ImageWidget titleWidget = new ImageWidget(0, headerHeight / 6, windowWidth, 16, titleTexture);
        headerGroup.addWidget(titleWidget);

        // 搜索框 - 使用圆角背景，支持输入
        int searchBoxWidth = (int)(windowWidth * 0.36f);
        int searchBoxHeight = (int)(headerHeight * 0.4f);
        TextFieldWidget searchBox = new TextFieldWidget();
        searchBox.setSelfPosition((int)(windowWidth * 0.02f), headerHeight / 2);
        searchBox.setSize(searchBoxWidth, searchBoxHeight);
        searchBox.setTextColor(0xFFFFFFFF);
        searchBox.setBordered(false);
        searchBox.setBackground(new GuiTextureGroup(
                new ColorRectTexture(0xFF2A2A2A).setRadius(3),
                new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(3)
        ));
        searchBox.setActive(true);
        headerGroup.addWidget(searchBox);
        holder.setSearchBox(searchBox);

        // 搜索按钮 - 点击执行搜索（放大镜图标，与搜索框同高）
        ButtonWidget searchButton = createSmallButton((int)(windowWidth * 0.39f), headerHeight / 2, searchBoxHeight, searchBoxHeight, "\uD83D\uDD0D",
                clickData -> holder.performSearch());
        headerGroup.addWidget(searchButton);

        // 刷新按钮（圆箭头图标，与搜索框同高）
        ButtonWidget refreshButton = createSmallButton((int)(windowWidth * 0.39f) + searchBoxHeight + 2, headerHeight / 2, searchBoxHeight, searchBoxHeight, "\u27F3",
                clickData -> holder.refreshStockFromServer());
        headerGroup.addWidget(refreshButton);

        // 分页按钮 - 相对位置
        int pageBtnWidth = Math.max(20, (int)(windowWidth * 0.06f));
        int pageBtnHeight = Math.max(14, (int)(headerHeight * 0.32f));
        // 向左翻页按钮向左移动10像素
        ButtonWidget prevButton = createSmallButton(windowWidth - pageBtnWidth * 3 - 20, headerHeight / 2 + 2, pageBtnWidth, pageBtnHeight, "◀",
                clickData -> holder.prevPage());
        headerGroup.addWidget(prevButton);
        holder.setPrevButton(prevButton);

        ButtonWidget nextButton = createSmallButton(windowWidth - pageBtnWidth - 5, headerHeight / 2 + 2, pageBtnWidth, pageBtnHeight, "▶",
                clickData -> holder.nextPage());
        headerGroup.addWidget(nextButton);
        holder.setNextButton(nextButton);

        // 页码显示
        TextTexture pageTexture = new TextTexture("1 / 1", 0xFFCCCCCC);
        pageTexture.setType(TextTexture.TextType.NORMAL);
        ImageWidget pageLabel = new ImageWidget(windowWidth - pageBtnWidth * 2 - 15, headerHeight / 2 + 2, pageBtnWidth + 10, pageBtnHeight, pageTexture);
        headerGroup.addWidget(pageLabel);
        holder.setPageLabel(pageLabel);

        // 列表区域
        int listY = headerHeight;

        // 表头
        WidgetGroup listHeader = new WidgetGroup();
        listHeader.setSelfPosition((int)(windowWidth * 0.012f), listY);
        listHeader.setSize((int)(windowWidth * 0.976f), (int)(listHeight * 0.15f));
        windowGroup.addWidget(listHeader);

        // 相对列位置
        int colName = (int)(windowWidth * 0.07f);
        int colPrice = (int)(windowWidth * 0.35f);
        int colStock = (int)(windowWidth * 0.475f);
        int colQty = (int)(windowWidth * 0.60f);
        int colSubtotal = (int)(windowWidth * 0.88f);

        listHeader.addWidget(createHeaderLabel(colName, 0, (int)(windowWidth * 0.15f), (int)(listHeight * 0.13f), "物品"));
        listHeader.addWidget(createHeaderLabel(colPrice, 0, (int)(windowWidth * 0.10f), (int)(listHeight * 0.13f), "单价"));
        listHeader.addWidget(createHeaderLabel(colStock, 0, (int)(windowWidth * 0.10f), (int)(listHeight * 0.13f), "库存"));
        listHeader.addWidget(createHeaderLabel(colQty + (int)(windowWidth * 0.05f), 0, (int)(windowWidth * 0.15f), (int)(listHeight * 0.13f), "数量"));
        listHeader.addWidget(createHeaderLabel(colSubtotal, 0, (int)(windowWidth * 0.125f), (int)(listHeight * 0.13f), "小计"));

        // 分隔线
        WidgetGroup separator = new WidgetGroup();
        separator.setSelfPosition((int)(windowWidth * 0.012f), listY + (int)(listHeight * 0.15f));
        separator.setSize((int)(windowWidth * 0.976f), 1);
        separator.setBackground(new ColorRectTexture(0xFF3A5A7A));
        windowGroup.addWidget(separator);

        // 物品列表容器
        WidgetGroup listGroup = new WidgetGroup();
        listGroup.setSelfPosition((int)(windowWidth * 0.012f), listY + (int)(listHeight * 0.1875f));
        listGroup.setSize((int)(windowWidth * 0.976f), (int)(listHeight * 0.75f));
        windowGroup.addWidget(listGroup);
        holder.setListGroup(listGroup);

        // 底部分隔线
        WidgetGroup footerSeparator = new WidgetGroup();
        footerSeparator.setSelfPosition((int)(windowWidth * 0.012f), windowHeight - footerHeight);
        footerSeparator.setSize((int)(windowWidth * 0.976f), 1);
        footerSeparator.setBackground(new ColorRectTexture(0xFF3A5A7A));
        windowGroup.addWidget(footerSeparator);

        // 底部区域
        WidgetGroup footerGroup = new WidgetGroup();
        footerGroup.setSelfPosition(0, windowHeight - footerHeight + (int)(footerHeight * 0.05f));
        footerGroup.setSize(windowWidth, (int)(footerHeight * 0.95f));
        windowGroup.addWidget(footerGroup);

        // 总计（居中显示在底部上方）
        TextTexture totalTexture = new TextTexture("总计: 0.00 元", COLOR_TEXT_GOLD);
        totalTexture.setType(TextTexture.TextType.NORMAL);
        ImageWidget totalLabel = new ImageWidget(0, (int)(footerHeight * 0.05f), windowWidth, (int)(footerHeight * 0.25f), totalTexture);
        footerGroup.addWidget(totalLabel);
        holder.setTotalLabel(totalLabel);

        // 按钮区域 - 使用更合理的布局
        int btnY = (int)(footerHeight * 0.35f);
        int btnHeight = Math.max(16, (int)(footerHeight * 0.5f));
        int sideMargin = Math.max(10, (int)(windowWidth * 0.02f));
        int btnSpacing = Math.max(8, (int)(windowWidth * 0.015f));
        
        // 返回按钮（左侧）
        int backBtnWidth = Math.max(50, (int)(windowWidth * 0.12f));
        ButtonWidget backButton = createButton(sideMargin, btnY, backBtnWidth, btnHeight, "gui.button.back",
                clickData -> holder.onBack());
        footerGroup.addWidget(backButton);

        // 重置按钮（左侧，返回按钮右侧）- 清空搜索，显示所有物品
        int resetBtnWidth = Math.max(50, (int)(windowWidth * 0.12f));
        ButtonWidget resetButton = createButton(sideMargin + backBtnWidth + btnSpacing, btnY, resetBtnWidth, btnHeight, "gui.commercial_buy.reset",
                clickData -> {
                    holder.clearSearch();
                });
        footerGroup.addWidget(resetButton);

        // 购买按钮（右侧）
        int purchaseBtnWidth = Math.max(60, (int)(windowWidth * 0.15f));
        ButtonWidget purchaseButton = createButton(windowWidth - purchaseBtnWidth - sideMargin, btnY, purchaseBtnWidth, btnHeight, "gui.button.purchase",
                clickData -> holder.purchaseItems());
        purchaseButton.setActive(false);
        footerGroup.addWidget(purchaseButton);
        holder.setPurchaseButton(purchaseButton);

        // 初始化
        modularUI.widget(rootGroup);
        modularUI.initWidgets();

        // 初始化 holder（在 initWidgets 之后，确保所有 widget 已初始化）
        holder.init();

        return modularUI;
    }

    private static ImageWidget createHeaderLabel(int x, int y, int width, int height, String text) {
        TextTexture texture = new TextTexture(text, COLOR_TEXT_HEADER);
        texture.setType(TextTexture.TextType.LEFT);
        ImageWidget label = new ImageWidget(x, y, width, height, texture);
        return label;
    }

    private static ButtonWidget createSmallButton(int x, int y, int width, int height, String text,
                                                   java.util.function.Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);

        TextTexture buttonText = new TextTexture(text, 0xFFFFFFFF);
        buttonText.setType(TextTexture.TextType.NORMAL);

        button.setButtonTexture(
                new GuiTextureGroup(
                        new ColorRectTexture(COLOR_BUTTON_BG).setRadius(2),
                        new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(2)
                ),
                buttonText
        );
        button.setHoverTexture(
                new GuiTextureGroup(
                        new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(2),
                        new ColorBorderTexture(1, COLOR_BUTTON_BORDER).setRadius(2)
                ),
                buttonText
        );
        button.setOnPressCallback(onPress);

        return button;
    }

    private static ButtonWidget createButton(int x, int y, int width, int height, String textKey,
                                              java.util.function.Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);

        TextTexture buttonText = new TextTexture(textKey, 0xFFFFFFFF);
        buttonText.setType(TextTexture.TextType.NORMAL);

        button.setButtonTexture(
                new GuiTextureGroup(
                        new ColorRectTexture(COLOR_BUTTON_BG).setRadius(3),
                        new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(3)
                ),
                buttonText
        );
        button.setHoverTexture(
                new GuiTextureGroup(
                        new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(3),
                        new ColorBorderTexture(1, COLOR_BUTTON_BORDER).setRadius(3)
                ),
                buttonText
        );
        button.setOnPressCallback(onPress);

        return button;
    }

    // ==================== 物品行组件 ====================

    private static class TradeItemRow extends WidgetGroup {
        private final TradeItem tradeItem;
        private final BuyUIHolder holder;
        private int quantity = 0;

        // Widget 引用，用于动态更新
        private ImageWidget qtyLabel;
        private ImageWidget subtotalLabel;
        private ButtonWidget decreaseBtn;
        private ButtonWidget increaseBtn;

        public TradeItemRow(int x, int y, int width, int height, TradeItem tradeItem, BuyUIHolder holder) {
            this.setSelfPosition(x, y);
            this.setSize(width, height);
            this.tradeItem = tradeItem;
            this.holder = holder;

            rebuildWidgets();
        }

        private void rebuildWidgets() {
            this.clearAllWidgets();

            boolean inStock = tradeItem.isInStock();
            int maxQty = tradeItem.getMaxQuantity();
            int availableStock = tradeItem.retail ? tradeItem.currentStock : tradeItem.getStockInStacks();

            // 背景 - 零库存使用统一的颜色
            int bgColor = inStock ? COLOR_ROW_EVEN : COLOR_ROW_OUT_OF_STOCK;
            this.setBackground(new ColorRectTexture(bgColor));

            int width = getSize().width;
            int height = getSize().height;

            // 相对位置计算
            int iconSize = Math.min(16, height - 4);
            int textHeight = Math.max(8, height / 3);
            int btnSize = Math.max(14, height / 2);
            int qtyWidth = Math.max(35, width / 7);

            // 物品图标
            ItemIconWidget iconWidget = new ItemIconWidget((int)(width * 0.015f), (height - iconSize) / 2, iconSize, iconSize, tradeItem.item);
            this.addWidget(iconWidget);

            // 物品名称
            String name = tradeItem.displayName;
            int maxNameLen = Math.max(8, width / 25);
            if (name.length() > maxNameLen) name = name.substring(0, maxNameLen - 1) + "…";
            TextTexture nameTexture = new TextTexture(name, inStock ? COLOR_TEXT_NORMAL : COLOR_TEXT_GRAY);
            nameTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget nameLabel = new ImageWidget((int)(width * 0.075f), (height - textHeight) / 2, (int)(width * 0.25f), textHeight, nameTexture);
            this.addWidget(nameLabel);

            // 单价
            TextTexture priceTexture = new TextTexture(String.format(Locale.US, "%.2f", tradeItem.sellPrice), COLOR_TEXT_GOLD);
            priceTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget priceLabel = new ImageWidget((int)(width * 0.34f), (height - textHeight) / 2, (int)(width * 0.12f), textHeight, priceTexture);
            this.addWidget(priceLabel);

            // 库存 - 零库存统一使用红色
            int stockColor = inStock ? COLOR_TEXT_GREEN : COLOR_TEXT_RED;
            if (inStock && tradeItem.currentStock < tradeItem.maxStock * 0.3) stockColor = COLOR_TEXT_YELLOW;
            TextTexture stockTexture = new TextTexture(tradeItem.getStockDisplay(), stockColor);
            stockTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget stockLabel = new ImageWidget((int)(width * 0.47f), (height - textHeight) / 2, (int)(width * 0.12f), textHeight, stockTexture);
            this.addWidget(stockLabel);

            // 数量控制
            int qtyY = (height - btnSize) / 2;
            int qtyBtnX = (int)(width * 0.60f);

            // 减号按钮
            decreaseBtn = createSmallButton(qtyBtnX, qtyY, btnSize, btnSize, "−",
                    clickData -> {
                        if (quantity > 0) {
                            quantity--;
                            holder.updateSelectedQuantity(tradeItem.itemId, quantity);
                            updateDisplay();
                            holder.updateTotalPrice();
                        }
                    });
            decreaseBtn.setActive(quantity > 0);
            this.addWidget(decreaseBtn);

            // 数量显示
            TextTexture qtyTexture = new TextTexture(quantity + " " + tradeItem.getUnit(), quantity > 0 ? COLOR_TEXT_GREEN : 0xFFCCCCCC);
            qtyTexture.setType(TextTexture.TextType.NORMAL);
            qtyLabel = new ImageWidget(qtyBtnX + btnSize + 2, qtyY, qtyWidth, btnSize, qtyTexture);
            this.addWidget(qtyLabel);

            // 加号按钮
            increaseBtn = createSmallButton(qtyBtnX + btnSize + qtyWidth + 4, qtyY, btnSize, btnSize, "+",
                    clickData -> {
                        if (quantity < maxQty && quantity < availableStock) {
                            quantity++;
                            holder.updateSelectedQuantity(tradeItem.itemId, quantity);
                            updateDisplay();
                            holder.updateTotalPrice();
                        }
                    });
            increaseBtn.setActive(inStock && quantity < maxQty && quantity < availableStock);
            this.addWidget(increaseBtn);

            // 小计
            TextTexture subtotalTexture = new TextTexture(String.format(Locale.US, "%.2f", getSubtotal()),
                    quantity > 0 ? COLOR_TEXT_GOLD : 0xFF666666);
            subtotalTexture.setType(TextTexture.TextType.LEFT);
            subtotalLabel = new ImageWidget((int)(width * 0.88f), (height - textHeight) / 2, (int)(width * 0.15f), textHeight, subtotalTexture);
            this.addWidget(subtotalLabel);
        }

        // 动态更新显示，不重建整个列表
        public void updateDisplay() {
            boolean inStock = tradeItem.isInStock();
            int maxQty = tradeItem.getMaxQuantity();
            int availableStock = tradeItem.retail ? tradeItem.currentStock : tradeItem.getStockInStacks();

            // 更新数量显示
            TextTexture qtyTexture = new TextTexture(quantity + " " + tradeItem.getUnit(), quantity > 0 ? COLOR_TEXT_GREEN : 0xFFCCCCCC);
            qtyTexture.setType(TextTexture.TextType.NORMAL);
            qtyLabel.setImage(qtyTexture);

            // 更新小计显示
            TextTexture subtotalTexture = new TextTexture(String.format(Locale.US, "%.2f", getSubtotal()),
                    quantity > 0 ? COLOR_TEXT_GOLD : 0xFF666666);
            subtotalTexture.setType(TextTexture.TextType.LEFT);
            subtotalLabel.setImage(subtotalTexture);

            // 更新按钮状态
            decreaseBtn.setActive(quantity > 0);
            increaseBtn.setActive(inStock && quantity < maxQty && quantity < availableStock);
        }

        // 刷新库存显示
        public void refreshStock() {
            rebuildWidgets();
        }

        public void setQuantity(int qty) {
            this.quantity = qty;
            holder.updateSelectedQuantity(tradeItem.itemId, qty);
            updateDisplay();
        }

        public double getSubtotal() {
            return tradeItem.sellPrice * quantity;
        }

        public TradeItem getTradeItem() {
            return tradeItem;
        }
    }

    private static class ItemIconWidget extends WidgetGroup {
        private final Item item;

        public ItemIconWidget(int x, int y, int width, int height, Item item) {
            this.setSelfPosition(x, y);
            this.setSize(width, height);
            this.item = item;
        }

        @Override
        public void drawInBackground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);

            int x = getPosition().x;
            int y = getPosition().y;

            // 渲染物品图标
            graphics.renderItem(new ItemStack(item), x, y);
        }
    }

    // ==================== UI Holder 类 ====================

    public static class BuyUIHolder implements IUIHolder {
        private final BlockPos controlBoxPos;
        private final String buildingFileName;
        private final String buildingName;
        private final int restockInterval;

        // 数据
        private final List<TradeItem> allTradeItems = new ArrayList<>();
        private List<TradeItem> filteredTradeItems = new ArrayList<>();
        private final List<TradeItemRow> itemRows = new ArrayList<>();
        private final Map<String, Integer> selectedQuantities = new HashMap<>();

        // 分页
        private int currentPage = 0;
        private int itemsPerPage = 6;
        private int totalPages = 1;

        // Widget 引用
        private WidgetGroup listGroup;
        private ButtonWidget prevButton;
        private ButtonWidget nextButton;
        private ButtonWidget purchaseButton;
        private ImageWidget pageLabel;
        private ImageWidget totalLabel;
        private TextFieldWidget searchBox;

        // 搜索
        private String searchText = "";

        public BuyUIHolder(BlockPos pos, String buildingFileName) {
            this.controlBoxPos = pos;
            this.buildingFileName = buildingFileName;

            CommercialBuildingConfig config = CommercialClientData.getConfig(buildingFileName);
            if (config != null) {
                this.buildingName = config.getBuildingName();
                this.restockInterval = config.getRestockInterval();
                loadTradeItems(config);
            } else {
                // 如果配置无法加载，使用默认值
                this.buildingName = buildingFileName != null ? buildingFileName : "未知建筑";
                this.restockInterval = 12000;
                // 尝试从库存数据加载物品
                loadTradeItemsFromStock();
            }

            this.filteredTradeItems = new ArrayList<>(allTradeItems);
            updateTotalPages();
        }

        public ModularUI createModularUI() {
            return CommercialBuyScreen.createUI(this);
        }

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

        // ==================== 初始化 ====================

        public void init() {
            if (searchBox != null) {
                searchBox.setTextResponder(text -> {
                    this.onSearchChanged(text);
                });
                searchBox.setCurrentString("");
                // 确保搜索框可以接收输入
                searchBox.setActive(true);
            }
            refreshStockFromServer();
            rebuildItemList();
            updatePaginationButtons();
        }

        // 执行搜索
        public void performSearch() {
            if (searchBox != null) {
                String text = searchBox.getCurrentString();
                this.onSearchChanged(text);
            }
        }

        public void setListGroup(WidgetGroup listGroup) {
            this.listGroup = listGroup;
        }

        public void setPrevButton(ButtonWidget prevButton) {
            this.prevButton = prevButton;
        }

        public void setNextButton(ButtonWidget nextButton) {
            this.nextButton = nextButton;
        }

        public void setPurchaseButton(ButtonWidget purchaseButton) {
            this.purchaseButton = purchaseButton;
        }

        public void setPageLabel(ImageWidget pageLabel) {
            this.pageLabel = pageLabel;
        }

        public void setTotalLabel(ImageWidget totalLabel) {
            this.totalLabel = totalLabel;
        }

        public void setSearchBox(TextFieldWidget searchBox) {
            this.searchBox = searchBox;
        }

        // ==================== 数据加载 ====================

        private void loadTradeItems(CommercialBuildingConfig config) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            var itemRegistry = mc.level.registryAccess().registry(Registries.ITEM).orElse(null);
            if (itemRegistry == null) return;

            Map<String, CommercialHiredData.StockInfo> stockInfoMap = CommercialClientData.getStock(controlBoxPos);

            for (var trade : config.getTrades()) {
                if (trade.getSellPrice() > 0) {
                    Item item = itemRegistry.getOptional(
                            ResourceLocation.tryParse(trade.getItemId())
                    ).orElse(null);

                    if (item != null) {
                        String displayName = item.getDefaultInstance().getHoverName().getString();
                        int maxStock = trade.getMaxStock();
                        int restockAmount = trade.getRestockAmount();

                        TradeItem tradeItem = new TradeItem(
                                trade.getItemId(),
                                item,
                                displayName,
                                trade.getSellPrice(),
                                maxStock,
                                restockAmount,
                                trade.isRetail()
                        );

                        CommercialHiredData.StockInfo stockInfo = stockInfoMap.get(trade.getItemId());
                        if (stockInfo != null) {
                            tradeItem.currentStock = stockInfo.getCurrentStock();
                            tradeItem.lastRestockTick = stockInfo.getLastRestockTime();
                            tradeItem.nextRestockTick = tradeItem.lastRestockTick + restockInterval;
                        } else {
                            // 如果没有收到库存数据，显示为0（库存未初始化或数据未同步）
                            tradeItem.currentStock = 0;
                        }

                        allTradeItems.add(tradeItem);
                    }
                }
            }
        }

        // 从库存数据加载交易物品（当配置无法加载时使用）
        private void loadTradeItemsFromStock() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            var itemRegistry = mc.level.registryAccess().registry(Registries.ITEM).orElse(null);
            if (itemRegistry == null) return;

            Map<String, CommercialHiredData.StockInfo> stockInfoMap = CommercialClientData.getStock(controlBoxPos);

            for (Map.Entry<String, CommercialHiredData.StockInfo> entry : stockInfoMap.entrySet()) {
                String itemId = entry.getKey();
                CommercialHiredData.StockInfo stockInfo = entry.getValue();

                Item item = itemRegistry.getOptional(
                        ResourceLocation.tryParse(itemId)
                ).orElse(null);

                if (item != null) {
                    String displayName = item.getDefaultInstance().getHoverName().getString();
                    int maxStock = stockInfo.getMaxStock();

                    // 创建交易物品，使用默认价格（可以从服务器同步或配置文件读取）
                    TradeItem tradeItem = new TradeItem(
                            itemId,
                            item,
                            displayName,
                            10.0, // 默认售价
                            maxStock,
                            maxStock / 2, // 默认补货量
                            true // 零售模式
                    );

                    allTradeItems.add(tradeItem);
                }
            }
        }

        // ==================== 列表重建 ====================

        private void rebuildItemList() {
            if (listGroup == null) return;

            listGroup.clearAllWidgets();
            itemRows.clear();

            // 使用 listGroup 的实际尺寸计算每页显示数量
            int rowWidth = listGroup.getSize().width - 4;
            int listHeight = listGroup.getSize().height;
            int rowHeight = Math.max(28, listHeight / 6);
            
            // 根据列表高度动态计算每页可显示的物品数量
            int calculatedItemsPerPage = Math.max(1, listHeight / rowHeight);
            
            // 确保当前页码有效
            int maxPage = Math.max(0, (filteredTradeItems.size() - 1) / calculatedItemsPerPage);
            currentPage = Math.min(currentPage, maxPage);
            
            int startIndex = currentPage * calculatedItemsPerPage;
            int endIndex = Math.min(startIndex + calculatedItemsPerPage, filteredTradeItems.size());

            int yOffset = 0;

            for (int i = startIndex; i < endIndex; i++) {
                TradeItem item = filteredTradeItems.get(i);
                TradeItemRow row = new TradeItemRow(2, yOffset, rowWidth, rowHeight, item, this);

                row.setQuantity(getSelectedQuantity(item.itemId));

                listGroup.addWidget(row);
                itemRows.add(row);
                yOffset += rowHeight;
            }

            // 更新分页信息
            itemsPerPage = calculatedItemsPerPage;
            updateTotalPages();
            updatePaginationButtons();
            
            // 更新总计显示
            updateTotalPrice();
        }

        // 刷新库存显示（用于库存同步时）
        private void refreshStockDisplay() {
            if (listGroup == null || itemRows.isEmpty()) return;

            for (TradeItemRow row : itemRows) {
                TradeItem item = row.getTradeItem();
                if (item != null) {
                    row.refreshStock();
                }
            }
            updateTotalPrice();
        }

        // ==================== 搜索 ====================

        public void onSearchChanged(String text) {
            if (text == null) text = "";
            this.searchText = text.toLowerCase().trim();

            // 检查 listGroup 是否已初始化
            if (listGroup == null) return;

            // 过滤物品
            if (searchText.isEmpty()) {
                filteredTradeItems = new ArrayList<>(allTradeItems);
            } else {
                String searchQuery = this.searchText;
                filteredTradeItems = allTradeItems.stream()
                        .filter(item -> item.displayName != null &&
                                item.displayName.toLowerCase().contains(searchQuery))
                        .collect(Collectors.toList());
            }

            currentPage = 0;

            // 使用 listGroup 的实际尺寸计算每页显示数量
            int rowWidth = listGroup.getSize().width - 4;
            int listHeight = listGroup.getSize().height;
            int rowHeight = Math.max(28, listHeight / 6);
            itemsPerPage = Math.max(1, listHeight / rowHeight);
            
            updateTotalPages();

            // 清空并重建列表
            listGroup.clearAllWidgets();
            itemRows.clear();

            int startIndex = currentPage * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, filteredTradeItems.size());

            int yOffset = 0;

            for (int i = startIndex; i < endIndex; i++) {
                TradeItem item = filteredTradeItems.get(i);
                TradeItemRow row = new TradeItemRow(2, yOffset, rowWidth, rowHeight, item, this);

                row.setQuantity(getSelectedQuantity(item.itemId));

                listGroup.addWidget(row);
                itemRows.add(row);
                yOffset += rowHeight;
            }

            updateTotalPrice();
            updatePaginationButtons();
        }

        // ==================== 分页 ====================

        private void updateTotalPages() {
            totalPages = Math.max(1, (filteredTradeItems.size() + itemsPerPage - 1) / itemsPerPage);
            currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));
        }

        public void prevPage() {
            if (currentPage > 0) {
                currentPage--;
                rebuildItemList();
                updatePaginationButtons();
            }
        }

        public void nextPage() {
            if (currentPage < totalPages - 1) {
                currentPage++;
                rebuildItemList();
                updatePaginationButtons();
            }
        }

        public void goToPage(int page) {
            if (page >= 0 && page < totalPages && page != currentPage) {
                currentPage = page;
                rebuildItemList();
                updatePaginationButtons();
            }
        }

        private void updatePaginationButtons() {
            if (prevButton != null) prevButton.setActive(currentPage > 0);
            if (nextButton != null) nextButton.setActive(currentPage < totalPages - 1);
            if (pageLabel != null) {
                TextTexture texture = new TextTexture((currentPage + 1) + " / " + totalPages, 0xFFCCCCCC);
                texture.setType(TextTexture.TextType.NORMAL);
                pageLabel.setImage(texture);
            }
        }

        // ==================== 价格和购买 ====================

        public void updateTotalPrice() {
            double total = 0.0;
            for (TradeItem item : allTradeItems) {
                int quantity = getSelectedQuantity(item.itemId);
                if (quantity > 0) {
                    total += item.sellPrice * quantity;
                }
            }

            if (totalLabel != null) {
                TextTexture texture = new TextTexture(String.format(Locale.US, "总计: %.2f 元", total), COLOR_TEXT_GOLD);
                texture.setType(TextTexture.TextType.NORMAL);
                totalLabel.setImage(texture);
            }

            if (purchaseButton != null) {
                purchaseButton.setActive(total > 0);
            }
        }

        public void resetQuantities() {
            selectedQuantities.clear();
            rebuildItemList();
            updateTotalPrice();
        }

        // 清空搜索，显示所有物品
        public void clearSearch() {
            if (searchBox != null) {
                searchBox.setCurrentString("");
            }
            // 直接重置过滤列表，不依赖 onSearchChanged 的 listGroup 检查
            this.searchText = "";
            this.filteredTradeItems = new ArrayList<>(allTradeItems);
            this.currentPage = 0;
            updateTotalPages();
            rebuildItemList();
            updatePaginationButtons();
        }

        public void purchaseItems() {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;

            Map<String, CommercialBuyPacket.BuyItemInfo> itemsMap = new HashMap<>();
            double totalPrice = 0.0;

            for (TradeItem tradeItem : allTradeItems) {
                int qty = getSelectedQuantity(tradeItem.itemId);
                if (qty > 0) {
                    itemsMap.put(tradeItem.itemId, new CommercialBuyPacket.BuyItemInfo(qty, tradeItem.retail));
                    totalPrice += tradeItem.sellPrice * qty;
                }
            }

            if (itemsMap.isEmpty()) {
                player.sendSystemMessage(nn(Component.translatable("message.commercial_buy.no_selection")));
                return;
            }

            if (!hasEnoughInventorySpace(player, itemsMap)) {
                player.sendSystemMessage(nn(Component.translatable("message.commercial_buy.no_space")));
                return;
            }

            NetworkManager.INSTANCE.sendToServer(
                    new CommercialBuyPacket(controlBoxPos, buildingFileName, itemsMap, totalPrice)
            );

            mc.getSoundManager().play(
                    nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.MONEY_COLLECT.get()), 1.0F, 1.0F))
            );

            selectedQuantities.clear();
            mc.setScreen(null);
        }

        private boolean hasEnoughInventorySpace(Player player, Map<String, CommercialBuyPacket.BuyItemInfo> itemsMap) {
            // 简化检查，实际实现需要更复杂的逻辑
            return true;
        }

        // ==================== 库存同步 ====================

        public void refreshStockFromServer() {
            NetworkManager.sendToServer(
                    new RequestStockSyncPacket(controlBoxPos, buildingFileName)
            );
        }

        // 从客户端数据更新库存显示
        public void updateStockFromClientData() {
            Map<String, CommercialHiredData.StockInfo> stockMap = CommercialClientData.getStock(controlBoxPos);
            if (stockMap == null || stockMap.isEmpty()) return;

            boolean stockChanged = false;
            for (TradeItem item : allTradeItems) {
                CommercialHiredData.StockInfo stockInfo = stockMap.get(item.itemId);
                if (stockInfo != null) {
                    if (item.currentStock != stockInfo.getCurrentStock()) {
                        item.currentStock = stockInfo.getCurrentStock();
                        item.lastRestockTick = stockInfo.getLastRestockTime();
                        item.nextRestockTick = item.lastRestockTick + restockInterval;
                        stockChanged = true;
                    }
                }
            }

            // 如果库存有变化，刷新显示
            if (stockChanged) {
                refreshStockDisplay();
            }
        }

        public void onBack() {
            Minecraft.getInstance().setScreen(null);
        }

        private int getSelectedQuantity(String itemId) {
            return selectedQuantities.getOrDefault(itemId, 0);
        }

        private void updateSelectedQuantity(String itemId, int quantity) {
            if (quantity > 0) {
                selectedQuantities.put(itemId, quantity);
            } else {
                selectedQuantities.remove(itemId);
            }
        }
    }

    // ==================== 交易物品类 ====================

    public static class TradeItem {
        public final String itemId;
        public final Item item;
        public final String displayName;
        public final double sellPrice;
        public final int maxStock;
        public final int restockAmount;
        public final boolean retail; // 零售模式：true=一个一个卖，false=整组卖
        public int currentStock;
        public long lastRestockTick;
        public long nextRestockTick;

        public TradeItem(String itemId, Item item, String displayName, double sellPrice, int maxStock, int restockAmount, boolean retail) {
            this.itemId = itemId;
            this.item = item;
            this.displayName = displayName;
            this.sellPrice = sellPrice;
            this.maxStock = maxStock;
            this.restockAmount = restockAmount;
            this.retail = retail;
            this.currentStock = 0; // 默认库存为0，等待服务器同步
            this.lastRestockTick = 0;
            this.nextRestockTick = 12000;
        }

        public boolean isInStock() {
            return currentStock > 0;
        }

        public String getStockDisplay() {
            if (retail) {
                return String.format("%d/%d 个", currentStock, maxStock);
            }
            return String.format("%d/%d 组", getStockInStacks(), getMaxStockInStacks());
        }

        public int getStockInStacks() {
            return currentStock / 64;
        }

        public int getMaxStockInStacks() {
            return maxStock / 64;
        }

        public int getMaxQuantity() {
            return retail ? maxStock : 64;
        }

        public String getUnit() {
            return retail ? "个" : "组";
        }
    }
}
