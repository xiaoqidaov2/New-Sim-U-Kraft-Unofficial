package com.xiaoliang.simukraft.job.jobs.warehousemanager;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.utils.PerformanceMonitor;
import com.xiaoliang.simukraft.world.LogisticsData;
import com.xiaoliang.simukraft.world.LogisticsData.*;
import com.xiaoliang.simukraft.world.LogisticsHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        long tickStart = PerformanceMonitor.beginSection();
        MinecraftServer server = level.getServer();
        LogisticsData data = LogisticsData.get(level);
        TransferContext context = new TransferContext(level, ServerConfig.isCreativeModeEnabled());

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
                    processChannel(level, warehouse, client, channel, context);
                } catch (Exception e) {
                    Simukraft.LOGGER.error("[Logistics] 频道处理异常: {}", channel.getName(), e);
                }
            }
        }

        context.flushDirtyState();
        PerformanceMonitor.recordValue("logistics.channelsProcessed", context.processedChannels);
        PerformanceMonitor.recordValue("logistics.transfers", context.completedTransfers);
        PerformanceMonitor.endSection(level, "logistics.tick", tickStart);
    }

    private static void processChannel(ServerLevel level, Warehouse warehouse,
                                       LogisticsClient client, LogisticsChannel channel,
                                       TransferContext context) {
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
        List<ContainerSnapshot> sourceSnapshots = snapshotContainers(level, sources, true);
        List<BlockPos> validTargets = collectValidContainers(level, targets);
        if (sourceSnapshots.isEmpty() || validTargets.isEmpty()) {
            return;
        }

        for (ItemStack filterItem : channel.getItemFilters()) {
            if (transferItem(level, sourceSnapshots, validTargets, filterItem, distance, cityId, context)) {
                context.processedChannels++;
            }
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
    private static boolean transferItem(ServerLevel level,
                                        List<ContainerSnapshot> sources,
                                        List<BlockPos> targets,
                                        ItemStack filterItem,
                                        double distance,
                                        UUID cityId,
                                        TransferContext context) {
        if (filterItem == null || filterItem.isEmpty()) {
            return false;
        }

        // 计算传输费用
        double transferCost = calculateTransferCost(distance);
        boolean filterHasNbt = ContainerUtils.hasNBT(filterItem);

        // 从源中找到匹配的物品
        for (ContainerSnapshot sourceSnapshot : sources) {
            for (ItemStack sourceItem : sourceSnapshot.items) {
                if (sourceItem.isEmpty()) continue;

                // 使用仓库堆叠规则来匹配物品
                // - 无NBT的filter：匹配所有同类型物品
                // - 有NBT的filter：必须完全匹配（包括NBT）
                boolean matches;
                if (filterHasNbt) {
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
                    if (!ContainerUtils.canInsertItem(level, targetPos, transferStack)) continue;

                    // 如果需要付费，检查并扣除城市资金
                    if (!context.tryPayTransferCost(cityId, transferCost, distance)) {
                        return false;
                    }

                    // 执行转移
                    int inserted = ContainerUtils.insertItem(level, targetPos, transferStack);
                    if (inserted > 0) {
                        // 从源消耗 - 使用实际的sourceItem来消耗，确保NBT正确
                        ItemStack consumeStack = sourceItem.copy();
                        consumeStack.setCount(inserted);
                        if (ContainerUtils.consumeItem(level, sourceSnapshot.pos, consumeStack)) {
                            sourceItem.shrink(inserted);
                            context.completedTransfers++;
                            return true; // 每个物品类型每 tick 只转移一次
                        }
                    }
                }
            }
        }

        return false;
    }

    private static List<ContainerSnapshot> snapshotContainers(ServerLevel level, List<BlockPos> positions, boolean requireItems) {
        List<ContainerSnapshot> snapshots = new ArrayList<>();
        if (positions == null || positions.isEmpty()) {
            return snapshots;
        }

        for (BlockPos pos : positions) {
            if (!ContainerUtils.isContainer(level, pos)) {
                continue;
            }
            List<ItemStack> items = ContainerUtils.getAllItems(level, pos);
            if (requireItems && items.isEmpty()) {
                continue;
            }
            snapshots.add(new ContainerSnapshot(pos, items));
        }
        return snapshots;
    }

    private static List<BlockPos> collectValidContainers(ServerLevel level, List<BlockPos> positions) {
        List<BlockPos> validContainers = new ArrayList<>();
        if (positions == null || positions.isEmpty()) {
            return validContainers;
        }
        for (BlockPos pos : positions) {
            if (ContainerUtils.isContainer(level, pos)) {
                validContainers.add(pos);
            }
        }
        return validContainers;
    }

    private static final class ContainerSnapshot {
        private final BlockPos pos;
        private final List<ItemStack> items;

        private ContainerSnapshot(BlockPos pos, List<ItemStack> items) {
            this.pos = pos;
            this.items = items;
        }
    }

    private static final class TransferContext {
        private final boolean creativeMode;
        private final com.xiaoliang.simukraft.world.CityData cityData;
        private final Set<UUID> dirtyCities = new HashSet<>();
        private int completedTransfers = 0;
        private int processedChannels = 0;

        private TransferContext(ServerLevel level, boolean creativeMode) {
            this.creativeMode = creativeMode;
            this.cityData = com.xiaoliang.simukraft.world.CityData.get(level);
        }

        private boolean tryPayTransferCost(UUID cityId, double transferCost, double distance) {
            if (transferCost <= 0.0D || cityId == null) {
                return true;
            }
            if (creativeMode) {
                if (Simukraft.LOGGER.isDebugEnabled()) {
                    Simukraft.LOGGER.debug("[Logistics] 创造模式跳过传输费用，距离={}，费用={}", distance, transferCost);
                }
                return true;
            }

            var city = cityData.getCity(cityId);
            if (city == null) {
                if (Simukraft.LOGGER.isDebugEnabled()) {
                    Simukraft.LOGGER.debug("[Logistics] 传输失败：找不到城市 {}", cityId);
                }
                return false;
            }

            double cityFunds = city.getFunds();
            if (cityFunds < transferCost) {
                if (Simukraft.LOGGER.isDebugEnabled()) {
                    Simukraft.LOGGER.debug("[Logistics] 传输失败：城市资金不足，需要 {} 元", transferCost);
                }
                return false;
            }

            city.setFunds(cityFunds - transferCost);
            dirtyCities.add(cityId);
            return true;
        }

        private void flushDirtyState() {
            if (!dirtyCities.isEmpty()) {
                cityData.setDirty();
                PerformanceMonitor.recordValue("logistics.cityDirtyUpdates", dirtyCities.size());
            }
        }
    }
}
