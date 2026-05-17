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
    private static final int MAX_VISIBLE_CANDLES = 20;

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
        int chartX = centerX - 165;
        int chartY = 42;
        int chartWidth = 330;
        int candleHeight = 145;
        int volumeY = chartY + candleHeight + 18;
        int volumeHeight = 70;

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
        int slotWidth = Math.max(8, plotWidth / Math.max(1, visibleHistory.size()));

        for (int i = 1; i <= 3; i++) {
            int guideY = plotY + i * plotHeight / 4;
            guiGraphics.fill(plotX, guideY, plotX + plotWidth, guideY + 1, 0x33444444);
        }

        guiGraphics.drawString(nn(this.font), formatCurrency(maxPrice), x + width - 46, y + 6, 0xFFD0D0D0, false);
        guiGraphics.drawString(nn(this.font), formatCurrency(minPrice), x + width - 46, y + height - 12, 0xFFD0D0D0, false);

        for (int i = 0; i < visibleHistory.size(); i++) {
            StockMarketData.StockCandle candle = visibleHistory.get(i);
            int candleX = plotX + i * slotWidth + slotWidth / 2;
            int highY = mapPriceToY(candle.high(), minPrice, maxPrice, plotY, plotHeight);
            int lowY = mapPriceToY(candle.low(), minPrice, maxPrice, plotY, plotHeight);
            int openY = mapPriceToY(candle.open(), minPrice, maxPrice, plotY, plotHeight);
            int closeY = mapPriceToY(candle.close(), minPrice, maxPrice, plotY, plotHeight);
            int color = candle.close() >= candle.open() ? 0xFFFF4D4F : 0xFF00C853;
            int bodyTop = Math.min(openY, closeY);
            int bodyBottom = Math.max(openY, closeY);
            int bodyWidth = Math.max(3, slotWidth / 2);

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
        int slotWidth = Math.max(8, plotWidth / Math.max(1, visibleHistory.size()));

        for (int i = 0; i < visibleHistory.size(); i++) {
            StockMarketData.StockCandle candle = visibleHistory.get(i);
            int barHeight = (int) Math.round((candle.volume() / (double) maxVolume) * (plotHeight - 2));
            int barX = plotX + i * slotWidth + 1;
            int barY = plotY + plotHeight - barHeight;
            int color = candle.close() >= candle.open() ? 0x99FF4D4F : 0x9900C853;
            guiGraphics.fill(barX, barY, barX + Math.max(3, slotWidth - 2), plotY + plotHeight, color);
        }

        guiGraphics.drawString(nn(this.font), String.valueOf(maxVolume), x + width - 46, y + 6, 0xFFD0D0D0, false);
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
        int fromIndex = Math.max(0, history.size() - MAX_VISIBLE_CANDLES);
        return history.subList(fromIndex, history.size());
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
}
