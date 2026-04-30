package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.client.gui.ldlib.LDLibMenuScreen;
import com.xiaoliang.simukraft.network.BuildingListRequestPacket;
import com.xiaoliang.simukraft.network.BuildingListResponsePacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.*;

@OnlyIn(Dist.CLIENT)
public class BuildingListScreen extends LDLibMenuScreen {

    private final String category;
    private final Screen parent;
    private List<BuildingResponseInfo> buildings = new ArrayList<>();
    private List<BuildingResponseInfo> filteredBuildings = new ArrayList<>();
    private boolean isLoading = true;

    private int currentPage = 0;
    private static final int COLUMNS = 3;
    private static final int CARD_H = 72;
    private static final int CARD_GAP = 8;

    private static final int ACCENT_COLOR = 0xFFADD8E6;  // 细亮浅蓝�?
    private static final int CARD_BG = 0xFF2A2A2A;
    private static final int CARD_HOVER_BG = 0xFF3A3A3A;
    private static final int CARD_BORDER = 0xFF555555;

    private SortMode sortMode = SortMode.NAME;
    private boolean sortAscending = true;
    private BuildingResponseInfo selectedBuilding = null;
    private String searchQuery = "";
    private TextFieldWidget searchBox;
    private final PinManager pinManager = new PinManager(); // 置顶管理器
    private Set<String> pinnedBuildings; // 从文件加载的置顶建筑
    private final Map<String, String> searchableNameCache = new HashMap<>();
    private final Map<String, Integer> parsedPriceCache = new HashMap<>();
    private final Map<String, Integer> parsedVolumeCache = new HashMap<>();

    public record BuildingResponseInfo(String name, String size, String amount, String author, String description,
                                       String category, String fileName, String nbtFileName, List<String> tags) {
    }

    // 标签信息记录
    public record TagInfo(String label, String iconPath, int bgColor, int borderColor, int textColor) {
    }

    enum SortMode { NAME, PRICE, SIZE }

    public BuildingListScreen(String category, Screen parent) {
        super(Component.translatable("gui.building_list.title", getCategoryName(category)), parent);
        this.category = category;
        this.parent = parent;
        this.pinnedBuildings = pinManager.getPinnedBuildings();
        NetworkManager.INSTANCE.sendToServer(new BuildingListRequestPacket(category));
    }

    @Override
    protected int getUIWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    @Override
    protected int getUIHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    @Override
    protected boolean enableAutoScale() {
        return false;
    }

    @Override
    protected ModularUI createModularUI() {
        return createInitialModularUI();
    }

    private static ModularUI createInitialModularUI() {
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        Player player = Minecraft.getInstance().player;
        ListUIHolder holder = new ListUIHolder();
        ModularUI modularUI = new ModularUI(new Size(screenW, screenH), holder, player);
        WidgetGroup mainGroup = new WidgetGroup();
        mainGroup.setSelfPosition(0, 0);
        mainGroup.setSize(screenW, screenH);
        modularUI.widget(mainGroup);
        return modularUI;
    }

    @Override
    public void init() {
        super.init();
        rebuildUI();
    }

    private void rebuildUI() {
        // 清除现有 widgets
        this.modularUI.mainGroup.clearAllWidgets();
        
        int screenW = this.width;
        int screenH = this.height;
        
        // 主容�?
        WidgetGroup mainGroup = this.modularUI.mainGroup;
        mainGroup.setSelfPosition(Position.ORIGIN);
        mainGroup.setSize(new Size(screenW, screenH));
        
        // 背景
        mainGroup.setBackground(new ColorRectTexture(0xC0101010));
        
        // 标题 - 使用 ImageWidget + TextTexture 实现居中
        TextTexture titleTexture = new TextTexture(
            Component.translatable("gui.building_list.title", getCategoryName(category)).getString(), 0xFFFFFF);
        titleTexture.setWidth(screenW);
        titleTexture.setType(TextTexture.TextType.NORMAL);
        titleTexture.setDropShadow(false);
        mainGroup.addWidget(new ImageWidget(0, 4, screenW, this.font.lineHeight, titleTexture));
        
        // 搜索�?- 使用 LDLib TextFieldWidget 支持中文输入�?
        int searchBoxW = Math.min(160, screenW / 3);
        int searchBoxX = (screenW - searchBoxW) / 2;
        int searchY = 16;

        // 创建 TextFieldWidget 搜索�?
        this.searchBox = new TextFieldWidget();
        this.searchBox.setSelfPosition(searchBoxX, searchY);
        this.searchBox.setSize(searchBoxW, 14);
        this.searchBox.setMaxStringLength(50);
        this.searchBox.setBordered(false);
        this.searchBox.setCurrentString(searchQuery);
        this.searchBox.setBackground(new GuiTextureGroup(
            new ColorRectTexture(0xFF333333).setRadius(5),
            new ColorBorderTexture(1, 0xFF888888).setRadius(5)
        ));
        mainGroup.addWidget(this.searchBox);
        
        // 排序按钮
        int barY = searchY + 17;
        String arrow = sortAscending
                ? Component.translatable("gui.building_list.sort.asc").getString()
                : Component.translatable("gui.building_list.sort.desc").getString();
        String[] sortLabels = {
                Component.translatable("gui.building_list.sort.name").getString(),
                Component.translatable("gui.building_list.sort.price").getString(),
                Component.translatable("gui.building_list.sort.size").getString()
        };
        SortMode[] modes = SortMode.values();
        
        int totalBarW = 0;
        int[] btnWidths = new int[sortLabels.length];
        for (int i = 0; i < sortLabels.length; i++) {
            String label = safeString(sortLabels[i]);
            if (modes[i] == sortMode) label += " " + arrow;
            btnWidths[i] = this.font.width(label) + 8;
            totalBarW += btnWidths[i] + 4;
        }
        totalBarW -= 4;
        
        int barX = (screenW - totalBarW) / 2;
        int drawX = barX;
        
        for (int i = 0; i < sortLabels.length; i++) {
            String label = safeString(sortLabels[i]);
            if (modes[i] == sortMode) label += " " + arrow;
            int bw = btnWidths[i];
            final int index = i;
            
            int bgColor = modes[i] == sortMode ? 0xFF444444 : 0xFF2A2A2A;
            int borderColor = modes[i] == sortMode ? 0xFFC8A260 : 0xFF555555;
            int textColor = modes[i] == sortMode ? 0xFFFFD700 : 0xFFCCCCCC;
            
            ButtonWidget btn = new ButtonWidget(drawX, barY, bw, 14, 
                new GuiTextureGroup(
                    new ColorRectTexture(bgColor).setRadius(3),
                    new ColorBorderTexture(1, borderColor).setRadius(3),
                    new TextTexture(label, textColor).setType(TextTexture.TextType.NORMAL)
                ),
                (clickData) -> {
                    if (sortMode == modes[index]) {
                        sortAscending = !sortAscending;
                    } else {
                        sortMode = modes[index];
                        sortAscending = true;
                    }
                    applyFilterAndSort();
                    rebuildUI();
                });
            
            mainGroup.addWidget(btn);
            drawX += bw + 4;
        }
        
        // 建筑卡片区域
        int gridTop = 50;
        int gridBottom = screenH - 22;
        
        if (isLoading) {
            // 加载提示 - 使用 ImageWidget + TextTexture 实现居中
            TextTexture loadingTexture = new TextTexture(
                Component.translatable("gui.building_list.loading").getString(), 0xFFAAAA00);
            loadingTexture.setWidth(screenW);
            loadingTexture.setType(TextTexture.TextType.NORMAL);
            ImageWidget loadingWidget = new ImageWidget(0, (gridTop + gridBottom) / 2, screenW, this.font.lineHeight, loadingTexture);
            loadingWidget.setId("pagination_loading");
            mainGroup.addWidget(loadingWidget);
        } else if (filteredBuildings.isEmpty()) {
            // 空列表提�?- 使用 ImageWidget + TextTexture 实现居中
            TextTexture emptyTexture = new TextTexture(
                Component.translatable("gui.building_list.no_buildings").getString(), 0xFF888888);
            emptyTexture.setWidth(screenW);
            emptyTexture.setType(TextTexture.TextType.NORMAL);
            ImageWidget emptyWidget = new ImageWidget(0, (gridTop + gridBottom) / 2, screenW, this.font.lineHeight, emptyTexture);
            emptyWidget.setId("pagination_empty");
            mainGroup.addWidget(emptyWidget);
        } else {
            // 卡片容器
            int cardW = getCardW();
            int perPage = getBuildingsPerPage();
            int totalGridW = COLUMNS * cardW + (COLUMNS - 1) * CARD_GAP;
            int startX = (screenW - totalGridW) / 2;
            
            int startIdx = currentPage * perPage;
            int endIdx = Math.min(startIdx + perPage, filteredBuildings.size());
            
            for (int i = startIdx; i < endIdx; i++) {
                int rel = i - startIdx;
                int col = rel % COLUMNS;
                int row = rel / COLUMNS;
                int cx = startX + col * (cardW + CARD_GAP);
                int cy = gridTop + row * (CARD_H + CARD_GAP);

                BuildingResponseInfo b = filteredBuildings.get(i);
                boolean isSelected = b == selectedBuilding;

                WidgetGroup card = createBuildingCard(b, cx, cy, cardW, CARD_H, isSelected);
                card.setId("building_card_" + i);
                mainGroup.addWidget(card);
            }

            // 分页信息 - 使用 ImageWidget + TextTexture 实现居中
            int maxPages = Math.max(1, (int) Math.ceil((double) filteredBuildings.size() / perPage));
            String pageText = Component.translatable("gui.pagination.info", currentPage + 1, maxPages).getString();
            TextTexture pageTexture = new TextTexture(pageText, 0xFFCCCCCC);
            pageTexture.setWidth(screenW);
            pageTexture.setType(TextTexture.TextType.NORMAL);
            ImageWidget pageWidget = new ImageWidget(0, screenH - 16, screenW, this.font.lineHeight, pageTexture);
            pageWidget.setId("pagination_info");
            mainGroup.addWidget(pageWidget);

            // 分页按钮
            String prev = safeString(Component.translatable("gui.pagination.previous").getString());
            String next = safeString(Component.translatable("gui.pagination.next").getString());
            int prevW = this.font.width(prev) + 8;
            int nextW = this.font.width(next) + 8;
            int prevX = screenW / 2 - 80;
            int nextX = screenW / 2 + 80 - nextW;
            int pagY = screenH - 16;

            if (currentPage > 0) {
                ButtonWidget prevPageButton = new ButtonWidget(prevX, pagY, prevW, 12,
                    new GuiTextureGroup(
                        new ColorRectTexture(0xFF2A2A2A).setRadius(3),
                        new ColorBorderTexture(1, 0xFF555555).setRadius(3),
                        new TextTexture(prev, 0xFFCCCCCC).setType(TextTexture.TextType.NORMAL)
                    ),
                    (clickData) -> {
                        currentPage--;
                        rebuildUI();
                    });
                prevPageButton.setId("pagination_prev");
                mainGroup.addWidget(prevPageButton);
            }

            if (currentPage < maxPages - 1) {
                ButtonWidget nextPageButton = new ButtonWidget(nextX, pagY, nextW, 12,
                    new GuiTextureGroup(
                        new ColorRectTexture(0xFF2A2A2A).setRadius(3),
                        new ColorBorderTexture(1, 0xFF555555).setRadius(3),
                        new TextTexture(next, 0xFFCCCCCC).setType(TextTexture.TextType.NORMAL)
                    ),
                    (clickData) -> {
                        currentPage++;
                        rebuildUI();
                    });
                nextPageButton.setId("pagination_next");
                mainGroup.addWidget(nextPageButton);
            }
        }

        // 初始化所�?widgets
        this.modularUI.initWidgets();
    }

    private WidgetGroup createBuildingCard(BuildingResponseInfo b, int x, int y, int w, int h, boolean isSelected) {
        WidgetGroup card = new WidgetGroup(x, y, w, h);
        
        int bg = isSelected ? CARD_HOVER_BG : CARD_BG;
        int border = isSelected ? ACCENT_COLOR : CARD_BORDER;
        
        // 卡片背景
        card.setBackground(new GuiTextureGroup(
            new ColorRectTexture(bg).setRadius(5),
            new ColorBorderTexture(1, border).setRadius(5)
        ));
        
        // 顶部装饰�?- 带圆角，根据分类变换颜色
        int categoryColor = getCategoryColor(category);
        WidgetGroup topBar = new WidgetGroup(5, 1, w - 10, 3);
        topBar.setBackground(new ColorRectTexture(categoryColor).setRadius(2));
        card.addWidget(topBar);
        
        // 建筑名称 - 使用 ImageWidget + TextTexture 实现基于卡片的居�?
        String name = safeString(b.name());
        if (this.font.width(name) > w - 8) {
            name = this.font.plainSubstrByWidth(name, w - 14) + "..";
        }
        TextTexture nameTexture = new TextTexture(name, 0xFFFFFFFF);
        nameTexture.setWidth(w);
        nameTexture.setType(TextTexture.TextType.NORMAL);
        card.addWidget(new ImageWidget(0, 6, w, this.font.lineHeight, nameTexture));

        // 信息�?- 使用 ImageWidget + TextTexture
        int infoY = 20;
        String sizeText = Component.translatable("gui.building.size", b.size()).getString();
        TextTexture sizeTexture = new TextTexture(sizeText, 0xFFAAAAAA);
        sizeTexture.setWidth(w - 8);
        sizeTexture.setType(TextTexture.TextType.LEFT);
        card.addWidget(new ImageWidget(4, infoY, w - 8, this.font.lineHeight, sizeTexture));

        String priceText = Component.translatable("gui.building.price", b.amount()).getString();
        TextTexture priceTexture = new TextTexture(priceText, 0xFFAAAAAA);
        priceTexture.setWidth(w - 8);
        priceTexture.setType(TextTexture.TextType.LEFT);
        card.addWidget(new ImageWidget(4, infoY + 11, w - 8, this.font.lineHeight, priceTexture));

        String authorText = Component.translatable("gui.building.author", b.author()).getString();
        TextTexture authorTexture = new TextTexture(authorText, 0xFFAAAAAA);
        authorTexture.setWidth(w - 8);
        authorTexture.setType(TextTexture.TextType.LEFT);
        card.addWidget(new ImageWidget(4, infoY + 22, w - 8, this.font.lineHeight, authorTexture));

        // 标签显示 - 在建筑卡片底部
        if (b.tags() != null && !b.tags().isEmpty()) {
            int tagY = infoY + 36;
            int tagX = 4;
            int tagHeight = 12;
            int tagGap = 4;

            // 创建带有完整标签样式的悬停提示
            java.util.List<net.minecraft.network.chat.Component> tooltipTags = new java.util.ArrayList<>();
            
            // 添加标题
            tooltipTags.add(net.minecraft.network.chat.Component.literal("标签:").withStyle(style -> style.withColor(0xFFFFAA00)));
            
            // 添加每个标签的详细信息
            for (String tag : b.tags()) {
                TagInfo tagInfo = getTagInfo(tag);
                // 创建带有颜色的标签文本
                net.minecraft.network.chat.Component tagComponent = net.minecraft.network.chat.Component.literal("- " + tagInfo.label())
                    .withStyle(style -> style.withColor(tagInfo.textColor()));
                tooltipTags.add(tagComponent);
            }

            for (int i = 0; i < b.tags().size(); i++) {
                String tag = b.tags().get(i);
                TagInfo tagInfo = getTagInfo(tag);
                int tagWidth = this.font.width(safeString(tagInfo.label())) + 16; // 图标 + 文字 + 间距

                // 检查是否超出卡片宽度
                if (tagX + tagWidth > w - 4) {
                    // 空间不足，停止添加标签
                    break;
                }

                // 创建标签容器
                WidgetGroup tagGroup = new WidgetGroup(tagX, tagY, tagWidth, tagHeight);
                tagGroup.setBackground(new GuiTextureGroup(
                    new ColorRectTexture(tagInfo.bgColor()).setRadius(6),
                    new ColorBorderTexture(1, tagInfo.borderColor()).setRadius(6)
                ));

                // 标签图标 (使用 ResourceTexture 加载 SVG)
                if (!tagInfo.iconPath().isEmpty()) {
                    ResourceTexture iconTexture = new ResourceTexture(tagInfo.iconPath());
                    ImageWidget iconWidget = new ImageWidget(4, 2, 8, 8, iconTexture);
                    tagGroup.addWidget(iconWidget);
                }

                // 标签文字
                TextTexture tagTextTexture = new TextTexture(tagInfo.label(), tagInfo.textColor());
                tagTextTexture.setType(TextTexture.TextType.LEFT);
                tagGroup.addWidget(new ImageWidget(14, 2, tagWidth - 16, 8, tagTextTexture));

                // 添加透明按钮用于悬停提示
                ButtonWidget hoverButton = new ButtonWidget(0, 0, tagWidth, tagHeight, (clickData) -> {
                    // 空点击处理
                });
                hoverButton.setBackground(new ColorRectTexture(0x00FFFFFF)); // 透明背景
                hoverButton.setHoverTooltips(tooltipTags);
                tagGroup.addWidget(hoverButton);

                card.addWidget(tagGroup);
                tagX += tagWidth + tagGap;
            }
        }

        // 点击事件 - 使用 ButtonWidget 包装
        ButtonWidget clickArea = new ButtonWidget(0, 0, w, h, (clickData) -> {
            Minecraft minecraft = this.minecraft;
            if (selectedBuilding == b) {
                if (minecraft != null) {
                    minecraft.setScreen(new BuildingDetailScreen(b, this));
                }
            } else {
                selectedBuilding = b;
                rebuildUI();
            }
        });
        // 不设置背景，保持透明
        card.addWidget(clickArea);

        // 置顶按钮 - 右上角（在clickArea之后添加，确保在上层响应点击）
        boolean isPinned = pinnedBuildings.contains(b.fileName());
        // 未置顶：白色图钉，无背景；已置顶：展开图钉，黄色背景框
        ResourceTexture pinTexture = new ResourceTexture(isPinned ? "simukraft:textures/gui/pintop_open.png" : "simukraft:textures/gui/pintop.png");
        ButtonWidget pinButton = new ButtonWidget(w - 18, 6, 12, 12, (clickData) -> {
            if (pinnedBuildings.contains(b.fileName())) {
                pinnedBuildings.remove(b.fileName());
                pinManager.unpin(b.fileName()); // 取消置顶并保存
            } else {
                pinnedBuildings.add(b.fileName());
                pinManager.pin(b.fileName()); // 置顶并保存
            }
            applyFilterAndSort();
            refreshBuildingList();
        });
        pinButton.setBackground(new GuiTextureGroup(
            new ColorRectTexture(0x00FFFFFF).setRadius(2),
            pinTexture
        ));
        pinButton.setHoverTooltips(List.of(Component.translatable(isPinned ? "gui.building.unpin" : "gui.building.pin")));
        card.addWidget(pinButton);
        
        return card;
    }

    public static void setBuildingList(List<BuildingListResponsePacket.BuildingInfo> serverBuildingInfos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof BuildingListScreen screen) {
            List<BuildingResponseInfo> localBuildings = new ArrayList<>();
            for (BuildingListResponsePacket.BuildingInfo info : serverBuildingInfos) {
                localBuildings.add(new BuildingResponseInfo(
                        info.name(), info.size(), info.amount(), info.author(),
                        info.description(), info.category(), info.fileName(), info.nbtFileName(),
                        info.tags()
                ));
            }
            screen.buildings = localBuildings;
            screen.rebuildDerivedCaches();
            screen.filteredBuildings = new ArrayList<>(localBuildings);
            screen.isLoading = false;
            screen.applyFilterAndSort();
            screen.rebuildUI();
        }
    }

    private static String getCategoryName(String category) {
        return switch (category) {
            case "residential" -> Component.translatable("category.residential").getString();
            case "commercial" -> Component.translatable("category.commercial").getString();
            case "industry" -> Component.translatable("category.industrial").getString();
            case "other" -> Component.translatable("category.other").getString();
            default -> category;
        };
    }

    private int getCategoryColor(String category) {
        return switch (category) {
            case "residential" -> 0xFF90EE90; // 浅绿�?
            case "commercial" -> 0xFFADD8E6;  // 淡蓝�?
            case "industry" -> 0xFFC8A260;     // 金色（保持原色）
            case "other" -> 0xFFFFFFFF;        // 白色
            default -> 0xFFC8A260;             // 默认金色
        };
    }

    // 获取标签信息
    private TagInfo getTagInfo(String tag) {
        return switch (tag.toLowerCase()) {
            // 价格标签 - 使用 coin.png
            case "price_high" -> new TagInfo(
                Component.translatable("tag.price_high").getString(),
                "simukraft:textures/gui/coin.png",
                0xFF8B0000,  // 深红色背�?
                0xFFFF4444,  // 红色边框
                0xFFFFFFFF   // 白色文字
            );
            case "price_medium" -> new TagInfo(
                Component.translatable("tag.price_medium").getString(),
                "simukraft:textures/gui/coin.png",
                0xFF8B6914,  // 深橙色背�?
                0xFFFFA500,  // 橙色边框
                0xFFFFFFFF   // 白色文字
            );
            case "price_low" -> new TagInfo(
                Component.translatable("tag.price_low").getString(),
                "simukraft:textures/gui/coin.png",
                0xFF006400,  // 深绿色背�?
                0xFF32CD32,  // 绿色边框
                0xFFFFFFFF   // 白色文字
            );
            // 材料标签 - 使用 material.png
            case "material_high" -> new TagInfo(
                Component.translatable("tag.material_high").getString(),
                "simukraft:textures/gui/material.png",
                0xFF4B0082,  // 深紫色背�?
                0xFF9370DB,  // 紫色边框
                0xFFFFFFFF   // 白色文字
            );
            case "material_medium" -> new TagInfo(
                Component.translatable("tag.material_medium").getString(),
                "simukraft:textures/gui/material.png",
                0xFF8B4513,  // 深棕色背�?
                0xFFD2691E,  // 巧克力色边框
                0xFFFFFFFF   // 白色文字
            );
            case "material_low" -> new TagInfo(
                Component.translatable("tag.material_low").getString(),
                "simukraft:textures/gui/material.png",
                0xFF2E8B57,  // 深海绿色背景
                0xFF3CB371,  // 中海绿色边框
                0xFFFFFFFF   // 白色文字
            );
            // 阶段标签 - 使用 stage.png
            case "stage_early" -> new TagInfo(
                Component.translatable("tag.stage_early").getString(),
                "simukraft:textures/gui/stage.png",
                0xFF1E3A5F,  // 深蓝色背�?
                0xFF4682B4,  // 钢蓝色边�?
                0xFFFFFFFF   // 白色文字
            );
            case "stage_mid" -> new TagInfo(
                Component.translatable("tag.stage_mid").getString(),
                "simukraft:textures/gui/stage.png",
                0xFF5D4E37,  // 深褐色背�?
                0xFF8B7355,  // 浅褐色边�?
                0xFFFFFFFF   // 白色文字
            );
            case "stage_late" -> new TagInfo(
                Component.translatable("tag.stage_late").getString(),
                "simukraft:textures/gui/stage.png",
                0xFF4A0E4E,  // 深紫罗兰色背�?
                0xFF9932CC,  // 深兰花色边框
                0xFFFFFFFF   // 白色文字
            );
            // 兼容旧标�?
            case "expensive" -> new TagInfo(
                Component.translatable("tag.price_high").getString(),
                "simukraft:textures/gui/coin.png",
                0xFF8B0000,
                0xFFFF4444,
                0xFFFFFFFF
            );
            case "material" -> new TagInfo(
                Component.translatable("tag.material_high").getString(),
                "simukraft:textures/gui/material.png",
                0xFF4B0082,
                0xFF9370DB,
                0xFFFFFFFF
            );
            default -> new TagInfo(
                tag,
                "",
                0xFF666666,
                0xFF888888,
                0xFFFFFFFF
            );
        };
    }

    private int getCardW() {
        return Math.min(180, (this.width - 40 - (COLUMNS - 1) * CARD_GAP) / COLUMNS);
    }

    private int getGridTop() {
        return 50;
    }

    private int getGridBottom() {
        return this.height - 22;
    }

    private int getMaxRows() {
        int available = getGridBottom() - getGridTop();
        return Math.max(1, (available + CARD_GAP) / (CARD_H + CARD_GAP));
    }

    private int getBuildingsPerPage() {
        return COLUMNS * getMaxRows();
    }

    private void refreshBuildingList() {
        // 清除并重建建筑列表区域（包括卡片、分页信息和按钮�?
        // 通过 ID 前缀识别需要移除的 widgets
        List<Widget> toRemove = new ArrayList<>();
        for (Widget widget : this.modularUI.mainGroup.widgets) {
            String id = widget.getId();
            if (id != null && (id.startsWith("building_card_") || id.startsWith("pagination_"))) {
                toRemove.add(widget);
            }
        }
        for (Widget widget : toRemove) {
            this.modularUI.mainGroup.removeWidget(widget);
        }

        int screenW = this.width;
        int screenH = this.height;
        int gridTop = 50;
        int gridBottom = screenH - 22;

        if (isLoading) {
            TextTexture loadingTexture = new TextTexture(
                Component.translatable("gui.building_list.loading").getString(), 0xFFAAAA00);
            loadingTexture.setWidth(screenW);
            loadingTexture.setType(TextTexture.TextType.NORMAL);
            ImageWidget loadingWidget = new ImageWidget(0, (gridTop + gridBottom) / 2, screenW, this.font.lineHeight, loadingTexture);
            loadingWidget.setId("pagination_loading");
            this.modularUI.mainGroup.addWidget(loadingWidget);
        } else if (filteredBuildings.isEmpty()) {
            TextTexture emptyTexture = new TextTexture(
                Component.translatable("gui.building_list.no_buildings").getString(), 0xFF888888);
            emptyTexture.setWidth(screenW);
            emptyTexture.setType(TextTexture.TextType.NORMAL);
            ImageWidget emptyWidget = new ImageWidget(0, (gridTop + gridBottom) / 2, screenW, this.font.lineHeight, emptyTexture);
            emptyWidget.setId("pagination_empty");
            this.modularUI.mainGroup.addWidget(emptyWidget);
        } else {
            int cardW = getCardW();
            int perPage = getBuildingsPerPage();
            int totalGridW = COLUMNS * cardW + (COLUMNS - 1) * CARD_GAP;
            int startX = (screenW - totalGridW) / 2;

            int startIdx = currentPage * perPage;
            int endIdx = Math.min(startIdx + perPage, filteredBuildings.size());

            for (int i = startIdx; i < endIdx; i++) {
                int rel = i - startIdx;
                int col = rel % COLUMNS;
                int row = rel / COLUMNS;
                int cx = startX + col * (cardW + CARD_GAP);
                int cy = gridTop + row * (CARD_H + CARD_GAP);

                BuildingResponseInfo b = filteredBuildings.get(i);
                boolean isSelected = b == selectedBuilding;

                WidgetGroup card = createBuildingCard(b, cx, cy, cardW, CARD_H, isSelected);
                card.setId("building_card_" + i);
                this.modularUI.mainGroup.addWidget(card);
            }

            // 分页信息
            int maxPages = Math.max(1, (int) Math.ceil((double) filteredBuildings.size() / perPage));
            String pageText = Component.translatable("gui.pagination.info", currentPage + 1, maxPages).getString();
            TextTexture pageTexture = new TextTexture(pageText, 0xFFCCCCCC);
            pageTexture.setWidth(screenW);
            pageTexture.setType(TextTexture.TextType.NORMAL);
            ImageWidget pageWidget = new ImageWidget(0, screenH - 16, screenW, this.font.lineHeight, pageTexture);
            pageWidget.setId("pagination_info");
            this.modularUI.mainGroup.addWidget(pageWidget);

            // 分页按钮
            String prev = safeString(Component.translatable("gui.pagination.previous").getString());
            String next = safeString(Component.translatable("gui.pagination.next").getString());
            int prevW = this.font.width(prev) + 8;
            int nextW = this.font.width(next) + 8;
            int prevX = screenW / 2 - 80;
            int nextX = screenW / 2 + 80 - nextW;
            int pagY = screenH - 16;

            if (currentPage > 0) {
                ButtonWidget prevPageButton = new ButtonWidget(prevX, pagY, prevW, 12,
                    new GuiTextureGroup(
                        new ColorRectTexture(0xFF2A2A2A).setRadius(3),
                        new ColorBorderTexture(1, 0xFF555555).setRadius(3),
                        new TextTexture(prev, 0xFFCCCCCC).setType(TextTexture.TextType.NORMAL)
                    ),
                    (clickData) -> {
                        currentPage--;
                        refreshBuildingList();
                    });
                prevPageButton.setId("pagination_prev");
                this.modularUI.mainGroup.addWidget(prevPageButton);
            }

            if (currentPage < maxPages - 1) {
                ButtonWidget nextPageButton = new ButtonWidget(nextX, pagY, nextW, 12,
                    new GuiTextureGroup(
                        new ColorRectTexture(0xFF2A2A2A).setRadius(3),
                        new ColorBorderTexture(1, 0xFF555555).setRadius(3),
                        new TextTexture(next, 0xFFCCCCCC).setType(TextTexture.TextType.NORMAL)
                    ),
                    (clickData) -> {
                        currentPage++;
                        refreshBuildingList();
                    });
                nextPageButton.setId("pagination_next");
                this.modularUI.mainGroup.addWidget(nextPageButton);
            }
        }
    }

    private void applyFilterAndSort() {
        filteredBuildings.clear();
        if (searchQuery.isEmpty()) {
            filteredBuildings.addAll(buildings);
        } else {
            String lowerQuery = searchQuery.toLowerCase(Locale.ROOT);
            for (BuildingResponseInfo b : buildings) {
                if (getSearchableName(b).contains(lowerQuery)) {
                    filteredBuildings.add(b);
                }
            }
        }

        Comparator<BuildingResponseInfo> comp = switch (sortMode) {
            case NAME -> Comparator.comparing(BuildingResponseInfo::name, String.CASE_INSENSITIVE_ORDER);
            case PRICE -> Comparator.comparingInt(this::getCachedPrice);
            case SIZE -> Comparator.comparingInt(this::getCachedVolume);
        };
        if (!sortAscending) comp = comp.reversed();
        
        // 置顶建筑排在最前面
        comp = Comparator.<BuildingResponseInfo>comparingInt(b -> pinnedBuildings.contains(b.fileName()) ? 0 : 1).thenComparing(comp);
        
        filteredBuildings.sort(comp);
        currentPage = 0;
    }

    private void rebuildDerivedCaches() {
        searchableNameCache.clear();
        parsedPriceCache.clear();
        parsedVolumeCache.clear();
        for (BuildingResponseInfo building : buildings) {
            String cacheKey = getCacheKey(building);
            searchableNameCache.put(cacheKey, safeString(building.name()).toLowerCase(Locale.ROOT));
            parsedPriceCache.put(cacheKey, parsePrice(building.amount()));
            parsedVolumeCache.put(cacheKey, parseVolume(building.size()));
        }
    }

    private String getSearchableName(BuildingResponseInfo building) {
        return searchableNameCache.computeIfAbsent(
                getCacheKey(building),
                ignored -> safeString(building.name()).toLowerCase(Locale.ROOT)
        );
    }

    private int getCachedPrice(BuildingResponseInfo building) {
        return parsedPriceCache.computeIfAbsent(getCacheKey(building), ignored -> parsePrice(building.amount()));
    }

    private int getCachedVolume(BuildingResponseInfo building) {
        return parsedVolumeCache.computeIfAbsent(getCacheKey(building), ignored -> parseVolume(building.size()));
    }

    private String getCacheKey(BuildingResponseInfo building) {
        String fileName = building.fileName();
        return fileName == null || fileName.isEmpty() ? safeString(building.name()) : fileName;
    }

    private void syncSearchQueryFromWidget() {
        if (searchBox == null) {
            return;
        }
        String currentText = safeString(searchBox.getCurrentString());
        if (currentText.equals(searchQuery)) {
            return;
        }
        searchQuery = currentText;
        applyFilterAndSort();
        refreshBuildingList();
    }

    private static int parsePrice(String amount) {
        try { return Integer.parseInt(amount.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int parseVolume(String size) {
        try {
            String[] parts = size.toLowerCase().split("x");
            int vol = 1;
            for (String p : parts) vol *= Integer.parseInt(p.trim());
            return vol;
        } catch (Exception e) { return 0; }
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(nn(guiGraphics), mouseX, mouseY, partialTick);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        boolean handled = super.charTyped(codePoint, modifiers);
        syncSearchQueryFromWidget();
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Minecraft minecraft = this.minecraft;
        // ESC 返回上级界面
        if (keyCode == 256) {
            if (minecraft != null) {
                minecraft.setScreen(parent);
            }
            return true;
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        syncSearchQueryFromWidget();
        return handled;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class ListUIHolder implements IUIHolder {
        @Override public ModularUI createUI(Player entityPlayer) { return null; }
        @Override public boolean isInvalid() { return false; }
        @Override public boolean isRemote() { return true; }
        @Override public void markAsDirty() {}
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(String value) {
        return nn(value);
    }
}
