package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 住宅控制盒GUI基类
 * 统一的住宅类型控制盒界面，从控制盒sk文件读取建筑信息
 */
public class ResidentialControlBoxScreen extends Screen {

    private final BlockPos controlBoxPos;

    // 建筑信息缓存
    private String buildingName = null;
    private String residentName = null;
    private long buildingNameCacheTime = 0;
    private long residentNameCacheTime = 0;
    private long lastBuildingRequestTime = 0;
    private long lastResidentRequestTime = 0;
    private static final long CACHE_VALIDITY = 30000; // 缓存有效期30秒
    private static final long REQUEST_INTERVAL = 500; // 请求间隔500毫秒

    // simukraft: 界限显示开关状态
    private boolean showBuildingBounds = false;
    private Button buildingBoundsButton;

    /**
     * 从渲染器同步开关状态
     * menglannnn: 在界面打开时从渲染器读取当前显示状态，保持开关状态一致
     */
    private void syncBoundsStateFromRenderer() {
        this.showBuildingBounds = BuildingBoundsRenderer.isBuildingBoundsVisible(controlBoxPos);
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    public ResidentialControlBoxScreen(BlockPos pos) {
        super(Component.translatable("gui.residential_control_box.title"));
        this.controlBoxPos = pos;

        // 播放建筑盒打开界面音效
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
            .play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.BUILD_BOX_OPEN.get()), 1.0F)));
    }

    /**
     * 获取控制盒位置
     */
    public BlockPos getControlBoxPos() {
        return controlBoxPos;
    }

    @Override
    protected void init() {
        super.init();

        // simukraft: 从渲染器同步开关状态
        syncBoundsStateFromRenderer();

        // 完成按钮
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

        // simukraft: 显示建筑界限开关（在居民信息下方）
        int switchWidth = 120;
        int switchHeight = 20;
        int switchX = 10;
        int switchY = 85; // 在居民信息(65)下方留一些间距

        buildingBoundsButton = nn(Button.builder(
                        nn(Component.translatable("gui.residential_control_box.show_building_bounds", getOnOffText(showBuildingBounds))),
                        button -> this.toggleBuildingBounds())
                .bounds(switchX, switchY, switchWidth, switchHeight)
                .build());
        this.addRenderableWidget(buildingBoundsButton);
    }

    /**
     * 获取开关状态文本
     */
    private String getOnOffText(boolean enabled) {
        return enabled ? Component.translatable("gui.switch.on").getString()
                       : Component.translatable("gui.switch.off").getString();
    }

    /**
     * 切换建筑界限显示
     */
    private void toggleBuildingBounds() {
        showBuildingBounds = !showBuildingBounds;
        updateBuildingBoundsButton();

        // 发送网络包通知服务器/客户端更新渲染
        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
            new com.xiaoliang.simukraft.network.ToggleBoundsDisplayPacket(controlBoxPos, "building", showBuildingBounds)
        );

        // 客户端立即更新渲染状态
        BuildingBoundsRenderer.setBuildingBoundsVisible(controlBoxPos, showBuildingBounds);
    }

    /**
     * 更新建筑界限按钮文本
     */
    private void updateBuildingBoundsButton() {
        if (buildingBoundsButton != null) {
            buildingBoundsButton.setMessage(nn(Component.translatable("gui.residential_control_box.show_building_bounds", getOnOffText(showBuildingBounds))));
        }
    }

    /**
     * 点击拆除按钮处理
     */
    private void onDemolishClicked() {
        // 关闭界限显示
        if (showBuildingBounds) {
            BuildingBoundsRenderer.setBuildingBoundsVisible(controlBoxPos, false);
        }

        // 发送拆除请求到服务器
        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
            new com.xiaoliang.simukraft.network.DemolishBuildingPacket(controlBoxPos)
        );
        // 关闭界面
        this.onClose();
    }

    @Override
    public void onClose() {
        // 关闭界面时清理渲染状态（可选，根据需求决定是否保留）
        super.onClose();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 黑色半透明背景 - 与建筑盒相同的背景
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        // 白色标题：建筑控制面板
        int titleColor = 0xFFFFFF;
        Component title = Component.translatable("gui.residential_control_box.panel_title").withStyle(style -> style.withColor(titleColor));
        guiGraphics.drawCenteredString(nn(this.font), nn(title), this.width / 2, 10, titleColor);

        // 黄色文字内容
        int textColor = 0xFFF5F5A0;

        // 获取建筑名称（从缓存或请求）
        String currentBuildingName = getBuildingName();

        // 第一行：建筑：xxx by XiaoLiang小亮
        String buildingName = currentBuildingName != null ? currentBuildingName : Component.translatable("gui.residential_control_box.loading").getString();
        Component line1 = Component.translatable("gui.residential_control_box.building_line", buildingName).withStyle(style -> style.withColor(textColor));
        guiGraphics.drawString(nn(this.font), nn(line1), 10, 35, textColor, false);

        // 第二行：类型：住宅类（可用建筑）
        Component line2 = Component.translatable("gui.residential_control_box.building_type").withStyle(style -> style.withColor(textColor));
        guiGraphics.drawString(nn(this.font), nn(line2), 10, 50, textColor, false);

        // 第三行：居民：
        String currentResidentName = getResidentName();
        Component residentDisplay;
        if (currentResidentName == null) {
            // 数据还在加载中
            residentDisplay = Component.translatable("gui.residential_control_box.loading");
        } else if (currentResidentName.isEmpty()) {
            // 加载完成，但住宅为空
            residentDisplay = Component.translatable("gui.residential_control_box.no_resident");
        } else {
            // 加载完成，显示居民名字
            residentDisplay = Component.literal(currentResidentName);
        }
        Component line3 = Component.translatable("gui.residential_control_box.resident_line", residentDisplay).withStyle(style -> style.withColor(textColor));
        guiGraphics.drawString(nn(this.font), nn(line3), 10, 65, textColor, false);

        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 获取建筑名称
     * 从控制盒sk文件读取，带缓存机制
     */
    private String getBuildingName() {
        long currentTime = System.currentTimeMillis();

        // 检查缓存是否有效（30秒内）
        if (buildingName != null && (currentTime - buildingNameCacheTime) < CACHE_VALIDITY) {
            return buildingName;
        }

        // 限制请求频率
        if (currentTime - lastBuildingRequestTime < REQUEST_INTERVAL) {
            // 如果有过期缓存，先返回过期缓存
            return buildingName;
        }
        lastBuildingRequestTime = currentTime;

        // 发送网络包请求建筑信息
        requestControlBoxInfo();

        // 如果有过期缓存，先返回过期缓存，避免显示"加载中"
        return buildingName;
    }

    /**
     * 获取居民名字，带缓存机制
     */
    private String getResidentName() {
        long currentTime = System.currentTimeMillis();

        // 检查缓存是否有效（30秒内）
        if (residentName != null && (currentTime - residentNameCacheTime) < CACHE_VALIDITY) {
            return residentName;
        }

        // 限制请求频率
        if (currentTime - lastResidentRequestTime < REQUEST_INTERVAL) {
            // 如果有过期缓存，先返回过期缓存
            return residentName;
        }
        lastResidentRequestTime = currentTime;

        // 发送网络包请求居民信息
        requestResidentInfo();

        // 如果有过期缓存，先返回过期缓存，避免显示"加载中"
        return residentName;
    }

    /**
     * 发送网络包请求控制盒信息
     */
    private void requestControlBoxInfo() {
        try {
            com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                new com.xiaoliang.simukraft.network.RequestControlBoxInfoPacket(controlBoxPos, "residential")
            );
        } catch (Exception e) {
            System.err.println("[ResidentialControlBoxScreen] 请求控制盒信息失败: " + e.getMessage());
        }
    }

    /**
     * 发送网络包请求居民信息
     */
    private void requestResidentInfo() {
        try {
            com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                new com.xiaoliang.simukraft.network.RequestResidentInfoPacket(controlBoxPos, "residential")
            );
        } catch (Exception e) {
            System.err.println("[ResidentialControlBoxScreen] 请求居民信息失败: " + e.getMessage());
        }
    }

    /**
     * 设置建筑名称（由网络包回调）
     */
    public void setBuildingName(String name) {
        this.buildingName = name;
        this.buildingNameCacheTime = System.currentTimeMillis();
    }

    /**
     * 设置居民名字（由网络包回调）
     */
    public void setResidentName(String name) {
        this.residentName = name;
        this.residentNameCacheTime = System.currentTimeMillis();
    }

    /**
     * 清除缓存（当需要刷新数据时调用）
     */
    public void clearCache() {
        this.buildingName = null;
        this.residentName = null;
        this.buildingNameCacheTime = 0;
        this.residentNameCacheTime = 0;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC键关闭界面
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 刷新按钮状态（用于服务器同步后刷新界面）
     * 住宅控制盒没有雇佣/解雇按钮，此方法为空实现
     */
    public void refreshButtonStates() {
        // 住宅控制盒没有雇佣按钮，不需要刷新
    }
}
