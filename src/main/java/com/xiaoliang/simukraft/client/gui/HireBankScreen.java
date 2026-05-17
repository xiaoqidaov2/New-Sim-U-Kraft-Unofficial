package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestIdleNPCsPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 银行岗位雇佣界面
 * 仅负责从空闲 NPC 中选择一名大堂经理并回到银行控制面板。
 */
public class HireBankScreen extends AbstractHireScreen {
    private static final String BANK_JOB_TYPE = "banker";

    private final BlockPos controlBoxPos;

    public HireBankScreen(BlockPos controlBoxPos) {
        super(Component.translatable("gui.bank_control_box.hire_title"));
        this.controlBoxPos = controlBoxPos;
    }

    @Override
    protected void init() {
        super.init();

        boolean hasHiredEmployee = CommercialClientData.hasHiredEmployee(controlBoxPos);
        String jobName = Component.translatable("job.banker").getString();

        if (confirmButton != null) {
            confirmButton.setMessage(nn(Component.translatable("gui.button.hire_employee_with_job", jobName)));
            confirmButton.active = !hasHiredEmployee;
        }

        NetworkManager.INSTANCE.sendToServer(new RequestIdleNPCsPacket());

        if (hasHiredEmployee) {
            this.statusText = nn(Component.translatable("message.simukraft.already_hired_employee_with_job", jobName)
                    .withStyle(style -> style.withColor(0xFF5555)));
        } else {
            this.statusText = nn(Component.translatable("message.simukraft.loading_npcs"));
        }
    }

    @Override
    protected void confirmSelection() {
        if (selectedNPCId == null) {
            return;
        }

        String jobName = Component.translatable("job.banker").getString();
        if (CommercialClientData.hasHiredEmployee(controlBoxPos)) {
            player().displayClientMessage(
                    nn(Component.translatable("message.simukraft.commercial_already_has_employee_with_job", jobName)
                            .withStyle(ChatFormatting.RED)),
                    true
            );
            return;
        }

        String dimensionId = level().dimension().location().toString();
        NetworkManager.INSTANCE.sendToServer(
                EmploymentCommandPacket.hire(selectedNPCId, controlBoxPos, "commercial", BANK_JOB_TYPE, dimensionId)
        );

        CustomEntity npc = findNpcEntity(selectedNPCId);
        if (npc != null) {
            CommercialClientData.setHiredEmployee(controlBoxPos, npc, BANK_JOB_TYPE);
        } else {
            CommercialClientData.setHiredEmployee(controlBoxPos, selectedNPCId, BANK_JOB_TYPE);
        }

        onClose();
    }

    private CustomEntity findNpcEntity(UUID npcUuid) {
        for (var entity : level().entitiesForRendering()) {
            if (entity instanceof CustomEntity customEntity && customEntity.getUUID().equals(npcUuid)) {
                return customEntity;
            }
        }
        return null;
    }

    @Override
    public void onClose() {
        minecraft().setScreen(new BankControlBoxScreen(controlBoxPos));
    }
}
