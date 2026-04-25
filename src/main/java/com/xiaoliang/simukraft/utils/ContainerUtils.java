package com.xiaoliang.simukraft.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 通用容器工具类
 * 同时支持原生的 Container 接口和 Forge Capabilities (IItemHandler)
 * 用于兼容原版箱子、精致存储等不同类型的容器
 */
public class ContainerUtils {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * 检查两个物品是否完全匹配（包括物品类型和NBT数据）
     * 用于仓库系统中区分不同NBT的物品
     *
     * @param stack1 第一个物品
     * @param stack2 第二个物品
     * @return 如果物品类型和NBT都相同则返回true
     */
    public static boolean areItemsEqualWithNBT(ItemStack stack1, ItemStack stack2) {
        if (stack1.isEmpty() || stack2.isEmpty()) {
            return stack1.isEmpty() && stack2.isEmpty();
        }
        // 检查物品类型
        if (stack1.getItem() != stack2.getItem()) {
            return false;
        }
        // 检查NBT数据
        var tag1 = stack1.getTag();
        var tag2 = stack2.getTag();
        if (tag1 == null && tag2 == null) {
            return true;
        }
        if (tag1 == null || tag2 == null) {
            return false;
        }
        return tag1.equals(tag2);
    }

    /**
     * 检查两个物品是否只匹配类型（忽略NBT）
     * 用于基本的物品分类
     *
     * @param stack1 第一个物品
     * @param stack2 第二个物品
     * @return 如果物品类型相同则返回true
     */
    public static boolean areItemsEqualIgnoreNBT(ItemStack stack1, ItemStack stack2) {
        if (stack1.isEmpty() || stack2.isEmpty()) {
            return stack1.isEmpty() && stack2.isEmpty();
        }
        return stack1.getItem() == stack2.getItem();
    }

    /**
     * 检查物品是否有NBT标签
     *
     * @param stack 物品
     * @return 如果有NBT标签则返回true
     */
    public static boolean hasNBT(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var tag = stack.getTag();
        return tag != null && !tag.isEmpty();
    }

    /**
     * 检查两个物品是否可以堆叠（仓库规则）
     * - 无NBT的物品可以堆叠（只要类型相同）
     * - 有NBT的物品不能堆叠（必须完全匹配）
     *
     * @param stack1 第一个物品
     * @param stack2 第二个物品
     * @return 如果可以堆叠则返回true
     */
    public static boolean canStackInWarehouse(ItemStack stack1, ItemStack stack2) {
        if (stack1.isEmpty() || stack2.isEmpty()) {
            return stack1.isEmpty() && stack2.isEmpty();
        }
        // 检查物品类型
        if (stack1.getItem() != stack2.getItem()) {
            return false;
        }
        // 如果有任何一个物品有NBT，就不能堆叠
        boolean hasNBT1 = hasNBT(stack1);
        boolean hasNBT2 = hasNBT(stack2);
        if (hasNBT1 || hasNBT2) {
            // 有NBT的物品必须完全匹配才能堆叠
            return areItemsEqualWithNBT(stack1, stack2);
        }
        // 都无NBT，可以堆叠
        return true;
    }

    /**
     * 在主线程中执行容器操作（同步等待结果）
     * 用于解决多线程环境下访问容器导致的线程安全问题
     *
     * @param level 服务器世界
     * @param action 要在主线程执行的容器操作
     * @param <T> 返回类型
     * @return 操作结果，如果出错则返回默认值（boolean返回false，对象返回null）
     */
    public static <T> T executeOnMainThread(ServerLevel level, Supplier<T> action) {
        MinecraftServer server = level.getServer();

        // 如果当前已经在主线程，直接执行
        if (server.isSameThread()) {
            try {
                return action.get();
            } catch (Exception e) {
                LOGGER.error("执行容器操作时出错", e);
                return null;
            }
        }

        // 提交到主线程并等待结果
        try {
            return server.submit(() -> {
                try {
                    return action.get();
                } catch (Exception e) {
                    LOGGER.error("在主线程执行容器操作时出错", e);
                    return null;
                }
            }).join();
        } catch (Exception e) {
            LOGGER.error("提交容器操作到主线程时出错", e);
            return null;
        }
    }

    /**
     * 在主线程中执行容器操作（无返回值，同步等待）
     *
     * @param level 服务器世界
     * @param action 要在主线程执行的操作
     */
    public static void executeOnMainThread(ServerLevel level, Runnable action) {
        MinecraftServer server = level.getServer();

        // 如果当前已经在主线程，直接执行
        if (server.isSameThread()) {
            try {
                action.run();
            } catch (Exception e) {
                LOGGER.error("执行容器操作时出错", e);
            }
            return;
        }

        // 提交到主线程并等待完成
        try {
            server.submit(() -> {
                try {
                    action.run();
                } catch (Exception e) {
                    LOGGER.error("在主线程执行容器操作时出错", e);
                }
            }).join();
        } catch (Exception e) {
            LOGGER.error("提交容器操作到主线程时出错", e);
        }
    }

    /**
     * 在主线程中检查容器
     *
     * @param level 服务器世界
     * @param pos 容器位置
     * @return 是否是容器
     */
    public static boolean isContainerOnMainThread(ServerLevel level, BlockPos pos) {
        return executeOnMainThread(level, () -> isContainer(level, pos));
    }

    /**
     * 在主线程中获取容器中的所有物品
     *
     * @param level 服务器世界
     * @param pos 容器位置
     * @return 物品列表
     */
    public static List<ItemStack> getAllItemsOnMainThread(ServerLevel level, BlockPos pos) {
        return executeOnMainThread(level, () -> getAllItems(level, pos));
    }

    /**
     * 在主线程中插入物品到容器
     *
     * @param level 服务器世界
     * @param pos 容器位置
     * @param itemStack 要插入的物品
     * @return 成功插入的数量
     */
    public static int insertItemOnMainThread(ServerLevel level, BlockPos pos, ItemStack itemStack) {
        return executeOnMainThread(level, () -> insertItem(level, pos, itemStack));
    }

    /**
     * 在主线程中从容器消耗物品
     *
     * @param level 服务器世界
     * @param pos 容器位置
     * @param itemStack 要消耗的物品
     * @return 是否成功消耗
     */
    public static boolean consumeItemOnMainThread(ServerLevel level, BlockPos pos, ItemStack itemStack) {
        return executeOnMainThread(level, () -> consumeItem(level, pos, itemStack));
    }

    /**
     * 在主线程中检查容器是否有空槽位
     *
     * @param level 服务器世界
     * @param pos 容器位置
     * @return 是否有空槽位
     */
    public static boolean hasEmptySlotOnMainThread(ServerLevel level, BlockPos pos) {
        return executeOnMainThread(level, () -> hasEmptySlot(level, pos));
    }

    /**
     * 在主线程中统计容器中特定物品的数量
     *
     * @param level 服务器世界
     * @param pos 容器位置
     * @param itemStack 要统计的物品
     * @return 物品数量
     */
    public static int countItemOnMainThread(ServerLevel level, BlockPos pos, ItemStack itemStack) {
        return executeOnMainThread(level, () -> countItem(level, pos, itemStack));
    }

    /**
     * 在主线程中检查容器是否有特定物品
     *
     * @param level 服务器世界
     * @param pos 容器位置
     * @param itemStack 要查找的物品
     * @return 是否找到
     */
    public static boolean hasItemOnMainThread(ServerLevel level, BlockPos pos, ItemStack itemStack) {
        return executeOnMainThread(level, () -> hasItem(level, pos, itemStack));
    }

    /**
     * 检查方块位置是否是有效的容器
     * @param level 世界
     * @param pos 位置
     * @return 如果是容器返回true
     */
    public static boolean isContainer(Level level, BlockPos pos) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }
        
        // 检查是否实现了 Container 接口
        if (blockEntity instanceof Container) {
            return true;
        }
        
        // 检查是否有 IItemHandler Capability
        Optional<IItemHandler> handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        return handler.isPresent();
    }

    /**
     * 从容器中查找物品
     * @param level 世界
     * @param pos 容器位置
     * @param itemStack 要查找的物品
     * @return 如果找到返回true
     */
    public static boolean hasItem(Level level, BlockPos pos, ItemStack itemStack) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }
        
        // 优先使用 Container 接口
        if (blockEntity instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (ItemHandlerHelper.canItemStacksStack(stack, itemStack) && stack.getCount() >= itemStack.getCount()) {
                    return true;
                }
            }
            return false;
        }
        
        // 使用 IItemHandler Capability
        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            IItemHandler handler = cap.get();
            int foundCount = 0;
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (ItemHandlerHelper.canItemStacksStack(stack, itemStack)) {
                    foundCount += stack.getCount();
                    if (foundCount >= itemStack.getCount()) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    /**
     * 从容器中消耗物品
     * @param level 世界
     * @param pos 容器位置
     * @param itemStack 要消耗的物品
     * @return 如果成功消耗返回true
     */
    public static boolean consumeItem(Level level, BlockPos pos, ItemStack itemStack) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }

        int remaining = itemStack.getCount();

        // 优先使用 Container 接口
        if (blockEntity instanceof Container container) {
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = container.getItem(i);
                // 使用包含NBT的比较方法来匹配物品
                if (areItemsEqualWithNBT(stack, itemStack)) {
                    int toExtract = Math.min(remaining, stack.getCount());
                    stack.shrink(toExtract);
                    remaining -= toExtract;
                    if (stack.isEmpty()) {
                        container.setItem(i, Objects.requireNonNull(ItemStack.EMPTY));
                    }
                }
            }
            return remaining == 0;
        }

        // 使用 IItemHandler Capability
        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            IItemHandler handler = cap.get();
            for (int i = 0; i < handler.getSlots() && remaining > 0; i++) {
                ItemStack stack = handler.getStackInSlot(i);
                // 使用包含NBT的比较方法来匹配物品
                if (areItemsEqualWithNBT(stack, itemStack)) {
                    ItemStack extracted = handler.extractItem(i, remaining, false);
                    remaining -= extracted.getCount();
                }
            }
            return remaining == 0;
        }

        return false;
    }

    /**
     * 将物品存入容器
     * @param level 世界
     * @param pos 容器位置
     * @param itemStack 要存入的物品
     * @return 成功存入的物品数量
     */
    public static int insertItem(Level level, BlockPos pos, ItemStack itemStack) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved() || itemStack.isEmpty()) {
            return 0;
        }

        int remaining = itemStack.getCount();

        // 优先使用 Container 接口
        if (blockEntity instanceof Container container) {
            // 先尝试合并到已有槽位（使用仓库堆叠规则）
            // - 无NBT的物品可以堆叠
            // - 有NBT的物品不能堆叠
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && canStackInWarehouse(stack, itemStack)) {
                    int space = stack.getMaxStackSize() - stack.getCount();
                    int toAdd = Math.min(remaining, space);
                    stack.grow(toAdd);
                    remaining -= toAdd;
                }
            }

            // 再尝试放入空槽位
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    int toAdd = Math.min(remaining, itemStack.getMaxStackSize());
                    ItemStack copy = itemStack.copy();
                    copy.setCount(toAdd);
                    container.setItem(i, copy);
                    remaining -= toAdd;
                }
            }

            return itemStack.getCount() - remaining;
        }

        // 使用 IItemHandler Capability
        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            IItemHandler handler = cap.get();
            ItemStack toInsert = itemStack.copy();

            for (int i = 0; i < handler.getSlots() && !toInsert.isEmpty(); i++) {
                toInsert = handler.insertItem(i, toInsert, false);
            }

            return itemStack.getCount() - toInsert.getCount();
        }

        return 0;
    }

    /**
     * 获取容器中的所有物品
     * 返回的物品已经合并，避免某些模组（如精妙储存）的重复槽位问题
     * @param level 世界
     * @param pos 容器位置
     * @return 物品列表（已合并）
     */
    public static List<ItemStack> getAllItems(Level level, BlockPos pos) {
        List<ItemStack> rawItems = new ArrayList<>();
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return rawItems;
        }
        
        // 使用 IItemHandler Capability（优先，兼容性更好）
        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            IItemHandler handler = cap.get();
            LOGGER.debug("[ContainerUtils] 容器 {} 有 {} 个槽位", safePos, handler.getSlots());
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    LOGGER.debug("[ContainerUtils] 槽位 {}: {} x {}", i, stack.getItem(), stack.getCount());
                    rawItems.add(stack.copy());
                }
            }
            // 合并物品，防止某些模组（如精妙储存）的重复槽位问题
            return mergeItemStacks(rawItems);
        }
        
        // 回退到 Container 接口
        if (blockEntity instanceof Container container) {
            LOGGER.debug("[ContainerUtils] 容器 {} 使用 Container 接口，有 {} 个槽位", safePos, container.getContainerSize());
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    LOGGER.debug("[ContainerUtils] 槽位 {}: {} x {}", i, stack.getItem(), stack.getCount());
                    rawItems.add(stack.copy());
                }
            }
        }
        
        return rawItems;
    }
    
    /**
     * 合并物品列表中的可堆叠物品
     * 用于处理某些模组（如精妙储存）可能返回重复物品的情况
     * @param items 原始物品列表
     * @return 合并后的物品列表
     */
    private static List<ItemStack> mergeItemStacks(List<ItemStack> items) {
        List<ItemStack> merged = new ArrayList<>();
        
        for (ItemStack item : items) {
            if (item.isEmpty()) continue;
            
            // 查找是否可以与已合并的物品堆叠
            boolean found = false;
            for (ItemStack mergedItem : merged) {
                if (ItemHandlerHelper.canItemStacksStack(mergedItem, item)) {
                    // 可以堆叠，增加数量
                    mergedItem.grow(item.getCount());
                    found = true;
                    LOGGER.debug("[ContainerUtils] 合并物品: {} x {} -> 新数量 {}", 
                        item.getItem(), item.getCount(), mergedItem.getCount());
                    break;
                }
            }
            
            if (!found) {
                // 不能堆叠，添加为新物品
                merged.add(item.copy());
            }
        }
        
        return merged;
    }

    /**
     * 统计容器中特定物品的数量
     * @param level 世界
     * @param pos 容器位置
     * @param itemStack 要统计的物品
     * @return 物品数量
     */
    public static int countItem(Level level, BlockPos pos, ItemStack itemStack) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return 0;
        }
        
        int count = 0;
        
        // 优先使用 Container 接口
        if (blockEntity instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (ItemHandlerHelper.canItemStacksStack(stack, itemStack)) {
                    count += stack.getCount();
                }
            }
            return count;
        }
        
        // 使用 IItemHandler Capability
        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            IItemHandler handler = cap.get();
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (ItemHandlerHelper.canItemStacksStack(stack, itemStack)) {
                    count += stack.getCount();
                }
            }
        }
        
        return count;
    }

    /**
     * 检查容器是否能够完整插入指定物品（支持叠加到已有槽位）
     */
    public static boolean canInsertItem(Level level, BlockPos pos, ItemStack itemStack) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved() || itemStack.isEmpty()) {
            return false;
        }

        int remaining = itemStack.getCount();

        if (blockEntity instanceof Container container) {
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    int slotLimit = Math.min(container.getMaxStackSize(), itemStack.getMaxStackSize());
                    remaining -= Math.min(remaining, slotLimit);
                } else if (ItemHandlerHelper.canItemStacksStack(stack, itemStack)) {
                    int slotLimit = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
                    int availableSpace = Math.max(0, slotLimit - stack.getCount());
                    remaining -= Math.min(remaining, availableSpace);
                }
            }
            return remaining <= 0;
        }

        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            IItemHandler handler = cap.get();
            ItemStack remainingStack = itemStack.copy();
            for (int i = 0; i < handler.getSlots() && !remainingStack.isEmpty(); i++) {
                remainingStack = handler.insertItem(i, remainingStack, true);
            }
            return remainingStack.isEmpty();
        }

        return false;
    }

    /**
     * 获取容器大小
     * @param level 世界
     * @param pos 容器位置
     * @return 槽位数量，如果不是容器返回0
     */
    public static int getContainerSize(Level level, BlockPos pos) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return 0;
        }
        
        if (blockEntity instanceof Container container) {
            return container.getContainerSize();
        }
        
        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        return cap.map(IItemHandler::getSlots).orElse(0);
    }

    /**
     * 检查容器是否有空槽位
     * @param level 世界
     * @param pos 容器位置
     * @return 如果有空槽位返回true
     */
    public static boolean hasEmptySlot(Level level, BlockPos pos) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }
        
        if (blockEntity instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (container.getItem(i).isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        
        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            IItemHandler handler = cap.get();
            for (int i = 0; i < handler.getSlots(); i++) {
                if (handler.getStackInSlot(i).isEmpty()) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * 包装类：统一访问容器中的单个槽位
     */
    public static class SlotAccessor {
        private final Container container;
        private final IItemHandler itemHandler;
        private final int slot;
        
        public SlotAccessor(Container container, int slot) {
            this.container = container;
            this.itemHandler = null;
            this.slot = slot;
        }
        
        public SlotAccessor(IItemHandler itemHandler, int slot) {
            this.container = null;
            this.itemHandler = itemHandler;
            this.slot = slot;
        }
        
        public ItemStack getStack() {
            if (container != null) {
                return container.getItem(slot);
            }
            return itemHandler.getStackInSlot(slot);
        }
        
        public void setStack(ItemStack stack) {
            if (container != null) {
                container.setItem(slot, Objects.requireNonNull(stack));
            }
            // IItemHandler 不支持直接设置，需要使用 insert/extract
        }
        
        public int getSlotLimit() {
            if (container != null) {
                return container.getMaxStackSize();
            }
            return itemHandler.getSlotLimit(slot);
        }
    }

    /**
     * 性能优化：直接查找并消耗建筑材料，避免复制整个容器内容
     * @param level 世界
     * @param pos 容器位置
     * @param state 要放置的方块状态
     * @return 是否成功消耗材料
     */
    public static boolean findAndConsumeBuildingMaterial(Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        BlockPos safePos = Objects.requireNonNull(pos);
        BlockEntity blockEntity = level.getBlockEntity(safePos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }

        // 使用材料管理器获取需要的物品类型
        java.util.function.Predicate<ItemStack> canUse = stack -> 
            com.xiaoliang.simukraft.utils.MaterialManager.canUseItemForBlock(stack, state);

        // 优先使用 IItemHandler Capability
        Optional<IItemHandler> cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            IItemHandler handler = cap.get();
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (!stack.isEmpty() && canUse.test(stack)) {
                    // 尝试消耗1个物品
                    ItemStack extracted = handler.extractItem(i, 1, false);
                    if (!extracted.isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        // 回退到 Container 接口
        if (blockEntity instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && canUse.test(stack)) {
                    // 消耗1个物品
                    ItemStack toConsume = stack.copy();
                    toConsume.setCount(1);
                    if (consumeItem(level, pos, toConsume)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
