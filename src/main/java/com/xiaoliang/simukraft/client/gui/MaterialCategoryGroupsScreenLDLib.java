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
import com.xiaoliang.simukraft.config.ServerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 通类匹配组配置界面 - LDLib版本
 * 用于配置普通模式下的材料通类匹配组
 * 格式: 组名|组头1,组头2|组员1,组员2,组员3
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class MaterialCategoryGroupsScreenLDLib extends ModularUIGuiContainer {

    // 颜色定义
    private static final int COLOR_WINDOW_BG = 0xE6202020;
    private static final int COLOR_WINDOW_BORDER = 0xFF4A90A4;
    private static final int COLOR_HEADER_BG = 0xFF2D5A6B;
    private static final int COLOR_PANEL_BG = 0xCC2A2A2A;
    private static final int COLOR_ITEM_BG = 0xFF353535;
    private static final int COLOR_ITEM_BG_HOVER = 0xFF454545;
    private static final int COLOR_ITEM_SELECTED = 0xFF5555AA;
    private static final int COLOR_HEADER_ITEM_BG = 0xFF664400;
    private static final int COLOR_MEMBER_ITEM_BG = 0xFF334466;
    private static final int COLOR_BUTTON_BG = 0xFF3A5A6A;
    private static final int COLOR_BUTTON_HOVER = 0xFF4A7A8A;
    private static final int COLOR_DELETE_BUTTON = 0xFFFF5555;
    private static final int COLOR_DELETE_HOVER = 0xFFFF7777;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_NORMAL = 0xFFE0E0E0;
    private static final int COLOR_TEXT_GRAY = 0xFFAAAAAA;
    private static final int COLOR_TEXT_HEADER = 0xFFFFAA00;
    private static final int COLOR_TEXT_MEMBER = 0xFF88AAFF;
    private static final int COLOR_INPUT_BORDER = 0xFF5A5A5A;

    // 窗口尺寸
    private static final int WINDOW_WIDTH = 600;
    private static final int WINDOW_HEIGHT = 400;
    private static final int HEADER_HEIGHT = 50;
    private static final int FOOTER_HEIGHT = 45;
    private static final int PANEL_WIDTH = 180;
    private static final int ITEM_HEIGHT = 24;

    // 数据
    private final Screen parent;
    private final Consumer<List<String>> onSave;
    private List<String> groupNames;
    private String selectedGroup = null;
    private Map<String, List<String>> groupHeaders = new HashMap<>();
    private Map<String, List<String>> groupMembers = new HashMap<>();

    // UI引用
    private static MaterialCategoryGroupsScreenLDLib currentInstance;
    private DraggableScrollableWidgetGroup groupListGroup;
    private DraggableScrollableWidgetGroup headerListGroup;
    private DraggableScrollableWidgetGroup memberListGroup;
    private TextFieldWidget newGroupField;
    private TextFieldWidget headerField;
    private TextFieldWidget memberField;
    private ButtonWidget deleteGroupBtn;
    private ButtonWidget addHeaderBtn;
    private ButtonWidget addMemberBtn;
    private ButtonWidget addGroupBtn;

    public MaterialCategoryGroupsScreenLDLib(Screen parent, Map<String, ServerConfig.MaterialGroupInfo> initialGroups,
                                              Consumer<List<String>> onSave) {
        // simukraft: 先初始化数据，再设置currentInstance，最后创建UI
        super(createHolderAndUI(), 0);
        this.parent = parent;
        this.onSave = onSave;

        // 从MaterialGroupInfo中提取数据
        this.groupNames = new ArrayList<>(initialGroups.keySet());
        this.groupNames.sort(String::compareTo);

        for (Map.Entry<String, ServerConfig.MaterialGroupInfo> entry : initialGroups.entrySet()) {
            String groupName = entry.getKey();
            ServerConfig.MaterialGroupInfo group = entry.getValue();
            this.groupHeaders.put(groupName, new ArrayList<>(group.getHeaders()));
            this.groupMembers.put(groupName, new ArrayList<>(group.getMembers()));
        }

        // simukraft: 在super调用后设置currentInstance，但UI创建时还不能访问它
        // 需要在init()中重新赋值UI引用
        currentInstance = this;
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
    }

    private static ModularUI createHolderAndUI() {
        return new GroupsUIHolder().createModularUI();
    }

    @Override
    public void init() {
        super.init();
        // simukraft: 重新查找UI引用（因为UI是在super中创建的，当时currentInstance还未设置）
        findUIReferences();
        // simukraft: 初始化完成后刷新所有列表
        if (groupListGroup != null && headerListGroup != null && memberListGroup != null) {
            refreshAllLists();
        }
    }

    /**
     * 从modularUI中查找并赋值UI引用
     */
    private void findUIReferences() {
        if (modularUI == null || modularUI.mainGroup == null) return;

        // simukraft: 递归遍历所有widget查找列表组、输入框和按钮
        findWidgetsInGroup(modularUI.mainGroup, null);
    }

    /**
     * 递归查找widget
     * @param group 当前遍历的组
     * @param panelX 父面板X坐标（用于判断属于哪个面板）
     */
    private void findWidgetsInGroup(WidgetGroup group, Integer panelX) {
        // 检查当前group是否是一个面板（根据位置判断）
        int currentPanelX = group.getSelfPosition().x;
        Integer effectivePanelX = panelX;

        // 如果当前group是三个面板之一，更新panelX
        if (currentPanelX == 15 || currentPanelX == 15 + PANEL_WIDTH + 15 || currentPanelX == 15 + PANEL_WIDTH * 2 + 30) {
            effectivePanelX = currentPanelX;
        }

        for (Widget widget : group.widgets) {
            // 检查当前widget
            if (widget instanceof DraggableScrollableWidgetGroup listGroup) {
                // 根据父容器位置判断是哪个列表
                if (effectivePanelX != null) {
                    if (effectivePanelX == 15) {
                        // 左侧面板
                        if (groupListGroup == null) groupListGroup = listGroup;
                    } else if (effectivePanelX == 15 + PANEL_WIDTH + 15) {
                        // 中间面板
                        if (headerListGroup == null) headerListGroup = listGroup;
                    } else if (effectivePanelX == 15 + PANEL_WIDTH * 2 + 30) {
                        // 右侧面板
                        if (memberListGroup == null) memberListGroup = listGroup;
                    }
                }
            } else if (widget instanceof TextFieldWidget textField) {
                if (effectivePanelX != null) {
                    int fieldY = textField.getSelfPosition().y;
                    // 注意：输入框现在在容器中，y坐标是相对于容器的
                    if (effectivePanelX == 15 && fieldY == 0) {
                        if (newGroupField == null) newGroupField = textField;
                    } else if (effectivePanelX == 15 + PANEL_WIDTH + 15 && fieldY == 0) {
                        if (headerField == null) headerField = textField;
                    } else if (effectivePanelX == 15 + PANEL_WIDTH * 2 + 30 && fieldY == 0) {
                        if (memberField == null) memberField = textField;
                    }
                }
            } else if (widget instanceof ButtonWidget button) {
                if (effectivePanelX != null) {
                    int btnX = button.getSelfPosition().x;
                    int btnY = button.getSelfPosition().y;
                    if (effectivePanelX == 15 && btnY == 28 && btnX == PANEL_WIDTH - 45) {
                        if (addGroupBtn == null) addGroupBtn = button;
                    } else if (effectivePanelX == 15 + PANEL_WIDTH + 15 && btnY == 28 && btnX == PANEL_WIDTH - 45) {
                        if (addHeaderBtn == null) addHeaderBtn = button;
                    } else if (effectivePanelX == 15 + PANEL_WIDTH * 2 + 30 && btnY == 28 && btnX == PANEL_WIDTH - 45) {
                        if (addMemberBtn == null) addMemberBtn = button;
                    } else if (effectivePanelX == 15 && btnY >= 100 && btnX == 10) {
                        if (deleteGroupBtn == null) deleteGroupBtn = button;
                    }
                }
            }

            // 递归遍历子group
            if (widget instanceof WidgetGroup childGroup) {
                findWidgetsInGroup(childGroup, effectivePanelX);
            }
        }
    }

    @Override
    public void onClose() {
        // simukraft: 子界面不恢复缩放，由父界面自己管理
        // GuiScaleManager.restore();
        currentInstance = null;
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

    private boolean needsRefresh = true;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
        // simukraft: 首次渲染时刷新列表
        if (needsRefresh && groupListGroup != null) {
            refreshAllLists();
            needsRefresh = false;
        }
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 刷新所有列表
     */
    private void refreshAllLists() {
        refreshGroupList();
        refreshHeaderList();
        refreshMemberList();
        updateUIState();
    }

    /**
     * 刷新组列表
     */
    private void refreshGroupList() {
        if (groupListGroup == null) return;
        groupListGroup.clearAllWidgets();

        int y = 5;
        for (String groupName : groupNames) {
            WidgetGroup row = createGroupRow(groupName, y);
            groupListGroup.addWidget(row);
            y += ITEM_HEIGHT;
        }
    }

    private WidgetGroup createGroupRow(String groupName, int y) {
        WidgetGroup row = new WidgetGroup();
        row.setSelfPosition(5, y);
        row.setSize(PANEL_WIDTH - 20, ITEM_HEIGHT - 2);

        boolean isSelected = groupName.equals(selectedGroup);
        int bgColor = isSelected ? COLOR_ITEM_SELECTED : COLOR_ITEM_BG;
        row.setBackground(new ColorRectTexture(bgColor).setRadius(3));

        // simukraft: 先添加按钮（底层），再添加文字（上层），避免文字被覆盖
        // 选择按钮
        ButtonWidget selectBtn = new ButtonWidget();
        selectBtn.setSelfPosition(0, 0);
        selectBtn.setSize(PANEL_WIDTH - 35, ITEM_HEIGHT - 2);
        selectBtn.setBackground(new ColorRectTexture(0x00000000).setRadius(3));
        selectBtn.setHoverTexture(new ColorRectTexture(COLOR_ITEM_BG_HOVER).setRadius(3));
        selectBtn.setOnPressCallback(click -> {
            selectedGroup = groupName;
            refreshGroupList();
            refreshHeaderList();
            refreshMemberList();
            updateUIState();
        });
        row.addWidget(selectBtn);

        // 文字显示（后添加，显示在按钮之上）
        int textColor = isSelected ? COLOR_TEXT_TITLE : COLOR_TEXT_NORMAL;
        TextTexture nameTexture = new TextTexture(groupName, textColor);
        nameTexture.setType(TextTexture.TextType.NORMAL);
        nameTexture.setWidth(PANEL_WIDTH - 50);
        row.addWidget(new ImageWidget(8, 6, PANEL_WIDTH - 50, 12, nameTexture));

        return row;
    }

    /**
     * 刷新组头列表
     */
    private void refreshHeaderList() {
        if (headerListGroup == null) return;
        headerListGroup.clearAllWidgets();

        List<String> headers = selectedGroup != null ?
                groupHeaders.getOrDefault(selectedGroup, new ArrayList<>()) : new ArrayList<>();

        int y = 5;
        for (String headerId : headers) {
            WidgetGroup row = createItemRow(headerId, y, true);
            headerListGroup.addWidget(row);
            y += ITEM_HEIGHT;
        }
    }

    /**
     * 刷新组员列表
     */
    private void refreshMemberList() {
        if (memberListGroup == null) return;
        memberListGroup.clearAllWidgets();

        List<String> members = selectedGroup != null ?
                groupMembers.getOrDefault(selectedGroup, new ArrayList<>()) : new ArrayList<>();

        int y = 5;
        for (String memberId : members) {
            WidgetGroup row = createItemRow(memberId, y, false);
            memberListGroup.addWidget(row);
            y += ITEM_HEIGHT;
        }
    }

    private WidgetGroup createItemRow(String itemId, int y, boolean isHeader) {
        WidgetGroup row = new WidgetGroup();
        row.setSelfPosition(5, y);
        row.setSize(PANEL_WIDTH - 20, ITEM_HEIGHT - 2);

        int bgColor = isHeader ? COLOR_HEADER_ITEM_BG : COLOR_MEMBER_ITEM_BG;
        row.setBackground(new ColorRectTexture(bgColor).setRadius(3));

        // simukraft: 添加物品图标
        ItemStack itemStack = getItemStack(itemId);
        if (!itemStack.isEmpty()) {
            ItemStackTexture itemTexture = new ItemStackTexture(itemStack);
            row.addWidget(new ImageWidget(8, 2, 16, 16, itemTexture));
        }

        // 文字显示
        String displayName = getDisplayName(itemId);
        int textColor = isHeader ? COLOR_TEXT_HEADER : COLOR_TEXT_MEMBER;
        TextTexture nameTexture = new TextTexture(displayName, textColor);
        nameTexture.setType(TextTexture.TextType.NORMAL);
        nameTexture.setWidth(PANEL_WIDTH - 70);
        row.addWidget(new ImageWidget(28, 6, PANEL_WIDTH - 70, 12, nameTexture));

        // simukraft: 删除按钮（带叉符号）
        ButtonWidget deleteBtn = new ButtonWidget();
        deleteBtn.setSelfPosition(PANEL_WIDTH - 35, 3);
        deleteBtn.setSize(18, 18);

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
            if (isHeader) {
                removeHeader(itemId);
            } else {
                removeMember(itemId);
            }
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
     * 更新UI状态
     */
    private void updateUIState() {
        boolean hasSelection = selectedGroup != null && groupNames.contains(selectedGroup);
        if (deleteGroupBtn != null) deleteGroupBtn.setVisible(hasSelection);
        if (headerField != null) headerField.setVisible(hasSelection);
        if (addHeaderBtn != null) addHeaderBtn.setVisible(hasSelection);
        if (memberField != null) memberField.setVisible(hasSelection);
        if (addMemberBtn != null) addMemberBtn.setVisible(hasSelection);
    }

    /**
     * 添加新组
     */
    private void addNewGroup() {
        if (newGroupField == null) return;
        String groupName = newGroupField.getCurrentString().trim();
        if (groupName.isEmpty()) return;
        if (groupNames.contains(groupName)) return;

        groupNames.add(groupName);
        groupNames.sort(String::compareTo);
        groupHeaders.put(groupName, new ArrayList<>());
        groupMembers.put(groupName, new ArrayList<>());
        selectedGroup = groupName;
        newGroupField.setCurrentString("");
        refreshAllLists();
    }

    /**
     * 删除选中组
     */
    private void deleteSelectedGroup() {
        if (selectedGroup == null || !groupNames.contains(selectedGroup)) return;

        groupNames.remove(selectedGroup);
        groupHeaders.remove(selectedGroup);
        groupMembers.remove(selectedGroup);
        selectedGroup = null;
        refreshAllLists();
    }

    /**
     * 添加组头
     */
    private void addHeader() {
        if (selectedGroup == null) return;
        if (headerField == null) return;

        String headerId = headerField.getCurrentString().trim();
        if (headerId.isEmpty()) return;
        if (!headerId.contains(":")) {
            headerId = "minecraft:" + headerId;
        }

        List<String> headers = groupHeaders.get(selectedGroup);
        if (headers == null) return;
        if (headers.contains(headerId)) {
            headerField.setCurrentString("");
            return;
        }

        headers.add(headerId);
        headerField.setCurrentString("");
        refreshHeaderList();
    }

    /**
     * 添加组员
     */
    private void addMember() {
        if (selectedGroup == null) return;
        if (memberField == null) return;

        String memberId = memberField.getCurrentString().trim();
        if (memberId.isEmpty()) return;
        if (!memberId.contains(":")) {
            memberId = "minecraft:" + memberId;
        }

        List<String> members = groupMembers.get(selectedGroup);
        if (members == null) return;
        if (members.contains(memberId)) {
            memberField.setCurrentString("");
            return;
        }

        members.add(memberId);
        memberField.setCurrentString("");
        refreshMemberList();
    }

    /**
     * 移除组头
     */
    private void removeHeader(String headerId) {
        if (selectedGroup == null) return;
        List<String> headers = groupHeaders.get(selectedGroup);
        if (headers != null) {
            headers.remove(headerId);
            refreshHeaderList();
        }
    }

    /**
     * 移除组员
     */
    private void removeMember(String memberId) {
        if (selectedGroup == null) return;
        List<String> members = groupMembers.get(selectedGroup);
        if (members != null) {
            members.remove(memberId);
            refreshMemberList();
        }
    }

    /**
     * 获取物品显示名称
     */
    private String getDisplayName(String itemId) {
        try {
            ResourceLocation location = ResourceLocation.tryParse(itemId);
            if (location == null) return itemId;

            Item item = ForgeRegistries.ITEMS.getValue(location);
            if (item != null && item != Items.AIR) {
                return item.getDescription().getString();
            }

            Block block = ForgeRegistries.BLOCKS.getValue(location);
            if (block != null && block != Blocks.AIR) {
                return block.getName().getString();
            }
        } catch (Exception e) {
            // 忽略错误
        }
        return itemId;
    }

    /**
     * 保存并关闭
     */
    private void saveAndClose() {
        List<String> configList = new ArrayList<>();
        for (String groupName : groupNames) {
            StringBuilder sb = new StringBuilder();
            sb.append(groupName).append("|");

            List<String> headers = groupHeaders.getOrDefault(groupName, new ArrayList<>());
            if (!headers.isEmpty()) {
                sb.append(String.join(",", headers));
            }
            sb.append("|");

            List<String> members = groupMembers.getOrDefault(groupName, new ArrayList<>());
            if (!members.isEmpty()) {
                sb.append(String.join(",", members));
            }

            configList.add(sb.toString());
        }

        if (onSave != null) {
            onSave.accept(configList);
        }
        onClose();
    }

    // ==================== JEI 拖拽支持 ====================

    /**
     * 处理 JEI 物品拖拽（通用）
     */
    public void handleJEIItemDrop(ItemStack itemStack) {
        // simukraft: 默认添加到组头
        handleJEIItemDropToHeader(itemStack);
    }

    /**
     * 处理 JEI 物品拖拽到组头区域
     */
    public void handleJEIItemDropToHeader(ItemStack itemStack) {
        if (selectedGroup == null || itemStack.isEmpty()) {
            return;
        }

        String itemId = getItemIdFromStack(itemStack);
        if (itemId == null) {
            return;
        }

        List<String> headers = groupHeaders.get(selectedGroup);
        if (headers == null) {
            return;
        }

        if (!headers.contains(itemId)) {
            headers.add(itemId);
            refreshHeaderList();
            // simukraft: 添加后自动滚动到新物品
            scrollToItemInHeaderList(itemId);
        }
    }

    /**
     * 处理 JEI 物品拖拽到组员区域
     */
    public void handleJEIItemDropToMember(ItemStack itemStack) {
        if (selectedGroup == null || itemStack.isEmpty()) {
            return;
        }

        String itemId = getItemIdFromStack(itemStack);
        if (itemId == null) {
            return;
        }

        List<String> members = groupMembers.get(selectedGroup);
        if (members == null) {
            return;
        }

        if (!members.contains(itemId)) {
            members.add(itemId);
            refreshMemberList();
            // simukraft: 添加后自动滚动到新物品
            scrollToItemInMemberList(itemId);
        }
    }

    /**
     * 滚动到组头列表中的指定物品
     */
    private void scrollToItemInHeaderList(String itemId) {
        if (headerListGroup == null || selectedGroup == null) return;

        List<String> headers = groupHeaders.getOrDefault(selectedGroup, new ArrayList<>());
        int index = headers.indexOf(itemId);
        if (index < 0) return;

        scrollToIndex(headerListGroup, index, headers.size());
    }

    /**
     * 滚动到组员列表中的指定物品
     */
    private void scrollToItemInMemberList(String itemId) {
        if (memberListGroup == null || selectedGroup == null) return;

        List<String> members = groupMembers.getOrDefault(selectedGroup, new ArrayList<>());
        int index = members.indexOf(itemId);
        if (index < 0) return;

        scrollToIndex(memberListGroup, index, members.size());
    }

    /**
     * 滚动到指定索引位置
     */
    private void scrollToIndex(DraggableScrollableWidgetGroup listGroup, int index, int totalSize) {
        // simukraft: 计算目标滚动位置（每个项目高度 24）
        int targetY = index * ITEM_HEIGHT;

        // simukraft: 获取列表可视区域高度
        int visibleHeight = listGroup.getSize().height;

        // simukraft: 计算需要滚动的偏移量，使目标物品显示在列表中间
        int scrollOffset = targetY - visibleHeight / 2 + ITEM_HEIGHT / 2;

        // simukraft: 确保滚动位置在有效范围内
        int maxScroll = Math.max(0, totalSize * ITEM_HEIGHT - visibleHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // simukraft: 设置滚动位置
        listGroup.setScrollYOffset(scrollOffset);
    }

    /**
     * 从物品堆栈获取物品ID
     */
    private String getItemIdFromStack(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return null;
        }

        var item = itemStack.getItem();
        var key = item.builtInRegistryHolder().key();
        if (key != null) {
            return key.location().toString();
        }

        return null;
    }

    // ==================== UI Holder ====================

    private static class GroupsUIHolder implements IUIHolder {

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
            createLeftPanel(windowGroup);
            createMiddlePanel(windowGroup);
            createRightPanel(windowGroup);
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

            TextTexture titleTexture = new TextTexture("通类匹配组配置", COLOR_TEXT_TITLE);
            titleTexture.setType(TextTexture.TextType.NORMAL);
            headerGroup.addWidget(new ImageWidget(0, 8, WINDOW_WIDTH - 4, 16, titleTexture));

            TextTexture descTexture = new TextTexture("配置普通模式下的材料通类匹配", COLOR_TEXT_GRAY);
            descTexture.setType(TextTexture.TextType.NORMAL);
            headerGroup.addWidget(new ImageWidget(0, 28, WINDOW_WIDTH - 4, 12, descTexture));

            parent.addWidget(headerGroup);
        }

        private void createLeftPanel(WidgetGroup parent) {
            int panelX = 15;
            int panelY = HEADER_HEIGHT + 10;
            int panelH = WINDOW_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 20;

            // 面板背景
            WidgetGroup panelBg = new WidgetGroup();
            panelBg.setSelfPosition(panelX, panelY);
            panelBg.setSize(PANEL_WIDTH, panelH);
            panelBg.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_PANEL_BG).setRadius(5),
                    new ColorBorderTexture(1, COLOR_INPUT_BORDER).setRadius(5)
            ));
            parent.addWidget(panelBg);

            // 标题
            TextTexture titleTexture = new TextTexture("组列表", COLOR_TEXT_TITLE);
            titleTexture.setType(TextTexture.TextType.NORMAL);
            panelBg.addWidget(new ImageWidget(10, 8, PANEL_WIDTH - 20, 14, titleTexture));

            // simukraft: 新建组输入框容器（带下划线样式）
            WidgetGroup newGroupInputContainer = new WidgetGroup();
            newGroupInputContainer.setSelfPosition(10, 28);
            newGroupInputContainer.setSize(PANEL_WIDTH - 60, 18);
            panelBg.addWidget(newGroupInputContainer);

            // 新建组输入框 - 透明背景无边框
            TextFieldWidget newGroupField = new TextFieldWidget();
            newGroupField.setSelfPosition(0, 0);
            newGroupField.setSize(PANEL_WIDTH - 60, 16);
            newGroupField.setTextColor(COLOR_TEXT_NORMAL);
            newGroupField.setCurrentString("");
            newGroupField.setBordered(false);
            newGroupInputContainer.addWidget(newGroupField);
            if (currentInstance != null) {
                currentInstance.newGroupField = newGroupField;
            }

            // 底部下划线
            WidgetGroup newGroupUnderline = new WidgetGroup();
            newGroupUnderline.setSelfPosition(0, 16);
            newGroupUnderline.setSize(PANEL_WIDTH - 60, 2);
            newGroupUnderline.setBackground(new ColorRectTexture(0xFF808080).setRadius(1));
            newGroupInputContainer.addWidget(newGroupUnderline);

            // 添加组按钮
            ButtonWidget addGroupBtn = new ButtonWidget();
            addGroupBtn.setSelfPosition(PANEL_WIDTH - 45, 28);
            addGroupBtn.setSize(30, 16);
            TextTexture addGroupText = new TextTexture("+", COLOR_TEXT_TITLE);
            addGroupText.setType(TextTexture.TextType.NORMAL);
            addGroupText.setWidth(30);
            addGroupBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(3),
                    addGroupText
            ));
            TextTexture addGroupHoverText = new TextTexture("+", COLOR_TEXT_TITLE);
            addGroupHoverText.setType(TextTexture.TextType.NORMAL);
            addGroupHoverText.setWidth(30);
            addGroupBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(3),
                    addGroupHoverText
            ));
            addGroupBtn.setOnPressCallback(click -> {
                if (currentInstance != null) currentInstance.addNewGroup();
            });
            panelBg.addWidget(addGroupBtn);

            // 删除组按钮
            ButtonWidget deleteGroupBtn = new ButtonWidget();
            deleteGroupBtn.setSelfPosition(10, panelH - 28);
            deleteGroupBtn.setSize(PANEL_WIDTH - 20, 20);
            TextTexture deleteText = new TextTexture("删除选中组", COLOR_TEXT_TITLE);
            deleteText.setType(TextTexture.TextType.NORMAL);
            deleteText.setWidth(PANEL_WIDTH - 20);
            deleteGroupBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_DELETE_BUTTON).setRadius(3),
                    deleteText
            ));
            TextTexture deleteHoverText = new TextTexture("删除选中组", COLOR_TEXT_TITLE);
            deleteHoverText.setType(TextTexture.TextType.NORMAL);
            deleteHoverText.setWidth(PANEL_WIDTH - 20);
            deleteGroupBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_DELETE_HOVER).setRadius(3),
                    deleteHoverText
            ));
            deleteGroupBtn.setOnPressCallback(click -> {
                if (currentInstance != null) currentInstance.deleteSelectedGroup();
            });
            panelBg.addWidget(deleteGroupBtn);
            if (currentInstance != null) {
                currentInstance.deleteGroupBtn = deleteGroupBtn;
            }

            // 组列表
            DraggableScrollableWidgetGroup groupList = new DraggableScrollableWidgetGroup();
            groupList.setSelfPosition(5, 50);
            groupList.setSize(PANEL_WIDTH - 10, panelH - 85);
            panelBg.addWidget(groupList);
            if (currentInstance != null) {
                currentInstance.groupListGroup = groupList;
                // simukraft: 不在创建时刷新，在init()中统一刷新
            }
        }

        private void createMiddlePanel(WidgetGroup parent) {
            int panelX = 15 + PANEL_WIDTH + 15;
            int panelY = HEADER_HEIGHT + 10;
            int panelH = WINDOW_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 20;

            // 面板背景
            WidgetGroup panelBg = new WidgetGroup();
            panelBg.setSelfPosition(panelX, panelY);
            panelBg.setSize(PANEL_WIDTH, panelH);
            panelBg.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_PANEL_BG).setRadius(5),
                    new ColorBorderTexture(1, COLOR_INPUT_BORDER).setRadius(5)
            ));
            parent.addWidget(panelBg);

            // 标题
            TextTexture titleTexture = new TextTexture("组头", COLOR_TEXT_HEADER);
            titleTexture.setType(TextTexture.TextType.NORMAL);
            panelBg.addWidget(new ImageWidget(10, 8, PANEL_WIDTH - 20, 14, titleTexture));

            // simukraft: 添加组头输入框容器（带下划线样式）
            WidgetGroup headerInputContainer = new WidgetGroup();
            headerInputContainer.setSelfPosition(10, 28);
            headerInputContainer.setSize(PANEL_WIDTH - 60, 18);
            panelBg.addWidget(headerInputContainer);

            // 添加组头输入框 - 透明背景无边框
            TextFieldWidget headerField = new TextFieldWidget();
            headerField.setSelfPosition(0, 0);
            headerField.setSize(PANEL_WIDTH - 60, 16);
            headerField.setTextColor(COLOR_TEXT_NORMAL);
            headerField.setCurrentString("");
            headerField.setBordered(false);
            headerInputContainer.addWidget(headerField);
            if (currentInstance != null) {
                currentInstance.headerField = headerField;
            }

            // 底部下划线
            WidgetGroup headerUnderline = new WidgetGroup();
            headerUnderline.setSelfPosition(0, 16);
            headerUnderline.setSize(PANEL_WIDTH - 60, 2);
            headerUnderline.setBackground(new ColorRectTexture(0xFF808080).setRadius(1));
            headerInputContainer.addWidget(headerUnderline);

            // 添加组头按钮
            ButtonWidget addHeaderBtn = new ButtonWidget();
            addHeaderBtn.setSelfPosition(PANEL_WIDTH - 45, 28);
            addHeaderBtn.setSize(30, 16);
            TextTexture addHeaderText = new TextTexture("+", COLOR_TEXT_TITLE);
            addHeaderText.setType(TextTexture.TextType.NORMAL);
            addHeaderText.setWidth(30);
            addHeaderBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(3),
                    addHeaderText
            ));
            TextTexture addHeaderHoverText = new TextTexture("+", COLOR_TEXT_TITLE);
            addHeaderHoverText.setType(TextTexture.TextType.NORMAL);
            addHeaderHoverText.setWidth(30);
            addHeaderBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(3),
                    addHeaderHoverText
            ));
            addHeaderBtn.setOnPressCallback(click -> {
                if (currentInstance != null) currentInstance.addHeader();
            });
            panelBg.addWidget(addHeaderBtn);
            if (currentInstance != null) {
                currentInstance.addHeaderBtn = addHeaderBtn;
            }

            // 组头列表
            DraggableScrollableWidgetGroup headerList = new DraggableScrollableWidgetGroup();
            headerList.setSelfPosition(5, 50);
            headerList.setSize(PANEL_WIDTH - 10, panelH - 60);
            panelBg.addWidget(headerList);
            if (currentInstance != null) {
                currentInstance.headerListGroup = headerList;
                // simukraft: 不在创建时刷新，在init()中统一刷新
            }
        }

        private void createRightPanel(WidgetGroup parent) {
            int panelX = 15 + PANEL_WIDTH * 2 + 30;
            int panelY = HEADER_HEIGHT + 10;
            int panelH = WINDOW_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 20;

            // 面板背景
            WidgetGroup panelBg = new WidgetGroup();
            panelBg.setSelfPosition(panelX, panelY);
            panelBg.setSize(PANEL_WIDTH, panelH);
            panelBg.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_PANEL_BG).setRadius(5),
                    new ColorBorderTexture(1, COLOR_INPUT_BORDER).setRadius(5)
            ));
            parent.addWidget(panelBg);

            // 标题
            TextTexture titleTexture = new TextTexture("组员", COLOR_TEXT_MEMBER);
            titleTexture.setType(TextTexture.TextType.NORMAL);
            panelBg.addWidget(new ImageWidget(10, 8, PANEL_WIDTH - 20, 14, titleTexture));

            // simukraft: 添加组员输入框容器（带下划线样式）
            WidgetGroup memberInputContainer = new WidgetGroup();
            memberInputContainer.setSelfPosition(10, 28);
            memberInputContainer.setSize(PANEL_WIDTH - 60, 18);
            panelBg.addWidget(memberInputContainer);

            // 添加组员输入框 - 透明背景无边框
            TextFieldWidget memberField = new TextFieldWidget();
            memberField.setSelfPosition(0, 0);
            memberField.setSize(PANEL_WIDTH - 60, 16);
            memberField.setTextColor(COLOR_TEXT_NORMAL);
            memberField.setCurrentString("");
            memberField.setBordered(false);
            memberInputContainer.addWidget(memberField);
            if (currentInstance != null) {
                currentInstance.memberField = memberField;
            }

            // 底部下划线
            WidgetGroup memberUnderline = new WidgetGroup();
            memberUnderline.setSelfPosition(0, 16);
            memberUnderline.setSize(PANEL_WIDTH - 60, 2);
            memberUnderline.setBackground(new ColorRectTexture(0xFF808080).setRadius(1));
            memberInputContainer.addWidget(memberUnderline);

            // 添加组员按钮
            ButtonWidget addMemberBtn = new ButtonWidget();
            addMemberBtn.setSelfPosition(PANEL_WIDTH - 45, 28);
            addMemberBtn.setSize(30, 16);
            TextTexture addMemberText = new TextTexture("+", COLOR_TEXT_TITLE);
            addMemberText.setType(TextTexture.TextType.NORMAL);
            addMemberText.setWidth(30);
            addMemberBtn.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(3),
                    addMemberText
            ));
            TextTexture addMemberHoverText = new TextTexture("+", COLOR_TEXT_TITLE);
            addMemberHoverText.setType(TextTexture.TextType.NORMAL);
            addMemberHoverText.setWidth(30);
            addMemberBtn.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(3),
                    addMemberHoverText
            ));
            addMemberBtn.setOnPressCallback(click -> {
                if (currentInstance != null) currentInstance.addMember();
            });
            panelBg.addWidget(addMemberBtn);
            if (currentInstance != null) {
                currentInstance.addMemberBtn = addMemberBtn;
            }

            // 组员列表
            DraggableScrollableWidgetGroup memberList = new DraggableScrollableWidgetGroup();
            memberList.setSelfPosition(5, 50);
            memberList.setSize(PANEL_WIDTH - 10, panelH - 60);
            panelBg.addWidget(memberList);
            if (currentInstance != null) {
                currentInstance.memberListGroup = memberList;
                // simukraft: 不在创建时刷新，在init()中统一刷新
            }
        }

        private void createFooter(WidgetGroup parent) {
            int footerY = WINDOW_HEIGHT - FOOTER_HEIGHT + 5;
            int centerX = WINDOW_WIDTH / 2;

            // 保存按钮
            ButtonWidget saveBtn = new ButtonWidget();
            saveBtn.setSelfPosition(centerX - 80, footerY);
            saveBtn.setSize(70, 28);
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

            // 取消按钮
            ButtonWidget cancelBtn = new ButtonWidget();
            cancelBtn.setSelfPosition(centerX + 10, footerY);
            cancelBtn.setSize(70, 28);
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
