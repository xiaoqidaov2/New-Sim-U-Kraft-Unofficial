package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.LogisticsActionPacket;
import com.xiaoliang.simukraft.network.LogisticsChannelPacket;
import com.xiaoliang.simukraft.network.LogisticsRoutesSyncPacket;
import com.xiaoliang.simukraft.network.LogisticsSyncPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.OpenWarehouseGridPacket;
import com.xiaoliang.simukraft.network.RequestLogisticsStatusPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * 物流盒服务端大菜单 — 左侧 tab bar + 右侧内容区。
 * Tab: 仓库总览 | 物流地图 | 路径管理
 */
@SuppressWarnings("null")
public class LogisticsServerScreen extends AbstractTransitionScreen
        implements LogisticsSyncPacket.LogisticsSyncReceiver,
                   LogisticsRoutesSyncPacket.LogisticsRoutesSyncReceiver {

    private enum Tab { OVERVIEW, MAP, ROUTES }

    private final BlockPos blockPos;
    private Tab currentTab = Tab.OVERVIEW;

    // 仓库状态
    private boolean hasNpc = false;
    private boolean hasWarehouse = false;
    private int containerCount = 0;
    private UUID warehouseId = null;

    // 路径管理数据
    private List<RouteEntry> routes = new ArrayList<>();

    // 自动刷新定时器
    private int refreshTimer = 0;
    private static final int REFRESH_INTERVAL = 1; // 每秒刷新一次

    // 布局常量
    private static final int TAB_WIDTH = 80;
    private static final int TAB_HEIGHT = 24;
    private static final int TAB_X = 5;

    public LogisticsServerScreen(BlockPos blockPos) {
        super(Component.translatable("gui.logistics_server.title"));
        this.blockPos = blockPos;
    }

    @Override
    protected void init() {
        super.init();
        // 请求服务端同步（会同时返回状态和路径数据）
        NetworkManager.INSTANCE.sendToServer(new RequestLogisticsStatusPacket(blockPos, true));
        rebuildUI();
    }

    private void rebuildUI() {
        clearWidgets();

        int tabY = 30;

        // ── 左侧 tab bar ──
        addTab(Component.translatable("gui.logistics_server.tab.overview").getString(), Tab.OVERVIEW, tabY);
        addTab(Component.translatable("gui.logistics_server.tab.map").getString(), Tab.MAP, tabY + TAB_HEIGHT + 2);
        addTab(Component.translatable("gui.logistics_server.tab.routes").getString(), Tab.ROUTES, tabY + (TAB_HEIGHT + 2) * 2);

        // ── 右侧内容区按钮 ──
        int contentX = TAB_X + TAB_WIDTH + 10;
        int contentY = 30;

        switch (currentTab) {
            case OVERVIEW -> buildOverviewTab(contentX, contentY);
            case MAP -> {
                addRenderableWidget(Button.builder(Component.translatable("gui.logistics_server.open_map"), btn -> {
                    if (warehouseId != null && this.minecraft != null)
                        this.minecraft.setScreen(new LogisticsNetworkScreen(blockPos, warehouseId));
                }).bounds(contentX, contentY + 20, 100, 20).build()).active = hasWarehouse;
            }
            case ROUTES -> buildRoutesTab(contentX, contentY);
        }
    }

    private void addTab(String label, Tab tab, int y) {
        boolean active = currentTab == tab;
        String text = active ? "§e§l" + label : "§7" + label;
        addRenderableWidget(Button.builder(Component.literal(text), btn -> {
            currentTab = tab;
            rebuildUI();
        }).bounds(TAB_X, y, TAB_WIDTH, TAB_HEIGHT).build());
    }

    // ══════════════════════════════
    //  仓库总览 tab
    // ══════════════════════════════

    private void buildOverviewTab(int x, int y) {
        int btnW = 150;
        int btnH = 20;
        int gap = 24;

        addRenderableWidget(Button.builder(
                hasNpc ? Component.translatable("gui.logistics_server.hire_npc.hired") : Component.translatable("gui.logistics_server.hire_npc"),
                btn -> onHireNpc()).bounds(x, y, btnW, btnH).build()).active = !hasNpc;

        addRenderableWidget(Button.builder(Component.translatable("gui.logistics_server.fire_npc"),
                btn -> onFireNpc()).bounds(x, y + gap, btnW, btnH).build()).active = hasNpc;

        addRenderableWidget(Button.builder(
                hasWarehouse ? Component.translatable("gui.logistics_server.create_warehouse.containers", containerCount) : Component.translatable("gui.logistics_server.create_warehouse"),
                btn -> onCreateWarehouse()).bounds(x, y + gap * 2, btnW, btnH).build()).active = !hasWarehouse;

        addRenderableWidget(Button.builder(Component.translatable("gui.logistics_server.delete_warehouse"),
                btn -> onDeleteWarehouse()).bounds(x, y + gap * 3, btnW, btnH).build()).active = hasWarehouse;

        addRenderableWidget(Button.builder(Component.translatable("gui.logistics_server.manage_warehouse"),
                btn -> onManageWarehouse()).bounds(x, y + gap * 4, btnW, btnH).build()).active = hasWarehouse;
    }

    // ══════════════════════════════
    //  路径管理 tab
    // ══════════════════════════════

    private void buildRoutesTab(int x, int y) {
        int addButtonX = x + 120;
        addRenderableWidget(Button.builder(Component.literal("§a+ ").append(Component.translatable("gui.logistics_server.add_route")), btn -> {
            if (warehouseId != null && this.minecraft != null) {
                this.minecraft.setScreen(new ChannelCreateScreen(blockPos, warehouseId));
            }
        }).bounds(addButtonX, y - 2, 96, 18).build()).active = hasWarehouse;

        int rowY = y + 30;
        for (int i = 0; i < routes.size(); i++) {
            RouteEntry route = routes.get(i);
            final UUID chId = route.channelId;
            int rowX = x;

            // 启停按钮
            addRenderableWidget(Button.builder(
                    Component.translatable(route.enabled ? "gui.logistics_network.toggle.disable" : "gui.logistics_network.toggle.enable"),
                    btn -> {
                        NetworkManager.INSTANCE.sendToServer(LogisticsChannelPacket.toggle(blockPos, chId));
                        delayRefresh();
                    }).bounds(rowX + 240, rowY, 32, 16).build());

            // 删除按钮
            addRenderableWidget(Button.builder(
                    Component.literal("§c").append(Component.translatable("gui.logistics_network.delete")),
                    btn -> {
                        NetworkManager.INSTANCE.sendToServer(LogisticsChannelPacket.delete(blockPos, chId));
                        delayRefresh();
                    }).bounds(rowX + 276, rowY, 32, 16).build());

            rowY += 40;
            if (rowY > this.height - 20) break;
        }
    }

    // ══════════════════════════════
    //  渲染
    // ══════════════════════════════

    @Override
    protected void drawBackground(GuiGraphics guiGraphics) {
        int a = (int) (getAlpha() * 200) & 0xFF;
        guiGraphics.fill(0, 0, this.width, this.height, (a << 24) | 0x0D0D1A);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        int a = (int) (getAlpha() * 255) & 0xFF;
        int white = (a << 24) | 0xFFFFFF;
        // 标题
        guiGraphics.drawString(this.font, "§l物流管理中心", TAB_X, 10, white);

        // tab bar 背景
        guiGraphics.fill(TAB_X - 2, 26, TAB_X + TAB_WIDTH + 2, this.height - 5, (a << 24) | 0x151525);

        // 内容区分隔线
        int lineX = TAB_X + TAB_WIDTH + 6;
        guiGraphics.fill(lineX, 26, lineX + 1, this.height - 5, (a << 24) | 0x333355);

        int contentX = lineX + 8;
        int contentY = 30;

        switch (currentTab) {
            case OVERVIEW -> renderOverviewInfo(guiGraphics, contentX + 160, contentY, a);
            case MAP -> renderMapPlaceholder(guiGraphics, contentX, contentY, a);
            case ROUTES -> renderRoutesContent(guiGraphics, contentX, contentY, a);
        }
    }

    private void renderOverviewInfo(GuiGraphics guiGraphics, int x, int y, int a) {
        int gray = (a << 24) | 0xAAAAAA;
        guiGraphics.drawString(this.font, "§e仓库状态", x, y, (a << 24) | 0xFFFFFF);
        y += 14;
        guiGraphics.drawString(this.font, "管理员: " + (hasNpc ? "§a已雇佣" : "§c未雇佣"), x, y, gray);
        y += 11;
        guiGraphics.drawString(this.font, "容器: " + (hasWarehouse ? containerCount + " 个" : "§c未创建"), x, y, gray);
        y += 11;
        guiGraphics.drawString(this.font, "路径: " + routes.size() + " 条", x, y, gray);

        // 显示传输费用规则
        y += 20;
        guiGraphics.drawString(this.font, "§e传输费用规则", x, y, (a << 24) | 0xFFFFFF);
        y += 12;
        guiGraphics.drawString(this.font, "§7• 256格内: §a免费", x, y, gray);
        y += 10;
        guiGraphics.drawString(this.font, "§7• 超过256格: §e0.02元/组", x, y, gray);
        y += 10;
        guiGraphics.drawString(this.font, "§7• 每超64格: §e+0.01元", x, y, gray);
    }

    private void renderMapPlaceholder(GuiGraphics guiGraphics, int x, int y, int a) {
        guiGraphics.drawString(this.font, "§e物流地图", x, y, (a << 24) | 0xFFFFFF);
        y += 16;

        if (warehouseId != null) {
            guiGraphics.drawString(this.font, "§7点击下方按钮打开全屏物流地图", x, y, (a << 24) | 0xAAAAAA);
        }
    }

    private void renderRoutesContent(GuiGraphics guiGraphics, int x, int y, int a) {
        int white = (a << 24) | 0xFFFFFF;
        int gray = (a << 24) | 0xAAAAAA;

        guiGraphics.drawString(this.font, "§e路径管理 (" + routes.size() + " 条)", x, y, white);

        if (routes.isEmpty()) {
            guiGraphics.drawString(this.font, "§8暂无路径，点击右侧按钮新增", x, y + 30, gray);
            return;
        }

        int rowY = y + 30;
        for (RouteEntry route : routes) {
            String dirIcon = "SEND".equals(route.direction) ? "§a发送→" : "§c←接收";
            String status = route.enabled ? "§a●" : "§8○";
            guiGraphics.drawString(this.font, status + " " + dirIcon + " §f" + route.name, x, rowY, white);

            // 详情
            guiGraphics.drawString(this.font, "§7目标: " + route.clientPos, x + 10, rowY + 11, gray);
            String items = route.itemNames.size() <= 3
                    ? String.join(", ", route.itemNames)
                    : route.itemNames.get(0) + ", " + route.itemNames.get(1) + "... 共" + route.itemNames.size() + "种";
            guiGraphics.drawString(this.font, "§7物品: " + items, x + 10, rowY + 22, gray);

            rowY += 40;
            if (rowY > this.height - 20) break;
        }
    }

    // ══════════════════════════════
    //  按钮回调
    // ══════════════════════════════

    private void onHireNpc() {
        if (this.minecraft != null) this.minecraft.setScreen(new HireWarehouseManagerScreen(blockPos));
    }

    private void onFireNpc() {
        if (this.minecraft != null && this.minecraft.level != null) {
            String dimId = this.minecraft.level.dimension().location().toString();
            NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByWorkplace(blockPos, dimId));
            hasNpc = false;
            rebuildUI();
        }
    }

    private void onCreateWarehouse() {
        if (this.minecraft != null)
            this.minecraft.setScreen(new LogisticsAreaSelectionScreen(blockPos, LogisticsActionPacket.Action.CREATE_WAREHOUSE));
    }

    private void onDeleteWarehouse() {
        NetworkManager.INSTANCE.sendToServer(new LogisticsActionPacket(LogisticsActionPacket.Action.DELETE_WAREHOUSE, blockPos, null, null));
        hasWarehouse = false;
        containerCount = 0;
        routes.clear();
        rebuildUI();
    }

    private void onManageWarehouse() {
        System.out.println("[LogisticsServerScreen] 点击管理仓库按钮，位置: " + blockPos);
        NetworkManager.INSTANCE.sendToServer(new OpenWarehouseGridPacket(blockPos));
    }

    private void delayRefresh() {
        // 延迟请求服务端刷新
        if (this.minecraft == null) return;
        var mc = this.minecraft;
        mc.tell(() -> mc.tell(() ->
            NetworkManager.INSTANCE.sendToServer(new RequestLogisticsStatusPacket(blockPos, true))
        ));
    }

    @Override
    public void onLogisticsSync(LogisticsSyncPacket packet) {
        if (packet.getSyncType() == LogisticsSyncPacket.SyncType.SERVER_STATUS
                && packet.getBlockPos().equals(this.blockPos)) {
            this.hasNpc = packet.hasNpc();
            this.hasWarehouse = packet.hasWarehouse();
            this.containerCount = packet.getContainerCount();
            this.warehouseId = packet.getWarehouseId();
            rebuildUI();
        }
    }

    @Override
    public void onRoutesSynced(LogisticsRoutesSyncPacket packet) {
        if (packet.getBlockPos().equals(this.blockPos)) {
            this.routes.clear();
            for (var entry : packet.getRoutes()) {
                this.routes.add(new RouteEntry(entry.channelId(), entry.name(), entry.direction(),
                        entry.enabled(), entry.clientPos(), entry.itemNames()));
            }
            rebuildUI();
        }
    }

    @Override
    public void tick() {
        super.tick();

        // 定时请求服务端同步最新状态
        refreshTimer++;
        if (refreshTimer >= REFRESH_INTERVAL) {
            refreshTimer = 0;
            if (this.minecraft != null) {
                NetworkManager.INSTANCE.sendToServer(new RequestLogisticsStatusPacket(blockPos, true));
            }
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── 数据记录 ──

    private record RouteEntry(UUID channelId, String name, String direction,
                               boolean enabled, String clientPos, List<String> itemNames) {}
}
