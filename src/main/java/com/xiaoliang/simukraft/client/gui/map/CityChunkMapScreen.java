package com.xiaoliang.simukraft.client.gui.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.xiaoliang.simukraft.client.ClientCityChunkData;
import com.xiaoliang.simukraft.client.map.SimuMapManager;
import com.xiaoliang.simukraft.client.map.SimuMapRegion;
import com.xiaoliang.simukraft.network.BuyChunkPacket;
import com.xiaoliang.simukraft.network.BuyChunkResponsePacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 独立运行的全屏城市地图界面。
 * 使用 {@link SimuMapManager} 进行地图渲染，完全不依赖 FTB Chunks 或 Xaero。
 * <p>
 * 功能:
 * <ul>
 *   <li>显示自有渲染系统生成的地图纹理</li>
 *   <li>城市区块边框叠加（仅边框，不填充）</li>
 *   <li>鼠标拖拽平移、滚轮缩放</li>
 *   <li>悬停区块信息</li>
 *   <li>玩家位置指示</li>
 *   <li>右键区块购买认领</li>
 * </ul>
 */
public class CityChunkMapScreen extends Screen {

    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 16.0;
    private static final double CHUNK_COST = 10.0;

    private double zoom = 1.0;

    private double viewCenterX;
    private double viewCenterZ;

    private boolean dragging = false;
    private double lastDragX, lastDragY;

    private int hoveredChunkX = Integer.MIN_VALUE;
    private int hoveredChunkZ = Integer.MIN_VALUE;

    private final ClientCityChunkData cityData = ClientCityChunkData.getInstance();

    private static final int BG_COLOR = 0xFF1A1A2E;

    private boolean showConfirmWindow = false;
    @Nullable
    private ChunkPos confirmChunkPos = null;
    @Nonnull
    private final List<ChunkPos> confirmChunkSelection = new ArrayList<>();
    private double confirmCost = 0.0;

    private boolean areaSelecting = false;
    private int selectionStartChunkX = Integer.MIN_VALUE;
    private int selectionStartChunkZ = Integer.MIN_VALUE;
    private int selectionEndChunkX = Integer.MIN_VALUE;
    private int selectionEndChunkZ = Integer.MIN_VALUE;

    private static final int CONFIRM_WINDOW_WIDTH = 320;
    private static final int CONFIRM_WINDOW_HEIGHT = 180;
    private static final int CONFIRM_BTN_WIDTH = 100;
    private static final int CONFIRM_BTN_HEIGHT = 24;

    public CityChunkMapScreen() {
        super(Component.translatable("gui.simukraft.city_map"));
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(@Nullable String value) {
        return nn(value);
    }

    @Override
    protected void init() {
        super.init();

        SimuMapManager mgr = SimuMapManager.getInstance();
        mgr.acquireConsumer();

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            viewCenterX = player.getX();
            viewCenterZ = player.getZ();

            int pcx = player.chunkPosition().x;
            int pcz = player.chunkPosition().z;
            mgr.forceScanArea(pcx, pcz, 8);
            mgr.forceRenderAll();
        }

        BuyChunkResponsePacket.BuyChunkResultEvent.INSTANCE.register(this::onBuyChunkResult);
    }

    @Override
    public void removed() {
        super.removed();
        if (SimuMapManager.isAvailable()) {
            SimuMapManager.getInstance().releaseConsumer();
        }
    }

    /**
     * 购买区块结果回调。
     */
    private void onBuyChunkResult(boolean success, long chunkPosLong, double cost, String errorMessage) {
        showConfirmWindow = false;
        confirmChunkPos = null;
        confirmChunkSelection.clear();
        confirmCost = 0.0;
    }

    @Override
    public void tick() {
        super.tick();
        if (SimuMapManager.isAvailable()) {
            SimuMapManager.getInstance().tick();
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, BG_COLOR);

        if (!SimuMapManager.isAvailable()) {
            guiGraphics.drawCenteredString(nn(font), nn(Component.translatable("gui.simukraft.map_loading")),
                    width / 2, height / 2, 0xFFFFFF);
            return;
        }

        renderMapTiles(guiGraphics);

        renderCityChunkBorders(guiGraphics);

        if (zoom >= 2.0) {
            renderChunkGrid(guiGraphics);
        }

        renderCityCoreMarkers(guiGraphics, mouseX, mouseY);

        renderPlayerMarker(guiGraphics);

        updateHover(mouseX, mouseY);
        renderAreaSelection(guiGraphics);
        renderHoverHighlight(guiGraphics);

        renderHud(guiGraphics, mouseX, mouseY);

        if (showConfirmWindow && confirmChunkPos != null) {
            renderConfirmWindow(guiGraphics, mouseX, mouseY);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 渲染所有可见的地图区域纹理。
     */
    private void renderMapTiles(GuiGraphics guiGraphics) {
        SimuMapManager manager = SimuMapManager.getInstance();
        Collection<SimuMapRegion> regions = manager.getAllRegions();

        double halfW = width / 2.0;
        double halfH = height / 2.0;

        for (SimuMapRegion region : regions) {
            if (!region.isImageLoaded() && !region.hasData()) continue;

            int textureId = region.getTextureId();
            if (textureId == -1) continue;

            double regionWorldX = region.regionX * 512.0;
            double regionWorldZ = region.regionZ * 512.0;

            double screenX = halfW + (regionWorldX - viewCenterX) * zoom;
            double screenY = halfH + (regionWorldZ - viewCenterZ) * zoom;
            double regionSize = 512.0 * zoom;

            if (screenX + regionSize < 0 || screenX > width || screenY + regionSize < 0 || screenY > height) {
                continue;
            }

            RenderSystem.setShaderTexture(0, textureId);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.enableBlend();

            Matrix4f matrix = nn(guiGraphics.pose().last().pose());
            BufferBuilder buf = Tesselator.getInstance().getBuilder();
            buf.begin(nn(VertexFormat.Mode.QUADS), nn(DefaultVertexFormat.POSITION_TEX));

            float x0 = (float) screenX;
            float y0 = (float) screenY;
            float x1 = (float) (screenX + regionSize);
            float y1 = (float) (screenY + regionSize);

            buf.vertex(matrix, x0, y1, 0).uv(0, 1).endVertex();
            buf.vertex(matrix, x1, y1, 0).uv(1, 1).endVertex();
            buf.vertex(matrix, x1, y0, 0).uv(1, 0).endVertex();
            buf.vertex(matrix, x0, y0, 0).uv(0, 0).endVertex();

            BufferBuilder.RenderedBuffer renderedBuffer = nn(buf.end());
            BufferUploader.drawWithShader(renderedBuffer);
            RenderSystem.disableBlend();
        }
    }

    /**
     * 渲染城市区块边框叠加（仅外框轮廓，不填充颜色）。
     */
    private void renderCityChunkBorders(GuiGraphics guiGraphics) {
        double halfW = width / 2.0;
        double halfH = height / 2.0;
        double chunkPixels = 16.0 * zoom;

        int minCX = (int) Math.floor((viewCenterX - halfW / zoom) / 16.0) - 1;
        int maxCX = (int) Math.ceil((viewCenterX + halfW / zoom) / 16.0) + 1;
        int minCZ = (int) Math.floor((viewCenterZ - halfH / zoom) / 16.0) - 1;
        int maxCZ = (int) Math.ceil((viewCenterZ + halfH / zoom) / 16.0) + 1;

        int borderThickness = Math.max(1, (int) Math.round(zoom * 0.5));
        final int GREEN_BORDER = 0xCC00DD00;
        final int RED_BORDER = 0xCCDD0000;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                long chunkLong = ChunkPos.asLong(cx, cz);
                if (!cityData.isChunkOwned(chunkLong)) continue;

                boolean isCurrentCity = cityData.isChunkInCurrentCity(chunkLong);
                int borderColor = isCurrentCity ? GREEN_BORDER : RED_BORDER;

                double sx = halfW + (cx * 16.0 - viewCenterX) * zoom;
                double sy = halfH + (cz * 16.0 - viewCenterZ) * zoom;
                int ix = (int) sx;
                int iy = (int) sy;
                int iw = (int) chunkPixels;
                int ih = (int) chunkPixels;

                if (!cityData.isChunkOwned(ChunkPos.asLong(cx, cz - 1))) {
                    guiGraphics.fill(ix, iy, ix + iw, iy + borderThickness, borderColor);
                }
                if (!cityData.isChunkOwned(ChunkPos.asLong(cx, cz + 1))) {
                    guiGraphics.fill(ix, iy + ih - borderThickness, ix + iw, iy + ih, borderColor);
                }
                if (!cityData.isChunkOwned(ChunkPos.asLong(cx - 1, cz))) {
                    guiGraphics.fill(ix, iy, ix + borderThickness, iy + ih, borderColor);
                }
                if (!cityData.isChunkOwned(ChunkPos.asLong(cx + 1, cz))) {
                    guiGraphics.fill(ix + iw - borderThickness, iy, ix + iw, iy + ih, borderColor);
                }
            }
        }
    }

    /**
     * 渲染区块网格线。
     */
    private void renderChunkGrid(GuiGraphics guiGraphics) {
        double halfW = width / 2.0;
        double halfH = height / 2.0;

        int gridColor = 0x20FFFFFF;

        double worldMinX = viewCenterX - halfW / zoom;
        double worldMaxX = viewCenterX + halfW / zoom;
        double worldMinZ = viewCenterZ - halfH / zoom;
        double worldMaxZ = viewCenterZ + halfH / zoom;

        int startCX = (int) Math.floor(worldMinX / 16.0);
        int endCX = (int) Math.ceil(worldMaxX / 16.0);
        int startCZ = (int) Math.floor(worldMinZ / 16.0);
        int endCZ = (int) Math.ceil(worldMaxZ / 16.0);

        for (int cx = startCX; cx <= endCX; cx++) {
            int screenX = (int) (halfW + (cx * 16.0 - viewCenterX) * zoom);
            if (screenX >= 0 && screenX < width) {
                guiGraphics.fill(screenX, 0, screenX + 1, height, gridColor);
            }
        }
        for (int cz = startCZ; cz <= endCZ; cz++) {
            int screenZ = (int) (halfH + (cz * 16.0 - viewCenterZ) * zoom);
            if (screenZ >= 0 && screenZ < height) {
                guiGraphics.fill(0, screenZ, width, screenZ + 1, gridColor);
            }
        }
    }

    /**
     * 渲染玩家位置标记。
     */
    private void renderPlayerMarker(GuiGraphics guiGraphics) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        double px = player.getX();
        double pz = player.getZ();

        int screenX = (int) (width / 2.0 + (px - viewCenterX) * zoom);
        int screenZ = (int) (height / 2.0 + (pz - viewCenterZ) * zoom);

        guiGraphics.fill(screenX - 4, screenZ - 4, screenX + 4, screenZ + 4, 0xFF000000);
        guiGraphics.fill(screenX - 3, screenZ - 3, screenX + 3, screenZ + 3, 0xFF4488FF);
        guiGraphics.fill(screenX - 1, screenZ - 1, screenX + 1, screenZ + 1, 0xFFFFFFFF);
    }

    /**
     * 渲染所有城市核心标记。
     */
    private void renderCityCoreMarkers(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        var cores = cityData.getAllCityCores();
        if (cores.isEmpty()) return;

        double halfW = width / 2.0;
        double halfH = height / 2.0;

        for (var entry : cores.entrySet()) {
            ClientCityChunkData.CityCoreCacheEntry core = entry.getValue();
            net.minecraft.core.BlockPos pos = core.getPos();

            int screenX = (int) (halfW + (pos.getX() - viewCenterX) * zoom);
            int screenZ = (int) (halfH + (pos.getZ() - viewCenterZ) * zoom);

            if (screenX < -10 || screenX > width + 10 || screenZ < -10 || screenZ > height + 10) continue;

            // 外框（黑色）
            guiGraphics.fill(screenX - 5, screenZ - 5, screenX + 5, screenZ + 5, 0xFF000000);
            // 内框（蓝色）
            guiGraphics.fill(screenX - 4, screenZ - 4, screenX + 4, screenZ + 4, 0xFF0000FF);
            // 中心（白色）
            guiGraphics.fill(screenX - 2, screenZ - 2, screenX + 2, screenZ + 2, 0xFF4080FF);

            // 城市名称标签
            String label = safeString(core.getCityName());
            int labelW = nn(font).width(label);
            int labelX = screenX - labelW / 2;
            int labelY = screenZ - 15;
            guiGraphics.fill(labelX - 2, labelY - 1, labelX + labelW + 2, labelY + nn(font).lineHeight + 1, 0xAA000000);
            guiGraphics.drawString(nn(font), label, labelX, labelY, 0xFFFFFF);
        }
    }

    /**
     * 更新鼠标悬停区块。
     */
    private void updateHover(int mouseX, int mouseY) {
        double halfW = width / 2.0;
        double halfH = height / 2.0;

        double worldX = viewCenterX + (mouseX - halfW) / zoom;
        double worldZ = viewCenterZ + (mouseY - halfH) / zoom;

        hoveredChunkX = (int) Math.floor(worldX / 16.0);
        hoveredChunkZ = (int) Math.floor(worldZ / 16.0);
    }

    /**
     * 渲染悬停区块高亮。
     */
    private void renderHoverHighlight(GuiGraphics guiGraphics) {
        if (hoveredChunkX == Integer.MIN_VALUE) return;

        double halfW = width / 2.0;
        double halfH = height / 2.0;
        double chunkPixels = 16.0 * zoom;

        int sx = (int) (halfW + (hoveredChunkX * 16.0 - viewCenterX) * zoom);
        int sy = (int) (halfH + (hoveredChunkZ * 16.0 - viewCenterZ) * zoom);
        int sw = (int) chunkPixels;
        int sh = (int) chunkPixels;

        guiGraphics.fill(sx, sy, sx + sw, sy + sh, 0x30FFFFFF);
    }

    /**
     * 渲染 HUD 信息（坐标、缩放、区块状态等）。
     */
    private void renderHud(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(nn(font), nn(title), 4, 4, 0xFFFFFF);

        String zoomText = safeString(String.format("%.1fx", zoom));
        guiGraphics.drawString(nn(font), zoomText, 4, 16, 0xCCCCCC);

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            String posText = safeString(String.format("X: %.0f, Z: %.0f",
                    player.getX(), player.getZ()));
            int posW = nn(font).width(posText);
            guiGraphics.fill(width - posW - 8, 0, width, 14, 0x80000000);
            guiGraphics.drawString(nn(font), posText, width - posW - 4, 3, 0xFFFFFF);
        }

        if (hoveredChunkX != Integer.MIN_VALUE && !showConfirmWindow) {
            List<String> lines = new ArrayList<>();

            lines.add(Component.translatable("gui.city_map.chunk_coordinate", hoveredChunkX, hoveredChunkZ).getString());

            int bx0 = hoveredChunkX * 16;
            int bz0 = hoveredChunkZ * 16;
            lines.add(Component.translatable("gui.city_map.block_range", bx0, bx0 + 15, bz0, bz0 + 15).getString());

            long chunkLong = ChunkPos.asLong(hoveredChunkX, hoveredChunkZ);
            boolean isOwned = cityData.isChunkOwned(chunkLong);
            boolean isCurrentCity = cityData.isChunkInCurrentCity(chunkLong);
            if (isOwned) {
                if (isCurrentCity) {
                    lines.add(Component.translatable("gui.city_map.owned_by_current").getString());
                } else {
                    lines.add(Component.translatable("gui.city_map.owned_by_other").getString());
                }
            } else {
                lines.add(Component.translatable("gui.city_map.available").getString());
            }

            int maxW = 0;
            for (String l : lines) {
                maxW = Math.max(maxW, nn(font).width(safeString(l)));
            }
            int lineH = nn(font).lineHeight + 2;
            int boxH = lines.size() * lineH + 4;
            int boxX = (width - maxW) / 2 - 4;
            int boxY = height - boxH - 4;

            guiGraphics.fill(boxX, boxY, boxX + maxW + 8, boxY + boxH, 0xCC000000);

            int ty = boxY + 2;
            for (int i = 0; i < lines.size(); i++) {
                int color = i == 0 ? 0xFFFF00 : (i == lines.size() - 1 ? (isOwned ? (isCurrentCity ? 0x55FF55 : 0xFF5555) : 0x55FFFF) : 0xFFFFFF);
                guiGraphics.drawString(nn(font), safeString(lines.get(i)), boxX + 4, ty, color);
                ty += lineH;
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
                int selectionWidth = nn(font).width(selectionText);
                guiGraphics.fill(width - selectionWidth - 8, 16, width, 30, 0x80000000);
                guiGraphics.drawString(nn(font), selectionText, width - selectionWidth - 4, 19, 0x55FFFF);
            }
        }

        String hint = "[ESC] " + Component.translatable("gui.simukraft.close_map").getString()
                + "  " + Component.translatable("gui.confirm_buy_chunk.hint").getString();
        int hintW = nn(font).width(hint);
        guiGraphics.drawString(nn(font), safeString(hint), width - hintW - 4, height - 12, 0x888888);
    }

    /**
     * 渲染确认购买弹窗。
     */
    private void renderConfirmWindow(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int windowX = (width - CONFIRM_WINDOW_WIDTH) / 2;
        int windowY = (height - CONFIRM_WINDOW_HEIGHT) / 2;

        guiGraphics.fill(windowX - 2, windowY - 2, windowX + CONFIRM_WINDOW_WIDTH + 2, windowY + CONFIRM_WINDOW_HEIGHT + 2, 0xFF000000);
        guiGraphics.fillGradient(windowX, windowY, windowX + CONFIRM_WINDOW_WIDTH, windowY + CONFIRM_WINDOW_HEIGHT, 0xFF1a1a2e, 0xFF16213e);

        guiGraphics.fill(windowX, windowY, windowX + CONFIRM_WINDOW_WIDTH, windowY + 24, 0xFF0f3460);
        Component titleComp = Component.translatable("gui.confirm_buy_chunk.title");
        int titleWidth = nn(font).width(safeString(titleComp.getString()));
        guiGraphics.drawString(nn(font), safeString(titleComp.getString()),
                windowX + (CONFIRM_WINDOW_WIDTH - titleWidth) / 2, windowY + 8, 0xFFFFFF);

        int infoY = windowY + 32;
        int lineHeight = 14;

        if (confirmChunkSelection.size() > 1) {
            ChunkSelectionBounds bounds = nn(getConfirmSelectionBounds());
            String selectionCount = safeString(Component.translatable(
                    "gui.confirm_buy_chunk.selection_count",
                    confirmChunkSelection.size()
            ).getString());
            guiGraphics.drawString(nn(font), selectionCount, windowX + 15, infoY, 0xFFFF00);

            String selectionRange = safeString(Component.translatable(
                    "gui.confirm_buy_chunk.selection_range",
                    bounds.minChunkX(),
                    bounds.minChunkZ(),
                    bounds.maxChunkX(),
                    bounds.maxChunkZ()
            ).getString());
            guiGraphics.drawString(nn(font), selectionRange, windowX + 15, infoY + lineHeight, 0xAAAAAA);
        } else {
            ChunkPos chunkPos = nn(confirmChunkPos);
            String chunkCoords = safeString(Component.translatable("gui.city_map.chunk_coordinate", chunkPos.x, chunkPos.z).getString());
            guiGraphics.drawString(nn(font), chunkCoords, windowX + 15, infoY, 0xFFFF00);

            int blockStartX = chunkPos.x * 16;
            int blockEndX = blockStartX + 15;
            int blockStartZ = chunkPos.z * 16;
            int blockEndZ = blockStartZ + 15;
            String blockRange = safeString(Component.translatable("gui.city_map.block_range", blockStartX, blockEndX, blockStartZ, blockEndZ).getString());
            guiGraphics.drawString(nn(font), blockRange, windowX + 15, infoY + lineHeight, 0xAAAAAA);
        }

        guiGraphics.fill(windowX + 10, infoY + lineHeight * 2 + 5, windowX + CONFIRM_WINDOW_WIDTH - 10, infoY + lineHeight * 2 + 6, 0xFF444444);

        int costY = infoY + lineHeight * 3 + 15;
        String costLabel = safeString(Component.translatable("gui.confirm_buy_chunk.cost_label").getString());
        guiGraphics.drawString(nn(font), costLabel, windowX + 15, costY, 0xFFFFFF);
        String costText = safeString(String.format("$%.2f", confirmCost));
        guiGraphics.drawString(nn(font), costText, windowX + 15 + nn(font).width(costLabel) + 5, costY, 0x55FF55);

        int buttonY = windowY + CONFIRM_WINDOW_HEIGHT - 40;

        int confirmButtonX = windowX + (CONFIRM_WINDOW_WIDTH / 2) - CONFIRM_BTN_WIDTH - 15;
        boolean hoverConfirm = mouseX >= confirmButtonX && mouseX <= confirmButtonX + CONFIRM_BTN_WIDTH
                && mouseY >= buttonY && mouseY <= buttonY + CONFIRM_BTN_HEIGHT;
        guiGraphics.fill(confirmButtonX, buttonY, confirmButtonX + CONFIRM_BTN_WIDTH, buttonY + CONFIRM_BTN_HEIGHT,
                hoverConfirm ? 0xFF00AA00 : 0xFF008800);
        guiGraphics.fill(confirmButtonX + 1, buttonY + 1, confirmButtonX + CONFIRM_BTN_WIDTH - 1, buttonY + CONFIRM_BTN_HEIGHT - 1,
                hoverConfirm ? 0xFF00CC00 : 0xFF00AA00);
        String confirmText = safeString(Component.translatable("gui.confirm_buy_chunk.confirm").getString());
        int confirmTextWidth = nn(font).width(confirmText);
        guiGraphics.drawString(nn(font), confirmText, confirmButtonX + (CONFIRM_BTN_WIDTH - confirmTextWidth) / 2, buttonY + 8, 0xFFFFFF);

        int cancelButtonX = windowX + (CONFIRM_WINDOW_WIDTH / 2) + 15;
        boolean hoverCancel = mouseX >= cancelButtonX && mouseX <= cancelButtonX + CONFIRM_BTN_WIDTH
                && mouseY >= buttonY && mouseY <= buttonY + CONFIRM_BTN_HEIGHT;
        guiGraphics.fill(cancelButtonX, buttonY, cancelButtonX + CONFIRM_BTN_WIDTH, buttonY + CONFIRM_BTN_HEIGHT,
                hoverCancel ? 0xFFAA0000 : 0xFF880000);
        guiGraphics.fill(cancelButtonX + 1, buttonY + 1, cancelButtonX + CONFIRM_BTN_WIDTH - 1, buttonY + CONFIRM_BTN_HEIGHT - 1,
                hoverCancel ? 0xFFCC0000 : 0xFFAA0000);
        String cancelText = safeString(Component.translatable("gui.confirm_buy_chunk.cancel").getString());
        int cancelTextWidth = nn(font).width(cancelText);
        guiGraphics.drawString(nn(font), cancelText, cancelButtonX + (CONFIRM_BTN_WIDTH - cancelTextWidth) / 2, buttonY + 8, 0xFFFFFF);

        String hintText = safeString(Component.translatable("gui.confirm_buy_chunk.hint").getString());
        int hintWidth = nn(font).width(hintText);
        guiGraphics.drawString(nn(font), hintText,
                windowX + (CONFIRM_WINDOW_WIDTH - hintWidth) / 2, windowY + CONFIRM_WINDOW_HEIGHT - 12, 0x888888);
    }

    private boolean isClickOnConfirmButton(double mouseX, double mouseY) {
        int windowX = (width - CONFIRM_WINDOW_WIDTH) / 2;
        int windowY = (height - CONFIRM_WINDOW_HEIGHT) / 2;
        int buttonY = windowY + CONFIRM_WINDOW_HEIGHT - 40;
        int confirmButtonX = windowX + (CONFIRM_WINDOW_WIDTH / 2) - CONFIRM_BTN_WIDTH - 15;
        return mouseX >= confirmButtonX && mouseX <= confirmButtonX + CONFIRM_BTN_WIDTH
                && mouseY >= buttonY && mouseY <= buttonY + CONFIRM_BTN_HEIGHT;
    }

    private boolean isClickOnCancelButton(double mouseX, double mouseY) {
        int windowX = (width - CONFIRM_WINDOW_WIDTH) / 2;
        int windowY = (height - CONFIRM_WINDOW_HEIGHT) / 2;
        int buttonY = windowY + CONFIRM_WINDOW_HEIGHT - 40;
        int cancelButtonX = windowX + (CONFIRM_WINDOW_WIDTH / 2) + 15;
        return mouseX >= cancelButtonX && mouseX <= cancelButtonX + CONFIRM_BTN_WIDTH
                && mouseY >= buttonY && mouseY <= buttonY + CONFIRM_BTN_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showConfirmWindow) {
            if (button == 0) {
                if (isClickOnConfirmButton(mouseX, mouseY)) {
                    submitPendingChunkPurchases();
                    return true;
                } else if (isClickOnCancelButton(mouseX, mouseY)) {
                    clearConfirmSelection();
                    Minecraft.getInstance().getSoundManager()
                            .play(nn(SimpleSoundInstance.forUI(nn(SoundEvents.UI_BUTTON_CLICK), 1.0F)));
                    return true;
                }
            }
            return true;
        }

        if (button == 0) {
            dragging = true;
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        } else if (button == 2) {
            ChunkPos chunkPos = getChunkPosAt(mouseX, mouseY);
            if (chunkPos != null && cityData.getCityId() != null) {
                areaSelecting = true;
                selectionStartChunkX = chunkPos.x;
                selectionStartChunkZ = chunkPos.z;
                selectionEndChunkX = chunkPos.x;
                selectionEndChunkZ = chunkPos.z;
                return true;
            }
        } else if (button == 1) {
            if (hoveredChunkX != Integer.MIN_VALUE) {
                long chunkLong = ChunkPos.asLong(hoveredChunkX, hoveredChunkZ);
                boolean isAvailable = !cityData.isChunkOwned(chunkLong);
                if (isAvailable) {
                    UUID currentCityId = cityData.getCityId();
                    if (currentCityId != null) {
                        openConfirmWindow(List.of(new ChunkPos(hoveredChunkX, hoveredChunkZ)));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
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
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && dragging) {
            double dx = mouseX - lastDragX;
            double dy = mouseY - lastDragY;
            viewCenterX -= dx / zoom;
            viewCenterZ -= dy / zoom;
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        } else if (button == 2 && areaSelecting) {
            ChunkPos chunkPos = getChunkPosAt(mouseX, mouseY);
            if (chunkPos != null) {
                selectionEndChunkX = chunkPos.x;
                selectionEndChunkZ = chunkPos.z;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (showConfirmWindow) return true;

        double oldZoom = zoom;
        if (delta > 0) {
            zoom = Math.min(zoom * 1.25, MAX_ZOOM);
        } else {
            zoom = Math.max(zoom / 1.25, MIN_ZOOM);
        }

        if (oldZoom != zoom) {
            double halfW = width / 2.0;
            double halfH = height / 2.0;
            double mouseWorldX = viewCenterX + (mouseX - halfW) / oldZoom;
            double mouseWorldZ = viewCenterZ + (mouseY - halfH) / oldZoom;

            viewCenterX = mouseWorldX - (mouseX - halfW) / zoom;
            viewCenterZ = mouseWorldZ - (mouseY - halfH) / zoom;
        }

        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (keyCode == 32 && player != null) {
            viewCenterX = player.getX();
            viewCenterZ = player.getZ();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 打开地图界面的便捷方法。
     */
    public static void open() {
        Minecraft.getInstance().setScreen(new CityChunkMapScreen());
    }

    @Nullable
    private ChunkPos getChunkPosAt(double mouseX, double mouseY) {
        if (mouseX < 0 || mouseX > width || mouseY < 0 || mouseY > height) {
            return null;
        }

        double halfW = width / 2.0;
        double halfH = height / 2.0;
        double worldX = viewCenterX + (mouseX - halfW) / zoom;
        double worldZ = viewCenterZ + (mouseY - halfH) / zoom;
        return new ChunkPos((int) Math.floor(worldX / 16.0), (int) Math.floor(worldZ / 16.0));
    }

    private void renderAreaSelection(GuiGraphics guiGraphics) {
        ChunkSelectionBounds bounds = getCurrentSelectionBounds();
        if (bounds == null) {
            return;
        }

        double halfW = width / 2.0;
        double halfH = height / 2.0;
        double chunkPixels = 16.0 * zoom;

        for (int chunkX = bounds.minChunkX(); chunkX <= bounds.maxChunkX(); chunkX++) {
            for (int chunkZ = bounds.minChunkZ(); chunkZ <= bounds.maxChunkZ(); chunkZ++) {
                int sx = (int) (halfW + (chunkX * 16.0 - viewCenterX) * zoom);
                int sy = (int) (halfH + (chunkZ * 16.0 - viewCenterZ) * zoom);
                int sw = (int) chunkPixels;
                int sh = (int) chunkPixels;

                long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
                int color = cityData.isChunkOwned(chunkLong) ? 0x35FF5555 : 0x3522CCFF;
                guiGraphics.fill(sx, sy, sx + sw, sy + sh, color);
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
        UUID currentCityId = cityData.getCityId();
        if (bounds == null || currentCityId == null) {
            return List.of();
        }

        List<ChunkPos> availableChunks = new ArrayList<>();
        for (int chunkX = bounds.minChunkX(); chunkX <= bounds.maxChunkX(); chunkX++) {
            for (int chunkZ = bounds.minChunkZ(); chunkZ <= bounds.maxChunkZ(); chunkZ++) {
                long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
                if (!cityData.isChunkOwned(chunkLong)) {
                    availableChunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }

        if (availableChunks.isEmpty()) {
            return List.of();
        }

        Set<Long> simulatedOwnedChunks = new HashSet<>(cityData.getCurrentCityChunks());
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
        UUID currentCityId = cityData.getCityId();
        if (currentCityId == null || confirmChunkSelection.isEmpty()) {
            clearConfirmSelection();
            return;
        }

        for (ChunkPos chunkPos : confirmChunkSelection) {
            NetworkManager.INSTANCE.sendToServer(new BuyChunkPacket(currentCityId, chunkPos));
        }

        Minecraft.getInstance().getSoundManager()
                .play(nn(SimpleSoundInstance.forUI(nn(SoundEvents.UI_BUTTON_CLICK), 1.0F)));
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
