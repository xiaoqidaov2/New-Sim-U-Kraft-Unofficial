package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.GetStockMarketInfoPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.StockTradeActionPacket;
import com.xiaoliang.simukraft.world.StockMarketData;
import com.xiaoliang.simukraft.world.StockMarketService;
import net.minecraft.client.Minecraft;
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
import java.util.UUID;
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
    private static final int CITY_LIST_ENTRY_HEIGHT = 22;

    private final BlockPos controlBoxPos;
    @Nullable
    private EditBox buySharesField;
    @Nullable
    private EditBox sellSharesField;
    @Nullable
    private Button sellButton;
    @Nullable
    private Button sellAllButton;
    private final List<StockMarketService.CityStockSnapshot> markets = new ArrayList<>();
    private String localMessage = "";
    private boolean marketInfoLoaded;
    private int currentDay;
    private double playerFunds;
    private int chartScrollOffset;
    private double chartDragRemainderX;
    private int cityListScrollOffset;
    @Nullable
    private UUID selectedMarketCityId;
    @Nullable
    private UUID currentMarketCityId;
    private String currentMarketCityName = "";

    public BankStockMarketScreen(BlockPos controlBoxPos) {
        super(Component.translatable("gui.bank_stock_market.title"));
        GuiScaleManager.applyFixedScale(2);
        this.controlBoxPos = controlBoxPos;
        playOpenSound();
    }

    private void playOpenSound() {
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
                .play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));
    }

    @Override
    protected void init() {
        GuiScaleManager.applyFixedScale(2);
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

        this.sellButton = this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.sell")),
                button -> submitTrade(StockTradeActionPacket.ActionType.SELL, requireSellSharesField())
        ).bounds(centerX - 80, bottomY, 50, 18).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.buy_all")),
                button -> fillMaxBuyableShares()
        ).bounds(centerX - 25, bottomY - 25, 70, 18).build()));

        this.sellAllButton = this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.sell_all")),
                button -> requireSellSharesField().setValue(String.valueOf(getSelectedOwnedShares()))
        ).bounds(centerX - 25, bottomY, 70, 18).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.bank_stock_market.refresh")),
                button -> requestMarketInfo()
        ).bounds(centerX + 55, bottomY - 25, 70, 43).build()));

        this.setInitialFocus(requireBuySharesField());
        requestMarketInfo();
        updateTradeButtonsState();
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
        StockMarketService.CityStockSnapshot selectedMarket = getSelectedMarket();
        double currentPrice = selectedMarket == null ? 1.0D : selectedMarket.currentPrice();
        int ownedShares = selectedMarket == null ? 0 : selectedMarket.ownedShares();

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
                nn(Component.translatable("gui.bank_stock_market.current_city", getCurrentMarketCityLabel())),
                chartX, 36, 0xFF7FC8FF, false);
        safeGuiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_stock_market.selected_city", getSelectedMarketName())),
                chartX + 150, 36, 0xFFFFD27F, false);
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

        if (!marketInfoLoaded || selectedMarket == null || selectedMarket.history().isEmpty()) {
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

        renderCityMarketList(safeGuiGraphics, mouseX, mouseY);

        if (!canSellSelectedMarket()) {
            safeGuiGraphics.drawString(nn(this.font),
                    nn(Component.translatable("gui.bank_stock_market.sell_locked", getCurrentMarketCityLabel())),
                    getCityListX(), getCityListY() + getCityListHeight() + 8, 0xFFFF8888, false);
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
        double fallbackPrice = getSelectedCurrentPrice();
        double maxPrice = visibleHistory.stream().mapToDouble(StockMarketData.StockCandle::high).max().orElse(fallbackPrice);
        double minPrice = visibleHistory.stream().mapToDouble(StockMarketData.StockCandle::low).min().orElse(fallbackPrice);
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

        double fallbackPrice = getSelectedCurrentPrice();
        double maxPrice = visibleHistory.stream().mapToDouble(StockMarketData.StockCandle::high).max().orElse(fallbackPrice);
        double minPrice = visibleHistory.stream().mapToDouble(StockMarketData.StockCandle::low).min().orElse(fallbackPrice);
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
        return this.width / 2 - 190;
    }

    private int getChartY() {
        return 42;
    }

    private int getChartWidth() {
        return 250;
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
        return Math.max(0, getSelectedHistory().size() - MAX_VISIBLE_CANDLES);
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
        List<StockMarketData.StockCandle> selectedHistory = getSelectedHistory();
        if (selectedHistory.isEmpty()) {
            return;
        }
        StockMarketData.StockCandle latest = selectedHistory.get(selectedHistory.size() - 1);
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
        List<StockMarketData.StockCandle> selectedHistory = getSelectedHistory();
        int size = selectedHistory.size();
        int toIndex = size - chartScrollOffset;
        int fromIndex = Math.max(0, toIndex - MAX_VISIBLE_CANDLES);
        return selectedHistory.subList(fromIndex, Math.max(fromIndex, toIndex));
    }

    public void updateMarketInfo(BlockPos responseControlBoxPos, StockMarketService.StockMarketSnapshot snapshot) {
        if (!this.controlBoxPos.equals(responseControlBoxPos)) {
            return;
        }

        this.currentDay = snapshot.currentDay();
        this.playerFunds = snapshot.playerFunds();
        this.currentMarketCityId = snapshot.currentMarketCityId();
        this.currentMarketCityName = snapshot.currentMarketCityName();
        this.markets.clear();
        this.markets.addAll(snapshot.markets());
        this.selectedMarketCityId = resolveNextSelectedMarketId();
        this.cityListScrollOffset = Math.max(0, Math.min(cityListScrollOffset, getMaxCityListScrollOffset()));
        clampChartScrollOffset();
        this.marketInfoLoaded = true;
        this.localMessage = "";
        updateTradeButtonsState();
    }

    private void requestMarketInfo() {
        NetworkManager.sendToServer(new GetStockMarketInfoPacket(controlBoxPos));
    }

    private void submitTrade(StockTradeActionPacket.ActionType actionType, EditBox field) {
        StockMarketService.CityStockSnapshot selectedMarket = getSelectedMarket();
        if (selectedMarket == null) {
            this.localMessage = "gui.bank_stock_market.error.no_market";
            return;
        }
        if (actionType == StockTradeActionPacket.ActionType.SELL && !canSellSelectedMarket()) {
            this.localMessage = "gui.bank_stock_market.error.sell_wrong_city";
            return;
        }
        int quantity = parseQuantity(field);
        if (quantity <= 0) {
            return;
        }
        this.localMessage = "";
        NetworkManager.sendToServer(new StockTradeActionPacket(controlBoxPos, selectedMarket.cityId(), actionType, quantity));
    }

    private void fillMaxBuyableShares() {
        StockMarketService.CityStockSnapshot selectedMarket = getSelectedMarket();
        double currentPrice = selectedMarket == null ? 1.0D : selectedMarket.currentPrice();
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
        if (isMouseOverCityList(mouseX, mouseY) && getMaxCityListScrollOffset() > 0) {
            int scrollDelta = delta < 0 ? 1 : -1;
            cityListScrollOffset = Math.max(0, Math.min(cityListScrollOffset + scrollDelta, getMaxCityListScrollOffset()));
            return true;
        }
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handleCityListClick(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        closeScreen();
    }

    private void closeScreen() {
        GuiScaleManager.forceRestore();
        nn(this.minecraft).setScreen(new BankControlBoxScreen(controlBoxPos));
    }

    @Override
    public void resize(@Nonnull Minecraft minecraft, int width, int height) {
        GuiScaleManager.applyFixedScale(2);
        super.resize(minecraft, width, height);
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

    @Nullable
    private StockMarketService.CityStockSnapshot getSelectedMarket() {
        if (markets.isEmpty()) {
            return null;
        }
        UUID targetId = selectedMarketCityId != null ? selectedMarketCityId : resolveNextSelectedMarketId();
        for (StockMarketService.CityStockSnapshot market : markets) {
            if (market.cityId().equals(targetId)) {
                return market;
            }
        }
        return markets.get(0);
    }

    @Nonnull
    private List<StockMarketData.StockCandle> getSelectedHistory() {
        StockMarketService.CityStockSnapshot selectedMarket = getSelectedMarket();
        return selectedMarket == null ? List.of() : selectedMarket.history();
    }

    private int getSelectedOwnedShares() {
        StockMarketService.CityStockSnapshot selectedMarket = getSelectedMarket();
        return selectedMarket == null ? 0 : selectedMarket.ownedShares();
    }

    private double getSelectedCurrentPrice() {
        StockMarketService.CityStockSnapshot selectedMarket = getSelectedMarket();
        return selectedMarket == null ? 1.0D : selectedMarket.currentPrice();
    }

    private boolean canSellSelectedMarket() {
        StockMarketService.CityStockSnapshot selectedMarket = getSelectedMarket();
        return selectedMarket != null && currentMarketCityId != null && currentMarketCityId.equals(selectedMarket.cityId());
    }

    private void updateTradeButtonsState() {
        if (sellButton != null) {
            sellButton.active = canSellSelectedMarket();
        }
        if (sellAllButton != null) {
            sellAllButton.active = canSellSelectedMarket();
        }
    }

    @Nullable
    private UUID resolveNextSelectedMarketId() {
        if (markets.isEmpty()) {
            return null;
        }
        if (selectedMarketCityId != null) {
            for (StockMarketService.CityStockSnapshot market : markets) {
                if (market.cityId().equals(selectedMarketCityId)) {
                    return selectedMarketCityId;
                }
            }
        }
        if (currentMarketCityId != null) {
            for (StockMarketService.CityStockSnapshot market : markets) {
                if (market.cityId().equals(currentMarketCityId)) {
                    return currentMarketCityId;
                }
            }
        }
        return markets.get(0).cityId();
    }

    private String getSelectedMarketName() {
        StockMarketService.CityStockSnapshot selectedMarket = getSelectedMarket();
        return selectedMarket == null ? "-" : selectedMarket.cityName();
    }

    private String getCurrentMarketCityLabel() {
        return currentMarketCityName == null || currentMarketCityName.isBlank() ? "-" : currentMarketCityName;
    }

    private void renderCityMarketList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = getCityListX();
        int y = getCityListY();
        int width = getCityListWidth();
        int height = getCityListHeight();
        guiGraphics.fill(x, y, x + width, y + height, 0x66222222);
        guiGraphics.fill(x, y, x + width, y + 1, 0x99FFFFFF);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0x99FFFFFF);
        guiGraphics.fill(x, y, x + 1, y + height, 0x99FFFFFF);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0x99FFFFFF);
        guiGraphics.drawString(nn(this.font), Component.translatable("gui.bank_stock_market.city_list"), x + 6, y + 6, 0xFFFFFFFF, false);

        int listY = y + 20;
        int visibleCount = Math.max(1, (height - 24) / CITY_LIST_ENTRY_HEIGHT);
        int endIndex = Math.min(markets.size(), cityListScrollOffset + visibleCount);
        for (int i = cityListScrollOffset; i < endIndex; i++) {
            StockMarketService.CityStockSnapshot market = markets.get(i);
            int rowY = listY + (i - cityListScrollOffset) * CITY_LIST_ENTRY_HEIGHT;
            boolean selected = market.cityId().equals(selectedMarketCityId);
            boolean hovered = mouseX >= x + 4 && mouseX <= x + width - 4 && mouseY >= rowY && mouseY <= rowY + CITY_LIST_ENTRY_HEIGHT - 2;
            int background = selected ? 0x885A7FFF : hovered ? 0x55444444 : 0x33202020;
            guiGraphics.fill(x + 4, rowY, x + width - 4, rowY + CITY_LIST_ENTRY_HEIGHT - 2, background);
            guiGraphics.drawString(nn(this.font), truncateText(market.cityName(), 12), x + 8, rowY + 3, 0xFFFFFFFF, false);
            guiGraphics.drawString(nn(this.font),
                    Component.literal(formatCurrency(market.currentPrice())),
                    x + 8, rowY + 12, 0xFF80FF80, false);
            guiGraphics.drawString(nn(this.font),
                    Component.literal(String.valueOf(market.ownedShares())),
                    x + width - 22, rowY + 12, 0xFF7FC8FF, false);
        }
    }

    private boolean handleCityListClick(double mouseX, double mouseY) {
        if (!isMouseOverCityList(mouseX, mouseY)) {
            return false;
        }
        int relativeY = (int) mouseY - (getCityListY() + 20);
        if (relativeY < 0) {
            return false;
        }
        int index = cityListScrollOffset + (relativeY / CITY_LIST_ENTRY_HEIGHT);
        if (index < 0 || index >= markets.size()) {
            return false;
        }
        selectedMarketCityId = markets.get(index).cityId();
        chartScrollOffset = 0;
        chartDragRemainderX = 0.0D;
        localMessage = "";
        updateTradeButtonsState();
        return true;
    }

    private boolean isMouseOverCityList(double mouseX, double mouseY) {
        int x = getCityListX();
        int y = getCityListY();
        return mouseX >= x && mouseX <= x + getCityListWidth()
                && mouseY >= y && mouseY <= y + getCityListHeight();
    }

    private int getMaxCityListScrollOffset() {
        int visibleCount = Math.max(1, (getCityListHeight() - 24) / CITY_LIST_ENTRY_HEIGHT);
        return Math.max(0, markets.size() - visibleCount);
    }

    private int getCityListX() {
        return getChartX() + getChartWidth() + 12;
    }

    private int getCityListY() {
        return getChartY();
    }

    private int getCityListWidth() {
        return 128;
    }

    private int getCityListHeight() {
        return getVolumeChartY() + getVolumeChartHeight() - getChartY();
    }

    private String truncateText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "~";
    }
}
