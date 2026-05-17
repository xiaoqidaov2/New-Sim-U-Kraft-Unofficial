package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.GetStockMarketInfoPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.StockTradeActionPacket;
import com.xiaoliang.simukraft.world.StockMarketData;
import com.xiaoliang.simukraft.world.StockMarketService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
@SuppressWarnings("null")
/**
 * 银行股票界面
 * 显示最近行情的K线图与成交量柱状图，并提供基础买卖能力。
 */
public class BankStockMarketScreen extends AbstractTransitionScreen {
    private static final int ENTER_KEY_CODE = 257;
    private static final int MAX_VISIBLE_CANDLES = 50;
    private static final int MIN_SLOT_WIDTH = 6;
    private static final int MAX_CANDLE_BODY_WIDTH = 6;
    private static final int MAX_VOLUME_BAR_WIDTH = 4;
    private static final double DRAG_PIXELS_PER_SCROLL_STEP = 12.0D;

    private final BlockPos controlBoxPos;
    @Nullable
    private EditBox buySharesField;
    @Nullable
    private EditBox sellSharesField;
    private final List<StockMarketData.StockCandle> history = new ArrayList<>();
    private String localMessage = "";
    private boolean marketInfoLoaded;
    private int currentDay;
    private double currentPrice = 1.0D;
    private int ownedShares;
    private double playerFunds;
    private int chartScrollOffset;
    private double chartDragRemainderX;

    public BankStockMarketScreen(BlockPos controlBoxPos) {
        super(Component.translatable("gui.bank_stock_market.title"));
        this.controlBoxPos = controlBoxPos;
        playOpenSound();
    }

    private void playOpenSound() {
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
                .play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int bottomY = this.height - 30;
        var fontRenderer = nn(this.font);

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.back")),
                button -> closeScreen()
        ).bounds(10, 10, 55, 20).build()));

        this.buySharesField = nn(new EditBox(fontRenderer, centerX - 160, bottomY - 25, 70, 18,
                nn(Component.translatable("gui.bank_stock_market.buy_amount"))));
        requireBuySharesField().setMaxLength(8);
        this.addWidget(requireBuySharesField());

        this.sellSharesField = nn(new EditBox(fontRenderer, centerX - 160, bottomY, 70, 18,
                nn(Component.translatable("gui.bank_stock_market.sell_amount"))));
        requireSellSharesField().setMaxLength(8);
        this.addWidget(requireSellSharesField());

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.buy")),
                button -> submitTrade(StockTradeActionPacket.ActionType.BUY, requireBuySharesField())
        ).bounds(centerX - 80, bottomY - 25, 50, 18).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.sell")),
                button -> submitTrade(StockTradeActionPacket.ActionType.SELL, requireSellSharesField())
        ).bounds(centerX - 80, bottomY, 50, 18).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.buy_all")),
                button -> fillMaxBuyableShares()
        ).bounds(centerX - 25, bottomY - 25, 70, 18).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.sell_all")),
                button -> requireSellSharesField().setValue(String.valueOf(ownedShares))
        ).bounds(centerX - 25, bottomY, 70, 18).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.refresh")),
                button -> requestMarketInfo()
        ).bounds(centerX + 55, bottomY - 25, 70, 43).build()));

        this.setInitialFocus(requireBuySharesField());
        requestMarketInfo();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics safeGuiGraphics = nn(guiGraphics);
        super.render(safeGuiGraphics, mouseX, mouseY, partialTicks);

        int centerX = this.width / 2;
        int chartX = getChartX();
        int chartY = getChartY();
        int chartWidth = getChartWidth();
        int candleHeight = getCandleChartHeight();
        int volumeY = getVolumeChartY();
        int volumeHeight = getVolumeChartHeight();

        safeGuiGraphics.drawCenteredString(nn(this.font),
                nn(Component.translatable("gui.bank_stock_market.title")),
                centerX, 15, 0xFFFFFF);

        int labelColor = 0xFFF5F5A0;
        safeGuiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_stock_market.day", currentDay)),
                chartX, 25, labelColor, false);
        safeGuiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_stock_market.price", formatCurrency(currentPrice))),
                chartX + 90, 25, 0xFF55FF55, false);
        safeGuiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_stock_market.player_funds", formatCurrency(playerFunds))),
                chartX + 185, 25, 0xFF55FFFF, false);
        safeGuiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_stock_market.owned_shares", ownedShares)),
                chartX, volumeY + volumeHeight + 10, labelColor, false);
        safeGuiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_stock_market.current_value", formatCurrency(currentPrice * ownedShares))),
                chartX + 120, volumeY + volumeHeight + 10, 0xFFFFD27F, false);

        drawChartFrame(safeGuiGraphics, chartX, chartY, chartWidth, candleHeight,
                nn(Component.translatable("gui.bank_stock_market.kline")));
        drawVolumeFrame(safeGuiGraphics, chartX, volumeY, chartWidth, volumeHeight,
                nn(Component.translatable("gui.bank_stock_market.volume")));

        if (!marketInfoLoaded || history.isEmpty()) {
            safeGuiGraphics.drawCenteredString(nn(this.font),
                    nn(Component.translatable("gui.bank_stock_market.loading")),
                    centerX, chartY + 90, 0xAAAAAA);
        } else {
            drawCandlestickChart(safeGuiGraphics, chartX, chartY, chartWidth, candleHeight);
            drawVolumeChart(safeGuiGraphics, chartX, volumeY, chartWidth, volumeHeight);
            drawLatestChangeText(safeGuiGraphics, chartX, volumeY + volumeHeight + 24);
            renderCandlestickTooltip(safeGuiGraphics, mouseX, mouseY, chartX, chartY, chartWidth, candleHeight);
            renderVolumeTooltip(safeGuiGraphics, mouseX, mouseY, chartX, volumeY, chartWidth, volumeHeight);
        }

        if (!localMessage.isEmpty()) {
            safeGuiGraphics.drawString(nn(this.font),
                    nn(Component.translatable(localMessage)),
                    chartX, this.height - 45, 0xFFFF6666, false);
        }

        requireBuySharesField().render(safeGuiGraphics, mouseX, mouseY, partialTicks);
        requireSellSharesField().render(safeGuiGraphics, mouseX, mouseY, partialTicks);
    }

    private void drawChartFrame(GuiGraphics guiGraphics, int x, int y, int width, int height, Component title) {
        guiGraphics.fill(x, y, x + width, y + height, 0x66222222);
        guiGraphics.fill(x, y, x + width, y + 1, 0x99FFFFFF);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0x99FFFFFF);
        guiGraphics.fill(x, y, x + 1, y + height, 0x99FFFFFF);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0x99FFFFFF);
        guiGraphics.drawString(nn(this.font), title, x + 6, y + 6, 0xFFFFFFFF, false);
    }

    private void drawVolumeFrame(GuiGraphics guiGraphics, int x, int y, int width, int height, Component title) {
        drawChartFrame(guiGraphics, x, y, width, height, title);
    }

    private void drawCandlestickChart(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        List<StockMarketData.StockCandle> visibleHistory = getVisibleHistory();
        double maxPrice = visibleHistory.stream().mapToDouble(StockMarketData.StockCandle::high).max().orElse(currentPrice);
        double minPrice = visibleHistory.stream().mapToDouble(StockMarketData.StockCandle::low).min().orElse(currentPrice);
        if (Math.abs(maxPrice - minPrice) < 0.01D) {
            maxPrice += 0.5D;
            minPrice = Math.max(0.1D, minPrice - 0.5D);
        }

        int plotX = x + 8;
        int plotY = y + 18;
        int plotWidth = width - 16;
        int plotHeight = height - 28;
        int slotWidth = resolveSlotWidth(plotWidth);
        int startX = resolveSeriesStartX(plotX, plotWidth, slotWidth, visibleHistory.size());

        for (int i = 1; i <= 3; i++) {
            int guideY = plotY + i * plotHeight / 4;
            guiGraphics.fill(plotX, guideY, plotX + plotWidth, guideY + 1, 0x33444444);
        }

        guiGraphics.drawString(nn(this.font), formatCurrency(maxPrice), x + width - 46, y + 6, 0xFFD0D0D0, false);
        guiGraphics.drawString(nn(this.font), formatCurrency(minPrice), x + width - 46, y + height - 12, 0xFFD0D0D0, false);

        for (int i = 0; i < visibleHistory.size(); i++) {
            StockMarketData.StockCandle candle = visibleHistory.get(i);
            int candleX = startX + i * slotWidth + slotWidth / 2;
            int highY = mapPriceToY(candle.high(), minPrice, maxPrice, plotY, plotHeight);
            int lowY = mapPriceToY(candle.low(), minPrice, maxPrice, plotY, plotHeight);
            int openY = mapPriceToY(candle.open(), minPrice, maxPrice, plotY, plotHeight);
            int closeY = mapPriceToY(candle.close(), minPrice, maxPrice, plotY, plotHeight);
            int color = candle.close() >= candle.open() ? 0xFFFF4D4F : 0xFF00C853;
            int bodyTop = Math.min(openY, closeY);
            int bodyBottom = Math.max(openY, closeY);
            int bodyWidth = Math.min(MAX_CANDLE_BODY_WIDTH, Math.max(3, slotWidth / 2));

            guiGraphics.fill(candleX, highY, candleX + 1, lowY + 1, color);
            guiGraphics.fill(candleX - bodyWidth / 2, bodyTop, candleX + bodyWidth / 2 + 1,
                    Math.max(bodyTop + 1, bodyBottom + 1), color);

            if (i == visibleHistory.size() - 1) {
                guiGraphics.drawString(nn(this.font),
                        String.valueOf(candle.day()),
                        candleX - 6, y + height - 12, 0xFFB0B0B0, false);
            }
        }
    }

    private void drawVolumeChart(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        List<StockMarketData.StockCandle> visibleHistory = getVisibleHistory();
        long maxVolume = visibleHistory.stream().mapToLong(StockMarketData.StockCandle::volume).max().orElse(1L);
        int plotX = x + 8;
        int plotY = y + 18;
        int plotWidth = width - 16;
        int plotHeight = height - 24;
        int slotWidth = resolveSlotWidth(plotWidth);
        int startX = resolveSeriesStartX(plotX, plotWidth, slotWidth, visibleHistory.size());

        for (int i = 0; i < visibleHistory.size(); i++) {
            StockMarketData.StockCandle candle = visibleHistory.get(i);
            int barHeight = (int) Math.round((candle.volume() / (double) maxVolume) * (plotHeight - 2));
            int barWidth = Math.min(MAX_VOLUME_BAR_WIDTH, Math.max(2, slotWidth - 4));
            int barCenterX = startX + i * slotWidth + slotWidth / 2;
            int barX = barCenterX - barWidth / 2;
            int barY = plotY + plotHeight - barHeight;
            int color = candle.close() >= candle.open() ? 0x99FF4D4F : 0x9900C853;
            guiGraphics.fill(barX, barY, barX + barWidth, plotY + plotHeight, color);
        }

        guiGraphics.drawString(nn(this.font), String.valueOf(maxVolume), x + width - 46, y + 6, 0xFFD0D0D0, false);
    }

    private void renderVolumeTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y, int width, int height) {
        StockMarketData.StockCandle hoveredCandle = findHoveredVolumeCandle(mouseX, mouseY, x, y, width, height);
        if (hoveredCandle == null) {
            return;
        }

        double changeAmount = roundToCurrency(hoveredCandle.close() - hoveredCandle.open());
        double baseOpen = Math.max(0.01D, hoveredCandle.open());
        double changeRate = (changeAmount / baseOpen) * 100.0D;
        boolean isUp = changeAmount > 0.0D;
        boolean isDown = changeAmount < 0.0D;
        String actionKey = isUp
                ? "gui.bank_stock_market.tooltip.change_up"
                : isDown ? "gui.bank_stock_market.tooltip.change_down" : "gui.bank_stock_market.tooltip.change_flat";
        String rateKey = isUp
                ? "gui.bank_stock_market.tooltip.rate_up"
                : isDown ? "gui.bank_stock_market.tooltip.rate_down" : "gui.bank_stock_market.tooltip.rate_flat";

        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.translatable("gui.bank_stock_market.tooltip.day", hoveredCandle.day()));
        tooltip.add(Component.translatable(actionKey, formatCurrency(Math.abs(changeAmount))));
        tooltip.add(Component.translatable(rateKey, formatPercent(Math.abs(changeRate))));
        tooltip.add(Component.translatable("gui.bank_stock_market.tooltip.volume", hoveredCandle.volume()));
        guiGraphics.renderTooltip(nn(this.font), tooltip, java.util.Optional.empty(), mouseX, mouseY);
    }

    private void renderCandlestickTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y, int width, int height) {
        StockMarketData.StockCandle hoveredCandle = findHoveredCandlestick(mouseX, mouseY, x, y, width, height);
        if (hoveredCandle == null) {
            return;
        }

        double changeAmount = roundToCurrency(hoveredCandle.close() - hoveredCandle.open());
        double baseOpen = Math.max(0.01D, hoveredCandle.open());
        double changeRate = (changeAmount / baseOpen) * 100.0D;
        boolean isUp = changeAmount > 0.0D;
        boolean isDown = changeAmount < 0.0D;
        String actionKey = isUp
                ? "gui.bank_stock_market.tooltip.change_up"
                : isDown ? "gui.bank_stock_market.tooltip.change_down" : "gui.bank_stock_market.tooltip.change_flat";
        String rateKey = isUp
                ? "gui.bank_stock_market.tooltip.rate_up"
                : isDown ? "gui.bank_stock_market.tooltip.rate_down" : "gui.bank_stock_market.tooltip.rate_flat";

        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.translatable("gui.bank_stock_market.tooltip.day", hoveredCandle.day()));
        tooltip.add(Component.translatable("gui.bank_stock_market.tooltip.open", formatCurrency(hoveredCandle.open())));
        tooltip.add(Component.translatable("gui.bank_stock_market.tooltip.high", formatCurrency(hoveredCandle.high())));
        tooltip.add(Component.translatable("gui.bank_stock_market.tooltip.low", formatCurrency(hoveredCandle.low())));
        tooltip.add(Component.translatable("gui.bank_stock_market.tooltip.close", formatCurrency(hoveredCandle.close())));
        tooltip.add(Component.translatable(actionKey, formatCurrency(Math.abs(changeAmount))));
        tooltip.add(Component.translatable(rateKey, formatPercent(Math.abs(changeRate))));
        guiGraphics.renderTooltip(nn(this.font), tooltip, java.util.Optional.empty(), mouseX, mouseY);
    }

    @Nullable
    private StockMarketData.StockCandle findHoveredCandlestick(int mouseX, int mouseY, int x, int y, int width, int height) {
        List<StockMarketData.StockCandle> visibleHistory = getVisibleHistory();
        if (visibleHistory.isEmpty()) {
            return null;
        }

        double maxPrice = visibleHistory.stream().mapToDouble(StockMarketData.StockCandle::high).max().orElse(currentPrice);
        double minPrice = visibleHistory.stream().mapToDouble(StockMarketData.StockCandle::low).min().orElse(currentPrice);
        if (Math.abs(maxPrice - minPrice) < 0.01D) {
            maxPrice += 0.5D;
            minPrice = Math.max(0.1D, minPrice - 0.5D);
        }

        int plotX = x + 8;
        int plotY = y + 18;
        int plotWidth = width - 16;
        int plotHeight = height - 28;
        int slotWidth = resolveSlotWidth(plotWidth);
        int startX = resolveSeriesStartX(plotX, plotWidth, slotWidth, visibleHistory.size());

        if (mouseX < plotX || mouseX > plotX + plotWidth || mouseY < plotY || mouseY > plotY + plotHeight) {
            return null;
        }

        for (int i = 0; i < visibleHistory.size(); i++) {
            StockMarketData.StockCandle candle = visibleHistory.get(i);
            int candleX = startX + i * slotWidth + slotWidth / 2;
            int highY = mapPriceToY(candle.high(), minPrice, maxPrice, plotY, plotHeight);
            int lowY = mapPriceToY(candle.low(), minPrice, maxPrice, plotY, plotHeight);
            int openY = mapPriceToY(candle.open(), minPrice, maxPrice, plotY, plotHeight);
            int closeY = mapPriceToY(candle.close(), minPrice, maxPrice, plotY, plotHeight);
            int bodyTop = Math.min(openY, closeY);
            int bodyBottom = Math.max(openY, closeY);
            int bodyWidth = Math.min(MAX_CANDLE_BODY_WIDTH, Math.max(3, slotWidth / 2));
            int left = candleX - bodyWidth / 2 - 2;
            int right = candleX + bodyWidth / 2 + 2;
            int top = Math.min(highY, bodyTop);
            int bottom = Math.max(lowY, bodyBottom);
            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
                return candle;
            }
        }
        return null;
    }

    @Nullable
    private StockMarketData.StockCandle findHoveredVolumeCandle(int mouseX, int mouseY, int x, int y, int width, int height) {
        List<StockMarketData.StockCandle> visibleHistory = getVisibleHistory();
        if (visibleHistory.isEmpty()) {
            return null;
        }

        long maxVolume = visibleHistory.stream().mapToLong(StockMarketData.StockCandle::volume).max().orElse(1L);
        int plotX = x + 8;
        int plotY = y + 18;
        int plotWidth = width - 16;
        int plotHeight = height - 24;
        int slotWidth = resolveSlotWidth(plotWidth);
        int startX = resolveSeriesStartX(plotX, plotWidth, slotWidth, visibleHistory.size());

        if (mouseX < plotX || mouseX > plotX + plotWidth || mouseY < plotY || mouseY > plotY + plotHeight) {
            return null;
        }

        for (int i = 0; i < visibleHistory.size(); i++) {
            StockMarketData.StockCandle candle = visibleHistory.get(i);
            int barHeight = (int) Math.round((candle.volume() / (double) maxVolume) * (plotHeight - 2));
            int barWidth = Math.min(MAX_VOLUME_BAR_WIDTH, Math.max(2, slotWidth - 4));
            int barCenterX = startX + i * slotWidth + slotWidth / 2;
            int barX = barCenterX - barWidth / 2;
            int barY = plotY + plotHeight - barHeight;
            if (mouseX >= barX && mouseX <= barX + barWidth && mouseY >= barY && mouseY <= plotY + plotHeight) {
                return candle;
            }
        }
        return null;
    }

    private int resolveSlotWidth(int plotWidth) {
        return Math.max(MIN_SLOT_WIDTH, plotWidth / MAX_VISIBLE_CANDLES);
    }

    private int resolveSeriesStartX(int plotX, int plotWidth, int slotWidth, int itemCount) {
        int seriesWidth = slotWidth * Math.max(1, itemCount);
        return plotX + Math.max(0, plotWidth - seriesWidth);
    }

    private int getChartX() {
        return this.width / 2 - 165;
    }

    private int getChartY() {
        return 42;
    }

    private int getChartWidth() {
        return 330;
    }

    private int getCandleChartHeight() {
        return 145;
    }

    private int getVolumeChartY() {
        return getChartY() + getCandleChartHeight() + 18;
    }

    private int getVolumeChartHeight() {
        return 70;
    }

    private boolean isMouseOverChartArea(double mouseX, double mouseY) {
        int left = getChartX();
        int right = left + getChartWidth();
        int top = getChartY();
        int bottom = getVolumeChartY() + getVolumeChartHeight();
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    private int getMaxChartScrollOffset() {
        return Math.max(0, history.size() - MAX_VISIBLE_CANDLES);
    }

    private void clampChartScrollOffset() {
        chartScrollOffset = Math.max(0, Math.min(chartScrollOffset, getMaxChartScrollOffset()));
    }

    private boolean scrollChartBy(int delta) {
        if (delta == 0) {
            return false;
        }
        int previousOffset = chartScrollOffset;
        chartScrollOffset += delta;
        clampChartScrollOffset();
        return previousOffset != chartScrollOffset;
    }

    private void drawLatestChangeText(GuiGraphics guiGraphics, int x, int y) {
        StockMarketData.StockCandle latest = history.get(history.size() - 1);
        int color = latest.dailyChange() >= 0 ? 0xFFFF8080 : 0xFF80FF80;
        guiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_stock_market.daily_change", latest.dailyChange())),
                x, y, color, false);
    }

    private int mapPriceToY(double price, double minPrice, double maxPrice, int plotY, int plotHeight) {
        double progress = (price - minPrice) / (maxPrice - minPrice);
        return plotY + plotHeight - (int) Math.round(progress * plotHeight);
    }

    private List<StockMarketData.StockCandle> getVisibleHistory() {
        clampChartScrollOffset();
        int size = history.size();
        int toIndex = size - chartScrollOffset;
        int fromIndex = Math.max(0, toIndex - MAX_VISIBLE_CANDLES);
        return history.subList(fromIndex, Math.max(fromIndex, toIndex));
    }

    public void updateMarketInfo(BlockPos responseControlBoxPos, StockMarketService.StockMarketSnapshot snapshot) {
        if (!this.controlBoxPos.equals(responseControlBoxPos)) {
            return;
        }

        this.currentDay = snapshot.currentDay();
        this.currentPrice = snapshot.currentPrice();
        this.ownedShares = snapshot.ownedShares();
        this.playerFunds = snapshot.playerFunds();
        this.history.clear();
        this.history.addAll(snapshot.history());
        clampChartScrollOffset();
        this.marketInfoLoaded = true;
        this.localMessage = "";
    }

    private void requestMarketInfo() {
        NetworkManager.sendToServer(new GetStockMarketInfoPacket(controlBoxPos));
    }

    private void submitTrade(StockTradeActionPacket.ActionType actionType, EditBox field) {
        int quantity = parseQuantity(field);
        if (quantity <= 0) {
            return;
        }
        this.localMessage = "";
        NetworkManager.sendToServer(new StockTradeActionPacket(controlBoxPos, actionType, quantity));
    }

    private void fillMaxBuyableShares() {
        int maxShares = (int) Math.floor(playerFunds / Math.max(0.01D, currentPrice));
        requireBuySharesField().setValue(String.valueOf(Math.max(0, maxShares)));
    }

    private int parseQuantity(EditBox field) {
        String raw = field.getValue().trim();
        if (raw.isEmpty()) {
            this.localMessage = "gui.bank_stock_market.error.empty_quantity";
            return 0;
        }
        try {
            int quantity = Integer.parseInt(raw);
            if (quantity <= 0) {
                this.localMessage = "gui.bank_stock_market.error.invalid_quantity";
                return 0;
            }
            return quantity;
        } catch (NumberFormatException exception) {
            this.localMessage = "gui.bank_stock_market.error.invalid_quantity";
            return 0;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == ENTER_KEY_CODE) {
            if (requireSellSharesField().isFocused()) {
                submitTrade(StockTradeActionPacket.ActionType.SELL, requireSellSharesField());
            } else {
                submitTrade(StockTradeActionPacket.ActionType.BUY, requireBuySharesField());
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isMouseOverChartArea(mouseX, mouseY) && getMaxChartScrollOffset() > 0) {
            int scrollDelta = delta < 0 ? 1 : -1;
            if (scrollChartBy(scrollDelta)) {
                chartDragRemainderX = 0.0D;
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && isMouseOverChartArea(mouseX, mouseY) && getMaxChartScrollOffset() > 0) {
            chartDragRemainderX += dragX;
            int steps = (int) (chartDragRemainderX / DRAG_PIXELS_PER_SCROLL_STEP);
            if (steps != 0) {
                chartDragRemainderX -= steps * DRAG_PIXELS_PER_SCROLL_STEP;
                if (scrollChartBy(-steps)) {
                    return true;
                }
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        chartDragRemainderX = 0.0D;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        closeScreen();
    }

    private void closeScreen() {
        nn(this.minecraft).setScreen(new BankControlBoxScreen(controlBoxPos));
    }

    @Nonnull
    private EditBox requireBuySharesField() {
        return nn(this.buySharesField);
    }

    @Nonnull
    private EditBox requireSellSharesField() {
        return nn(this.sellSharesField);
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    private static String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    private static String formatPercent(double amount) {
        return String.format(Locale.US, "%.2f%%", amount);
    }

    private static double roundToCurrency(double amount) {
        return Math.round(amount * 100.0D) / 100.0D;
    }
}
