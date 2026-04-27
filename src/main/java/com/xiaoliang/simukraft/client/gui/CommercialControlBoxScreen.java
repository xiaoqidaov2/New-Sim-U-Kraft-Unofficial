package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Objects;

public class CommercialControlBoxScreen extends Screen {
    private final BlockPos controlBoxPos;
    private final String buildingFileName;
    private Button hireEmployeeButton;
    private Button fireEmployeeButton;

    public CommercialControlBoxScreen(BlockPos pos, String buildingFileName) {
        super(Component.translatable("gui.commercial_control_box.title"));
        this.controlBoxPos = pos;
        this.buildingFileName = buildingFileName;
        Minecraft.getInstance().getSoundManager().play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));

        NetworkManager.INSTANCE.sendToServer(
            new com.xiaoliang.simukraft.network.RequestWorkBlockHireStatusPacket(pos, "commercial")
        );
    }

    @Override
    protected void init() {
        super.init();

        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.done")),
                        button -> this.onClose())
                .bounds(5, 5, 45, 20)
                .build()));

        // simukraft: 拆除按钮（右上角）
        int demolishBtnWidth = 60;
        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.demolish")),
                        button -> this.onDemolishClicked())
                .bounds(this.width - demolishBtnWidth - 5, 5, demolishBtnWidth, 20)
                .build()));

        // 优先从建筑配置文件读取职业类型（支持自定义职业）
        String jobType = readJobTypeFromBuildingFile();
        if (jobType == null) {
            // 如果配置文件没有，尝试从缓存获取（已雇佣的情况）
            jobType = CommercialClientData.getJobType(controlBoxPos);
        }
        if (jobType == null) {
            jobType = "shopkeeper";
        }

        String hireButtonText = getHireButtonText(jobType);

        String finalJobType = jobType;
        hireEmployeeButton = nn(Button.builder(
                        nn(Component.literal(safeString(hireButtonText))),
                        button -> {
                            Minecraft.getInstance().setScreen(new HireCommercialScreen(controlBoxPos, finalJobType, buildingFileName));
                        })
                .bounds(5, this.height - 50, 80, 20)
                .build());
        this.addRenderableWidget(hireEmployeeButton);

        fireEmployeeButton = nn(Button.builder(
                        nn(Component.translatable("gui.button.fire_employee")),
                        button -> {
                            handleFireEmployee();
                        })
                .bounds(5, this.height - 25, 80, 20)
                .build());
        this.addRenderableWidget(fireEmployeeButton);

        updateButtonStates();
    }

    private String getHireButtonText(String jobType) {
        if (jobType == null) {
            return nn(Component.translatable("gui.button.hire_employee")).getString();
        }
        // 从配置获取职业名称，支持自定义职业
        String jobName = CommercialClientData.getJobNameByJobType(jobType, buildingFileName);
        if (jobName == null || jobName.isEmpty()) {
            jobName = jobType;
        }
        return nn(Component.translatable("gui.button.hire_employee_with_job", safeString(jobName))).getString();
    }

    private void updateButtonStates() {
        updateButtonStates(false);
    }

    private void updateButtonStates(boolean skipDataSync) {
        boolean hasHiredEmployee = CommercialClientData.hasHiredEmployee(controlBoxPos);

        nn(hireEmployeeButton).active = !hasHiredEmployee;
        nn(fireEmployeeButton).active = hasHiredEmployee;

        if (hasHiredEmployee) {
            nn(hireEmployeeButton).setMessage(nn(Component.translatable("gui.button.hire_employee").withStyle(style -> style.withColor(0x666666))));
            CustomEntity npc = CommercialClientData.getHiredEmployee(controlBoxPos);
            if (npc != null) {
                nn(fireEmployeeButton).setMessage(nn(Component.translatable("gui.button.fire_employee_with_name", safeString(npc.getFullName()))));
            }
        } else {
            String jobType = CommercialClientData.getJobType(controlBoxPos);
            String hireButtonText = getHireButtonText(jobType);
            nn(hireEmployeeButton).setMessage(nn(Component.literal(safeString(hireButtonText)).withStyle(style -> style.withColor(0xFFFFFF))));
            nn(fireEmployeeButton).setMessage(nn(Component.translatable("gui.button.fire_employee")));
        }
    }

    private void handleFireEmployee() {
        if (CommercialClientData.hasHiredEmployee(controlBoxPos)) {
            CustomEntity npc = CommercialClientData.getHiredEmployee(controlBoxPos);
            java.util.UUID npcUuid = npc != null ? npc.getUUID() : CommercialClientData.getHiredEmployeeUUID(controlBoxPos);

            if (npcUuid != null) {
                NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByNpc(npcUuid));
                CommercialClientData.clearHiredEmployee(controlBoxPos);
                updateButtonStates(true);  // 跳过数据同步，立即更新UI
            }
        }
    }

    /**
     * 点击拆除按钮处理
     */
    private void onDemolishClicked() {
        // 发送拆除请求到服务器
        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
            new com.xiaoliang.simukraft.network.DemolishBuildingPacket(controlBoxPos)
        );
        // 关闭界面
        this.onClose();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        int titleColor = 0xFFFFFF;
        Component title = nn(Component.translatable("gui.control_panel.title").withStyle(style -> style.withColor(titleColor)));
        guiGraphics.drawCenteredString(nn(this.font), title, this.width / 2, 10, titleColor);

        int textColor = 0xFFF5F5A0;

        String buildingName = getBuildingNameFromConfig();
        Component line1 = nn(Component.translatable("gui.control_panel.building.name_format", safeString(buildingName)).withStyle(style -> style.withColor(textColor)));
        guiGraphics.drawString(nn(this.font), line1, 10, 35, textColor, false);

        Component line2 = nn(Component.translatable("gui.control_panel.type.commercial").withStyle(style -> style.withColor(textColor)));
        guiGraphics.drawString(nn(this.font), line2, 10, 50, textColor, false);

        Component line3;
        if (CommercialClientData.hasHiredEmployee(controlBoxPos)) {
            CustomEntity npc = CommercialClientData.getHiredEmployee(controlBoxPos);
            if (npc != null) {
                line3 = nn(Component.translatable("gui.control_panel.employee.hired", safeString(npc.getFullName())).withStyle(style -> style.withColor(textColor)));
            } else {
                line3 = nn(Component.translatable("gui.control_panel.employee.hired_not_found").withStyle(style -> style.withColor(textColor)));
            }
        } else {
            line3 = nn(Component.translatable("gui.control_panel.employee.none").withStyle(style -> style.withColor(textColor)));
        }
        guiGraphics.drawString(nn(this.font), line3, 10, 65, textColor, false);

        CommercialBuildingConfig.ShopMode shopMode = getShopModeFromConfig();
        Component line4 = nn(Component.translatable("gui.control_panel.shop_mode", safeString(getShopModeDisplayName(shopMode))).withStyle(style -> style.withColor(textColor)));
        guiGraphics.drawString(nn(this.font), line4, 10, 80, textColor, false);

        renderStockInfo(guiGraphics, 95, textColor);

        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    private void renderStockInfo(GuiGraphics guiGraphics, int startY, int textColor) {
        CommercialBuildingConfig.ShopMode shopMode = getShopModeFromConfig();

        if (shopMode == CommercialBuildingConfig.ShopMode.NPC_SELL || shopMode == CommercialBuildingConfig.ShopMode.MIXED) {
            Map<String, CommercialHiredData.StockInfo> stock = CommercialClientData.getStock(controlBoxPos);

            if (!stock.isEmpty()) {
                Component stockTitle = nn(Component.translatable("gui.control_panel.stock.title").withStyle(style -> style.withColor(textColor)));
                guiGraphics.drawString(nn(this.font), stockTitle, 10, startY, textColor, false);

                int y = startY + 15;
                for (Map.Entry<String, CommercialHiredData.StockInfo> entry : stock.entrySet()) {
                    CommercialHiredData.StockInfo stockInfo = entry.getValue();
                    Component stockLine = nn(Component.translatable("gui.control_panel.stock.item",
                            safeString(entry.getKey()), stockInfo.getCurrentStock()).withStyle(style -> style.withColor(textColor)));
                    guiGraphics.drawString(nn(this.font), stockLine, 20, y, textColor, false);
                    y += 12;
                }
            } else {
                Component noStock = nn(Component.translatable("gui.control_panel.stock.no_data").withStyle(style -> style.withColor(textColor)));
                guiGraphics.drawString(nn(this.font), noStock, 10, startY, textColor, false);
            }
        }
    }

    private String getShopModeDisplayName(CommercialBuildingConfig.ShopMode shopMode) {
        return switch (shopMode) {
            case NPC_SELL -> Component.translatable("gui.shop_mode.npc_sell").getString();
            case PLAYER_SELL -> Component.translatable("gui.shop_mode.player_sell").getString();
            case MIXED -> Component.translatable("gui.shop_mode.mixed").getString();
            default -> Component.translatable("gui.shop_mode.unknown").getString();
        };
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

    public BlockPos getControlBoxPos() {
        return controlBoxPos;
    }

    public String getBuildingFileName() {
        return buildingFileName;
    }

    public void refreshButtonStates() {
        updateButtonStates();
    }

    private String readJobTypeFromBuildingFile() {
        try {
            var minecraft = nn(Minecraft.getInstance());
            return com.xiaoliang.simukraft.client.utils.ClientFileUtils.readCommercialJobTypeClient(
                minecraft, controlBoxPos);
        } catch (Exception e) {
            System.err.println("CommercialControlBoxScreen: 无法从建筑数据文件读取职业类型: " + e.getMessage());
        }
        return null;
    }

    private String getEffectiveBuildingFileName() {
        // 优先使用从服务器同步的建筑文件名（多人游戏）
        String syncedName = CommercialClientData.getBuildingFileName(controlBoxPos);
        if (syncedName != null && !syncedName.isEmpty()) {
            return syncedName;
        }
        // 其次使用从本地文件读取的建筑文件名（单人游戏）
        if (buildingFileName != null && !buildingFileName.isEmpty() && !"unknown".equals(buildingFileName)) {
            return buildingFileName;
        }
        return null;
    }

    private String getBuildingNameFromConfig() {
        String effectiveFileName = getEffectiveBuildingFileName();
        if (effectiveFileName == null || effectiveFileName.isEmpty()) {
            return nn(Component.translatable("gui.control_panel.building.commercial_default")).getString();
        }

        String name = CommercialClientData.getBuildingName(effectiveFileName);
        if (name != null && !name.isEmpty()) {
            return name;
        }

        return effectiveFileName;
    }

    private CommercialBuildingConfig.ShopMode getShopModeFromConfig() {
        String effectiveFileName = getEffectiveBuildingFileName();
        if (effectiveFileName != null && !effectiveFileName.isEmpty()) {
            CommercialBuildingConfig.ShopMode mode = CommercialClientData.getShopMode(effectiveFileName);
            if (mode != null) {
                return mode;
            }
        }
        return CommercialBuildingConfig.ShopMode.NPC_SELL;
    }

    private int refreshTimer = 0;
    private static final int REFRESH_INTERVAL = 10;

    @Override
    public void tick() {
        super.tick();

        refreshTimer++;
        if (refreshTimer >= REFRESH_INTERVAL) {
            refreshTimer = 0;
            updateButtonStates();
        }
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(String value) {
        return nn(value);
    }
}
