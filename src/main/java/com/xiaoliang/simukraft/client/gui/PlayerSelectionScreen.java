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
import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.SendOfficialInvitationPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 玩家选择界面
 * 使用LDLib框架实现
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class PlayerSelectionScreen extends ModularUIGuiContainer {
    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    // ==================== 布局常量 ====================

    // 窗口尺寸
    private static final int WINDOW_WIDTH = 400;
    private static final int WINDOW_HEIGHT = 280;

    // 列表区域
    private static final int LIST_WIDTH = 360;
    private static final int LIST_HEIGHT = 160;
    private static final int SLOT_HEIGHT = 50;
    private static final int SLOT_SPACING = 4;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int PLAYERS_PER_PAGE = 3;

    // 搜索框
    private static final int SEARCH_WIDTH = 200;
    private static final int SEARCH_HEIGHT = 18;

    // 按钮尺寸
    private static final int BUTTON_WIDTH = 60;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_SPACING = 8;

    // 颜色定义
    private static final int COLOR_WINDOW_BG = 0xFF2A2A2A;
    private static final int COLOR_WINDOW_BORDER = 0xFF555555;
    private static final int COLOR_LIST_BG = 0xFF333333;
    private static final int COLOR_SLOT_BG = 0xFF3A3A3A;
    private static final int COLOR_SLOT_HOVER = 0xFF4A4A4A;
    private static final int COLOR_BORDER = 0xFF666666;
    private static final int COLOR_TEXT_NAME = 0xFFFFFFFF;
    private static final int COLOR_TEXT_HINT = 0xFFAAAAAA;
    private static final int COLOR_SCROLLBAR_BG = 0xFF222222;
    private static final int COLOR_SCROLLBAR_THUMB = 0xFF888888;
    private static final int COLOR_BUTTON_HOVER = 0xFFADD8E6;

    // 纹理路径
    @Nonnull
    private static final ResourceLocation DEFAULT_PLAYER_SKIN = nn(ResourceLocation.tryParse("minecraft:textures/entity/steve.png"));

    // ==================== 成员变量 ====================

    private final BlockPos cityCorePos;
    private final CityOfficialScreen parentScreen;
    private final PlayerSelectionUIHolder holder;

    // ==================== 构造函数 ====================

    public PlayerSelectionScreen(BlockPos cityCorePos, CityOfficialScreen parentScreen) {
        super(createHolderAndUI(cityCorePos, parentScreen), 0);
        this.cityCorePos = cityCorePos;
        this.parentScreen = parentScreen;
        this.holder = ((ModularUI) this.modularUI).holder instanceof PlayerSelectionUIHolder
                ? (PlayerSelectionUIHolder) ((ModularUI) this.modularUI).holder
                : null;

        if (this.holder != null) {
            this.holder.setScreen(this);
        }

        playOpenSound();
    }

    private void playOpenSound() {
        Minecraft.getInstance().getSoundManager().play(
                nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));
    }

    // ==================== UI 创建 ====================

    private static ModularUI createHolderAndUI(BlockPos cityCorePos, CityOfficialScreen parentScreen) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int windowX = (screenWidth - WINDOW_WIDTH) / 2;
        int windowY = (screenHeight - WINDOW_HEIGHT) / 2;

        PlayerSelectionUIHolder holder = new PlayerSelectionUIHolder(cityCorePos, parentScreen);
        ModularUI modularUI = new ModularUI(new Size(screenWidth, screenHeight), holder, mc.player);

        WidgetGroup rootGroup = new WidgetGroup();
        rootGroup.setSize(screenWidth, screenHeight);
        rootGroup.setBackground(new ColorRectTexture(0xC0000000));

        // 主窗口背景（带圆角）
        WidgetGroup windowGroup = new WidgetGroup();
        windowGroup.setSelfPosition(windowX, windowY);
        windowGroup.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        windowGroup.setBackground(new GuiTextureGroup(
                new ColorRectTexture(COLOR_WINDOW_BG).setRadius(8),
                new ColorBorderTexture(1, COLOR_WINDOW_BORDER).setRadius(8)
        ));
        rootGroup.addWidget(windowGroup);

        // 标题
        TextTexture titleTexture = new TextTexture("gui.player_selection.title", 0xFFFFFFFF);
        titleTexture.setWidth(WINDOW_WIDTH);
        titleTexture.setType(TextTexture.TextType.NORMAL);
        titleTexture.setDropShadow(true);
        ImageWidget titleWidget = new ImageWidget(0, 8, WINDOW_WIDTH, 20, titleTexture);
        windowGroup.addWidget(titleWidget);

        // 搜索框
        int searchX = (WINDOW_WIDTH - SEARCH_WIDTH) / 2;
        int searchY = 28;

        WidgetGroup searchBg = new WidgetGroup();
        searchBg.setSelfPosition(searchX, searchY);
        searchBg.setSize(SEARCH_WIDTH, SEARCH_HEIGHT);
        searchBg.setBackground(new GuiTextureGroup(
                new ColorRectTexture(0xFF1A1A1A).setRadius(3),
                new ColorBorderTexture(1, COLOR_BORDER).setRadius(3)
        ));
        windowGroup.addWidget(searchBg);

        TextFieldWidget searchBox = new TextFieldWidget();
        searchBox.setSelfPosition(searchX + 4, searchY + 4);
        searchBox.setSize(SEARCH_WIDTH - 8, SEARCH_HEIGHT - 8);
        searchBox.setTextColor(0xFFFFFFFF);
        searchBox.setBackground(new ColorRectTexture(0xFF2A2A2A).setRadius(2));
        windowGroup.addWidget(searchBox);
        holder.setSearchBox(searchBox);

        // 提示文本
        TextTexture hintTexture = new TextTexture("gui.player_selection.hint", COLOR_TEXT_HINT);
        hintTexture.setWidth(WINDOW_WIDTH);
        hintTexture.setType(TextTexture.TextType.NORMAL);
        ImageWidget hintWidget = new ImageWidget(0, searchY + SEARCH_HEIGHT + 4, WINDOW_WIDTH, 12, hintTexture);
        windowGroup.addWidget(hintWidget);

        // 列表区域背景（带圆角）
        int listX = (WINDOW_WIDTH - LIST_WIDTH) / 2;
        int listY = searchY + SEARCH_HEIGHT + 20;

        WidgetGroup listBackground = new WidgetGroup();
        listBackground.setSelfPosition(listX, listY);
        listBackground.setSize(LIST_WIDTH, LIST_HEIGHT);
        listBackground.setBackground(new GuiTextureGroup(
                new ColorRectTexture(COLOR_LIST_BG).setRadius(4),
                new ColorBorderTexture(1, COLOR_BORDER).setRadius(4)
        ));
        windowGroup.addWidget(listBackground);

        // 玩家列表容器
        WidgetGroup listGroup = new WidgetGroup();
        listGroup.setSelfPosition(2, 2);
        listGroup.setSize(LIST_WIDTH - SCROLLBAR_WIDTH - 4, LIST_HEIGHT - 4);
        listBackground.addWidget(listGroup);
        holder.setListGroup(listGroup);

        // 滚动条
        WidgetGroup scrollbarGroup = new WidgetGroup();
        scrollbarGroup.setSelfPosition(LIST_WIDTH - SCROLLBAR_WIDTH - 2, 2);
        scrollbarGroup.setSize(SCROLLBAR_WIDTH, LIST_HEIGHT - 4);
        scrollbarGroup.setBackground(new ColorRectTexture(COLOR_SCROLLBAR_BG));
        listBackground.addWidget(scrollbarGroup);
        holder.setScrollbarGroup(scrollbarGroup);

        // 分页信息
        ImageWidget pageInfoLabel = new ImageWidget(0, listY + LIST_HEIGHT + 8, WINDOW_WIDTH, 12,
                new TextTexture("", 0xFFFFFFFF));
        pageInfoLabel.setVisible(false);
        windowGroup.addWidget(pageInfoLabel);
        holder.setPageInfoLabel(pageInfoLabel);

        // 按钮区域
        int buttonY = WINDOW_HEIGHT - BUTTON_HEIGHT - 12;
        int totalButtonWidth = BUTTON_WIDTH * 3 + BUTTON_SPACING * 2;
        int buttonStartX = (WINDOW_WIDTH - totalButtonWidth) / 2;

        // 上一页按钮
        ButtonWidget prevButton = createButton(buttonStartX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.pagination.previous", clickData -> holder.onPrevPage());
        windowGroup.addWidget(prevButton);
        holder.setPrevButton(prevButton);

        // 返回按钮
        ButtonWidget backButton = createButton(buttonStartX + BUTTON_WIDTH + BUTTON_SPACING, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.back", clickData -> holder.onBack());
        windowGroup.addWidget(backButton);

        // 下一页按钮
        ButtonWidget nextButton = createButton(buttonStartX + (BUTTON_WIDTH + BUTTON_SPACING) * 2, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.pagination.next", clickData -> holder.onNextPage());
        windowGroup.addWidget(nextButton);
        holder.setNextButton(nextButton);

        // 初始化
        holder.init();

        modularUI.widget(rootGroup);
        modularUI.initWidgets();
        return modularUI;
    }

    private static ButtonWidget createButton(int x, int y, int width, int height,
                                              String textKey,
                                              java.util.function.Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);

        TextTexture buttonText = new TextTexture(textKey, 0xFFFFFFFF);
        buttonText.setType(TextTexture.TextType.NORMAL);

        button.setButtonTexture(new ColorRectTexture(COLOR_SLOT_BG).setRadius(3), buttonText);
        button.setHoverTexture(new GuiTextureGroup(
                new ColorRectTexture(COLOR_SLOT_HOVER).setRadius(3),
                new ColorBorderTexture(1, COLOR_BUTTON_HOVER).setRadius(3)
        ), buttonText);
        button.setOnPressCallback(onPress);

        return button;
    }

    // ==================== UI Holder 类 ====================

    public static class PlayerSelectionUIHolder implements IUIHolder {
        private final BlockPos cityCorePos;
        private final CityOfficialScreen parentScreen;
        private PlayerSelectionScreen screen;

        // 数据
        private List<PlayerInfo> allPlayers = new ArrayList<>();
        private List<PlayerInfo> filteredPlayers = new ArrayList<>();
        private int currentPage = 0;

        // Widget 引用
        private TextFieldWidget searchBox;
        private WidgetGroup listGroup;
        private WidgetGroup scrollbarGroup;
        private ImageWidget pageInfoLabel;
        private ButtonWidget prevButton;
        private ButtonWidget nextButton;

        // 滚动
        private double scrollAmount = 0;

        // 玩家信息记录
        public record PlayerInfo(String playerName) {}

        public PlayerSelectionUIHolder(BlockPos cityCorePos, CityOfficialScreen parentScreen) {
            this.cityCorePos = cityCorePos;
            this.parentScreen = parentScreen;
        }

        public void setScreen(PlayerSelectionScreen screen) {
            this.screen = screen;
        }

        public void setSearchBox(TextFieldWidget searchBox) {
            this.searchBox = searchBox;
        }

        public void setListGroup(WidgetGroup listGroup) {
            this.listGroup = listGroup;
        }

        public void setScrollbarGroup(WidgetGroup scrollbarGroup) {
            this.scrollbarGroup = scrollbarGroup;
        }

        public void setPageInfoLabel(ImageWidget pageInfoLabel) {
            this.pageInfoLabel = pageInfoLabel;
        }

        public void setPrevButton(ButtonWidget prevButton) {
            this.prevButton = prevButton;
        }

        public void setNextButton(ButtonWidget nextButton) {
            this.nextButton = nextButton;
        }

        // ==================== IUIHolder 接口实现 ====================

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
            loadOnlinePlayers();
            if (searchBox != null) {
                searchBox.setTextResponder(this::onSearchChanged);
            }
            rebuildPlayerList();
            updatePaginationButtons();
        }

        private void loadOnlinePlayers() {
            allPlayers.clear();
            Minecraft minecraft = Minecraft.getInstance();
            Level level = minecraft.level;
            LocalPlayer self = minecraft.player;
            if (level != null && self != null) {
                level.players().forEach(player -> {
                    if (!player.getName().getString().equals(self.getName().getString())) {
                        allPlayers.add(new PlayerInfo(player.getName().getString()));
                    }
                });
            }
            filteredPlayers = new ArrayList<>(allPlayers);
        }

        // ==================== 搜索 ====================

        private void onSearchChanged(String query) {
            filteredPlayers.clear();
            if (query == null || query.isEmpty()) {
                filteredPlayers.addAll(allPlayers);
            } else {
                String lowerQuery = query.toLowerCase();
                for (PlayerInfo player : allPlayers) {
                    if (player.playerName().toLowerCase().contains(lowerQuery)) {
                        filteredPlayers.add(player);
                    }
                }
            }
            currentPage = 0;
            scrollAmount = 0;
            rebuildPlayerList();
            updatePaginationButtons();
        }

        // ==================== 列表重建 ====================

        private void rebuildPlayerList() {
            if (listGroup == null) return;

            listGroup.clearAllWidgets();

            if (filteredPlayers.isEmpty()) {
                TextTexture emptyTexture = new TextTexture("gui.player_selection.empty", COLOR_TEXT_HINT);
                emptyTexture.setWidth(LIST_WIDTH - SCROLLBAR_WIDTH - 8);
                emptyTexture.setType(TextTexture.TextType.NORMAL);
                ImageWidget emptyLabel = new ImageWidget(0, LIST_HEIGHT / 2 - 6, LIST_WIDTH - SCROLLBAR_WIDTH - 8, 12, emptyTexture);
                listGroup.addWidget(emptyLabel);
                updateScrollbar(0, 0);
                return;
            }

            int startIndex = currentPage * PLAYERS_PER_PAGE;
            int endIndex = Math.min(startIndex + PLAYERS_PER_PAGE, filteredPlayers.size());

            int slotWidth = LIST_WIDTH - SCROLLBAR_WIDTH - 8;
            int yOffset = 0;

            for (int i = startIndex; i < endIndex; i++) {
                PlayerInfo player = filteredPlayers.get(i);
                WidgetGroup slot = createPlayerSlot(0, yOffset, slotWidth, SLOT_HEIGHT, player);
                listGroup.addWidget(slot);
                yOffset += SLOT_HEIGHT + SLOT_SPACING;
            }

            int contentHeight = (endIndex - startIndex) * (SLOT_HEIGHT + SLOT_SPACING);
            int maxScroll = Math.max(0, contentHeight - LIST_HEIGHT + 8);
            updateScrollbar(contentHeight, maxScroll);
        }

        // ==================== 玩家槽位 ====================

        private WidgetGroup createPlayerSlot(int x, int y, int width, int height, PlayerInfo player) {
            WidgetGroup slot = new WidgetGroup();
            slot.setSelfPosition(x, y);
            slot.setSize(width, height);

            // 背景（带圆角）
            slot.setBackground(new ColorRectTexture(COLOR_SLOT_BG).setRadius(4));

            // 边框（带圆角）
            WidgetGroup border = new WidgetGroup();
            border.setSelfPosition(0, 0);
            border.setSize(width, height);
            border.setBackground(new ColorBorderTexture(1, COLOR_BORDER).setRadius(4));
            slot.addWidget(border);

            // 头像区域（带圆角）
            int headSize = 36;
            int headX = 8;
            int headY = (height - headSize) / 2;

            // 头像背景（带圆角）
            WidgetGroup headBg = new WidgetGroup();
            headBg.setSelfPosition(headX, headY);
            headBg.setSize(headSize, headSize);
            headBg.setBackground(new ColorRectTexture(0xFF1A1A1A).setRadius(4));
            slot.addWidget(headBg);

            // 头像边框（带圆角）
            WidgetGroup headBorder = new WidgetGroup();
            headBorder.setSelfPosition(headX, headY);
            headBorder.setSize(headSize, headSize);
            headBorder.setBackground(new ColorBorderTexture(1, 0xFF888888).setRadius(4));
            slot.addWidget(headBorder);

            // 加载玩家头像
            loadPlayerHead(player.playerName(), headX + 2, headY + 2, headSize - 4, slot);

            // 玩家名称
            TextTexture nameTexture = new TextTexture(player.playerName(), COLOR_TEXT_NAME);
            nameTexture.setType(TextTexture.TextType.LEFT);
            nameTexture.setDropShadow(true);
            ImageWidget nameLabel = new ImageWidget(headX + headSize + 12, headY + 10, width - headSize - 80, 14, nameTexture);
            slot.addWidget(nameLabel);

            // 邀请按钮
            int inviteBtnWidth = 50;
            int inviteBtnHeight = 20;
            int inviteBtnX = width - inviteBtnWidth - 8;
            int inviteBtnY = (height - inviteBtnHeight) / 2;

            ButtonWidget inviteButton = createButton(inviteBtnX, inviteBtnY, inviteBtnWidth, inviteBtnHeight,
                    "gui.player_selection.invite", clickData -> onInvitePlayer(player));
            slot.addWidget(inviteButton);

            return slot;
        }

        // ==================== 头像加载 ====================

        private void loadPlayerHead(String playerName, int x, int y, int size, WidgetGroup parent) {
            Minecraft mc = Minecraft.getInstance();

            // 创建头像显示容器（带圆角背景）
            WidgetGroup headContainer = new WidgetGroup();
            headContainer.setSelfPosition(x, y);
            headContainer.setSize(size, size);
            headContainer.setBackground(new ColorRectTexture(0xFF1A1A1A).setRadius(3));
            parent.addWidget(headContainer);

            // 查找玩家实体并获取皮肤
            ResourceLocation skinLocation = DEFAULT_PLAYER_SKIN;
            if (mc.level != null) {
                for (var player : mc.level.players()) {
                    if (player.getName().getString().equals(playerName)) {
                        skinLocation = nn(player.getSkinTextureLocation());
                        break;
                    }
                }
            }

            // 创建头像图片
            PlayerHeadWidget headWidget = new PlayerHeadWidget(skinLocation, 1, 1, size - 2, size - 2);
            headContainer.addWidget(headWidget);
        }

        /**
         * 玩家头像自定义渲染组件
         */
        private static class PlayerHeadWidget extends WidgetGroup {
            private final ResourceLocation skinLocation;

            public PlayerHeadWidget(ResourceLocation skinLocation, int x, int y, int width, int height) {
                this.skinLocation = skinLocation;
                this.setSelfPosition(x, y);
                this.setSize(width, height);
            }

            @Override
            public void drawInBackground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                super.drawInBackground(graphics, mouseX, mouseY, partialTicks);

                int x = getPosition().x;
                int y = getPosition().y;
                int width = getSize().width;
                int height = getSize().height;

                RenderSystem.setShaderTexture(0, skinLocation);
                RenderSystem.enableBlend();

                // 绘制头部底层
                graphics.blit(skinLocation, x, y, width, height, 8, 8, 8, 8, 64, 64);
                // 绘制头部帽子层
                graphics.blit(skinLocation, x, y, width, height, 40, 8, 8, 8, 64, 64);

                RenderSystem.disableBlend();
            }
        }

        // ==================== 滚动条 ====================

        private void updateScrollbar(int contentHeight, int maxScroll) {
            if (scrollbarGroup == null) return;

            scrollbarGroup.clearAllWidgets();

            if (contentHeight <= LIST_HEIGHT - 8) {
                return;
            }

            double scrollPercent = maxScroll > 0 ? scrollAmount / maxScroll : 0;
            int trackHeight = LIST_HEIGHT - 8;
            int thumbHeight = Math.max(24, (int) (trackHeight * ((double) (LIST_HEIGHT - 8) / contentHeight)));
            int thumbY = (int) ((trackHeight - thumbHeight) * scrollPercent);

            // 滚动条滑块（带圆角）
            WidgetGroup thumb = new WidgetGroup();
            thumb.setSelfPosition(1, thumbY);
            thumb.setSize(SCROLLBAR_WIDTH - 2, thumbHeight);
            thumb.setBackground(new ColorRectTexture(COLOR_SCROLLBAR_THUMB).setRadius(3));
            scrollbarGroup.addWidget(thumb);
        }

        // ==================== 分页 ====================

        private String getPageInfoText() {
            int maxPages = (int) Math.ceil((double) filteredPlayers.size() / PLAYERS_PER_PAGE);
            if (maxPages <= 0) return "";
            return (currentPage + 1) + " / " + maxPages;
        }

        private void updatePaginationButtons() {
            int maxPages = (int) Math.ceil((double) filteredPlayers.size() / PLAYERS_PER_PAGE);

            if (prevButton != null) {
                prevButton.setActive(currentPage > 0);
            }
            if (nextButton != null) {
                nextButton.setActive(currentPage < maxPages - 1);
            }
            if (pageInfoLabel != null) {
                pageInfoLabel.setVisible(maxPages > 0);
            }
        }

        public void onPrevPage() {
            if (currentPage > 0) {
                currentPage--;
                scrollAmount = 0;
                rebuildPlayerList();
                updatePaginationButtons();
            }
        }

        public void onNextPage() {
            int maxPages = (int) Math.ceil((double) filteredPlayers.size() / PLAYERS_PER_PAGE);
            if (currentPage < maxPages - 1) {
                currentPage++;
                scrollAmount = 0;
                rebuildPlayerList();
                updatePaginationButtons();
            }
        }

        // ==================== 按钮回调 ====================

        private void onInvitePlayer(PlayerInfo player) {
            ConfirmationScreen.open(
                    Component.translatable("gui.player_selection.confirm_title"),
                    Component.translatable("gui.player_selection.confirm_invite_message", player.playerName()),
                    confirmed -> {
                        if (confirmed) {
                            NetworkManager.INSTANCE.sendToServer(
                                    new SendOfficialInvitationPacket(cityCorePos, player.playerName()));
                            onBack();
                        }
                    }
            );
        }

        public void onBack() {
            if (Minecraft.getInstance() != null && parentScreen != null) {
                Minecraft.getInstance().setScreen(parentScreen);
            }
        }
    }
}
