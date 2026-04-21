package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.integration.jei.JEIIntegrationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 可配置列表界面 - LDLib版本
 * 支持搜索、添加、删除
 * 使用固定3x缩放渲染
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public abstract class ConfigurableListScreenLDLib extends ModularUIGuiContainer {

    // 颜色定义 - 与ServerConfigScreen保持一致
    private static final int COLOR_WINDOW_BG = 0xE6202020;
    private static final int COLOR_WINDOW_BORDER = 0xFF4A90A4;
    private static final int COLOR_HEADER_BG = 0xFF2D5A6B;
    private static final int COLOR_PANEL_BG = 0xCC2A2A2A;
    private static final int COLOR_ITEM_BG = 0xFF353535;
    private static final int COLOR_BUTTON_BG = 0xFF3A5A6A;
    private static final int COLOR_BUTTON_HOVER = 0xFF4A7A8A;
    private static final int COLOR_DELETE_BUTTON = 0xFFFF5555;
    private static final int COLOR_DELETE_HOVER = 0xFFFF7777;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_NORMAL = 0xFFE0E0E0;
    private static final int COLOR_TEXT_GRAY = 0xFFAAAAAA;
    private static final int COLOR_INPUT_BORDER = 0xFF5A5A5A;

    // 窗口尺寸
    private static final int WINDOW_WIDTH = 400;
    private static final int WINDOW_HEIGHT = 340;
    private static final int HEADER_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 40;
    private static final int ITEM_HEIGHT = 28;

    // 数据
    protected final Screen parent;
    protected final String title;
    protected final Consumer<List<String>> onSave;
    protected List<String> items;
    protected List<String> filteredItems;
    protected List<String> availableItems;

    // UI引用
    private static ConfigurableListScreenLDLib currentInstance;
    private DraggableScrollableWidgetGroup listGroup;
    private TextFieldWidget searchField;
    private TextFieldWidget addField;

    // 状态
    private String searchText = "";

    public ConfigurableListScreenLDLib(Screen parent, String title,
                                       List<String> initialItems,
                                       List<String> availableItems,
                                       Consumer<List<String>> onSave) {
        super(createHolderAndUI(title), 0);
        this.parent = parent;
        this.title = title;
        this.items = new ArrayList<>(initialItems);
        this.filteredItems = new ArrayList<>(items);
        this.availableItems = new ArrayList<>(availableItems);
        this.onSave = onSave;
        currentInstance = this;

        // simukraft: 根据窗口尺寸选择能完整显示的最大缩放
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
    }

    private static ModularUI createHolderAndUI(String title) {
        return new ListUIHolder(title).createModularUI();
    }

    @Override
    public void init() {
        super.init();
        // simukraft: 重新查找UI引用（因为UI是在super中创建的，当时currentInstance还未设置）
        findUIReferences();
        // simukraft: 初始化完成后刷新列表
        if (listGroup != null) {
            refreshList();
        }
    }

    /**
     * 从modularUI中查找并赋值UI引用
     */
    private void findUIReferences() {
        if (modularUI == null || modularUI.mainGroup == null) return;

        // simukraft: 递归遍历所有widget查找列表组和输入框
        findWidgetsInGroup(modularUI.mainGroup);
    }

    /**
     * 递归查找widget
     */
    private void findWidgetsInGroup(WidgetGroup group) {
        findWidgetsInGroupRecursive(group, 0);
    }

    /**
     * 递归查找widget（带层级）
     * @param depth 当前层级，用于判断输入框类型
     */
    private void findWidgetsInGroupRecursive(WidgetGroup group, int depth) {
        for (Widget widget : group.widgets) {
            // 检查当前widget
            if (widget instanceof DraggableScrollableWidgetGroup list) {
                // 列表组 - 根据位置判断
                int x = list.getSelfPosition().x;
                int y = list.getSelfPosition().y;
                if (x == 5 && y == 5) {
                    if (listGroup == null) {
                        listGroup = list;
                    }
                }
            } else if (widget instanceof TextFieldWidget textField) {
                // 输入框 - 根据层级判断
                // 搜索输入框容器在 createInputArea (depth=2)，添加输入框容器也在 createInputArea
                // 通过创建顺序判断：先搜索后添加
                int fieldY = textField.getSelfPosition().y;
                if (fieldY == 0) {
                    if (searchField == null) {
                        searchField = textField;
                    } else if (addField == null) {
                        addField = textField;
                    }
                }
            }

            // 递归遍历子group
            if (widget instanceof WidgetGroup childGroup) {
                findWidgetsInGroupRecursive(childGroup, depth + 1);
            }
        }
    }

    @Override
    public void onClose() {
        // simukraft: 子界面不恢复缩放，由父界面自己管理
        // GuiScaleManager.restore();
        currentInstance = null;
        // simukraft: 清除 JEI 拖拽目标
        JEIIntegrationManager.getInstance().clearDropTargets();
        Minecraft.getInstance().setScreen(parent);
        // simukraft: 父界面会在init/render中重新应用3x
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // simukraft: 使用GuiScaleManager统一处理ESC键
        if (GuiScaleManager.handleEscKey(keyCode, this::onClose)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // simukraft: 保持可完整显示的最佳缩放
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 获取所有可用物品列表 - 子类实现
     */
    protected abstract List<String> getAllAvailableItems();

    /**
     * 更新过滤后的列表
     */
    protected void updateFilteredItems() {
        if (searchText.isEmpty()) {
            filteredItems = new ArrayList<>(items);
        } else {
            String lowerSearch = searchText.toLowerCase();
            filteredItems = items.stream()
                    .filter(item -> item.toLowerCase().contains(lowerSearch))
                    .collect(Collectors.toList());
        }
        refreshList();
    }

    /**
     * 刷新列表显示
     */
    protected void refreshList() {
        if (listGroup == null) return;

        listGroup.clearAllWidgets();

        int y = 5;
        for (String item : filteredItems) {
            WidgetGroup itemRow = createItemRow(item, y);
            listGroup.addWidget(itemRow);
            y += ITEM_HEIGHT;
        }
    }

    /**
     * 创建列表项行
     */
    private WidgetGroup createItemRow(String item, int y) {
        WidgetGroup row = new WidgetGroup();
        row.setSelfPosition(5, y);
        row.setSize(WINDOW_WIDTH - 50, ITEM_HEIGHT - 2);

        // 背景
        row.setBackground(new ColorRectTexture(COLOR_ITEM_BG).setRadius(3));

        // simukraft: 添加物品图标
        ItemStack itemStack = getItemStack(item);
        if (!itemStack.isEmpty()) {
            ItemStackTexture itemTexture = new ItemStackTexture(itemStack);
            row.addWidget(new ImageWidget(8, 2, 16, 16, itemTexture));
        }

        // 图标和名称
        String displayName = getItemDisplayName(item);
        TextTexture nameTexture = new TextTexture(displayName, COLOR_TEXT_NORMAL);
        nameTexture.setType(TextTexture.TextType.NORMAL);
        nameTexture.setWidth(WINDOW_WIDTH - 120);
        row.addWidget(new ImageWidget(28, 5, WINDOW_WIDTH - 120, 10, nameTexture));

        // ID
        TextTexture idTexture = new TextTexture(item, COLOR_TEXT_GRAY);
        idTexture.setType(TextTexture.TextType.NORMAL);
        idTexture.setWidth(WINDOW_WIDTH - 120);
        row.addWidget(new ImageWidget(28, 16, WINDOW_WIDTH - 120, 8, idTexture));

        // simukraft: 删除按钮（带叉符号）
        ButtonWidget deleteBtn = new ButtonWidget();
        deleteBtn.setSelfPosition(WINDOW_WIDTH - 75, 4);
        deleteBtn.setSize(20, 20);

        // 叉符号纹理
        TextTexture deleteText = new TextTexture("✕", COLOR_TEXT_TITLE);
        deleteText.setType(TextTexture.TextType.NORMAL);

        // 组合背景色和叉符号
        deleteBtn.setBackground(new GuiTextureGroup(
                new ColorRectTexture(COLOR_DELETE_BUTTON).setRadius(3),
                deleteText
        ));

        TextTexture deleteHoverText = new TextTexture("✕", COLOR_TEXT_TITLE);
        deleteHoverText.setType(TextTexture.TextType.NORMAL);
        deleteBtn.setHoverTexture(new GuiTextureGroup(
                new ColorRectTexture(COLOR_DELETE_HOVER).setRadius(3),
                deleteHoverText
        ));

        deleteBtn.setOnPressCallback(click -> {
            items.remove(item);
            updateFilteredItems();
        });
        row.addWidget(deleteBtn);

        return row;
    }

    /**
     * 获取物品堆栈
     */
    private ItemStack getItemStack(String itemId) {
        try {
            ResourceLocation location = ResourceLocation.tryParse(itemId);
            if (location == null) return ItemStack.EMPTY;

            Item item = ForgeRegistries.ITEMS.getValue(location);
            if (item != null && item != Items.AIR) {
                return new ItemStack(item);
            }

            Block block = ForgeRegistries.BLOCKS.getValue(location);
            if (block != null && block != Blocks.AIR) {
                return new ItemStack(block);
            }
        } catch (Exception e) {
            // 忽略错误
        }
        return ItemStack.EMPTY;
    }

    /**
     * 获取物品显示名称
     */
    protected String getItemDisplayName(String itemId) {
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        if (location == null) return itemId;

        Block block = ForgeRegistries.BLOCKS.getValue(location);
        if (block != null && block != Blocks.AIR) {
            return block.getName().getString();
        }

        Item item = ForgeRegistries.ITEMS.getValue(location);
        if (item != null && item != Items.AIR) {
            return item.getDescription().getString();
        }

        return itemId;
    }

    /**
     * 验证物品是否有效
     */
    protected boolean isValidItem(String itemId) {
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        if (location == null) return false;

        Block block = ForgeRegistries.BLOCKS.getValue(location);
        if (block != null && block != Blocks.AIR) return true;

        Item item = ForgeRegistries.ITEMS.getValue(location);
        return item != null && item != Items.AIR;
    }

    /**
     * 添加当前物品
     */
    protected void addCurrentItem() {
        if (addField == null) return;
        String itemId = addField.getCurrentString().trim();
        addItemById(itemId);
    }

    /**
     * 通过物品ID添加物品（支持 JEI 拖拽）
     */
    public void addItemById(String itemId) {
        if (itemId == null || itemId.isEmpty()) return;

        if (!isValidItem(itemId)) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("message.simukraft.config.invalid_item_id", itemId), false);
            }
            return;
        }

        if (!items.contains(itemId)) {
            items.add(itemId);
            if (addField != null) {
                addField.setCurrentString("");
            }
            updateFilteredItems();
            // simukraft: 添加后自动滚动到新物品
            scrollToItem(itemId);
        }
    }

    /**
     * 滚动到指定物品位置
     */
    private void scrollToItem(String itemId) {
        if (listGroup == null) return;

        // simukraft: 找到物品在过滤后列表中的索引
        int index = filteredItems.indexOf(itemId);
        if (index < 0) return;

        // simukraft: 计算目标滚动位置（每个项目高度 24）
        int targetY = index * ITEM_HEIGHT;

        // simukraft: 获取列表可视区域高度
        int visibleHeight = listGroup.getSize().height;

        // simukraft: 计算需要滚动的偏移量，使目标物品显示在列表中间
        int scrollOffset = targetY - visibleHeight / 2 + ITEM_HEIGHT / 2;

        // simukraft: 确保滚动位置在有效范围内
        int maxScroll = Math.max(0, filteredItems.size() * ITEM_HEIGHT - visibleHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // simukraft: 设置滚动位置
        listGroup.setScrollYOffset(scrollOffset);
    }

    /**
     * 搜索物品
     */
    protected void doSearch() {
        if (searchField == null) return;
        searchText = searchField.getCurrentString().trim();
        updateFilteredItems();
    }

    /**
     * 保存并关闭
     */
    protected void saveAndClose() {
        if (onSave != null) {
            onSave.accept(new ArrayList<>(items));
        }
        onClose();
    }

    // ==================== UI Holder ====================

    private static class ListUIHolder implements IUIHolder {
        private final String title;

        public ListUIHolder(String title) {
            this.title = title;
        }

        @Override
        public ModularUI createUI(Player entityPlayer) {
            return createModularUI();
        }

        public ModularUI createModularUI() {
            ModularUI modularUI = new ModularUI(new Size(WINDOW_WIDTH, WINDOW_HEIGHT), this, null);
            WidgetGroup rootGroup = new WidgetGroup();
            rootGroup.setSelfPosition(0, 0);
            rootGroup.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);

            // 主窗口背景
            WidgetGroup windowGroup = new WidgetGroup();
            windowGroup.setSelfPosition(0, 0);
            windowGroup.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
            windowGroup.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_WINDOW_BG).setRadius(10),
                    new ColorBorderTexture(2, COLOR_WINDOW_BORDER).setRadius(10)
            ));
            rootGroup.addWidget(windowGroup);

            createHeader(windowGroup);
            createSearchArea(windowGroup);
            createListArea(windowGroup);
            createFooter(windowGroup);

            modularUI.widget(rootGroup);
            modularUI.initWidgets();
            return modularUI;
        }

        private void createHeader(WidgetGroup parent) {
            WidgetGroup headerGroup = new WidgetGroup();
            headerGroup.setSelfPosition(2, 2);
            headerGroup.setSize(WINDOW_WIDTH - 4, HEADER_HEIGHT - 4);
            headerGroup.setBackground(new ColorRectTexture(COLOR_HEADER_BG).setRadius(8));

            TextTexture titleTexture = new TextTexture(title, COLOR_TEXT_TITLE);
            titleTexture.setType(TextTexture.TextType.NORMAL);
            headerGroup.addWidget(new ImageWidget(0, 10, WINDOW_WIDTH - 4, 16, titleTexture));

            parent.addWidget(headerGroup);
        }

        private void createSearchArea(WidgetGroup parent) {
            int startY = HEADER_HEIGHT + 10;

            // 搜索框标签
            TextTexture searchLabel = new TextTexture("搜索:", COLOR_TEXT_GRAY);
            searchLabel.setType(TextTexture.TextType.NORMAL);
            parent.addWidget(new ImageWidget(10, startY, 40, 12, searchLabel));

            // simukraft: 搜索输入框容器（带下划线样式）
            WidgetGroup searchInputContainer = new WidgetGroup();
            searchInputContainer.setSelfPosition(50, startY);
            searchInputContainer.setSize(120, 18);
            parent.addWidget(searchInputContainer);

            // 搜索输入框 - 透明背景无边框
            TextFieldWidget searchField = new TextFieldWidget();
            searchField.setSelfPosition(0, 0);
            searchField.setSize(120, 16);
            searchField.setTextColor(COLOR_TEXT_NORMAL);
            searchField.setCurrentString("");
            searchField.setBordered(false);
            searchInputContainer.addWidget(searchField);
            if (currentInstance != null) {
                currentInstance.searchField = searchField;
            }

            // 底部下划线
            WidgetGroup searchUnderline = new WidgetGroup();
            searchUnderline.setSelfPosition(0, 16);
            searchUnderline.setSize(120, 2);
            searchUnderline.setBackground(new ColorRectTexture(0xFF808080).setRadius(1));
            searchInputContainer.addWidget(searchUnderline);

            // 搜索按钮 - 使用GuiTextureGroup包含背景和文字
            ButtonWidget searchBtn = new ButtonWidget();
            searchBtn.setSelfPosition(175, startY);
            searchBtn.setSize(35, 16);
            TextTexture searchText = new TextTexture("搜索", COLOR_TEXT_NORMAL);
            searchText.setType(TextTexture.TextType.NORMAL);
            searchText.setWidth(35);
            searchBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(3),
                    searchText
            ));
            TextTexture searchHoverText = new TextTexture("搜索", COLOR_TEXT_NORMAL);
            searchHoverText.setType(TextTexture.TextType.NORMAL);
            searchHoverText.setWidth(35);
            searchBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(3),
                    searchHoverText
            ));
            searchBtn.setOnPressCallback(click -> {
                if (currentInstance != null) currentInstance.doSearch();
            });
            parent.addWidget(searchBtn);

            // 清除搜索按钮
            ButtonWidget clearBtn = new ButtonWidget();
            clearBtn.setSelfPosition(215, startY);
            clearBtn.setSize(20, 16);
            TextTexture clearText = new TextTexture("✕", COLOR_TEXT_TITLE);
            clearText.setType(TextTexture.TextType.NORMAL);
            clearText.setWidth(20);
            clearBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_DELETE_BUTTON).setRadius(3),
                    clearText
            ));
            TextTexture clearHoverText = new TextTexture("✕", COLOR_TEXT_TITLE);
            clearHoverText.setType(TextTexture.TextType.NORMAL);
            clearHoverText.setWidth(20);
            clearBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_DELETE_HOVER).setRadius(3),
                    clearHoverText
            ));
            clearBtn.setOnPressCallback(click -> {
                searchField.setCurrentString("");
                if (currentInstance != null) {
                    currentInstance.searchText = "";
                    currentInstance.updateFilteredItems();
                }
            });
            parent.addWidget(clearBtn);

            // 添加框标签
            TextTexture addLabel = new TextTexture("添加:", COLOR_TEXT_GRAY);
            addLabel.setType(TextTexture.TextType.NORMAL);
            parent.addWidget(new ImageWidget(10, startY + 22, 40, 12, addLabel));

            // simukraft: 添加输入框容器（带下划线样式）
            WidgetGroup addInputContainer = new WidgetGroup();
            addInputContainer.setSelfPosition(50, startY + 22);
            addInputContainer.setSize(120, 18);
            parent.addWidget(addInputContainer);

            // 添加输入框 - 透明背景无边框
            TextFieldWidget addField = new TextFieldWidget();
            addField.setSelfPosition(0, 0);
            addField.setSize(120, 16);
            addField.setTextColor(COLOR_TEXT_NORMAL);
            addField.setCurrentString("");
            addField.setBordered(false);
            addInputContainer.addWidget(addField);
            if (currentInstance != null) {
                currentInstance.addField = addField;
            }

            // 底部下划线
            WidgetGroup addUnderline = new WidgetGroup();
            addUnderline.setSelfPosition(0, 16);
            addUnderline.setSize(120, 2);
            addUnderline.setBackground(new ColorRectTexture(0xFF808080).setRadius(1));
            addInputContainer.addWidget(addUnderline);

            // 添加按钮 - 使用GuiTextureGroup包含背景和文字
            ButtonWidget addBtn = new ButtonWidget();
            addBtn.setSelfPosition(175, startY + 22);
            addBtn.setSize(60, 16);
            TextTexture addText = new TextTexture("添加", COLOR_TEXT_NORMAL);
            addText.setType(TextTexture.TextType.NORMAL);
            addText.setWidth(60);
            addBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(3),
                    addText
            ));
            TextTexture addHoverText = new TextTexture("添加", COLOR_TEXT_NORMAL);
            addHoverText.setType(TextTexture.TextType.NORMAL);
            addHoverText.setWidth(60);
            addBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(3),
                    addHoverText
            ));
            addBtn.setOnPressCallback(click -> {
                if (currentInstance != null) currentInstance.addCurrentItem();
            });
            parent.addWidget(addBtn);
        }

        private void createListArea(WidgetGroup parent) {
            int listY = HEADER_HEIGHT + 65;
            int listHeight = WINDOW_HEIGHT - listY - FOOTER_HEIGHT - 10;

            // 列表背景
            WidgetGroup listBg = new WidgetGroup();
            listBg.setSelfPosition(10, listY);
            listBg.setSize(WINDOW_WIDTH - 20, listHeight);
            listBg.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_PANEL_BG).setRadius(5),
                    new ColorBorderTexture(1, COLOR_INPUT_BORDER).setRadius(5)
            ));
            parent.addWidget(listBg);

            // 可滚动列表
            DraggableScrollableWidgetGroup listGroup = new DraggableScrollableWidgetGroup();
            listGroup.setSelfPosition(5, 5);
            listGroup.setSize(WINDOW_WIDTH - 30, listHeight - 10);
            listBg.addWidget(listGroup);

            if (currentInstance != null) {
                currentInstance.listGroup = listGroup;
                currentInstance.refreshList();
            }
        }

        private void createFooter(WidgetGroup parent) {
            int footerY = WINDOW_HEIGHT - FOOTER_HEIGHT + 5;
            int centerX = WINDOW_WIDTH / 2;

            // 保存按钮 - 使用GuiTextureGroup包含背景和文字
            ButtonWidget saveBtn = new ButtonWidget();
            saveBtn.setSelfPosition(centerX - 80, footerY);
            saveBtn.setSize(70, 24);
            TextTexture saveText = new TextTexture("保存", COLOR_TEXT_NORMAL);
            saveText.setType(TextTexture.TextType.NORMAL);
            saveText.setWidth(70);
            saveBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(5),
                    saveText
            ));
            TextTexture saveHoverText = new TextTexture("保存", COLOR_TEXT_NORMAL);
            saveHoverText.setType(TextTexture.TextType.NORMAL);
            saveHoverText.setWidth(70);
            saveBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(5),
                    saveHoverText
            ));
            saveBtn.setOnPressCallback(click -> {
                if (currentInstance != null) currentInstance.saveAndClose();
            });
            parent.addWidget(saveBtn);

            // 取消按钮 - 使用GuiTextureGroup包含背景和文字
            ButtonWidget cancelBtn = new ButtonWidget();
            cancelBtn.setSelfPosition(centerX + 10, footerY);
            cancelBtn.setSize(70, 24);
            TextTexture cancelText = new TextTexture("取消", COLOR_TEXT_NORMAL);
            cancelText.setType(TextTexture.TextType.NORMAL);
            cancelText.setWidth(70);
            cancelBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(5),
                    cancelText
            ));
            TextTexture cancelHoverText = new TextTexture("取消", COLOR_TEXT_NORMAL);
            cancelHoverText.setType(TextTexture.TextType.NORMAL);
            cancelHoverText.setWidth(70);
            cancelBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(5),
                    cancelHoverText
            ));
            cancelBtn.setOnPressCallback(click -> {
                if (currentInstance != null) currentInstance.onClose();
            });
            parent.addWidget(cancelBtn);
        }

        @Override public boolean isInvalid() { return false; }
        @Override public boolean isRemote() { return true; }
        @Override public void markAsDirty() {}
    }
}
