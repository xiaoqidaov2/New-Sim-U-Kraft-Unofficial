package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestWorkBlockHireStatusPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;
@SuppressWarnings("null")
/**
 * 银行控制面板
 * 复用通用雇佣同步能力，仅负责展示银行信息和雇佣状态。
 */
public class BankControlBoxScreen extends Screen {
    private static final String BANK_JOB_TYPE = "banker";
    private static final int FOOTER_COLOR = 0x808080;
    private static final int FOOTER_MARGIN = 8;
    @Nonnull
    private static final Component FOOTER_AUTHOR = Component.literal("menglannnn");
    @Nonnull
    private static final Component FOOTER_FEEDBACK = Component.literal("bug反馈:3630797734@qq.com");

    private final BlockPos controlBoxPos;
    @Nullable
    private Button hireEmployeeButton;
    @Nullable
    private Button fireEmployeeButton;
    @Nullable
    private Button loanButton;
    @Nullable
    private Button stockMarketButton;

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    public BankControlBoxScreen(BlockPos pos) {
        super(Component.translatable("gui.bank_control_box.title"));
        this.controlBoxPos = pos;

        net.minecraft.client.Minecraft.getInstance().getSoundManager()
                .play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));
        // 银行面板仅复用商业雇佣状态同步，不接入商业经营逻辑。
        NetworkManager.INSTANCE.sendToServer(new RequestWorkBlockHireStatusPacket(pos, "commercial"));
    }

    @Override
    protected void init() {
        super.init();

        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.done")),
                        button -> this.onClose())
                .bounds(5, 5, 45, 20)
                .build()));

        int demolishBtnWidth = 60;
        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.demolish")),
                        button -> this.onDemolishClicked())
                .bounds(this.width - demolishBtnWidth - 5, 5, demolishBtnWidth, 20)
                .build()));

        hireEmployeeButton = nn(Button.builder(
                        nn(Component.translatable("gui.button.hire_employee_with_job",
                                getBankJobName())),
                        button -> nn(this.minecraft).setScreen(new HireBankScreen(controlBoxPos)))
                .bounds(5, this.height - 50, 110, 20)
                .build());
        this.addRenderableWidget(hireEmployeeButton);

        fireEmployeeButton = nn(Button.builder(
                        nn(Component.translatable("gui.button.fire_employee")),
                        button -> handleFireEmployee())
                .bounds(5, this.height - 25, 110, 20)
                .build());
        this.addRenderableWidget(fireEmployeeButton);

        loanButton = nn(Button.builder(
                        nn(Component.translatable("gui.bank_control_box.loan")),
                        button -> nn(this.minecraft).setScreen(new BankLoanScreen(controlBoxPos)))
                .bounds(125, this.height - 50, 110, 20)
                .build());
        this.addRenderableWidget(loanButton);

        stockMarketButton = nn(Button.builder(
                        nn(Component.translatable("gui.bank_control_box.stock_market")),
                        button -> nn(this.minecraft).setScreen(new BankStockMarketScreen(controlBoxPos)))
                .bounds(125, this.height - 25, 110, 20)
                .build());
        this.addRenderableWidget(stockMarketButton);

        refreshButtonStates();
    }

    private void onDemolishClicked() {
        NetworkManager.INSTANCE.sendToServer(new com.xiaoliang.simukraft.network.DemolishBuildingPacket(controlBoxPos));
        this.onClose();
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    public void refreshButtonStates() {
        Button hireButton = requireHireEmployeeButton();
        Button fireButton = requireFireEmployeeButton();
        Button marketButton = requireStockMarketButton();
        boolean hasHiredEmployee = CommercialClientData.hasHiredEmployee(controlBoxPos);
        hireButton.active = !hasHiredEmployee;
        fireButton.active = hasHiredEmployee;
        marketButton.active = hasHiredEmployee;

        if (hasHiredEmployee) {
            hireButton.setMessage(nn(Component.translatable("gui.button.hire_employee_with_job",
                    getBankJobName()).withStyle(style -> style.withColor(0x666666))));
            marketButton.setMessage(nn(Component.translatable("gui.bank_control_box.stock_market")
                    .withStyle(style -> style.withColor(0xFFFFFF))));
            CustomEntity npc = CommercialClientData.getHiredEmployee(controlBoxPos);
            if (npc != null) {
                fireButton.setMessage(nn(Component.translatable("gui.button.fire_employee_with_name", npc.getFullName())));
            } else {
                fireButton.setMessage(nn(Component.translatable("gui.button.fire_employee")));
            }
            return;
        }

        hireButton.setMessage(nn(Component.translatable("gui.button.hire_employee_with_job",
                getBankJobName()).withStyle(style -> style.withColor(0xFFFFFF))));
        fireButton.setMessage(nn(Component.translatable("gui.button.fire_employee")));
        marketButton.setMessage(nn(Component.translatable("gui.bank_control_box.stock_market")
                .withStyle(style -> style.withColor(0x666666))));
    }

    @Nonnull
    private static Component getBankJobName() {
        return Component.translatable("job." + BANK_JOB_TYPE);
    }

    private void handleFireEmployee() {
        if (!CommercialClientData.hasHiredEmployee(controlBoxPos)) {
            return;
        }

        CustomEntity npc = CommercialClientData.getHiredEmployee(controlBoxPos);
        UUID npcUuid = npc != null ? npc.getUUID() : CommercialClientData.getHiredEmployeeUUID(controlBoxPos);
        if (npcUuid == null) {
            return;
        }

        NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByNpc(npcUuid));
        CommercialClientData.clearHiredEmployee(controlBoxPos);
        refreshButtonStates();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        int titleColor = 0xFFFFFF;
        Component title = Component.translatable("gui.bank_control_box.panel_title")
                .withStyle(style -> style.withColor(titleColor));
        guiGraphics.drawCenteredString(nn(this.font), nn(title), this.width / 2, 10, titleColor);

        int textColor = 0xFFF5F5A0;
        guiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_control_box.building").withStyle(style -> style.withColor(textColor))),
                10, 35, textColor, false);
        guiGraphics.drawString(nn(this.font),
                nn(Component.translatable("gui.bank_control_box.type.finance").withStyle(style -> style.withColor(textColor))),
                10, 50, textColor, false);
        guiGraphics.drawString(nn(this.font),
                nn(Component.translatable(getEmployeeStatusKey()).withStyle(style -> style.withColor(textColor))),
                10, 65, textColor, false);

        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        renderFooter(guiGraphics);
    }

    private void renderFooter(@Nonnull GuiGraphics guiGraphics) {
        int secondLineY = this.height - FOOTER_MARGIN - this.font.lineHeight;
        int firstLineY = secondLineY - this.font.lineHeight - 2;
        int authorX = this.width - FOOTER_MARGIN - this.font.width(FOOTER_AUTHOR);
        int feedbackX = this.width - FOOTER_MARGIN - this.font.width(FOOTER_FEEDBACK);

        guiGraphics.drawString(nn(this.font), FOOTER_AUTHOR, authorX, firstLineY, FOOTER_COLOR, false);
        guiGraphics.drawString(nn(this.font), FOOTER_FEEDBACK, feedbackX, secondLineY, FOOTER_COLOR, false);
    }

    private String getEmployeeStatusKey() {
        return CommercialClientData.hasHiredEmployee(controlBoxPos)
                ? "gui.bank_control_box.employee.hired"
                : "gui.bank_control_box.employee.none";
    }

    public BlockPos getControlBoxPos() {
        return controlBoxPos;
    }

    @Nonnull
    private Button requireHireEmployeeButton() {
        return nn(this.hireEmployeeButton);
    }

    @Nonnull
    private Button requireFireEmployeeButton() {
        return nn(this.fireEmployeeButton);
    }

    @Nonnull
    private Button requireStockMarketButton() {
        return nn(this.stockMarketButton);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
