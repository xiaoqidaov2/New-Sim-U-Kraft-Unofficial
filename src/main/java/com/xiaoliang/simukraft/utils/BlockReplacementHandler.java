package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 方块替换处理器
 * 处理选区内方块的替换逻辑
 */
public class BlockReplacementHandler {
    // 每个方块替换的费用（元）
    private static final double COST_PER_BLOCK = 0.04;

    /**
     * 处理方块替换请求
     *
     * @param player         执行替换的玩家
     * @param level          服务器世界
     * @param selectionStart 选区起点
     * @param selectionEnd   选区终点
     * @param chestPos       箱子位置
     * @param replacementMap 替换映射（原方块 -> 目标方块）
     */
    public static void handleReplacement(ServerPlayer player, ServerLevel level,
                                         BlockPos selectionStart, BlockPos selectionEnd,
                                         BlockPos chestPos, Map<Block, Block> replacementMap) {
        // 获取容器实体（支持所有实现Container接口的方块，包括大箱子）
        BlockPos safeChestPos = Objects.requireNonNull(chestPos);
        BlockState state = level.getBlockState(safeChestPos);
        BlockEntity blockEntity = level.getBlockEntity(safeChestPos);
        
        Container chest = null;
        
        // 如果是箱子，使用ChestBlock.getContainer来获取完整容器（支持大箱子）
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            chest = ChestBlock.getContainer(chestBlock, state, level, safeChestPos, true);
        } else if (blockEntity instanceof Container container) {
            chest = container;
        }
        
        if (chest == null) {
            player.sendSystemMessage(Objects.requireNonNull(
                    Component.translatable("message.simukraft.block_replacement.chest_not_found")));
            return;
        }

        // 统计选区内需要替换的方块
        Map<Block, Integer> blocksToReplace = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                Objects.requireNonNull(selectionStart),
                Objects.requireNonNull(selectionEnd))) {
            BlockPos safePos = Objects.requireNonNull(pos);
            BlockState blockState = level.getBlockState(safePos);
            Block block = blockState.getBlock();
            if (replacementMap.containsKey(block)) {
                blocksToReplace.put(block, blocksToReplace.getOrDefault(block, 0) + 1);
            }
        }

        if (blocksToReplace.isEmpty()) {
            player.sendSystemMessage(Objects.requireNonNull(
                    Component.translatable("message.simukraft.block_replacement.no_blocks_to_replace")));
            return;
        }

        // 检查箱子中是否有足够的材料
        Map<Block, Integer> requiredMaterials = new HashMap<>();
        for (Map.Entry<Block, Block> entry : replacementMap.entrySet()) {
            Block fromBlock = entry.getKey();
            Block toBlock = entry.getValue();
            int count = blocksToReplace.getOrDefault(fromBlock, 0);
            if (count > 0) {
                requiredMaterials.put(toBlock, count);
            }
        }

        // 检查材料是否充足
        Map<Block, Integer> insufficientMaterials = new HashMap<>();
        for (Map.Entry<Block, Integer> entry : requiredMaterials.entrySet()) {
            Block block = entry.getKey();
            int required = entry.getValue();
            int available = countItemsInChest(chest, block);
            if (available < required) {
                insufficientMaterials.put(block, required - available);
            }
        }

        // 如果有材料不足，发送消息并停止
        if (!insufficientMaterials.isEmpty()) {
            // 构建缺少的材料列表
            StringBuilder materialsList = new StringBuilder();
            for (Map.Entry<Block, Integer> entry : insufficientMaterials.entrySet()) {
                String blockChineseName = BlockNameTranslator.getBlockName(entry.getKey());
                if (materialsList.length() > 0) {
                    materialsList.append(", ");
                }
                materialsList.append(blockChineseName).append(" x").append(entry.getValue());
            }
            player.sendSystemMessage(Objects.requireNonNull(
                    Component.translatable(
                            "message.simukraft.block_replacement.insufficient_materials",
                            materialsList.toString()
                    )));
            return;
        }

        // 计算总费用
        int totalBlocksToReplace = blocksToReplace.values().stream().mapToInt(Integer::intValue).sum();
        double totalCost = totalBlocksToReplace * COST_PER_BLOCK;
        totalCost = roundToTwoDecimalPlaces(totalCost);

        // 获取玩家所在城市并检查资金
        CityData cityData = CityData.get(level);
        String playerName = player.getName().getString();
        UUID cityId = cityData.getPlayerCityId(playerName);
        if (cityId == null) {
            player.sendSystemMessage(Objects.requireNonNull(
                    Component.translatable("message.simukraft.block_replacement.no_city")));
            return;
        }

        CityData.CityInfo cityInfo = cityData.getCity(cityId);
        if (cityInfo == null) {
            player.sendSystemMessage(Objects.requireNonNull(
                    Component.translatable("message.simukraft.block_replacement.city_not_found")));
            return;
        }

        // 创造模式下跳过资金检查
        boolean isCreativeMode = ServerConfig.isCreativeModeEnabled();
        if (!isCreativeMode) {
            double currentFunds = cityInfo.getFunds();
            if (currentFunds < totalCost) {
                player.sendSystemMessage(Objects.requireNonNull(
                        Component.translatable(
                                "message.simukraft.block_replacement.insufficient_funds",
                                totalCost,
                                currentFunds
                        )));
                return;
            }
            // 扣除费用
            cityInfo.setFunds(currentFunds - totalCost);
            cityData.setDirty();
        }

        // 执行替换
        int replacedCount = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                Objects.requireNonNull(selectionStart),
                Objects.requireNonNull(selectionEnd))) {
            BlockPos safePos = Objects.requireNonNull(pos);
            BlockState blockState = level.getBlockState(safePos);
            Block fromBlock = blockState.getBlock();
            Block toBlock = replacementMap.get(fromBlock);

            if (toBlock != null) {
                // 消耗箱子中的材料
                if (consumeItemFromChest(chest, toBlock, 1)) {
                    // 将原方块放入箱子
                    ItemStack originalItem = new ItemStack(Objects.requireNonNull(fromBlock.asItem()));
                    if (!addItemToChest(chest, originalItem)) {
                        // 如果箱子满了，停止替换并退还已消耗的材料
                        player.sendSystemMessage(Objects.requireNonNull(
                                Component.translatable("message.simukraft.block_replacement.chest_full")));
                        // 退还已消耗的材料
                        refundMaterials(chest, toBlock, 1);
                        break;
                    }

                    // 替换方块
                    level.setBlock(
                            safePos,
                            Objects.requireNonNull(toBlock.defaultBlockState()),
                            3
                    );
                    replacedCount++;
                }
            }
        }

        // 发送完成消息
        if (replacedCount > 0) {
            double actualCost = replacedCount * COST_PER_BLOCK;
            actualCost = roundToTwoDecimalPlaces(actualCost);
            player.sendSystemMessage(Objects.requireNonNull(
                    Component.translatable("message.simukraft.block_replacement.success", replacedCount, actualCost)));
        } else {
            // 如果没有方块被替换，退还费用（非创造模式）
            if (!isCreativeMode) {
                cityInfo.setFunds(cityInfo.getFunds() + totalCost);
                cityData.setDirty();
            }
            player.sendSystemMessage(Objects.requireNonNull(
                    Component.translatable("message.simukraft.block_replacement.refunded")));
        }
    }

    /**
     * 将数值四舍五入到两位小数
     */
    private static double roundToTwoDecimalPlaces(double value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 统计容器中指定方块物品的数量
     */
    private static int countItemsInChest(Container container, Block block) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (blockItem.getBlock() == block) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    /**
     * 从容器中消耗指定数量的方块物品
     */
    private static boolean consumeItemFromChest(Container container, Block block, int amount) {
        int remaining = amount;

        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (blockItem.getBlock() == block) {
                    int toConsume = Math.min(remaining, stack.getCount());
                    stack.shrink(toConsume);
                    remaining -= toConsume;
                    if (stack.isEmpty()) {
                        container.setItem(i, Objects.requireNonNull(ItemStack.EMPTY));
                    }
                }
            }
        }

        container.setChanged();
        return remaining == 0;
    }

    /**
     * 将物品添加到容器
     */
    private static boolean addItemToChest(Container container, ItemStack item) {
        // 先尝试合并到现有槽位
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.getItem() == item.getItem() && stack.getCount() < stack.getMaxStackSize()) {
                int space = stack.getMaxStackSize() - stack.getCount();
                int toAdd = Math.min(space, item.getCount());
                stack.grow(toAdd);
                item.shrink(toAdd);
                if (item.isEmpty()) {
                    container.setChanged();
                    return true;
                }
            }
        }

        // 再尝试放入空槽位
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, Objects.requireNonNull(item.copy()));
                container.setChanged();
                return true;
            }
        }

        return false; // 容器满了
    }

    /**
     * 退还材料到容器
     */
    private static void refundMaterials(Container container, Block block, int amount) {
        ItemStack refund = new ItemStack(Objects.requireNonNull(block.asItem()), amount);
        addItemToChest(container, refund);
    }
}
