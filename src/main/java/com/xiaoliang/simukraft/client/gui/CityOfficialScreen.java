package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.OfficialListRequestPacket;
import com.xiaoliang.simukraft.network.OfficialListResponsePacket;
import com.xiaoliang.simukraft.network.RemoveOfficialPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 城市官员管理界面
 * 使用LDLib框架实现
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("null")
public class CityOfficialScreen extends ModularUIGuiContainer {

    // ==================== 布局常量 ====================

    // 窗口尺寸
    private static final int WINDOW_WIDTH = 320;
    private static final int WINDOW_HEIGHT = 240;

    // 列表区域
    private static final int LIST_WIDTH = 280;
    private static final int LIST_HEIGHT = 160;
    private static final int SLOT_HEIGHT = 50;
    private static final int SLOT_SPACING = 4;
    private static final int SCROLLBAR_WIDTH = 8;

    // 按钮尺寸
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 10;

    // 刷新间隔
    private static final long REFRESH_INTERVAL_MS = 3000;

    // 颜色定义
    private static final int COLOR_WINDOW_BG = 0xFF2A2A2A;
    private static final int COLOR_WINDOW_BORDER = 0xFF555555;
    private static final int COLOR_LIST_BG = 0xFF333333;
    private static final int COLOR_SLOT_BG = 0xFF3A3A3A;
    private static final int COLOR_SLOT_SELECTED = 0xFF5A5A5A;
    private static final int COLOR_BORDER = 0xFF666666;
    private static final int COLOR_BORDER_SELECTED = 0xFFFFAA00;
    private static final int COLOR_TEXT_NAME = 0xFFFFFFFF;
    private static final int COLOR_TEXT_MAYOR = 0xFFFFD700;
    private static final int COLOR_TEXT_OFFICIAL = 0xFF55AAFF;
    private static final int COLOR_SCROLLBAR_BG = 0xFF222222;
    private static final int COLOR_SCROLLBAR_THUMB = 0xFF888888;
    private static final int COLOR_BUTTON_HOVER = 0xFFADD8E6;

    // 纹理路径
    private static final String LONG_BUTTON_TEXTURE = "simukraft:textures/gui/long_button.png";
    private static final ResourceLocation DEFAULT_PLAYER_SKIN = ResourceLocation.tryParse("minecraft:textures/entity/steve.png");

    // ==================== 成员变量 ====================

    private final BlockPos cityCorePos;
    private final OfficialUIHolder holder;
    private long lastRefreshTime = 0;

    // ==================== 构造函数 ====================

    public CityOfficialScreen(BlockPos cityCorePos) {
        super(createHolderAndUI(cityCorePos), 0);
        this.cityCorePos = cityCorePos;
        this.holder = ((ModularUI) this.modularUI).holder instanceof OfficialUIHolder
                ? (OfficialUIHolder) ((ModularUI) this.modularUI).holder
                : null;
        if (this.holder != null) {
            this.holder.setScreen(this);
        }
        playOpenSound();
        requestOfficialListFromServer();
    }

    private void playOpenSound() {
        Minecraft.getInstance().getSoundManager().play(
                nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));
    }

    // ==================== UI 创建 ====================

    private static ModularUI createHolderAndUI(BlockPos cityCorePos) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // 计算窗口居中位置
        int windowX = (screenWidth - WINDOW_WIDTH) / 2;
        int windowY = (screenHeight - WINDOW_HEIGHT) / 2;

        OfficialUIHolder holder = new OfficialUIHolder(cityCorePos, null);
        ModularUI modularUI = new ModularUI(new Size(screenWidth, screenHeight), holder, nn(mc.player));

        WidgetGroup rootGroup = new WidgetGroup();
        rootGroup.setSize(screenWidth, screenHeight);

        // 半透明背景遮罩
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
        TextTexture titleTexture = new TextTexture("gui.city_official.title", 0xFFFFFFFF);
        titleTexture.setWidth(WINDOW_WIDTH);
        titleTexture.setType(TextTexture.TextType.NORMAL);
        titleTexture.setDropShadow(true);
        ImageWidget titleWidget = new ImageWidget(0, 8, WINDOW_WIDTH, 20, titleTexture);
        windowGroup.addWidget(titleWidget);

        // 列表区域背景（带圆角）
        int listX = (WINDOW_WIDTH - LIST_WIDTH) / 2;
        int listY = 32;

        WidgetGroup listBackground = new WidgetGroup();
        listBackground.setSelfPosition(listX, listY);
        listBackground.setSize(LIST_WIDTH, LIST_HEIGHT);
        listBackground.setBackground(new GuiTextureGroup(
                new ColorRectTexture(COLOR_LIST_BG).setRadius(4),
                new ColorBorderTexture(1, COLOR_BORDER).setRadius(4)
        ));
        windowGroup.addWidget(listBackground);

        // 官员列表容器
        WidgetGroup officialListGroup = new WidgetGroup();
        officialListGroup.setSelfPosition(2, 2);
        officialListGroup.setSize(LIST_WIDTH - SCROLLBAR_WIDTH - 4, LIST_HEIGHT - 4);
        listBackground.addWidget(officialListGroup);
        holder.setOfficialListGroup(officialListGroup);

        // 滚动条
        WidgetGroup scrollbarGroup = new WidgetGroup();
        scrollbarGroup.setSelfPosition(LIST_WIDTH - SCROLLBAR_WIDTH - 2, 2);
        scrollbarGroup.setSize(SCROLLBAR_WIDTH, LIST_HEIGHT - 4);
        scrollbarGroup.setBackground(new ColorRectTexture(COLOR_SCROLLBAR_BG));
        listBackground.addWidget(scrollbarGroup);
        holder.setScrollbarGroup(scrollbarGroup);

        // 底部按钮区域
        int buttonY = WINDOW_HEIGHT - BUTTON_HEIGHT - 10;
        int totalWidth = BUTTON_WIDTH * 3 + BUTTON_SPACING * 2;
        int startX = (WINDOW_WIDTH - totalWidth) / 2;

        // 添加玩家按钮
        ButtonWidget addButton = createButton(
                startX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.city_official.add",
                clickData -> holder.onAddButtonClick()
        );
        windowGroup.addWidget(addButton);
        holder.setAddButton(addButton);

        // 删除玩家按钮
        ButtonWidget removeButton = createButton(
                startX + BUTTON_WIDTH + BUTTON_SPACING, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.city_official.remove",
                clickData -> holder.onRemoveButtonClick()
        );
        windowGroup.addWidget(removeButton);
        holder.setRemoveButton(removeButton);

        // 完成按钮
        ButtonWidget doneButton = createButton(
                startX + (BUTTON_WIDTH + BUTTON_SPACING) * 2, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.button.done",
                clickData -> holder.onDoneButtonClick()
        );
        windowGroup.addWidget(doneButton);

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

        ResourceTexture buttonBg = new ResourceTexture(nn(ResourceLocation.tryParse(LONG_BUTTON_TEXTURE)));
        GuiTextureGroup buttonHover = new GuiTextureGroup(
                new ResourceTexture(nn(ResourceLocation.tryParse(LONG_BUTTON_TEXTURE))),
                new ColorBorderTexture(1, COLOR_BUTTON_HOVER).setRadius(3)
        );
        TextTexture buttonText = new TextTexture(textKey, 0xFFFFFFFF);
        buttonText.setType(TextTexture.TextType.NORMAL);

        button.setButtonTexture(buttonBg, buttonText);
        button.setHoverTexture(buttonHover, buttonText);
        button.setOnPressCallback(onPress);

        return button;
    }

    // ==================== 网络请求 ====================

    private void requestOfficialListFromServer() {
        NetworkManager.INSTANCE.sendToServer(new OfficialListRequestPacket(cityCorePos));
    }

    public void updateOfficialList(List<OfficialListResponsePacket.OfficialInfo> serverOfficials) {
        if (holder != null) {
            holder.updateOfficialList(serverOfficials);
        }
    }

    // ==================== 渲染 ====================

    @Override
    public void containerTick() {
        super.containerTick();
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRefreshTime >= REFRESH_INTERVAL_MS) {
            lastRefreshTime = currentTime;
            requestOfficialListFromServer();
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(nn(guiGraphics), mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (holder != null && holder.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (holder != null) {
            holder.mouseReleased(mouseX, mouseY, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (holder != null && holder.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    // ==================== UI Holder 类 ====================

    public static class OfficialUIHolder implements IUIHolder {

        // 数据
        private final BlockPos cityCorePos;
        private CityOfficialScreen screen;
        private List<OfficialInfo> officials = new ArrayList<>();
        private boolean isMayor = false;
        private int selectedIndex = -1;

        // 滚动状态
        private double scrollAmount = 0;
        private boolean isScrolling = false;

        // Widget 引用
        private WidgetGroup officialListGroup;
        private WidgetGroup scrollbarGroup;
        private ButtonWidget addButton;
        private ButtonWidget removeButton;

        public record OfficialInfo(String playerName, boolean isMayor) {
        }

        public OfficialUIHolder(BlockPos cityCorePos, CityOfficialScreen screen) {
            this.cityCorePos = cityCorePos;
            this.screen = screen;
        }

        public void setScreen(CityOfficialScreen screen) {
            this.screen = screen;
        }

        public void setOfficialListGroup(WidgetGroup group) {
            this.officialListGroup = group;
        }

        public void setScrollbarGroup(WidgetGroup group) {
            this.scrollbarGroup = group;
        }

        public void setAddButton(ButtonWidget button) {
            this.addButton = button;
        }

        public void setRemoveButton(ButtonWidget button) {
            this.removeButton = button;
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

        // ==================== 数据更新 ====================

        public void updateOfficialList(List<OfficialListResponsePacket.OfficialInfo> serverOfficials) {
            this.officials = new ArrayList<>();
            Player player = Minecraft.getInstance().player;
            for (OfficialListResponsePacket.OfficialInfo info : serverOfficials) {
                this.officials.add(new OfficialInfo(info.playerName(), info.isMayor()));
                if (info.isMayor() && player != null) {
                    this.isMayor = info.playerName().equals(player.getName().getString());
                }
            }
            rebuildOfficialList();
            updateButtonStates();
        }

        // ==================== 列表渲染 ====================

        private void rebuildOfficialList() {
            if (officialListGroup == null) return;

            officialListGroup.clearAllWidgets();

            int contentHeight = officials.size() * (SLOT_HEIGHT + SLOT_SPACING);
            int maxScroll = Math.max(0, contentHeight - LIST_HEIGHT + 8);

            scrollAmount = Math.max(0, Math.min(maxScroll, scrollAmount));

            int slotWidth = LIST_WIDTH - SCROLLBAR_WIDTH - 8;
            int yOffset = 2 - (int) scrollAmount;

            for (int i = 0; i < officials.size(); i++) {
                OfficialInfo official = officials.get(i);
                if (yOffset + SLOT_HEIGHT >= 0 && yOffset <= LIST_HEIGHT - 8) {
                    WidgetGroup slot = createOfficialSlot(0, yOffset, slotWidth, SLOT_HEIGHT, official, i == selectedIndex, i);
                    officialListGroup.addWidget(slot);
                }
                yOffset += SLOT_HEIGHT + SLOT_SPACING;
            }

            updateScrollbar(contentHeight, maxScroll);
        }

        private WidgetGroup createOfficialSlot(int x, int y, int width, int height,
                                                OfficialInfo official, boolean isSelected, int index) {
            WidgetGroup slot = new WidgetGroup();
            slot.setSelfPosition(x, y);
            slot.setSize(width, height);

            // 背景（带圆角）
            int bgColor = isSelected ? COLOR_SLOT_SELECTED : COLOR_SLOT_BG;
            slot.setBackground(new ColorRectTexture(bgColor).setRadius(4));

            // 边框（带圆角）
            int borderColor = isSelected ? COLOR_BORDER_SELECTED : COLOR_BORDER;
            WidgetGroup border = new WidgetGroup();
            border.setSelfPosition(0, 0);
            border.setSize(width, height);
            border.setBackground(new ColorBorderTexture(1, borderColor).setRadius(4));
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
            loadPlayerHead(official.playerName(), headX + 2, headY + 2, headSize - 4, slot);

            // 玩家名称
            TextTexture nameTexture = new TextTexture(official.playerName(), COLOR_TEXT_NAME);
            nameTexture.setType(TextTexture.TextType.LEFT);
            nameTexture.setDropShadow(true);
            ImageWidget nameLabel = new ImageWidget(headX + headSize + 12, headY + 4, width - headSize - 24, 14, nameTexture);
            slot.addWidget(nameLabel);

            // 职位标签
            String roleKey = official.isMayor() ? "gui.city_official.mayor" : "gui.city_official.official";
            int roleColor = official.isMayor() ? COLOR_TEXT_MAYOR : COLOR_TEXT_OFFICIAL;
            TextTexture roleTexture = new TextTexture(roleKey, roleColor);
            roleTexture.setType(TextTexture.TextType.LEFT);
            ImageWidget roleLabel = new ImageWidget(headX + headSize + 12, headY + 20, width - headSize - 24, 12, roleTexture);
            slot.addWidget(roleLabel);

            // 点击区域
            ButtonWidget clickArea = new ButtonWidget();
            clickArea.setSelfPosition(0, 0);
            clickArea.setSize(width, height);
            clickArea.setButtonTexture(new ColorRectTexture(0x00000000));
            final int idx = index;
            clickArea.setOnPressCallback(clickData -> {
                selectedIndex = (selectedIndex == idx) ? -1 : idx;
                rebuildOfficialList();
                updateButtonStates();
            });
            slot.addWidget(clickArea);

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
                        skinLocation = player.getSkinTextureLocation();
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

                // 渲染玩家头像（只渲染头部区域）
                RenderSystem.setShaderTexture(0, skinLocation);
                RenderSystem.enableBlend();

                // 绘制头部底层 (8,8 到 16,16 在64x64皮肤纹理中)
                graphics.blit(skinLocation, x, y, width, height, 8, 8, 8, 8, 64, 64);

                // 绘制头部帽子层 (40,8 到 48,16)
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

        // ==================== 按钮状态 ====================

        private void updateButtonStates() {
            if (addButton != null) {
                addButton.setVisible(isMayor);
                addButton.setActive(isMayor);
            }

            if (removeButton != null) {
                removeButton.setVisible(isMayor);
                boolean canRemove = isMayor && selectedIndex >= 0 && selectedIndex < officials.size()
                        && !officials.get(selectedIndex).isMayor();
                removeButton.setActive(canRemove);
            }
        }

        // ==================== 按钮回调 ====================

        public void onAddButtonClick() {
            openPlayerSelectionScreen();
        }

        public void onRemoveButtonClick() {
            removeSelectedOfficial();
        }

        public void onDoneButtonClick() {
            closeScreen();
        }

        private void openPlayerSelectionScreen() {
            if (screen != null && Minecraft.getInstance() != null) {
                Minecraft.getInstance().setScreen(new PlayerSelectionScreen(cityCorePos, screen));
            }
        }

        private void removeSelectedOfficial() {
            if (selectedIndex < 0 || selectedIndex >= officials.size()) return;

            OfficialInfo selected = officials.get(selectedIndex);
            if (!isMayor || selected.isMayor()) return;

            ConfirmationScreen.open(
                    Component.translatable("gui.city_official.confirm_remove_title"),
                    Component.translatable("gui.city_official.confirm_remove_message", selected.playerName()),
                    confirmed -> {
                        if (confirmed) {
                            NetworkManager.INSTANCE.sendToServer(
                                    new RemoveOfficialPacket(cityCorePos, selected.playerName()));
                            selectedIndex = -1;
                            updateButtonStates();
                            rebuildOfficialList();
                        }
                    }
            );
        }

        private void closeScreen() {
            if (Minecraft.getInstance() != null) {
                Minecraft.getInstance().setScreen(new CityManagementScreen(cityCorePos));
            }
        }

        // ==================== 鼠标事件处理 ====================

        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            int contentHeight = officials.size() * (SLOT_HEIGHT + SLOT_SPACING);
            int maxScroll = Math.max(0, contentHeight - LIST_HEIGHT + 8);

            if (maxScroll > 0) {
                scrollAmount = Math.max(0, Math.min(maxScroll, scrollAmount - delta * 15));
                rebuildOfficialList();
                return true;
            }
            return false;
        }

        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            Minecraft mc = Minecraft.getInstance();
            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int screenHeight = mc.getWindow().getGuiScaledHeight();

            int windowX = (screenWidth - WINDOW_WIDTH) / 2;
            int windowY = (screenHeight - WINDOW_HEIGHT) / 2;

            int listX = windowX + (WINDOW_WIDTH - LIST_WIDTH) / 2;
            int listY = windowY + 32;
            int scrollbarX = listX + LIST_WIDTH - SCROLLBAR_WIDTH - 2;

            int contentHeight = officials.size() * (SLOT_HEIGHT + SLOT_SPACING);
            if (contentHeight > LIST_HEIGHT - 8) {
                if (mouseX >= scrollbarX && mouseX <= scrollbarX + SCROLLBAR_WIDTH
                        && mouseY >= listY && mouseY <= listY + LIST_HEIGHT) {
                    isScrolling = true;
                    return true;
                }
            }
            return false;
        }

        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            isScrolling = false;
            return false;
        }

        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (!isScrolling) return false;

            Minecraft mc = Minecraft.getInstance();
            int screenHeight = mc.getWindow().getGuiScaledHeight();
            int windowY = (screenHeight - WINDOW_HEIGHT) / 2;
            int listY = windowY + 32;

            int trackHeight = LIST_HEIGHT - 8;
            int contentHeight = officials.size() * (SLOT_HEIGHT + SLOT_SPACING);
            int maxScroll = Math.max(0, contentHeight - LIST_HEIGHT + 8);

            double scrollPercent = (mouseY - listY) / trackHeight;
            scrollAmount = Math.max(0, Math.min(maxScroll, scrollPercent * maxScroll));
            rebuildOfficialList();
            return true;
        }
    }
}
