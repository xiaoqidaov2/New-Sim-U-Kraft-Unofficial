package com.xiaoliang.simukraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * 方块替换/填充箱子选择界面
 * 自动搜索建筑盒六个面紧贴的所有箱子，并显示列表供玩家选择
 */
@SuppressWarnings("null")
public class BlockReplacementChestSelectScreen extends Screen {
    public enum Mode {
        REPLACE,    // 替换模式
        FILL        // 填充模式
    }

    private final BlockPos selectionStart;
    private final BlockPos selectionEnd;
    private final BlockPos buildBoxPos;
    private final Screen parent;
    private final Mode mode;

    // 建筑盒六个面紧贴的箱子列表
    private List<ContainerInfo> chestList = new ArrayList<>();
    private int selectedIndex = -1;

    // 滚动偏移
    private int scrollOffset = 0;
    private static final int ITEMS_PER_PAGE = 5;
    private static final int ITEM_HEIGHT = 30;

    // 按钮
    private Button confirmButton;
    private Button upButton;
    private Button downButton;

    public BlockReplacementChestSelectScreen(BlockPos selectionStart, BlockPos selectionEnd, 
                                             BlockPos buildBoxPos, Screen parent) {
        this(selectionStart, selectionEnd, buildBoxPos, parent, Mode.REPLACE);
    }

    public BlockReplacementChestSelectScreen(BlockPos selectionStart, BlockPos selectionEnd, 
                                             BlockPos buildBoxPos, Screen parent, Mode mode) {
        super(Component.translatable("选择箱子"));
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.buildBoxPos = buildBoxPos;
        this.parent = parent;
        this.mode = mode;
    }

    @Override
    protected void init() {
        super.init();

        // 搜索建筑盒六个面紧贴的箱子
        scanChestsAroundBuildBox();

        int centerX = this.width / 2;

        // 确认按钮
        confirmButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.block_replacement_chest.confirm"),
                        button -> confirmSelection())
                .bounds(centerX - 100, this.height - 40, 80, 20)
                .build());

        // 取消按钮
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.button.cancel"),
                        button -> this.onClose())
                .bounds(centerX + 20, this.height - 40, 80, 20)
                .build());

        // 上翻按钮
        upButton = this.addRenderableWidget(Button.builder(
                        Component.literal("↑"),
                        button -> scroll(-1))
                .bounds(this.width - 50, 100, 30, 20)
                .build());

        // 下翻按钮
        downButton = this.addRenderableWidget(Button.builder(
                        Component.literal("↓"),
                        button -> scroll(1))
                .bounds(this.width - 50, this.height - 80, 30, 20)
                .build());

        updateButtonStates();
    }

    /**
     * 扫描建筑盒六个面紧贴的所有箱子
     */
    private void scanChestsAroundBuildBox() {
        chestList.clear();
        if (minecraft == null || minecraft.level == null || buildBoxPos == null) {
            return;
        }

        // 只搜索六个面紧贴的容器
        Set<BlockPos> processedChests = new HashSet<>();
        Direction[] directions = new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

        for (Direction dir : directions) {
            BlockPos pos = buildBoxPos.relative(dir);
            BlockState state = minecraft.level.getBlockState(pos);
            BlockEntity blockEntity = minecraft.level.getBlockEntity(pos);

            // 如果是箱子，检查是否已经处理过（大箱子的另一半）
            if (state.getBlock() instanceof ChestBlock) {
                if (processedChests.contains(pos)) {
                    continue;
                }

                BlockPos otherHalf = getOtherChestHalf(state, pos);
                if (otherHalf != null) {
                    processedChests.add(otherHalf);
                }
                processedChests.add(pos);

                chestList.add(new ContainerInfo(pos, null, "大箱子"));
            }
            // 其他容器类型（木桶等）
            else if (blockEntity instanceof Container container) {
                String typeName = blockEntity.getClass().getSimpleName();
                chestList.add(new ContainerInfo(pos, container, typeName));
            }
        }
    }
    
    /**
     * 获取大箱子的另一半位置
     */
    private BlockPos getOtherChestHalf(BlockState state, BlockPos pos) {
        if (!(state.getBlock() instanceof ChestBlock)) {
            return null;
        }
        
        // 检查四个方向是否有另一个箱子
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = minecraft.level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof ChestBlock) {
                // 检查是否是同类型的箱子（普通箱子或陷阱箱）
                if (state.getBlock() == neighborState.getBlock()) {
                    return neighborPos;
                }
            }
        }
        return null;
    }

    /**
     * 确认选择
     */
    private void confirmSelection() {
        if (selectedIndex >= 0 && selectedIndex < chestList.size()) {
            ContainerInfo containerInfo = chestList.get(selectedIndex);
            // 根据模式打开不同的界面
            if (mode == Mode.FILL) {
                // 打开方块填充界面
                Minecraft.getInstance().setScreen(new BlockFillScreen(
                        selectionStart, selectionEnd, containerInfo.pos, buildBoxPos));
            } else {
                // 打开方块替换主界面，传递建筑盒位置
                Minecraft.getInstance().setScreen(new BlockReplacementScreen(
                        selectionStart, selectionEnd, containerInfo.pos, buildBoxPos));
            }
        }
    }

    /**
     * 滚动列表
     */
    private void scroll(int direction) {
        int maxScroll = Math.max(0, chestList.size() - ITEMS_PER_PAGE);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + direction));
    }

    @Override
    public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 渲染半透明背景
        this.renderBackground(graphics);

        int centerX = this.width / 2;

        // 标题
        String titleKey = (mode == Mode.FILL) ? "gui.block_replacement_chest.title.fill" : "gui.block_replacement_chest.title.replace";
        graphics.drawCenteredString(this.font,
                Component.translatable(titleKey),
                centerX, 20, 0xFFFFFF);

        // 建筑盒位置信息
        if (buildBoxPos != null) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.block_replacement_chest.build_box_location",
                        buildBoxPos.getX(), buildBoxPos.getY(), buildBoxPos.getZ()),
                    centerX, 45, 0x55FF55);
        }

        // 选区信息
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.block_replacement_chest.selection",
                    selectionStart.getX(), selectionStart.getY(), selectionStart.getZ(),
                    selectionEnd.getX(), selectionEnd.getY(), selectionEnd.getZ()),
                centerX, 60, 0xAAAAAA);

        // 渲染箱子列表
        if (chestList.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("message.block_replacement_chest.no_chest"),
                    centerX, this.height / 2, 0xFF5555);
            graphics.drawCenteredString(this.font,
                    Component.translatable("message.block_replacement_chest.place_chest_hint"),
                    centerX, this.height / 2 + 20, 0xAAAAAA);
        } else {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.block_replacement_chest.found_chests", chestList.size()),
                    centerX, 85, 0xFFFFFF);
            renderChestList(graphics, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 渲染箱子列表
     */
    private void renderChestList(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.width / 2 - 100;
        int y = 105;
        int index = 0;

        for (int i = scrollOffset; i < Math.min(chestList.size(), scrollOffset + ITEMS_PER_PAGE); i++) {
            ContainerInfo containerInfo = chestList.get(i);

            // 渲染背景
            int bgColor = (index == selectedIndex) ? 0xFF555555 : 0xFF333333;
            graphics.fill(x, y, x + 200, y + ITEM_HEIGHT - 2, bgColor);

            // 渲染箱子图标
            ItemStack chestStack = new ItemStack(Items.CHEST);
            graphics.renderItem(chestStack, x + 5, y + 5);

            // 渲染箱子位置信息（包含类型名称）
            String posText = String.format("%s #%d: (%d, %d, %d)",
                    containerInfo.typeName, i + 1, containerInfo.pos.getX(), containerInfo.pos.getY(), containerInfo.pos.getZ());
            graphics.drawString(this.font, posText, x + 30, y + 10, 0xFFFFFF);

            // 检查鼠标悬停
            if (mouseX >= x && mouseX <= x + 200 && mouseY >= y && mouseY <= y + ITEM_HEIGHT - 2) {
                graphics.renderTooltip(this.font,
                        Component.translatable("gui.block_replacement_chest.click_to_select"), mouseX, mouseY);
            }

            y += ITEM_HEIGHT;
            index++;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !chestList.isEmpty()) { // 左键点击
            int x = this.width / 2 - 100;
            int y = 105;

            if (mouseX >= x && mouseX <= x + 200) {
                int relativeY = (int) (mouseY - y);
                if (relativeY >= 0) {
                    int index = relativeY / ITEM_HEIGHT;
                    if (index >= 0 && index < ITEMS_PER_PAGE) {
                        int actualIndex = scrollOffset + index;
                        if (actualIndex < chestList.size()) {
                            selectedIndex = actualIndex;
                            updateButtonStates();
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int x = this.width / 2 - 100;
        int y = 105;
        if (mouseX >= x && mouseX <= x + 200 &&
            mouseY >= y && mouseY <= y + ITEMS_PER_PAGE * ITEM_HEIGHT) {
            scroll(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void updateButtonStates() {
        confirmButton.active = selectedIndex >= 0 && selectedIndex < chestList.size();

        int maxScroll = Math.max(0, chestList.size() - ITEMS_PER_PAGE);
        upButton.active = scrollOffset > 0;
        downButton.active = scrollOffset < maxScroll;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 容器信息类
     */
    private static class ContainerInfo {
        final BlockPos pos;
        final String typeName;

        ContainerInfo(BlockPos pos, Container container, String typeName) {
            this.pos = pos;
            this.typeName = typeName;
        }
    }
}
