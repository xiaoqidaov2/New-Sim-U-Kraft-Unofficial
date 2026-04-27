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
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.building.IndustrialBuildingConfig;
import com.xiaoliang.simukraft.building.IndustrialBuildingManager;
import com.xiaoliang.simukraft.building.RecipeConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.SelectRecipePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * LDLib工业控制盒界面
 * 使用网格卡片选择配方，物品图标显示
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("unused")
public class IndustrialControlBoxLDLibScreen extends ModularUIGuiContainer {

    // 颜色定义
    private static final int COLOR_WINDOW_BG = 0xFF2A2A2A;
    private static final int COLOR_WINDOW_BORDER = 0xFF555555;
    private static final int COLOR_HEADER_BG = 0xFF1A3A5A;
    private static final int COLOR_CARD_BG = 0xFF3A3A3A;
    private static final int COLOR_CARD_SELECTED = 0xFF4A6A8A;
    private static final int COLOR_CARD_HOVER = 0xFF5A5A5A;
    private static final int COLOR_BUTTON_BG = 0xFF3A3A3A;
    private static final int COLOR_BUTTON_HOVER = 0xFF4A4A4A;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_TEXT_GRAY = 0xFFAAAAAA;
    private static final int COLOR_TEXT_HIGHLIGHT = 0xFF88CCFF;

    // 布局常量（4x缩放基准尺寸）- 增大尺寸使界面更大
    private static final int BASE_WINDOW_WIDTH = 380;
    private static final int BASE_WINDOW_HEIGHT = 400;
    private static final int BASE_HEADER_HEIGHT = 50;
    private static final int BASE_FOOTER_HEIGHT = 60;
    private static final int BASE_CARD_WIDTH = 100;
    private static final int BASE_CARD_HEIGHT = 110;
    private static final int BASE_CARD_SPACING = 12;

    private final BlockPos controlBoxPos;
    private final String buildingFileName;
    private final ControlBoxUIHolder holder;
    private int appliedScale;

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(@Nullable String value) {
        return nn(value);
    }

    public IndustrialControlBoxLDLibScreen(BlockPos pos, String buildingFileName) {
        super(createHolderAndUI(pos, buildingFileName), 0);
        this.controlBoxPos = pos.immutable();
        this.buildingFileName = buildingFileName;
        this.holder = ((ModularUI) this.modularUI).holder instanceof ControlBoxUIHolder
                ? (ControlBoxUIHolder) ((ModularUI) this.modularUI).holder
                : null;

        // simukraft: 应用自适应缩放
        GuiScaleManager.applyBestFitScale(BASE_WINDOW_WIDTH, BASE_WINDOW_HEIGHT);
        this.appliedScale = GuiScaleManager.calculateBestScale(BASE_WINDOW_WIDTH, BASE_WINDOW_HEIGHT);

        // 播放打开音效
        Minecraft.getInstance().getSoundManager().play(
                nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));

        // 请求同步雇佣状态
        NetworkManager.INSTANCE.sendToServer(
                new com.xiaoliang.simukraft.network.RequestWorkBlockHireStatusPacket(pos, "industrial"));

        // 请求同步配方数据
        NetworkManager.INSTANCE.sendToServer(
                new com.xiaoliang.simukraft.network.RequestRecipeSyncPacket(pos));
    }

    private static ModularUI createHolderAndUI(BlockPos pos, String buildingFileName) {
        ControlBoxUIHolder holder = new ControlBoxUIHolder(pos, buildingFileName);
        return holder.createModularUI();
    }

    /**
     * 从服务器同步配方信息后更新UI
     */
    public void onRecipeSyncReceived(String recipeId, boolean multiRecipe) {
        if (holder != null) {
            holder.updateRecipeFromServer(recipeId, multiRecipe);
        }
    }

    /**
     * 刷新按钮状态（雇佣状态变化时调用）
     */
    public void refreshButtonStates() {
        if (holder != null) {
            holder.refreshUI();
        }
    }

    // ==================== 鼠标事件 ====================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (holder != null && holder.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);

        // 在裁剪完成后绘制tooltip
        if (holder != null) {
            holder.renderTooltip(graphics, mouseX, mouseY);
        }
    }

    @Override
    public void resize(@Nonnull Minecraft minecraft, int width, int height) {
        int targetScale = GuiScaleManager.calculateBestScale(BASE_WINDOW_WIDTH, BASE_WINDOW_HEIGHT);
        if (targetScale != appliedScale) {
            minecraft.setScreen(new IndustrialControlBoxLDLibScreen(controlBoxPos, buildingFileName));
            return;
        }
        GuiScaleManager.applyBestFitScale(BASE_WINDOW_WIDTH, BASE_WINDOW_HEIGHT);
        super.resize(minecraft, width, height);
    }

    @Override
    public void onClose() {
        // simukraft: 恢复原始缩放
        GuiScaleManager.restore();
        super.onClose();
    }

    /**
     * 获取控制盒位置（menglannnn: 供外部调用）
     */
    public BlockPos getControlBoxPos() {
        return controlBoxPos;
    }

    // ==================== UI 创建 ====================

    private static ModularUI createUI(ControlBoxUIHolder holder) {
        Minecraft mc = Minecraft.getInstance();

        // simukraft: 使用GuiScaleManager计算最佳缩放
        int guiScale = GuiScaleManager.calculateBestScale(BASE_WINDOW_WIDTH, BASE_WINDOW_HEIGHT);
        float scaleFactor = guiScale / 4.0f; // 以4x为基准计算缩放因子

        // 确保缩放因子在合理范围内
        if (scaleFactor > 1.0f) scaleFactor = 1.0f;
        if (scaleFactor < 0.5f) scaleFactor = 0.5f;

        // 应用缩放后的尺寸
        int windowWidth = (int)(BASE_WINDOW_WIDTH * scaleFactor);
        int windowHeight = (int)(BASE_WINDOW_HEIGHT * scaleFactor);
        int headerHeight = (int)(BASE_HEADER_HEIGHT * scaleFactor);
        int footerHeight = (int)(BASE_FOOTER_HEIGHT * scaleFactor);
        int sideMargin = (int)(20 * scaleFactor);
        int contentWidth = windowWidth - sideMargin * 2;

        ModularUI modularUI = new ModularUI(new Size(windowWidth, windowHeight), holder, mc.player);

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

        // 标题（相对位置）
        TextTexture titleTexture = new TextTexture(holder.buildingName, COLOR_TEXT_TITLE);
        titleTexture.setType(TextTexture.TextType.NORMAL);
        int titleY = (int)(headerHeight * 0.25); // 10/40
        ImageWidget titleWidget = new ImageWidget(0, titleY, windowWidth, 16, titleTexture);
        headerGroup.addWidget(titleWidget);

        // 员工状态（相对位置）
        TextTexture statusTexture = new TextTexture(holder.getEmployeeStatusText(), COLOR_TEXT_GRAY);
        statusTexture.setType(TextTexture.TextType.NORMAL);
        int statusY = (int)(headerHeight * 0.65); // 26/40
        ImageWidget statusWidget = new ImageWidget(0, statusY, windowWidth, 12, statusTexture);
        headerGroup.addWidget(statusWidget);
        holder.setStatusWidget(statusWidget);

        // simukraft: 拆除按钮（右上角圆角矩形，拆除文字+X符号居中）
        int demolishBtnWidth = (int)(50 * scaleFactor);
        int demolishBtnHeight = (int)(30 * scaleFactor);
        int demolishBtnX = windowWidth - demolishBtnWidth - (int)(5 * scaleFactor);
        int demolishBtnY = (int)(5 * scaleFactor);

        ButtonWidget demolishButton = new ButtonWidget();
        demolishButton.setSelfPosition(demolishBtnX, demolishBtnY);
        demolishButton.setSize(demolishBtnWidth, demolishBtnHeight);

        // 两行文字：拆除 + ×
        TextTexture demolishText = new TextTexture("拆除 §c×", COLOR_TEXT_NORMAL);
        demolishText.setType(TextTexture.TextType.NORMAL);

        demolishButton.setButtonTexture(
                new GuiTextureGroup(
                        new ColorRectTexture(COLOR_BUTTON_BG).setRadius(5),
                        new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(5)
                ),
                demolishText
        );
        demolishButton.setHoverTexture(
                new GuiTextureGroup(
                        new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(5),
                        new ColorBorderTexture(1, COLOR_TEXT_HIGHLIGHT).setRadius(5)
                ),
                demolishText
        );
        demolishButton.setOnPressCallback(clickData -> holder.onDemolishClick());
        headerGroup.addWidget(demolishButton);

        // 配方网格区域（仅多配方建筑显示）
        // simukraft: 增加顶部间距，使卡片区域位置更合理
        int gridStartY = headerHeight + (int)(20 * scaleFactor);
        int gridBottomY = gridStartY;
        if (holder.isMultiRecipe()) {
            gridBottomY = createRecipeGrid(windowGroup, holder, gridStartY, contentWidth, scaleFactor);
        }

        // 等级翻倍说明区域
        int infoPanelY = gridBottomY + (int)(10 * scaleFactor);
        createLevelInfoPanel(windowGroup, infoPanelY, contentWidth, scaleFactor, windowWidth);

        // 底部按钮区域（相对位置）
        WidgetGroup footerGroup = new WidgetGroup();
        footerGroup.setSelfPosition(0, windowHeight - footerHeight);
        footerGroup.setSize(windowWidth, footerHeight);
        windowGroup.addWidget(footerGroup);

        // 分隔线（相对位置）
        WidgetGroup separator = new WidgetGroup();
        separator.setSelfPosition(sideMargin, 0);
        separator.setSize(contentWidth, 1);
        separator.setBackground(new ColorRectTexture(0xFF3A5A7A));
        footerGroup.addWidget(separator);

        // 雇佣/解雇按钮（相对位置）
        int btnWidth = (int)(windowWidth * 0.233); // 70/300
        int btnHeight = (int)(footerHeight * 0.4); // 20/50
        int btnY = (int)(footerHeight * 0.3); // 15/50

        // 雇佣按钮（左侧）
        int hireBtnX = sideMargin;
        ButtonWidget hireButton = createButton(hireBtnX, btnY, btnWidth, btnHeight,
                holder.hasHiredEmployee() ? "已雇佣" : "雇佣员工",
                clickData -> holder.onHireClick());
        hireButton.setActive(!holder.hasHiredEmployee());
        footerGroup.addWidget(hireButton);
        holder.setHireButton(hireButton);

        // 解雇按钮（中间偏左）
        int fireBtnX = hireBtnX + btnWidth + (int)(windowWidth * 0.067); // +20/300
        ButtonWidget fireButton = createButton(fireBtnX, btnY, btnWidth, btnHeight,
                "解雇员工",
                clickData -> holder.onFireClick());
        fireButton.setActive(holder.hasHiredEmployee());
        footerGroup.addWidget(fireButton);
        holder.setFireButton(fireButton);

        // 确定/刷新按钮（右侧）
        int confirmBtnX = windowWidth - sideMargin - btnWidth;
        ButtonWidget confirmButton = createButton(confirmBtnX, btnY, btnWidth, btnHeight,
                "确定",
                clickData -> holder.onConfirmClick());
        footerGroup.addWidget(confirmButton);

        // 初始化
        modularUI.widget(rootGroup);
        modularUI.initWidgets();
        holder.init();

        return modularUI;
    }

    /**
     * 创建配方网格（带水平滚动）
     * @param startY 起始Y坐标
     * @param contentWidth 内容区域宽度
     * @param scaleFactor 缩放因子
     * @return 返回网格区域底部的Y坐标
     */
    private static int createRecipeGrid(WidgetGroup parent, ControlBoxUIHolder holder, int startY, int contentWidth, float scaleFactor) {
        List<RecipeConfig> recipes = holder.getRecipes();
        if (recipes.isEmpty()) return startY;

        // 应用缩放的尺寸计算
        int titleHeight = (int)(12 * scaleFactor);
        int padding = (int)(5 * scaleFactor);
        int cardHeight = (int)(BASE_CARD_HEIGHT * scaleFactor);
        int cardWidth = (int)(BASE_CARD_WIDTH * scaleFactor);
        int cardSpacing = (int)(BASE_CARD_SPACING * scaleFactor);
        // simukraft: 计算水平边距，与createUI保持一致
        int sideMargin = (int)(20 * scaleFactor);

        int gridAreaHeight = titleHeight + cardHeight + padding * 2;

        // 创建配方网格组（带裁剪）
        WidgetGroup gridGroup = new WidgetGroup() {
            @Override
            public void drawInBackground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                // 启用裁剪 - 裁剪整个配方区域
                graphics.enableScissor(getPosition().x, getPosition().y,
                        getPosition().x + getSize().width, getPosition().y + getSize().height);
                super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
                graphics.disableScissor();
            }
        };
        // simukraft: 使用sideMargin计算水平居中，与内容区域对齐
        gridGroup.setSelfPosition(sideMargin, startY);
        gridGroup.setSize(contentWidth, gridAreaHeight);
        parent.addWidget(gridGroup);
        // 标题
        TextTexture gridTitleTexture = new TextTexture("选择配方:", COLOR_TEXT_HIGHLIGHT);
        gridTitleTexture.setType(TextTexture.TextType.LEFT);
        ImageWidget gridTitle = new ImageWidget(0, 0, (int)(100 * scaleFactor), titleHeight, gridTitleTexture);
        gridGroup.addWidget(gridTitle);

        // 计算是否需要滚动
        int visibleWidth = contentWidth - padding * 2;
        int totalCardsWidth = recipes.size() * (cardWidth + cardSpacing) - cardSpacing;
        boolean needScroll = totalCardsWidth > visibleWidth;

        // 卡片区域高度
        int cardsAreaHeight = cardHeight + padding;

        // 创建卡片容器（可滚动区域）
        WidgetGroup cardsContainer = new WidgetGroup();
        cardsContainer.setSelfPosition(0, titleHeight + padding);
        cardsContainer.setSize(visibleWidth, cardsAreaHeight);
        gridGroup.addWidget(cardsContainer);
        holder.setCardsContainer(cardsContainer);

        // 清空之前的tooltip
        holder.clearTooltips();

        // 创建卡片
        List<RecipeCardWidget> recipeCards = new ArrayList<>();
        int startX = padding / 2;

        for (int i = 0; i < recipes.size(); i++) {
            RecipeConfig recipe = recipes.get(i);
            int x = startX + i * (cardWidth + cardSpacing);
            int y = 0;

            RecipeCardWidget card = new RecipeCardWidget(x, y, cardWidth, cardHeight, recipe, holder);
            cardsContainer.addWidget(card);
            recipeCards.add(card);
        }

        holder.setRecipeCards(recipeCards);

        // simukraft: 存储实际卡片尺寸和间距，用于滚动位置计算
        holder.setActualCardDimensions(cardWidth, cardHeight, cardSpacing);

        // 设置最大滚动值（不显示滚动条，只保留鼠标滚轮滚动）
        if (needScroll) {
            holder.setMaxScroll(totalCardsWidth - visibleWidth);
        } else {
            holder.setMaxScroll(0);
        }

        return startY + gridAreaHeight;
    }

    /**
     * 创建等级翻倍说明面板
     * @param y 起始Y坐标
     * @param contentWidth 内容区域宽度
     * @param scaleFactor 缩放因子
     * @param windowWidth 窗口宽度
     */
    private static void createLevelInfoPanel(WidgetGroup parent, int y, int contentWidth, float scaleFactor, int windowWidth) {
        // 应用缩放的尺寸计算
        int panelHeight = (int)(70 * scaleFactor);
        int padding = (int)(8 * scaleFactor);
        int lineHeight = (int)(11 * scaleFactor);

        // 创建说明面板组 - 使用实际窗口宽度计算居中位置
        WidgetGroup infoGroup = new WidgetGroup();
        infoGroup.setSelfPosition((windowWidth - contentWidth) / 2, y);
        infoGroup.setSize(contentWidth, panelHeight);
        infoGroup.setBackground(new GuiTextureGroup(
                new ColorRectTexture(0xFF2A3A4A).setRadius(5),
                new ColorBorderTexture(1, 0xFF4A5A6A).setRadius(5)
        ));
        parent.addWidget(infoGroup);

        // 标题
        TextTexture titleTexture = new TextTexture("§e✦ 等级翻倍机制", COLOR_TEXT_HIGHLIGHT);
        titleTexture.setType(TextTexture.TextType.LEFT);
        ImageWidget titleWidget = new ImageWidget(padding, (int)(6 * scaleFactor), contentWidth - padding * 2, lineHeight, titleTexture);
        infoGroup.addWidget(titleWidget);

        // 说明文本行1
        TextTexture line1Texture = new TextTexture("NPC等级越高，生产效率越高:", 0xFFCCCCCC);
        line1Texture.setType(TextTexture.TextType.LEFT);
        ImageWidget line1Widget = new ImageWidget(padding, (int)(20 * scaleFactor), contentWidth - padding * 2, lineHeight, line1Texture);
        infoGroup.addWidget(line1Widget);

        // 说明文本行2 - 分两行显示，避免重叠
        TextTexture line2Texture = new TextTexture("§7Lv.1§f:1倍 §7Lv.2§f:1.1倍 §7Lv.3§f:1.25倍", 0xFFAAAAAA);
        line2Texture.setType(TextTexture.TextType.LEFT);
        ImageWidget line2Widget = new ImageWidget(padding, (int)(34 * scaleFactor), contentWidth - padding * 2, lineHeight, line2Texture);
        infoGroup.addWidget(line2Widget);

        // 说明文本行3
        TextTexture line3Texture = new TextTexture("§7Lv.4§f:1.4倍 §7Lv.5§f:1.6倍 §7Lv.6§f:1.8倍 §7Lv.7+§f:2倍+", 0xFFAAAAAA);
        line3Texture.setType(TextTexture.TextType.LEFT);
        ImageWidget line3Widget = new ImageWidget(padding, (int)(48 * scaleFactor), contentWidth - padding * 2, lineHeight, line3Texture);
        infoGroup.addWidget(line3Widget);
    }

    private static ButtonWidget createButton(int x, int y, int width, int height, String text,
                                              java.util.function.Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);

        TextTexture buttonText = new TextTexture(text, COLOR_TEXT_NORMAL);
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
                        new ColorBorderTexture(1, COLOR_TEXT_HIGHLIGHT).setRadius(3)
                ),
                buttonText
        );
        button.setOnPressCallback(onPress);

        return button;
    }

    // ==================== 配方卡片组件 ====================

    private static class RecipeCardWidget extends WidgetGroup {
        private final RecipeConfig recipe;
        private final ControlBoxUIHolder holder;
        private boolean isSelected = false;
        private final int cardWidth;
        private final int cardHeight;

        public RecipeCardWidget(int x, int y, int width, int height, RecipeConfig recipe, ControlBoxUIHolder holder) {
            this.cardWidth = width;
            this.cardHeight = height;
            this.recipe = recipe;
            this.holder = holder;

            this.setSelfPosition(x, y);
            this.setSize(width, height);

            rebuildWidget();
        }

        private void rebuildWidget() {
            this.clearAllWidgets();

            // 背景
            int bgColor = isSelected ? COLOR_CARD_SELECTED : COLOR_CARD_BG;
            int borderColor = isSelected ? COLOR_TEXT_HIGHLIGHT : COLOR_WINDOW_BORDER;

            this.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(bgColor).setRadius(5),
                    new ColorBorderTexture(1, borderColor).setRadius(5)
            ));

            // 设置悬停效果
            this.setHoverTexture(new GuiTextureGroup(
                    new ColorRectTexture(COLOR_CARD_HOVER).setRadius(5),
                    new ColorBorderTexture(1, COLOR_TEXT_HIGHLIGHT).setRadius(5)
            ));

            // 相对尺寸计算
            int iconSize = (int)(cardHeight * 0.356); // 32/90
            int nameY = (int)(cardHeight * 0.444); // 40/90
            int nameHeight = (int)(cardHeight * 0.133); // 12/90
            int materialY = (int)(cardHeight * 0.6); // 54/90
            int materialIconSize = (int)(cardHeight * 0.156); // 14/90
            int productY = (int)(cardHeight * 0.822); // 74/90
            int productHeight = (int)(cardHeight * 0.111); // 10/90

            // 获取配方的主要产物物品
            Item displayItem = getDisplayItem();

            // 物品图标（居中）
            if (displayItem != null) {
                int iconX = (cardWidth - iconSize) / 2;
                int iconY = (int)(cardHeight * 0.067); // 6/90
                ItemIconWidget iconWidget = new ItemIconWidget(iconX, iconY, iconSize, iconSize, displayItem);
                this.addWidget(iconWidget);
            }

            // 配方名称
            String recipeName = recipe.getRecipeName();
            if (recipeName.length() > 6) {
                recipeName = recipeName.substring(0, 5) + "…";
            }
            TextTexture nameTexture = new TextTexture(recipeName, COLOR_TEXT_NORMAL);
            nameTexture.setType(TextTexture.TextType.NORMAL);
            ImageWidget nameWidget = new ImageWidget(0, nameY, cardWidth, nameHeight, nameTexture);
            this.addWidget(nameWidget);

            // 原料需求显示（小图标+数量在右下角，整体居中）
            List<IndustrialBuildingConfig.MaterialRequirement> materials = recipe.getMaterials();
            if (!materials.isEmpty()) {
                int spacing = 2;
                int countWidth = 8;
                int plusSize = (int)(cardHeight * 0.133); // 12/90

                // 计算总宽度以居中
                int displayCount = Math.min(materials.size(), 2);
                int totalWidth = displayCount * materialIconSize + (displayCount - 1) * (countWidth + spacing);
                if (materials.size() > 2) {
                    totalWidth += countWidth + spacing + plusSize; // +号宽度
                }
                int startX = (cardWidth - totalWidth) / 2;

                // 显示原料
                for (int i = 0; i < displayCount; i++) {
                    IndustrialBuildingConfig.MaterialRequirement material = materials.get(i);
                    Item materialItem = parseItemId(material.getItemId());

                    if (materialItem != null) {
                        int x = startX + i * (materialIconSize + countWidth + spacing);

                        // 原料图标（小）
                        ItemIconWidget materialIcon = new ItemIconWidget(x, materialY, materialIconSize, materialIconSize, materialItem);
                        this.addWidget(materialIcon);

                        // 数量文本（放在图标右下角）
                        TextTexture countTexture = new TextTexture(String.valueOf(material.getCount()), 0xFFFFFFFF);
                        countTexture.setType(TextTexture.TextType.LEFT);
                        int countX = x + materialIconSize - (int)(materialIconSize * 0.429); // -6/14
                        int countY = materialY + materialIconSize - (int)(materialIconSize * 0.571); // -8/14
                        ImageWidget countWidget = new ImageWidget(countX, countY, countWidth, (int)(cardHeight * 0.089), countTexture);
                        this.addWidget(countWidget);
                    }
                }

                // 如果有更多原料，显示+号（带圆形背景）
                if (materials.size() > 2) {
                    StringBuilder tooltip = new StringBuilder("其他原料:\n");
                    for (int i = 2; i < materials.size(); i++) {
                        IndustrialBuildingConfig.MaterialRequirement mat = materials.get(i);
                        Item matItem = parseItemId(mat.getItemId());
                        String itemName = matItem != null ? matItem.getDescription().getString() : mat.getItemId();
                        tooltip.append(itemName).append(" x").append(mat.getCount());
                        if (i < materials.size() - 1) tooltip.append("\n");
                    }

                    // +号位置（居中布局）
                    int plusX = startX + displayCount * (materialIconSize + countWidth + spacing);
                    int plusY = materialY + (int)(cardHeight * 0.022); // +2/90

                    // 创建按钮组
                    WidgetGroup plusButton = new WidgetGroup() {
                        private boolean isHovered = false;

                        @Override
                        public void drawInBackground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);

                            int x = getPosition().x;
                            int y = getPosition().y;
                            int size = getSize().width;

                            // 检测悬停状态
                            isHovered = mouseX >= x && mouseX <= x + size && mouseY >= y && mouseY <= y + size;

                            // 绘制+号文本
                            graphics.drawCenteredString(nn(Minecraft.getInstance().font), "+",
                                    x + size / 2, y + size / 2 - 4, 0xFFFFFFFF);

                            // 悬停时绘制tooltip
                            if (isHovered) {
                                holder.setActiveTooltip(mouseX, mouseY, tooltip.toString());
                            }
                        }
                    };
                    plusButton.setSelfPosition(plusX, plusY);
                    plusButton.setSize(plusSize, plusSize);

                    // 使用LDLib的圆角纹理
                    int plusBorderColor = 0xFF777777;
                    int plusBgColor = 0xFF444444;
                    plusButton.setBackground(new GuiTextureGroup(
                            new ColorRectTexture(plusBgColor).setRadius(3),
                            new ColorBorderTexture(1, plusBorderColor).setRadius(3)
                    ));
                    plusButton.setHoverTexture(new GuiTextureGroup(
                            new ColorRectTexture(0xFF555555).setRadius(3),
                            new ColorBorderTexture(1, 0xFF999999).setRadius(3)
                    ));

                    this.addWidget(plusButton);
                }
            } else {
                // 无原料需求显示"无需材料"
                TextTexture noMaterialTexture = new TextTexture("无需材料", 0xFF888888);
                noMaterialTexture.setType(TextTexture.TextType.NORMAL);
                ImageWidget noMaterialWidget = new ImageWidget(0, materialY, cardWidth, productHeight, noMaterialTexture);
                this.addWidget(noMaterialWidget);
            }

            // 产物预览
            List<IndustrialBuildingConfig.ProductOutput> products = recipe.getProducts();
            if (!products.isEmpty()) {
                int totalAmount = 0;
                for (IndustrialBuildingConfig.ProductOutput product : products) {
                    totalAmount += product.getBaseAmount();
                }

                TextTexture productTexture = new TextTexture("产出: " + totalAmount, COLOR_TEXT_GRAY);
                productTexture.setType(TextTexture.TextType.NORMAL);
                ImageWidget productWidget = new ImageWidget(0, productY, cardWidth, productHeight, productTexture);
                this.addWidget(productWidget);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isMouseOverElement(mouseX, mouseY)) {
                holder.selectRecipe(recipe.getRecipeId());
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        public void setSelected(boolean selected) {
            if (this.isSelected != selected) {
                this.isSelected = selected;
                rebuildWidget();
            }
        }

        private Item getDisplayItem() {
            // 优先使用第一个产物作为卡片图标
            List<IndustrialBuildingConfig.ProductOutput> products = recipe.getProducts();
            if (!products.isEmpty()) {
                Item productItem = parseItemId(products.get(0).getItemId());
                if (productItem != null) return productItem;
            }

            // 如果没有产物，使用手持物品
            String heldItemId = recipe.getHeldItem();
            if (heldItemId != null && !heldItemId.isEmpty()) {
                Item item = parseItemId(heldItemId);
                if (item != null) return item;
            }

            return null;
        }

        private Item parseItemId(String itemId) {
            try {
                if (itemId.contains(":")) {
                    return ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemId));
                } else {
                    return ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("minecraft", itemId));
                }
            } catch (Exception e) {
                return null;
            }
        }

        public String getRecipeId() {
            return recipe.getRecipeId();
        }
    }

    // ==================== 物品图标组件 ====================

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

            // 渲染物品图标（居中）
            if (item != null) {
                graphics.renderItem(new ItemStack(item), x + (getSize().width - 16) / 2, y + (getSize().height - 16) / 2);
            }
        }
    }
    // ==================== UI Holder 类 ====================

    public static class ControlBoxUIHolder implements IUIHolder {
        private final BlockPos controlBoxPos;
        private final String buildingFileName;
        private String buildingName;
        private IndustrialBuildingConfig buildingConfig;

        // 配方数据
        private List<RecipeConfig> recipes = new ArrayList<>();
        private String currentRecipeId = null;
        private boolean isMultiRecipe = false;

        // Widget引用
        private ImageWidget statusWidget;
        private ButtonWidget hireButton;
        private ButtonWidget fireButton;
        private WidgetGroup cardsContainer;
        private List<RecipeCardWidget> recipeCards = new ArrayList<>();

        // 滚动状态
        private double scrollOffset = 0;
        private int maxScroll = 0;
        private boolean needScroll = false;

        // 卡片尺寸（根据缩放因子计算后的实际尺寸）
        private int actualCardWidth = BASE_CARD_WIDTH;
        private int actualCardHeight = BASE_CARD_HEIGHT;
        private int actualCardSpacing = BASE_CARD_SPACING;

        // Tooltip信息
        private List<TooltipInfo> tooltips = new ArrayList<>();

        // 当前激活的tooltip（悬停显示）
        private String activeTooltip = null;
        private int activeTooltipX = 0;
        private int activeTooltipY = 0;

        public ControlBoxUIHolder(BlockPos pos, String buildingFileName) {
            this.controlBoxPos = pos;
            this.buildingFileName = buildingFileName;

            // 加载建筑配置
            loadBuildingConfig();
        }

        private void loadBuildingConfig() {
            if (buildingFileName == null || buildingFileName.isEmpty() || "unknown".equals(buildingFileName)) {
                this.buildingName = "工业建筑";
                return;
            }

            this.buildingConfig = IndustrialBuildingManager.getConfig(buildingFileName);
            if (buildingConfig != null) {
                this.buildingName = buildingConfig.getBuildingName();
                this.recipes = buildingConfig.getRecipes();
                this.isMultiRecipe = buildingConfig.isMultiRecipe();

                // 不默认选择配方，等待服务器同步
                // 如果服务器没有保存配方，会在收到同步包后选择第一个
            } else {
                this.buildingName = buildingFileName;
            }
        }

        public ModularUI createModularUI() {
            return IndustrialControlBoxLDLibScreen.createUI(this);
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
            // 从客户端数据更新状态
            refreshUI();

            // 更新配方卡片选中状态
            updateRecipeCardSelection();
        }

        // ==================== 配方选择 ====================

        public void selectRecipe(String recipeId) {
            if (recipeId == null || recipeId.equals(currentRecipeId)) return;

            this.currentRecipeId = recipeId;

            // 更新UI选中状态
            updateRecipeCardSelection();

            // 发送选择到服务器
            NetworkManager.INSTANCE.sendToServer(
                    new SelectRecipePacket(controlBoxPos, recipeId, buildingFileName));
        }

        private void updateRecipeCardSelection() {
            for (RecipeCardWidget card : recipeCards) {
                card.setSelected(card.getRecipeId().equals(currentRecipeId));
            }
        }

        // ==================== 服务器同步 ====================

        public void updateRecipeFromServer(String recipeId, boolean multiRecipe) {
            this.isMultiRecipe = multiRecipe;

            if (recipeId != null && !recipeId.isEmpty()) {
                // 服务器有保存的配方
                this.currentRecipeId = recipeId;
            } else if (isMultiRecipe && !recipes.isEmpty()) {
                // 服务器没有保存配方，默认选择第一个
                this.currentRecipeId = recipes.get(0).getRecipeId();

                // simukraft: 将默认选择同步到服务器，否则建筑不会工作
                NetworkManager.INSTANCE.sendToServer(
                        new SelectRecipePacket(controlBoxPos, currentRecipeId, buildingFileName));
            }

            updateRecipeCardSelection();
        }

        // ==================== 雇佣/解雇 ====================

        public void onHireClick() {
            Minecraft mc = Minecraft.getInstance();

            // 切换到雇佣界面
            String jobType = getJobType();
            mc.setScreen(new HireIndustrialScreen(controlBoxPos, jobType, buildingFileName));
        }

        public void onFireClick() {
            if (!hasHiredEmployee()) return;

            CustomEntity npc = IndustrialClientData.getHiredEmployee(controlBoxPos);
            if (npc != null) {
                NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByNpc(npc.getUUID()));
                IndustrialClientData.clearHiredEmployee(controlBoxPos);
                refreshUI();
            }
        }

        public void onConfirmClick() {
            // 播放确认音效
            Minecraft.getInstance().getSoundManager().play(
                    nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));

            // simukraft: 恢复原始缩放（setScreen(null)不会触发onClose）
            GuiScaleManager.restore();

            // 关闭界面
            Minecraft.getInstance().setScreen(null);
        }

        public void onDemolishClick() {
            // simukraft: 发送拆除请求到服务器
            com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                new com.xiaoliang.simukraft.network.DemolishBuildingPacket(controlBoxPos)
            );

            // simukraft: 恢复原始缩放
            GuiScaleManager.restore();

            // 关闭界面
            Minecraft.getInstance().setScreen(null);
        }

        // ==================== 状态更新 ====================

        public void refreshUI() {
            // 更新员工状态文本
            if (statusWidget != null) {
                TextTexture texture = new TextTexture(getEmployeeStatusText(), COLOR_TEXT_GRAY);
                texture.setType(TextTexture.TextType.NORMAL);
                statusWidget.setImage(texture);
            }

            // 更新按钮状态
            boolean hasEmployee = hasHiredEmployee();

            if (hireButton != null) {
                String buttonText = hasEmployee ? "已雇佣" : "雇佣员工";
                TextTexture texture = new TextTexture(buttonText, COLOR_TEXT_NORMAL);
                texture.setType(TextTexture.TextType.NORMAL);

                int bgColor = hasEmployee ? 0xFF2A2A2A : COLOR_BUTTON_BG;

                // 更新按钮纹理和悬停纹理
                hireButton.setButtonTexture(
                        new GuiTextureGroup(
                                new ColorRectTexture(bgColor).setRadius(3),
                                new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(3)
                        ),
                        texture
                );
                hireButton.setHoverTexture(
                        new GuiTextureGroup(
                                new ColorRectTexture(COLOR_BUTTON_HOVER).setRadius(3),
                                new ColorBorderTexture(1, COLOR_TEXT_HIGHLIGHT).setRadius(3)
                        ),
                        texture
                );
                hireButton.setActive(!hasEmployee);
            }

            if (fireButton != null) {
                fireButton.setActive(hasEmployee);
            }
        }

        // ==================== Getter方法 ====================

        public boolean hasHiredEmployee() {
            return IndustrialClientData.hasHiredEmployee(controlBoxPos);
        }

        public String getEmployeeStatusText() {
            if (hasHiredEmployee()) {
                CustomEntity npc = IndustrialClientData.getHiredEmployee(controlBoxPos);
                if (npc != null) {
                    return "员工: " + npc.getFullName();
                }
                return "员工: 已雇佣";
            }
            return "员工: 未雇佣";
        }

        public String getJobType() {
            String jobType = IndustrialClientData.getJobType(controlBoxPos);
            if (jobType == null && buildingConfig != null) {
                jobType = buildingConfig.getJobType();
            }
            return jobType != null ? jobType : "worker";
        }

        public boolean isMultiRecipe() {
            return isMultiRecipe;
        }

        public List<RecipeConfig> getRecipes() {
            return recipes;
        }

        // ==================== Setter方法 ====================

        public void setStatusWidget(ImageWidget widget) {
            this.statusWidget = widget;
        }

        public void setHireButton(ButtonWidget button) {
            this.hireButton = button;
        }

        public void setFireButton(ButtonWidget button) {
            this.fireButton = button;
        }

        public void setCardsContainer(WidgetGroup container) {
            this.cardsContainer = container;
        }

        public void setRecipeCards(List<RecipeCardWidget> cards) {
            this.recipeCards = cards;
        }

        // simukraft: 存储实际卡片尺寸和间距
        public void setActualCardDimensions(int width, int height, int spacing) {
            this.actualCardWidth = width;
            this.actualCardHeight = height;
            this.actualCardSpacing = spacing;
        }

        // ==================== 滚动控制 ====================

        public void setScrollOffset(double offset) {
            this.scrollOffset = Math.max(0, Math.min(maxScroll, offset));
            updateCardPositions();
        }

        public void setMaxScroll(int maxScroll) {
            this.maxScroll = maxScroll;
            this.needScroll = maxScroll > 0;
        }

        private void updateCardPositions() {
            if (cardsContainer == null) return;

            int startX = 5 - (int) scrollOffset;
            int startY = 0;

            // simukraft: 使用实际卡片尺寸和间距（考虑缩放因子）
            for (int i = 0; i < recipeCards.size(); i++) {
                RecipeCardWidget card = recipeCards.get(i);
                int x = startX + i * (actualCardWidth + actualCardSpacing);
                card.setSelfPosition(x, startY);
            }
        }

        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            if (!needScroll || cardsContainer == null) return false;

            // 检查鼠标是否在卡片区域内
            int containerX = cardsContainer.getPosition().x;
            int containerY = cardsContainer.getPosition().y;
            int containerWidth = cardsContainer.getSize().width;
            int containerHeight = cardsContainer.getSize().height;

            if (mouseX >= containerX && mouseX <= containerX + containerWidth &&
                mouseY >= containerY && mouseY <= containerY + containerHeight) {
                // 水平滚动
                double scrollSpeed = 20;
                setScrollOffset(scrollOffset - delta * scrollSpeed);
                return true;
            }
            return false;
        }

        // ==================== Tooltip管理 ====================

        public void registerTooltip(int x, int y, int width, int height, String text) {
            tooltips.add(new TooltipInfo(x, y, width, height, text));
        }

        public void clearTooltips() {
            tooltips.clear();
        }

        public void setActiveTooltip(int x, int y, String text) {
            this.activeTooltip = text;
            this.activeTooltipX = x;
            this.activeTooltipY = y;
        }

        public void clearActiveTooltip() {
            this.activeTooltip = null;
        }

        public void renderTooltip(@Nonnull GuiGraphics graphics, int mouseX, int mouseY) {
            // 优先显示悬停tooltip（来自+号按钮）
            if (activeTooltip != null) {
                drawTooltip(graphics, activeTooltipX, activeTooltipY, activeTooltip);
                activeTooltip = null; // 显示后清除，需要下一帧重新设置
                return;
            }

            // 显示注册的tooltip
            for (TooltipInfo tooltip : tooltips) {
                if (tooltip.isMouseOver(mouseX, mouseY)) {
                    drawTooltip(graphics, mouseX, mouseY, tooltip.text);
                    break;
                }
            }
        }

        private void drawTooltip(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, String text) {
            Minecraft mc = Minecraft.getInstance();
            List<Component> lines = new ArrayList<>();

            String[] parts = text.split("\\n");
            for (String part : parts) {
                lines.add(Component.literal(safeString(part)));
            }

            // 计算tooltip尺寸
            int tooltipWidth = 0;
            int tooltipHeight = lines.size() * 12 + 6;

            for (Component line : lines) {
                int lineWidth = nn(mc.font).width(nn(line));
                if (lineWidth > tooltipWidth) tooltipWidth = lineWidth;
            }
            tooltipWidth += 8;

            int tooltipX = mouseX + 8;
            int tooltipY = mouseY - tooltipHeight - 4;

            // 确保tooltip不超出屏幕
            if (tooltipX + tooltipWidth > mc.getWindow().getGuiScaledWidth()) {
                tooltipX = mouseX - tooltipWidth - 8;
            }
            if (tooltipY < 0) {
                tooltipY = mouseY + 12;
            }

            // 绘制背景
            graphics.fill(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight, 0xCC000000);
            graphics.renderOutline(tooltipX, tooltipY, tooltipWidth, tooltipHeight, 0xFF555555);

            // 绘制文本
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawString(nn(mc.font), nn(lines.get(i)), tooltipX + 4, tooltipY + 4 + i * 12, 0xFFFFFFFF);
            }
        }
    }

    // ==================== Tooltip信息类 ====================

    private static class TooltipInfo {
        public final int x, y, width, height;
        public final String text;

        public TooltipInfo(int x, int y, int width, int height, String text) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.text = text;
        }

        public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }
}
