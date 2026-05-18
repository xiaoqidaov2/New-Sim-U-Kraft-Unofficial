package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.CityInvestmentActionPacket;
import com.xiaoliang.simukraft.network.CityLoanActionPacket;
import com.xiaoliang.simukraft.network.GetCityFinanceInfoPacket;
import com.xiaoliang.simukraft.world.CityInvestmentService.CityFinanceSnapshot;
import com.xiaoliang.simukraft.world.CityInvestmentService.InvestmentPositionSnapshot;
import com.xiaoliang.simukraft.world.CityInvestmentService.InvestmentProductSnapshot;
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

public class CityFinanceScreen extends AbstractTransitionScreen {
    private static final int ENTER_KEY_CODE = 257;
    private static final int PANEL_TOP = 32;
    private static final int LOAN_PANEL_HEIGHT = 270;
    private static final int PRODUCT_CARD_WIDTH = 175;
    private static final int PRODUCT_CARD_HEIGHT = 56;
    private static final int PRODUCT_CARD_GAP = 8;
    private static final int PRODUCT_GRID_TOP = 92;
    private static final int INVESTMENT_PANEL_BOTTOM_PADDING = 30;
    private static final int PANEL_GAP = 32;
    private static final int POSITION_PANEL_HEIGHT = 142;
    private static final int POSITION_ROW_HEIGHT = 18;
    private static final int MAX_VISIBLE_POSITIONS = 6;
    private final BlockPos cityCorePos;
    @Nullable
    private EditBox borrowAmountField;
    @Nullable
    private EditBox repayAmountField;
    @Nullable
    private EditBox investAmountField;
    private String localMessage = "";
    private boolean financeInfoLoaded;
    private double cityFunds;
    private double outstandingDebt;
    private double maxLoanAmount;
    private double availableLoanAmount;
    private double dailyInterestRate;
    private int cityLevel;
    private int currentDay;
    private int positionScrollOffset;
    private final List<InvestmentProductSnapshot> products = new ArrayList<>();
    private final List<InvestmentPositionSnapshot> positions = new ArrayList<>();

    public CityFinanceScreen(BlockPos cityCorePos) {
        super(Component.translatable("gui.city_finance.title"));
        GuiScaleManager.applyFixedScale(2);
        this.cityCorePos = cityCorePos;
        playOpenSound();
    }

    @SuppressWarnings("null")
    @Override
    protected void init() {
        GuiScaleManager.applyFixedScale(2);
        super.init();
        var fontRenderer = nn(this.font);
        int leftX = getLoanPanelX();
        int topY = 42;

        this.borrowAmountField = nn(new EditBox(
                fontRenderer,
                leftX,
                topY + 118,
                110,
                20,
                nn(Component.translatable("gui.city_finance.borrow_amount"))
        ));
        this.borrowAmountField.setMaxLength(12);
        this.addWidget(this.borrowAmountField);

        this.repayAmountField = nn(new EditBox(
                fontRenderer,
                leftX,
                topY + 170,
                110,
                20,
                nn(Component.translatable("gui.city_finance.repay_amount"))
        ));
        this.repayAmountField.setMaxLength(12);
        this.addWidget(this.repayAmountField);

        this.investAmountField = nn(new EditBox(
                fontRenderer,
                getInvestmentPanelX(),
                topY + 18,
                160,
                20,
                nn(Component.translatable("gui.city_finance.invest_amount_hint"))
        ));
        this.investAmountField.setMaxLength(12);
        this.addWidget(this.investAmountField);

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.borrow")),
                button -> onBorrow()
        ).pos(leftX + 120, topY + 118).size(64, 20).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.fill_available")),
                button -> requireBorrowAmountField().setValue(formatCurrency(availableLoanAmount))
        ).pos(leftX, topY + 142).size(184, 20).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.repay")),
                button -> onRepay()
        ).pos(leftX + 120, topY + 170).size(64, 20).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.repay_all")),
                button -> onRepayAll()
        ).pos(leftX, topY + 194).size(184, 20).build()));

        Button backButton = nn(Button.builder(
                nn(Component.translatable("gui.city_finance.back")),
                button -> this.closeScreen()
        ).pos(leftX, this.height - 34).size(90, 20).build());
        this.addRenderableWidget(backButton);

        this.setInitialFocus(requireInvestAmountField());
        requestFinanceInfo();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics safeGuiGraphics = nn(guiGraphics);
        var font = nn(this.font);
        super.render(safeGuiGraphics, mouseX, mouseY, partialTicks);

        safeGuiGraphics.drawCenteredString(font, nn(Component.translatable("gui.city_finance.title")), this.width / 2, 14, 0xFFFFFF);
        renderLoanPanel(safeGuiGraphics);
        renderInvestmentPanel(safeGuiGraphics, mouseX, mouseY);
        renderPositionsPanel(safeGuiGraphics);

        if (!financeInfoLoaded) {
            safeGuiGraphics.drawCenteredString(font, nn(Component.translatable("gui.city_finance.loading")), this.width / 2, this.height - 44, 0xAAAAAA);
        } else if (!localMessage.isEmpty()) {
            safeGuiGraphics.drawCenteredString(font, nn(Component.translatable(localMessage)), this.width / 2, this.height - 44, 0xFF7777);
        }

        requireBorrowAmountField().render(safeGuiGraphics, mouseX, mouseY, partialTicks);
        requireRepayAmountField().render(safeGuiGraphics, mouseX, mouseY, partialTicks);
        requireInvestAmountField().render(safeGuiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == ENTER_KEY_CODE) {
            EditBox repayField = this.repayAmountField;
            if (repayField != null && repayField.isFocused()) {
                onRepay();
            } else if (requireBorrowAmountField().isFocused()) {
                onBorrow();
            } else {
                focusNextInput();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handleProductClick(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isMouseOverPositions(mouseX, mouseY) && positions.size() > MAX_VISIBLE_POSITIONS) {
            int scrollDelta = delta < 0 ? 1 : -1;
            positionScrollOffset = Math.max(0, Math.min(positionScrollOffset + scrollDelta, getMaxPositionScrollOffset()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    public void updateFinanceInfo(BlockPos responseCityCorePos, CityFinanceSnapshot snapshot) {
        if (!this.cityCorePos.equals(responseCityCorePos)) {
            return;
        }
        this.cityFunds = snapshot.cityFunds();
        this.outstandingDebt = snapshot.outstandingDebt();
        this.maxLoanAmount = snapshot.maxLoanAmount();
        this.availableLoanAmount = snapshot.availableLoanAmount();
        this.dailyInterestRate = snapshot.dailyInterestRate();
        this.cityLevel = snapshot.cityLevel();
        this.currentDay = snapshot.currentDay();
        this.products.clear();
        this.products.addAll(snapshot.products());
        this.positions.clear();
        this.positions.addAll(snapshot.positions());
        this.positionScrollOffset = Math.max(0, Math.min(positionScrollOffset, getMaxPositionScrollOffset()));
        this.financeInfoLoaded = true;
        this.localMessage = "";
    }

    private void onBorrow() {
        double amount = parseAmount(requireBorrowAmountField());
        if (amount <= 0.0D) {
            return;
        }
        sendAction(CityLoanActionPacket.ActionType.BORROW, amount);
    }

    private void onRepay() {
        double amount = parseAmount(requireRepayAmountField());
        if (amount <= 0.0D) {
            return;
        }
        sendAction(CityLoanActionPacket.ActionType.REPAY, amount);
    }

    private void onRepayAll() {
        this.localMessage = "";
        sendAction(CityLoanActionPacket.ActionType.REPAY_ALL_CURRENT_FUNDS, 0.0D);
    }

    private void onBuyProduct(InvestmentProductSnapshot product) {
        double amount = parseAmount(requireInvestAmountField());
        if (amount <= 0.0D) {
            return;
        }
        this.localMessage = "";
        com.xiaoliang.simukraft.network.NetworkManager.sendToServer(
                new CityInvestmentActionPacket(this.cityCorePos, product.productId(), amount)
        );
    }

    private void sendAction(CityLoanActionPacket.ActionType actionType, double amount) {
        com.xiaoliang.simukraft.network.NetworkManager.sendToServer(new CityLoanActionPacket(this.cityCorePos, actionType, amount));
    }

    private void requestFinanceInfo() {
        com.xiaoliang.simukraft.network.NetworkManager.sendToServer(new GetCityFinanceInfoPacket(this.cityCorePos));
    }

    private double parseAmount(EditBox field) {
        String raw = field.getValue().trim();
        if (raw.isEmpty()) {
            this.localMessage = "gui.city_finance.error.empty_amount";
            return 0.0D;
        }
        try {
            double amount = Double.parseDouble(raw);
            if (amount <= 0.0D) {
                this.localMessage = "gui.city_finance.error.invalid_amount";
                return 0.0D;
            }
            this.localMessage = "";
            return amount;
        } catch (NumberFormatException exception) {
            this.localMessage = "gui.city_finance.error.invalid_amount";
            return 0.0D;
        }
    }

    private void renderLoanPanel(GuiGraphics guiGraphics) {
        int x = getLoanPanelX() - 10;
        int y = PANEL_TOP;
        int width = 205;
        int height = LOAN_PANEL_HEIGHT;
        drawPanel(guiGraphics, x, y, width, height);

        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.section.loan")), x + 8, y + 8, 0xFFFFFF, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.level", cityLevel)), x + 10, y + 28, 0xE0E0E0, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.day", currentDay)), x + 10, y + 42, 0x7FC8FF, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.funds", formatCurrency(cityFunds))), x + 10, y + 58, 0x55FF55, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.debt", formatCurrency(outstandingDebt))), x + 10, y + 72, 0xFFAA55, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.max_loan", formatCurrency(maxLoanAmount))), x + 10, y + 86, 0xAAAAFF, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.available_loan", formatCurrency(availableLoanAmount))), x + 10, y + 100, 0xAAAAFF, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.daily_interest", formatPercent(dailyInterestRate))), x + 10, y + 114, 0xFFDD55, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.borrow_label")), x + 10, y + 132, 0xFFFFFF, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.repay_label")), x + 10, y + 184, 0xFFFFFF, false);
    }

    private void renderInvestmentPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = getInvestmentPanelX() - 10;
        int y = PANEL_TOP;
        int width = this.width - x - 22;
        int height = getInvestmentPanelHeight();
        drawPanel(guiGraphics, x, y, width, height);

        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.section.investment")), x + 8, y + 8, 0xFFFFFF, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.invest_amount")), x + 8, y + 32, 0xE0E0E0, false);
        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.invest_hint")), x + 175, y + 24, 0xA0A0A0, false);

        for (int i = 0; i < products.size(); i++) {
            int cardX = getProductCardX(i);
            int cardY = getProductCardY(i);
            boolean hovered = mouseX >= cardX && mouseX <= cardX + PRODUCT_CARD_WIDTH
                    && mouseY >= cardY && mouseY <= cardY + PRODUCT_CARD_HEIGHT;
            int background = hovered ? 0x88444444 : 0x66333333;
            guiGraphics.fill(cardX, cardY, cardX + PRODUCT_CARD_WIDTH, cardY + PRODUCT_CARD_HEIGHT, background);
            guiGraphics.fill(cardX, cardY, cardX + PRODUCT_CARD_WIDTH, cardY + 1, 0x99FFFFFF);
            guiGraphics.fill(cardX, cardY + PRODUCT_CARD_HEIGHT - 1, cardX + PRODUCT_CARD_WIDTH, cardY + PRODUCT_CARD_HEIGHT, 0x99FFFFFF);
            guiGraphics.fill(cardX, cardY, cardX + 1, cardY + PRODUCT_CARD_HEIGHT, 0x99FFFFFF);
            guiGraphics.fill(cardX + PRODUCT_CARD_WIDTH - 1, cardY, cardX + PRODUCT_CARD_WIDTH, cardY + PRODUCT_CARD_HEIGHT, 0x99FFFFFF);

            InvestmentProductSnapshot product = products.get(i);
            int titleColor = product.stable() ? 0xFF80FF80 : 0xFFFF8888;
            guiGraphics.drawString(nn(this.font), translate(product.nameKey()), cardX + 6, cardY + 5, titleColor, false);
            guiGraphics.drawString(nn(this.font),
                    translate("gui.city_finance.invest_day_short", product.cycleDays()),
                    cardX + 6, cardY + 17, 0xFFD6D6D6, false);
            guiGraphics.drawString(nn(this.font),
                    product.stable()
                            ? translate("gui.city_finance.invest_stable_range",
                            formatPercent(product.positiveMinRate()),
                            formatPercent(product.positiveMaxRate()))
                            : translate("gui.city_finance.invest_risky_range",
                            formatPercent(product.positiveMaxRate()),
                            formatPercent(product.negativeMaxRate())),
                    cardX + 6, cardY + 29, product.stable() ? 0xFF7FC8FF : 0xFFFFD27F, false);
            guiGraphics.drawString(nn(this.font),
                    product.stable()
                            ? translate("gui.city_finance.invest_stable_tag")
                            : translate("gui.city_finance.invest_success_rate", formatPercent(product.successChance())),
                    cardX + 6, cardY + 41, 0xFFB8B8B8, false);
        }
    }

    private void renderPositionsPanel(GuiGraphics guiGraphics) {
        int x = getLoanPanelX() - 10;
        int y = getPositionsPanelY();
        int width = this.width - x - 22;
        int height = POSITION_PANEL_HEIGHT;
        drawPanel(guiGraphics, x, y, width, height);

        guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.section.position")), x + 8, y + 8, 0xFFFFFF, false);

        if (positions.isEmpty()) {
            guiGraphics.drawString(nn(this.font), nn(Component.translatable("gui.city_finance.positions.empty")), x + 10, y + 28, 0xAAAAAA, false);
            return;
        }

        int startIndex = Math.min(positionScrollOffset, Math.max(0, positions.size() - 1));
        int endIndex = Math.min(positions.size(), startIndex + MAX_VISIBLE_POSITIONS);
        for (int index = startIndex; index < endIndex; index++) {
            InvestmentPositionSnapshot position = positions.get(index);
            int rowY = y + 28 + (index - startIndex) * POSITION_ROW_HEIGHT;
            int rowColor = position.stable() ? 0x2233AA33 : 0x22AA3333;
            guiGraphics.fill(x + 8, rowY - 1, x + width - 8, rowY + POSITION_ROW_HEIGHT - 3, rowColor);
            guiGraphics.drawString(nn(this.font),
                    translate("gui.city_finance.positions.row",
                            translate(position.nameKey()),
                            formatCurrency(position.principal()),
                            position.maturityDay(),
                            position.remainingDays()),
                    x + 12, rowY + 3, 0xFFE8E8E8, false);
        }
    }

    private void drawPanel(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + height, 0x66222222);
        guiGraphics.fill(x, y, x + width, y + 1, 0x99FFFFFF);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0x99FFFFFF);
        guiGraphics.fill(x, y, x + 1, y + height, 0x99FFFFFF);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0x99FFFFFF);
    }

    private boolean handleProductClick(double mouseX, double mouseY) {
        if (!financeInfoLoaded) {
            return false;
        }
        for (int i = 0; i < products.size(); i++) {
            int cardX = getProductCardX(i);
            int cardY = getProductCardY(i);
            if (mouseX >= cardX && mouseX <= cardX + PRODUCT_CARD_WIDTH
                    && mouseY >= cardY && mouseY <= cardY + PRODUCT_CARD_HEIGHT) {
                onBuyProduct(products.get(i));
                return true;
            }
        }
        return false;
    }

    private void focusNextInput() {
        if (requireBorrowAmountField().isFocused()) {
            setFocused(requireRepayAmountField());
            return;
        }
        if (requireRepayAmountField().isFocused()) {
            setFocused(requireInvestAmountField());
            return;
        }
        setFocused(requireBorrowAmountField());
    }

    private int getLoanPanelX() {
        return this.width / 2 - 286;
    }

    private int getInvestmentPanelX() {
        return this.width / 2 - 58;
    }

    private int getProductCardX(int index) {
        int column = index % 2;
        return getInvestmentPanelX() + column * (PRODUCT_CARD_WIDTH + PRODUCT_CARD_GAP);
    }

    private int getProductCardY(int index) {
        int row = index / 2;
        return PRODUCT_GRID_TOP + row * (PRODUCT_CARD_HEIGHT + PRODUCT_CARD_GAP);
    }

    private boolean isMouseOverPositions(double mouseX, double mouseY) {
        int x = getLoanPanelX() - 10;
        int y = getPositionsPanelY();
        int width = this.width - x - 22;
        int height = POSITION_PANEL_HEIGHT;
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private int getInvestmentPanelHeight() {
        int lastCardBottom = getProductCardY(getProductRowCount() - 1) + PRODUCT_CARD_HEIGHT;
        int productPanelHeight = lastCardBottom - PANEL_TOP + INVESTMENT_PANEL_BOTTOM_PADDING;
        return Math.max(LOAN_PANEL_HEIGHT, productPanelHeight);
    }

    private int getPositionsPanelY() {
        return getTopPanelsBottom() + PANEL_GAP;
    }

    private int getTopPanelsBottom() {
        return PANEL_TOP + Math.max(LOAN_PANEL_HEIGHT, getInvestmentPanelHeight());
    }

    private int getProductRowCount() {
        return Math.max(1, (products.size() + 1) / 2);
    }

    private int getMaxPositionScrollOffset() {
        return Math.max(0, positions.size() - MAX_VISIBLE_POSITIONS);
    }

    private static String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    private static String formatPercent(double rate) {
        return String.format(Locale.US, "%.2f%%", rate * 100.0D);
    }

    @Override
    public void onClose() {
        this.closeScreen();
    }

    private void closeScreen() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new CityManagementScreen(cityCorePos));
        }
    }

    @Override
    public void resize(@Nonnull Minecraft minecraft, int width, int height) {
        GuiScaleManager.applyFixedScale(2);
        super.resize(minecraft, width, height);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void playOpenSound() {
        Minecraft.getInstance().getSoundManager().play(
                nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F))
        );
    }

    @Nonnull
    private EditBox requireBorrowAmountField() {
        return nn(this.borrowAmountField);
    }

    @Nonnull
    private EditBox requireRepayAmountField() {
        return nn(this.repayAmountField);
    }

    @Nonnull
    private EditBox requireInvestAmountField() {
        return nn(this.investAmountField);
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static Component translate(String key, Object... args) {
        return nn(Component.translatable(nn(key), nn(args)));
    }
}
