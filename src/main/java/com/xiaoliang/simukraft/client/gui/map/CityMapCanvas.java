package com.xiaoliang.simukraft.client.gui.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.xiaoliang.simukraft.client.ClientCityChunkData;
import com.xiaoliang.simukraft.client.config.ClientConfig;
import com.xiaoliang.simukraft.client.gui.BuyChunkToast;
import com.xiaoliang.simukraft.client.map.MapRenderStyle;
import com.xiaoliang.simukraft.client.map.SimuMapManager;
import com.xiaoliang.simukraft.client.map.SimuMapRegion;
import com.xiaoliang.simukraft.integration.ModIntegrationManager;
import com.xiaoliang.simukraft.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 城市管理面板中的地图画布组件。
 * 支持多种渲染风格切换、区块购买认领、拖拽平移和滚轮缩放。
 */
public class CityMapCanvas extends AbstractWidget implements CityControlBoxesResponsePacket.CityControlBoxesReceiver {

    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 10.0;
    private static final double ZOOM_STEP = 0.5;
    private static final double CHUNK_COST = 10.0;
    private static final int MAP_SIDE_PADDING = 10;
    private static final int MAP_TOP_PADDING = 30;
    private static final int STYLE_BTN_WIDTH = 56;
    private static final int STYLE_BTN_HEIGHT = 16;
    private static final int STYLE_BTN_GAP = 4;
    private static final int CONTROL_BOX_BTN_WIDTH = 56;
    private static final int CONTROL_BOX_BTN_HEIGHT = 16;

    private double zoomLevel = 4.0;
    private double offsetX = 0;
    private double offsetY = 0;
    private BlockPos cityCorePos;
    private boolean isDragging = false;
    private double lastMouseX = 0;
    private double lastMouseY = 0;

    private int hoveredChunkX = Integer.MIN_VALUE;
    private int hoveredChunkY = Integer.MIN_VALUE;
    private MapMarker hoveredMarker = null;

    private boolean showConfirmWindow = false;
    private ChunkPos confirmChunkPos = null;
    @Nonnull
    private final List<ChunkPos> confirmChunkSelection = new ArrayList<>();
    private double confirmCost = 0.0;

    private boolean areaSelecting = false;
    private int selectionStartChunkX = Integer.MIN_VALUE;
    private int selectionStartChunkZ = Integer.MIN_VALUE;
    private int selectionEndChunkX = Integer.MIN_VALUE;
    private int selectionEndChunkZ = Integer.MIN_VALUE;

    private final List<BuyChunkToast> buyChunkToasts = new ArrayList<>();

    private int soundTimer = 0;
    private boolean playSoundOut = false;

    private final List<MapMarker> markers = new ArrayList<>();
    private final ClientCityChunkData clientCityChunkData = ClientCityChunkData.getInstance();

    private boolean showTerrain = true;
    private boolean showGrid = true;
    private boolean showAxisIndicator = true;
    private boolean mapConsumerAcquired = false;

    // 控制盒按钮悬停状态
    private boolean controlBoxBtnHovered = false;

    // 控制盒显示模式
    private boolean showControlBoxes = false;
    private UUID playerCityId = null;

    // 全局实例引用，用于 CityControlBoxesResponsePacket 通知
    private static volatile CityMapCanvas currentInstance = null;

    // 控制盒标记颜色
    private static final int INDUSTRIAL_COLOR = 0xFFFF8800;
    private static final int COMMERCIAL_COLOR = 0xFF0088FF;
    private static final int RESIDENTIAL_COLOR = 0xFF00FF00;
    private static final int OTHER_COLOR = 0xFFAAAAAA;

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(@Nullable String value) {
        return nn(value);
    }



    /**
     * 地图标记类
     */
    public static class MapMarker {
        private final BlockPos pos;
        private final int color;
        private final String hoverText;

        public MapMarker(BlockPos pos, int color, String hoverText) {
            this.pos = pos;
            this.color = color;
            this.hoverText = hoverText;
        }

        public BlockPos getPos() {
            return pos;
        }

        public int getColor() {
            return color;
        }

        public String getHoverText() {
            return hoverText;
        }
    }

    public CityMapCanvas(int x, int y, int width, int height, BlockPos cityCorePos) {
        super(x, y, width, height, Component.empty());
        this.cityCorePos = cityCorePos;

        BuyChunkResponsePacket.BuyChunkResultEvent.INSTANCE.register(this::onBuyChunkResult);
        ensureMapManagerReady();

        // 注册全局实例
        currentInstance = this;

        if (cityCorePos != null) {
            addMarker(new MapMarker(cityCorePos, 0xFF0000FF,
                    String.format("城市核心: (%d, %d, %d)", cityCorePos.getX(), cityCorePos.getY(), cityCorePos.getZ())));
            centerMapOnCityCore();
            requestCityChunks();
        }
    }

    /**
     * 确保 SimuMapManager 已初始化并注册为活跃消费者。
     */
    private void ensureMapManagerReady() {
        SimuMapManager mgr = SimuMapManager.getInstance();
        if (!mapConsumerAcquired) {
            mgr.acquireConsumer();
            mapConsumerAcquired = true;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            int pcx = player.chunkPosition().x;
            int pcz = player.chunkPosition().z;
            mgr.forceScanArea(pcx, pcz, 8);
        }
    }

    /**
     * 释放地图管理器消费者。应在包含此 Widget 的 Screen 关闭时调用。
     */
    public void releaseMapConsumer() {
        if (mapConsumerAcquired && SimuMapManager.isAvailable()) {
            SimuMapManager.getInstance().releaseConsumer();
            mapConsumerAcquired = false;
        }
        // 清除全局实例
        if (currentInstance == this) {
            currentInstance = null;
        }
    }

    private void centerMapOnCityCore() {
        if (cityCorePos != null) {
            int coreChunkX = cityCorePos.getX() >> 4;
            int coreChunkZ = cityCorePos.getZ() >> 4;
            double chunkSize = 16 * zoomLevel;
            this.offsetX = -coreChunkX * chunkSize;
            this.offsetY = -coreChunkZ * chunkSize;
        }
    }

    /**
     * 处理购买区块结果
     */
    private void onBuyChunkResult(boolean success, long chunkPosLong, double cost, String errorMessage) {
        ChunkPos chunkPos = new ChunkPos(chunkPosLong);
        addBuyChunkToast(chunkPos, cost, success, errorMessage);
    }

    /**
     * 请求城市区块数据
     */
    private void requestCityChunks() {
        if (cityCorePos != null) {
            GetCityChunksPacket packet = new GetCityChunksPacket(cityCorePos);
            NetworkManager.INSTANCE.sendToServer(packet);
        }
    }

    /**
     * 添加标记
     */
    public void addMarker(MapMarker marker) {
        markers.add(marker);
    }

    /**
     * 移除标记
     */
    public boolean removeMarker(MapMarker marker) {
        return markers.remove(marker);
    }

    /**
     * 清空所有标记
     */
    public void clearMarkers() {
        markers.clear();
    }

    /**
     * 获取所有标记
     */
    public List<MapMarker> getMarkers() {
        return new ArrayList<>(markers);
    }

    /**
     * 获取当前实例（用于 CityControlBoxesResponsePacket 通知）
     */
    public static CityMapCanvas getCurrentInstance() {
        return currentInstance;
    }

    @Override
    public void renderWidget(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80000000);

        int mapStartX = getX() + MAP_SIDE_PADDING;
        int mapStartY = getY() + MAP_TOP_PADDING;
        int mapEndX = getX() + width - MAP_SIDE_PADDING;
        int mapEndY = getY() + height - MAP_SIDE_PADDING;
        int mapWidth = mapEndX - mapStartX;
        int mapHeight = mapEndY - mapStartY;

        guiGraphics.fill(mapStartX - 2, mapStartY - 2, mapEndX + 2, mapEndY + 2, 0xFFFFFFFF);
        guiGraphics.fill(mapStartX - 1, mapStartY - 1, mapEndX + 1, mapEndY + 1, 0x80000000);

        renderMap(guiGraphics, mapStartX, mapStartY, mapWidth, mapHeight, mouseX, mouseY);

        if (!showConfirmWindow) {
            renderHoverInfo(guiGraphics, mouseX, mouseY);
        }

        if (showConfirmWindow && confirmChunkPos != null) {
            renderConfirmWindow(guiGraphics, mouseX, mouseY);
        }

        updateBuyChunkToasts();
        renderBuyChunkToasts(guiGraphics, mouseX, mouseY, partialTicks);
        renderStyleSwitcher(guiGraphics, mouseX, mouseY);

        if (playSoundOut) {
            soundTimer++;
            if (soundTimer >= 100) {
                Minecraft.getInstance().getSoundManager()
                        .play(nn(SimpleSoundInstance.forUI(nn(SoundEvents.UI_TOAST_OUT), 1.0F)));
                playSoundOut = false;
                soundTimer = 0;
            }
        }
    }

    /**
     * 渲染地图风格切换按钮组。
     */
    private void renderStyleSwitcher(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        MapRenderStyle current = ClientConfig.getMapRenderStyle();
        MapRenderStyle[] styles = MapRenderStyle.values();

        int totalWidth = styles.length * STYLE_BTN_WIDTH + (styles.length - 1) * STYLE_BTN_GAP;
        int btnY = getY() + 6;
        int btnStartX = getX() + width - totalWidth - 10;

        // 先渲染控制盒按钮（在风格按钮组左边）
        int controlBoxBtnX = btnStartX - CONTROL_BOX_BTN_WIDTH - STYLE_BTN_GAP;
        controlBoxBtnHovered = mouseX >= controlBoxBtnX && mouseX <= controlBoxBtnX + CONTROL_BOX_BTN_WIDTH
                && mouseY >= btnY && mouseY <= btnY + CONTROL_BOX_BTN_HEIGHT;

        // 橙色主题
        int controlBoxBgColor = showControlBoxes ? 0xFFFF8800 : (controlBoxBtnHovered ? 0xFFCC6600 : 0xFF222233);
        int controlBoxBorderColor = showControlBoxes ? 0xFFFFAA00 : 0xFF555577;

        guiGraphics.fill(controlBoxBtnX, btnY, controlBoxBtnX + CONTROL_BOX_BTN_WIDTH, btnY + CONTROL_BOX_BTN_HEIGHT, controlBoxBorderColor);
        guiGraphics.fill(controlBoxBtnX + 1, btnY + 1, controlBoxBtnX + CONTROL_BOX_BTN_WIDTH - 1, btnY + CONTROL_BOX_BTN_HEIGHT - 1, controlBoxBgColor);

        String controlBoxLabel = "控制盒";
        int controlBoxTextColor = showControlBoxes ? 0xFFFFFF : (controlBoxBtnHovered ? 0xDDDDFF : 0xAAAAAA);
        Minecraft minecraft = Minecraft.getInstance();
        int controlBoxLabelW = nn(minecraft.font).width(controlBoxLabel);
        guiGraphics.drawString(nn(minecraft.font), safeString(controlBoxLabel),
                controlBoxBtnX + (CONTROL_BOX_BTN_WIDTH - controlBoxLabelW) / 2,
                btnY + (CONTROL_BOX_BTN_HEIGHT - nn(minecraft.font).lineHeight) / 2 + 1,
                controlBoxTextColor);

        for (int i = 0; i < styles.length; i++) {
            MapRenderStyle style = styles[i];
            int btnX = btnStartX + i * (STYLE_BTN_WIDTH + STYLE_BTN_GAP);
            boolean hovered = mouseX >= btnX && mouseX <= btnX + STYLE_BTN_WIDTH
                    && mouseY >= btnY && mouseY <= btnY + STYLE_BTN_HEIGHT;
            boolean selected = style == current;

            int bgColor = selected ? 0xFF2266AA : (hovered ? 0xFF444466 : 0xFF222233);
            int borderColor = selected ? 0xFF44AAFF : 0xFF555577;

            guiGraphics.fill(btnX, btnY, btnX + STYLE_BTN_WIDTH, btnY + STYLE_BTN_HEIGHT, borderColor);
            guiGraphics.fill(btnX + 1, btnY + 1, btnX + STYLE_BTN_WIDTH - 1, btnY + STYLE_BTN_HEIGHT - 1, bgColor);

            int textColor = selected ? 0xFFFFFF : (hovered ? 0xDDDDFF : 0xAAAAAA);
            String label = safeString(style.name());
            int labelW = nn(minecraft.font).width(label);
            guiGraphics.drawString(nn(minecraft.font), label,
                    btnX + (STYLE_BTN_WIDTH - labelW) / 2,
                    btnY + (STYLE_BTN_HEIGHT - nn(minecraft.font).lineHeight) / 2 + 1,
                    textColor);
        }
    }

    /**
     * 处理风格切换按钮组的点击事件
     */
    private boolean handleStyleSwitcherClick(double mouseX, double mouseY) {
        MapRenderStyle[] styles = MapRenderStyle.values();
        int totalWidth = styles.length * STYLE_BTN_WIDTH + (styles.length - 1) * STYLE_BTN_GAP;
        int btnY = getY() + 6;
        int btnStartX = getX() + width - totalWidth - 10;

        // 检查控制盒按钮点击（在风格按钮组左边）
        int controlBoxBtnX = btnStartX - CONTROL_BOX_BTN_WIDTH - STYLE_BTN_GAP;
        if (mouseX >= controlBoxBtnX && mouseX <= controlBoxBtnX + CONTROL_BOX_BTN_WIDTH
                && mouseY >= btnY && mouseY <= btnY + CONTROL_BOX_BTN_HEIGHT) {
            // 切换控制盒显示模式
            toggleControlBoxMode();
            return true;
        }

        for (int i = 0; i < styles.length; i++) {
            int btnX = btnStartX + i * (STYLE_BTN_WIDTH + STYLE_BTN_GAP);
            if (mouseX >= btnX && mouseX <= btnX + STYLE_BTN_WIDTH
                    && mouseY >= btnY && mouseY <= btnY + STYLE_BTN_HEIGHT) {
                ClientConfig.setMapRenderStyle(styles[i]);
                Minecraft.getInstance().getSoundManager()
                        .play(nn(SimpleSoundInstance.forUI(nn(SoundEvents.UI_BUTTON_CLICK), 1.0F)));
                return true;
            }
        }
        return false;
    }

    /**
     * 切换控制盒显示模式
     */
    private void toggleControlBoxMode() {
        showControlBoxes = !showControlBoxes;
        Minecraft.getInstance().getSoundManager()
                .play(nn(SimpleSoundInstance.forUI(nn(SoundEvents.UI_BUTTON_CLICK), 1.0F)));

        if (showControlBoxes) {
            loadControlBoxData();
        } else {
            // 清除控制盒标记（通过颜色识别）
            markers.removeIf(marker ->
                marker.getColor() == INDUSTRIAL_COLOR ||
                marker.getColor() == COMMERCIAL_COLOR ||
                marker.getColor() == RESIDENTIAL_COLOR ||
                marker.getColor() == OTHER_COLOR
            );
        }
    }

    /**
     * 加载控制盒数据
     * 使用网络请求从服务端获取数据
     */
    private void loadControlBoxData() {
        // 获取玩家所属的城市ID
        playerCityId = clientCityChunkData.getCityId();
        if (playerCityId == null) {
            return;
        }

        // 发送网络请求获取控制盒数据
        NetworkManager.INSTANCE.sendToServer(new RequestCityControlBoxesPacket(playerCityId));
    }

    /**
     * 接收服务端返回的控制盒数据
     */
    @Override
    public void onCityControlBoxesReceived(List<CityControlBoxesResponsePacket.ControlBoxData> controlBoxes) {
        for (CityControlBoxesResponsePacket.ControlBoxData data : controlBoxes) {
            int color;
            String typeLabel;
            String defaultName;

            switch (data.type) {
                case "industrial":
                    color = INDUSTRIAL_COLOR;
                    typeLabel = "工业";
                    defaultName = "工业建筑";
                    break;
                case "commercial":
                    color = COMMERCIAL_COLOR;
                    typeLabel = "商业";
                    defaultName = "商业建筑";
                    break;
                case "residential":
                    color = RESIDENTIAL_COLOR;
                    typeLabel = "住宅";
                    defaultName = "住宅建筑";
                    break;
                default:
                    color = OTHER_COLOR;
                    typeLabel = "其他";
                    defaultName = "其他建筑";
                    break;
            }

            String name = data.buildingName != null && !data.buildingName.isEmpty()
                    ? data.buildingName
                    : defaultName;
            addMarker(new MapMarker(
                data.position,
                color,
                String.format("[%s] %s (%d, %d, %d)", typeLabel, name, data.position.getX(), data.position.getY(), data.position.getZ())
            ));
        }
    }

    /**
     * 更新购买区块弹窗
     */
    private void updateBuyChunkToasts() {
        for (BuyChunkToast toast : buyChunkToasts) {
            toast.update();
        }
        buyChunkToasts.removeIf(toast -> !toast.isVisible());
    }

    /**
     * 渲染购买区块弹窗
     */
    private void renderBuyChunkToasts(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        for (BuyChunkToast toast : buyChunkToasts) {
            toast.render(nn(guiGraphics), mouseX, mouseY, partialTicks);
        }
    }

    /**
     * 添加购买区块弹窗（带结果）
     */
    private void addBuyChunkToast(ChunkPos chunkPos, double cost, boolean success, String errorMessage) {
        int x = this.getX() + this.width - 210;
        int y = this.getY() + this.height - 60;

        boolean found = false;
        for (BuyChunkToast toast : buyChunkToasts) {
            if (toast.isVisible()) {
                toast.resetAnimation();
                found = true;
                break;
            }
        }

        if (!found) {
            BuyChunkToast toast = new BuyChunkToast(x, y, chunkPos, cost, success, errorMessage);
            buyChunkToasts.add(toast);
        }
    }

    private void renderMap(GuiGraphics guiGraphics, int startX, int startY, int width, int height, int mouseX, int mouseY) {
        int centerX = startX + width / 2;
        int centerY = startY + height / 2;
        double chunkSize = 16 * zoomLevel;

        int visibleChunksX = (int) Math.ceil(width / chunkSize) + 2;
        int visibleChunksY = (int) Math.ceil(height / chunkSize) + 2;

        int startChunkX = (int) Math.floor((-offsetX - width / 2.0) / chunkSize);
        int startChunkZ = (int) Math.floor((-offsetY - height / 2.0) / chunkSize);
        int endChunkX = startChunkX + visibleChunksX;
        int endChunkZ = startChunkZ + visibleChunksY;

        hoveredChunkX = Integer.MIN_VALUE;
        hoveredChunkY = Integer.MIN_VALUE;
        hoveredMarker = null;

        updateHoveredChunk(startX, startY, width, height, centerX, centerY, chunkSize, mouseX, mouseY);

        if (showTerrain) {
            renderWorldMapTerrain(guiGraphics, startX, startY, width, height, centerX, centerY,
                    chunkSize, startChunkX, startChunkZ, visibleChunksX, visibleChunksY);
        } else {
            guiGraphics.fill(startX, startY, startX + width, startY + height, 0xFF404040);
        }

        if (showGrid) {
            renderGridOverlay(guiGraphics, startX, startY, width, height, centerX, centerY, chunkSize, startChunkX, startChunkZ);
        }

        renderOwnedChunkBorders(guiGraphics, startX, startY, width, height, centerX, centerY, chunkSize,
                startChunkX, endChunkX, startChunkZ, endChunkZ);
        renderAreaSelection(guiGraphics, startX, startY, width, height, centerX, centerY, chunkSize);
        renderHoveredChunkHighlight(guiGraphics, startX, startY, width, height, centerX, centerY, chunkSize);

        if (showAxisIndicator) {
            renderAxisIndicator(guiGraphics, startX, startY, width, height, centerX, centerY, chunkSize);
        }

        renderMarkers(guiGraphics, startX, startY, width, height, centerX, centerY, chunkSize, mouseX, mouseY);
    }

    private void updateHoveredChunk(int startX, int startY, int width, int height,
                                    int centerX, int centerY, double chunkSize,
                                    int mouseX, int mouseY) {
        if (mouseX < startX || mouseX > startX + width || mouseY < startY || mouseY > startY + height) {
            return;
        }

        hoveredChunkX = (int) Math.floor((mouseX - centerX - offsetX) / chunkSize);
        hoveredChunkY = (int) Math.floor((mouseY - centerY - offsetY) / chunkSize);
    }

    private void renderGridOverlay(GuiGraphics guiGraphics, int startX, int startY, int width, int height,
                                   int centerX, int centerY, double chunkSize,
                                   int startChunkX, int startChunkZ) {
        int gridColor = 0x40000000;
        int verticalLines = (int) Math.ceil(width / chunkSize) + 2;
        int horizontalLines = (int) Math.ceil(height / chunkSize) + 2;

        for (int i = 0; i <= verticalLines; i++) {
            double lineX = centerX + offsetX + (startChunkX + i) * chunkSize;
            int drawX = (int) Math.round(lineX);
            if (drawX >= startX && drawX <= startX + width) {
                guiGraphics.fill(drawX, startY, drawX + 1, startY + height, gridColor);
            }
        }

        for (int i = 0; i <= horizontalLines; i++) {
            double lineY = centerY + offsetY + (startChunkZ + i) * chunkSize;
            int drawY = (int) Math.round(lineY);
            if (drawY >= startY && drawY <= startY + height) {
                guiGraphics.fill(startX, drawY, startX + width, drawY + 1, gridColor);
            }
        }
    }

    private void renderOwnedChunkBorders(GuiGraphics guiGraphics, int startX, int startY, int width, int height,
                                         int centerX, int centerY, double chunkSize,
                                         int startChunkX, int endChunkX, int startChunkZ, int endChunkZ) {
        for (int chunkX = startChunkX; chunkX <= endChunkX; chunkX++) {
            for (int chunkZ = startChunkZ; chunkZ <= endChunkZ; chunkZ++) {
                long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
                if (!clientCityChunkData.isChunkOwned(chunkLong)) {
                    continue;
                }

                boolean isCurrentCityChunk = clientCityChunkData.isChunkInCurrentCity(chunkLong);
                int borderColor = isCurrentCityChunk ? 0xCC00DD00 : 0xCCDD0000;
                drawChunkOwnershipBorder(guiGraphics, startX, startY, width, height, centerX, centerY,
                        chunkSize, chunkX, chunkZ, borderColor);
            }
        }
    }

    private void drawChunkOwnershipBorder(GuiGraphics guiGraphics, int startX, int startY, int width, int height,
                                          int centerX, int centerY, double chunkSize,
                                          int chunkX, int chunkZ, int borderColor) {
        double screenX = centerX + offsetX + chunkX * chunkSize;
        double screenY = centerY + offsetY + chunkZ * chunkSize;

        int drawX = Math.max((int) Math.floor(screenX), startX);
        int drawY = Math.max((int) Math.floor(screenY), startY);
        int drawWidth = Math.min((int) Math.ceil(screenX + chunkSize), startX + width) - drawX;
        int drawHeight = Math.min((int) Math.ceil(screenY + chunkSize), startY + height) - drawY;
        if (drawWidth <= 0 || drawHeight <= 0) {
            return;
        }

        int borderThickness = Math.max(1, Math.min(2, drawWidth / 4));

        if (!clientCityChunkData.isChunkOwned(ChunkPos.asLong(chunkX, chunkZ - 1))) {
            guiGraphics.fill(drawX, drawY, drawX + drawWidth, drawY + borderThickness, borderColor);
        }
        if (!clientCityChunkData.isChunkOwned(ChunkPos.asLong(chunkX, chunkZ + 1))) {
            guiGraphics.fill(drawX, drawY + drawHeight - borderThickness, drawX + drawWidth, drawY + drawHeight, borderColor);
        }
        if (!clientCityChunkData.isChunkOwned(ChunkPos.asLong(chunkX - 1, chunkZ))) {
            guiGraphics.fill(drawX, drawY, drawX + borderThickness, drawY + drawHeight, borderColor);
        }
        if (!clientCityChunkData.isChunkOwned(ChunkPos.asLong(chunkX + 1, chunkZ))) {
            guiGraphics.fill(drawX + drawWidth - borderThickness, drawY, drawX + drawWidth, drawY + drawHeight, borderColor);
        }
    }

    private void renderHoveredChunkHighlight(GuiGraphics guiGraphics, int startX, int startY, int width, int height,
                                             int centerX, int centerY, double chunkSize) {
        if (hoveredChunkX == Integer.MIN_VALUE || hoveredChunkY == Integer.MIN_VALUE) {
            return;
        }

        double screenX = centerX + offsetX + hoveredChunkX * chunkSize;
        double screenY = centerY + offsetY + hoveredChunkY * chunkSize;
        int drawX = Math.max((int) Math.floor(screenX), startX);
        int drawY = Math.max((int) Math.floor(screenY), startY);
        int drawWidth = Math.min((int) Math.ceil(screenX + chunkSize), startX + width) - drawX;
        int drawHeight = Math.min((int) Math.ceil(screenY + chunkSize), startY + height) - drawY;

        if (drawWidth > 2 && drawHeight > 2) {
            guiGraphics.fill(drawX + 1, drawY + 1, drawX + drawWidth - 1, drawY + drawHeight - 1, 0x40FFFFFF);
        }
    }

    private void renderWorldMapTerrain(GuiGraphics guiGraphics, int startX, int startY,
                                       int width, int height,
                                       int centerX, int centerY, double chunkSize,
                                       int startChunkX, int startChunkZ,
                                       int visibleChunksX, int visibleChunksY) {
        MapRenderStyle style = ClientConfig.getMapRenderStyle();

        if (style == MapRenderStyle.XAERO && ModIntegrationManager.isXaeroWorldMapPresent()) {
            try {
                boolean rendered = com.xiaoliang.simukraft.integration.xaero.XaeroEnhancedRenderer
                        .renderXaeroTerrain(guiGraphics, startX, startY, width, height,
                                centerX, centerY, offsetX, offsetY, zoomLevel,
                                startChunkX, startChunkZ, visibleChunksX, visibleChunksY);
                if (rendered) return;
            } catch (Throwable ignored) {
            }
        }

        if (style == MapRenderStyle.FTB && ModIntegrationManager.isFTBChunksPresent()) {
            try {
                boolean rendered = com.xiaoliang.simukraft.integration.ftb.FTBEnhancedRenderer
                        .renderFTBTerrain(guiGraphics, startX, startY, width, height,
                                centerX, centerY, offsetX, offsetY, zoomLevel,
                                startChunkX, startChunkZ, visibleChunksX, visibleChunksY);
                if (rendered) return;
            } catch (Throwable ignored) {
            }
        }

        if (!SimuMapManager.isAvailable()) return;

        SimuMapManager manager = SimuMapManager.getInstance();

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
        int scissorY = (int) ((Minecraft.getInstance().getWindow().getScreenHeight()
                - (startY + height) * guiScale));
        int scissorW = (int) (width * guiScale);
        int scissorH = (int) (height * guiScale);

        com.mojang.blaze3d.platform.GlStateManager._enableScissorTest();
        com.mojang.blaze3d.platform.GlStateManager._scissorBox(scissorX, scissorY, scissorW, scissorH);
        try {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

            for (int regX = minRegX; regX <= maxRegX; regX++) {
                for (int regZ = minRegZ; regZ <= maxRegZ; regZ++) {
                    SimuMapRegion region = manager.getRegion(regX, regZ);
                    if (region == null || !region.hasData()) continue;

                    int textureId = region.getTextureId();
                    if (textureId == -1) continue;

                    double regionWorldX = regX * 512.0;
                    double regionWorldZ = regZ * 512.0;

                    double screenLeft = centerX + offsetX + regionWorldX * zoomLevel;
                    double screenTop = centerY + offsetY + regionWorldZ * zoomLevel;
                    double regionPixels = 512.0 * zoomLevel;

                    if (screenLeft + regionPixels < startX || screenLeft > startX + width
                            || screenTop + regionPixels < startY || screenTop > startY + height) {
                        continue;
                    }

                    RenderSystem.setShaderTexture(0, textureId);
                    RenderSystem.setShader(GameRenderer::getPositionTexShader);
                    RenderSystem.enableBlend();

                    Matrix4f matrix = nn(guiGraphics.pose().last().pose());
                    BufferBuilder buf = Tesselator.getInstance().getBuilder();
                    buf.begin(nn(VertexFormat.Mode.QUADS), nn(DefaultVertexFormat.POSITION_TEX));

                    float x0 = (float) screenLeft;
                    float y0 = (float) screenTop;
                    float x1 = (float) (screenLeft + regionPixels);
                    float y1 = (float) (screenTop + regionPixels);

                    buf.vertex(matrix, x0, y1, 0).uv(0, 1).endVertex();
                    buf.vertex(matrix, x1, y1, 0).uv(1, 1).endVertex();
                    buf.vertex(matrix, x1, y0, 0).uv(1, 0).endVertex();
                    buf.vertex(matrix, x0, y0, 0).uv(0, 0).endVertex();

                    BufferBuilder.RenderedBuffer renderedBuffer = nn(buf.end());
                    BufferUploader.drawWithShader(renderedBuffer);
                    RenderSystem.disableBlend();
                }
            }

            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        } finally {
            com.mojang.blaze3d.platform.GlStateManager._disableScissorTest();
        }
    }

    private void renderAxisIndicator(GuiGraphics guiGraphics, int startX, int startY, int width, int height,
                                     int centerX, int centerY, double chunkSize) {
        double zeroX = centerX + offsetX;
        double zeroZ = centerY + offsetY;
        int axisColor = 0xCCFFFFFF;
        int labelBgColor = 0xCC000000;
        Minecraft minecraft = Minecraft.getInstance();
        if (zeroX >= startX && zeroX <= startX + width) {
            guiGraphics.fill((int) zeroX - 1, startY, (int) zeroX + 1, startY + height, axisColor);
            String xLabel = "X=0";
            int labelWidth = nn(minecraft.font).width(xLabel);
            guiGraphics.fill((int) zeroX - labelWidth / 2 - 2, startY + 2, (int) zeroX + labelWidth / 2 + 2, startY + 14, labelBgColor);
            guiGraphics.drawString(nn(minecraft.font), xLabel, (int) zeroX - labelWidth / 2, startY + 4, 0xFFFFFF);
        }
        if (zeroZ >= startY && zeroZ <= startY + height) {
            guiGraphics.fill(startX, (int) zeroZ - 1, startX + width, (int) zeroZ + 1, axisColor);
            String zLabel = "Z=0";
            int labelWidth = nn(minecraft.font).width(zLabel);
            guiGraphics.fill(startX + 2, (int) zeroZ - 6, startX + labelWidth + 6, (int) zeroZ + 6, labelBgColor);
            guiGraphics.drawString(nn(minecraft.font), zLabel, startX + 4, (int) zeroZ - 4, 0xFFFFFF);
        }
        String zoomText = safeString(String.format("%.1fx", zoomLevel));
        int zoomWidth = nn(minecraft.font).width(zoomText);
        guiGraphics.fill(startX + width - zoomWidth - 8, startY + height - 16, startX + width - 2, startY + height - 2, labelBgColor);
        guiGraphics.drawString(nn(minecraft.font), zoomText, startX + width - zoomWidth - 4, startY + height - 12, 0xFFFFFF);
    }

    private void renderMarkers(GuiGraphics guiGraphics, int startX, int startY, int width, int height,
                               int centerX, int centerY, double chunkSize, int mouseX, int mouseY) {
        for (MapMarker marker : markers) {
            int markerChunkX = marker.getPos().getX() >> 4;
            int markerChunkZ = marker.getPos().getZ() >> 4;

            double markerScreenX = centerX + offsetX + markerChunkX * chunkSize + (marker.getPos().getX() & 15) * zoomLevel;
            double markerScreenY = centerY + offsetY + markerChunkZ * chunkSize + (marker.getPos().getZ() & 15) * zoomLevel;

            if (markerScreenX >= startX && markerScreenX <= startX + width &&
                markerScreenY >= startY && markerScreenY <= startY + height) {
                guiGraphics.fill((int) (markerScreenX - 3), (int) (markerScreenY - 3), (int) (markerScreenX + 3), (int) (markerScreenY + 3), marker.getColor());
                guiGraphics.fill((int) (markerScreenX - 2), (int) (markerScreenY - 2), (int) (markerScreenX + 2), (int) (markerScreenY + 2), 0xFF4080FF);

                if (mouseX >= markerScreenX - 3 && mouseX <= markerScreenX + 3 &&
                    mouseY >= markerScreenY - 3 && mouseY <= markerScreenY + 3) {
                    hoveredMarker = marker;
                }
            }
        }
    }

    private void renderHoverInfo(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (hoveredMarker != null) {
            Minecraft minecraft = Minecraft.getInstance();
            String hoverText = safeString(hoveredMarker.getHoverText());
            int textWidth = nn(minecraft.font).width(hoverText);
            int textHeight = nn(minecraft.font).lineHeight;
            int boxX = mouseX + 10;
            int boxY = mouseY - textHeight - 5;

            guiGraphics.fill(boxX - 2, boxY - 2, boxX + textWidth + 2, boxY + textHeight + 2, 0xEE222222);
            guiGraphics.drawString(nn(minecraft.font), hoverText, boxX, boxY, 0xFFFFFF);
        } else if (hoveredChunkX != Integer.MIN_VALUE && hoveredChunkY != Integer.MIN_VALUE) {
            Minecraft minecraft = Minecraft.getInstance();
            List<String> lines = new ArrayList<>();
            lines.add(Component.translatable("gui.city_map.chunk_coordinate", hoveredChunkX, hoveredChunkY).getString());

            int blockStartX = hoveredChunkX * 16;
            int blockEndX = blockStartX + 15;
            int blockStartZ = hoveredChunkY * 16;
            int blockEndZ = blockStartZ + 15;
            lines.add(Component.translatable("gui.city_map.block_range", blockStartX, blockEndX, blockStartZ, blockEndZ).getString());

            long chunkLong = ChunkPos.asLong(hoveredChunkX, hoveredChunkY);
            boolean isOwned = clientCityChunkData.isChunkOwned(chunkLong);
            boolean isCurrentCity = clientCityChunkData.isChunkInCurrentCity(chunkLong);
            if (isOwned) {
                if (isCurrentCity) {
                    lines.add(Component.translatable("gui.city_map.owned_by_current").getString());
                } else {
                    lines.add(Component.translatable("gui.city_map.owned_by_other").getString());
                }
            } else {
                lines.add(Component.translatable("gui.city_map.available").getString());
            }

            int lineHeight = nn(minecraft.font).lineHeight;
            int maxWidth = 0;
            for (String line : lines) {
                int w = nn(minecraft.font).width(safeString(line));
                if (w > maxWidth) maxWidth = w;
            }
            int boxHeight = lines.size() * (lineHeight + 2) + 4;

            int boxX = mouseX + 10;
            int boxY = mouseY - boxHeight - 5;

            if (boxX + maxWidth + 4 > this.getX() + this.width) {
                boxX = mouseX - maxWidth - 14;
            }
            if (boxY < this.getY()) {
                boxY = mouseY + 15;
            }

            guiGraphics.fill(boxX - 2, boxY - 2, boxX + maxWidth + 6, boxY + boxHeight + 2, 0xCC000000);
            guiGraphics.fill(boxX - 1, boxY - 1, boxX + maxWidth + 5, boxY + boxHeight + 1, 0xEE222222);

            int textY = boxY + 2;
            for (int i = 0; i < lines.size(); i++) {
                int color = i == 0 ? 0xFFFF00 : 0xFFFFFF;
                if (i == lines.size() - 1) {
                    if (isOwned && isCurrentCity) {
                        color = 0x55FF55;
                    } else if (isOwned) {
                        color = 0xFF5555;
                    } else {
                        color = 0x55FFFF;
                    }
                }
                guiGraphics.drawString(nn(minecraft.font), safeString(lines.get(i)), boxX + 2, textY, color);
                textY += lineHeight + 2;
            }
        }

        if (areaSelecting) {
            ChunkSelectionBounds selectionBounds = getCurrentSelectionBounds();
            if (selectionBounds != null) {
                String selectionText = safeString(Component.translatable(
                        "gui.city_map.selection_status",
                        selectionBounds.minChunkX(),
                        selectionBounds.minChunkZ(),
                        selectionBounds.maxChunkX(),
                        selectionBounds.maxChunkZ()
                ).getString());
                Minecraft minecraft = Minecraft.getInstance();
                int selectionWidth = nn(minecraft.font).width(selectionText);
                int boxX = this.getX() + this.width - selectionWidth - 14;
                int boxY = this.getY() + this.height - 28;
                guiGraphics.fill(boxX - 2, boxY - 2, boxX + selectionWidth + 4, boxY + nn(minecraft.font).lineHeight + 2, 0xCC000000);
                guiGraphics.drawString(nn(minecraft.font), selectionText, boxX, boxY, 0x55FFFF);
            }
        }
    }

    /**
     * 绘制确认购买弹窗
     */
    private void renderConfirmWindow(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int windowWidth = 320;
        int windowHeight = 180;
        int windowX = (this.getX() + this.width / 2) - (windowWidth / 2);
        int windowY = (this.getY() + this.height / 2) - (windowHeight / 2);

        guiGraphics.fill(windowX - 2, windowY - 2, windowX + windowWidth + 2, windowY + windowHeight + 2, 0xFF000000);
        guiGraphics.fillGradient(windowX, windowY, windowX + windowWidth, windowY + windowHeight, 0xFF1a1a2e, 0xFF16213e);

        guiGraphics.fill(windowX, windowY, windowX + windowWidth, windowY + 24, 0xFF0f3460);
        Component title = Component.translatable("gui.confirm_buy_chunk.title");
        Minecraft minecraft = Minecraft.getInstance();
        int titleWidth = nn(minecraft.font).width(safeString(title.getString()));
        guiGraphics.drawString(nn(minecraft.font), safeString(title.getString()),
                windowX + (windowWidth - titleWidth) / 2, windowY + 8, 0xFFFFFF);

        int infoY = windowY + 32;
        int lineHeight = 14;

        if (confirmChunkSelection.size() > 1) {
            ChunkSelectionBounds bounds = nn(getConfirmSelectionBounds());
            String selectionCount = safeString(Component.translatable(
                    "gui.confirm_buy_chunk.selection_count",
                    confirmChunkSelection.size()
            ).getString());
            guiGraphics.drawString(nn(minecraft.font), selectionCount, windowX + 15, infoY, 0xFFFF00);

            String selectionRange = safeString(Component.translatable(
                    "gui.confirm_buy_chunk.selection_range",
                    bounds.minChunkX(),
                    bounds.minChunkZ(),
                    bounds.maxChunkX(),
                    bounds.maxChunkZ()
            ).getString());
            guiGraphics.drawString(nn(minecraft.font), selectionRange, windowX + 15, infoY + lineHeight, 0xAAAAAA);
        } else {
            ChunkPos chunkPos = nn(confirmChunkPos);
            String chunkCoords = safeString(Component.translatable("gui.city_map.chunk_coordinate", chunkPos.x, chunkPos.z).getString());
            guiGraphics.drawString(nn(minecraft.font), chunkCoords, windowX + 15, infoY, 0xFFFF00);

            int blockStartX = chunkPos.x * 16;
            int blockEndX = blockStartX + 15;
            int blockStartZ = chunkPos.z * 16;
            int blockEndZ = blockStartZ + 15;
            String blockRange = safeString(Component.translatable("gui.city_map.block_range", blockStartX, blockEndX, blockStartZ, blockEndZ).getString());
            guiGraphics.drawString(nn(minecraft.font), blockRange, windowX + 15, infoY + lineHeight, 0xAAAAAA);
        }

        guiGraphics.fill(windowX + 10, infoY + lineHeight * 2 + 5, windowX + windowWidth - 10, infoY + lineHeight * 2 + 6, 0xFF444444);

        int costY = infoY + lineHeight * 3 + 15;
        String costLabel = safeString(Component.translatable("gui.confirm_buy_chunk.cost_label").getString());
        guiGraphics.drawString(nn(minecraft.font), costLabel, windowX + 15, costY, 0xFFFFFF);

        String costText = safeString(String.format("$%.2f", confirmCost));
        guiGraphics.drawString(nn(minecraft.font), costText,
                windowX + 15 + nn(minecraft.font).width(costLabel) + 5, costY, 0x55FF55);

        int buttonWidth = 100;
        int buttonHeight = 24;
        int buttonY = windowY + windowHeight - 40;

        int confirmButtonX = windowX + (windowWidth / 2) - buttonWidth - 15;
        boolean hoverConfirm = mouseX >= confirmButtonX && mouseX <= confirmButtonX + buttonWidth &&
                              mouseY >= buttonY && mouseY <= buttonY + buttonHeight;
        int confirmBg = hoverConfirm ? 0xFF00AA00 : 0xFF008800;
        guiGraphics.fill(confirmButtonX, buttonY, confirmButtonX + buttonWidth, buttonY + buttonHeight, confirmBg);
        guiGraphics.fill(confirmButtonX + 1, buttonY + 1, confirmButtonX + buttonWidth - 1, buttonY + buttonHeight - 1,
                        hoverConfirm ? 0xFF00CC00 : 0xFF00AA00);
        Component confirmTextComponent = Component.translatable("gui.confirm_buy_chunk.confirm");
        String confirmText = safeString(confirmTextComponent.getString());
        int confirmTextWidth = nn(minecraft.font).width(confirmText);
        guiGraphics.drawString(nn(minecraft.font), confirmText,
                              confirmButtonX + (buttonWidth - confirmTextWidth) / 2, buttonY + 8, 0xFFFFFF);

        int cancelButtonX = windowX + (windowWidth / 2) + 15;
        boolean hoverCancel = mouseX >= cancelButtonX && mouseX <= cancelButtonX + buttonWidth &&
                             mouseY >= buttonY && mouseY <= buttonY + buttonHeight;
        int cancelBg = hoverCancel ? 0xFFAA0000 : 0xFF880000;
        guiGraphics.fill(cancelButtonX, buttonY, cancelButtonX + buttonWidth, buttonY + buttonHeight, cancelBg);
        guiGraphics.fill(cancelButtonX + 1, buttonY + 1, cancelButtonX + buttonWidth - 1, buttonY + buttonHeight - 1,
                        hoverCancel ? 0xFFCC0000 : 0xFFAA0000);
        Component cancelTextComponent = Component.translatable("gui.confirm_buy_chunk.cancel");
        String cancelText = safeString(cancelTextComponent.getString());
        int cancelTextWidth = nn(minecraft.font).width(cancelText);
        guiGraphics.drawString(nn(minecraft.font), cancelText,
                              cancelButtonX + (buttonWidth - cancelTextWidth) / 2, buttonY + 8, 0xFFFFFF);

        String hint = safeString(Component.translatable("gui.confirm_buy_chunk.hint").getString());
        int hintWidth = nn(minecraft.font).width(hint);
        guiGraphics.drawString(nn(minecraft.font), hint,
                windowX + (windowWidth - hintWidth) / 2, windowY + windowHeight - 12, 0x888888);
    }

    /**
     * 检查点击是否在确认按钮上
     */
    private boolean isClickOnConfirmButton(double mouseX, double mouseY) {
        int windowWidth = 320;
        int windowHeight = 180;
        int windowX = (this.getX() + this.width / 2) - (windowWidth / 2);
        int windowY = (this.getY() + this.height / 2) - (windowHeight / 2);

        int buttonWidth = 100;
        int buttonHeight = 24;
        int buttonY = windowY + windowHeight - 40;
        int confirmButtonX = windowX + (windowWidth / 2) - buttonWidth - 15;

        return mouseX >= confirmButtonX && mouseX <= confirmButtonX + buttonWidth &&
               mouseY >= buttonY && mouseY <= buttonY + buttonHeight;
    }

    private boolean isClickOnCancelButton(double mouseX, double mouseY) {
        int windowWidth = 320;
        int windowHeight = 180;
        int windowX = (this.getX() + this.width / 2) - (windowWidth / 2);
        int windowY = (this.getY() + this.height / 2) - (windowHeight / 2);

        int buttonWidth = 100;
        int buttonHeight = 24;
        int buttonY = windowY + windowHeight - 40;
        int cancelButtonX = windowX + (windowWidth / 2) + 15;

        return mouseX >= cancelButtonX && mouseX <= cancelButtonX + buttonWidth &&
               mouseY >= buttonY && mouseY <= buttonY + buttonHeight;
    }

    @Override
    public void updateWidgetNarration(@Nonnull NarrationElementOutput narrationElementOutput) {
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handleStyleSwitcherClick(mouseX, mouseY)) {
            return true;
        }

        if (showConfirmWindow) {
            if (button == 0) {
                if (isClickOnConfirmButton(mouseX, mouseY)) {
                    submitPendingChunkPurchases();
                    return true;
                } else if (isClickOnCancelButton(mouseX, mouseY)) {
                    clearConfirmSelection();
                    return true;
                }
            }
            return true;
        }

        if (button == 0) {
            isDragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        } else if (button == 2) {
            ChunkPos chunkPos = getChunkPosAt(mouseX, mouseY);
            if (chunkPos != null && clientCityChunkData.getCityId() != null) {
                areaSelecting = true;
                selectionStartChunkX = chunkPos.x;
                selectionStartChunkZ = chunkPos.z;
                selectionEndChunkX = chunkPos.x;
                selectionEndChunkZ = chunkPos.z;
                return true;
            }
        } else if (button == 1) {
            int mapStartX = getX() + MAP_SIDE_PADDING;
            int mapStartY = getY() + MAP_TOP_PADDING;
            int mapEndX = getX() + width - MAP_SIDE_PADDING;
            int mapEndY = getY() + height - MAP_SIDE_PADDING;

            if (mouseX >= mapStartX && mouseX <= mapEndX && mouseY >= mapStartY && mouseY <= mapEndY) {
                int centerX = mapStartX + (mapEndX - mapStartX) / 2;
                int centerY = mapStartY + (mapEndY - mapStartY) / 2;
                double chunkSize = 16 * zoomLevel;

                int chunkX = (int) Math.floor((mouseX - centerX - offsetX) / chunkSize);
                int chunkZ = (int) Math.floor((mouseY - centerY - offsetY) / chunkSize);

                long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
                boolean isAvailable = !clientCityChunkData.isChunkOwned(chunkLong);

                if (isAvailable) {
                    UUID currentCityId = clientCityChunkData.getCityId();
                    if (currentCityId != null) {
                        openConfirmWindow(List.of(new ChunkPos(chunkX, chunkZ)));
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDragging = false;
            return true;
        } else if (button == 2 && areaSelecting) {
            List<ChunkPos> selectedChunks = collectPurchasableChunksFromSelection();
            resetAreaSelection();
            if (!selectedChunks.isEmpty()) {
                openConfirmWindow(selectedChunks);
            } else {
                notifySelectionEmpty();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && isDragging) {
            offsetX += mouseX - lastMouseX;
            offsetY += mouseY - lastMouseY;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        } else if (button == 2 && areaSelecting) {
            ChunkPos chunkPos = getChunkPosAt(mouseX, mouseY);
            if (chunkPos != null) {
                selectionEndChunkX = chunkPos.x;
                selectionEndChunkZ = chunkPos.z;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double oldZoom = zoomLevel;

        if (delta > 0) {
            zoomLevel = Math.min(zoomLevel + ZOOM_STEP, MAX_ZOOM);
        } else {
            zoomLevel = Math.max(zoomLevel - ZOOM_STEP, MIN_ZOOM);
        }

        if (oldZoom != zoomLevel) {
            int mapStartX = getX() + MAP_SIDE_PADDING;
            int mapStartY = getY() + MAP_TOP_PADDING;
            int mapWidth = width - MAP_SIDE_PADDING * 2;
            int mapHeight = height - MAP_TOP_PADDING - MAP_SIDE_PADDING;
            int centerX = mapStartX + mapWidth / 2;
            int centerY = mapStartY + mapHeight / 2;

            double mouseOffsetX = mouseX - centerX;
            double mouseOffsetY = mouseY - centerY;

            double scaleFactor = zoomLevel / oldZoom;
            offsetX = mouseOffsetX - (mouseOffsetX - offsetX) * scaleFactor;
            offsetY = mouseOffsetY - (mouseOffsetY - offsetY) * scaleFactor;
        }

        return true;
    }

    public double getZoomLevel() {
        return zoomLevel;
    }

    public void setZoomLevel(double zoomLevel) {
        this.zoomLevel = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoomLevel));
    }

    public double getOffsetX() {
        return offsetX;
    }

    public void setOffsetX(double offsetX) {
        this.offsetX = offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public void setOffsetY(double offsetY) {
        this.offsetY = offsetY;
    }

    public BlockPos getCityCorePos() {
        return cityCorePos;
    }

    public void setCityCorePos(BlockPos cityCorePos) {
        this.cityCorePos = cityCorePos;
        if (cityCorePos != null) {
            offsetX = 0;
            offsetY = 0;
        }
    }

    @Nullable
    private ChunkPos getChunkPosAt(double mouseX, double mouseY) {
        int mapStartX = getX() + MAP_SIDE_PADDING;
        int mapStartY = getY() + MAP_TOP_PADDING;
        int mapEndX = getX() + width - MAP_SIDE_PADDING;
        int mapEndY = getY() + height - MAP_SIDE_PADDING;
        if (mouseX < mapStartX || mouseX > mapEndX || mouseY < mapStartY || mouseY > mapEndY) {
            return null;
        }

        int centerX = mapStartX + (mapEndX - mapStartX) / 2;
        int centerY = mapStartY + (mapEndY - mapStartY) / 2;
        double chunkSize = 16 * zoomLevel;
        int chunkX = (int) Math.floor((mouseX - centerX - offsetX) / chunkSize);
        int chunkZ = (int) Math.floor((mouseY - centerY - offsetY) / chunkSize);
        return new ChunkPos(chunkX, chunkZ);
    }

    private void renderAreaSelection(GuiGraphics guiGraphics, int startX, int startY, int width, int height,
                                     int centerX, int centerY, double chunkSize) {
        ChunkSelectionBounds bounds = getCurrentSelectionBounds();
        if (bounds == null) {
            return;
        }

        for (int chunkX = bounds.minChunkX(); chunkX <= bounds.maxChunkX(); chunkX++) {
            for (int chunkZ = bounds.minChunkZ(); chunkZ <= bounds.maxChunkZ(); chunkZ++) {
                double screenX = centerX + offsetX + chunkX * chunkSize;
                double screenY = centerY + offsetY + chunkZ * chunkSize;
                int drawX = Math.max((int) Math.floor(screenX), startX);
                int drawY = Math.max((int) Math.floor(screenY), startY);
                int drawWidth = Math.min((int) Math.ceil(screenX + chunkSize), startX + width) - drawX;
                int drawHeight = Math.min((int) Math.ceil(screenY + chunkSize), startY + height) - drawY;
                if (drawWidth <= 0 || drawHeight <= 0) {
                    continue;
                }

                long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
                int color = clientCityChunkData.isChunkOwned(chunkLong) ? 0x35FF5555 : 0x3522CCFF;
                guiGraphics.fill(drawX, drawY, drawX + drawWidth, drawY + drawHeight, color);
            }
        }
    }

    @Nullable
    private ChunkSelectionBounds getCurrentSelectionBounds() {
        if (!areaSelecting || selectionStartChunkX == Integer.MIN_VALUE || selectionEndChunkX == Integer.MIN_VALUE) {
            return null;
        }
        return new ChunkSelectionBounds(
                Math.min(selectionStartChunkX, selectionEndChunkX),
                Math.max(selectionStartChunkX, selectionEndChunkX),
                Math.min(selectionStartChunkZ, selectionEndChunkZ),
                Math.max(selectionStartChunkZ, selectionEndChunkZ)
        );
    }

    @Nullable
    private ChunkSelectionBounds getConfirmSelectionBounds() {
        if (confirmChunkSelection.isEmpty()) {
            return null;
        }

        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (ChunkPos chunkPos : confirmChunkSelection) {
            minChunkX = Math.min(minChunkX, chunkPos.x);
            maxChunkX = Math.max(maxChunkX, chunkPos.x);
            minChunkZ = Math.min(minChunkZ, chunkPos.z);
            maxChunkZ = Math.max(maxChunkZ, chunkPos.z);
        }
        return new ChunkSelectionBounds(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
    }

    private List<ChunkPos> collectPurchasableChunksFromSelection() {
        ChunkSelectionBounds bounds = getCurrentSelectionBounds();
        UUID currentCityId = clientCityChunkData.getCityId();
        if (bounds == null || currentCityId == null) {
            return List.of();
        }

        List<ChunkPos> availableChunks = new ArrayList<>();
        for (int chunkX = bounds.minChunkX(); chunkX <= bounds.maxChunkX(); chunkX++) {
            for (int chunkZ = bounds.minChunkZ(); chunkZ <= bounds.maxChunkZ(); chunkZ++) {
                long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
                if (!clientCityChunkData.isChunkOwned(chunkLong)) {
                    availableChunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }

        if (availableChunks.isEmpty()) {
            return List.of();
        }

        Set<Long> simulatedOwnedChunks = new HashSet<>(clientCityChunkData.getCurrentCityChunks());
        List<ChunkPos> orderedChunks = new ArrayList<>(availableChunks.size());
        List<ChunkPos> pendingChunks = new ArrayList<>(availableChunks);

        boolean progress;
        do {
            progress = false;
            for (int index = 0; index < pendingChunks.size(); index++) {
                ChunkPos chunkPos = pendingChunks.get(index);
                if (!isAdjacentToOwned(simulatedOwnedChunks, chunkPos)) {
                    continue;
                }

                pendingChunks.remove(index--);
                orderedChunks.add(chunkPos);
                simulatedOwnedChunks.add(chunkPos.toLong());
                progress = true;
            }
        } while (progress);

        return orderedChunks;
    }

    private boolean isAdjacentToOwned(Set<Long> ownedChunks, ChunkPos chunkPos) {
        if (ownedChunks.isEmpty()) {
            return true;
        }

        return ownedChunks.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z - 1))
                || ownedChunks.contains(ChunkPos.asLong(chunkPos.x, chunkPos.z + 1))
                || ownedChunks.contains(ChunkPos.asLong(chunkPos.x - 1, chunkPos.z))
                || ownedChunks.contains(ChunkPos.asLong(chunkPos.x + 1, chunkPos.z));
    }

    private void resetAreaSelection() {
        areaSelecting = false;
        selectionStartChunkX = Integer.MIN_VALUE;
        selectionStartChunkZ = Integer.MIN_VALUE;
        selectionEndChunkX = Integer.MIN_VALUE;
        selectionEndChunkZ = Integer.MIN_VALUE;
    }

    private void openConfirmWindow(List<ChunkPos> chunkSelection) {
        if (chunkSelection.isEmpty()) {
            return;
        }

        confirmChunkSelection.clear();
        confirmChunkSelection.addAll(chunkSelection);
        confirmChunkPos = chunkSelection.get(0);
        confirmCost = chunkSelection.size() * CHUNK_COST;
        showConfirmWindow = true;
    }

    private void clearConfirmSelection() {
        showConfirmWindow = false;
        confirmChunkPos = null;
        confirmChunkSelection.clear();
        confirmCost = 0.0;
    }

    private void submitPendingChunkPurchases() {
        UUID currentCityId = clientCityChunkData.getCityId();
        if (currentCityId == null || confirmChunkSelection.isEmpty()) {
            clearConfirmSelection();
            return;
        }

        for (ChunkPos chunkPos : confirmChunkSelection) {
            NetworkManager.INSTANCE.sendToServer(new BuyChunkPacket(currentCityId, chunkPos));
        }

        playSoundOut = true;
        soundTimer = 0;
        clearConfirmSelection();
    }

    private void notifySelectionEmpty() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(nn(Component.translatable("message.simukraft.buy_chunk.error.selection_empty")), true);
        }
    }

    private record ChunkSelectionBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
    }
}
