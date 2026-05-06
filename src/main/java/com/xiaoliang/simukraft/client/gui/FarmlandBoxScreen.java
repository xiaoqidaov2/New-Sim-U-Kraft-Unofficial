package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.client.preview.AreaSelectionManager;
import com.xiaoliang.simukraft.client.preview.FarmlandAreaPreviewManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.farmland.CropDefinition;
import com.xiaoliang.simukraft.farmland.CropRegistry;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestIdleNPCsPacket;
import com.xiaoliang.simukraft.network.SyncFarmlandDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

@SuppressWarnings("null")
public class FarmlandBoxScreen extends Screen {
    private final BlockPos farmlandBoxPos;
    private Button hireFarmerButton;
    private Button fireFarmerButton;
    private Button selectCropButton;
    private Button selectAreaButton;
    private Button startFarmingButton;
    private long lastUpdateTime = 0;
    private static final long UPDATE_INTERVAL = 100; // 每100毫秒更新一次

    // 上一次玩家朝向
    private Direction lastPlayerFacing = Direction.NORTH;

    public FarmlandBoxScreen(BlockPos pos) {
        super(Component.translatable("gui.farmland_box.title"));
        this.farmlandBoxPos = pos;

        // 强制重新加载数据，确保界面显示最新数据
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            FarmlandData.reloadData(nn(Minecraft.getInstance().getSingleplayerServer()));
        }

        // 播放建筑盒打开界面音效
        Minecraft.getInstance().getSoundManager().play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));

        // 获取玩家当前朝向
        if (Minecraft.getInstance().player != null) {
            lastPlayerFacing = nn(Minecraft.getInstance().player).getDirection();
        }

        // 启动区域预览（如果已选择区域）
        if (FarmlandData.hasSelectedPlot(farmlandBoxPos)) {
            FarmlandAreaPreviewManager.startPreview(farmlandBoxPos, FarmlandData.getSelectedPlot(farmlandBoxPos));
        } else {
            int currentAreaSize = FarmlandData.getSelectedAreaSize(farmlandBoxPos);
            if (currentAreaSize > 0) {
                FarmlandAreaPreviewManager.startPreview(farmlandBoxPos, currentAreaSize, lastPlayerFacing);
            }
        }
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(@Nullable String value) {
        return nn(value);
    }

    @Override
    protected void init() {
        super.init();

        // 再次确保数据已加载（单人游戏）
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            FarmlandData.init(nn(Minecraft.getInstance().getSingleplayerServer()));
        } else {
            // 多人游戏：发送请求同步农田盒数据的数据包
            NetworkManager.INSTANCE.sendToServer(new SyncFarmlandDataPacket.Request(farmlandBoxPos));
        }

        // 发送请求空闲NPC列表的数据包
        NetworkManager.INSTANCE.sendToServer(new RequestIdleNPCsPacket());

        // 完成按钮 - 与建筑盒界面相同的位置和大小
        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.done")),
                        button -> this.onClose())
                .bounds(5, 5, 45, 20)
                .build()));

        // 拆除按钮
        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.demolish")),
                        button -> onDemolishClicked())
                .bounds(55, 5, 45, 20)
                .build()));

        // 雇佣农民按钮 - 左下角
        hireFarmerButton = nn(Button.builder(
                        nn(Component.translatable("gui.button.hire_farmer")),
                        button -> {
                            // 切换到雇佣农民界面
                            Minecraft.getInstance().setScreen(new HireFarmerScreen(farmlandBoxPos));
                        })
                .bounds(5, this.height - 85, 80, 20)  // 左下角，宽度80像素
                .build());
        this.addRenderableWidget(hireFarmerButton);

        // 解雇农民按钮 - 在雇佣农民按钮下方
        fireFarmerButton = nn(Button.builder(
                        nn(Component.translatable("gui.farmland.fire_farmer")),
                        button -> {
                            handleFireFarmer();
                        })
                .bounds(5, this.height - 60, 80, 20)  // 在雇佣农民按钮下方
                .build());
        this.addRenderableWidget(fireFarmerButton);

        // 选择作物按钮 - 右侧
        selectCropButton = nn(Button.builder(
                        nn(Component.translatable("gui.farmland.select_crop")),
                        button -> {
                            // 切换到作物选择界面
                            Minecraft.getInstance().setScreen(new SelectCropScreen(farmlandBoxPos));
                        })
                .bounds(this.width - 85, this.height - 85, 80, 20)
                .build());
        this.addRenderableWidget(selectCropButton);

        // 选择区域按钮 - 右侧
        selectAreaButton = nn(Button.builder(
                        nn(Component.translatable("gui.farmland.select_area")),
                        button -> {
                            Minecraft.getInstance().setScreen(new AreaSelectionScreen(farmlandBoxPos, this, AreaSelectionManager.SelectionMode.FARMLAND));
                        })
                .bounds(this.width - 85, this.height - 60, 80, 20)
                .build());
        this.addRenderableWidget(selectAreaButton);

        // 开始耕种按钮 - 右侧
        startFarmingButton = nn(Button.builder(
                        nn(Component.translatable("gui.farmland.start_farming")),
                        button -> {
                            handleStartFarming();
                        })
                .bounds(this.width - 85, this.height - 35, 80, 20)
                .build());
        this.addRenderableWidget(startFarmingButton);

        // 更新按钮状态
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean hasHiredFarmer = FarmlandData.hasHiredFarmer(farmlandBoxPos);
        boolean hasSelectedCrop = FarmlandData.hasSelectedCrop(farmlandBoxPos);
        boolean hasSelectedArea = FarmlandData.hasSelectedArea(farmlandBoxPos) || FarmlandData.hasSelectedPlot(farmlandBoxPos);

        // 设置按钮激活状态
        nn(hireFarmerButton).active = !hasHiredFarmer;
        nn(fireFarmerButton).active = hasHiredFarmer;
        nn(selectCropButton).active = hasHiredFarmer;
        nn(selectAreaButton).active = hasHiredFarmer && hasSelectedCrop;
        nn(startFarmingButton).active = hasHiredFarmer && hasSelectedCrop && hasSelectedArea;
        
        // 设置按钮颜色
        if (hasHiredFarmer) {
            nn(hireFarmerButton).setMessage(nn(Component.translatable("gui.button.hire_farmer").withStyle(style -> style.withColor(0x666666))));
            CustomEntity npc = FarmlandData.getHiredFarmer(farmlandBoxPos);
            if (npc != null) {
                nn(fireFarmerButton).setMessage(nn(Component.translatable("gui.farmland.fire_farmer_with_name", npc.getFullName())));
            } else {
                // 如果实体为空，尝试从npcNames映射获取NPC名称
                UUID npcUuid = FarmlandData.getHiredFarmerUUID(farmlandBoxPos);
                if (npcUuid != null) {
                    String npcName = FarmlandData.getNPCNameByUUID(npcUuid);
                    if (npcName != null) {
                        nn(fireFarmerButton).setMessage(nn(Component.translatable("gui.farmland.fire_farmer_with_name", npcName)));
                    } else {
                        nn(fireFarmerButton).setMessage(nn(Component.translatable("gui.farmland.fire_farmer_with_name", safeString(Component.translatable("gui.farmland.unknown_npc").getString()))));
                    }
                }
            }
        } else {
            nn(hireFarmerButton).setMessage(nn(Component.translatable("gui.button.hire_farmer").withStyle(style -> style.withColor(0xFFFFFF))));
            nn(fireFarmerButton).setMessage(nn(Component.translatable("gui.farmland.fire_farmer")));
        }

        if (hasSelectedCrop) {
            nn(selectCropButton).setMessage(nn(Component.translatable("gui.farmland.crop").append(getSelectedCropDisplayName())));
        } else {
            nn(selectCropButton).setMessage(nn(Component.translatable("gui.farmland.select_crop")));
        }

        if (hasSelectedArea) {
            if (FarmlandData.hasSelectedPlot(farmlandBoxPos)) {
                var plot = FarmlandData.getSelectedPlot(farmlandBoxPos);
                nn(selectAreaButton).setMessage(nn(Component.translatable("gui.farmland.area.size", plot.widthX(), plot.depthZ())));
            } else {
                int areaSize = FarmlandData.getSelectedAreaSize(farmlandBoxPos);
                nn(selectAreaButton).setMessage(nn(Component.translatable("gui.farmland.area.size", areaSize, areaSize)));
            }
        } else {
            nn(selectAreaButton).setMessage(nn(Component.translatable("gui.farmland.select_area")));
        }
    }

    private void handleFireFarmer() {
        if (!FarmlandData.hasHiredFarmer(farmlandBoxPos) || Minecraft.getInstance().player == null) {
            return;
        }

        String dimensionId = nn(Minecraft.getInstance().player).level().dimension().location().toString();

        // 统一由服务端按工作方块位置解雇，避免客户端本地状态和服务端真实状态打架。
        NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByWorkplace(farmlandBoxPos, dimensionId));
        NetworkManager.INSTANCE.sendToServer(new SyncFarmlandDataPacket.Request(farmlandBoxPos));

        nn(fireFarmerButton).active = false;
    }

    private void handleStartFarming() {
        if (FarmlandData.hasHiredFarmer(farmlandBoxPos) && 
            FarmlandData.hasSelectedCrop(farmlandBoxPos) && 
            (FarmlandData.hasSelectedArea(farmlandBoxPos) || FarmlandData.hasSelectedPlot(farmlandBoxPos))) {
            
            // 发送开始耕种的数据包
            NetworkManager.INSTANCE.sendToServer(
                    new com.xiaoliang.simukraft.network.StartFarmingPacket(
                        farmlandBoxPos,
                        nn(FarmlandData.getSelectedCrop(farmlandBoxPos)),
                        FarmlandData.getSelectedAreaSize(farmlandBoxPos)
                    )
            );
            
            if (Minecraft.getInstance().player != null) {
                nn(Minecraft.getInstance().player).displayClientMessage(
                        nn(Component.translatable("message.simukraft.farmer_started").withStyle(style -> style.withColor(0x55FF55))),
                        false
                );
            }
        }
    }

    /**
     * 点击拆除按钮处理
     */
    private void onDemolishClicked() {
        // 发送拆除请求到服务器
        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
            new com.xiaoliang.simukraft.network.DemolishBuildingPacket(farmlandBoxPos)
        );
        // 关闭界面
        this.onClose();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 定时更新按钮状态（每100毫秒）
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdateTime >= UPDATE_INTERVAL) {
            updateButtonStates();
            lastUpdateTime = currentTime;
        }

        // 获取当前玩家朝向并更新预览
        Direction currentFacing = Minecraft.getInstance().player != null ?
            nn(Minecraft.getInstance().player).getDirection() : Direction.NORTH;
        if (currentFacing != lastPlayerFacing && FarmlandData.hasSelectedArea(farmlandBoxPos) && !FarmlandData.hasSelectedPlot(farmlandBoxPos)) {
            lastPlayerFacing = currentFacing;
            FarmlandAreaPreviewManager.startPreview(farmlandBoxPos,
                FarmlandData.getSelectedAreaSize(farmlandBoxPos), lastPlayerFacing);
        }

        // 黑色半透明背景 - 与建筑盒相同的背景
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        // 白色标题：建筑控制面板
        int titleColor = 0xFFFFFF; // 白色
        Component title = nn(Component.translatable("gui.farmland_box.title").withStyle(style -> style.withColor(titleColor)));
        guiGraphics.drawCenteredString(nn(this.font), title, this.width / 2, 10, titleColor);

        // 黄色文字内容
        int textColor = 0xFFF5F5A0; // 黄色

        // 第一行：建筑：农田盒 by XiaoLiang小亮
        Component line1 = nn(Component.translatable("gui.farmland.module_type").withStyle(style -> style.withColor(textColor)));
        guiGraphics.drawString(nn(this.font), line1, 10, 35, textColor, false);

        // 第二行：类型：农业类
        Component line2 = nn(Component.translatable("gui.farmland.type").withStyle(style -> style.withColor(textColor)));
        guiGraphics.drawString(nn(this.font), line2, 10, 50, textColor, false);

        // 第三行：农民状态
        Component line3;
        if (FarmlandData.hasHiredFarmer(farmlandBoxPos)) {
            CustomEntity npc = FarmlandData.getHiredFarmer(farmlandBoxPos);
            if (npc != null) {
                line3 = nn(Component.translatable("gui.farmland.farmer.hired", npc.getFullName()).withStyle(style -> style.withColor(textColor)));
            } else {
                // 如果实体为空，尝试从npcNames映射获取NPC名称
                UUID npcUuid = FarmlandData.getHiredFarmerUUID(farmlandBoxPos);
                if (npcUuid != null) {
                    String npcName = FarmlandData.getNPCNameByUUID(npcUuid);
                    if (npcName != null) {
                        line3 = nn(Component.translatable("gui.farmland.farmer.hired", npcName).withStyle(style -> style.withColor(textColor)));
                    } else {
                        line3 = nn(Component.translatable("gui.farmland.farmer.hired_not_found").withStyle(style -> style.withColor(textColor)));
                    }
                } else {
                    line3 = nn(Component.translatable("gui.farmland.farmer.hired_not_found").withStyle(style -> style.withColor(textColor)));
                }
            }
        } else {
            line3 = nn(Component.translatable("gui.farmland.farmer.none").withStyle(style -> style.withColor(textColor)));
        }
        guiGraphics.drawString(nn(this.font), line3, 10, 65, textColor, false);

        // 第四行：作物状态
        Component line4;
        if (FarmlandData.hasSelectedCrop(farmlandBoxPos)) {
            line4 = nn(Component.translatable("gui.farmland.crop").append(getSelectedCropDisplayName()).withStyle(style -> style.withColor(textColor)));
        } else {
            line4 = nn(Component.translatable("gui.farmland.crop.none").withStyle(style -> style.withColor(textColor)));
        }
        guiGraphics.drawString(nn(this.font), line4, 10, 80, textColor, false);

        // 第五行：区域状态
        Component line5;
        if (FarmlandData.hasSelectedArea(farmlandBoxPos) || FarmlandData.hasSelectedPlot(farmlandBoxPos)) {
            if (FarmlandData.hasSelectedPlot(farmlandBoxPos)) {
                var plot = FarmlandData.getSelectedPlot(farmlandBoxPos);
                line5 = nn(Component.translatable("gui.farmland.area.size", plot.widthX(), plot.depthZ()).withStyle(style -> style.withColor(textColor)));
            } else {
                int areaSize = FarmlandData.getSelectedAreaSize(farmlandBoxPos);
                line5 = nn(Component.translatable("gui.farmland.area.size", areaSize, areaSize).withStyle(style -> style.withColor(textColor)));
            }
        } else {
            line5 = nn(Component.translatable("gui.farmland.area.none").withStyle(style -> style.withColor(textColor)));
        }
        guiGraphics.drawString(nn(this.font), line5, 10, 95, textColor, false);

        // 第六行：朝向提示
        Component line6 = nn(Component.translatable("gui.farmland.direction", getDirectionName(currentFacing))
            .withStyle(style -> style.withColor(0xAAAAAA)));
        guiGraphics.drawString(nn(this.font), line6, 10, 110, 0xAAAAAA, false);

        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 获取方向的本地化名称
     */
    private Component getSelectedCropDisplayName() {
        String selectedCrop = FarmlandData.getSelectedCrop(farmlandBoxPos);
        CropDefinition definition = CropRegistry.resolve(selectedCrop).orElse(null);
        if (definition != null) {
            return definition.displayName();
        }
        String normalized = CropRegistry.normalizeSelectionId(selectedCrop);
        return Component.literal(normalized != null ? normalized : "");
    }

    private String getDirectionName(Direction direction) {
        return switch (direction) {
            case NORTH -> Component.translatable("direction.north").getString();
            case SOUTH -> Component.translatable("direction.south").getString();
            case EAST -> Component.translatable("direction.east").getString();
            case WEST -> Component.translatable("direction.west").getString();
            default -> Component.translatable("direction.unknown").getString();
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC键关闭界面
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // 停止区域预览
        FarmlandAreaPreviewManager.stopPreview();
        super.onClose();
    }
}
