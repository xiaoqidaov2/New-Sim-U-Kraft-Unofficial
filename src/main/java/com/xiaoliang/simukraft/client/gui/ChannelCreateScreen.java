package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.network.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import java.util.*;

/**
 * 频道创建界面：选客户端 → 选方向 → 选物品 → 命名 → 确认。
 */
@SuppressWarnings("null")
public class ChannelCreateScreen extends AbstractTransitionScreen
        implements LogisticsNetworkResponsePacket.LogisticsNetworkScreenReceiver,
                   ChannelItemsResponsePacket.ChannelCreateScreenReceiver {

    private final BlockPos warehouseBlockPos;
    private final UUID warehouseId;

    // 可选的客户端列表（从服务端同步）
    private List<ClientEntry> clients = new ArrayList<>();
    private int selectedClientIndex = -1;

    // 方向
    private boolean isSend = true; // true=SEND(仓库→客户端), false=RECEIVE

    // 仓库物品列表（供选择过滤）
    private List<ItemEntry> warehouseItems = new ArrayList<>();
    private Set<Integer> selectedItemIndices = new LinkedHashSet<>();

    // 频道名
    private EditBox nameBox;

    // 客户端重命名
    private EditBox clientRenameBox;
    private Button clientRenameButton;
    private boolean isRenamingClient = false;

    // 物品滚动
    private int itemScrollOffset = 0;
    private static final int ITEM_COLS = 9;
    private static final int ITEM_SLOT = 18;

    public ChannelCreateScreen(BlockPos warehouseBlockPos, UUID warehouseId) {
        super(Component.translatable("gui.channel_create.title"));
        this.warehouseBlockPos = warehouseBlockPos;
        this.warehouseId = warehouseId;
    }

    @Override
    protected void init() {
        super.init();
        var font = nn(this.font);

        // 请求服务端发送物流网络数据（客户端列表 + 频道列表）
        NetworkManager.INSTANCE.sendToServer(
                new RequestLogisticsNetworkPacket(warehouseBlockPos, warehouseId)
        );

        int left = 20;
        int y = 35;

        // ── 1. 选择客户端 ──
        Button previousClientButton = nn(Button.builder(nn(Component.literal("◀")), btn -> {
            if (selectedClientIndex > 0) {
                selectedClientIndex--;
                updateClientRenameBox();
                if (!isSend) {
                    refreshItemList();
                }
            }
        }).bounds(left + 80, y, 20, 20).build());
        addRenderableWidget(previousClientButton);

        Button nextClientButton = nn(Button.builder(nn(Component.literal("▶")), btn -> {
            if (selectedClientIndex < clients.size() - 1) {
                selectedClientIndex++;
                updateClientRenameBox();
                if (!isSend) {
                    refreshItemList();
                }
            }
        }).bounds(left + 200, y, 20, 20).build());
        addRenderableWidget(nextClientButton);

        // 客户端重命名按钮
        clientRenameButton = nn(Button.builder(nn(Component.literal("✎")), btn -> toggleClientRename())
                .bounds(left + 225, y, 20, 20).build());
        addRenderableWidget(clientRenameButton);

        // ── 2. 方向切换 ──
        y += 33;
        Button directionButton = nn(Button.builder(
                nn(Component.translatable("gui.channel_create.direction", nn(Component.translatable(isSend ? "gui.channel_create.direction.send" : "gui.channel_create.direction.receive")))),
                btn -> {
                    isSend = !isSend;
                    btn.setMessage(nn(Component.translatable("gui.channel_create.direction", nn(Component.translatable(isSend ? "gui.channel_create.direction.send" : "gui.channel_create.direction.receive")))));
                    refreshItemList();
                }).bounds(left + 80, y, 140, 20).build());
        addRenderableWidget(directionButton);

        // ── 3. 频道名称 ──
        y += 28;
        EditBox nameBoxTmp = new EditBox(font, left + 80, y, 140, 18, nn(Component.translatable("gui.channel_create.name_hint")));
        nameBox = nn(nameBoxTmp);
        nameBox.setMaxLength(32);
        nameBox.setValue(safeString(nn(Component.translatable("gui.channel_create.default_name")).getString()));
        addRenderableWidget(nn(nameBox));

        // ── 4. 客户端重命名编辑框（初始隐藏） ──
        y += 28;
        EditBox clientRenameBoxTmp = new EditBox(font, left + 80, y, 140, 18, nn(Component.literal("客户端名称")));
        clientRenameBox = nn(clientRenameBoxTmp);
        clientRenameBox.setMaxLength(32);
        clientRenameBox.setVisible(false);
        clientRenameBox.setResponder(this::onClientRenameChanged);
        addRenderableWidget(nn(clientRenameBox));

        // ── 5. 确认/取消 ──
        int bottomY = this.height - 30;
        Button confirmButton = nn(Button.builder(nn(Component.translatable("gui.channel_create.confirm")), btn -> onConfirm())
                .bounds(this.width / 2 - 110, bottomY, 100, 20).build());
        addRenderableWidget(confirmButton);
        Button cancelButton = nn(Button.builder(nn(Component.translatable("gui.channel_create.cancel")), btn -> this.onClose())
                .bounds(this.width / 2 + 10, bottomY, 100, 20).build());
        addRenderableWidget(cancelButton);
    }

    private void toggleClientRename() {
        isRenamingClient = !isRenamingClient;
        clientRenameBox.setVisible(isRenamingClient);
        if (isRenamingClient) {
            updateClientRenameBox();
            clientRenameBox.setFocused(true);
        } else {
            updateClientRenameBox();
        }
    }

    private void updateClientRenameBox() {
        if (selectedClientIndex >= 0 && selectedClientIndex < clients.size()) {
            ClientEntry client = clients.get(selectedClientIndex);
            String currentName = client.hasCustomName() ? safeString(client.name) : "";
            nn(clientRenameBox).setValue(currentName);
        }
    }

    private void onClientRenameChanged(String newName) {
        if (selectedClientIndex >= 0 && selectedClientIndex < clients.size()) {
            ClientEntry client = clients.get(selectedClientIndex);
            String cleanedName = nn(newName).trim();
            if (cleanedName.length() > 32) {
                cleanedName = cleanedName.substring(0, 32);
            }
            client.name = cleanedName;
            NetworkManager.INSTANCE.sendToServer(new LogisticsClientRenamePacket(client.clientId, cleanedName));
        }
    }

    /**
     * 根据当前传输方向刷新物品列表 — 通过网络包请求服务端数据
     */
    private void refreshItemList() {
        selectedItemIndices.clear();
        NetworkManager.INSTANCE.sendToServer(
                new RequestChannelItemsPacket(
                        warehouseBlockPos,
                        isSend,
                        selectedClientIndex >= 0 && selectedClientIndex < clients.size()
                                ? clients.get(selectedClientIndex).clientId
                                : null
                )
        );
    }

    /**
     * 接收服务器返回的物品数据
     */
    @Override
    public void onChannelItemsReceived(List<ItemEntry> items) {
        this.warehouseItems = nn(items);
        this.itemScrollOffset = 0;
    }

    /**
     * 接收服务器返回的物流网络数据
     */
    @Override
    public void onLogisticsNetworkDataReceived(LogisticsNetworkResponsePacket packet) {
        if (!nn(packet).getWarehouseId().equals(warehouseId)) {
            return;
        }

        // 更新客户端列表
        this.clients = new ArrayList<>();
        for (LogisticsNetworkResponsePacket.ClientData clientData : packet.getClients()) {
            ClientEntry client = new ClientEntry(clientData.clientId, clientData.blockPos);
            client.portPositions.addAll(clientData.portPositions);

            // 使用服务端传来的名称（优先），如果没有则使用本地缓存
            if (clientData.name != null && !clientData.name.isEmpty()) {
                client.name = clientData.name;
            } else {
                // 同步名称缓存
                String cachedName = LogisticsClientData.getClientName(clientData.clientId);
                if (!cachedName.isEmpty()) {
                    client.name = cachedName;
                }
            }

            this.clients.add(client);
        }

        // 重置选择
        if (!clients.isEmpty() && selectedClientIndex < 0) {
            selectedClientIndex = 0;
        }

        // 刷新物品列表
        refreshItemList();
    }

    @Override
    protected void drawBackground(@Nonnull GuiGraphics guiGraphics) {
        GuiGraphics safeGuiGraphics = nn(guiGraphics);
        int a = (int) (getAlpha() * 220) & 0xFF;
        safeGuiGraphics.fill(0, 0, this.width, this.height, (a << 24) | 0x0D0D1A);
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics safeGuiGraphics = nn(guiGraphics);
        var font = nn(this.font);
        super.render(safeGuiGraphics, mouseX, mouseY, partialTicks);

        int a = (int) (getAlpha() * 255) & 0xFF;
        int white = (a << 24) | 0xFFFFFF;
        int gray = (a << 24) | 0xAAAAAA;
        int left = 20;
        int y = 40;

        // 标题
        safeGuiGraphics.drawCenteredString(font, nn(this.title), this.width / 2, 10, white);

        // 费用提示
        safeGuiGraphics.drawCenteredString(font, nn(Component.translatable("gui.channel_create.cost_hint")), this.width / 2, 25, 0xFFD700);

        // 1. 客户端选择
        safeGuiGraphics.drawString(font, "目标客户端:", left, y, gray);
        if (clients.isEmpty()) {
            safeGuiGraphics.drawString(font, "§c没有可用的客户端", left + 105, y, 0xFFFF5555);
        } else if (selectedClientIndex >= 0 && selectedClientIndex < clients.size()) {
            ClientEntry client = clients.get(selectedClientIndex);
            String displayName = client.getDisplayName();
            String info = "§6" + displayName + " §7(" + client.portPositions.size() + "个端口)";
            safeGuiGraphics.drawString(font, info, left + 105, y, white);

            // 显示距离和费用信息
            y += 12;
            double distance = calculateDistance(client.blockPos);
            double costPerStack = calculateTransferCost(distance);
            String distanceText = String.format("§7距离: §f%.0f格 §7| 费用: §f¥%.2f/组", distance, costPerStack);
            safeGuiGraphics.drawString(font, distanceText, left + 105, y, 0xAAAAAA);
            y += 22;
        } else {
            y += 28;
        }

        safeGuiGraphics.drawString(font, "传输方向:", left, y + 5, gray);

        y += 28;
        safeGuiGraphics.drawString(font, "频道名称:", left, y + 5, gray);

        // 显示客户端重命名标签
        if (isRenamingClient) {
            y += 28;
            safeGuiGraphics.drawString(font, "客户端名称:", left, y + 5, gray);
        }

        // 5. 物品选择区
        int gridStartY = getItemGridStartY();
        String itemSelectLabel = isSend
                ? "选择要从仓库发送的物品（点击选中/取消）:"
                : "选择要从客户端接收的物品（点击选中/取消）:";
        safeGuiGraphics.drawString(font, itemSelectLabel, left, gridStartY - 14, gray);

        renderItemGrid(safeGuiGraphics, left, gridStartY, mouseX, mouseY, a);
    }

    private int getItemGridStartY() {
        int y = 40;
        if (clients.isEmpty()) {
            y += 28;
        } else if (selectedClientIndex >= 0 && selectedClientIndex < clients.size()) {
            y += 34;
        } else {
            y += 28;
        }
        y += 28;
        y += 28;
        if (isRenamingClient) {
            y += 28;
        }
        return y + 44;
    }

    private void renderItemGrid(GuiGraphics guiGraphics, int startX, int startY, int mouseX, int mouseY, int alpha) {
        int visibleRows = 4;

        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < ITEM_COLS; col++) {
                int idx = (itemScrollOffset + row) * ITEM_COLS + col;
                int x = startX + col * ITEM_SLOT;
                int y = startY + row * ITEM_SLOT;

                // 槽位背景
                guiGraphics.fill(x, y, x + 16, y + 16, (alpha << 24) | 0x2A2A4A);

                if (idx < warehouseItems.size()) {
                    ItemEntry entry = warehouseItems.get(idx);
                    guiGraphics.renderItem(nn(entry.stack), x + 1, y + 1);

                    // 选中高亮（橙色边框）
                    if (selectedItemIndices.contains(idx)) {
                        guiGraphics.fill(x - 1, y - 1, x + 17, y, 0xFFFF8800);
                        guiGraphics.fill(x - 1, y + 16, x + 17, y + 17, 0xFFFF8800);
                        guiGraphics.fill(x - 1, y, x, y + 16, 0xFFFF8800);
                        guiGraphics.fill(x + 16, y, x + 17, y + 16, 0xFFFF8800);
                    }

                    // 悬停
                    if (mouseX >= x && mouseX < x + ITEM_SLOT && mouseY >= y && mouseY < y + ITEM_SLOT) {
                        guiGraphics.fill(x, y, x + 16, y + 16, 0x40FFFFFF);
                    }
                }
            }
        }

        // 选中物品计数
        guiGraphics.drawString(nn(this.font), "§e已选: " + selectedItemIndices.size() + " 种",
                startX, startY + visibleRows * ITEM_SLOT + 4, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int startX = 20;
            int startY = getItemGridStartY();
            int visibleRows = 4;

            for (int row = 0; row < visibleRows; row++) {
                for (int col = 0; col < ITEM_COLS; col++) {
                    int idx = (itemScrollOffset + row) * ITEM_COLS + col;
                    int x = startX + col * ITEM_SLOT;
                    int y = startY + row * ITEM_SLOT;

                    if (mouseX >= x && mouseX < x + ITEM_SLOT && mouseY >= y && mouseY < y + ITEM_SLOT) {
                        if (idx < warehouseItems.size()) {
                            if (selectedItemIndices.contains(idx)) {
                                selectedItemIndices.remove(idx);
                            } else {
                                selectedItemIndices.add(idx);
                            }
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
        int maxRows = (warehouseItems.size() + ITEM_COLS - 1) / ITEM_COLS;
        int maxScroll = Math.max(0, maxRows - 4);
        itemScrollOffset = Math.max(0, Math.min(maxScroll, itemScrollOffset - (int) delta));
        return true;
    }

    private void onConfirm() {
        if (selectedClientIndex < 0 || selectedClientIndex >= clients.size()) return;
        if (selectedItemIndices.isEmpty()) return;

        ClientEntry target = clients.get(selectedClientIndex);
        String name = nn(nameBox).getValue().isBlank() ? safeString(nn(Component.translatable("gui.channel_create.default_name")).getString()) : safeString(nameBox.getValue());
        String dir = isSend ? "SEND" : "RECEIVE";

        // 收集选中的物品（包含NBT）
        List<ItemStack> selectedItems = new ArrayList<>();
        for (int index : selectedItemIndices) {
            if (index >= 0 && index < warehouseItems.size()) {
                selectedItems.add(nn(warehouseItems.get(index).stack).copy());
            }
        }

        NetworkManager.INSTANCE.sendToServer(LogisticsChannelPacket.create(
                warehouseBlockPos, target.clientId, name, dir, selectedItems));

        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    nn(Component.translatable("gui.channel_create.success", name)), false);
        }

        // 返回地图界面
        if (this.minecraft != null) {
            this.minecraft.setScreen(new LogisticsNetworkScreen(warehouseBlockPos, warehouseId));
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── 距离和费用计算 ──

    private static final double BASE_COST_PER_GROUP = 0.02;
    private static final int BASE_DISTANCE = 256;
    private static final int ADDITIONAL_DISTANCE_STEP = 64;
    private static final double ADDITIONAL_COST_PER_STEP = 0.01;

    private double calculateDistance(BlockPos clientPos) {
        double dx = warehouseBlockPos.getX() - clientPos.getX();
        double dy = warehouseBlockPos.getY() - clientPos.getY();
        double dz = warehouseBlockPos.getZ() - clientPos.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double calculateTransferCost(double distance) {
        if (distance <= BASE_DISTANCE) {
            return 0;
        }
        double extraDistance = distance - BASE_DISTANCE;
        int additionalSteps = (int) Math.ceil(extraDistance / ADDITIONAL_DISTANCE_STEP);
        return BASE_COST_PER_GROUP + (additionalSteps * ADDITIONAL_COST_PER_STEP);
    }

    // ── 客户端数据类 ──

    private static class ClientEntry {
        final UUID clientId;
        final BlockPos blockPos;
        final List<BlockPos> portPositions = new ArrayList<>();
        String name = "";

        ClientEntry(UUID clientId, BlockPos blockPos) {
            this.clientId = clientId;
            this.blockPos = blockPos;
        }

        boolean hasCustomName() { return name != null && !name.isBlank(); }

        String getDisplayName() {
            if (hasCustomName()) return name;
            return blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
        }
    }

    public static class ItemEntry {
        public final ItemStack stack;
        public final String itemId;
        public int count;

        public ItemEntry(ItemStack stack, String itemId, int count) {
            this.stack = stack;
            this.stack.setCount(1);
            this.itemId = itemId;
            this.count = count;
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
