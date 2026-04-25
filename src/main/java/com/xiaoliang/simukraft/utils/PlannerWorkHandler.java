package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.planning.PlanningTask;
import com.xiaoliang.simukraft.planning.PlanningTaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 规划师工作处理器
 * 处理规划师的拆除、替换、填充任务
 */
@SuppressWarnings("null")
public class PlannerWorkHandler {

    private final CustomEntity npc;
    private final UUID npcId;
    private PlanningTask currentTask;
    private int workTimer = 0;
    private BlockPos currentTargetPos;

    private long lastWarningTime = 0;

    public PlannerWorkHandler(CustomEntity npc) {
        this.npc = npc;
        this.npcId = npc.getUUID();
    }

    private int restCheckTimer = 0;
    /**
     * 每tick调用，处理工作逻辑
     */
    public void tick(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // 检查并恢复NPC工作状态（学习FarmerDailyWorkHandler的模式）
        restorePlannerWorkState();

        // 检查NPC是否需要休息（学习ConstructionTask的格式）
        restCheckTimer++;
        if (restCheckTimer >= ServerConfig.getPlannerRestCheckInterval()) {
            restCheckTimer = 0;
            checkAndHandleRest(serverLevel);
        }

        // 如果NPC正在休息，暂停任务处理
        if (isNPCResting()) {
            return;
        }

        // 获取当前任务
        if (currentTask == null || currentTask.getStatus() != PlanningTask.TaskStatus.IN_PROGRESS) {
            currentTask = PlanningTaskManager.get(level).getActiveTaskByNpc(npcId);
            if (currentTask == null) return;
        }

        // 根据任务类型处理
        switch (currentTask.getType()) {
            case REMOVE -> processRemoveTask(serverLevel);
            case REPLACE -> processReplaceTask(serverLevel);
            case FILL -> processFillTask(serverLevel);
        }
    }

    /**
     * 恢复规划师工作状态（学习FarmerDailyWorkHandler的模式）
     * 确保NPC休息后或传送后能正常工作
     */
    private void restorePlannerWorkState() {
        // 检查NPC当前状态，如果是空闲，强制恢复为工作中
        if (npc.getWorkStatus() == com.xiaoliang.simukraft.entity.WorkStatus.IDLE) {
            npc.setWorkStatus(com.xiaoliang.simukraft.entity.WorkStatus.WORKING);
            npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
            npc.setWorking(true);
        }
    }

    /**
     * 检查并处理NPC休息状态
     */
    private void checkAndHandleRest(ServerLevel level) {
    }

    /**
     * 检查NPC是否正在休息
     */
    private boolean isNPCResting() {
        return npc.getWorkSubState() == com.xiaoliang.simukraft.entity.WorkSubState.RESTING;
    }

    /**
     * 处理拆除任务
     */
    private void processRemoveTask(ServerLevel level) {
        // 检查是否到达工作间隔
        int requiredTicks = getRemoveSpeedByLevel();
        workTimer++;

        if (workTimer < requiredTicks) {
            // 显示工作进度粒子效果
            if (currentTargetPos != null && workTimer % 10 == 0) {
                showWorkingParticles(level, currentTargetPos);
            }
            return;
        }

        workTimer = 0;

        // 获取下一个要拆除的方块（跳过空气和黑名单方块）
        BlockPos targetPos = null;
        while ((targetPos = currentTask.getNextBlock()) != null) {
            BlockState state = level.getBlockState(targetPos);
            if (state.isAir()) {
                // 是空气，标记完成并继续下一个
                currentTask.markCurrentBlockComplete();
                continue;
            }
            // 检查黑名单
            Block block = state.getBlock();
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
            if (blockId != null && ServerConfig.isBlockBlacklistedForPlanning(blockId.toString())) {
                currentTask.markCurrentBlockComplete();
                continue;
            }
            break;
        }

        if (targetPos == null) {
            // 任务完成
            completeTask(level);
            return;
        }

        currentTargetPos = targetPos;

        // 获取方块掉落物
        BlockState blockState = level.getBlockState(targetPos);
        List<ItemStack> drops = Block.getDrops(Objects.requireNonNull(blockState), level, targetPos, null);

        // 拆除方块（根据配置决定是否掉落物品）
        // 如果 shouldDropItemsOnRemove 为 false，则直接删除方块不掉落任何物品
        // 如果为 true，则根据 shouldStoreItemsInChest 决定是存入箱子还是掉落在地上
        if (!ServerConfig.shouldDropItemsOnRemove()) {
            // 不掉落物品，直接删除方块
            level.destroyBlock(targetPos, false);
        } else {
            // 掉落物品，先获取掉落物再拆除方块
            level.destroyBlock(targetPos, false);

            // 将掉落物存入附近箱子（以建筑盒位置为中心搜索）
            if (!drops.isEmpty() && ServerConfig.shouldStoreItemsInChest()) {
                BlockPos buildBoxPos = currentTask.getBuildBoxPos();
                storeItemsInNearbyChest(level, buildBoxPos, drops);
            } else if (!drops.isEmpty()) {
                // 不存入箱子，直接掉落在原地
                for (ItemStack item : drops) {
                    if (!item.isEmpty()) {
                        Block.popResource(level, targetPos, item);
                    }
                }
            }
        }

        // 增加NPC经验
        if (level.getServer() != null && ServerConfig.isPlannerXpGainEnabled()) {
            NPCDataManager.addXp(level.getServer(), npcId, ServerConfig.getPlannerXpPerBlock());
        }

        // 标记当前方块完成
        currentTask.markCurrentBlockComplete();

        // 保存任务进度到JSON
        if (level.getServer() != null) {
            PlannerDailyWorkHandler.savePlanningTask(level.getServer(), currentTask);
        }

        // 检查任务是否完成
        if (currentTask.getStatus() == PlanningTask.TaskStatus.COMPLETED) {
            completeTask(level);
        }
    }

    /**
     * 处理替换任务
     */
    private void processReplaceTask(ServerLevel level) {
        // 检查是否到达工作间隔
        int requiredTicks = getReplaceSpeedByLevel();
        workTimer++;

        if (workTimer < requiredTicks) {
            // 显示工作进度粒子效果
            if (currentTargetPos != null && workTimer % 10 == 0) {
                showWorkingParticles(level, currentTargetPos);
            }
            return;
        }

        workTimer = 0;

        // 获取替换映射
        Map<String, String> replacementMap = currentTask.getReplacementMap();
        if (replacementMap == null || replacementMap.isEmpty()) {
            Simukraft.LOGGER.error("[PlannerWorkHandler] 替换任务没有映射关系，取消任务");
            currentTask.setStatus(PlanningTask.TaskStatus.CANCELLED);
            completeTask(level);
            return;
        }

        // 获取下一个要替换的方块
        BlockPos targetPos = null;
        BlockState currentState = null;
        Block currentBlock = null;
        String currentBlockId = null;

        while ((targetPos = currentTask.getNextBlock()) != null) {
            currentState = level.getBlockState(targetPos);
            currentBlock = currentState.getBlock();
            currentBlockId = currentBlock.getDescriptionId();

            // 检查黑名单
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(currentBlock);
            if (blockId != null && ServerConfig.isBlockBlacklistedForPlanning(blockId.toString())) {
                currentTask.markCurrentBlockComplete();
                continue;
            }

            // 检查这个方块是否在替换映射中
            if (replacementMap.containsKey(currentBlockId)) {
                break; // 找到需要替换的方块
            }

            // 不需要替换，标记完成并继续下一个
            currentTask.markCurrentBlockComplete();
        }

        if (targetPos == null) {
            // 任务完成
            completeTask(level);
            return;
        }

        currentTargetPos = targetPos;

        // 获取目标方块ID
        String targetBlockId = replacementMap.get(currentBlockId);
        if (targetBlockId == null) {
            currentTask.markCurrentBlockComplete();
            return;
        }

        // 解析目标方块
        String parsedId = targetBlockId.replace("block.", "").replaceFirst("\\.", ":");
        net.minecraft.resources.ResourceLocation resourceLocation =
                net.minecraft.resources.ResourceLocation.tryParse(Objects.requireNonNull(parsedId));
        Block targetBlock = null;
        if (resourceLocation != null) {
            targetBlock = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(resourceLocation);
        }
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            Simukraft.LOGGER.error("[PlannerWorkHandler] 无法解析目标方块: {} -> {}", targetBlockId, parsedId);
            currentTask.markCurrentBlockComplete();
            return;
        }

        // 从附近箱子中消耗目标方块材料
        BlockPos buildBoxPos = currentTask.getBuildBoxPos();
        ItemStack requiredItem = new ItemStack(Objects.requireNonNull(targetBlock.asItem()));
        boolean consumed = consumeItemFromNearbyChest(level, buildBoxPos, requiredItem);

        if (!consumed) {
            // 材料不足，发送消息给市长并暂停任务
            net.minecraft.network.chat.Component blockNameComp = BlockNameTranslator.getBlockComponent(targetBlock);
            sendMaterialInsufficientMessage(level, blockNameComp);
            return;
        }

        // 获取原方块掉落物
        List<ItemStack> drops = Block.getDrops(
                Objects.requireNonNull(currentState),
                Objects.requireNonNull(level),
                targetPos,
                null
        );

        // 拆除原方块
        level.destroyBlock(targetPos, false);

        // 放置新方块
        BlockState newState = Objects.requireNonNull(targetBlock.defaultBlockState());
        level.setBlock(targetPos, newState, 3);

        // 根据配置处理原方块掉落物
        if (ServerConfig.shouldDropItemsOnRemove() && !drops.isEmpty()) {
            // 将掉落物存入附近箱子或掉落在地上
            if (ServerConfig.shouldStoreItemsInChest()) {
                storeItemsInNearbyChest(level, buildBoxPos, drops);
            } else {
                // 不存入箱子，直接掉落在原地
                for (ItemStack item : drops) {
                    if (!item.isEmpty()) {
                        Block.popResource(level, targetPos, item);
                    }
                }
            }
        }
        // 如果 shouldDropItemsOnRemove 为 false，则不掉落任何物品（直接删除）

        // 增加NPC经验
        if (level.getServer() != null && ServerConfig.isPlannerXpGainEnabled()) {
            NPCDataManager.addXp(level.getServer(), npcId, ServerConfig.getPlannerXpPerBlock());
        }

        // 标记当前方块完成
        currentTask.markCurrentBlockComplete();

        // 保存任务进度到JSON
        if (level.getServer() != null) {
            PlannerDailyWorkHandler.savePlanningTask(level.getServer(), currentTask);
        }

        // 检查任务是否完成
        if (currentTask.getStatus() == PlanningTask.TaskStatus.COMPLETED) {
            completeTask(level);
        }
    }

    /**
     * 处理填充任务
     */
    private void processFillTask(ServerLevel level) {
        // 检查是否到达工作间隔
        int requiredTicks = getFillSpeedByLevel();
        workTimer++;

        if (workTimer < requiredTicks) {
            // 显示工作进度粒子效果
            if (currentTargetPos != null && workTimer % 10 == 0) {
                showWorkingParticles(level, currentTargetPos);
            }
            return;
        }

        workTimer = 0;

        // 获取目标方块ID
        String targetBlockId = currentTask.getTargetBlockId();
        if (targetBlockId == null || targetBlockId.isEmpty()) {
            Simukraft.LOGGER.error("[PlannerWorkHandler] 填充任务没有目标方块，取消任务");
            currentTask.setStatus(PlanningTask.TaskStatus.CANCELLED);
            completeTask(level);
            return;
        }

        // 解析目标方块
        String parsedId = targetBlockId.replace("block.", "").replaceFirst("\\.", ":");
        net.minecraft.resources.ResourceLocation resourceLocation =
                net.minecraft.resources.ResourceLocation.tryParse(Objects.requireNonNull(parsedId));
        Block targetBlock = null;
        if (resourceLocation != null) {
            targetBlock = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(resourceLocation);
        }
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            Simukraft.LOGGER.error("[PlannerWorkHandler] 无法解析填充目标方块: {} -> {}", targetBlockId, parsedId);
            currentTask.setStatus(PlanningTask.TaskStatus.CANCELLED);
            completeTask(level);
            return;
        }

        // 检查目标方块是否在黑名单中
        ResourceLocation targetBlockIdLoc = ForgeRegistries.BLOCKS.getKey(targetBlock);
        if (targetBlockIdLoc != null && ServerConfig.isBlockBlacklistedForPlanning(targetBlockIdLoc.toString())) {
            if (ServerConfig.shouldLogSkippedBlocks()) {
                Simukraft.LOGGER.error("[PlannerWorkHandler] 填充目标方块在黑名单中，取消任务: {}", targetBlockIdLoc);
            }
            currentTask.setStatus(PlanningTask.TaskStatus.CANCELLED);
            completeTask(level);
            return;
        }

        // 获取下一个要填充的位置（填充空气或可替换的植物）
        BlockPos targetPos = null;
        BlockState targetState = null;
        while ((targetPos = currentTask.getNextBlock()) != null) {
            targetState = level.getBlockState(targetPos);
            if (targetState.isAir() || isReplaceablePlant(targetState)) {
                break; // 找到空气或植物位置，需要填充
            }
            // 不是空气也不是植物，标记完成并继续下一个
            currentTask.markCurrentBlockComplete();
        }

        if (targetPos == null) {
            // 任务完成
            completeTask(level);
            return;
        }

        currentTargetPos = targetPos;

        // 从附近箱子中消耗方块
        BlockPos buildBoxPos = currentTask.getBuildBoxPos();
        ItemStack requiredItem = new ItemStack(Objects.requireNonNull(targetBlock.asItem()));
        boolean consumed = consumeItemFromNearbyChest(level, buildBoxPos, requiredItem);

        if (!consumed) {
            // 材料不足，发送消息给市长
            net.minecraft.network.chat.Component blockNameComp = BlockNameTranslator.getBlockComponent(targetBlock);
            sendMaterialInsufficientMessage(level, blockNameComp);
            // 暂停任务，等待材料
            return;
        }

        // 如果是植物，先清除（不掉落物品）
        if (targetState != null && isReplaceablePlant(targetState)) {
            level.destroyBlock(targetPos, false);
        }

        // 放置方块
        BlockState newState = Objects.requireNonNull(targetBlock.defaultBlockState());
        level.setBlock(targetPos, newState, 3);

        // 增加NPC经验
        if (level.getServer() != null && ServerConfig.isPlannerXpGainEnabled()) {
            NPCDataManager.addXp(level.getServer(), npcId, ServerConfig.getPlannerXpPerBlock());
        }

        // 标记当前方块完成
        currentTask.markCurrentBlockComplete();

        // 保存任务进度到JSON
        if (level.getServer() != null) {
            PlannerDailyWorkHandler.savePlanningTask(level.getServer(), currentTask);
        }

        // 检查任务是否完成
        if (currentTask.getStatus() == PlanningTask.TaskStatus.COMPLETED) {
            completeTask(level);
        }
    }

    // 性能优化：缓存容器位置和检查时间
    private final java.util.List<BlockPos> cachedContainerPositions = new java.util.ArrayList<>();
    private long lastContainerCheckTime = 0;
    private static final long CONTAINER_CACHE_DURATION_MS = 30000; // 缓存30秒

    /**
     * 从附近箱子中消耗物品（支持Container接口和IItemHandler Capability）
     * 只搜索建筑盒六个面紧贴的容器，支持大箱子
     * 性能优化：使用缓存的容器位置，避免每tick重复检查
     */
    private boolean consumeItemFromNearbyChest(ServerLevel level, BlockPos sourcePos, ItemStack requiredItem) {
        // 性能优化：更新缓存的容器位置
        updateContainerCache(level, sourcePos);

        // 使用缓存的容器位置
        for (BlockPos pos : cachedContainerPositions) {
            // 检查是否有足够物品并直接消耗
            if (tryConsumeFromContainer(level, pos, requiredItem)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 性能优化：更新容器位置缓存
     */
    private void updateContainerCache(ServerLevel level, BlockPos sourcePos) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastContainerCheckTime < CONTAINER_CACHE_DURATION_MS && !cachedContainerPositions.isEmpty()) {
            return; // 缓存未过期，直接使用
        }

        cachedContainerPositions.clear();
        Direction[] directions = new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

        for (Direction dir : directions) {
            BlockPos pos = sourcePos.relative(dir);
            if (ContainerUtils.isContainer(level, pos)) {
                cachedContainerPositions.add(pos);
                // 检查是否是大箱子，添加另一半
                BlockPos otherHalf = getOtherChestHalf(level, pos);
                if (otherHalf != null && !cachedContainerPositions.contains(otherHalf)) {
                    cachedContainerPositions.add(otherHalf);
                }
            }
        }
        lastContainerCheckTime = currentTime;
    }

    /**
     * 性能优化：尝试从单个容器消耗物品
     */
    private boolean tryConsumeFromContainer(ServerLevel level, BlockPos pos, ItemStack requiredItem) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }

        // 优先使用 IItemHandler Capability
        var cap = blockEntity.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            net.minecraftforge.items.IItemHandler handler = cap.get();
            for (int i = 0; i < handler.getSlots(); i++) {
                net.minecraft.world.item.ItemStack stack = handler.getStackInSlot(i);
                if (!stack.isEmpty() && net.minecraftforge.items.ItemHandlerHelper.canItemStacksStack(stack, requiredItem)) {
                    net.minecraft.world.item.ItemStack extracted = handler.extractItem(i, 1, false);
                    if (!extracted.isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        // 回退到 Container 接口
        if (blockEntity instanceof net.minecraft.world.Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && net.minecraftforge.items.ItemHandlerHelper.canItemStacksStack(stack, requiredItem)) {
                    net.minecraft.world.item.ItemStack toConsume = requiredItem.copy();
                    toConsume.setCount(1);
                    if (ContainerUtils.consumeItem(level, pos, toConsume)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 发送材料不足消息给市长（带冷却时间）
     */
    private void sendMaterialInsufficientMessage(ServerLevel level, net.minecraft.network.chat.Component blockNameComponent) {
        // 检查冷却时间
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastWarningTime < ServerConfig.getPlannerWarningCooldownMs()) {
            return; // 冷却中，不发送消息
        }
        lastWarningTime = currentTime;

        // 获取城市ID
        UUID cityId = npc.getCityId();
        if (cityId == null) return;

        // 获取任务类型翻译键
        String taskTypeKey = switch (currentTask.getType()) {
            case REMOVE -> "gui.planning.task.remove";
            case REPLACE -> "gui.planning.task.replace";
            case FILL -> "gui.planning.task.fill";
        };

        net.minecraft.network.chat.Component content = net.minecraft.network.chat.Component.translatable(
                "message.simukraft.planning.need_materials", taskTypeKey, blockNameComponent, 5);

        CityMessageUtils.sendToMayorViaService(level.getServer(), cityId,
                net.minecraft.network.chat.Component.translatable("notify.title.planning"), content,
                com.xiaoliang.simukraft.notification.MessageCategory.CONSTRUCTION);
    }

    /**
     * 完成任务
     */
    private void completeTask(ServerLevel level) {
        UUID taskId = currentTask.getTaskId();
        PlanningTaskManager.get(level).completeTask(taskId);

        // 从JSON持久化存储中移除任务
        if (level.getServer() != null) {
            PlannerDailyWorkHandler.removePlanningTask(level.getServer(), taskId);
        }

        // 发送完成消息给市长
        sendCompletionMessage(level);

        currentTask = null;
        currentTargetPos = null;
        workTimer = 0;
    }

    /**
     * 发送任务完成消息
     */
    private void sendCompletionMessage(ServerLevel level) {
        // 获取建筑盒位置
        // 获取城市ID
        UUID cityId = npc.getCityId();
        if (cityId == null) return;

        net.minecraft.network.chat.Component taskTypeComp = switch (currentTask.getType()) {
            case REMOVE -> net.minecraft.network.chat.Component.translatable("message.simukraft.planning.task_type.remove");
            case REPLACE -> net.minecraft.network.chat.Component.translatable("message.simukraft.planning.task_type.replace");
            case FILL -> net.minecraft.network.chat.Component.translatable("message.simukraft.planning.task_type.fill");
        };

        net.minecraft.network.chat.Component content = net.minecraft.network.chat.Component.translatable(
                "message.simukraft.planning.completed",
                npc.getName().getString(), taskTypeComp, currentTask.getTotalBlocks());

        CityMessageUtils.sendToMayorViaService(level.getServer(), cityId,
                net.minecraft.network.chat.Component.translatable("notify.title.planning"), content,
                com.xiaoliang.simukraft.notification.MessageCategory.CONSTRUCTION);
    }

    /**
     * 显示工作粒子效果
     */
    private void showWorkingParticles(ServerLevel level, BlockPos pos) {
        // 在方块位置显示破坏粒子
        level.sendParticles(
            Objects.requireNonNull(net.minecraft.core.particles.ParticleTypes.CRIT),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            3, 0.2, 0.2, 0.2, 0.0
        );
    }

    /**
     * 将物品存入附近的容器
     * 支持：箱子、陷阱箱、木桶、潜影盒等所有实现 Container 接口或 IItemHandler Capability 的方块
     * 性能优化：使用缓存的容器位置，避免每tick重复检查
     */
    private void storeItemsInNearbyChest(ServerLevel level, BlockPos sourcePos, List<ItemStack> items) {
        // 复制物品列表，避免修改原始列表
        List<ItemStack> remainingItems = new ArrayList<>();
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                remainingItems.add(item.copy());
            }
        }

        if (remainingItems.isEmpty()) {
            return;
        }

        // 性能优化：使用缓存的容器位置
        updateContainerCache(level, sourcePos);

        // 使用缓存的容器位置
        for (BlockPos pos : cachedContainerPositions) {
            // 尝试将物品存入容器
            if (tryInsertToContainer(level, pos, remainingItems)) {
                return; // 全部存入
            }
            
            // 检查是否全部存入
            boolean allStored = remainingItems.stream().allMatch(ItemStack::isEmpty);
            if (allStored) {
                return;
            }
        }

        // 如果没有找到容器或容器已满，物品掉落在原地
        int droppedCount = 0;
        for (ItemStack item : remainingItems) {
            if (!item.isEmpty()) {
                Block.popResource(Objects.requireNonNull(level), sourcePos, item);
                droppedCount++;
            }
        }

        if (droppedCount > 0) {
            // 发送箱子已满提示（带冷却）
            sendChestFullMessage(level);
        }
    }

    /**
     * 性能优化：尝试将物品存入单个容器
     * @return 如果所有物品都已存入返回true
     */
    private boolean tryInsertToContainer(ServerLevel level, BlockPos pos, List<ItemStack> items) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }

        // 优先使用 IItemHandler Capability
        var cap = blockEntity.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            net.minecraftforge.items.IItemHandler handler = cap.get();
            for (ItemStack item : items) {
                if (item.isEmpty()) continue;

                // 尝试插入到容器的空槽位
                for (int i = 0; i < handler.getSlots() && !item.isEmpty(); i++) {
                    net.minecraft.world.item.ItemStack remaining = handler.insertItem(i, item, false);
                    item.setCount(remaining.getCount());
                }
            }
            return items.stream().allMatch(ItemStack::isEmpty);
        }

        // 回退到 Container 接口
        if (blockEntity instanceof net.minecraft.world.Container container) {
            for (ItemStack item : items) {
                if (item.isEmpty()) continue;

                for (int i = 0; i < container.getContainerSize() && !item.isEmpty(); i++) {
                    net.minecraft.world.item.ItemStack slotStack = container.getItem(i);
                    if (slotStack.isEmpty()) {
                        // 空槽位，直接放入
                        container.setItem(i, item.copy());
                        item.setCount(0);
                    } else if (net.minecraftforge.items.ItemHandlerHelper.canItemStacksStack(slotStack, item)) {
                        // 可以堆叠
                        int maxStack = Math.min(slotStack.getMaxStackSize(), container.getMaxStackSize());
                        int canAdd = maxStack - slotStack.getCount();
                        if (canAdd > 0) {
                            int toAdd = Math.min(canAdd, item.getCount());
                            slotStack.grow(toAdd);
                            item.shrink(toAdd);
                        }
                    }
                }
            }
            return items.stream().allMatch(ItemStack::isEmpty);
        }
        return false;
    }

    /**
     * 获取大箱子的另一半位置
     * @param level 世界
     * @param pos 箱子位置
     * @return 另一半位置，如果不是大箱子则返回null
     */
    @javax.annotation.Nullable
    private BlockPos getOtherChestHalf(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) {
            return null;
        }

        // 检查四个水平方向是否有相邻的箱子
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = pos.relative(dir);
            net.minecraft.world.level.block.state.BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                // 检查是否是同一个大箱子的一部分
                if (state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE) &&
                    neighborState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
                    return neighborPos;
                }
            }
        }
        return null;
    }

    /**
     * 发送箱子已满消息给市长（带冷却时间）
     */
    private void sendChestFullMessage(ServerLevel level) {
        // 检查冷却时间
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastWarningTime < ServerConfig.getPlannerWarningCooldownMs()) {
            return; // 冷却中，不发送消息
        }
        lastWarningTime = currentTime;

        // 获取城市ID
        UUID cityId = npc.getCityId();
        if (cityId == null) return;

        // 获取任务类型名称
        net.minecraft.network.chat.Component taskTypeComp = currentTask != null ? switch (currentTask.getType()) {
            case REMOVE -> net.minecraft.network.chat.Component.translatable("message.simukraft.planning.task_type.remove");
            case REPLACE -> net.minecraft.network.chat.Component.translatable("message.simukraft.planning.task_type.replace");
            case FILL -> net.minecraft.network.chat.Component.translatable("message.simukraft.planning.task_type.fill");
        } : net.minecraft.network.chat.Component.translatable("message.simukraft.planning.task_type.plan");

        net.minecraft.network.chat.Component content = net.minecraft.network.chat.Component.translatable(
                "message.simukraft.planning.chest_full", taskTypeComp);

        CityMessageUtils.sendToMayorViaService(level.getServer(), cityId,
                net.minecraft.network.chat.Component.translatable("notify.title.planning"), content,
                com.xiaoliang.simukraft.notification.MessageCategory.CONSTRUCTION);
    }

    /**
     * 根据NPC等级获取拆除速度（tick/方块）
     */
    private int getRemoveSpeedByLevel() {
        int level = 1;
        if (npc.level() instanceof ServerLevel serverLevel) {
            level = NPCDataManager.getNPCLevel(serverLevel.getServer(), npcId);
        }
        return ServerConfig.getPlannerRemoveSpeed(level);
    }

    /**
     * 根据NPC等级获取替换速度（tick/方块）
     */
    private int getReplaceSpeedByLevel() {
        int level = 1;
        if (npc.level() instanceof ServerLevel serverLevel) {
            level = NPCDataManager.getNPCLevel(serverLevel.getServer(), npcId);
        }
        return ServerConfig.getPlannerReplaceSpeed(level);
    }

    /**
     * 根据NPC等级获取填充速度（tick/方块）
     */
    private int getFillSpeedByLevel() {
        int level = 1;
        if (npc.level() instanceof ServerLevel serverLevel) {
            level = NPCDataManager.getNPCLevel(serverLevel.getServer(), npcId);
        }
        return ServerConfig.getPlannerFillSpeed(level);
    }

    /**
     * 检查是否正在工作
     */
    public boolean isWorking() {
        return currentTask != null && currentTask.getStatus() == PlanningTask.TaskStatus.IN_PROGRESS;
    }

    /**
     * 检查是否有活跃任务（用于挥手动画控制）
     */
    public boolean hasActiveTask() {
        return currentTask != null && currentTask.getStatus() == PlanningTask.TaskStatus.IN_PROGRESS;
    }

    /**
     * 获取当前任务进度
     */
    public float getProgress() {
        if (currentTask == null) return 0f;
        return currentTask.getProgress();
    }

    /**
     * 获取当前任务
     */
    public PlanningTask getCurrentTask() {
        return currentTask;
    }

    /**
     * 检查方块是否是可替换的植物（杂草、花等）
     */
    private boolean isReplaceablePlant(BlockState state) {
        Block block = state.getBlock();
        return block == net.minecraft.world.level.block.Blocks.GRASS ||
               block == net.minecraft.world.level.block.Blocks.TALL_GRASS ||
               block == net.minecraft.world.level.block.Blocks.FERN ||
               block == net.minecraft.world.level.block.Blocks.LARGE_FERN ||
               block == net.minecraft.world.level.block.Blocks.DEAD_BUSH ||
               block == net.minecraft.world.level.block.Blocks.DANDELION ||
               block == net.minecraft.world.level.block.Blocks.POPPY ||
               block == net.minecraft.world.level.block.Blocks.BLUE_ORCHID ||
               block == net.minecraft.world.level.block.Blocks.ALLIUM ||
               block == net.minecraft.world.level.block.Blocks.AZURE_BLUET ||
               block == net.minecraft.world.level.block.Blocks.RED_TULIP ||
               block == net.minecraft.world.level.block.Blocks.ORANGE_TULIP ||
               block == net.minecraft.world.level.block.Blocks.WHITE_TULIP ||
               block == net.minecraft.world.level.block.Blocks.PINK_TULIP ||
               block == net.minecraft.world.level.block.Blocks.OXEYE_DAISY ||
               block == net.minecraft.world.level.block.Blocks.CORNFLOWER ||
               block == net.minecraft.world.level.block.Blocks.LILY_OF_THE_VALLEY ||
               block == net.minecraft.world.level.block.Blocks.WITHER_ROSE ||
               block == net.minecraft.world.level.block.Blocks.SUNFLOWER ||
               block == net.minecraft.world.level.block.Blocks.LILAC ||
               block == net.minecraft.world.level.block.Blocks.ROSE_BUSH ||
               block == net.minecraft.world.level.block.Blocks.PEONY ||
               block == net.minecraft.world.level.block.Blocks.TALL_SEAGRASS ||
               block == net.minecraft.world.level.block.Blocks.SEAGRASS ||
               block == net.minecraft.world.level.block.Blocks.KELP ||
               block == net.minecraft.world.level.block.Blocks.KELP_PLANT;
    }
}
