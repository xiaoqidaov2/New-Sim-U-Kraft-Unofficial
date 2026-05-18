package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.BankLoanActionPacket;
import com.xiaoliang.simukraft.network.GetBankFinanceInfoPacket;
import com.xiaoliang.simukraft.world.CityLoanService.FinanceSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

public class BankLoanScreen extends AbstractTransitionScreen {
    private static final int ENTER_KEY_CODE = 257;

    private final BlockPos controlBoxPos;
    @Nullable
    private EditBox borrowAmountField;
    @Nullable
    private EditBox repayAmountField;
    private String localMessage = "";
    private boolean financeInfoLoaded;
    private double cityFunds;
    private double outstandingDebt;
    private double maxLoanAmount;
    private double availableLoanAmount;
    private double dailyInterestRate;
    private int cityLevel;

    public BankLoanScreen(BlockPos controlBoxPos) {
        super(Component.translatable("gui.bank_control_box.loan"));
        this.controlBoxPos = controlBoxPos;
        playOpenSound();
    }

    @SuppressWarnings("null")
    @Override
    protected void init() {
        super.init();
        var fontRenderer = nn(this.font);
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.borrowAmountField = nn(new EditBox(
                fontRenderer,
                centerX - 145,
                centerY + 18,
                120,
                20,
                nn(Component.translatable("gui.city_finance.borrow_amount"))
        ));
        this.borrowAmountField.setMaxLength(12);
        this.addWidget(this.borrowAmountField);

        this.repayAmountField = nn(new EditBox(
                fontRenderer,
                centerX - 145,
                centerY + 68,
                120,
                20,
                nn(Component.translatable("gui.city_finance.repay_amount"))
        ));
        this.repayAmountField.setMaxLength(12);
        this.addWidget(this.repayAmountField);

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.borrow")),
                button -> onBorrow()
        ).pos(centerX - 15, centerY + 18).size(70, 20).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.fill_available")),
                button -> requireBorrowAmountField().setValue(formatCurrency(availableLoanAmount))
        ).pos(centerX + 60, centerY + 18).size(90, 20).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.repay")),
                button -> onRepay()
        ).pos(centerX - 15, centerY + 68).size(70, 20).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.repay_all")),
                button -> onRepayAll()
        ).pos(centerX + 60, centerY + 68).size(90, 20).build()));

        this.addRenderableWidget(nn(Button.builder(
                nn(Component.translatable("gui.city_finance.back")),
                button -> closeScreen()
        ).pos(centerX - 50, centerY + 108).size(100, 20).build()));

        this.setInitialFocus(requireBorrowAmountField());
        requestFinanceInfo();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics safeGuiGraphics = nn(guiGraphics);
        var font = nn(this.font);
        super.render(safeGuiGraphics, mouseX, mouseY, partialTicks);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.bank_control_box.loan")), centerX - 40, centerY - 95, 0xFFFFFF);
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.level", cityLevel)), centerX - 145, centerY - 72, 0xE0E0E0);
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.funds", formatCurrency(cityFunds))), centerX - 145, centerY - 58, 0x55FF55);
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.debt", formatCurrency(outstandingDebt))), centerX - 145, centerY - 44, 0xFFAA55);
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.max_loan", formatCurrency(maxLoanAmount))), centerX - 145, centerY - 30, 0xAAAAFF);
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.available_loan", formatCurrency(availableLoanAmount))), centerX - 145, centerY - 16, 0xAAAAFF);
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.daily_interest", formatPercent(dailyInterestRate))), centerX - 145, centerY - 2, 0xFFDD55);
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.borrow_label")), centerX - 145, centerY + 4, 0xFFFFFF);
        safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.repay_label")), centerX - 145, centerY + 54, 0xFFFFFF);

        if (!financeInfoLoaded) {
            safeGuiGraphics.drawString(font, nn(Component.translatable("gui.city_finance.loading")), centerX - 45, centerY + 92, 0xAAAAAA);
        }
        if (!localMessage.isEmpty()) {
            safeGuiGraphics.drawString(font, nn(Component.translatable(localMessage)), centerX - 145, centerY + 96, 0xFF7777);
        }

        requireBorrowAmountField().render(safeGuiGraphics, mouseX, mouseY, partialTicks);
        requireRepayAmountField().render(safeGuiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == ENTER_KEY_CODE) {
            EditBox repayField = this.repayAmountField;
            if (repayField != null && repayField.isFocused()) {
                onRepay();
            } else {
                onBorrow();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public void updateFinanceInfo(BlockPos responseControlBoxPos, FinanceSnapshot snapshot) {
        if (!this.controlBoxPos.equals(responseControlBoxPos)) {
            return;
        }
        this.cityFunds = snapshot.cityFunds();
        this.outstandingDebt = snapshot.outstandingDebt();
        this.maxLoanAmount = snapshot.maxLoanAmount();
        this.availableLoanAmount = snapshot.availableLoanAmount();
        this.dailyInterestRate = snapshot.dailyInterestRate();
        this.cityLevel = snapshot.cityLevel();
        this.financeInfoLoaded = true;
        this.localMessage = "";
    }

    private void onBorrow() {
        double amount = parseAmount(requireBorrowAmountField());
        if (amount > 0.0D) {
            sendAction(BankLoanActionPacket.ActionType.BORROW, amount);
        }
    }

    private void onRepay() {
        double amount = parseAmount(requireRepayAmountField());
        if (amount > 0.0D) {
            sendAction(BankLoanActionPacket.ActionType.REPAY, amount);
        }
    }

    private void onRepayAll() {
        this.localMessage = "";
        sendAction(BankLoanActionPacket.ActionType.REPAY_ALL_CURRENT_FUNDS, 0.0D);
    }

    private void sendAction(BankLoanActionPacket.ActionType actionType, double amount) {
        com.xiaoliang.simukraft.network.NetworkManager.sendToServer(new BankLoanActionPacket(this.controlBoxPos, actionType, amount));
    }

    private void requestFinanceInfo() {
        com.xiaoliang.simukraft.network.NetworkManager.sendToServer(new GetBankFinanceInfoPacket(this.controlBoxPos));
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

    private static String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    private static String formatPercent(double rate) {
        return String.format(Locale.US, "%.2f%%", rate * 100.0D);
    }

    @Override
    public void onClose() {
        closeScreen();
    }

    private void closeScreen() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new BankControlBoxScreen(controlBoxPos));
        }
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
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }
}
