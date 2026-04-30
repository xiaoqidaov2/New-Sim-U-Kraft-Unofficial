package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.client.gui.ldlib.LDLibMenuScreen;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestBuildBoxHireStatusPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
@SuppressWarnings({"null", "unchecked"})
public class BuildBoxScreen extends LDLibMenuScreen {
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 24;
    private static final int BUTTON_SPACING_CLOSE = 2;
    private static final int SHORT_BUTTON_WIDTH_THRESHOLD = 60;
    private static final int SHORT_BUTTON_HEIGHT_THRESHOLD = 24;

    private static final String LONG_BUTTON_TEXTURE = "simukraft:textures/gui/long_button.png";
    private static final String LONG_LOCK_BUTTON_TEXTURE = "simukraft:textures/gui/lock_long_button.png";
    private static final String BUTTON_TEXTURE = "simukraft:textures/gui/button.png";
    private static final String LOCK_BUTTON_TEXTURE = "simukraft:textures/gui/lock_button.png";
    // 淡蓝色边框颜色 (ARGB)
    private static final int HOVER_BORDER_COLOR = 0xFFADD8E6;

    private final BlockPos buildBoxPos;

    public BuildBoxScreen(BlockPos buildBoxPos) {
        super(Component.translatable("gui.build_box.title"), null);
        this.buildBoxPos = buildBoxPos;

        initNBTData();

        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(ModSoundEvents.BUILD_BOX_OPEN.get(), 1.0F));

        NetworkManager.INSTANCE.sendToServer(new RequestBuildBoxHireStatusPacket(buildBoxPos));
    }

    @Override
    protected int getUIWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    @Override
    protected int getUIHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    @Override
    protected boolean enableAutoScale() {
        return false;
    }

    @Override
    protected ModularUI createModularUI() {
        return createModularUI(buildBoxPos);
    }

    private void initNBTData() {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            applyNbtState(player.getPersistentData(), BuildBoxData.hasHiredBuilder(buildBoxPos), BuildBoxData.hasHiredPlanner(buildBoxPos));
        }
    }

    private static ModularUI createModularUI(BlockPos buildBoxPos) {
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        Player player = Minecraft.getInstance().player;
        BuildBoxUIHolder holder = new BuildBoxUIHolder(buildBoxPos);
        ModularUI modularUI = new ModularUI(new Size(screenWidth, screenHeight), holder, player);

        WidgetGroup rootGroup = new WidgetGroup();
        rootGroup.setSelfPosition(0, 0);
        rootGroup.setSize(screenWidth, screenHeight);

        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;

        TextTexture titleTexture = new TextTexture("gui.build_box.title");
        titleTexture.setWidth(screenWidth);
        titleTexture.setDropShadow(true);
        ImageWidget titleWidget = new ImageWidget(0, centerY - 80, screenWidth, 20, titleTexture);
        rootGroup.addWidget(titleWidget);

        TextTexture statusTexture = new TextTexture(() -> {
            var p = Minecraft.getInstance().player;
            if (p != null) {
                var status = p.getPersistentData().getString("_buildbox_status");
                return "working".equals(status) ?
                        Component.translatable("gui.build_box.status_working").getString() :
                        Component.translatable("gui.build_box.status_idle").getString();
            }
            return Component.translatable("gui.build_box.status_idle").getString();
        });
        statusTexture.setWidth(screenWidth);
        statusTexture.setColor(0xADD8E6);
        statusTexture.setDropShadow(true);
        ImageWidget statusWidget = new ImageWidget(0, centerY - 60, screenWidth, 16, statusTexture);
        rootGroup.addWidget(statusWidget);

        TextTexture instructionTexture = new TextTexture("gui.build_box.instruction");
        instructionTexture.setWidth(screenWidth);
        instructionTexture.setColor(0xF5F5A0);
        instructionTexture.setDropShadow(true);
        ImageWidget instructionWidget = new ImageWidget(0, centerY - 40, screenWidth, 16, instructionTexture);
        rootGroup.addWidget(instructionWidget);

        int totalWidth = BUTTON_WIDTH * 3 + BUTTON_SPACING_CLOSE * 2;
        int startX = centerX - totalWidth / 2;
        int firstRowY = centerY + 20;
        int secondRowY = firstRowY + BUTTON_HEIGHT + BUTTON_SPACING_CLOSE;

        // 第一行按钮
        // 1. 雇佣建造
        ButtonWidget hireBuilderButton = createRealtimeButton(
                startX, firstRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.build_box.hire_builder",
                "_buildbox_hire_builder",
                clickData -> handleHireBuilder(buildBoxPos)
        );
        rootGroup.addWidget(hireBuilderButton);

        // 2. 选择建筑
        ButtonWidget selectBuildingButton = createRealtimeButton(
                startX + BUTTON_WIDTH + BUTTON_SPACING_CLOSE, firstRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.build_box.select_building",
                "_buildbox_select_building",
                clickData -> handleSelectBuilding(buildBoxPos)
        );
        rootGroup.addWidget(selectBuildingButton);

        // 3. 解雇员工
        ButtonWidget fireEmployeeButton = createRealtimeButton(
                startX + BUTTON_WIDTH * 2 + BUTTON_SPACING_CLOSE * 2, firstRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.build_box.fire_employee",
                "_buildbox_fire_employee",
                clickData -> handleFireEmployee(buildBoxPos)
        );
        rootGroup.addWidget(fireEmployeeButton);

        // 第二行按钮
        // 1. 雇佣规划
        ButtonWidget hirePlannerButton = createRealtimeButton(
                startX, secondRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.build_box.hire_planner",
                "_buildbox_hire_planner",
                clickData -> handleHirePlanner(buildBoxPos)
        );
        rootGroup.addWidget(hirePlannerButton);

        // 2. 规划区域
        ButtonWidget planAreaButton = createRealtimeButton(
                startX + BUTTON_WIDTH + BUTTON_SPACING_CLOSE, secondRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.build_box.plan_area",
                "_buildbox_plan_area",
                clickData -> handlePlanArea(buildBoxPos)
        );
        rootGroup.addWidget(planAreaButton);

        // 3. 员工信息
        ButtonWidget employeeInfoButton = createButton(
                startX + BUTTON_WIDTH * 2 + BUTTON_SPACING_CLOSE * 2, secondRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.build_box.employee_info",
                true,
                clickData -> handleEmployeeInfo()
        );
        rootGroup.addWidget(employeeInfoButton);

        // 完成按钮（左上角）
        ButtonWidget doneButton = createButton(
                10, 10, 50, 24,
                "gui.button.done",
                true,
                clickData -> Minecraft.getInstance().setScreen(null)
        );
        rootGroup.addWidget(doneButton);

        // 水印（右上角）- 使用ImageWidget配合TextTexture实现右对齐
        TextTexture copyrightTexture = new TextTexture("gui.copyright");
        copyrightTexture.setWidth(200);
        copyrightTexture.setType(TextTexture.TextType.RIGHT);
        copyrightTexture.setColor(0x666666);
        copyrightTexture.setDropShadow(false);
        ImageWidget copyrightLabel = new ImageWidget(screenWidth - 210, 10, 200, 16, copyrightTexture);
        rootGroup.addWidget(copyrightLabel);

        modularUI.widget(rootGroup);
        modularUI.initWidgets();

        return modularUI;
    }

    private static ButtonWidget createButton(int x, int y, int width, int height, String textKey, boolean active, java.util.function.Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);

        String textureLocation = getButtonTexture(active, width, height);
        var buttonBackground = new ResourceTexture(textureLocation);
        // 悬停时显示1像素淡蓝色边框
        var buttonHover = new GuiTextureGroup(
                new ResourceTexture(textureLocation),
                new ColorBorderTexture(1, HOVER_BORDER_COLOR)
        );
        var buttonText = new TextTexture(textKey);

        button.setButtonTexture(buttonBackground, buttonText);
        button.setHoverTexture(buttonHover, buttonText);

        if (active) {
            button.setOnPressCallback(onPress);
        }

        return button;
    }

    private static ButtonWidget createRealtimeButton(int x, int y, int width, int height, String textKey, String nbtKey, java.util.function.Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);
        button.setClientSideWidget();

        // 创建动态文本
        TextTexture buttonText = new TextTexture(textKey);

        // 使用DynamicTexture实现实时更新的按钮背景
        DynamicTexture dynamicBackground = new DynamicTexture(() -> {
            var player = Minecraft.getInstance().player;
            boolean active = player != null && player.getPersistentData().getBoolean(nbtKey);
            String textureLocation = getButtonTexture(active, width, height);
            return new ResourceTexture(textureLocation);
        });

        // 使用DynamicTexture实现实时更新的悬停纹理 - 1px淡蓝色边框
        DynamicTexture dynamicHover = new DynamicTexture(() -> {
            var player = Minecraft.getInstance().player;
            boolean active = player != null && player.getPersistentData().getBoolean(nbtKey);
            String textureLocation = getButtonTexture(active, width, height);
            return new GuiTextureGroup(
                    new ResourceTexture(textureLocation),
                    new ColorBorderTexture(1, HOVER_BORDER_COLOR)
            );
        });

        button.setButtonTexture(dynamicBackground, buttonText);
        button.setHoverTexture(dynamicHover, buttonText);

        // 点击时实时检查状态
        button.setOnPressCallback(clickData -> {
            var player = Minecraft.getInstance().player;
            boolean active = player != null && player.getPersistentData().getBoolean(nbtKey);
            if (active) {
                onPress.accept(clickData);
            }
        });

        return button;
    }

    private static void handleHireBuilder(BlockPos buildBoxPos) {
        Minecraft.getInstance().setScreen(new HireBuilderScreen(buildBoxPos));
    }

    private static void handleHirePlanner(BlockPos buildBoxPos) {
        Minecraft.getInstance().setScreen(new HirePlannerScreen(buildBoxPos));
    }

    private static void handleSelectBuilding(BlockPos buildBoxPos) {
        Minecraft.getInstance().setScreen(new SelectBuildingScreen(buildBoxPos));
    }

    private static void handlePlanArea(BlockPos buildBoxPos) {
        Minecraft.getInstance().setScreen(new PlanAreaScreen(buildBoxPos));
    }

    private static void handleEmployeeInfo() {
        Minecraft.getInstance().setScreen(new EmployeeInfoScreen());
    }

    private static void handleFireEmployee(BlockPos buildBoxPos) {
        System.out.println("[BuildBoxScreen] 开始处理解雇操作 - buildBoxPos: " + buildBoxPos);

        if (BuildBoxData.hasHiredBuilder(buildBoxPos)) {
            System.out.println("[BuildBoxScreen] 检测到有雇佣建筑师");

            CustomEntity npc = BuildBoxData.getHiredBuilder(buildBoxPos);
            UUID npcUUID = null;

            if (npc != null) {
                npcUUID = npc.getUUID();
            } else {
                npcUUID = getHiredBuilderUuid(buildBoxPos);
            }

            if (npcUUID != null) {
                NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByNpc(npcUUID));
            }

            BuildBoxData.clearHiredBuilder(buildBoxPos);
            updateNBTState(buildBoxPos);
        } else if (BuildBoxData.hasHiredPlanner(buildBoxPos)) {
            System.out.println("[BuildBoxScreen] 检测到有雇佣规划师");

            CustomEntity npc = BuildBoxData.getHiredPlanner(buildBoxPos);
            UUID npcUUID = null;

            if (npc != null) {
                npcUUID = npc.getUUID();
            } else {
                npcUUID = getHiredPlannerUuid(buildBoxPos);
            }

            if (npcUUID != null) {
                NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByNpc(npcUUID));
            }

            BuildBoxData.clearHiredPlanner(buildBoxPos);
            updateNBTState(buildBoxPos);
        }
    }

    private static void updateNBTState(BlockPos buildBoxPos) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            var nbt = player.getPersistentData();
            boolean hasBuilder = BuildBoxData.hasHiredBuilder(buildBoxPos);
            boolean hasPlanner = BuildBoxData.hasHiredPlanner(buildBoxPos);
            applyNbtState(nbt, hasBuilder, hasPlanner);
        }
    }

    private static void applyNbtState(net.minecraft.nbt.CompoundTag nbt, boolean hasBuilder, boolean hasPlanner) {
        nbt.putBoolean("_buildbox_hire_builder", !hasBuilder && !hasPlanner);
        nbt.putBoolean("_buildbox_hire_planner", !hasPlanner && !hasBuilder);
        nbt.putBoolean("_buildbox_select_building", hasBuilder && !hasPlanner);
        nbt.putBoolean("_buildbox_plan_area", hasPlanner);
        nbt.putBoolean("_buildbox_fire_employee", hasBuilder || hasPlanner);
        nbt.putString("_buildbox_status", hasBuilder || hasPlanner ? "working" : "idle");
    }

    private static String getButtonTexture(boolean active, int width, int height) {
        boolean useShortTexture = width <= SHORT_BUTTON_WIDTH_THRESHOLD && height <= SHORT_BUTTON_HEIGHT_THRESHOLD;
        if (useShortTexture) {
            return active ? BUTTON_TEXTURE : LOCK_BUTTON_TEXTURE;
        }
        return active ? LONG_BUTTON_TEXTURE : LONG_LOCK_BUTTON_TEXTURE;
    }

    private static UUID getHiredBuilderUuid(BlockPos pos) {
        try {
            java.lang.reflect.Field field = BuildBoxData.class.getDeclaredField("hiredBuilderUuids");
            field.setAccessible(true);
            Map<BlockPos, UUID> map = (Map<BlockPos, UUID>) field.get(null);
            return map.get(pos);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID getHiredPlannerUuid(BlockPos pos) {
        try {
            java.lang.reflect.Field field = BuildBoxData.class.getDeclaredField("hiredPlannerUuids");
            field.setAccessible(true);
            Map<BlockPos, UUID> map = (Map<BlockPos, UUID>) field.get(null);
            return map.get(pos);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 渲染半透明黑色背景（全屏）
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        // 调用父类渲染
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public BlockPos getBuildBoxPos() {
        return buildBoxPos;
    }

    public void refreshButtonStates() {
        updateNBTState(buildBoxPos);
    }

    private static class BuildBoxUIHolder implements IUIHolder {
        public BuildBoxUIHolder(BlockPos buildBoxPos) {
        }

        @Override
        public ModularUI createUI(Player entityPlayer) {
            return null;
        }

        @Override
        public boolean isInvalid() {
            return false;
        }

        @Override
        public boolean isRemote() {
            return true;
        }

        @Override
        public void markAsDirty() {
        }
    }
}
