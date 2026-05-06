package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoliang.simukraft.client.NPCFamilyInfoCache;
import com.xiaoliang.simukraft.client.NPCResidenceCache;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.Gender;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestNPCFamilyInfoPacket;
import com.xiaoliang.simukraft.network.RequestNPCResidencePacket;
import com.xiaoliang.simukraft.utils.SkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 雇员信息界面
 * 显示所有被雇佣的NPC信息，并提供解雇功能
 */
@SuppressWarnings("null")
public class EmployeeInfoScreen extends AbstractTransitionScreen {

    // 网格布局常量
    private static final int EMPLOYEES_PER_PAGE = 6;
    private static final int COLUMNS = 3;
    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 80;
    private static final int BUTTON_SPACING = 10;

    private List<EmployeeInfo> employees;
    private Button prevPageButton;
    private Button nextPageButton;
    private int currentPage = 0;
    private final Set<String> residenceRequested = new HashSet<>();
    private final Set<UUID> familyInfoRequested = new HashSet<>();

    // 弹出式菜单相关
    private EmployeeInfo selectedEmployee = null;
    private boolean showContextMenu = false;
    private int menuX, menuY;
    private static final int MENU_WIDTH = 100;
    private static final int MENU_HEIGHT = 30; // 只有解雇按钮，所以高度小一些

    // 雇员信息记录类
    public record EmployeeInfo(
        @Nonnull UUID uuid,
        @Nonnull String name,
        @Nonnull String job,
        @Nonnull BlockPos workplacePos,
        @Nonnull String workplaceType,
        @Nullable String buildingFileName
    ) {
        public EmployeeInfo {
            uuid = nn(uuid);
            name = nn(name);
            job = nn(job);
            workplacePos = nn(workplacePos);
            workplaceType = nn(workplaceType);
        }

        public EmployeeInfo(UUID uuid, String name, String job, BlockPos workplacePos, String workplaceType) {
            this(uuid, name, job, workplacePos, workplaceType, null);
        }

        @Nonnull
        public String getJobDisplayName() {
            // 1. 工业建筑：从配置文件中动态读取职业名称
            if ("industrial".equals(workplaceType) && buildingFileName != null) {
                String jobName = IndustrialClientData.getJobName(buildingFileName);
                if (jobName != null && !jobName.isEmpty()) {
                    return jobName;
                }
            }

            // 2. 商业建筑：优先使用buildingFileName从配置文件中动态读取职业名称
            if (workplaceType.startsWith("commercial") || isCommercialWorkplace(workplaceType)) {
                // 优先通过buildingFileName查找配置，支持自定义职业
                if (buildingFileName != null && !buildingFileName.isEmpty()) {
                    String jobName = CommercialClientData.getJobNameByJobType(job, buildingFileName);
                    if (jobName != null && !jobName.isEmpty()) {
                        return jobName;
                    }
                }
                // 如果通过buildingFileName找不到，尝试通过jobType查找
                java.util.List<com.xiaoliang.simukraft.building.CommercialBuildingConfig> configs =
                    com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfigsByJobType(job);
                if (!configs.isEmpty()) {
                    String jobName = configs.get(0).getJobName();
                    if (jobName != null && !jobName.isEmpty()) {
                        return jobName;
                    }
                }
            }

            // 3. 默认职业映射
            // 首先检查商业建筑配置（统一使用CommercialBuildingManager）
            if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(job)) {
                // 优先通过buildingFileName查找配置
                if (buildingFileName != null && !buildingFileName.isEmpty()) {
                    String jobName = CommercialClientData.getJobNameByJobType(job, buildingFileName);
                    if (jobName != null && !jobName.isEmpty()) {
                        return jobName;
                    }
                }
                // 尝试通过jobType查找
                java.util.List<com.xiaoliang.simukraft.building.CommercialBuildingConfig> configs =
                    com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfigsByJobType(job);
                if (!configs.isEmpty()) {
                    String jobName = configs.get(0).getJobName();
                    if (jobName != null && !jobName.isEmpty()) {
                        return jobName;
                    }
                }
            }
            
            return switch (job) {
                case "builder" -> nn(Component.translatable("gui.employee_info.job.builder")).getString();
                case "planner" -> nn(Component.translatable("gui.employee_info.job.planner")).getString();
                case "farmer" -> nn(Component.translatable("gui.employee_info.job.farmer")).getString();
                case "warehouse_manager" -> nn(Component.translatable("gui.employee_info.job.warehouse_manager")).getString();
                default -> {
                    // 尝试从工业配置查找
                    String indJobName = IndustrialClientData.getJobNameByJobType(job);
                    if (indJobName != null && !indJobName.isEmpty()) {
                        yield indJobName;
                    }
                    yield nn(Component.translatable("gui.employee_info.job.unknown")).getString();
                }
            };
        }

        private boolean isCommercialWorkplace(String type) {
            // 从JSON配置检查是否是商业建筑
            if (type == null || type.isBlank()) return false;
            var config = com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(type);
            return config != null;
        }

        @Nonnull
        public String getWorkplaceDisplayName() {
            // 工业建筑：从配置文件中动态读取建筑名称
            if ("industrial".equals(workplaceType) && buildingFileName != null) {
                String buildingName = IndustrialClientData.getBuildingName(buildingFileName);
                if (buildingName != null && !buildingName.isEmpty()) {
                    return buildingName;
                }
            }
            
            // 自定义商业建筑：从配置文件中动态读取建筑名称
            if ("commercial".equals(workplaceType) && buildingFileName != null && !buildingFileName.isEmpty()) {
                var config = com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(buildingFileName);
                if (config != null) {
                    String buildingName = config.getBuildingName();
                    if (buildingName != null && !buildingName.isEmpty()) {
                        return buildingName;
                    }
                }
            }
            
            // 对于商业建筑，优先从JSON配置读取建筑名称
            if (workplaceType != null && !workplaceType.isBlank()) {
                var config = com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(workplaceType);
                if (config != null) {
                    String buildingName = config.getBuildingName();
                    if (buildingName != null && !buildingName.isEmpty()) {
                        return buildingName;
                    }
                }
            }
            
            return switch (workplaceType) {
                case "build_box" -> nn(Component.translatable("gui.employee_info.workplace.build_box")).getString();
                case "wool_farm" -> nn(Component.translatable("gui.employee_info.workplace.wool_farm")).getString();
                case "beef_farm" -> nn(Component.translatable("gui.employee_info.workplace.beef_farm")).getString();
                case "farmland" -> nn(Component.translatable("gui.employee_info.workplace.farmland")).getString();
                case "warehouse" -> nn(Component.translatable("gui.employee_info.workplace.warehouse")).getString();
                default -> nn(Component.translatable("gui.employee_info.workplace.unknown")).getString();
            };
        }
    }

    public EmployeeInfoScreen() {
        super(Component.translatable("gui.employee_info.title"));
        this.employees = new ArrayList<>();
        Minecraft.getInstance().getSoundManager().play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));
        // 向服务器请求雇员数据
        NetworkManager.INSTANCE.sendToServer(new com.xiaoliang.simukraft.network.EmployeeListRequestPacket());
    }

    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    /**
     * 获取NPC名称
     */
    @Nonnull
    private String getNPCName(@Nonnull UUID npcUuid, @Nullable String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            return serverName;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            // 遍历所有实体查找匹配的UUID
            for (var entity : nn(minecraft.level).entitiesForRendering()) {
                if (entity instanceof CustomEntity customEntity && entity.getUUID().equals(npcUuid)) {
                    return nn(customEntity.getFullName());
                }
            }
        }
        return "未知NPC";
    }

    /**
     * 从服务器更新雇员数据
     */
    public void updateEmployeeData(@Nonnull Map<UUID, com.xiaoliang.simukraft.network.EmployeeListRequestPacket.EmployeeData> data) {
        Simukraft.LOGGER.debug("[EmployeeInfoScreen] Received {} employees from server", data.size());
        employees.clear();
        for (var entry : data.entrySet()) {
            var empData = entry.getValue();
            Simukraft.LOGGER.debug("[EmployeeInfoScreen] Employee: {}, job: {}, workplace: {}, buildingFile: {}", empData.uuid, empData.job, empData.workplaceType, empData.buildingFileName);
            UUID employeeUuid = nn(empData.uuid);
            String name = getNPCName(employeeUuid, empData.name);
            employees.add(new EmployeeInfo(
                employeeUuid,
                name,
                nn(empData.job),
                nn(empData.workplacePos),
                nn(empData.workplaceType),
                empData.buildingFileName
            ));
        }
        // 重新创建按钮
        if (this.minecraft != null) {
            this.clearWidgets();
            this.init();
        }
    }

    @Override
    protected void init() {
        super.init();

        // 返回按钮
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.button.back"),
                        button -> this.onClose())
                .bounds(5, 5, 45, 20)
                .build());

        // 分页按钮
        this.prevPageButton = Button.builder(
                        Component.literal("<"),
                        button -> {
                            if (currentPage > 0) {
                                currentPage--;
                                updateButtons();
                            }
                        })
                .bounds(this.width / 2 - 100, this.height - 30, 20, 20)
                .build();
        this.addRenderableWidget(nn(prevPageButton));

        this.nextPageButton = Button.builder(
                        Component.literal(">"),
                        button -> {
                            int maxPages = (int) Math.ceil((double) employees.size() / EMPLOYEES_PER_PAGE);
                            if (currentPage < maxPages - 1) {
                                currentPage++;
                                updateButtons();
                            }
                        })
                .bounds(this.width / 2 + 80, this.height - 30, 20, 20)
                .build();
        this.addRenderableWidget(nn(nextPageButton));

        updateButtons();
    }

    private void updateButtons() {
        // 清除旧的雇员按钮
        this.clearWidgets();

        // 重新添加导航按钮
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.button.back"),
                        button -> this.onClose())
                .bounds(5, 5, 45, 20)
                .build());

        this.addRenderableWidget(nn(prevPageButton));
        this.addRenderableWidget(nn(nextPageButton));

        // 获取当前页的雇员
        int startIndex = currentPage * EMPLOYEES_PER_PAGE;
        int endIndex = Math.min(startIndex + EMPLOYEES_PER_PAGE, employees.size());
        int itemsOnPage = endIndex - startIndex;

        // 自适应缩放计算
        int availableWidth = this.width - 40;
        int availableHeight = this.height - 80;

        // 计算当前页实际需要的行数
        int actualRows = (int) Math.ceil((double) itemsOnPage / COLUMNS);
        actualRows = Math.max(1, actualRows);

        // 计算最大可能的按钮尺寸
        int maxButtonWidth = (availableWidth - (COLUMNS - 1) * BUTTON_SPACING) / COLUMNS;
        int maxButtonHeight = actualRows > 0 ? (availableHeight - (actualRows - 1) * BUTTON_SPACING) / actualRows : BUTTON_HEIGHT;

        // 使用计算出的尺寸，但不小于最小值
        int actualButtonWidth = Math.max(140, Math.min(BUTTON_WIDTH, maxButtonWidth));
        int actualButtonHeight = Math.max(70, Math.min(BUTTON_HEIGHT, maxButtonHeight));

        // 计算布局
        int totalWidth = COLUMNS * actualButtonWidth + (COLUMNS - 1) * BUTTON_SPACING;
        int totalHeight = actualRows * actualButtonHeight + (actualRows - 1) * BUTTON_SPACING;
        int startX = (this.width - totalWidth) / 2;
        int startY = 40 + (availableHeight - totalHeight) / 2;
        startY = Math.max(40, Math.min(startY, this.height - totalHeight - 40));

        // 创建雇员按钮
        for (int i = startIndex; i < endIndex; i++) {
            EmployeeInfo employee = employees.get(i);
            int index = i - startIndex;
            int col = index % COLUMNS;
            int row = index / COLUMNS;

            int x = startX + col * (actualButtonWidth + BUTTON_SPACING);
            int y = startY + row * (actualButtonHeight + BUTTON_SPACING);

            EmployeeButton button = new EmployeeButton(x, y, actualButtonWidth, actualButtonHeight, employee,
                btn -> onEmployeeClicked(employee, x + actualButtonWidth / 2, y + actualButtonHeight));
            this.addRenderableWidget(button);
        }

        // 更新分页按钮状态
        int maxPages = (int) Math.ceil((double) employees.size() / EMPLOYEES_PER_PAGE);
        nn(this.prevPageButton).active = currentPage > 0;
        nn(this.nextPageButton).active = currentPage < maxPages - 1;
    }

    private void onEmployeeClicked(@Nonnull EmployeeInfo employee, int buttonCenterX, int buttonBottomY) {
        if (selectedEmployee != null && nn(selectedEmployee).uuid().equals(employee.uuid())) {
            // 点击同一个雇员，切换菜单显示/隐藏
            if (showContextMenu) {
                hideContextMenu();
            } else {
                showContextMenu(employee, buttonCenterX, buttonBottomY);
            }
        } else {
            // 点击不同的雇员，显示新菜单
            hideContextMenu();
            showContextMenu(employee, buttonCenterX, buttonBottomY);
        }
    }

    private void showContextMenu(@Nonnull EmployeeInfo employee, int buttonCenterX, int buttonBottomY) {
        selectedEmployee = employee;
        showContextMenu = true;

        // 计算菜单位置（在按钮下方居中）
        menuX = buttonCenterX - MENU_WIDTH / 2;
        menuY = buttonBottomY + 5;

        // 确保菜单不超出屏幕
        if (menuX < 10) menuX = 10;
        if (menuX + MENU_WIDTH > this.width - 10) menuX = this.width - 10 - MENU_WIDTH;
        if (menuY + MENU_HEIGHT > this.height - 10) menuY = buttonBottomY - MENU_HEIGHT - 5;
    }

    private void hideContextMenu() {
        showContextMenu = false;
        selectedEmployee = null;
    }

    private void fireEmployee() {
        if (selectedEmployee != null) {
            // 直接解雇，无需确认
            Simukraft.LOGGER.info("[EmployeeInfoScreen] 解雇雇员: " + nn(selectedEmployee).name());
            NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByNpc(nn(selectedEmployee).uuid()));

            // 同步清除本地BuildBoxData中的雇佣记录
            if ("build_box".equals(nn(selectedEmployee).workplaceType())) {
                if ("builder".equals(nn(selectedEmployee).job())) {
                    BuildBoxData.clearHiredBuilder(nn(selectedEmployee).workplacePos());
                } else if ("planner".equals(nn(selectedEmployee).job())) {
                    BuildBoxData.clearHiredPlanner(nn(selectedEmployee).workplacePos());
                }
            }

            // 本地移除
            employees.removeIf(e -> e.uuid().equals(nn(selectedEmployee).uuid()));
            updateButtons();
        }
        hideContextMenu();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 如果菜单显示，优先处理菜单点击
        if (showContextMenu) {
            // 检查是否点击在菜单区域内
            boolean clickedInMenu = mouseX >= menuX && mouseX <= menuX + MENU_WIDTH &&
                                    mouseY >= menuY && mouseY <= menuY + MENU_HEIGHT;

            if (clickedInMenu) {
                // 检查点击的是解雇按钮
                if (mouseY >= menuY + 5 && mouseY <= menuY + 25) {
                    fireEmployee();
                }
                return true;
            } else {
                // 点击在菜单外，关闭菜单
                hideContextMenu();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 绘制黑色半透明背景
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x80000000, 0x80000000);

        // 绘制标题
        guiGraphics.drawCenteredString(
            nn(this.font),
            nn(this.title),
            this.width / 2,            5,
            0xFFFFFF
        );

        // 绘制页码信息
        int maxPages = (int) Math.ceil((double) employees.size() / EMPLOYEES_PER_PAGE);
        if (maxPages > 0) {
            guiGraphics.drawCenteredString(
                nn(this.font),
                Component.literal((currentPage + 1) + " / " + maxPages),
                this.width / 2,
                this.height - 25,
                0xFFFFFF
            );
        }

        if (employees.isEmpty()) {
            guiGraphics.drawCenteredString(
                nn(this.font),
                Component.translatable("gui.employee_info.empty"),
                this.width / 2,
                this.height / 2,
                0xAAAAAA
            );
        }

        // 绘制所有内容（按钮等）
        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        // 最后绘制弹出式菜单（确保在最上层）
        if (showContextMenu) {
            // 重置混合状态并禁用深度测试，确保菜单覆盖所有内容
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

            // 提高 Z 坐标，确保在最上层
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 400);

            // 绘制菜单背景
            guiGraphics.fill(menuX - 2, menuY - 2, menuX + MENU_WIDTH + 2, menuY + MENU_HEIGHT + 2, 0xFF000000);
            guiGraphics.fillGradient(menuX, menuY, menuX + MENU_WIDTH, menuY + MENU_HEIGHT, 0xFF111111, 0xFF111111);

            // 绘制菜单边框
            guiGraphics.renderOutline(menuX, menuY, MENU_WIDTH, MENU_HEIGHT, 0xFFFFFFFF);

            // 绘制解雇按钮文字
            guiGraphics.drawString(
                nn(this.font),
                Component.translatable("gui.employee_info.fire"),
                menuX + 30,
                menuY + 10,
                0xFFFFFF,
                false
            );

            guiGraphics.pose().popPose();
            RenderSystem.enableDepthTest();
        }
    }

    /**
     * 雇员按钮类
     */
    private class EmployeeButton extends Button {
        private final EmployeeInfo employee;

        public EmployeeButton(int x, int y, int width, int height, EmployeeInfo employee, OnPress onPress) {
            super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
            this.employee = employee;
        }

        @Override
        public void renderWidget(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
            // 绘制按钮背景
            int bgColor = this.isHovered() ? 0xFF444444 : 0xFF333333;
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bgColor);

            // 绘制边框
            guiGraphics.renderOutline(this.getX(), this.getY(), this.width, this.height, 0xFFFFFFFF);

            // 绘制NPC头像（32x32，与市民管理一致）
            renderNPCHead(guiGraphics, employee.uuid(), this.getX() + 8, this.getY() + 8, 32);

            // 绘制NPC名称
            guiGraphics.drawString(
                nn(Minecraft.getInstance().font),
                employee.name(),
                this.getX() + 50,
                this.getY() + 8,
                0xFFFFFF
            );

            CustomEntity npc = getNpcEntity(employee.uuid());

            // 绘制工作需求
            String needText = npc != null
                    ? Component.translatable(npc.getHunger() <= 6 ? "gui.npc_need.hungry" : "gui.npc_need.ok", npc.getHunger()).getString()
                    : Component.translatable("gui.npc_interaction.loading").getString();
            guiGraphics.drawString(
                    nn(Minecraft.getInstance().font),
                    Component.translatable("gui.npc_interaction.work_need").getString() + needText,
                    this.getX() + 50,
                    this.getY() + 22,
                    0xAAAAAA
            );

            // 绘制职业
            guiGraphics.drawString(
                nn(Minecraft.getInstance().font),
                Component.translatable("gui.npc_interaction.job").getString() + employee.getJobDisplayName(),
                this.getX() + 50,
                this.getY() + 36,
                0x888888
            );

            // 绘制住宅状态
            String residence = getResidenceText(npc != null ? npc.getFullName() : employee.name());
            guiGraphics.drawString(
                    nn(Minecraft.getInstance().font),
                    Component.translatable("gui.npc_interaction.residence").getString() + residence,
                    this.getX() + 50,
                    this.getY() + 50,
                    0x666666
            );

            // 绘制婚姻状态
            guiGraphics.drawString(
                    nn(Minecraft.getInstance().font),
                    Component.translatable("gui.npc_interaction.marriage").getString() + getMarriageText(employee.uuid()),
                    this.getX() + 50,
                    this.getY() + 64,
                    0x666666
            );
        }
    }

    @Nullable
    private CustomEntity getNpcEntity(@Nonnull UUID npcUuid) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return null;
        for (var entity : nn(minecraft.level).entitiesForRendering()) {
            if (entity instanceof CustomEntity customEntity && entity.getUUID().equals(npcUuid)) {
                return customEntity;
            }
        }
        return null;
    }

    @Nonnull
    private String getResidenceText(@Nonnull String npcName) {
        NPCResidenceCache.ResidenceInfo cachedInfo = NPCResidenceCache.getResidenceInfo(npcName);
        if (cachedInfo == null) {
            if (residenceRequested.add(npcName)) {
                NetworkManager.INSTANCE.sendToServer(new RequestNPCResidencePacket(npcName));
            }
            return Component.translatable("gui.npc_interaction.loading").getString();
        }
        if (cachedInfo.hasResidence) {
            if (cachedInfo.position != null && !cachedInfo.position.isEmpty()) {
                return Component.translatable("gui.npc_interaction.has_residence", cachedInfo.position).getString();
            }
            return Component.translatable("gui.npc_interaction.has_residence_unknown").getString();
        }
        return Component.translatable("gui.npc_interaction.no_residence").getString();
    }

    @Nonnull
    private String getMarriageText(@Nonnull UUID npcUuid) {
        NPCFamilyInfoCache.FamilyInfo cachedInfo = NPCFamilyInfoCache.get(npcUuid);
        if (cachedInfo == null) {
            if (familyInfoRequested.add(npcUuid)) {
                NetworkManager.INSTANCE.sendToServer(new RequestNPCFamilyInfoPacket(npcUuid));
            }
            return Component.translatable("gui.npc_interaction.loading").getString();
        }
        if (cachedInfo.spouseName() == null || cachedInfo.spouseName().isBlank()) {
            return Component.translatable("gui.npc_marriage.single").getString();
        }
        return Component.translatable("gui.npc_marriage.married_format", cachedInfo.spouseName()).getString();
    }

    /**
     * 渲染NPC头像 - 与市民管理、雇佣列表保持一致
     */
    private void renderNPCHead(@Nonnull GuiGraphics guiGraphics, @Nonnull UUID npcUuid, int x, int y, int size) {
        // 获取NPC实体
        CustomEntity npc = null;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            for (var entity : nn(minecraft.level).entitiesForRendering()) {
                if (entity instanceof CustomEntity customEntity && entity.getUUID().equals(npcUuid)) {
                    npc = customEntity;
                    break;
                }
            }
        }

        String skinPath = null;
        if (npc != null) {
            skinPath = npc.getSkinPath();
        }

        if (skinPath == null || skinPath.isEmpty() || !SkinManager.isValidSkinPath(skinPath)) {
            Gender gender = npc != null ? npc.getGender() : Gender.MALE;
            skinPath = SkinManager.getDefaultSkinPath(gender);
        }

        ResourceLocation skinLocation = nn(SkinManager.getTextureResourceLocation(skinPath));
        
        RenderSystem.setShaderTexture(0, skinLocation);
        guiGraphics.blit(skinLocation, x, y, size, size, 8.0F, 8.0F, 8, 8, 64, 64);
        guiGraphics.blit(skinLocation, x, y, size, size, 40.0F, 8.0F, 8, 8, 64, 64);
    }
}
