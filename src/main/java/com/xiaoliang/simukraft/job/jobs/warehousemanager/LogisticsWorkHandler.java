package com.xiaoliang.simukraft.job.jobs.warehousemanager;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.world.LogisticsData;
import com.xiaoliang.simukraft.world.LogisticsData.*;
import com.xiaoliang.simukraft.world.LogisticsHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 物流传输引擎 — 每隔固定 tick 处理所有启用的频道。
 */
public class LogisticsWorkHandler {

    private static final int TRANSFER_INTERVAL_TICKS = 100; // 5 秒
    private static int tickCounter = 0;

    // 距离计费常量
    private static final double BASE_COST_PER_GROUP = 0.02; // 超过256格每组基础费用
    private static final int BASE_DISTANCE = 256; // 基础距离阈值（256格内免费）
    private static final int ADDITIONAL_DISTANCE_STEP = 64; // 额外距离步长（每超64格加费）
    private static final double ADDITIONAL_COST_PER_STEP = 0.01; // 每增加64格的额外费用

    /**
     * 在 WorldEvents 的 LevelTickEvent 中调用
     */
    public static void onServerTick(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;

        tickCounter++;
        if (tickCounter < TRANSFER_INTERVAL_TICKS) return;
        tickCounter = 0;

        MinecraftServer server = level.getServer();
        LogisticsData data = LogisticsData.get(level);

        // 照抄建筑盒模式：从 LogisticsHiredData 读取雇佣状态
        for (Warehouse warehouse : data.getAllWarehouses()) {
            // 必须有仓库管理员 NPC 在岗 - 照抄建筑盒模式
            if (!LogisticsHiredData.hasServerBoxHired(server, warehouse.getBlockPos())) continue;
            if (!warehouse.hasContainers()) continue;

            for (LogisticsChannel channel : warehouse.getChannels()) {
                if (!channel.isEnabled()) continue;

                LogisticsClient client = data.getClient(channel.getTargetClientId());
                if (client == null || !client.hasPorts()) continue;

                try {
                    processChannel(level, warehouse, client, channel);
                } catch (Exception e) {
                    System.err.println("[Logistics] 频道处理异常: " + channel.getName() + " - " + e.getMessage());
                }
            }
        }
    }

    private static void processChannel(ServerLevel level, Warehouse warehouse,
                                         LogisticsClient client, LogisticsChannel channel) {
        List<BlockPos> sources;
        List<BlockPos> targets;

        if (channel.getDirection() == LogisticsData.ChannelDirection.SEND) {
            // 仓库 → 客户端
            sources = warehouse.getContainerPositions();
            targets = client.getPortPositions();
        } else {
            // 客户端 → 仓库
            sources = client.getPortPositions();
            targets = warehouse.getContainerPositions();
        }

        // 计算仓库和客户端之间的距离（使用中心点）
        double distance = calculateDistance(warehouse, client);

        // 获取城市ID用于扣费
        UUID cityId = warehouse.getCityId();

        for (ItemStack filterItem : channel.getItemFilters()) {
            transferItem(level, sources, targets, filterItem, distance, cityId);
        }
    }

    /**
     * 计算仓库和客户端之间的距离
     */
    private static double calculateDistance(Warehouse warehouse, LogisticsClient client) {
        BlockPos warehouseCenter = getCenterPosition(warehouse.getContainerPositions());
        BlockPos clientCenter = getCenterPosition(client.getPortPositions());

        if (warehouseCenter == null || clientCenter == null) {
            return 0;
        }

        return Math.sqrt(warehouseCenter.distSqr(clientCenter));
    }

    /**
     * 获取位置列表的中心点
     */
    private static BlockPos getCenterPosition(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }

        long sumX = 0, sumY = 0, sumZ = 0;
        for (BlockPos pos : positions) {
            sumX += pos.getX();
            sumY += pos.getY();
            sumZ += pos.getZ();
        }

        return new BlockPos((int)(sumX / positions.size()),
                           (int)(sumY / positions.size()),
                           (int)(sumZ / positions.size()));
    }

    /**
     * 计算传输费用
     * 256格内免费，256格以上每组0.02元，每超过64格加0.01元
     */
    private static double calculateTransferCost(double distance) {
        if (distance <= BASE_DISTANCE) {
            return 0;
        }

        double extraDistance = distance - BASE_DISTANCE;
        int additionalSteps = (int) Math.ceil(extraDistance / ADDITIONAL_DISTANCE_STEP);

        return BASE_COST_PER_GROUP + (additionalSteps * ADDITIONAL_COST_PER_STEP);
    }

    /**
     * 尝试将 filterItem 匹配的物品从 sources 转移到 targets。
     * 每次最多转移一组（64个）。
     * 注意：NBT物品使用仓库堆叠规则处理
     */
    private static void transferItem(ServerLevel level, List<BlockPos> sources,
                                       List<BlockPos> targets, ItemStack filterItem,
                                       double distance, UUID cityId) {
        // 计算传输费用
        double transferCost = calculateTransferCost(distance);

        // 从源中找到匹配的物品
        for (BlockPos sourcePos : sources) {
            if (!ContainerUtils.isContainer(level, sourcePos)) continue;

            List<ItemStack> sourceItems = ContainerUtils.getAllItems(level, sourcePos);
            for (ItemStack sourceItem : sourceItems) {
                if (sourceItem.isEmpty()) continue;

                // 使用仓库堆叠规则来匹配物品
                // - 无NBT的filter：匹配所有同类型物品
                // - 有NBT的filter：必须完全匹配（包括NBT）
                boolean matches;
                if (ContainerUtils.hasNBT(filterItem)) {
                    // filter有NBT，必须完全匹配
                    matches = ContainerUtils.areItemsEqualWithNBT(sourceItem, filterItem);
                } else {
                    // filter无NBT，只匹配物品类型
                    matches = sourceItem.getItem() == filterItem.getItem();
                }

                if (!matches) continue;

                // 找到了匹配物品，尝试放入目标
                // 对于有NBT的物品，每次只转移1个（不堆叠）
                int maxTransfer = ContainerUtils.hasNBT(sourceItem) ? 1 : Math.min(sourceItem.getCount(), 64);
                int toTransfer = Math.min(sourceItem.getCount(), maxTransfer);
                ItemStack transferStack = sourceItem.copy();
                transferStack.setCount(toTransfer);

                for (BlockPos targetPos : targets) {
                    if (!ContainerUtils.isContainer(level, targetPos)) continue;
                    if (!ContainerUtils.canInsertItem(level, targetPos, transferStack)) continue;

                    // 如果需要付费，检查并扣除城市资金
                    if (transferCost > 0 && cityId != null) {
                        var cityData = com.xiaoliang.simukraft.world.CityData.get(level);
                        var city = cityData.getCity(cityId);
                        if (city == null) {
                            System.out.println("[Logistics] 传输失败: 找不到城市");
                            return;
                        }
                        // 创造模式下跳过资金检查
                        if (!ServerConfig.isCreativeModeEnabled()) {
                            double cityFunds = city.getFunds();
                            if (cityFunds < transferCost) {
                                System.out.println("[Logistics] 传输失败: 城市资金不足，需要 " + transferCost + " 元");
                                return; // 资金不足，不执行传输
                            }
                            // 扣除城市资金
                            city.setFunds(cityFunds - transferCost);
                            cityData.setDirty();
                            System.out.println("[Logistics] 扣除传输费用: " + transferCost + " 元 (距离: " + String.format("%.1f", distance) + " 格)");
                        } else {
                            System.out.println("[Logistics] 创造模式：跳过传输费用 " + transferCost + " 元 (距离: " + String.format("%.1f", distance) + " 格)");
                        }
                    }

                    // 执行转移
                    int inserted = ContainerUtils.insertItem(level, targetPos, transferStack);
                    if (inserted > 0) {
                        // 从源消耗 - 使用实际的sourceItem来消耗，确保NBT正确
                        ItemStack consumeStack = sourceItem.copy();
                        consumeStack.setCount(inserted);
                        ContainerUtils.consumeItem(level, sourcePos, consumeStack);
                        return; // 每个物品类型每 tick 只转移一次
                    }
                }
            }
        }
    }
}
