package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.building.IndustrialBuildingConfig;
import com.xiaoliang.simukraft.building.IndustrialBuildingManager;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.network.CitizenListRequestPacket;
import com.xiaoliang.simukraft.network.CitizenListResponsePacket;
import com.xiaoliang.simukraft.network.DeleteNPCPacket;
import com.xiaoliang.simukraft.network.EmploymentCommandPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RenameNPCPacket;
import com.xiaoliang.simukraft.utils.SkinManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

public class CityCitizenScreen extends AbstractTransitionScreen {
    private final BlockPos cityCorePos;

    // 网格布局常量
    private static final int CITIZENS_PER_PAGE = 6;
    private static final int COLUMNS = 3;
    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 80;
    private static final int BUTTON_SPACING = 10;

    private List<CitizenInfo> citizens;
    private List<CitizenInfo> filteredCitizens;
    private Button backButton;
    private Button prevPageButton;
    private Button nextPageButton;
    private int currentPage = 0;

    // 弹出式菜单相关
    private CitizenInfo selectedCitizen = null;
    private boolean showContextMenu = false;
    private int menuX, menuY;
    private static final int MENU_WIDTH = 100;
    private static final int MENU_HEIGHT = 80; // 增加高度以容纳解雇按钮
    private Button renameButton;
    private Button deleteButton;
    private Button fireButton; // 解雇按钮
    private EditBox renameBox;
    private boolean isRenaming = false;

    // 自动刷新相关
    private static final long REFRESH_INTERVAL_MS = 2000; // 每2秒刷新一次
    private long lastRefreshTime = 0;

    // 本地市民信息类 - 使用服务端响应包中的定义
    public record CitizenInfo(UUID uuid, String name, int npcId, boolean hasResidence, String job, String skinPath, int level, int xp) {
        /**
         * 从服务端响应数据转换为本地数据
         */
        public static CitizenInfo fromResponse(CitizenListResponsePacket.CitizenInfo responseInfo) {
            return new CitizenInfo(
                responseInfo.uuid(),
                responseInfo.name(),
                responseInfo.npcId(),
                responseInfo.hasResidence(),
                responseInfo.job(),
                responseInfo.skinPath(),
                responseInfo.level(),
                responseInfo.xp()
            );
        }
    }

    public CityCitizenScreen(BlockPos cityCorePos) {
        super(Component.translatable("gui.city_citizen.title"));
        this.cityCorePos = cityCorePos;
        // 添加音效播放
        Minecraft.getInstance().getSoundManager().play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));

        this.citizens = new ArrayList<>();
        this.filteredCitizens = new ArrayList<>();

        // 从服务器请求市民列表
        requestCitizenListFromServer();
    }

    private void requestCitizenListFromServer() {
        // 发送网络包请求市民列表
        Simukraft.LOGGER.debug("[CityCitizenScreen] Requesting citizen list for city at: {}", cityCorePos);
        NetworkManager.INSTANCE.sendToServer(new CitizenListRequestPacket(cityCorePos));
    }

    // 设置市民列表，用于接收服务器响应
    public static void setCitizenList(List<CitizenListResponsePacket.CitizenInfo> serverCitizenInfos) {
        Simukraft.LOGGER.debug("[CityCitizenScreen] Received citizen list with {} citizens", serverCitizenInfos.size());
        for (CitizenListResponsePacket.CitizenInfo info : serverCitizenInfos) {
            Simukraft.LOGGER.debug("[CityCitizenScreen] Citizen: {}, job: {}", info.name(), info.job());
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CityCitizenScreen screen) {
            // 转换服务端数据为本地数据
            List<CitizenInfo> localCitizens = new ArrayList<>();
            for (CitizenListResponsePacket.CitizenInfo info : serverCitizenInfos) {
                localCitizens.add(CitizenInfo.fromResponse(info));
            }

            screen.citizens = localCitizens;
            screen.filteredCitizens = new ArrayList<>(localCitizens);
            // 确保按钮已初始化后再更新
            if (screen.prevPageButton != null && screen.nextPageButton != null) {
                screen.updateButtons();
            }
        } else {
            Simukraft.LOGGER.debug("[CityCitizenScreen] Current screen is not CityCitizenScreen, cannot update");
        }
    }

    @Override
    protected void init() {
        super.init();

        // 返回按钮
        this.backButton = nn(Button.builder(
                nn(Component.translatable("gui.back")),
                button -> this.closeScreen()
            ).pos(width - 90, height - 30)
            .size(80, 20)
            .build());
        this.addRenderableWidget(this.backButton);

        // 分页按钮
        int paginationY = this.height - 30;
        this.prevPageButton = nn(Button.builder(
                nn(Component.translatable("gui.pagination.previous")),
                button -> {
                    if (currentPage > 0) {
                        currentPage--;
                        hideContextMenu();
                        updateButtons();
                    }
                }
            ).bounds(this.width / 2 - 100, paginationY, 60, 20)
            .build());
        this.addRenderableWidget(this.prevPageButton);

        this.nextPageButton = nn(Button.builder(
                nn(Component.translatable("gui.pagination.next")),
                button -> {
                    int maxPages = (int) Math.ceil((double) filteredCitizens.size() / CITIZENS_PER_PAGE);
                    if (currentPage < maxPages - 1) {
                        currentPage++;
                        hideContextMenu();
                        updateButtons();
                    }
                }
            ).bounds(this.width / 2 + 40, paginationY, 60, 20)
            .build());
        this.addRenderableWidget(this.nextPageButton);

        updateButtons();
    }

    private void updateButtons() {
        // 清除旧的市民按钮
        this.children().removeIf(child -> child instanceof CitizenButton);
        this.renderables.removeIf(child -> child instanceof CitizenButton);

        int startIndex = currentPage * CITIZENS_PER_PAGE;
        int endIndex = Math.min(startIndex + CITIZENS_PER_PAGE, filteredCitizens.size());
        int itemsOnPage = endIndex - startIndex;

        // 自动缩放计算 - 根据实际显示数量动态调整
        int availableWidth = this.width - 40;
        int availableHeight = this.height - 100;

        // 计算当前页实际需要的行数
        int actualRows = (int) Math.ceil((double) itemsOnPage / COLUMNS);
        actualRows = Math.max(1, actualRows);

        // 计算最大可能的按钮尺寸
        int maxButtonWidth = (availableWidth - (COLUMNS - 1) * BUTTON_SPACING) / COLUMNS;
        int maxButtonHeight = actualRows > 0 ? (availableHeight - (actualRows - 1) * BUTTON_SPACING) / actualRows : BUTTON_HEIGHT;

        // 使用计算出的尺寸，但不小于最小值
        int actualButtonWidth = Math.max(120, Math.min(BUTTON_WIDTH, maxButtonWidth));
        int actualButtonHeight = Math.max(60, Math.min(BUTTON_HEIGHT, maxButtonHeight));

        // 计算起始位置，确保居中
        int totalGridWidth = COLUMNS * actualButtonWidth + (COLUMNS - 1) * BUTTON_SPACING;
        int totalGridHeight = actualRows * actualButtonHeight + (actualRows - 1) * BUTTON_SPACING;

        int startX = (this.width - totalGridWidth) / 2;
        int startY = 50 + (availableHeight - totalGridHeight) / 2;
        startY = Math.max(50, Math.min(startY, this.height - totalGridHeight - 50));

        for (int i = startIndex; i < endIndex; i++) {
            CitizenInfo citizen = filteredCitizens.get(i);
            int relativeIndex = i - startIndex;
            int row = relativeIndex / COLUMNS;
            int col = relativeIndex % COLUMNS;

            int x = startX + col * (actualButtonWidth + BUTTON_SPACING);
            int y = startY + row * (actualButtonHeight + BUTTON_SPACING);

            CitizenButton button = new CitizenButton(x, y, actualButtonWidth, actualButtonHeight, citizen,
                btn -> onCitizenClicked(citizen, x + actualButtonWidth / 2, y + actualButtonHeight));
            this.addRenderableWidget(button);
        }

        // 更新分页按钮位置
        int paginationY = this.height - 30;
        nn(this.prevPageButton).setX(this.width / 2 - 100);
        nn(this.prevPageButton).setY(paginationY);
        nn(this.nextPageButton).setX(this.width / 2 + 40);
        nn(this.nextPageButton).setY(paginationY);

        // 更新分页按钮状态
        int maxPages = (int) Math.ceil((double) filteredCitizens.size() / CITIZENS_PER_PAGE);
        nn(this.prevPageButton).active = currentPage > 0;
        nn(this.nextPageButton).active = currentPage < maxPages - 1;
    }

    private void onCitizenClicked(CitizenInfo citizen, int buttonCenterX, int buttonBottomY) {
        if (selectedCitizen != null && selectedCitizen.uuid().equals(citizen.uuid())) {
            // 点击同一个市民，切换菜单显示/隐藏
            if (showContextMenu) {
                hideContextMenu();
            } else {
                showContextMenu(citizen, buttonCenterX, buttonBottomY);
            }
        } else {
            // 点击不同的市民，显示新菜单
            hideContextMenu();
            showContextMenu(citizen, buttonCenterX, buttonBottomY);
        }
    }

    private void showContextMenu(CitizenInfo citizen, int buttonCenterX, int buttonBottomY) {
        selectedCitizen = citizen;
        showContextMenu = true;
        isRenaming = false;

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
        selectedCitizen = null;
        isRenaming = false;

        if (renameButton != null) {
            this.removeWidget(renameButton);
            renameButton = null;
        }
        if (deleteButton != null) {
            this.removeWidget(deleteButton);
            deleteButton = null;
        }
        if (fireButton != null) {
            this.removeWidget(fireButton);
            fireButton = null;
        }
        if (renameBox != null) {
            this.removeWidget(renameBox);
            renameBox = null;
        }
    }

    private void startRename() {
        isRenaming = true;

        // 移除重命名、删除和解雇按钮
        if (renameButton != null) {
            this.removeWidget(renameButton);
            renameButton = null;
        }
        if (deleteButton != null) {
            this.removeWidget(deleteButton);
            deleteButton = null;
        }
        if (fireButton != null) {
            this.removeWidget(fireButton);
            fireButton = null;
        }

        // 创建输入框
        renameBox = nn(new EditBox(nn(this.font), menuX + 5, menuY + 5, MENU_WIDTH - 10, 20, nn(Component.literal(""))));
        renameBox.setValue(safeString(selectedCitizen.name()));
        renameBox.setMaxLength(32);
        renameBox.setFocused(true);
        renameBox.setBordered(true);
        // EditBox 在 Minecraft 1.20.1 中没有 setBackgroundColor 方法
        // 背景色由渲染系统处理，我们确保菜单背景能完全覆盖即可
        this.addRenderableWidget(renameBox);

        // 创建确认按钮
        renameButton = nn(Button.builder(
                nn(Component.translatable("gui.button.confirm")),
                btn -> confirmRename()
            ).bounds(menuX + 5, menuY + 30, MENU_WIDTH - 10, 20)
            .build());
        this.addRenderableWidget(renameButton);
    }

    private void confirmRename() {
        if (renameBox != null && selectedCitizen != null) {
            String newName = renameBox.getValue().trim();
            if (!newName.isEmpty() && !newName.equals(selectedCitizen.name())) {
                // 发送网络包重命名NPC
                System.out.println("[CityCitizenScreen] 发送重命名请求: NPC=" + selectedCitizen.uuid() + ", 新名称=" + newName);
                NetworkManager.INSTANCE.sendToServer(new RenameNPCPacket(selectedCitizen.uuid(), newName));
                
                // 本地更新市民列表中的名称（临时显示，等待服务器确认）
                for (CitizenInfo citizen : citizens) {
                    if (citizen.uuid().equals(selectedCitizen.uuid())) {
                        // 创建新的CitizenInfo对象，因为record是不可变的
                        citizens.set(citizens.indexOf(citizen), 
                            new CitizenInfo(citizen.uuid(), newName, citizen.npcId(), 
                                          citizen.hasResidence(), citizen.job(), citizen.skinPath(),
                                          citizen.level(), citizen.xp()));
                        break;
                    }
                }
                // 更新过滤列表
                filteredCitizens = new ArrayList<>(citizens);
                updateButtons();
            }
        }
        hideContextMenu();
    }
    
    /**
     * 刷新市民列表（用于同步删除操作后更新界面）
     */
    public void refreshCitizenList() {
        // 重新请求市民列表数据
        System.out.println("[CityCitizenScreen] 刷新市民列表");
        
        // 重新发送市民列表请求
        NetworkManager.INSTANCE.sendToServer(new CitizenListRequestPacket(cityCorePos));
        
        // 重置页面状态
        currentPage = 0;
        updateButtons();
    }

    private void fireCitizen() {
        if (selectedCitizen != null) {
            // 检查NPC是否有工作
            String job = selectedCitizen.job();
            if (job == null || job.isEmpty() || "unemployed".equals(job)) {
                // NPC没有工作，显示提示
                if (Minecraft.getInstance().player != null) {
                    nn(Minecraft.getInstance().player).displayClientMessage(
                        nn(Component.translatable("message.city_citizen.no_job", selectedCitizen.name())),
                        false
                    );
                }
                hideContextMenu();
                return;
            }

            // 打开确认屏幕
            ConfirmationScreen.open(
                Component.translatable("gui.city_citizen.confirm_fire_title"),
                Component.translatable("gui.city_citizen.confirm_fire_message", selectedCitizen.name(), getJobDisplayName(job)),
                confirmed -> {
                    if (confirmed) {
                        // 用户确认解雇
                        System.out.println("[CityCitizenScreen] 用户确认解雇NPC: " + selectedCitizen.name());
                        // 发送解雇请求 - 统一走 v2 EmploymentCommandPacket
                        NetworkManager.INSTANCE.sendToServer(EmploymentCommandPacket.fireByNpc(selectedCitizen.uuid()));

                        // 本地更新职业显示
                        for (int i = 0; i < citizens.size(); i++) {
                            CitizenInfo citizen = citizens.get(i);
                            if (citizen.uuid().equals(selectedCitizen.uuid())) {
                                citizens.set(i, new CitizenInfo(
                                    citizen.uuid(),
                                    citizen.name(),
                                    citizen.npcId(),
                                    citizen.hasResidence(),
                                    "unemployed", // 设置为失业
                                    citizen.skinPath(),
                                    citizen.level(),
                                    citizen.xp()
                                ));
                                break;
                            }
                        }
                        filteredCitizens = new ArrayList<>(citizens);
                        updateButtons();

                        if (Minecraft.getInstance().player != null) {
                            nn(Minecraft.getInstance().player).displayClientMessage(
                                nn(Component.translatable("message.city_citizen.fired", selectedCitizen.name())),
                                false
                            );
                        }
                    } else {
                        // 用户取消解雇
                        System.out.println("[CityCitizenScreen] 用户取消解雇NPC");
                    }
                    // 关闭菜单
                    hideContextMenu();
                }
            );
        } else {
            hideContextMenu();
        }
    }

    // 职业名称缓存，避免重复查找
    private static final Map<String, String> jobNameCache = new HashMap<>();

    /**
     * 获取职业显示名称
     * 优先从JSON配置文件中读取，如果找不到则使用默认映射
     */
    private static String getJobDisplayName(String job) {
        if (job == null || job.isEmpty() || "unemployed".equals(job)) {
            return nn(Component.translatable("job.unemployed")).getString();
        }

        // 检查缓存
        if (jobNameCache.containsKey(job)) {
            return nn(jobNameCache.get(job));
        }

        // 1. 先从商业建筑配置中查找
        List<CommercialBuildingConfig> commercialConfigs = CommercialBuildingManager.getConfigsByJobType(job);
        if (!commercialConfigs.isEmpty()) {
            String jobName = commercialConfigs.get(0).getJobName();
            if (jobName != null && !jobName.isEmpty()) {
                jobNameCache.put(job, jobName);
                return jobName;
            }
        }

        // 2. 再从工业建筑配置中查找
        List<IndustrialBuildingConfig> industrialConfigs = IndustrialBuildingManager.getConfigsByJobType(job);
        if (!industrialConfigs.isEmpty()) {
            String jobName = industrialConfigs.get(0).getJobName();
            if (jobName != null && !jobName.isEmpty()) {
                jobNameCache.put(job, jobName);
                return jobName;
            }
        }

        // 3. 使用默认映射作为后备
        String defaultName = getDefaultJobDisplayName(job);
        jobNameCache.put(job, defaultName);
        return defaultName;
    }

    /**
     * 默认职业名称映射（硬编码作为后备）
     */
    private static String getDefaultJobDisplayName(String job) {
        return switch (job) {
            case "builder" -> nn(Component.translatable("job.builder")).getString();
            case "planner" -> nn(Component.translatable("job.planner")).getString();
            case "shepherd" -> nn(Component.translatable("job.shepherd")).getString();
            case "butcher" -> nn(Component.translatable("job.butcher")).getString();
            case "farmer" -> nn(Component.translatable("job.farmer")).getString();
            case "warehouse_manager" -> nn(Component.translatable("job.warehouse_manager")).getString();
            default -> job; // 如果找不到，返回原始的jobType
        };
    }

    private void deleteCitizen() {
        if (selectedCitizen != null) {
            // 打开确认屏幕
            ConfirmationScreen.openForNPCDelete(
                selectedCitizen.name(),
                confirmed -> {
                    if (confirmed) {
                        // 用户确认删除，执行删除操作
                        System.out.println("[CityCitizenScreen] 用户确认删除NPC: " + selectedCitizen.name());
                        // 发送网络包删除NPC
                        NetworkManager.INSTANCE.sendToServer(new DeleteNPCPacket(selectedCitizen.uuid()));
                        
                        // 本地立即从列表中移除NPC（临时显示，等待服务器确认）
                        citizens.removeIf(citizen -> citizen.uuid().equals(selectedCitizen.uuid()));
                        filteredCitizens = new ArrayList<>(citizens);
                        updateButtons();
                    } else {
                        // 用户取消删除
                        System.out.println("[CityCitizenScreen] 用户取消删除NPC");
                    }
                    // 关闭菜单
                    hideContextMenu();
                }
            );
        } else {
            hideContextMenu();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 如果菜单显示，优先处理菜单点击
        if (showContextMenu) {
            // 检查是否点击在菜单区域内
            boolean clickedInMenu = mouseX >= menuX && mouseX <= menuX + MENU_WIDTH &&
                                    mouseY >= menuY && mouseY <= menuY + MENU_HEIGHT;

            if (clickedInMenu) {
                // 检查点击的是哪个选项
                if (!isRenaming) {
                    // 重命名选项区域
                    if (mouseY >= menuY + 5 && mouseY <= menuY + 25) {
                        startRename();
                    }
                    // 解雇选项区域
                    else if (mouseY >= menuY + 30 && mouseY <= menuY + 50) {
                        fireCitizen();
                    }
                    // 删除选项区域
                    else if (mouseY >= menuY + 55 && mouseY <= menuY + 75) {
                        deleteCitizen();
                    }
                } else {
                    // 重命名模式下的点击处理
                    if (renameBox != null && renameBox.isMouseOver(mouseX, mouseY)) {
                        renameBox.mouseClicked(mouseX, mouseY, button);
                    }
                    // 确认按钮区域
                    else if (mouseY >= menuY + 30 && mouseY <= menuY + 50) {
                        confirmRename();
                    }
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
    public void tick() {
        super.tick();
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRefreshTime >= REFRESH_INTERVAL_MS) {
            lastRefreshTime = currentTime;
            requestCitizenListFromServer();
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 绘制黑色半透明背景（调整透明度）
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x80000000, 0x80000000);

        // 绘制标题、页码信息等固定内容
        guiGraphics.drawCenteredString(
            nn(this.font),
            nn(this.title),
            this.width / 2,
            5,
            0xFFFFFF
        );

        int maxPages = (int) Math.ceil((double) filteredCitizens.size() / CITIZENS_PER_PAGE);
        if (maxPages > 0) {
            guiGraphics.drawCenteredString(
                nn(this.font),
                nn(Component.translatable("gui.pagination.info", currentPage + 1, maxPages)),
                this.width / 2,
                this.height - 35,
                0xFFFFFF
            );
        }

        if (filteredCitizens.isEmpty()) {
            guiGraphics.drawCenteredString(
                nn(this.font),
                nn(Component.translatable("gui.city_citizen.empty")),
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
            RenderSystem.disableDepthTest();  // 关键：禁用深度测试，使菜单绘制在所有内容之上
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

            // 提高 Z 坐标，确保在最上层
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 400);  // 提高 Z 坐标，确保在最上层

            // 绘制菜单背景（完全不透明）
            guiGraphics.fill(menuX - 2, menuY - 2, menuX + MENU_WIDTH + 2, menuY + MENU_HEIGHT + 2, 0xFF000000); // 黑色边框
            guiGraphics.fillGradient(menuX, menuY, menuX + MENU_WIDTH, menuY + MENU_HEIGHT, 0xFF111111, 0xFF111111); // 深灰背景
            
            // 绘制菜单边框
            guiGraphics.renderOutline(menuX, menuY, MENU_WIDTH, MENU_HEIGHT, 0xFFFFFFFF);
            
            // 绘制菜单文字（直接绘制，但深度测试已禁用）
            if (!isRenaming) {
                guiGraphics.drawString(
                    nn(this.font),
                    nn(Component.translatable("gui.city_citizen.rename")),
                    menuX + 15,
                    menuY + 10,
                    0xFFFFFF,
                    false  // dropShadow 设为 false
                );
                guiGraphics.drawString(
                    nn(this.font),
                    nn(Component.translatable("gui.city_citizen.fire")),
                    menuX + 15,
                    menuY + 35,
                    0xFFFFFF,
                    false
                );
                guiGraphics.drawString(
                    nn(this.font),
                    nn(Component.translatable("gui.city_citizen.banish")),
                    menuX + 15,
                    menuY + 60,
                    0xFFFFFF,
                    false
                );
            } else {
                // 重命名模式下的渲染
                if (renameBox != null) {
                    renameBox.render(guiGraphics, mouseX, mouseY, partialTicks);
                }
                guiGraphics.drawString(
                    nn(this.font),
                    nn(Component.translatable("gui.button.confirm")),
                    menuX + 15,
                    menuY + 35,
                    0xFFFFFF,
                    false
                );
            }

            guiGraphics.pose().popPose();
            RenderSystem.enableDepthTest();  // 恢复深度测试
        }
    }

    @Override
    public void onClose() {
        this.closeScreen();
    }

    private void closeScreen() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new CityManagementScreen(cityCorePos));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 在重命名模式下，优先处理输入框的键盘事件
        if (isRenaming && renameBox != null) {
            // 先让输入框处理键盘事件
            boolean handled = renameBox.keyPressed(keyCode, scanCode, modifiers);
            if (handled) {
                return true;
            }
            
            // 回车键确认重命名
            if (keyCode == 257) { // 回车键
                confirmRename();
                return true;
            }
            
            // ESC键取消重命名
            if (keyCode == 256) { // ESC键
                hideContextMenu();
                return true;
            }
        }
        
        if (keyCode == 256) { // ESC键
            if (showContextMenu) {
                hideContextMenu();
                return true;
            }
            this.closeScreen();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 在重命名模式下，处理字符输入
        if (isRenaming && renameBox != null) {
            if (renameBox.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    // 自定义市民按钮类
    private static class CitizenButton extends Button {
        private final CitizenInfo citizen;
        private static final int HEAD_SIZE = 32;

        public CitizenButton(int x, int y, int width, int height,
                            CitizenInfo citizen, OnPress onPress) {
            super(x, y, width, height, nn(Component.literal(safeString(citizen.name()))), onPress, DEFAULT_NARRATION);
            this.citizen = citizen;
        }

        @Override
        public void renderWidget(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
            // 绘制按钮背景 - 70%透明度
            int color = this.isHovered ? 0xB3FFFFFF : 0xB3000000;
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, color);

            // 绘制边框
            guiGraphics.renderOutline(this.getX(), this.getY(), this.width, this.height, 0xFFFFFFFF);

            // 根据按钮高度调整文字大小和位置
            int textColor = 0xFFFFFF;
            int infoColor = 0xCCCCCC;

            // 计算头像位置（左侧）
            int headX = this.getX() + 5;
            int headY = this.getY() + (this.height - HEAD_SIZE) / 2;

            // 绘制NPC头像
            renderNpcHead(guiGraphics, headX, headY);

            // 文字起始X位置（头像右侧）
            int textStartX = this.getX() + HEAD_SIZE + 10;

            // 市民名称
            guiGraphics.drawString(
                nn(Minecraft.getInstance().font),
                nn(Component.literal(safeString(citizen.name()))),
                textStartX,
                this.getY() + 8,
                textColor
            );

            // 动态调整信息显示
            int availableHeight = this.height - 25;
            int lineCount = 3;
            int lineHeight = Math.max(8, Math.min(12, availableHeight / lineCount));

            int startY = this.getY() + 25;

            // 只在空间足够时显示完整信息
            if (this.height >= 50) {
                // NPC ID
                guiGraphics.drawString(
                    nn(Minecraft.getInstance().font),
                    nn(Component.translatable("gui.citizen.id", citizen.npcId())),
                    textStartX,
                    startY,
                    infoColor
                );

                // 职业 - 使用翻译键或直接显示
                String jobDisplay = getJobDisplayName(citizen.job());
                guiGraphics.drawString(
                    nn(Minecraft.getInstance().font),
                    nn(Component.translatable("gui.citizen.job", safeString(jobDisplay))),
                    textStartX,
                    startY + lineHeight,
                    infoColor
                );

                // 住宅状态
                String residenceStatus = citizen.hasResidence()
                    ? nn(Component.translatable("gui.citizen.has_residence")).getString()
                    : nn(Component.translatable("gui.citizen.no_residence")).getString();
                int statusColor = citizen.hasResidence() ? 0x55FF55 : 0xFF5555;
                guiGraphics.drawString(
                    nn(Minecraft.getInstance().font),
                    safeString(residenceStatus),
                    textStartX,
                    startY + lineHeight * 2,
                    statusColor
                );
            } else if (this.height >= 40) {
                // 高度较小时只显示关键信息
                guiGraphics.drawString(
                    nn(Minecraft.getInstance().font),
                    "#" + citizen.npcId(),
                    textStartX,
                    startY,
                    infoColor
                );

                String residenceStatus = citizen.hasResidence() ? "§a✓" : "§c✗";
                guiGraphics.drawString(
                    nn(Minecraft.getInstance().font),
                    nn(Component.literal(residenceStatus)),
                    this.getX() + this.width - 15,
                    startY,
                    infoColor
                );
            }

            // 绘制经验条和等级（在按钮底部）
            renderExpBarAndLevel(guiGraphics);
        }

        /**
         * 绘制经验条和等级 - 圆角风格
         */
        private void renderExpBarAndLevel(GuiGraphics guiGraphics) {
            int level = citizen.level();
            int xp = citizen.xp();

            // 计算下一级所需经验值
            int xpForNextLevel = getXpForNextLevel(level);
            int xpForCurrentLevel = getXpForCurrentLevel(level);

            // 经验条位置和尺寸
            int barX = this.getX() + 8;
            int barY = this.getY() + this.height - 20;
            int barWidth = this.width - 16; // 使用完整宽度
            int barHeight = 12;
            int cornerRadius = 3;

            // 检查是否已满级（达到最大等级且经验达到阈值）
            int maxLevel = com.xiaoliang.simukraft.config.ServerConfig.getNpcMaxLevel();
            boolean isMaxLevel = xpForNextLevel < 0 || level >= maxLevel;

            // 计算经验条进度
            float progress;
            String expText;
            if (isMaxLevel) {
                // 满级状态：经验条全满，显示 MAX
                progress = 1.0f;
                expText = "MAX";
            } else {
                int xpInCurrentLevel = xp - xpForCurrentLevel;
                int xpNeeded = xpForNextLevel - xpForCurrentLevel;
                progress = xpNeeded > 0 ? (float) xpInCurrentLevel / xpNeeded : 1.0f;
                expText = xpInCurrentLevel + "/" + xpNeeded;
            }
            int filledWidth = (int) ((barWidth - 2) * progress);

            // 绘制圆角背景（深灰色）
            int bgColor = 0xFF3D3D3D;
            renderRoundedRect(guiGraphics, barX, barY, barWidth, barHeight, cornerRadius, bgColor);

            // 绘制圆角填充（灰色）- 满级时使用金色
            if (filledWidth > 0) {
                int fillColor = isMaxLevel ? 0xFFFFD700 : 0xFF808080; // 满级金色，普通灰色
                renderRoundedRect(guiGraphics, barX + 1, barY + 1, filledWidth, barHeight - 2, cornerRadius - 1, fillColor);
            }

            // 绘制经验值文字（居中）- 白色文字+黑色阴影
            int textX = barX + (barWidth - nn(Minecraft.getInstance().font).width(expText)) / 2;
            int textY = barY + 2;
            // 黑色阴影 + 白色文字
            guiGraphics.drawString(nn(Minecraft.getInstance().font), expText, textX + 1, textY + 1, 0xFF000000);
            guiGraphics.drawString(nn(Minecraft.getInstance().font), expText, textX, textY, 0xFFFFFFFF);

            // menglan: 删除等级图标，只保留等级文字
            // 绘制等级文字（右侧）
            String levelText = "Lv " + level;
            int levelTextX = this.getX() + this.width - 8 - nn(Minecraft.getInstance().font).width(levelText);
            int levelTextY = barY - 10;
            guiGraphics.drawString(nn(Minecraft.getInstance().font), levelText, levelTextX + 1, levelTextY + 1, 0xFF000000);
            guiGraphics.drawString(nn(Minecraft.getInstance().font), levelText, levelTextX, levelTextY, 0xFFFFFFFF);
        }

        /**
         * 绘制圆角矩形
         */
        private void renderRoundedRect(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius, int color) {
            // 主体矩形
            guiGraphics.fill(x + radius, y, x + width - radius, y + height, color);
            // 左侧矩形
            guiGraphics.fill(x, y + radius, x + radius, y + height - radius, color);
            // 右侧矩形
            guiGraphics.fill(x + width - radius, y + radius, x + width, y + height - radius, color);
            // 四个角（使用小矩形模拟圆角）
            // 左上角
            guiGraphics.fill(x + radius - 1, y, x + radius, y + 1, color);
            guiGraphics.fill(x, y + radius - 1, x + 1, y + radius, color);
            // 右上角
            guiGraphics.fill(x + width - radius, y, x + width - radius + 1, y + 1, color);
            guiGraphics.fill(x + width - 1, y + radius - 1, x + width, y + radius, color);
            // 左下角
            guiGraphics.fill(x + radius - 1, y + height - 1, x + radius, y + height, color);
            guiGraphics.fill(x, y + height - radius, x + 1, y + height - radius + 1, color);
            // 右下角
            guiGraphics.fill(x + width - radius, y + height - 1, x + width - radius + 1, y + height, color);
            guiGraphics.fill(x + width - 1, y + height - radius, x + width, y + height - radius + 1, color);
        }

        /**
         * 获取当前等级所需经验值（累计值）
         * 使用NPCDataManager.LEVEL_THRESHOLDS
         */
        private int getXpForCurrentLevel(int level) {
            if (level <= 1) return 0;
            int maxLevel = com.xiaoliang.simukraft.config.ServerConfig.getNpcMaxLevel();
            if (level > maxLevel) return com.xiaoliang.simukraft.utils.NPCDataManager.getXpToNextLevel(maxLevel - 1);
            // LEVEL_THRESHOLDS[i] 表示升到 (i+2) 级所需的总经验值
            // 所以当前等级所需经验值是 LEVEL_THRESHOLDS[level - 2]
            return com.xiaoliang.simukraft.utils.NPCDataManager.getXpToNextLevel(level - 1);
        }

        /**
         * 获取下一级所需经验值（累计值）
         * 满级返回-1表示已满级
         */
        private int getXpForNextLevel(int level) {
            int maxLevel = com.xiaoliang.simukraft.config.ServerConfig.getNpcMaxLevel();
            if (level >= maxLevel) return -1; // 已满级
            return com.xiaoliang.simukraft.utils.NPCDataManager.getXpToNextLevel(level);
        }

        /**
         * 渲染NPC头像
         */
        private void renderNpcHead(GuiGraphics guiGraphics, int x, int y) {
            String skinPath = citizen.skinPath();
            if (skinPath == null || skinPath.isEmpty() || !SkinManager.isValidSkinPath(skinPath)) {
                // 如果没有皮肤路径或路径无效，绘制默认头像背景
                guiGraphics.fill(x, y, x + HEAD_SIZE, y + HEAD_SIZE, 0xFF666666);
                guiGraphics.renderOutline(x, y, HEAD_SIZE, HEAD_SIZE, 0xFFFFFFFF);
                return;
            }

            try {
                // 使用SkinManager构建纹理资源位置
                ResourceLocation texture = nn(SkinManager.getTextureResourceLocation(skinPath));

                // 绑定并绘制纹理
                RenderSystem.setShaderTexture(0, texture);
                RenderSystem.enableBlend();
                guiGraphics.blit(texture, x, y, HEAD_SIZE, HEAD_SIZE, 8, 8, 8, 8, 64, 64);
                guiGraphics.blit(texture, x, y, HEAD_SIZE, HEAD_SIZE, 40, 8, 8, 8, 64, 64);
                RenderSystem.disableBlend();
                // 绘制头像边框
                guiGraphics.renderOutline(x, y, HEAD_SIZE, HEAD_SIZE, 0xFFFFFFFF);
            } catch (Exception e) {
                // 渲染失败时绘制默认背景
                guiGraphics.fill(x, y, x + HEAD_SIZE, y + HEAD_SIZE, 0xFF666666);
                guiGraphics.renderOutline(x, y, HEAD_SIZE, HEAD_SIZE, 0xFFFFFFFF);
            }
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
