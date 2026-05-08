package com.xiaoliang.simukraft.client.gui.components;

import com.xiaoliang.simukraft.world.CityUpgradeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("null")
public class UpgradeCanvas extends AbstractWidget {
    private static final int COLOR_TEXT = 0xFF4F3928;
    private static final int COLOR_TEXT_MUTED = 0xFF7A654A;
    private static final int COLOR_COMPLETE = 0xFF677845;
    private static final int COLOR_AVAILABLE = 0xFF7C8D49;
    private static final int COLOR_LOCKED = 0xFF9C6A55;
    private static final int COLOR_SELECTED = 0xFFB7893D;
    private static final int COLOR_LINE_COMPLETE = 0xFF9F8C58;
    private static final int COLOR_LINE_LOCKED = 0xFFB9A88A;
    private static final int NODE_RADIUS = 10;
    private static final int NODE_HIT_RADIUS = 18;

    private double zoomLevel = 4.0;
    private double offsetX = 0;
    private double offsetY = 0;
    private BlockPos cityCorePos;
    private final int cityLevel;
    private boolean isDragging = false;
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private double scrollOffsetY = 0;
    private MapMarker hoveredMarker = null;
    private MapMarker selectedMarker = null;
    private final List<MapMarker> markers = new ArrayList<>();

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

    public UpgradeCanvas(int x, int y, int width, int height, Screen parentScreen, BlockPos cityCorePos, int cityLevel) {
        super(x, y, width, height, Component.empty());
        this.cityCorePos = cityCorePos != null ? cityCorePos : new BlockPos(0, 0, 0);
        this.cityLevel = cityLevel;
        int coreCX = this.cityCorePos.getX() >> 4;
        int coreCZ = this.cityCorePos.getZ() >> 4;
        double chunkSize = 16 * zoomLevel;
        this.offsetX = -coreCX * chunkSize;
        this.offsetY = -coreCZ * chunkSize;
        loadCityUpgradeMarkers(cityLevel);
    }

    private void loadCityUpgradeMarkers(int cityLevel) {
        CityUpgradeManager upgradeManager = CityUpgradeManager.getInstance();
        List<CityUpgradeManager.CityUpgrade> upgrades = upgradeManager.getAllUpgrades();
        clearMarkers();
        for (int i = 0; i < upgrades.size(); i++) {
            CityUpgradeManager.CityUpgrade upgrade = upgrades.get(i);
            int level = upgrade.level();
            int color = getStateColor(level, cityLevel);
            String hoverText = level + ":" + upgrade.name();
            int baseX = cityCorePos.getX() + (i % 2 == 0 ? -32 : 32);
            int baseZ = cityCorePos.getZ() + i * 48;
            addMarker(new MapMarker(new BlockPos(baseX, 0, baseZ), color, hoverText));
        }
    }

    public void addMarker(MapMarker marker) {
        markers.add(marker);
    }

    public boolean removeMarker(MapMarker marker) {
        return markers.remove(marker);
    }

    public void clearMarkers() {
        markers.clear();
    }

    public List<MapMarker> getMarkers() {
        return new ArrayList<>(markers);
    }

    @Override
    public void renderWidget(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        int mapStartX = getX() + 12;
        int mapStartY = getY() + 36;
        int mapEndX = getX() + width - 12;
        int mapEndY = getY() + height - 12;
        int mapWidth = mapEndX - mapStartX;
        int mapHeight = mapEndY - mapStartY;

        renderCanvasHeader(guiGraphics);
        renderMap(guiGraphics, mapStartX, mapStartY, mapWidth, mapHeight, mouseX, mouseY);
        renderHoverInfo(guiGraphics, mouseX, mouseY);
    }

    private void renderCanvasHeader(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        int titleX = getX() + 14;
        int titleY = getY() + 10;
        guiGraphics.drawString(mc.font, Component.literal("城市发展路线"), titleX, titleY, COLOR_TEXT, false);
        guiGraphics.drawString(mc.font, Component.literal("拖拽查看路线，滚轮缩放视图，点击节点查看详情"), titleX + 92, titleY, COLOR_TEXT_MUTED, false);
    }
    private void renderMap(GuiGraphics guiGraphics, int startX, int startY, int width, int height, int mouseX, int mouseY) {
        hoveredMarker = null;

        renderMapOverlay(guiGraphics, startX, startY, width, height);

        List<NodePoint> points = buildNodePoints(startX, startY, width, height);
        renderNodeLines(guiGraphics, points, startX, startY, width, height);
        renderNodes(guiGraphics, points, mouseX, mouseY);
    }

    private void renderMapOverlay(GuiGraphics guiGraphics, int startX, int startY, int width, int height) {
    }

    private List<NodePoint> buildNodePoints(int startX, int startY, int width, int height) {
        List<NodePoint> points = new ArrayList<>();
        if (markers.isEmpty()) {
            return points;
        }
        int nodeCount = markers.size();
        double scale = getAdaptiveScale(width, height);
        int leftX = startX + Math.max(46, (int) Math.round(56 * scale));
        int topPadding = Math.max(42, (int) Math.round(58 * scale));
        int itemGap = (int) Math.round(64 * scale);
        for (int i = 0; i < nodeCount; i++) {
            int level = parseLevel(markers.get(i));
            int x = leftX;
            int y = startY + topPadding + itemGap * i + (int) scrollOffsetY;
            points.add(new NodePoint(markers.get(i), level, x, y, i, scale, 0, 1));
        }
        return points;
    }

    private double getAdaptiveScale(int width, int height) {
        return Math.max(0.82, Math.min(1.12, height / 480.0));
    }

    private void renderNodeLines(GuiGraphics guiGraphics, List<NodePoint> points, int startX, int startY, int width, int height) {
        for (int i = 0; i < points.size() - 1; i++) {
            NodePoint from = points.get(i);
            NodePoint to = points.get(i + 1);
            int color = to.level <= cityLevel + 1 ? COLOR_LINE_COMPLETE : COLOR_LINE_LOCKED;
            int[] clipped = clipLine(from.x, from.y, to.x, to.y, startX, startY, startX + width, startY + height);
            if (clipped != null) {
                drawThickLine(guiGraphics, clipped[0], clipped[1], clipped[2], clipped[3], color);
            }
        }
    }

    private void renderNodes(GuiGraphics guiGraphics, List<NodePoint> points, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        for (NodePoint point : points) {
            if (!isNodeVisible(point)) {
                continue;
            }
            int hitRadius = (int) Math.round(NODE_HIT_RADIUS * point.scale());
            boolean hovered = Math.abs(mouseX - point.x) <= hitRadius && Math.abs(mouseY - point.y) <= hitRadius;
            boolean selected = point.marker == selectedMarker;
            if (hovered) {
                hoveredMarker = point.marker;
            }
            int stateColor = getStateColor(point.level, cityLevel);
            renderNodeHalo(guiGraphics, point.x, point.y, stateColor, hovered, selected, point.scale());
            renderNodeBody(guiGraphics, point.x, point.y, stateColor, selected, point.scale());
            renderNodeLabel(guiGraphics, mc, point, stateColor, selected || hovered);
        }
    }

    private void renderNodeHalo(GuiGraphics guiGraphics, int x, int y, int color, boolean hovered, boolean selected, double scale) {
        if (selected) {
            int halo = (int) Math.round(16 * scale);
            guiGraphics.fill(x - halo, y - 1, x + halo, y + 2, 0xFFC9A75A);
        }
    }

    private void renderNodeBody(GuiGraphics guiGraphics, int x, int y, int color, boolean selected, double scale) {
        int radius = Math.max(7, (int) Math.round(NODE_RADIUS * scale));
        int border = selected ? COLOR_SELECTED : 0xFFEEE2C8;
        guiGraphics.fill(x - radius, y - 5, x + radius + 1, y + 6, border);
        guiGraphics.fill(x - 6, y - radius, x + 7, y + radius + 1, border);
        guiGraphics.fill(x - radius + 2, y - 3, x + radius - 1, y + 4, color);
        guiGraphics.fill(x - 4, y - radius + 2, x + 5, y + radius - 1, color);
        guiGraphics.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFAF4E7);
        if (selected) {
            drawBorder(guiGraphics, x - radius - 5, y - radius - 5, x + radius + 6, y + radius + 6, COLOR_SELECTED);
        }
    }

    private void renderNodeLabel(GuiGraphics guiGraphics, Minecraft mc, NodePoint point, int color, boolean expanded) {
        if (!isNodeVisible(point)) {
            return;
        }
        String name = getName(point.marker);
        String title = "Lv." + point.level + " " + name;
        int labelWidth = Math.min((int) Math.round(128 * point.scale()), mc.font.width(title) + 14);
        int labelOffset = (int) Math.round(22 * point.scale());
        int labelX = point.x + labelOffset;
        int labelY = point.y - 9;
        guiGraphics.fill(labelX, labelY, labelX + labelWidth, labelY + 18, expanded ? 0xFFF4E8C9 : 0xFFF7EFD9);
        drawBorder(guiGraphics, labelX, labelY, labelX + labelWidth, labelY + 18, expanded ? color : 0xFFB79D77);
        guiGraphics.drawString(mc.font, Component.literal(trimToWidth(mc, title, labelWidth - 9)), labelX + 5, labelY + 5, expanded ? COLOR_TEXT : COLOR_TEXT_MUTED, false);
    }

    private String trimToWidth(Minecraft mc, String text, int width) {
        if (mc.font.width(text) <= width) {
            return text;
        }
        String suffix = "...";
        int suffixWidth = mc.font.width(suffix);
        String value = text;
        while (!value.isEmpty() && mc.font.width(value) + suffixWidth > width) {
            value = value.substring(0, value.length() - 1);
        }
        return value + suffix;
    }

    private boolean isNodeVisible(NodePoint point) {
        int mapStartY = getY() + 36;
        int mapEndY = getY() + height - 12;
        int radius = Math.max(12, (int) Math.round((NODE_RADIUS + 10) * point.scale()));
        return point.y + radius >= mapStartY && point.y - radius <= mapEndY;
    }

    private int[] clipLine(int x1, int y1, int x2, int y2, int left, int top, int right, int bottom) {
        final int leftCode = 1;
        final int rightCode = 2;
        final int bottomCode = 4;
        final int topCode = 8;
        int code1 = computeCode(x1, y1, left, top, right, bottom);
        int code2 = computeCode(x2, y2, left, top, right, bottom);
        while (true) {
            if ((code1 | code2) == 0) {
                return new int[]{x1, y1, x2, y2};
            } else if ((code1 & code2) != 0) {
                return null;
            } else {
                int codeOut = code1 != 0 ? code1 : code2;
                int x = 0;
                int y = 0;
                if ((codeOut & topCode) != 0) {
                    x = x1 + (x2 - x1) * (top - y1) / Math.max(1, y2 - y1);
                    y = top;
                } else if ((codeOut & bottomCode) != 0) {
                    x = x1 + (x2 - x1) * (bottom - y1) / Math.max(1, y2 - y1);
                    y = bottom;
                } else if ((codeOut & rightCode) != 0) {
                    y = y1 + (y2 - y1) * (right - x1) / Math.max(1, x2 - x1);
                    x = right;
                } else if ((codeOut & leftCode) != 0) {
                    y = y1 + (y2 - y1) * (left - x1) / Math.max(1, x2 - x1);
                    x = left;
                }
                if (codeOut == code1) {
                    x1 = x;
                    y1 = y;
                    code1 = computeCode(x1, y1, left, top, right, bottom);
                } else {
                    x2 = x;
                    y2 = y;
                    code2 = computeCode(x2, y2, left, top, right, bottom);
                }
            }
        }
    }

    private int computeCode(int x, int y, int left, int top, int right, int bottom) {
        int code = 0;
        if (x < left) {
            code |= 1;
        } else if (x > right) {
            code |= 2;
        }
        if (y < top) {
            code |= 8;
        } else if (y > bottom) {
            code |= 4;
        }
        return code;
    }

    private void drawLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            guiGraphics.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x1 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y1 += sy;
            }
        }
    }

    private void drawThickLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
        drawLine(guiGraphics, x1, y1, x2, y2, color);
        drawLine(guiGraphics, x1 + 1, y1, x2 + 1, y2, color);
        drawLine(guiGraphics, x1, y1 + 1, x2, y2 + 1, color);
    }

    private void renderHoverInfo(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (hoveredMarker == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int level = parseLevel(hoveredMarker);
        String title = "Lv." + level + " " + getName(hoveredMarker);
        Component status = getStatus(level);
        int maxWidth = Math.max(mc.font.width(title), mc.font.width(status)) + 18;
        int boxX = mouseX + 12;
        int boxY = mouseY + 14;
        if (boxX + maxWidth > mc.getWindow().getGuiScaledWidth()) {
            boxX = mouseX - maxWidth - 12;
        }
        if (boxY + 36 > mc.getWindow().getGuiScaledHeight()) {
            boxY = mouseY - 42;
        }
        guiGraphics.fill(boxX, boxY, boxX + maxWidth, boxY + 36, 0xFFF4E8C9);
        drawBorder(guiGraphics, boxX, boxY, boxX + maxWidth, boxY + 36, 0xFFB79D77);
        guiGraphics.drawString(mc.font, Component.literal(title), boxX + 8, boxY + 7, COLOR_TEXT, false);
        guiGraphics.drawString(mc.font, status, boxX + 8, boxY + 21, getStateColor(level, cityLevel), false);
    }

    @Override
    protected void updateWidgetNarration(@Nonnull NarrationElementOutput narrationElementOutput) {
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= getX() && mouseX <= getX() + width && mouseY >= getY() && mouseY <= getY() + height) {
            int screenWidth = nn(Minecraft.getInstance().screen).width;
            int panelWidth = Math.max(250, Math.min(330, screenWidth / 3));
            if (mouseX >= screenWidth - panelWidth - 18) {
                return false;
            }
            MapMarker clickedMarker = getMarkerAtPosition(mouseX, mouseY);
            if (clickedMarker != null) {
                selectedMarker = selectedMarker == clickedMarker ? null : clickedMarker;
                return true;
            }
            int mapStartX = getX() + 12;
            int mapStartY = getY() + 36;
            int mapEndX = getX() + width - 12;
            int mapEndY = getY() + height - 12;
            if (mouseX >= mapStartX && mouseX <= mapEndX && mouseY >= mapStartY && mouseY <= mapEndY) {
                if (selectedMarker != null) {
                    selectedMarker = null;
                    return true;
                }
                isDragging = true;
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                return true;
            }
        }
        return false;
    }

    private MapMarker getMarkerAtPosition(double mouseX, double mouseY) {
        List<NodePoint> points = buildNodePoints(getX() + 12, getY() + 36, width - 24, height - 48);
        for (NodePoint point : points) {
            if (!isNodeVisible(point)) {
                continue;
            }
            int hitRadius = (int) Math.round(NODE_HIT_RADIUS * point.scale());
            if (Math.abs(mouseX - point.x) <= hitRadius && Math.abs(mouseY - point.y) <= hitRadius) {
                return point.marker;
            }
        }
        return null;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDragging = false;
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
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double scale = getAdaptiveScale(width, height);
        int itemGap = (int) Math.round(64 * scale);
        int nodeCount = markers.size();
        int contentHeight = nodeCount * itemGap;
        int viewHeight = height - 80;
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        scrollOffsetY += delta * itemGap * 0.5;
        if (scrollOffsetY > 0) {
            scrollOffsetY = 0;
        }
        if (scrollOffsetY < -maxScroll) {
            scrollOffsetY = -maxScroll;
        }
        return true;
    }

    public double getZoomLevel() {
        return zoomLevel;
    }

    public void setZoomLevel(double zoomLevel) {
        this.zoomLevel = Math.max(4.0, Math.min(8.0, zoomLevel));
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

    public MapMarker getSelectedMarker() {
        return selectedMarker;
    }

    public void setSelectedMarker(MapMarker selectedMarker) {
        this.selectedMarker = selectedMarker;
    }

    private int parseLevel(MapMarker marker) {
        String hoverText = marker.getHoverText();
        int index = hoverText.indexOf(":");
        if (index <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(hoverText.substring(0, index));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String getName(MapMarker marker) {
        String hoverText = marker.getHoverText();
        int index = hoverText.indexOf(":");
        return index >= 0 ? hoverText.substring(index + 1) : hoverText;
    }

    private Component getStatus(int level) {
        if (level <= cityLevel) {
            return Component.translatable("gui.city_upgrade.status_completed");
        }
        if (level == cityLevel + 1) {
            return Component.translatable("gui.city_upgrade.status_available");
        }
        return Component.translatable("gui.city_upgrade.status_locked");
    }

    private int getStateColor(int level, int currentLevel) {
        if (level <= currentLevel) {
            return COLOR_COMPLETE;
        }
        if (level == currentLevel + 1) {
            return COLOR_AVAILABLE;
        }
        return COLOR_LOCKED;
    }

    private void drawBorder(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
        guiGraphics.fill(left, top, right, top + 1, color);
        guiGraphics.fill(left, bottom - 1, right, bottom, color);
        guiGraphics.fill(left, top, left + 1, bottom, color);
        guiGraphics.fill(right - 1, top, right, bottom, color);
    }

    private record NodePoint(MapMarker marker, int level, int x, int y, int index, double scale, int visualCol, int columns) {
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }
}
