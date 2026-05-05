package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.xiaoliang.simukraft.client.map.SimuMapManager;
import com.xiaoliang.simukraft.client.map.SimuMapRegion;
import com.xiaoliang.simukraft.network.LogisticsChannelPacket;
import com.xiaoliang.simukraft.network.LogisticsClientRenamePacket;
import com.xiaoliang.simukraft.network.LogisticsNetworkResponsePacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestLogisticsNetworkPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.*;

/**
 * 物流网络地图界面 — 显示仓库/客户端标记、频道线路、侧边面板管理频道。
 */
@SuppressWarnings("null")
public class LogisticsNetworkScreen extends Screen
        implements LogisticsNetworkResponsePacket.LogisticsNetworkScreenReceiver {

    private static final int MARKER_SIZE = 5;
    private static final int WAREHOUSE_COLOR = 0xFF4488FF;  // 蓝
    private static final int CLIENT_COLOR = 0xFFFF8844;     // 橙
    private static final int CHANNEL_LINE_COLOR = 0xCC55FF55; // 绿
    private static final int DISABLED_LINE_COLOR = 0x66888888;

    private final BlockPos serverBlockPos;
    private final UUID warehouseId;

    // 地图状态
    private double zoomLevel = 1.0;
    private double offsetX = 0, offsetY = 0;
    private boolean isDragging = false;
    private double lastMouseX, lastMouseY;
    private boolean mapConsumerAcquired = false;

    // 数据（从服务端同步）
    private BlockPos warehouseBlockPos;
    private List<BlockPos> warehouseContainers = new ArrayList<>();
    private List<ClientInfo> cityClients = new ArrayList<>();
    private List<ChannelInfo> channels = new ArrayList<>();
    private final List<MarkerEntry> markerEntries = new ArrayList<>();

    // 悬停/选中
    private MarkerEntry hoveredMarker = null;
    private MarkerEntry selectedMarker = null;

    // 侧边面板
    private boolean showPanel = false;
    private int panelWidth = 180;
    private List<ChannelInfo> selectedChannels = new ArrayList<>();

    // 重命名相关
    private EditBox renameBox;
    private Button renameButton;
    private boolean isRenaming = false;

    public LogisticsNetworkScreen(BlockPos serverBlockPos, UUID warehouseId) {
        super(Component.translatable("gui.logistics_network.title"));
        this.serverBlockPos = serverBlockPos;
        this.warehouseId = warehouseId;
        this.warehouseBlockPos = serverBlockPos; // 默认值，会被服务端数据覆盖
    }

    @Override
    protected void init() {
        super.init();
        ensureMapReady();

        // 请求服务端数据
        NetworkManager.INSTANCE.sendToServer(new RequestLogisticsNetworkPacket(serverBlockPos, warehouseId));

        // 居中到仓库位置
        double chunkSize = 16 * zoomLevel;
        int cx = serverBlockPos.getX() >> 4;
        int cz = serverBlockPos.getZ() >> 4;
        offsetX = -cx * chunkSize;
        offsetY = -cz * chunkSize;

        // 左下角按钮
        addRenderableWidget(Button.builder(Component.literal("§a+ ").append(Component.translatable("gui.logistics_network.create_channel")), btn -> onCreateChannel())
                .bounds(5, this.height - 25, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.logistics_network.back"), btn -> this.onClose())
                .bounds(110, this.height - 25, 60, 20).build());
    }

    @Override
    public void onLogisticsNetworkDataReceived(LogisticsNetworkResponsePacket packet) {
        if (!packet.getWarehouseId().equals(warehouseId)) return;

        this.warehouseBlockPos = packet.getWarehouseBlockPos();
        this.warehouseContainers = new ArrayList<>(packet.getWarehouseContainers());

        // 更新客户端列表
        this.cityClients.clear();
        for (LogisticsNetworkResponsePacket.ClientData cd : packet.getClients()) {
            ClientInfo ci = new ClientInfo(cd.clientId, cd.blockPos, cd.cityId, cd.portPositions);
            // 同步名称缓存
            String cachedName = LogisticsClientData.getClientName(cd.clientId);
            if (!cachedName.isEmpty()) {
                ci.name = cachedName;
            }
            this.cityClients.add(ci);
        }

        // 更新频道列表
        this.channels.clear();
        for (LogisticsNetworkResponsePacket.ChannelData chd : packet.getChannels()) {
            this.channels.add(new ChannelInfo(chd.channelId, chd.name, chd.targetClientId,
                    chd.direction, chd.enabled, chd.itemNames));
        }

        buildMarkers();
        updateSelectedChannels();
        rebuildChannelButtons();
    }

    private void ensureMapReady() {
        if (SimuMapManager.isAvailable() && !mapConsumerAcquired) {
            SimuMapManager.getInstance().acquireConsumer();
            mapConsumerAcquired = true;
            if (Minecraft.getInstance().player != null) {
                int px = Minecraft.getInstance().player.chunkPosition().x;
                int pz = Minecraft.getInstance().player.chunkPosition().z;
                SimuMapManager.getInstance().forceScanArea(px, pz, 8);
                SimuMapManager.getInstance().forceRenderAll();
            }
        }
    }

    private void buildMarkers() {
        markerEntries.clear();

        // 仓库标记
        markerEntries.add(new MarkerEntry(warehouseBlockPos, WAREHOUSE_COLOR,
                "§b" + Component.translatable("gui.logistics_network.marker.warehouse").getString() + " " + formatPos(warehouseBlockPos)
                        + "\n" + Component.translatable("gui.logistics_network.marker.containers", warehouseContainers.size()).getString()
                        + "\n" + Component.translatable("gui.logistics_network.marker.channels", channels.size()).getString(),
                null, warehouseId));

        // 客户端标记
        for (ClientInfo client : cityClients) {
            boolean hasPorts = !client.portPositions.isEmpty();
            String status = hasPorts ? "§a" + Component.translatable("gui.logistics_network.marker.status.available").getString() : "§c" + Component.translatable("gui.logistics_network.marker.status.unavailable").getString();
            String displayName = client.getDisplayName();
            markerEntries.add(new MarkerEntry(client.blockPos, CLIENT_COLOR,
                    "§6" + Component.translatable("gui.logistics_network.marker.client").getString() + " " + displayName
                            + "\n" + Component.translatable("gui.logistics_network.marker.ports", client.portPositions.size()).getString()
                            + "\n" + status,
                    client.clientId, null));
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 全屏黑底
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF0A0A0A);

        int mapWidth = showPanel ? this.width - panelWidth : this.width;
        int mapHeight = this.height;

        renderTerrain(guiGraphics, 0, 0, mapWidth, mapHeight);
        renderChannelLines(guiGraphics, 0, 0, mapWidth, mapHeight);
        renderMarkers(guiGraphics, 0, 0, mapWidth, mapHeight, mouseX, mouseY);

        if (showPanel) {
            renderSidePanel(guiGraphics, mapWidth, 0, panelWidth, this.height);
        }

        // 标题
        guiGraphics.drawString(this.font, Component.translatable("gui.logistics_network.title").getString() + " - " + formatPos(warehouseBlockPos),
                5, 5, 0xFFFFFF);

        // 传输费用规则提示
        guiGraphics.drawString(this.font, "§e传输费用: §a256格内免费 §7| §e超256格0.02元/组 §7| §e每超64格+0.01元", 5, 18, 0xAAAAAA);

        // 提示
        guiGraphics.drawString(this.font, "§7" + Component.translatable("gui.logistics_network.hint").getString(), 5, this.height - 12, 0x888888);

        // 悬停提示
        if (hoveredMarker != null) {
            renderHoverTooltip(guiGraphics, mouseX, mouseY, hoveredMarker.hoverText);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    // ── 地形渲染（SimuMap） ──

    private void renderTerrain(GuiGraphics guiGraphics, int startX, int startY, int width, int height) {
        if (!SimuMapManager.isAvailable()) return;
        SimuMapManager manager = SimuMapManager.getInstance();

        int centerX = startX + width / 2;
        int centerY = startY + height / 2;
        double chunkSize = 16 * zoomLevel;

        int visibleChunksX = (int) Math.ceil(width / chunkSize) + 2;
        int visibleChunksY = (int) Math.ceil(height / chunkSize) + 2;
        int startChunkX = (int) Math.floor((-offsetX - width / 2.0) / chunkSize);
        int startChunkZ = (int) Math.floor((-offsetY - height / 2.0) / chunkSize);

        int startBlockX = startChunkX << 4;
        int startBlockZ = startChunkZ << 4;
        int endBlockX = (startChunkX + visibleChunksX) << 4;
        int endBlockZ = (startChunkZ + visibleChunksY) << 4;

        int minRegX = startBlockX >> 9;
        int maxRegX = endBlockX >> 9;
        int minRegZ = startBlockZ >> 9;
        int maxRegZ = endBlockZ >> 9;

        guiGraphics.flush();
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        int scissorX = (int) (startX * guiScale);
        int scissorY = (int) (Minecraft.getInstance().getWindow().getScreenHeight() - (startY + height) * guiScale);
        int scissorW = (int) (width * guiScale);
        int scissorH = (int) (height * guiScale);

        GlStateManager._enableScissorTest();
        GlStateManager._scissorBox(scissorX, scissorY, scissorW, scissorH);
        try {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            for (int regX = minRegX; regX <= maxRegX; regX++) {
                for (int regZ = minRegZ; regZ <= maxRegZ; regZ++) {
                    SimuMapRegion region = manager.getRegion(regX, regZ);
                    if (region == null || !region.hasData()) continue;
                    int textureId = region.getTextureId();
                    if (textureId == -1) continue;

                    double screenLeft = centerX + offsetX + regX * 512.0 * zoomLevel;
                    double screenTop = centerY + offsetY + regZ * 512.0 * zoomLevel;
                    double regionPixels = 512.0 * zoomLevel;

                    if (screenLeft + regionPixels < startX || screenLeft > startX + width
                            || screenTop + regionPixels < startY || screenTop > startY + height) continue;

                    RenderSystem.setShaderTexture(0, textureId);
                    RenderSystem.setShader(GameRenderer::getPositionTexShader);
                    RenderSystem.enableBlend();

                    Matrix4f matrix = guiGraphics.pose().last().pose();
                    BufferBuilder buf = Tesselator.getInstance().getBuilder();
                    buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                    float x0 = (float) screenLeft, y0 = (float) screenTop;
                    float x1 = (float) (screenLeft + regionPixels), y1 = (float) (screenTop + regionPixels);
                    buf.vertex(matrix, x0, y1, 0).uv(0, 1).endVertex();
                    buf.vertex(matrix, x1, y1, 0).uv(1, 1).endVertex();
                    buf.vertex(matrix, x1, y0, 0).uv(1, 0).endVertex();
                    buf.vertex(matrix, x0, y0, 0).uv(0, 0).endVertex();
                    BufferUploader.drawWithShader(buf.end());
                    RenderSystem.disableBlend();
                }
            }
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        } finally {
            GlStateManager._disableScissorTest();
        }
    }

    // ── 频道线路 ──

    private void renderChannelLines(GuiGraphics guiGraphics, int startX, int startY, int width, int height) {
        if (channels.isEmpty()) return;
        int centerX = startX + width / 2;
        int centerY = startY + height / 2;

        for (ChannelInfo channel : channels) {
            ClientInfo target = cityClients.stream()
                    .filter(c -> c.clientId.equals(channel.targetClientId))
                    .findFirst().orElse(null);
            if (target == null) continue;

            int[] from = worldToScreen(warehouseBlockPos, centerX, centerY);
            int[] to = worldToScreen(target.blockPos, centerX, centerY);

            int color = channel.enabled ? CHANNEL_LINE_COLOR : DISABLED_LINE_COLOR;
            drawLine(guiGraphics, from[0], from[1], to[0], to[1], color);
        }
    }

    // ── 标记点 ──

    private void renderMarkers(GuiGraphics guiGraphics, int startX, int startY, int width, int height,
                                int mouseX, int mouseY) {
        int centerX = startX + width / 2;
        int centerY = startY + height / 2;
        hoveredMarker = null;

        for (MarkerEntry entry : markerEntries) {
            int[] screen = worldToScreen(entry.pos, centerX, centerY);
            int sx = screen[0], sy = screen[1];

            if (sx < startX - MARKER_SIZE || sx > startX + width + MARKER_SIZE
                    || sy < startY - MARKER_SIZE || sy > startY + height + MARKER_SIZE) continue;

            // 选中高亮
            if (entry == selectedMarker) {
                guiGraphics.fill(sx - MARKER_SIZE - 1, sy - MARKER_SIZE - 1,
                        sx + MARKER_SIZE + 1, sy + MARKER_SIZE + 1, 0xFFFFFFFF);
            }

            guiGraphics.fill(sx - MARKER_SIZE, sy - MARKER_SIZE, sx + MARKER_SIZE, sy + MARKER_SIZE, entry.color);

            // 标签
            String label = entry.warehouseId != null ? "W" : "C";
            guiGraphics.drawCenteredString(this.font, label, sx, sy - 4, 0xFFFFFF);

            // 悬停检测
            if (mouseX >= sx - MARKER_SIZE && mouseX <= sx + MARKER_SIZE
                    && mouseY >= sy - MARKER_SIZE && mouseY <= sy + MARKER_SIZE) {
                hoveredMarker = entry;
            }
        }
    }

    // ── 侧边面板 ──

    private List<Button> channelButtons = new ArrayList<>();

    private void renderSidePanel(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        guiGraphics.fill(x, y, x + w, y + h, 0xE0111122);
        guiGraphics.fill(x, y, x + 1, y + h, 0xFF444444);

        int lineY = y + 5;

        // 显示选中节点信息
        if (selectedMarker != null) {
            String nodeName;
            if (selectedMarker.warehouseId != null) {
                nodeName = Component.translatable("gui.logistics_network.marker.warehouse").getString();
                guiGraphics.drawString(this.font, "§b" + nodeName, x + 5, lineY, 0xFFFFFF);
            } else if (selectedMarker.clientId != null) {
                ClientInfo client = cityClients.stream()
                        .filter(c -> c.clientId.equals(selectedMarker.clientId))
                        .findFirst().orElse(null);
                if (client != null) {
                    nodeName = client.getDisplayName();
                    guiGraphics.drawString(this.font, "§6" + Component.translatable("gui.logistics_network.marker.client").getString(), x + 5, lineY, 0xFFFFFF);
                    lineY += 12;
                    guiGraphics.drawString(this.font, "§f" + nodeName, x + 5, lineY, 0xFFFFFF);
                }
            }
            lineY += 18;

            // 重命名区域
            if (selectedMarker.clientId != null && isRenaming) {
                guiGraphics.drawString(this.font, Component.translatable("gui.logistics_network.rename_hint").getString(), x + 5, lineY, 0xAAAAAA);
            }
            lineY += 20;

            // 分隔线
            guiGraphics.fill(x + 5, lineY, x + w - 5, lineY + 1, 0xFF444444);
            lineY += 10;
        }

        // 频道列表标题
        guiGraphics.drawString(this.font, "§e" + Component.translatable("gui.logistics_network.channels").getString(), x + 5, lineY, 0xFFFFFF);
        lineY += 15;

        // 显示选中节点的频道
        if (selectedChannels.isEmpty()) {
            guiGraphics.drawString(this.font, "§8" + Component.translatable("gui.logistics_network.no_channels").getString(), x + 5, lineY, 0x888888);
        } else {
            for (ChannelInfo ch : selectedChannels) {
                String dirIcon = "SEND".equals(ch.direction) ? "§a→" : "§c←";
                String status = ch.enabled ? "§a" + Component.translatable("gui.logistics_network.channel.enabled").getString() : "§8" + Component.translatable("gui.logistics_network.channel.disabled").getString();
                String name = ch.name.length() > 12 ? ch.name.substring(0, 12) + ".." : ch.name;

                guiGraphics.drawString(this.font, dirIcon + " " + name, x + 5, lineY, 0xCCCCCC);
                guiGraphics.drawString(this.font, status, x + 5, lineY + 10, 0xAAAAAA);

                // 物品信息
                String itemInfo = getChannelItemDisplay(ch);
                guiGraphics.drawString(this.font, "§7" + itemInfo, x + 5, lineY + 20, 0x888888);

                lineY += 36;
                if (lineY > y + h - 10) break;
            }
        }
    }

    private String getChannelItemDisplay(ChannelInfo ch) {
        if (ch.itemNames.isEmpty()) {
            return Component.translatable("gui.logistics_network.no_items").getString();
        }
        String firstItemName = ch.itemNames.get(0);
        if (ch.itemNames.size() == 1) {
            return firstItemName;
        } else {
            return firstItemName + Component.translatable("gui.logistics_network.item_etc").getString();
        }
    }

    private void rebuildChannelButtons() {
        // 清除旧按钮和编辑框
        for (Button btn : channelButtons) removeWidget(btn);
        if (renameBox != null) removeWidget(renameBox);
        channelButtons.clear();

        if (!showPanel) return;

        int x = this.width - panelWidth;
        int lineY = 5;

        // 添加重命名按钮（仅对客户端节点）
        if (selectedMarker != null && selectedMarker.clientId != null) {
            renameButton = Button.builder(
                    Component.literal("✎"),
                    btn -> toggleRename()
            ).bounds(x + panelWidth - 30, lineY, 20, 18).build();
            addRenderableWidget(renameButton);
            channelButtons.add(renameButton);

            // 重命名编辑框
            renameBox = new EditBox(this.font, x + 5, lineY + 22, panelWidth - 35, 16, Component.literal(""));
            renameBox.setMaxLength(32);
            renameBox.setVisible(isRenaming);
            renameBox.setResponder(this::onRenameChanged);

            // 设置当前名称
            ClientInfo client = cityClients.stream()
                    .filter(c -> c.clientId.equals(selectedMarker.clientId))
                    .findFirst().orElse(null);
            if (client != null) {
                renameBox.setValue(client.hasCustomName() ? client.name : "");
            }
            addRenderableWidget(renameBox);

            lineY += 65;
        } else if (selectedMarker != null && selectedMarker.warehouseId != null) {
            lineY += 35;
        }

        lineY += 25;

        // 为选中节点的频道添加操作按钮
        for (int i = 0; i < selectedChannels.size(); i++) {
            ChannelInfo ch = selectedChannels.get(i);
            final UUID chId = ch.channelId;

            Button toggleBtn = Button.builder(
                    Component.translatable(ch.enabled ? "gui.button.disable" : "gui.button.enable"),
                    btn -> {
                        NetworkManager.INSTANCE.sendToServer(LogisticsChannelPacket.toggle(serverBlockPos, chId));
                        refreshData();
                    }).bounds(x + panelWidth - 75, lineY + 8, 30, 14).build();

            Button deleteBtn = Button.builder(
                    Component.literal("§c×"),
                    btn -> {
                        NetworkManager.INSTANCE.sendToServer(LogisticsChannelPacket.delete(serverBlockPos, chId));
                        refreshData();
                    }).bounds(x + panelWidth - 42, lineY + 8, 20, 14).build();

            addRenderableWidget(toggleBtn);
            addRenderableWidget(deleteBtn);
            channelButtons.add(toggleBtn);
            channelButtons.add(deleteBtn);

            lineY += 36;
            if (lineY > this.height - 40) break;
        }
    }

    private void refreshData() {
        if (this.minecraft != null) {
            var mc = this.minecraft;
            mc.tell(() -> mc.tell(() ->
                NetworkManager.INSTANCE.sendToServer(new RequestLogisticsNetworkPacket(serverBlockPos, warehouseId))
            ));
        }
    }

    private void toggleRename() {
        isRenaming = !isRenaming;
        if (renameBox != null) {
            renameBox.setVisible(isRenaming);
            if (isRenaming) {
                renameBox.setFocused(true);
                ClientInfo client = cityClients.stream()
                        .filter(c -> selectedMarker != null && c.clientId.equals(selectedMarker.clientId))
                        .findFirst().orElse(null);
                if (client != null) {
                    renameBox.setValue(client.hasCustomName() ? client.name : "");
                }
            }
        }
    }

    private void onRenameChanged(String newName) {
        if (selectedMarker == null || selectedMarker.clientId == null) return;

        String cleanedName = newName.trim();
        if (cleanedName.length() > 32) {
            cleanedName = cleanedName.substring(0, 32);
        }

        // 发送到服务器
        NetworkManager.INSTANCE.sendToServer(new LogisticsClientRenamePacket(selectedMarker.clientId, cleanedName));

        // 更新本地缓存
        LogisticsClientData.updateClientName(selectedMarker.clientId, cleanedName);

        // 更新客户端对象
        String finalCleanedName = cleanedName;
        ClientInfo client = cityClients.stream()
                .filter(c -> c.clientId.equals(selectedMarker.clientId))
                .findFirst().orElse(null);
        if (client != null) {
            client.name = finalCleanedName;
        }
    }

    private void updateSelectedChannels() {
        selectedChannels.clear();
        if (selectedMarker == null) return;

        if (selectedMarker.warehouseId != null) {
            selectedChannels.addAll(channels);
        } else if (selectedMarker.clientId != null) {
            for (ChannelInfo ch : channels) {
                if (ch.targetClientId.equals(selectedMarker.clientId)) {
                    selectedChannels.add(ch);
                }
            }
        }
    }

    // ── 悬停提示 ──

    private void renderHoverTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, String text) {
        String[] lines = text.split("\n");
        int maxWidth = 0;
        for (String line : lines) maxWidth = Math.max(maxWidth, this.font.width(line));

        int boxX = mouseX + 12;
        int boxY = mouseY - lines.length * 10 - 4;
        if (boxY < 0) boxY = mouseY + 15;

        guiGraphics.fill(boxX - 3, boxY - 3, boxX + maxWidth + 3, boxY + lines.length * 10 + 3, 0xE0222233);
        guiGraphics.fill(boxX - 2, boxY - 2, boxX + maxWidth + 2, boxY + lines.length * 10 + 2, 0xE0333355);

        int curY = boxY;
        for (String line : lines) {
            guiGraphics.drawString(this.font, line, boxX, curY, 0xFFFFFF);
            curY += 10;
        }
    }

    // ── 坐标转换 ──

    private int[] worldToScreen(BlockPos pos, int centerX, int centerY) {
        double sx = centerX + offsetX + pos.getX() * zoomLevel;
        double sy = centerY + offsetY + pos.getZ() * zoomLevel;
        return new int[]{(int) sx, (int) sy};
    }

    // ── Bresenham 画线 ──

    private void drawLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            guiGraphics.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }

    // ── 输入处理 ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (button == 0) {
            if (hoveredMarker != null) {
                selectedMarker = hoveredMarker;
                showPanel = true;
                isRenaming = false;
                updateSelectedChannels();
                rebuildChannelButtons();
                return true;
            }
            if (showPanel && mouseX < this.width - panelWidth) {
                showPanel = false;
                selectedMarker = null;
                isRenaming = false;
                rebuildChannelButtons();
            }
            isDragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) isDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDragging && button == 0) {
            offsetX += mouseX - lastMouseX;
            offsetY += mouseY - lastMouseY;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double oldZoom = zoomLevel;
        zoomLevel *= delta > 0 ? 1.2 : 1.0 / 1.2;
        zoomLevel = Math.max(0.1, Math.min(10.0, zoomLevel));

        int mapWidth = showPanel ? this.width - panelWidth : this.width;
        int centerX = mapWidth / 2;
        int centerY = this.height / 2;

        double mouseOffsetX = mouseX - centerX;
        double mouseOffsetY = mouseY - centerY;

        double scale = zoomLevel / oldZoom;
        offsetX = offsetX * scale - mouseOffsetX * (scale - 1);
        offsetY = offsetY * scale - mouseOffsetY * (scale - 1);

        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            if (showPanel) {
                showPanel = false;
                selectedMarker = null;
                rebuildChannelButtons();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── 频道创建 ──

    private void onCreateChannel() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ChannelCreateScreen(serverBlockPos, warehouseId));
        }
    }

    @Override
    public void removed() {
        if (mapConsumerAcquired && SimuMapManager.isAvailable()) {
            SimuMapManager.getInstance().releaseConsumer();
            mapConsumerAcquired = false;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    // ── 数据类 ──

    private static class ClientInfo {
        final UUID clientId;
        final BlockPos blockPos;
        final UUID cityId;
        final List<BlockPos> portPositions;
        String name = "";

        ClientInfo(UUID clientId, BlockPos blockPos, UUID cityId, List<BlockPos> portPositions) {
            this.clientId = clientId;
            this.blockPos = blockPos;
            this.cityId = cityId;
            this.portPositions = new ArrayList<>(portPositions);
        }

        boolean hasCustomName() { return name != null && !name.isBlank(); }

        String getDisplayName() {
            if (hasCustomName()) return name;
            return blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
        }
    }

    private record ChannelInfo(UUID channelId, String name, UUID targetClientId,
                               String direction, boolean enabled, List<String> itemNames) {}

    private record MarkerEntry(BlockPos pos, int color, String hoverText, UUID clientId, UUID warehouseId) {}
}
