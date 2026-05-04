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
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.SyncConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// 使用完整类名避免与Minecraft的Runnable冲突

/*
 * 服务器配置界面 - LDLib现代化版本
 * 使用LDLib组件实现简约现代化UI设计
 * 该界面以固定3x缩放渲染
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class ServerConfigScreen extends ModularUIGuiContainer {

    // 颜色定义 - 现代化简约风格
    private static final int COLOR_WINDOW_BG = 0xE6202020;      // 半透明深色背景
    private static final int COLOR_WINDOW_BORDER = 0xFF4A90A4;  // 青蓝色边框
    private static final int COLOR_HEADER_BG = 0xFF2D5A6B;      // 深蓝绿色标题栏
    private static final int COLOR_TAB_ACTIVE = 0xFF4A90A4;     // 激活标签色
    private static final int COLOR_TAB_INACTIVE = 0xFF3A3A3A;   // 非激活标签色
    private static final int COLOR_PANEL_BG = 0xCC2A2A2A;       // 面板背景（半透明）
    private static final int COLOR_CARD_BG = 0xFF353535;        // 卡片背景
    private static final int COLOR_CARD_HOVER = 0xFF454545;     // 卡片悬停
    private static final int COLOR_BUTTON_BG = 0xFF3A5A6A;      // 按钮背景
    private static final int COLOR_BUTTON_HOVER = 0xFF4A7A8A;   // 按钮悬停
    private static final int COLOR_SWITCH_ON = 0xFF4CAF50;      // 开关开启
    private static final int COLOR_SWITCH_OFF = 0xFF666666;     // 开关关闭
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;     // 标题白色
    private static final int COLOR_TEXT_NORMAL = 0xFFE0E0E0;    // 正常文本
    private static final int COLOR_TEXT_HIGHLIGHT = 0xFF88CCFF; // 高亮文本
    private static final int COLOR_TEXT_GRAY = 0xFFAAAAAA;      // 灰色文本

    // 布局常量
    private static final int WINDOW_WIDTH = 360;
    private static final int WINDOW_HEIGHT = 420;
    private static final int HEADER_HEIGHT = 36;
    private static final int TAB_HEIGHT = 28;
    private static final int FOOTER_HEIGHT = 42;
    private static final int CONTENT_PADDING = 12;
    private static final int ITEM_HEIGHT = 32;
    private static final int SECTION_SPACING = 16;

    // 页面定义
    private static final String[] PAGE_KEYS = {
            "general", "npc", "planner", "builder", "materials"
    };
    private static final String[] PAGE_TITLES = {
            "通用", "NPC等级", "规划师", "建筑师", "材料"
    };

    private final Screen parent;
    private int currentPage = 0;
    private final ConfigUIHolder holder;

    public ServerConfigScreen(Screen parent) {
        super(createHolderAndUI(), 0);
        this.parent = parent;
        this.holder = ((ModularUI) this.modularUI).holder instanceof ConfigUIHolder
                ? (ConfigUIHolder) ((ModularUI) this.modularUI).holder
                : null;
        if (this.holder != null) {
            this.holder.setScreen(this);
            // 设置screen后刷新页面内容
            this.holder.refreshPage();
        }
    }

    private static ModularUI createHolderAndUI() {
        // simukraft: 根据窗口尺寸选择能完整显示的最大缩放
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);

        ConfigUIHolder holder = new ConfigUIHolder();
        return holder.createModularUI();
    }

    @Override
    public void onClose() {
        // simukraft: 恢复原始缩放并重置状态（返回到非3x界面）
        GuiScaleManager.forceRestore();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void init() {
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
        super.init();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        GuiScaleManager.applyBestFitScale(WINDOW_WIDTH, WINDOW_HEIGHT);
        super.resize(minecraft, width, height);
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
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 切换到指定页面
     */
    public void switchToPage(int page) {
        if (page >= 0 && page < PAGE_KEYS.length && page != currentPage) {
            currentPage = page;
            if (holder != null) {
                holder.refreshPage();
            }
        }
    }

    /**
     * 获取当前页码
     */
    public int getCurrentPage() {
        return currentPage;
    }

    // ==================== UI Holder ====================

    private static class ConfigUIHolder implements IUIHolder {
        private ServerConfigScreen screen;
        private ModularUI modularUI;
        private DraggableScrollableWidgetGroup contentGroup; //: 改为可滚动容器
        private final Map<String, ConfigValue<?>> configValues = new HashMap<>();
        private final List<ButtonWidget> tabButtons = new ArrayList<>(); //: 存储标签按钮引用
        // simukraft: 存储所有数字输入框，用于保存时读取最新值
        private final Map<String, TextFieldWidget> intInputFields = new HashMap<>();
        // menglan: 存储小数输入框
        private final Map<String, TextFieldWidget> doubleInputFields = new HashMap<>();

        public void setScreen(ServerConfigScreen screen) {
            this.screen = screen;
        }

        @Override
        public ModularUI createUI(Player entityPlayer) {
            return createModularUI();
        }

        public ModularUI createModularUI() {
            Minecraft mc = Minecraft.getInstance();
            modularUI = new ModularUI(new Size(WINDOW_WIDTH, WINDOW_HEIGHT), this, mc.player);

            // 根容器
            WidgetGroup rootGroup = new WidgetGroup();
            rootGroup.setSelfPosition(0, 0);
            rootGroup.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);

            // 主窗口背景 - 半透明毛玻璃效果
            WidgetGroup windowGroup = new WidgetGroup();
            windowGroup.setSelfPosition(0, 0);
            windowGroup.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
            windowGroup.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_WINDOW_BG).setRadius(10),
                    new ColorBorderTexture(2, COLOR_WINDOW_BORDER).setRadius(10)
            ));
            rootGroup.addWidget(windowGroup);

            // 标题栏
            createHeader(windowGroup);

            // 标签栏
            createTabs(windowGroup);
            // 初始化标签按钮外观
            updateTabButtons();

            // 内容区域 -: 使用可滚动容器支持内容裁切和滚动
            int contentWidth = WINDOW_WIDTH - CONTENT_PADDING * 2;
            int contentHeight = WINDOW_HEIGHT - HEADER_HEIGHT - TAB_HEIGHT - FOOTER_HEIGHT - 16;
            contentGroup = new DraggableScrollableWidgetGroup(
                    CONTENT_PADDING, HEADER_HEIGHT + TAB_HEIGHT + 8,
                    contentWidth, contentHeight
            );
            contentGroup.setBackground(new ColorRectTexture(COLOR_PANEL_BG).setRadius(6));
            windowGroup.addWidget(contentGroup);

            // 底部按钮栏
            createFooter(windowGroup);

            modularUI.widget(rootGroup);
            modularUI.initWidgets();

            return modularUI;
        }

        /**
         * 创建标题栏
         */
        private void createHeader(WidgetGroup parent) {
            WidgetGroup headerGroup = new WidgetGroup();
            headerGroup.setSelfPosition(2, 2);
            headerGroup.setSize(WINDOW_WIDTH - 4, HEADER_HEIGHT - 4);
            headerGroup.setBackground(new ColorRectTexture(COLOR_HEADER_BG).setRadius(8));
            parent.addWidget(headerGroup);

            // 标题
            TextTexture titleTexture = new TextTexture("服务器配置", COLOR_TEXT_TITLE);
            titleTexture.setType(TextTexture.TextType.NORMAL);
            ImageWidget titleWidget = new ImageWidget(0, 10, WINDOW_WIDTH - 4, 16, titleTexture);
            headerGroup.addWidget(titleWidget);
        }

        /**
         * 创建标签栏
         */
        private void createTabs(WidgetGroup parent) {
            int tabWidth = (WINDOW_WIDTH - CONTENT_PADDING * 2 - (PAGE_KEYS.length - 1) * 4) / PAGE_KEYS.length;
            int startX = CONTENT_PADDING;
            int tabY = HEADER_HEIGHT + 4;

            tabButtons.clear();
            for (int i = 0; i < PAGE_KEYS.length; i++) {
                final int pageIndex = i;
                ButtonWidget tabButton = createTabButton(
                        startX + i * (tabWidth + 4),
                        tabY,
                        tabWidth,
                        TAB_HEIGHT,
                        PAGE_TITLES[i],
                        pageIndex
                );
                tabButtons.add(tabButton);
                parent.addWidget(tabButton);
            }
        }

        /**
         * 创建标签按钮
         */
        private ButtonWidget createTabButton(int x, int y, int width, int height,
                                              String text, int pageIndex) {
            ButtonWidget button = new ButtonWidget();
            button.setSelfPosition(x, y);
            button.setSize(width, height);
            button.setOnPressCallback(clickData -> {
                if (screen != null) {
                    screen.switchToPage(pageIndex);
                }
            });

            return button;
        }

        // 更新所有标签按钮的外观
        private void updateTabButtons() {
            if (screen == null) return;
            int currentPage = screen.getCurrentPage();
            for (int i = 0; i < tabButtons.size() && i < PAGE_TITLES.length; i++) {
                ButtonWidget button = tabButtons.get(i);
                boolean isActive = (i == currentPage);
                // 激活状态使用蓝色，非激活使用灰色
                int bgColor = isActive ? COLOR_TAB_ACTIVE : COLOR_TAB_INACTIVE;
                int textColor = isActive ? COLOR_TEXT_TITLE : COLOR_TEXT_GRAY;
                int borderColor = isActive ? COLOR_TEXT_HIGHLIGHT : COLOR_WINDOW_BORDER;
                button.setButtonTexture(new GuiTextureGroup(
                        new ColorRectTexture(bgColor).setRadius(4),
                        new ColorBorderTexture(1, borderColor).setRadius(4)
                ), new TextTexture(PAGE_TITLES[i], textColor).setType(TextTexture.TextType.NORMAL));
            }
        }

        /**
         * 创建底部按钮栏
         */
        private void createFooter(WidgetGroup parent) {
            int footerY = WINDOW_HEIGHT - FOOTER_HEIGHT + 4;
            int btnWidth = 64;
            int btnHeight = 28;
            int btnSpacing = 8;
            int totalWidth = btnWidth * 5 + btnSpacing * 4;
            int startX = (WINDOW_WIDTH - totalWidth) / 2;

            // 保存按钮
            parent.addWidget(createFooterButton(startX, footerY, btnWidth, btnHeight, "保存",
                    clickData -> saveConfig()));

            // 重载按钮
            parent.addWidget(createFooterButton(startX + btnWidth + btnSpacing, footerY, btnWidth, btnHeight, "重载",
                    clickData -> reloadConfig()));

            // 重置按钮
            parent.addWidget(createFooterButton(startX + (btnWidth + btnSpacing) * 2, footerY, btnWidth, btnHeight, "重置",
                    clickData -> resetConfig()));

            // 取消按钮
            parent.addWidget(createFooterButton(startX + (btnWidth + btnSpacing) * 3, footerY, btnWidth, btnHeight, "取消",
                    clickData -> closeScreen()));

            // 关闭按钮
            parent.addWidget(createFooterButton(startX + (btnWidth + btnSpacing) * 4, footerY, btnWidth, btnHeight, "关闭",
                    clickData -> closeScreen()));
        }

        private ButtonWidget createFooterButton(int x, int y, int width, int height,
                                                 String text, Consumer<ClickData> onPress) {
            ButtonWidget button = new ButtonWidget();
            button.setSelfPosition(x, y);
            button.setSize(width, height);

            TextTexture btnText = new TextTexture(text, COLOR_TEXT_NORMAL);
            btnText.setType(TextTexture.TextType.NORMAL);

            button.setButtonTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_BG).setRadius(4),
                    new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(4)
            ), btnText);

            button.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(4),
                    new ColorBorderTexture(1, COLOR_TEXT_HIGHLIGHT).setRadius(4)
            ), btnText);

            button.setOnPressCallback(onPress);
            return button;
        }

        /**
         * 刷新当前页面内容
         */
        public void refreshPage() {
            if (contentGroup == null || screen == null) return;

            // 更新标签按钮外观
            updateTabButtons();

            contentGroup.clearAllWidgets();
            configValues.clear();
            // simukraft: 清空输入框映射，防止旧引用干扰
            intInputFields.clear();
            doubleInputFields.clear();

            int page = screen.getCurrentPage();
            int contentWidth = WINDOW_WIDTH - CONTENT_PADDING * 2 - 16;
            int startY = 10;

            switch (page) {
                case 0 -> buildGeneralPage(contentGroup, contentWidth, startY);
                case 1 -> buildNpcPage(contentGroup, contentWidth, startY);
                case 2 -> buildPlannerPage(contentGroup, contentWidth, startY);
                case 3 -> buildBuilderPage(contentGroup, contentWidth, startY);
                case 4 -> buildMaterialsPage(contentGroup, contentWidth, startY);
            }
        }

        // ==================== 页面构建 ====================

        private void buildGeneralPage(WidgetGroup parent, int width, int startY) {
            addSectionTitle(parent, width, startY, "通用设置");
            startY += 24;

            addBooleanOption(parent, width, startY, "enableBlacklistProtection",
                    "启用黑名单保护", "禁用后规划师和建筑师可处理所有方块",
                    ServerConfig.ENABLE_BLACKLIST_PROTECTION.get(),
                    value -> configValues.put("enableBlacklistProtection", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT;

            addBooleanOption(parent, width, startY, "logSkippedBlocks",
                    "记录跳过的方块", "在日志中记录被跳过的黑名单方块",
                    ServerConfig.LOG_BLACKLIST_SKIPPED_BLOCKS.get(),
                    value -> configValues.put("logSkippedBlocks", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT;

            addBooleanOption(parent, width, startY, "enableDebugLog",
                    "启用调试日志", "输出更多详细信息到日志",
                    ServerConfig.ENABLE_DEBUG_LOG.get(),
                    value -> configValues.put("enableDebugLog", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT;

            addBooleanOption(parent, width, startY, "farmerEnableCropGrowthBoost",
                    "作物加速生长", "农民是否启用作物加速生长",
                    ServerConfig.isFarmerCropGrowthBoostEnabled(),
                    value -> configValues.put("farmerEnableCropGrowthBoost", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT + SECTION_SPACING;

            // 创造模式和专家模式 - 移到通用设置
            addSectionTitle(parent, width, startY, "建造模式");
            startY += 24;

            addBooleanOption(parent, width, startY, "enableCreativeMode",
                    "创造模式", "建筑师不需要材料和金钱(优先于专家模式)",
                    ServerConfig.ENABLE_CREATIVE_MODE.get(),
                    value -> configValues.put("enableCreativeMode", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT;

            addBooleanOption(parent, width, startY, "enableExpertMode",
                    "专家模式", "建筑师需要所有材料(除跳过列表)",
                    ServerConfig.ENABLE_EXPERT_MODE.get(),
                    value -> configValues.put("enableExpertMode", new BooleanConfigValue(value)));
        }

        private void buildNpcPage(WidgetGroup parent, int width, int startY) {
            addSectionTitle(parent, width, startY, "NPC等级系统");
            startY += 24;

            addIntOption(parent, width, startY, "npcMaxLevel",
                    "最高等级", "NPC可达到的最高等级",
                    ServerConfig.NPC_MAX_LEVEL.get(), 1, 20,
                    value -> configValues.put("npcMaxLevel", new IntConfigValue(value)));
            startY += ITEM_HEIGHT;

            addIntOption(parent, width, startY, "npcSpeedBonusPerLevel",
                    "每级加速", "每升一级减少的工作时间(tick)",
                    ServerConfig.NPC_SPEED_BONUS_PER_LEVEL.get(), 0, 50,
                    value -> configValues.put("npcSpeedBonusPerLevel", new IntConfigValue(value)));
            startY += ITEM_HEIGHT;

            addIntOption(parent, width, startY, "npcMinSpeedTicks",
                    "最短时间", "NPC工作的最短时间(tick)",
                    ServerConfig.NPC_MIN_SPEED_TICKS.get(), 1, 100,
                    value -> configValues.put("npcMinSpeedTicks", new IntConfigValue(value)));
        }

        private void buildPlannerPage(WidgetGroup parent, int width, int startY) {
            addSectionTitle(parent, width, startY, "工作速度");
            startY += 24;

            addIntOption(parent, width, startY, "plannerRemoveSpeedBase",
                    "拆除速度", "基础拆除速度(tick/方块)",
                    ServerConfig.PLANNER_REMOVE_SPEED_BASE.get(), 5, 200,
                    value -> configValues.put("plannerRemoveSpeedBase", new IntConfigValue(value)));
            startY += ITEM_HEIGHT;

            addIntOption(parent, width, startY, "plannerReplaceSpeedBase",
                    "替换速度", "基础替换速度(tick/方块)",
                    ServerConfig.PLANNER_REPLACE_SPEED_BASE.get(), 5, 200,
                    value -> configValues.put("plannerReplaceSpeedBase", new IntConfigValue(value)));
            startY += ITEM_HEIGHT;

            addIntOption(parent, width, startY, "plannerFillSpeedBase",
                    "填充速度", "基础填充速度(tick/方块)",
                    ServerConfig.PLANNER_FILL_SPEED_BASE.get(), 5, 200,
                    value -> configValues.put("plannerFillSpeedBase", new IntConfigValue(value)));
            startY += ITEM_HEIGHT + SECTION_SPACING;

            addSectionTitle(parent, width, startY, "物品处理");
            startY += 24;

            addBooleanOption(parent, width, startY, "plannerDropItemsOnRemove",
                    "拆除掉落物品", "拆除方块时是否掉落物品",
                    ServerConfig.PLANNER_DROP_ITEMS_ON_REMOVE.get(),
                    value -> configValues.put("plannerDropItemsOnRemove", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT;

            addBooleanOption(parent, width, startY, "plannerStoreItemsInChest",
                    "存入箱子", "是否将物品存入附近箱子",
                    ServerConfig.PLANNER_STORE_ITEMS_IN_CHEST.get(),
                    value -> configValues.put("plannerStoreItemsInChest", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT;

            addIntOption(parent, width, startY, "plannerChestSearchRange",
                    "搜索范围", "搜索箱子的范围(格)",
                    ServerConfig.PLANNER_CHEST_SEARCH_RANGE.get(), 1, 20,
                    value -> configValues.put("plannerChestSearchRange", new IntConfigValue(value)));
            startY += ITEM_HEIGHT + SECTION_SPACING;

            addSectionTitle(parent, width, startY, "其他设置");
            startY += 24;

            addIntOption(parent, width, startY, "plannerWarningCooldown",
                    "警告冷却", "材料不足警告冷却时间(秒)",
                    ServerConfig.PLANNER_WARNING_COOLDOWN.get(), 1, 300,
                    value -> configValues.put("plannerWarningCooldown", new IntConfigValue(value)));
            startY += ITEM_HEIGHT;

            addBooleanOption(parent, width, startY, "plannerEnableXpGain",
                    "获得经验", "规划师是否获得经验",
                    ServerConfig.PLANNER_ENABLE_XP_GAIN.get(),
                    value -> configValues.put("plannerEnableXpGain", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT;

            addIntOption(parent, width, startY, "plannerXpPerBlock",
                    "每块经验", "每处理一个方块获得的经验值",
                    ServerConfig.PLANNER_XP_PER_BLOCK.get(), 0, 100,
                    value -> configValues.put("plannerXpPerBlock", new IntConfigValue(value)));
        }

        private void buildBuilderPage(WidgetGroup parent, int width, int startY) {
            addSectionTitle(parent, width, startY, "建造速度");
            startY += 24;

            // menglan: 使用更直观的每秒方块数配置
            addDoubleOption(parent, width, startY, "builderBlocksPerSecond",
                    "1级速度", "1级建筑师每秒放置方块数(20级=5倍)",
                    ServerConfig.BUILDER_BLOCKS_PER_SECOND.get(), 0.1, 20.0,
                    value -> configValues.put("builderBlocksPerSecond", new DoubleConfigValue(value)));
            startY += ITEM_HEIGHT + SECTION_SPACING;

            addSectionTitle(parent, width, startY, "材料搜索");
            startY += 24;

            addIntOption(parent, width, startY, "builderChestSearchRange",
                    "搜索范围", "搜索材料的箱子范围(格)",
                    ServerConfig.BUILDER_CHEST_SEARCH_RANGE.get(), 1, 20,
                    value -> configValues.put("builderChestSearchRange", new IntConfigValue(value)));
            startY += ITEM_HEIGHT + SECTION_SPACING;

            addSectionTitle(parent, width, startY, "经验与警告");
            startY += 24;

            addBooleanOption(parent, width, startY, "builderEnableXpGain",
                    "获得经验", "建筑师是否获得经验",
                    ServerConfig.BUILDER_ENABLE_XP_GAIN.get(),
                    value -> configValues.put("builderEnableXpGain", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT;

            addIntOption(parent, width, startY, "builderXpPerBlock",
                    "每块经验", "每放置一个方块获得的经验值",
                    ServerConfig.BUILDER_XP_PER_BLOCK.get(), 0, 100,
                    value -> configValues.put("builderXpPerBlock", new IntConfigValue(value)));
            startY += ITEM_HEIGHT;

            addIntOption(parent, width, startY, "builderWarningCooldown",
                    "警告冷却", "材料不足警告冷却时间(秒)",
                    ServerConfig.BUILDER_WARNING_COOLDOWN.get(), 1, 300,
                    value -> configValues.put("builderWarningCooldown", new IntConfigValue(value)));
            startY += ITEM_HEIGHT + SECTION_SPACING;

            addSectionTitle(parent, width, startY, "性能");
            startY += 24;

            addIntOption(parent, width, startY, "builderChunkLoadWaitTicks",
                    "区块加载等待", "等待区块就绪的最大tick数",
                    ServerConfig.BUILDER_CHUNK_LOAD_WAIT_TICKS.get(), 10, 200,
                    value -> configValues.put("builderChunkLoadWaitTicks", new IntConfigValue(value)));
        }

        private void buildMaterialsPage(WidgetGroup parent, int width, int startY) {
            addSectionTitle(parent, width, startY, "模式配置");
            startY += 24;

            addBooleanOption(parent, width, startY, "enableMaterialCategoryMatching",
                    "通类匹配", "普通模式下启用材料通类匹配",
                    ServerConfig.ENABLE_MATERIAL_CATEGORY_MATCHING.get(),
                    value -> configValues.put("enableMaterialCategoryMatching", new BooleanConfigValue(value)));
            startY += ITEM_HEIGHT + SECTION_SPACING;

            addSectionTitle(parent, width, startY, "列表配置");
            startY += 24;

            // 规划师黑名单按钮
            addOpenScreenButton(parent, width, startY, "编辑规划师黑名单",
                    clickData -> openPlanningBlacklist());
            startY += ITEM_HEIGHT;

            // 建筑师黑名单按钮
            addOpenScreenButton(parent, width, startY, "编辑建筑师黑名单",
                    clickData -> openBuilderBlacklist());
            startY += ITEM_HEIGHT;

            // 基础材料按钮
            addOpenScreenButton(parent, width, startY, "编辑基础材料",
                    clickData -> openBasicMaterials());
            startY += ITEM_HEIGHT;

            // 通类匹配组按钮
            addOpenScreenButton(parent, width, startY, "编辑通类匹配组",
                    clickData -> openMaterialCategoryGroups());
            startY += ITEM_HEIGHT;

            // 专家模式跳过列表按钮
            addOpenScreenButton(parent, width, startY, "编辑专家模式跳过列表",
                    clickData -> openExpertModeSkipList());
        }

        // ==================== 组件创建辅助方法 ====================

        private void addSectionTitle(WidgetGroup parent, int width, int y, String title) {
            TextTexture titleTexture = new TextTexture("§e" + title, COLOR_TEXT_HIGHLIGHT);
            titleTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget titleWidget = new ImageWidget(8, y, width - 16, 14, titleTexture);
            parent.addWidget(titleWidget);

            // 分隔线
            WidgetGroup separator = new WidgetGroup();
            separator.setSelfPosition(8, y + 16);
            separator.setSize(width - 16, 1);
            separator.setBackground(new ColorRectTexture(0xFF4A5A6A));
            parent.addWidget(separator);
        }

        private void addBooleanOption(WidgetGroup parent, int width, int y, String key,
                                       String label, String tooltip,
                                       boolean initialValue, Consumer<Boolean> onChange) {
            // 标签
            TextTexture labelTexture = new TextTexture(label, COLOR_TEXT_NORMAL);
            labelTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget labelWidget = new ImageWidget(8, y + 8, width - 80, 14, labelTexture);
            labelWidget.setHoverTooltips(tooltip);
            parent.addWidget(labelWidget);

            // 开关按钮 -: 使用数组包装实现状态管理
            ButtonWidget switchButton = new ButtonWidget();
            switchButton.setSelfPosition(width - 56, y + 4);
            switchButton.setSize(48, 20);

            // 开关状态容器类
            class SwitchState {
                boolean value = initialValue;
                void updateTexture() {
                    int bgColor = value ? COLOR_SWITCH_ON : COLOR_SWITCH_OFF;
                    int borderColor = value ? COLOR_TEXT_HIGHLIGHT : COLOR_TEXT_GRAY;
                    String text = value ? "ON" : "OFF";
                    switchButton.setButtonTexture(new GuiTextureGroup(
                            new ColorRectTexture(bgColor).setRadius(10),
                            new ColorBorderTexture(1, borderColor).setRadius(10)
                    ), new TextTexture(text, COLOR_TEXT_TITLE).setType(TextTexture.TextType.NORMAL));
                }
            }
            final SwitchState state = new SwitchState();
            state.updateTexture();

            switchButton.setOnPressCallback(clickData -> {
                state.value = !state.value;
                onChange.accept(state.value);
                state.updateTexture();
            });

            parent.addWidget(switchButton);
        }

        private void addIntOption(WidgetGroup parent, int width, int y, String key,
                                   String label, String tooltip,
                                   int initialValue, int min, int max,
                                   Consumer<Integer> onChange) {
            // 标签
            TextTexture labelTexture = new TextTexture(label, COLOR_TEXT_NORMAL);
            labelTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget labelWidget = new ImageWidget(8, y + 8, width - 100, 14, labelTexture);
            labelWidget.setHoverTooltips(tooltip);
            parent.addWidget(labelWidget);

            // 输入框容器 -: 包含下划线样式
            WidgetGroup inputContainer = new WidgetGroup();
            inputContainer.setSelfPosition(width - 80, y + 4);
            inputContainer.setSize(64, 22);

            // 输入框 - 透明背景无边框
            TextFieldWidget textField = new TextFieldWidget();
            textField.setSelfPosition(0, 0);
            textField.setSize(64, 20);
            textField.setCurrentString(String.valueOf(initialValue));
            textField.setTextColor(COLOR_TEXT_NORMAL);
            textField.setBordered(false);
            textField.setMaxStringLength(10);
            textField.setNumbersOnly(min, max);
            // simukraft: 存储输入框引用，用于保存时读取最新值
            intInputFields.put(key, textField);
            textField.setTextResponder(newText -> {
                try {
                    int value = Integer.parseInt(newText);
                    onChange.accept(value);
                } catch (NumberFormatException ignored) {
                    // 输入无效时不更新值
                }
            });
            inputContainer.addWidget(textField);

            // 底部灰色下划线 - 圆角设计
            WidgetGroup underline = new WidgetGroup();
            underline.setSelfPosition(0, 20);
            underline.setSize(64, 2);
            underline.setBackground(new ColorRectTexture(0xFF808080).setRadius(1));
            inputContainer.addWidget(underline);

            parent.addWidget(inputContainer);
        }

        // menglan: 添加小数输入选项
        private void addDoubleOption(WidgetGroup parent, int width, int y, String key,
                                    String label, String tooltip,
                                    double initialValue, double min, double max,
                                    Consumer<Double> onChange) {
            // 标签
            TextTexture labelTexture = new TextTexture(label, COLOR_TEXT_NORMAL);
            labelTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget labelWidget = new ImageWidget(8, y + 8, width - 100, 14, labelTexture);
            labelWidget.setHoverTooltips(tooltip);
            parent.addWidget(labelWidget);

            // 输入框容器
            WidgetGroup inputContainer = new WidgetGroup();
            inputContainer.setSelfPosition(width - 80, y + 4);
            inputContainer.setSize(64, 22);

            // 输入框
            TextFieldWidget textField = new TextFieldWidget();
            textField.setSelfPosition(0, 0);
            textField.setSize(64, 20);
            textField.setCurrentString(String.format("%.1f", initialValue));
            textField.setTextColor(COLOR_TEXT_NORMAL);
            textField.setBordered(false);
            textField.setMaxStringLength(10);
            // menglan: 存储输入框引用
            doubleInputFields.put(key, textField);
            textField.setTextResponder(newText -> {
                try {
                    double value = Double.parseDouble(newText);
                    if (value >= min && value <= max) {
                        onChange.accept(value);
                    }
                } catch (NumberFormatException ignored) {
                    // 输入无效时不更新值
                }
            });
            inputContainer.addWidget(textField);

            // 底部灰色下划线
            WidgetGroup underline = new WidgetGroup();
            underline.setSelfPosition(0, 20);
            underline.setSize(64, 2);
            underline.setBackground(new ColorRectTexture(0xFF808080).setRadius(1));
            inputContainer.addWidget(underline);

            parent.addWidget(inputContainer);
        }

        private void addOpenScreenButton(WidgetGroup parent, int width, int y,
                                          String text, Consumer<ClickData> onPress) {
            ButtonWidget button = new ButtonWidget();
            button.setSelfPosition(8, y + 2);
            button.setSize(width - 16, 26);

            TextTexture btnText = new TextTexture(text, COLOR_TEXT_NORMAL);
            btnText.setType(TextTexture.TextType.NORMAL);

            button.setButtonTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_CARD_BG).setRadius(4),
                    new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(4)
            ), btnText);

            button.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_CARD_HOVER).setRadius(4),
                    new ColorBorderTexture(1, COLOR_TEXT_HIGHLIGHT).setRadius(4)
            ), btnText);

            button.setOnPressCallback(onPress);
            parent.addWidget(button);
        }

        // ==================== 子界面打开方法 ====================

        private void openPlanningBlacklist() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && screen != null) {
                mc.setScreen(BlockBlacklistScreenLDLib.createPlanningBlacklistScreen(screen, () -> {}));
            }
        }

        private void openBuilderBlacklist() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && screen != null) {
                mc.setScreen(BlockBlacklistScreenLDLib.createConstructionBlacklistScreen(screen, () -> {}));
            }
        }

        private void openBasicMaterials() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && screen != null) {
                mc.setScreen(BasicMaterialsScreenLDLib.createScreen(screen, () -> {}));
            }
        }

        private void openMaterialCategoryGroups() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && screen != null) {
                mc.setScreen(new MaterialCategoryGroupsScreenLDLib(screen,
                        ServerConfig.parseMaterialCategoryGroups(),
                        list -> {
                            ServerConfig.setMaterialCategoryGroups(list);
                            ServerConfig.SPEC.save();
                        }));
            }
        }

        private void openExpertModeSkipList() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && screen != null) {
                mc.setScreen(ExpertModeSkipListScreenLDLib.createScreen(screen, () -> {}));
            }
        }

        // ==================== 操作按钮回调 ====================

        private void saveConfig() {
            // simukraft: 先从输入框中读取最新的数字值
            for (Map.Entry<String, TextFieldWidget> entry : intInputFields.entrySet()) {
                String key = entry.getKey();
                TextFieldWidget textField = entry.getValue();
                try {
                    int value = Integer.parseInt(textField.getCurrentString());
                    // 更新configValues中的值
                    configValues.put(key, new IntConfigValue(value));
                } catch (NumberFormatException ignored) {
                    // 输入无效时保持原值
                }
            }

            // menglan: 读取小数输入框的值
            for (Map.Entry<String, TextFieldWidget> entry : doubleInputFields.entrySet()) {
                String key = entry.getKey();
                TextFieldWidget textField = entry.getValue();
                try {
                    double value = Double.parseDouble(textField.getCurrentString());
                    // 更新configValues中的值
                    configValues.put(key, new DoubleConfigValue(value));
                } catch (NumberFormatException ignored) {
                    // 输入无效时保持原值
                }
            }

            // 应用所有配置值并同步到服务器
            for (Map.Entry<String, ConfigValue<?>> entry : configValues.entrySet()) {
                String key = entry.getKey();
                ConfigValue<?> value = entry.getValue();
                applyConfigValue(key, value);
                // 同步每个修改的配置到服务器
                syncConfigToServer(key, value);
            }

            // 保存配置
            ServerConfig.SPEC.save();

            // simukraft: 保存后不关闭界面，自动重载以刷新显示
            reloadConfig();
        }

        //n 同步单个配置到服务器
        private void syncConfigToServer(String key, ConfigValue<?> value) {
            if (value instanceof BooleanConfigValue) {
                boolean boolValue = ((BooleanConfigValue) value).value;
                NetworkManager.INSTANCE.sendToServer(new SyncConfigPacket(key, boolValue));
            } else if (value instanceof IntConfigValue) {
                int intValue = ((IntConfigValue) value).value;
                NetworkManager.INSTANCE.sendToServer(new SyncConfigPacket(key, intValue));
            } else if (value instanceof DoubleConfigValue) {
                double doubleValue = ((DoubleConfigValue) value).value;
                NetworkManager.INSTANCE.sendToServer(new SyncConfigPacket(key, doubleValue));
            }
        }

        private void reloadConfig() {
            ServerConfig.clearCache();
            refreshPage();
        }

        private void resetConfig() {
            ServerConfig.resetToDefaults();
            ServerConfig.clearCache(); // menglan: 重置后清除缓存
            closeScreen();
        }

        private void closeScreen() {
            if (screen != null) {
                screen.onClose();
            }
        }

        private void applyConfigValue(String key, ConfigValue<?> value) {
            try {
                if (value instanceof BooleanConfigValue) {
                    boolean boolValue = ((BooleanConfigValue) value).value;
                    switch (key) {
                        case "enableBlacklistProtection" -> ServerConfig.ENABLE_BLACKLIST_PROTECTION.set(boolValue);
                        case "logSkippedBlocks" -> ServerConfig.LOG_BLACKLIST_SKIPPED_BLOCKS.set(boolValue);
                        case "enableDebugLog" -> ServerConfig.ENABLE_DEBUG_LOG.set(boolValue);
                        case "farmerEnableCropGrowthBoost" -> ServerConfig.FARMER_ENABLE_CROP_GROWTH_BOOST.set(boolValue);
                        case "plannerDropItemsOnRemove" -> ServerConfig.PLANNER_DROP_ITEMS_ON_REMOVE.set(boolValue);
                        case "plannerStoreItemsInChest" -> ServerConfig.PLANNER_STORE_ITEMS_IN_CHEST.set(boolValue);
                        case "plannerEnableXpGain" -> ServerConfig.PLANNER_ENABLE_XP_GAIN.set(boolValue);
                        case "builderEnableXpGain" -> ServerConfig.BUILDER_ENABLE_XP_GAIN.set(boolValue);
                        case "enableExpertMode" -> ServerConfig.ENABLE_EXPERT_MODE.set(boolValue);
                        case "enableCreativeMode" -> ServerConfig.ENABLE_CREATIVE_MODE.set(boolValue);
                        case "enableMaterialCategoryMatching" -> ServerConfig.ENABLE_MATERIAL_CATEGORY_MATCHING.set(boolValue);
                    }
                } else if (value instanceof IntConfigValue) {
                    int intValue = ((IntConfigValue) value).value;
                    switch (key) {
                        case "npcMaxLevel" -> ServerConfig.NPC_MAX_LEVEL.set(intValue);
                        case "npcSpeedBonusPerLevel" -> ServerConfig.NPC_SPEED_BONUS_PER_LEVEL.set(intValue);
                        case "npcMinSpeedTicks" -> ServerConfig.NPC_MIN_SPEED_TICKS.set(intValue);
                        case "plannerRemoveSpeedBase" -> ServerConfig.PLANNER_REMOVE_SPEED_BASE.set(intValue);
                        case "plannerReplaceSpeedBase" -> ServerConfig.PLANNER_REPLACE_SPEED_BASE.set(intValue);
                        case "plannerFillSpeedBase" -> ServerConfig.PLANNER_FILL_SPEED_BASE.set(intValue);
                        case "plannerChestSearchRange" -> ServerConfig.PLANNER_CHEST_SEARCH_RANGE.set(intValue);
                        case "plannerWarningCooldown" -> ServerConfig.PLANNER_WARNING_COOLDOWN.set(intValue);
                        case "plannerXpPerBlock" -> ServerConfig.PLANNER_XP_PER_BLOCK.set(intValue);
                        case "builderChestSearchRange" -> ServerConfig.BUILDER_CHEST_SEARCH_RANGE.set(intValue);
                        case "builderChunkLoadWaitTicks" -> ServerConfig.BUILDER_CHUNK_LOAD_WAIT_TICKS.set(intValue);
                        case "builderWarningCooldown" -> ServerConfig.BUILDER_WARNING_COOLDOWN.set(intValue);
                        case "builderXpPerBlock" -> ServerConfig.BUILDER_XP_PER_BLOCK.set(intValue);
                    }
                } else if (value instanceof DoubleConfigValue) {
                    double doubleValue = ((DoubleConfigValue) value).value;
                    switch (key) {
                        case "builderBlocksPerSecond" -> ServerConfig.BUILDER_BLOCKS_PER_SECOND.set(doubleValue);
                    }
                }
            } catch (Exception e) {
                // 忽略配置应用错误
            }
        }

        @Override
        public boolean isRemote() {
            return Minecraft.getInstance().level != null && Minecraft.getInstance().level.isClientSide;
        }

        @Override
        public void markAsDirty() {
        }

        @Override
        public boolean isInvalid() {
            return false;
        }
    }

    // ==================== 配置值包装类 ====================

    private static abstract class ConfigValue<T> {
        public final T value;

        public ConfigValue(T value) {
            this.value = value;
        }
    }

    private static class BooleanConfigValue extends ConfigValue<Boolean> {
        public BooleanConfigValue(Boolean value) {
            super(value);
        }
    }

    private static class IntConfigValue extends ConfigValue<Integer> {
        public IntConfigValue(Integer value) {
            super(value);
        }
    }

    private static class DoubleConfigValue extends ConfigValue<Double> {
        public DoubleConfigValue(Double value) {
            super(value);
        }
    }
}
