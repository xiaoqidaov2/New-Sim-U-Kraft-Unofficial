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
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestStockSyncPacket;
import com.xiaoliang.simukraft.network.SellToNPCPacket;
import com.xiaoliang.simukraft.Simukraft;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统一商业建筑出售界面 - 使用LDLib框架
 * 玩家出售物品给NPC
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings({"null"})
public class CommercialSellScreen extends ModularUIGuiContainer {

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
    private static final int COLOR_ROW_SELECTED = 0x22224488;
    private static final int COLOR_ROW_NO_ITEMS = 0x44554444;
    private static final int COLOR_BUTTON_BG = 0xFF3A3A3A;
    private static final int COLOR_BUTTON_HOVER = 0xFF4A4A4A;
    private static final int COLOR_BUTTON_BORDER = 0xFFADD8E6;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_HEADER = 0xFF88AABB;
    private static final int COLOR_TEXT_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_TEXT_GRAY = 0xFF777777;
    private static final int COLOR_TEXT_GOLD = 0xFFFFCC66;
    private static final int COLOR_TEXT_GREEN = 0xFF66FF88;
    private static final int COLOR_TEXT_RED = 0xFFFF6666;

    // ==================== 成员变量 ====================

    private final SellUIHolder holder;

    // ==================== 构造函数 ====================

    public CommercialSellScreen(BlockPos pos, String buildingFileName) {
        super(createHolderAndUI(pos, buildingFileName), 0);
        this.holder = ((ModularUI) this.modularUI).holder instanceof SellUIHolder
                ? (SellUIHolder) ((ModularUI) this.modularUI).holder
                : null;

        playOpenSound();
    }

    private void playOpenSound() {
        Minecraft.getInstance().getSoundManager().play(
                nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));
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

    private static ModularUI createHolderAndUI(BlockPos pos, String buildingFileName) {
        SellUIHolder holder = new SellUIHolder(pos, buildingFileName);
        return holder.createModularUI();
    }

    // ==================== UI 创建 ====================

    private static ModularUI createUI(SellUIHolder holder) {
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
        headerGroup.addWidget(searchBox);
        holder.setSearchBox(searchBox);

        // 搜索按钮 - 点击执行搜索（放大镜图标，与搜索框同高）
        ButtonWidget searchButton = createSmallButton((int)(windowWidth * 0.39f), headerHeight / 2, searchBoxHeight, searchBoxHeight, "\uD83D\uDD0D",
                clickData -> holder.performSearch());
        headerGroup.addWidget(searchButton);

        // 刷新按钮（圆箭头图标，与搜索框同高）
        ButtonWidget refreshButton = createSmallButton((int)(windowWidth * 0.39f) + searchBoxHeight + 2, headerHeight / 2, searchBoxHeight, searchBoxHeight, "\u27F3",
                clickData -> holder.refreshItems());
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
        int colHas = (int)(windowWidth * 0.475f);
        int colQty = (int)(windowWidth * 0.60f);
        int colSubtotal = (int)(windowWidth * 0.88f);

        listHeader.addWidget(createHeaderLabel(colName, 0, (int)(windowWidth * 0.15f), (int)(listHeight * 0.13f), "物品"));
        listHeader.addWidget(createHeaderLabel(colPrice, 0, (int)(windowWidth * 0.10f), (int)(listHeight * 0.13f), "单价"));
        listHeader.addWidget(createHeaderLabel(colHas, 0, (int)(windowWidth * 0.10f), (int)(listHeight * 0.13f), "持有"));
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
        ButtonWidget resetButton = createButton(sideMargin + backBtnWidth + btnSpacing, btnY, resetBtnWidth, btnHeight, "gui.commercial_sell.reset",
                clickData -> {
                    Simukraft.LOGGER.debug("[CommercialSellScreen] Reset button clicked!");
                    holder.clearSearch();
                });
        footerGroup.addWidget(resetButton);

        // 出售按钮（右侧）
        int sellBtnWidth = Math.max(60, (int)(windowWidth * 0.15f));
        ButtonWidget sellButton = createButton(windowWidth - sellBtnWidth - sideMargin, btnY, sellBtnWidth, btnHeight, "gui.commercial_sell.sell",
                clickData -> holder.sellItems());
        sellButton.setActive(false);
        footerGroup.addWidget(sellButton);
        holder.setSellButton(sellButton);

        modularUI.widget(rootGroup);
        modularUI.initWidgets();

        // 初始化 holder（在 initWidgets 之后，确保所有 widget 已初始化）
        holder.init();

        return modularUI;
    }

    // 公共方法：当收到服务器库存同步时调用
    public void onStockSyncReceived() {
        if (holder != null) {
            holder.updateStockFromClientData();
        }
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

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 渲染背景遮罩
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);
        super.render(nn(guiGraphics), mouseX, mouseY, partialTicks);
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(String value) {
        return nn(value);
    }

    // ==================== 物品行组件 ====================

    private static class BuyItemRow extends WidgetGroup {
        private final BuyItem buyItem;
        private int selectedStacks = 0;
        private ImageWidget qtyLabel;
        private ImageWidget subtotalLabel;
        private ButtonWidget decreaseBtn;
        private ButtonWidget increaseBtn;

        public BuyItemRow(int x, int y, int width, int height, BuyItem buyItem, SellUIHolder holder) {
            this.setSelfPosition(x, y);
            this.setSize(width, height);
            this.buyItem = buyItem;

            boolean hasItems = buyItem.playerHasStacks > 0;

            // 背景
            int bgColor = hasItems ? COLOR_ROW_EVEN : COLOR_ROW_NO_ITEMS;
            if (selectedStacks > 0) bgColor = COLOR_ROW_SELECTED;
            this.setBackground(new ColorRectTexture(bgColor));

            // 相对位置计算
            int iconSize = Math.min(16, height - 4);
            int textHeight = Math.max(8, height / 3);
            int btnSize = Math.max(14, height / 2);
            int qtyWidth = Math.max(30, width / 8);

            // 物品图标
            ItemIconWidget iconWidget = new ItemIconWidget((int)(width * 0.015f), (height - iconSize) / 2, iconSize, iconSize, buyItem.item);
            this.addWidget(iconWidget);

            // 物品名称
            String name = buyItem.displayName;
            int maxNameLen = Math.max(8, width / 25);
            if (name.length() > maxNameLen) name = name.substring(0, maxNameLen - 1) + "…";
            TextTexture nameTexture = new TextTexture(name, hasItems ? COLOR_TEXT_NORMAL : COLOR_TEXT_GRAY);
            nameTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget nameLabel = new ImageWidget((int)(width * 0.075f), (height - textHeight) / 2, (int)(width * 0.25f), textHeight, nameTexture);
            this.addWidget(nameLabel);

            // 单价
            TextTexture priceTexture = new TextTexture(String.format(Locale.US, "%.2f", buyItem.sellPrice), COLOR_TEXT_GOLD);
            priceTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget priceLabel = new ImageWidget((int)(width * 0.34f), (height - textHeight) / 2, (int)(width * 0.12f), textHeight, priceTexture);
            this.addWidget(priceLabel);

            // 持有数量
            int hasColor = hasItems ? COLOR_TEXT_GREEN : COLOR_TEXT_RED;
            TextTexture hasTexture = new TextTexture(buyItem.playerHasStacks + "组", hasColor);
            hasTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget hasLabel = new ImageWidget((int)(width * 0.47f), (height - textHeight) / 2, (int)(width * 0.12f), textHeight, hasTexture);
            this.addWidget(hasLabel);

            // 数量控制
            int qtyY = (height - btnSize) / 2;
            int qtyBtnX = (int)(width * 0.60f);

            // 减号按钮
            decreaseBtn = createSmallButton(qtyBtnX, qtyY, btnSize, btnSize, "−",
                    clickData -> {
                        if (selectedStacks > 0) {
                            selectedStacks--;
                            updateDisplay();
                            holder.updateTotalPrice();
                        }
                    });
            decreaseBtn.setActive(hasItems && selectedStacks > 0);
            this.addWidget(decreaseBtn);

            // 数量显示
            TextTexture qtyTexture = new TextTexture(selectedStacks + "组", selectedStacks > 0 ? COLOR_TEXT_GREEN : 0xFFCCCCCC);
            qtyTexture.setType(TextTexture.TextType.NORMAL);
            qtyLabel = new ImageWidget(qtyBtnX + btnSize + 2, qtyY, qtyWidth, btnSize, qtyTexture);
            this.addWidget(qtyLabel);

            // 加号按钮
            increaseBtn = createSmallButton(qtyBtnX + btnSize + qtyWidth + 4, qtyY, btnSize, btnSize, "+",
                    clickData -> {
                        if (selectedStacks < buyItem.playerHasStacks && selectedStacks < buyItem.maxBuyStacks) {
                            selectedStacks++;
                            updateDisplay();
                            holder.updateTotalPrice();
                        }
                    });
            increaseBtn.setActive(hasItems && selectedStacks < buyItem.playerHasStacks && selectedStacks < buyItem.maxBuyStacks);
            this.addWidget(increaseBtn);

            // 小计
            TextTexture subtotalTexture = new TextTexture(String.format(Locale.US, "%.2f", getSubtotal()),
                    selectedStacks > 0 ? COLOR_TEXT_GOLD : 0xFF666666);
            subtotalTexture.setType(TextTexture.TextType.LEFT);
            subtotalLabel = new ImageWidget((int)(width * 0.88f), (height - textHeight) / 2, (int)(width * 0.15f), textHeight, subtotalTexture);
            this.addWidget(subtotalLabel);
        }

        public int getSelectedStacks() {
            return selectedStacks;
        }

        public void setSelectedStacks(int stacks) {
            this.selectedStacks = stacks;
            updateDisplay();
        }

        public double getSubtotal() {
            return buyItem.sellPrice * selectedStacks;
        }

        public BuyItem getBuyItem() {
            return buyItem;
        }

        public void updateDisplay() {
            // 更新数量显示
            TextTexture qtyTexture = new TextTexture(selectedStacks + "组", selectedStacks > 0 ? COLOR_TEXT_GREEN : 0xFFCCCCCC);
            qtyTexture.setType(TextTexture.TextType.NORMAL);
            qtyLabel.setImage(qtyTexture);

            // 更新小计显示
            TextTexture subtotalTexture = new TextTexture(String.format(Locale.US, "%.2f", getSubtotal()),
                    selectedStacks > 0 ? COLOR_TEXT_GOLD : 0xFF666666);
            subtotalTexture.setType(TextTexture.TextType.LEFT);
            subtotalLabel.setImage(subtotalTexture);

            // 更新按钮状态
            decreaseBtn.setActive(selectedStacks > 0);
            increaseBtn.setActive(selectedStacks < buyItem.playerHasStacks && selectedStacks < buyItem.maxBuyStacks);
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
            super.drawInBackground(nn(graphics), mouseX, mouseY, partialTicks);

            int x = getPosition().x;
            int y = getPosition().y;

            // 渲染物品图标
            graphics.renderItem(new ItemStack(nn(item)), x, y);
        }
    }

    // ==================== UI Holder 类 ====================

    public static class SellUIHolder implements IUIHolder {
        private final BlockPos controlBoxPos;
        private final String buildingFileName;
        private final String buildingName;

        // 数据
        private final List<BuyItem> allBuyItems = new ArrayList<>();
        private List<BuyItem> filteredBuyItems = new ArrayList<>();
        private final List<BuyItemRow> itemRows = new ArrayList<>();

        // 分页
        private int currentPage = 0;
        private int itemsPerPage = 6;
        private int totalPages = 1;

        // Widget 引用
        private WidgetGroup listGroup;
        private ButtonWidget prevButton;
        private ButtonWidget nextButton;
        private ButtonWidget sellButton;
        private ImageWidget pageLabel;
        private ImageWidget totalLabel;
        private TextFieldWidget searchBox;

        // 搜索
        private String searchText = "";

        public SellUIHolder(BlockPos pos, String buildingFileName) {
            this.controlBoxPos = pos;
            this.buildingFileName = buildingFileName;

            CommercialBuildingConfig config = CommercialClientData.getConfig(buildingFileName);
            if (config != null) {
                this.buildingName = config.getBuildingName();
                loadBuyItems(config);
            } else {
                // 如果配置无法加载，使用默认值
                this.buildingName = buildingFileName != null ? buildingFileName : "未知建筑";
                // 尝试从库存数据加载收购物品
                loadBuyItemsFromStock();
            }

            this.filteredBuyItems = new ArrayList<>(allBuyItems);
            updateTotalPages();
        }

        public ModularUI createModularUI() {
            return CommercialSellScreen.createUI(this);
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
                searchBox.setTextResponder(this::onSearchChanged);
                searchBox.setCurrentString("");
            }
            refreshStockFromServer();
            rebuildItemList();
            updatePaginationButtons();
        }

        // 执行搜索
        public void performSearch() {
            if (searchBox != null) {
                String text = searchBox.getCurrentString();
                Simukraft.LOGGER.debug("[CommercialSellScreen] Performing search with: {}", text);
                this.onSearchChanged(text);
            }
        }

        public void refreshStockFromServer() {
            NetworkManager.sendToServer(
                    new RequestStockSyncPacket(controlBoxPos, buildingFileName)
            );
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

        public void setSellButton(ButtonWidget sellButton) {
            this.sellButton = sellButton;
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

        private void loadBuyItems(CommercialBuildingConfig config) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            var itemRegistry = mc.level.registryAccess().registry(Registries.ITEM).orElse(null);
            if (itemRegistry == null) return;

            Player player = mc.player;
            if (player == null) return;

            for (var buyTrade : config.getBuyTrades()) {
                Item item = itemRegistry.getOptional(
                        ResourceLocation.tryParse(buyTrade.getItemId())
                ).orElse(null);

                if (item != null) {
                    String displayName = item.getDefaultInstance().getHoverName().getString();
                    BuyItem buyItem = new BuyItem(
                            buyTrade.getItemId(),
                            item,
                            displayName,
                            buyTrade.getBuyPrice(),
                            buyTrade.getMaxBuyAmount()
                    );
                    buyItem.playerHasStacks = countPlayerItems(player, buyTrade.getItemId()) / BuyItem.STACK_SIZE;
                    allBuyItems.add(buyItem);
                }
            }
        }

        private int countPlayerItems(Player player, String itemId) {
            var rl = ResourceLocation.tryParse(itemId);
            if (rl == null) return 0;
            var reg = player.level().registryAccess().registry(Registries.ITEM).orElse(null);
            if (reg == null) return 0;
            var target = reg.getOptional(rl).orElse(null);
            if (target == null) return 0;

            int count = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && rl.equals(reg.getKey(stack.getItem()))) {
                    count += stack.getCount();
                }
            }
            return count;
        }

        // 从库存数据加载收购物品（当配置无法加载时使用）
        private void loadBuyItemsFromStock() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            var itemRegistry = mc.level.registryAccess().registry(Registries.ITEM).orElse(null);
            if (itemRegistry == null) return;

            Player player = mc.player;
            if (player == null) return;

            Map<String, CommercialHiredData.StockInfo> stockInfoMap = CommercialClientData.getStock(controlBoxPos);

            for (Map.Entry<String, CommercialHiredData.StockInfo> entry : stockInfoMap.entrySet()) {
                String itemId = entry.getKey();

                Item item = itemRegistry.getOptional(
                        ResourceLocation.tryParse(itemId)
                ).orElse(null);

                if (item != null) {
                    String displayName = item.getDefaultInstance().getHoverName().getString();

                    // 创建收购物品，使用默认价格
                    BuyItem buyItem = new BuyItem(
                            itemId,
                            item,
                            displayName,
                            5.0, // 默认收购价
                            64   // 默认最大收购量
                    );
                    buyItem.playerHasStacks = countPlayerItems(player, itemId) / BuyItem.STACK_SIZE;
                    allBuyItems.add(buyItem);
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
            int maxPage = Math.max(0, (filteredBuyItems.size() - 1) / calculatedItemsPerPage);
            currentPage = Math.min(currentPage, maxPage);
            
            int startIndex = currentPage * calculatedItemsPerPage;
            int endIndex = Math.min(startIndex + calculatedItemsPerPage, filteredBuyItems.size());

            int yOffset = 0;

            for (int i = startIndex; i < endIndex; i++) {
                BuyItem item = filteredBuyItems.get(i);
                BuyItemRow row = new BuyItemRow(2, yOffset, rowWidth, rowHeight, item, this);
                listGroup.addWidget(row);
                itemRows.add(row);
                yOffset += rowHeight;
            }
            
            // 更新分页信息
            itemsPerPage = calculatedItemsPerPage;
            updateTotalPages();
            updatePaginationButtons();
        }

        // ==================== 搜索 ====================

        public void onSearchChanged(String text) {
            if (text == null) text = "";
            this.searchText = text.toLowerCase().trim();

            if (listGroup == null) return;

            if (searchText.isEmpty()) {
                filteredBuyItems = new ArrayList<>(allBuyItems);
            } else {
                String searchQuery = this.searchText;
                filteredBuyItems = allBuyItems.stream()
                        .filter(item -> item.displayName != null &&
                                item.displayName.toLowerCase().contains(searchQuery))
                        .collect(Collectors.toList());
            }

            currentPage = 0;
            updateTotalPages();
            rebuildItemList();
            updatePaginationButtons();
        }

        // ==================== 分页 ====================

        private void updateTotalPages() {
            totalPages = Math.max(1, (filteredBuyItems.size() + itemsPerPage - 1) / itemsPerPage);
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

        private void updatePaginationButtons() {
            if (prevButton != null) prevButton.setActive(currentPage > 0);
            if (nextButton != null) nextButton.setActive(currentPage < totalPages - 1);
            if (pageLabel != null) {
                TextTexture texture = new TextTexture((currentPage + 1) + " / " + totalPages, 0xFFCCCCCC);
                texture.setType(TextTexture.TextType.NORMAL);
                pageLabel.setImage(texture);
            }
        }

        // ==================== 价格和出售 ====================

        public void updateTotalPrice() {
            double total = 0.0;
            for (BuyItemRow row : itemRows) {
                total += row.getSubtotal();
            }

            if (totalLabel != null) {
                TextTexture texture = new TextTexture(String.format(Locale.US, "总计: %.2f 元", total), COLOR_TEXT_GOLD);
                texture.setType(TextTexture.TextType.NORMAL);
                totalLabel.setImage(texture);
            }

            if (sellButton != null) {
                sellButton.setActive(total > 0);
            }
        }

        public void resetQuantities() {
            for (BuyItemRow row : itemRows) {
                row.setSelectedStacks(0);
            }
            updateTotalPrice();
        }

        // 清空搜索，显示所有物品
        public void clearSearch() {
            Simukraft.LOGGER.debug("[CommercialSellScreen] clearSearch called");
            if (searchBox != null) {
                searchBox.setCurrentString("");
                Simukraft.LOGGER.debug("[CommercialSellScreen] Search box cleared");
            }
            // 直接重置过滤列表，不依赖 onSearchChanged 的 listGroup 检查
            this.searchText = "";
            this.filteredBuyItems = new ArrayList<>(allBuyItems);
            this.currentPage = 0;
            updateTotalPages();
            rebuildItemList();
            updatePaginationButtons();
            Simukraft.LOGGER.debug("[CommercialSellScreen] Search cleared, showing all {} items", filteredBuyItems.size());
        }

        public void sellItems() {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;

            long totalSel = itemRows.stream().mapToLong(BuyItemRow::getSelectedStacks).sum();
            if (totalSel == 0) {
                player.sendSystemMessage(nn(Component.translatable("gui.commercial_sell.no_selection")));
                return;
            }

            // 检查是否有足够物品
            for (BuyItemRow row : itemRows) {
                int selected = row.getSelectedStacks();
                if (selected > 0) {
                    BuyItem bi = row.getBuyItem();
                    int cur = countPlayerItems(player, bi.itemId) / BuyItem.STACK_SIZE;
                    if (cur < selected) {
                        player.sendSystemMessage(nn(Component.translatable(
                                "gui.commercial_sell.insufficient_stacks",
                                safeString(bi.displayName), selected, cur)));
                        return;
                    }
                }
            }

            Map<String, Integer> itemsMap = new HashMap<>();
            double totalPrice = 0.0;
            for (BuyItemRow row : itemRows) {
                int selected = row.getSelectedStacks();
                if (selected > 0) {
                    BuyItem bi = row.getBuyItem();
                    itemsMap.put(bi.itemId, selected * BuyItem.STACK_SIZE);
                    totalPrice += row.getSubtotal();
                }
            }

            NetworkManager.INSTANCE.sendToServer(
                    new SellToNPCPacket(controlBoxPos, buildingFileName, itemsMap, totalPrice)
            );

            mc.getSoundManager().play(
                    nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.MONEY_COLLECT.get()), 1.0F, 1.0F))
            );

            mc.setScreen(null);
        }

        public void refreshItems() {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player != null) {
                for (BuyItem bi : allBuyItems) {
                    bi.playerHasStacks = countPlayerItems(player, bi.itemId) / BuyItem.STACK_SIZE;
                }
            }
            rebuildItemList();
        }

        // 从客户端数据更新库存显示
        public void updateStockFromClientData() {
            Map<String, CommercialHiredData.StockInfo> stockMap = CommercialClientData.getStock(controlBoxPos);
            if (stockMap == null || stockMap.isEmpty()) return;

            boolean stockChanged = false;
            for (BuyItem item : allBuyItems) {
                CommercialHiredData.StockInfo stockInfo = stockMap.get(item.itemId);
                if (stockInfo != null) {
                    // 收购界面主要关注当前可收购数量
                    // 这里可以添加逻辑来更新可收购数量
                    stockChanged = true;
                }
            }

            // 如果库存有变化，刷新显示
            if (stockChanged) {
                refreshItems();
            }
        }

        public void onBack() {
            Minecraft.getInstance().setScreen(null);
        }
    }

    // ==================== 收购物品类 ====================

    public static class BuyItem {
        public final String itemId;
        public final Item item;
        public final String displayName;
        public final double buyPrice;
        public final double sellPrice;
        public final int maxBuyStacks;
        public int selectedStacks;
        public int playerHasStacks;
        public static final int STACK_SIZE = 64;

        public BuyItem(String itemId, Item item, String displayName, double buyPrice, int maxBuyAmount) {
            this.itemId = itemId;
            this.item = item;
            this.displayName = displayName;
            this.buyPrice = buyPrice;
            this.sellPrice = Math.round(buyPrice * 0.85 * 100.0) / 100.0;
            this.maxBuyStacks = maxBuyAmount;
            this.selectedStacks = 0;
            this.playerHasStacks = 0;
        }

        public double getSubtotal() {
            return sellPrice * selectedStacks;
        }
    }
}
