package com.xiaoliang.simukraft.inventory;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.init.ModMenus;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仓库网格菜单 - 使用真实槽位系统
 * 显示所有容器中的物品（按种类合并）
 */
@SuppressWarnings({"null", "unused"})
public class WarehouseGridMenu extends AbstractContainerMenu {

    private final BlockPos warehousePos;
    private final Level level;

    // 槽位索引定义
    private static final int WAREHOUSE_SLOTS = 54; // 6行 x 9列
    public static final int PLAYER_INVENTORY_START = WAREHOUSE_SLOTS;
    public static final int TOTAL_SLOTS = WAREHOUSE_SLOTS + 36;

    // 玩家背包位置
    private static final int PLAYER_INVENTORY_X = 8;
    private static final int PLAYER_INVENTORY_Y = 140;

    // 客户端缓存的物品数据（用于显示）
    private final List<ItemStack> clientItems = new ArrayList<>();
    // 绑定的容器位置列表（客户端缓存）
    private final List<BlockPos> containerPositions = new ArrayList<>();

    public WarehouseGridMenu(int containerId, Inventory playerInventory, BlockPos warehousePos) {
        super(ModMenus.WAREHOUSE_GRID_MENU.get(), containerId);
        this.warehousePos = warehousePos;
        this.level = playerInventory.player.level();

        // 初始化客户端缓存（54个空槽位）
        for (int i = 0; i < WAREHOUSE_SLOTS; i++) {
            clientItems.add(ItemStack.EMPTY);
        }

        // 添加仓库槽位
        initWarehouseSlots();

        // 添加玩家背包槽位
        initPlayerSlots(playerInventory);

        // 如果在服务器端，加载容器位置
        if (level instanceof ServerLevel serverLevel) {
            loadContainerPositions(serverLevel);
        }
    }

    /**
     * 加载绑定的容器位置
     */
    private void loadContainerPositions(ServerLevel serverLevel) {
        var data = com.xiaoliang.simukraft.world.LogisticsData.get(serverLevel);
        var warehouse = data.getWarehouseByBlockPos(warehousePos);
        if (warehouse != null) {
            containerPositions.clear();
            containerPositions.addAll(warehouse.getContainerPositions());
        }
    }

    /**
     * 初始化仓库槽位 - 6行 x 9列
     */
    private void initWarehouseSlots() {
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = row * 9 + col;
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                this.addSlot(new WarehouseSlot(slotIndex, x, y));
            }
        }
    }

    /**
     * 初始化玩家背包槽位
     */
    private void initPlayerSlots(Inventory playerInventory) {
        // 背包 (27个槽位)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = row * 9 + col + 9;
                int x = PLAYER_INVENTORY_X + col * 18;
                int y = PLAYER_INVENTORY_Y + row * 18;
                this.addSlot(new Slot(playerInventory, slotIndex, x, y));
            }
        }

        // 快捷栏 (9个槽位)
        for (int col = 0; col < 9; col++) {
            int x = PLAYER_INVENTORY_X + col * 18;
            int y = PLAYER_INVENTORY_Y + 58;
            this.addSlot(new Slot(playerInventory, col, x, y));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(
                warehousePos.getX() + 0.5,
                warehousePos.getY() + 0.5,
                warehousePos.getZ() + 0.5
        ) <= 64.0;
    }

    public BlockPos getWarehousePos() {
        return warehousePos;
    }

    /**
     * 更新客户端物品数据（由客户端调用）
     */
    public void updateClientItems(List<ItemStack> items, List<BlockPos> positions) {
        clientItems.clear();
        if (items != null) {
            for (int i = 0; i < WAREHOUSE_SLOTS && i < items.size(); i++) {
                clientItems.add(items.get(i).copy());
            }
        }
        // 填充剩余槽位
        while (clientItems.size() < WAREHOUSE_SLOTS) {
            clientItems.add(ItemStack.EMPTY);
        }

        // 更新容器位置
        containerPositions.clear();
        if (positions != null) {
            containerPositions.addAll(positions);
        }
    }

    /**
     * 获取容器位置列表
     */
    public List<BlockPos> getContainerPositions() {
        return new ArrayList<>(containerPositions);
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        // 拦截一键整理模组可能使用的批量操作类型
        if (clickType == ClickType.PICKUP_ALL || clickType == ClickType.CLONE) {
            // 一键整理模组通常会使用这些点击类型进行批量操作
            // 阻止这些操作以防止物品消失
            return;
        }
        
        // 检测是否是一键整理模组的批量操作（通过调用栈检测）
        if (isBulkOperation()) {
            // 阻止一键整理模组对仓库槽位的所有操作
            if (slotId >= 0 && slotId < WAREHOUSE_SLOTS) {
                return;
            }
        }

        // 处理仓库槽位的点击
        if (slotId >= 0 && slotId < WAREHOUSE_SLOTS) {
            Slot slot = this.slots.get(slotId);
            ItemStack carried = this.getCarried();
            
            // 检测 Shift 键状态
            boolean isShiftDown = player.isShiftKeyDown();
            
            if (clickType == ClickType.PICKUP) {
                // 只在客户端发送网络包到服务器执行
                if (level.isClientSide) {
                    if (isShiftDown && carried.isEmpty() && slot.hasItem()) {
                        // Shift+左键：直接移动一组到背包
                        ItemStack slotItem = slot.getItem();
                        String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(slotItem.getItem()).toString();
                        var nbtTag = slotItem.getTag();
                        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                                new com.xiaoliang.simukraft.network.WarehouseGridShiftClickPacket(warehousePos, itemId, nbtTag)
                        );
                    } else if (carried.isEmpty()) {
                        // 手上没有物品，请求从仓库提取到手上
                        if (slot.hasItem()) {
                            ItemStack slotItem = slot.getItem();
                            String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(slotItem.getItem()).toString();
                            var nbtTag = slotItem.getTag();
                            com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                                    new com.xiaoliang.simukraft.network.WarehouseGridExtractPacket(warehousePos, itemId, nbtTag, slotItem.getCount())
                            );
                        }
                    } else {
                        // 手上有物品，请求插入到仓库
                        com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                                new com.xiaoliang.simukraft.network.WarehouseGridInsertPacket(warehousePos)
                        );
                    }
                }
                return;
            } else if (clickType == ClickType.QUICK_MOVE) {
                // Shift+点击（另一种检测方式）
                if (level.isClientSide && carried.isEmpty() && slot.hasItem()) {
                    ItemStack slotItem = slot.getItem();
                    String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(slotItem.getItem()).toString();
                    var nbtTag = slotItem.getTag();
                    com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                            new com.xiaoliang.simukraft.network.WarehouseGridShiftClickPacket(warehousePos, itemId, nbtTag)
                    );
                }
                return;
            } else if (clickType == ClickType.SWAP) {
                // 数字键交换，不允许
                return;
            } else if (clickType == ClickType.THROW) {
                // 阻止从仓库槽位直接丢弃物品（一键整理模组可能会这样做）
                return;
            }
        }
        
        // 其他情况使用默认处理
        super.clicked(slotId, dragType, clickType, player);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        // 阻止拖拽到仓库槽位（一键整理模组可能会使用拖拽）
        if (slot instanceof WarehouseSlot) {
            return false;
        }
        return super.canDragTo(slot);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        // 阻止一键整理模组的"拾取全部"功能对仓库槽位生效
        if (slot instanceof WarehouseSlot) {
            return false;
        }
        return super.canTakeItemForPickAll(stack, slot);
    }
    
    @Override
    protected boolean moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        // 检测是否是一键整理模组的批量操作
        if (isBulkOperation()) {
            // 阻止一键整理模组批量移动物品到仓库槽位
            // 检查目标范围是否包含仓库槽位
            if (startIndex < WAREHOUSE_SLOTS || endIndex <= WAREHOUSE_SLOTS) {
                return false;
            }
        }
        return super.moveItemStackTo(stack, startIndex, endIndex, reverseDirection);
    }
    
    /**
     * 刷新仓库显示
     */
    public void refreshWarehouseDisplay() {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // 重新收集所有物品
        // 使用列表来存储物品原型和数量，使用 ItemHandlerHelper.canItemStacksStack 来判断是否可以堆叠
        List<ItemStack> mergedItemPrototypes = new ArrayList<>();
        List<Integer> mergedItemCounts = new ArrayList<>();

        var data = com.xiaoliang.simukraft.world.LogisticsData.get(serverLevel);
        var warehouse = data.getWarehouseByBlockPos(warehousePos);
        if (warehouse == null) return;

        for (BlockPos pos : warehouse.getContainerPositions()) {
            // 跳过精妙储存模组的大箱子副箱子（避免重复计算）
            if (isSophisticatedStorageSubChest(serverLevel, pos)) {
                continue;
            }
            
            List<ItemStack> items = ContainerUtils.getAllItemsOnMainThread(serverLevel, pos);
            for (ItemStack item : items) {
                if (item.isEmpty()) continue;

                // 查找是否可以与现有物品堆叠
                boolean found = false;
                for (int i = 0; i < mergedItemPrototypes.size(); i++) {
                    ItemStack prototype = mergedItemPrototypes.get(i);
                    if (net.minecraftforge.items.ItemHandlerHelper.canItemStacksStack(prototype, item)) {
                        // 可以堆叠，增加数量
                        mergedItemCounts.set(i, mergedItemCounts.get(i) + item.getCount());
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    // 不能堆叠，添加为新物品
                    mergedItemPrototypes.add(item.copy());
                    mergedItemCounts.add(item.getCount());
                }
            }
        }

        // 创建物品数据列表用于排序
        List<ItemData> itemDataList = new ArrayList<>();
        for (int i = 0; i < mergedItemPrototypes.size(); i++) {
            itemDataList.add(new ItemData(mergedItemPrototypes.get(i), mergedItemCounts.get(i)));
        }
        
        // 按物品名称排序（保持稳定的排序，方便快速拿取）
        itemDataList.sort((a, b) -> {
            String nameA = a.prototype.getItem().toString();
            String nameB = b.prototype.getItem().toString();
            return nameA.compareToIgnoreCase(nameB);
        });

        // 更新客户端缓存
        clientItems.clear();
        int slotIndex = 0;

        // 添加所有合并后的物品（已排序）
        for (ItemData itemData : itemDataList) {
            if (slotIndex >= WAREHOUSE_SLOTS) break;
            ItemStack displayStack = itemData.prototype.copy();
            displayStack.setCount(Math.min(displayStack.getMaxStackSize(), itemData.count));
            clientItems.add(displayStack);
            slotIndex++;
        }

        while (clientItems.size() < WAREHOUSE_SLOTS) {
            clientItems.add(ItemStack.EMPTY);
        }
    }
    
    /**
     * 物品数据类，用于排序
     */
    private static class ItemData {
        final ItemStack prototype;
        final int count;
        
        ItemData(ItemStack prototype, int count) {
            this.prototype = prototype;
            this.count = count;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);

        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();

        if (index < WAREHOUSE_SLOTS) {
            // 从仓库移动到玩家背包
            // 注意：一键整理模组可能会调用此方法进行批量整理
            // 由于仓库使用虚拟槽位系统，直接操作会导致物品消失
            // 这里只处理单个物品的移动，通过调用 clicked 方法使用网络包
            
            // 检测是否是一键整理模组的批量操作（通过调用栈检测）
            if (isBulkOperation()) {
                // 阻止批量操作
                return ItemStack.EMPTY;
            }
            
            // 先从实际容器提取物品
            // 对于有NBT的物品，只提取1个（不堆叠）
            int extractCount = ContainerUtils.hasNBT(stack) ? 1 : stack.getCount();
            ItemStack extracted = extractFromWarehouse(index, extractCount);
            if (extracted.isEmpty()) {
                return ItemStack.EMPTY;
            }
            
            // 清空槽位显示
            slot.set(ItemStack.EMPTY);
            
            // 尝试将提取的物品放入玩家背包
            ItemStack remaining = extracted.copy();
            if (!this.moveItemStackTo(remaining, PLAYER_INVENTORY_START, TOTAL_SLOTS, true)) {
                // 移动失败，把物品放回仓库
                insertToWarehouse(remaining);
                // 恢复槽位显示
                slot.set(stack);
                return ItemStack.EMPTY;
            }
            
            // 移动成功，检查是否有剩余
            if (!remaining.isEmpty()) {
                // 有剩余，放回仓库
                insertToWarehouse(remaining);
            }
            
            slot.onTake(player, extracted);
            return extracted;
        } else {
            // 从玩家背包移动到仓库
            ItemStack toInsert = stack.copy();
            ItemStack remaining = insertToWarehouse(toInsert);
            
            // 计算实际插入的数量
            int inserted = toInsert.getCount() - remaining.getCount();
            if (inserted <= 0) {
                return ItemStack.EMPTY;
            }
            
            // 从玩家背包移除已插入的物品
            stack.shrink(inserted);
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            }
            
            slot.onTake(player, toInsert);
            
            // 返回剩余的物品（如果有）
            return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
        }
    }
    
    /**
     * 检测是否是一键整理模组的批量操作
     * 通过检查调用栈中是否存在已知的整理模组类
     */
    private boolean isBulkOperation() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            // 检测 Inventory Profiles Next 模组的类
            if (className.contains("inventoryprofiles") || 
                className.contains("InventoryProfiles") ||
                className.contains("ipnext") ||
                className.contains("IPN")) {
                return true;
            }
            // 检测其他已知的一键整理模组
            if (className.contains("invtweaks") ||
                className.contains("InvTweaks") ||
                className.contains("inventorytweaks") ||
                className.contains("InventoryTweaks") ||
                className.contains("inventorysorter") ||
                className.contains("InventorySorter")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从仓库中提取物品
     * @param slotIndex 槽位索引（对应物品种类）
     * @param count 要提取的数量
     * @return 实际提取的物品
     */
    public ItemStack extractFromWarehouse(int slotIndex, int count) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return ItemStack.EMPTY;
        }
        var data = com.xiaoliang.simukraft.world.LogisticsData.get(serverLevel);
        var warehouse = data.getWarehouseByBlockPos(warehousePos);
        if (warehouse == null) {
            return ItemStack.EMPTY;
        }

        // 在服务器端，直接从容器收集物品信息（而不是依赖clientItems）
        // 使用列表来存储物品原型，使用 ItemHandlerHelper.canItemStacksStack 来判断是否可以堆叠
        List<ItemStack> mergedItemPrototypes = new ArrayList<>();

        for (BlockPos pos : warehouse.getContainerPositions()) {
            List<ItemStack> items = ContainerUtils.getAllItemsOnMainThread(serverLevel, pos);
            for (ItemStack item : items) {
                if (item.isEmpty()) continue;

                // 查找是否可以与现有物品堆叠
                boolean found = false;
                for (ItemStack prototype : mergedItemPrototypes) {
                    if (net.minecraftforge.items.ItemHandlerHelper.canItemStacksStack(prototype, item)) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    // 不能堆叠，添加为新物品
                    mergedItemPrototypes.add(item.copy());
                }
            }
        }

        // 获取该槽位显示的物品类型
        ItemStack targetItem = ItemStack.EMPTY;
        if (slotIndex >= 0 && slotIndex < mergedItemPrototypes.size()) {
            targetItem = mergedItemPrototypes.get(slotIndex);
        }

        if (targetItem.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack extracted = ItemStack.EMPTY;
        int remainingToExtract = count;

        // 遍历所有容器，提取该类型的物品
        for (BlockPos pos : warehouse.getContainerPositions()) {
            if (remainingToExtract <= 0) break;

            // 跳过精妙储存模组的大箱子副箱子（避免重复计算）
            if (isSophisticatedStorageSubChest(serverLevel, pos)) {
                continue;
            }

            // 获取容器中的所有物品
            List<ItemStack> items = ContainerUtils.getAllItemsOnMainThread(serverLevel, pos);

            for (ItemStack item : items) {
                if (remainingToExtract <= 0) break;
                if (item.isEmpty()) continue;

                // 使用仓库堆叠规则来判断是否匹配
                // - 如果目标物品无NBT，可以提取所有同类型物品（无论是否有NBT）
                // - 如果目标物品有NBT，只能提取完全匹配的物品
                boolean canExtract;
                if (ContainerUtils.hasNBT(targetItem)) {
                    // 目标有NBT，必须完全匹配
                    canExtract = ContainerUtils.areItemsEqualWithNBT(item, targetItem);
                } else {
                    // 目标无NBT，可以提取同类型的所有物品
                    canExtract = item.getItem() == targetItem.getItem();
                }

                if (!canExtract) continue;

                // 提取这个物品
                int extractCount = Math.min(remainingToExtract, item.getCount());
                ItemStack toExtract = item.copy();
                toExtract.setCount(extractCount);

                // 从容器中移除
                boolean success = ContainerUtils.consumeItemOnMainThread(serverLevel, pos, toExtract);

                if (success) {
                    // 累加到提取结果
                    if (extracted.isEmpty()) {
                        extracted = toExtract;
                    } else {
                        // 检查是否可以堆叠（仓库规则：无NBT的可以堆叠，有NBT的不能堆叠）
                        if (ContainerUtils.canStackInWarehouse(extracted, toExtract)) {
                            extracted.grow(extractCount);
                        } else {
                            // 不能堆叠，把刚提取的物品放回仓库
                            insertToWarehouse(toExtract);
                            remainingToExtract = 0;
                            break;
                        }
                    }

                    remainingToExtract -= extractCount;

                    // 如果目标物品有NBT，提取一个后就完全停止（不再继续遍历）
                    if (ContainerUtils.hasNBT(targetItem)) {
                        remainingToExtract = 0;
                        break;
                    }
                }
            }

            // 如果目标物品有NBT且已经提取成功，停止遍历所有容器
            if (ContainerUtils.hasNBT(targetItem) && !extracted.isEmpty()) {
                break;
            }
        }

        return extracted;
    }

    /**
     * 根据物品ID从仓库中提取物品（包含NBT匹配）
     * @param itemId 物品ID（如 "minecraft:oak_log"）
     * @param nbtTag 物品的NBT数据（可为null）
     * @param count 要提取的数量
     * @return 实际提取的物品
     */
    public ItemStack extractFromWarehouseByItemId(String itemId, net.minecraft.nbt.CompoundTag nbtTag, int count) {
        if (!(level instanceof ServerLevel serverLevel)) {
            Simukraft.LOGGER.debug("[WarehouseGridMenu] extractFromWarehouseByItemId: 不在服务器端");
            return ItemStack.EMPTY;
        }
        var data = com.xiaoliang.simukraft.world.LogisticsData.get(serverLevel);
        var warehouse = data.getWarehouseByBlockPos(warehousePos);
        if (warehouse == null) {
            Simukraft.LOGGER.debug("[WarehouseGridMenu] extractFromWarehouseByItemId: 找不到仓库");
            return ItemStack.EMPTY;
        }

        // 解析物品ID获取物品类型
        ItemStack targetItem = ItemStack.EMPTY;
        try {
            net.minecraft.resources.ResourceLocation resourceLocation = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (resourceLocation == null) {
                return ItemStack.EMPTY;
            }
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(resourceLocation);
            if (item != null) {
                targetItem = new ItemStack(item);
                // 设置NBT数据
                if (nbtTag != null) {
                    targetItem.setTag(nbtTag.copy());
                }
            }
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }

        if (targetItem.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack extracted = ItemStack.EMPTY;
        int remainingToExtract = count;

        // 遍历所有容器，提取该类型的物品（包含NBT匹配）
        for (BlockPos pos : warehouse.getContainerPositions()) {
            if (remainingToExtract <= 0) break;

            // 获取容器中的所有物品
            List<ItemStack> items = ContainerUtils.getAllItemsOnMainThread(serverLevel, pos);

            for (ItemStack item : items) {
                if (remainingToExtract <= 0) break;
                if (item.isEmpty()) continue;

                // 使用仓库堆叠规则来判断是否匹配
                boolean canExtract;
                if (ContainerUtils.hasNBT(targetItem)) {
                    // 目标有NBT，必须完全匹配
                    canExtract = ContainerUtils.areItemsEqualWithNBT(item, targetItem);
                } else {
                    // 目标无NBT，可以提取同类型的所有物品
                    canExtract = item.getItem() == targetItem.getItem();
                }
                if (!canExtract) continue;

                // 提取这个物品
                int extractCount = Math.min(remainingToExtract, item.getCount());
                ItemStack toExtract = item.copy();
                toExtract.setCount(extractCount);

                // 从容器中移除
                boolean success = ContainerUtils.consumeItemOnMainThread(serverLevel, pos, toExtract);

                if (success) {
                    // 累加到提取结果
                    if (extracted.isEmpty()) {
                        extracted = toExtract;
                    } else {
                        // 检查是否可以堆叠（仓库规则：无NBT的可以堆叠，有NBT的不能堆叠）
                        if (ContainerUtils.canStackInWarehouse(extracted, toExtract)) {
                            extracted.grow(extractCount);
                        } else {
                            // 不能堆叠，把刚提取的物品放回仓库
                            insertToWarehouse(toExtract);
                            remainingToExtract = 0;
                            break;
                        }
                    }

                    remainingToExtract -= extractCount;

                    // 如果目标物品有NBT，提取一个后就完全停止（不再继续遍历）
                    if (ContainerUtils.hasNBT(targetItem)) {
                        remainingToExtract = 0;
                        break;
                    }
                }
            }

            // 如果目标物品有NBT且已经提取成功，停止遍历所有容器
            if (ContainerUtils.hasNBT(targetItem) && !extracted.isEmpty()) {
                break;
            }
        }

        return extracted;
    }

    /**
     * 根据物品ID从仓库中提取物品（旧版本，不包含NBT匹配）
     * @deprecated 请使用包含NBT参数的版本
     */
    @Deprecated
    public ItemStack extractFromWarehouseByItemId(String itemId, int count) {
        return extractFromWarehouseByItemId(itemId, null, count);
    }

    /**
     * 存入物品到仓库
     * @return 剩余未存入的物品
     */
    public ItemStack insertToWarehouse(ItemStack stack) {
        if (!(level instanceof ServerLevel serverLevel)) return stack;
        var data = com.xiaoliang.simukraft.world.LogisticsData.get(serverLevel);
        var warehouse = data.getWarehouseByBlockPos(warehousePos);
        if (warehouse == null) return stack;

        ItemStack remaining = stack.copy();

        for (BlockPos pos : warehouse.getContainerPositions()) {
            if (remaining.isEmpty()) break;

            // 跳过精妙储存模组的大箱子副箱子（避免重复插入）
            if (isSophisticatedStorageSubChest(serverLevel, pos)) {
                continue;
            }

            // 使用 ContainerUtils 插入物品
            int inserted = ContainerUtils.insertItemOnMainThread(serverLevel, pos, remaining);
            remaining.shrink(inserted);
        }
        
        return remaining;
    }

    /**
     * 获取客户端缓存的物品（用于显示）
     */
    public List<ItemStack> getClientItems() {
        return clientItems;
    }

    /**
     * 仓库虚拟槽位 - 显示合并后的物品
     */
    private class WarehouseSlot extends Slot {
        private final int slotIndex;

        public WarehouseSlot(int index, int x, int y) {
            super(new net.minecraft.world.SimpleContainer(1), 0, x, y);
            this.slotIndex = index;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return true; // 允许放入物品
        }

        @Override
        public boolean mayPickup(Player player) {
            return true; // 允许取出物品
        }

        @Override
        public ItemStack getItem() {
            // 使用客户端缓存的物品数据
            if (slotIndex >= 0 && slotIndex < clientItems.size()) {
                return clientItems.get(slotIndex);
            }
            return ItemStack.EMPTY;
        }

        @Override
        public void set(ItemStack stack) {
            // 更新客户端缓存
            if (slotIndex >= 0 && slotIndex < clientItems.size()) {
                clientItems.set(slotIndex, stack.copy());
            }
        }

        @Override
        public void setChanged() {
            // 不需要同步，因为物品存储在实际容器中
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public boolean isActive() {
            // 所有仓库槽位都激活（前54个）
            return slotIndex < WAREHOUSE_SLOTS;
        }

        /**
         * 获取槽位索引
         */
        public int getSlotIndex() {
            return slotIndex;
        }
    }
    
    /**
     * 检测指定位置是否是精妙储存模组的大箱子副箱子
     * 精妙储存模组的大箱子由两个方块组成，副箱子会将所有 capability 请求转发到主箱子
     * 如果不跳过副箱子，会导致物品被重复计算
     */
    private static boolean isSophisticatedStorageSubChest(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return false;
        
        // 检查是否是精妙储存模组的箱子实体
        String className = blockEntity.getClass().getName();
        if (!className.contains("sophisticatedstorage")) {
            return false;
        }
        
        try {
            // 使用反射检查 doubleMainPos 字段
            // 如果该字段不为 null，说明这是副箱子
            java.lang.reflect.Field field = blockEntity.getClass().getDeclaredField("doubleMainPos");
            field.setAccessible(true);
            Object value = field.get(blockEntity);
            return value != null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // 如果没有这个字段，说明不是大箱子或者不是副箱子
            return false;
        }
    }
}
