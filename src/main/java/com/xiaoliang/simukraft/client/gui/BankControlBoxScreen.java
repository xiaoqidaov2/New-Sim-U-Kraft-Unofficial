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

    private final BlockPos controlBoxPos;
    private Button hireEmployeeButton;
    private Button fireEmployeeButton;
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

        stockMarketButton = nn(Button.builder(
                        nn(Component.translatable("gui.bank_control_box.stock_market")),
                        button -> nn(this.minecraft).setScreen(new BankStockMarketScreen(controlBoxPos)))
                .bounds(125, this.height - 50, 110, 45)
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
        boolean hasHiredEmployee = CommercialClientData.hasHiredEmployee(controlBoxPos);
        nn(hireEmployeeButton).active = !hasHiredEmployee;
        nn(fireEmployeeButton).active = hasHiredEmployee;

        if (hasHiredEmployee) {
            nn(hireEmployeeButton).setMessage(nn(Component.translatable("gui.button.hire_employee_with_job",
                    getBankJobName()).withStyle(style -> style.withColor(0x666666))));
            CustomEntity npc = CommercialClientData.getHiredEmployee(controlBoxPos);
            if (npc != null) {
                nn(fireEmployeeButton).setMessage(nn(Component.translatable("gui.button.fire_employee_with_name", npc.getFullName())));
            } else {
                nn(fireEmployeeButton).setMessage(nn(Component.translatable("gui.button.fire_employee")));
            }
            return;
        }

        nn(hireEmployeeButton).setMessage(nn(Component.translatable("gui.button.hire_employee_with_job",
                getBankJobName()).withStyle(style -> style.withColor(0xFFFFFF))));
        nn(fireEmployeeButton).setMessage(nn(Component.translatable("gui.button.fire_employee")));
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
    }

    private String getEmployeeStatusKey() {
        return CommercialClientData.hasHiredEmployee(controlBoxPos)
                ? "gui.bank_control_box.employee.hired"
                : "gui.bank_control_box.employee.none";
    }

    public BlockPos getControlBoxPos() {
        return controlBoxPos;
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
