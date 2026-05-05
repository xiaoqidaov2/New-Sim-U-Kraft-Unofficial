package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestIdleNPCsPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class HireFarmerScreen extends AbstractHireScreen {
    private final BlockPos farmlandBoxPos;
    private final BlockPos lastPlayerPos;
    private long lastCheckTime = 0;

    public HireFarmerScreen(BlockPos farmlandBoxPos) {
        super(Component.translatable("雇佣农民"));
        this.farmlandBoxPos = farmlandBoxPos;
        this.lastPlayerPos = player().blockPosition();
    }

    @Override
    public void tick() {
        super.tick();
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCheckTime > 100) { // 每100毫秒检查一次
            lastCheckTime = currentTime;
            BlockPos currentPos = player().blockPosition();
            if (!currentPos.equals(lastPlayerPos)) {
                player().displayClientMessage(
                    nn(Component.translatable("message.simukraft.must_stand_still").withStyle(style -> style.withColor(0xFF0000))),
                    false
                );
                this.onClose();
            }
        }
    }

    @Override
    protected void init() {
        super.init();

        // 检查是否已经雇佣了农民
        boolean hasHiredFarmer = FarmlandData.hasHiredFarmer(farmlandBoxPos);

        confirmButton = nn(Button.builder(
                        nn(Component.translatable("gui.button.hire")),
                        button -> confirmSelection())
                .pos(width - 90, height - 30)
                .size(80, 20)
                .build());
        
        // 设置按钮激活状态
        confirmButton.active = !hasHiredFarmer;
        
        this.addRenderableWidget(nn(confirmButton));

        NetworkManager.INSTANCE.sendToServer(new RequestIdleNPCsPacket());
        
        // 根据雇佣状态设置提示信息
        if (hasHiredFarmer) {
            this.statusText = nn(Component.translatable("已经雇佣了农民，无法再次雇佣").withStyle(style -> style.withColor(0xFF5555)));
        } else {
            this.statusText = nn(Component.translatable("message.simukraft.loading_npcs"));
        }
    }

    @Override
    protected void confirmSelection() {
        if (selectedNPCId != null) {
            BlockPos targetFarmlandBoxPos = this.farmlandBoxPos;

            // 再次检查是否已经雇佣了农民
            if (FarmlandData.hasHiredFarmer(targetFarmlandBoxPos)) {
                player().displayClientMessage(
                        nn(Component.translatable("该农田盒已经雇佣了农民").withStyle(ChatFormatting.RED)),
                        true
                );
                return;
            }

            // 使用v2命令包发送雇佣请求
            String dimensionId = level().dimension().location().toString();
            NetworkManager.INSTANCE.sendToServer(
                    EmploymentCommandPacket.hire(selectedNPCId, targetFarmlandBoxPos, "farmland", "farmer", dimensionId)
            );

            // 本地缓存预更新（乐观更新）
            CustomEntity npc = findNpcEntity(selectedNPCId);
            String npcName = npc != null ? npc.getFullName() : null;
            if (npc != null) {
                FarmlandData.setHiredFarmerFromServer(targetFarmlandBoxPos, selectedNPCId, npcName);
                npc.setJob("farmer");
            } else {
                FarmlandData.setHiredFarmerFromServer(targetFarmlandBoxPos, selectedNPCId, null);
            }

            // 发送雇佣完成消息
            String displayName = npcName != null ? npcName : nn(Component.translatable("job.farmer")).getString();
            player().displayClientMessage(
                    nn(Component.translatable("message.simukraft.farmer.hired", displayName).withStyle(style -> style.withColor(0x55FF55))),
                    false
            );

            onClose();
        }
    }

    /**
     * 根据UUID查找NPC实体
     */
    private CustomEntity findNpcEntity(UUID npcUuid) {
        for (var entity : level().entitiesForRendering()) {
            if (entity instanceof CustomEntity && entity.getUUID().equals(npcUuid)) {
                return (CustomEntity) entity;
            }
        }
        return null;
    }

    @Override
    public void onClose() {
        minecraft().setScreen(new FarmlandBoxScreen(farmlandBoxPos));
    }
}
