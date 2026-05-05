package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.init.ModSoundEvents;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * menglan: NPC出售信息界面
 * 当商店模式为NPC_SELL时，右键NPC显示商店信息（不可购买）
 * 显示：商品、原材料、价格、今日营业额、库存
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings({"null", "unused"})
public class NPCSellInfoScreen extends ModularUIGuiContainer {

    // ==================== 布局常量 ====================
    private static final float WINDOW_WIDTH_RATIO = 0.65f;
    private static final float WINDOW_HEIGHT_RATIO = 0.7f;
    private static final float HEADER_HEIGHT_RATIO = 0.15f;
    private static final float FOOTER_HEIGHT_RATIO = 0.15f;

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
    private static final int COLOR_TEXT_CYAN = 0xFF66CCFF;

    // ==================== 成员变量 ====================
    private final InfoUIHolder holder;

    // ==================== 构造函数 ====================

    public NPCSellInfoScreen(BlockPos pos, String buildingFileName) {
        super(createHolderAndUI(pos, buildingFileName), 0);
        this.holder = ((ModularUI) this.modularUI).holder instanceof InfoUIHolder
                ? (InfoUIHolder) ((ModularUI) this.modularUI).holder
                : null;

        playOpenSound();
    }

    private void playOpenSound() {
        Minecraft.getInstance().getSoundManager().play(
                nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
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

    // ==================== 尺寸计算 ====================

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
        InfoUIHolder holder = new InfoUIHolder(pos, buildingFileName);
        return holder.createModularUI();
    }

    // ==================== UI 创建 ====================

    private static ModularUI createUI(InfoUIHolder holder) {
        Minecraft mc = Minecraft.getInstance();

        int windowWidth = calculateWindowWidth(mc);
        int windowHeight = calculateWindowHeight(mc);
        int headerHeight = Math.max(40, (int)(windowHeight * HEADER_HEIGHT_RATIO));
        int footerHeight = Math.max(40, (int)(windowHeight * FOOTER_HEIGHT_RATIO));
        int listHeight = windowHeight - headerHeight - footerHeight;

        ModularUI modularUI = new ModularUI(new Size(windowWidth, windowHeight), holder, nn(mc.player));

        // 根容器
        WidgetGroup rootGroup = new WidgetGroup();
        rootGroup.setSelfPosition(0, 0);
        rootGroup.setSize(windowWidth, windowHeight);

        // 主窗口背景
        WidgetGroup windowGroup = new WidgetGroup();
        windowGroup.setSelfPosition(0, 0);
        windowGroup.setSize(windowWidth, windowHeight);
        windowGroup.setBackground(new GuiTextureGroup(
                new ColorRectTexture(COLOR_WINDOW_BG).setRadius(8),
                new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(8)
        ));
        rootGroup.addWidget(windowGroup);

        // 标题栏
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

        // 搜索框
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

        // 搜索按钮
        ButtonWidget searchButton = createSmallButton(
                (int)(windowWidth * 0.39f), headerHeight / 2, searchBoxHeight, searchBoxHeight, "\uD83D\uDD0D",
                clickData -> holder.performSearch());
        headerGroup.addWidget(searchButton);

        // 刷新按钮
        ButtonWidget refreshButton = createSmallButton(
                (int)(windowWidth * 0.39f) + searchBoxHeight + 2, headerHeight / 2, searchBoxHeight, searchBoxHeight, "\u27F3",
                clickData -> holder.refreshStockFromServer());
        headerGroup.addWidget(refreshButton);

        // 分页按钮
        int pageBtnWidth = Math.max(20, (int)(windowWidth * 0.06f));
        int pageBtnHeight = Math.max(14, (int)(headerHeight * 0.32f));
        ButtonWidget prevButton = createSmallButton(
                windowWidth - pageBtnWidth * 3 - 20, headerHeight / 2 + 2, pageBtnWidth, pageBtnHeight, "◀",
                clickData -> holder.prevPage());
        headerGroup.addWidget(prevButton);
        holder.setPrevButton(prevButton);

        ButtonWidget nextButton = createSmallButton(
                windowWidth - pageBtnWidth - 5, headerHeight / 2 + 2, pageBtnWidth, pageBtnHeight, "▶",
                clickData -> holder.nextPage());
        headerGroup.addWidget(nextButton);
        holder.setNextButton(nextButton);

        // 页码显示
        TextTexture pageTexture = new TextTexture("1 / 1", 0xFFCCCCCC);
        pageTexture.setType(TextTexture.TextType.NORMAL);
        ImageWidget pageLabel = new ImageWidget(
                windowWidth - pageBtnWidth * 2 - 15, headerHeight / 2 + 2, pageBtnWidth + 10, pageBtnHeight, pageTexture);
        headerGroup.addWidget(pageLabel);
        holder.setPageLabel(pageLabel);

        // 列表区域
        int listY = headerHeight;

        // 表头
        WidgetGroup listHeader = new WidgetGroup();
        listHeader.setSelfPosition((int)(windowWidth * 0.012f), listY);
        listHeader.setSize((int)(windowWidth * 0.976f), (int)(listHeight * 0.15f));
        windowGroup.addWidget(listHeader);

        // 相对列位置 - 与交易界面保持一致
        int colName = (int)(windowWidth * 0.07f);
        int colMaterial = (int)(windowWidth * 0.30f);
        int colPrice = (int)(windowWidth * 0.48f);
        int colStock = (int)(windowWidth * 0.60f);

        listHeader.addWidget(createHeaderLabel(colName, 0, (int)(windowWidth * 0.20f), (int)(listHeight * 0.13f), "商品"));
        listHeader.addWidget(createHeaderLabel(colMaterial, 0, (int)(windowWidth * 0.15f), (int)(listHeight * 0.13f), "原材料"));
        listHeader.addWidget(createHeaderLabel(colPrice, 0, (int)(windowWidth * 0.10f), (int)(listHeight * 0.13f), "售价"));
        listHeader.addWidget(createHeaderLabel(colStock, 0, (int)(windowWidth * 0.12f), (int)(listHeight * 0.13f), "库存"));

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

        // 提示文字
        TextTexture hintTexture = new TextTexture("此商店由NPC运营，仅可查看信息", COLOR_TEXT_CYAN);
        hintTexture.setType(TextTexture.TextType.NORMAL);
        ImageWidget hintLabel = new ImageWidget(0, (int)(footerHeight * 0.05f), windowWidth, (int)(footerHeight * 0.25f), hintTexture);
        footerGroup.addWidget(hintLabel);

        // 返回按钮
        int btnY = (int)(footerHeight * 0.35f);
        int btnHeight = Math.max(16, (int)(footerHeight * 0.5f));
        int sideMargin = Math.max(10, (int)(windowWidth * 0.02f));
        int backBtnWidth = Math.max(50, (int)(windowWidth * 0.12f));
        ButtonWidget backButton = createButton(sideMargin, btnY, backBtnWidth, btnHeight, "gui.button.back",
                clickData -> holder.onBack());
        footerGroup.addWidget(backButton);

        // 初始化
        modularUI.widget(rootGroup);
        modularUI.initWidgets();
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
                                                   java.util.function.Consumer<com.lowdragmc.lowdraglib.gui.util.ClickData> onPress) {
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
                                              java.util.function.Consumer<com.lowdragmc.lowdraglib.gui.util.ClickData> onPress) {
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

    // ==================== 信息行组件 ====================

    private static class InfoItemRow extends WidgetGroup {
        private final TradeItemInfo tradeItem;

        public InfoItemRow(int x, int y, int width, int height, TradeItemInfo tradeItem) {
            this.setSelfPosition(x, y);
            this.setSize(width, height);
            this.tradeItem = tradeItem;

            rebuildWidgets();
        }

        private void rebuildWidgets() {
            this.clearAllWidgets();

            boolean inStock = tradeItem.currentStock > 0;
            int bgColor = inStock ? COLOR_ROW_EVEN : COLOR_ROW_OUT_OF_STOCK;
            this.setBackground(new ColorRectTexture(bgColor));

            int width = getSize().width;
            int height = getSize().height;

            int iconSize = Math.min(16, height - 4);
            int textHeight = Math.max(8, height / 3);

            // 物品图标
            ItemIconWidget iconWidget = new ItemIconWidget(
                    (int)(width * 0.015f), (height - iconSize) / 2, iconSize, iconSize, tradeItem.item);
            this.addWidget(iconWidget);

            // 商品名称
            String name = tradeItem.displayName;
            int maxNameLen = Math.max(8, width / 25);
            if (name.length() > maxNameLen) name = name.substring(0, maxNameLen - 1) + "…";
            TextTexture nameTexture = new TextTexture(name, inStock ? COLOR_TEXT_NORMAL : COLOR_TEXT_GRAY);
            nameTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget nameLabel = new ImageWidget(
                    (int)(width * 0.075f), (height - textHeight) / 2, (int)(width * 0.20f), textHeight, nameTexture);
            this.addWidget(nameLabel);

            // 原材料
            String materialText = tradeItem.materialName != null ? tradeItem.materialName : "无";
            int maxMatLen = Math.max(6, width / 30);
            if (materialText.length() > maxMatLen) materialText = materialText.substring(0, maxMatLen - 1) + "…";
            TextTexture matTexture = new TextTexture(materialText, COLOR_TEXT_CYAN);
            matTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget matLabel = new ImageWidget(
                    (int)(width * 0.29f), (height - textHeight) / 2, (int)(width * 0.15f), textHeight, matTexture);
            this.addWidget(matLabel);

            // 售价
            TextTexture priceTexture = new TextTexture(
                    String.format(Locale.US, "%.2f", tradeItem.sellPrice), COLOR_TEXT_GOLD);
            priceTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget priceLabel = new ImageWidget(
                    (int)(width * 0.47f), (height - textHeight) / 2, (int)(width * 0.10f), textHeight, priceTexture);
            this.addWidget(priceLabel);

            // 库存
            int stockColor = inStock ? COLOR_TEXT_GREEN : COLOR_TEXT_RED;
            if (inStock && tradeItem.currentStock < tradeItem.maxStock * 0.3) stockColor = COLOR_TEXT_YELLOW;
            TextTexture stockTexture = new TextTexture(tradeItem.getStockDisplay(), stockColor);
            stockTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget stockLabel = new ImageWidget(
                    (int)(width * 0.59f), (height - textHeight) / 2, (int)(width * 0.12f), textHeight, stockTexture);
            this.addWidget(stockLabel);


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
            graphics.renderItem(new ItemStack(item), getPosition().x, getPosition().y);
        }
    }

    // ==================== UI Holder 类 ====================

    public static class InfoUIHolder implements IUIHolder {
        private final BlockPos controlBoxPos;
        private final String buildingFileName;
        private final String buildingName;
        private final int restockInterval;

        private final List<TradeItemInfo> allTradeItems = new ArrayList<>();
        private List<TradeItemInfo> filteredTradeItems = new ArrayList<>();

        private int currentPage = 0;
        private int itemsPerPage = 6;
        private int totalPages = 1;

        private WidgetGroup listGroup;
        private ButtonWidget prevButton;
        private ButtonWidget nextButton;
        private ImageWidget pageLabel;
        private TextFieldWidget searchBox;

        private String searchText = "";

        public InfoUIHolder(BlockPos pos, String buildingFileName) {
            this.controlBoxPos = pos;
            this.buildingFileName = buildingFileName;

            CommercialBuildingConfig config = CommercialClientData.getConfig(buildingFileName);
            if (config != null) {
                this.buildingName = config.getBuildingName();
                this.restockInterval = config.getRestockInterval();
                loadTradeItems(config);
            } else {
                this.buildingName = buildingFileName != null ? buildingFileName : "未知建筑";
                this.restockInterval = 12000;
                loadTradeItemsFromStock();
            }

            this.filteredTradeItems = new ArrayList<>(allTradeItems);
            updateTotalPages();
        }

        public ModularUI createModularUI() {
            return NPCSellInfoScreen.createUI(this);
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

        public void init() {
            if (searchBox != null) {
                searchBox.setTextResponder(text -> this.onSearchChanged(text));
                searchBox.setCurrentString("");
                searchBox.setActive(true);
            }
            refreshStockFromServer();
            rebuildItemList();
            updatePaginationButtons();
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

                        // 查找原材料
                        String materialName = null;
                        if (config.getMaterials() != null && !config.getMaterials().isEmpty()) {
                            // 显示第一个原材料作为代表
                            var firstMat = config.getMaterials().get(0);
                            if (firstMat.getItemId() != null) {
                                Item matItem = itemRegistry.getOptional(
                                        ResourceLocation.tryParse(firstMat.getItemId())
                                ).orElse(null);
                                if (matItem != null) {
                                    materialName = matItem.getDefaultInstance().getHoverName().getString();
                                    if (config.getMaterials().size() > 1) {
                                        materialName += "等";
                                    }
                                }
                            }
                        }

                        TradeItemInfo tradeItem = new TradeItemInfo(
                                trade.getItemId(),
                                item,
                                displayName,
                                trade.getSellPrice(),
                                trade.getMaxStock(),
                                trade.isRetail(),
                                materialName
                        );

                        CommercialHiredData.StockInfo stockInfo = stockInfoMap.get(trade.getItemId());
                        if (stockInfo != null) {
                            tradeItem.currentStock = stockInfo.getCurrentStock();
                            tradeItem.todaySold = stockInfo.getDailyBoughtAmount();
                        }

                        allTradeItems.add(tradeItem);
                    }
                }
            }

        }

        private void loadTradeItemsFromStock() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            var itemRegistry = mc.level.registryAccess().registry(Registries.ITEM).orElse(null);
            if (itemRegistry == null) return;

            Map<String, CommercialHiredData.StockInfo> stockInfoMap = CommercialClientData.getStock(controlBoxPos);

            for (Map.Entry<String, CommercialHiredData.StockInfo> entry : stockInfoMap.entrySet()) {
                String itemId = entry.getKey();
                CommercialHiredData.StockInfo stockInfo = entry.getValue();

                Item item = itemRegistry.getOptional(ResourceLocation.tryParse(itemId)).orElse(null);
                if (item != null) {
                    String displayName = item.getDefaultInstance().getHoverName().getString();

                    TradeItemInfo tradeItem = new TradeItemInfo(
                            itemId, item, displayName, 10.0, stockInfo.getMaxStock(), true, null);
                    tradeItem.currentStock = stockInfo.getCurrentStock();
                    tradeItem.todaySold = stockInfo.getDailyBoughtAmount();

                    allTradeItems.add(tradeItem);
                }
            }

        }

        // ==================== 列表重建 ====================

        private void rebuildItemList() {
            if (listGroup == null) return;

            listGroup.clearAllWidgets();

            int rowWidth = listGroup.getSize().width - 4;
            int listHeight = listGroup.getSize().height;
            int rowHeight = Math.max(28, listHeight / 6);

            int calculatedItemsPerPage = Math.max(1, listHeight / rowHeight);
            int maxPage = Math.max(0, (filteredTradeItems.size() - 1) / calculatedItemsPerPage);
            currentPage = Math.min(currentPage, maxPage);

            int startIndex = currentPage * calculatedItemsPerPage;
            int endIndex = Math.min(startIndex + calculatedItemsPerPage, filteredTradeItems.size());

            int yOffset = 0;
            for (int i = startIndex; i < endIndex; i++) {
                TradeItemInfo item = filteredTradeItems.get(i);
                InfoItemRow row = new InfoItemRow(2, yOffset, rowWidth, rowHeight, item);
                listGroup.addWidget(row);
                yOffset += rowHeight;
            }

            itemsPerPage = calculatedItemsPerPage;
            updateTotalPages();
            updatePaginationButtons();
        }

        // ==================== 搜索 ====================

        public void performSearch() {
            if (searchBox != null) {
                onSearchChanged(searchBox.getCurrentString());
            }
        }

        private void onSearchChanged(String text) {
            if (text == null) text = "";
            this.searchText = text.toLowerCase().trim();

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
            rebuildItemList();
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
            }
        }

        public void nextPage() {
            if (currentPage < totalPages - 1) {
                currentPage++;
                rebuildItemList();
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

        // ==================== 库存同步 ====================

        public void refreshStockFromServer() {
            NetworkManager.sendToServer(
                    new RequestStockSyncPacket(controlBoxPos, buildingFileName)
            );
        }

        public void updateStockFromClientData() {
            Map<String, CommercialHiredData.StockInfo> stockMap = CommercialClientData.getStock(controlBoxPos);
            if (stockMap == null || stockMap.isEmpty()) return;

            boolean changed = false;
            for (TradeItemInfo item : allTradeItems) {
                CommercialHiredData.StockInfo stockInfo = stockMap.get(item.itemId);
                if (stockInfo != null) {
                    if (item.currentStock != stockInfo.getCurrentStock() ||
                            item.todaySold != stockInfo.getDailyBoughtAmount()) {
                        item.currentStock = stockInfo.getCurrentStock();
                        item.todaySold = stockInfo.getDailyBoughtAmount();
                        changed = true;
                    }
                }
            }

            if (changed) {
                rebuildItemList();
            }
        }

        public void onBack() {
            Minecraft.getInstance().setScreen(null);
        }

        // ==================== Setter ====================

        public void setListGroup(WidgetGroup listGroup) { this.listGroup = listGroup; }
        public void setPrevButton(ButtonWidget prevButton) { this.prevButton = prevButton; }
        public void setNextButton(ButtonWidget nextButton) { this.nextButton = nextButton; }
        public void setPageLabel(ImageWidget pageLabel) { this.pageLabel = pageLabel; }
        public void setSearchBox(TextFieldWidget searchBox) { this.searchBox = searchBox; }
    }

    // ==================== 交易物品信息类 ====================

    public static class TradeItemInfo {
        public final String itemId;
        public final Item item;
        public final String displayName;
        public final double sellPrice;
        public final int maxStock;
        public final boolean retail;
        public final String materialName;
        public int currentStock;
        public int todaySold;

        public TradeItemInfo(String itemId, Item item, String displayName, double sellPrice,
                             int maxStock, boolean retail, String materialName) {
            this.itemId = itemId;
            this.item = item;
            this.displayName = displayName;
            this.sellPrice = sellPrice;
            this.maxStock = maxStock;
            this.retail = retail;
            this.materialName = materialName;
            this.currentStock = 0;
            this.todaySold = 0;
        }

        public String getStockDisplay() {
            if (retail) {
                return String.format("%d/%d 个", currentStock, maxStock);
            }
            return String.format("%d/%d 组", currentStock / 64, maxStock / 64);
        }

        public String getUnit() {
            return retail ? "个" : "组";
        }
    }
}
