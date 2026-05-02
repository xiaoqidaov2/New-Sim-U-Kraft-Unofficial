package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.MaterialRequirementsRequestPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MaterialRequirementsScreen extends AbstractTransitionScreen {
    private final BlockPos cityCorePos;
    private List<TaskMaterialInfo> tasks = new ArrayList<>();
    private int currentPage = 0;
    private static final int TASKS_PER_PAGE = 2; // 每页显示2个任务

    // 自动刷新相关
    private int refreshTimer = 0;
    private static final int REFRESH_INTERVAL = 40; // 2秒

    public MaterialRequirementsScreen(BlockPos cityCorePos) {
        super(Component.translatable("gui.material_requirements.title"));
        this.cityCorePos = cityCorePos;
        Minecraft.getInstance().getSoundManager()
                .play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));
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

        int centerX = this.width / 2;

        // 创建返回按钮
        this.addRenderableWidget(nn(Button.builder(
            nn(Component.translatable("gui.back")),
            button -> this.closeScreen()
        ).pos(centerX - 50, this.height - 30).size(100, 20).build()));

        // 创建上一页按钮
        this.addRenderableWidget(nn(Button.builder(
            nn(Component.literal("<")),
            button -> prevPage()
        ).pos(centerX - 100, this.height - 30).size(30, 20).build()));

        // 创建下一页按钮
        this.addRenderableWidget(nn(Button.builder(
            nn(Component.literal(">")),
            button -> nextPage()
        ).pos(centerX + 70, this.height - 30).size(30, 20).build()));

        // 请求材料数据
        requestMaterialsData();
    }

    private void requestMaterialsData() {
        NetworkManager.INSTANCE.sendToServer(new MaterialRequirementsRequestPacket(cityCorePos));
    }

    public void setTasks(List<TaskMaterialInfo> tasks) {
        this.tasks = tasks;
        int maxPage = Math.max(0, (tasks.size() - 1) / TASKS_PER_PAGE);
        if (currentPage > maxPage) {
            currentPage = maxPage;
        }
    }

    private void prevPage() {
        if (currentPage > 0) {
            currentPage--;
        }
    }

    private void nextPage() {
        int maxPage = (tasks.size() - 1) / TASKS_PER_PAGE;
        if (currentPage < maxPage) {
            currentPage++;
        }
    }

    @Override
    public void tick() {
        super.tick();
        refreshTimer++;
        if (refreshTimer >= REFRESH_INTERVAL) {
            refreshTimer = 0;
            requestMaterialsData();
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        int centerX = this.width / 2;
        int startY = 40;

        // 渲染标题
        Component title = Component.translatable("gui.material_requirements.title");
        int titleWidth = nn(this.font).width(nn(title));
        guiGraphics.drawString(nn(this.font), nn(title), centerX - titleWidth / 2, 15, 0xFFFFFF);

        // 渲染任务列表
        if (tasks.isEmpty()) {
            Component emptyText = Component.translatable("gui.material_requirements.empty");
            int emptyWidth = nn(this.font).width(nn(emptyText));
            guiGraphics.drawString(nn(this.font), nn(emptyText), centerX - emptyWidth / 2, startY + 50, 0x888888);
        } else {
            int startIndex = currentPage * TASKS_PER_PAGE;
            int endIndex = Math.min(startIndex + TASKS_PER_PAGE, tasks.size());

            for (int i = startIndex; i < endIndex; i++) {
                TaskMaterialInfo task = tasks.get(i);
                int taskY = startY + (i - startIndex) * 180; // 每个任务占用180像素高度
                renderTask(guiGraphics, centerX, taskY, task);
            }

            // 渲染页码
            int maxPage = (tasks.size() - 1) / TASKS_PER_PAGE + 1;
            Component pageText = Component.literal(safeString((currentPage + 1) + " / " + maxPage));
            int pageWidth = nn(this.font).width(nn(pageText));
            guiGraphics.drawString(nn(this.font), nn(pageText), centerX - pageWidth / 2, this.height - 25, 0xAAAAAA);
        }
    }

    private void renderTask(GuiGraphics guiGraphics, int centerX, int y, TaskMaterialInfo task) {
        int panelWidth = 360;
        int panelX = centerX - panelWidth / 2;

        // 渲染任务标题背景（半透明灰色）
        guiGraphics.fill(panelX, y, panelX + panelWidth, y + 20, 0xDD333333);

        // 渲染建筑名称和建造者
        Component taskTitle = Component.translatable("gui.material_requirements.task_title", task.getTaskName(), task.getBuilderName());
        guiGraphics.drawString(nn(this.font), nn(taskTitle), panelX + 5, y + 6, 0xFFFFFF);

        // 计算材料列表高度（显示所有材料）
        List<MaterialInfo> materials = task.getMaterials();
        int materialsPerRow = 2; // 每行2个材料
        int materialRows = (materials.size() + materialsPerRow - 1) / materialsPerRow; // 向上取整
        int materialsHeight = materialRows * 22 + 10; // 每个材料22像素高，加上边距

        // 渲染材料列表背景
        guiGraphics.fill(panelX, y + 20, panelX + panelWidth, y + 20 + materialsHeight, 0xAA222222);

        // 渲染材料列表（每行2个，显示所有材料）
        for (int i = 0; i < materials.size(); i++) {
            MaterialInfo material = materials.get(i);
            int col = i % materialsPerRow;
            int row = i / materialsPerRow;
            int matX = panelX + 10 + col * 170;
            int matY = y + 25 + row * 22;
            renderMaterial(guiGraphics, matX, matY, material);
        }
    }

    private void renderMaterial(GuiGraphics guiGraphics, int x, int y, MaterialInfo material) {
        // 渲染物品图标
        guiGraphics.renderItem(nn(material.getItemStack()), x, y);

        // 渲染物品名称（displayName已经在客户端处理时通过BlockNameTranslator映射为中文，截断以适应宽度）
        String name = material.getDisplayName();
        if (name.length() > 10) {
            name = name.substring(0, 9) + "...";
        }
        Component nameText = Component.literal(safeString(name));
        guiGraphics.drawString(nn(this.font), nn(nameText), x + 20, y + 4, 0xFFFFFF);

        // 渲染数量
        Component countText = Component.literal(safeString("§ex" + material.getCount()));
        guiGraphics.drawString(nn(this.font), nn(countText), x + 110, y + 4, 0xFFFFFF);
    }

    private void closeScreen() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 任务材料信息类
     */
    public static class TaskMaterialInfo {
        private final String taskName;
        private final String builderName;
        private final List<MaterialInfo> materials;

        public TaskMaterialInfo(String taskName, String builderName, List<MaterialInfo> materials) {
            this.taskName = taskName;
            this.builderName = builderName;
            this.materials = materials;
        }

        public String getTaskName() { return taskName; }
        public String getBuilderName() { return builderName; }
        public List<MaterialInfo> getMaterials() { return materials; }
    }

    /**
     * 材料信息类
     */
    public static class MaterialInfo {
        private final ItemStack itemStack;
        private final String displayName;
        private final int count;

        public MaterialInfo(ItemStack itemStack, String displayName, int count) {
            this.itemStack = itemStack;
            this.displayName = displayName;
            this.count = count;
        }

        public ItemStack getItemStack() { return itemStack; }
        public String getDisplayName() { return displayName; }
        public int getCount() { return count; }
    }
}
