package com.xiaoliang.simukraft.network;

import com.mojang.logging.LogUtils;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.world.LogisticsData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C→S 物流操作包。
 * 统一处理：创建/删除仓库、设定/删除端口 等操作。
 */
@SuppressWarnings("null")
public class LogisticsActionPacket {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Action {
        CREATE_WAREHOUSE,   // 创建仓库（附带选区范围）
        DELETE_WAREHOUSE,   // 删除仓库
        SET_CLIENT_PORT,    // 设定客户端端口（附带选区范围）
        DELETE_CLIENT_PORT  // 删除客户端端口
    }

    private final Action action;
    private final BlockPos blockPos;      // 物流盒方块位置
    private final BlockPos areaMin;       // 选区最小角（CREATE_WAREHOUSE / SET_CLIENT_PORT 时用）
    private final BlockPos areaMax;       // 选区最大角

    public LogisticsActionPacket(Action action, BlockPos blockPos, BlockPos areaMin, BlockPos areaMax) {
        this.action = action;
        this.blockPos = blockPos;
        this.areaMin = areaMin != null ? areaMin : BlockPos.ZERO;
        this.areaMax = areaMax != null ? areaMax : BlockPos.ZERO;
    }

    public LogisticsActionPacket(FriendlyByteBuf buf) {
        this.action = Action.values()[buf.readVarInt()];
        this.blockPos = buf.readBlockPos();
        this.areaMin = buf.readBlockPos();
        this.areaMax = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(action.ordinal());
        buf.writeBlockPos(blockPos);
        buf.writeBlockPos(areaMin);
        buf.writeBlockPos(areaMax);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            LogisticsData data = LogisticsData.get(level);

            LOGGER.info("[Logistics] 收到操作包: action={} blockPos={} area=({} ~ {})", action, blockPos, areaMin, areaMax);

            switch (action) {
                case CREATE_WAREHOUSE -> handleCreateWarehouse(data, level, player);
                case DELETE_WAREHOUSE -> handleDeleteWarehouse(data, player);
                case SET_CLIENT_PORT -> handleSetClientPort(data, level, player);
                case DELETE_CLIENT_PORT -> handleDeleteClientPort(data, player);
            }
        });
        ctx.setPacketHandled(true);
    }

    // 每个容器的创建费用（元）
    private static final double COST_PER_CONTAINER = 2.0;

    private void handleCreateWarehouse(LogisticsData data, ServerLevel level, ServerPlayer player) {
        // 检查是否已有仓库
        if (data.getWarehouseByBlockPos(blockPos) != null) return;

        // 获取玩家城市 ID
        var cityData = com.xiaoliang.simukraft.world.CityData.get(level);
        UUID cityId = cityData.getPlayerCityIdByName(player.getName().getString());
        if (cityId == null) {
            LOGGER.warn("[Logistics] 创建仓库失败: 玩家 {} 没有城市", player.getName().getString());
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("message.simukraft.logistics.no_city"),
                false
            );
            return;
        }

        // 扫描选区内的箱子/木桶
        List<BlockPos> containers = scanContainersInArea(level, areaMin, areaMax);
        if (containers.isEmpty()) {
            LOGGER.warn("[Logistics] 创建仓库失败: 选区内没有容器 ({} ~ {})", areaMin, areaMax);
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("message.simukraft.logistics.no_containers"),
                false
            );
            return;
        }

        // 计算创建费用
        double totalCost = containers.size() * COST_PER_CONTAINER;

        // 检查并扣除资金
        if (!com.xiaoliang.simukraft.utils.MoneyManager.hasEnoughMoney(player, totalCost)) {
            LOGGER.warn("[Logistics] 创建仓库失败: 玩家 {} 资金不足，需要 {} 元", player.getName().getString(), totalCost);
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("message.simukraft.logistics.insufficient_funds", totalCost),
                false
            );
            return;
        }

        // 扣除资金
        com.xiaoliang.simukraft.utils.MoneyManager.deductMoney(player, totalCost);
        LOGGER.info("[Logistics] 扣除创建仓库费用: {} 元 ({} 个容器 x {} 元/个)", totalCost, containers.size(), COST_PER_CONTAINER);

        LogisticsData.Warehouse warehouse = data.createWarehouse(blockPos, cityId);
        warehouse.getContainerPositions().addAll(containers);
        data.setDirty();

        LOGGER.info("[Logistics] 创建仓库成功: {} 城市={} 包含 {} 个容器", warehouse.getWarehouseId(), cityId, containers.size());

        // 发送成功消息给玩家
        player.displayClientMessage(
            net.minecraft.network.chat.Component.translatable("message.simukraft.logistics.warehouse_created", containers.size(), totalCost),
            false
        );

        // 同步更新给客户端
        syncServerStatus(data, player);
    }

    private void handleDeleteWarehouse(LogisticsData data, ServerPlayer player) {
        LogisticsData.Warehouse warehouse = data.getWarehouseByBlockPos(blockPos);
        if (warehouse == null) return;
        data.removeWarehouse(warehouse.getWarehouseId());
        LOGGER.info("[Logistics] 删除仓库: {}", blockPos);

        // 发送删除成功消息给玩家
        player.displayClientMessage(
            net.minecraft.network.chat.Component.translatable("message.simukraft.logistics.warehouse_deleted"),
            false
        );

        // 同步更新给客户端
        syncServerStatus(data, player);
    }

    private void handleSetClientPort(LogisticsData data, ServerLevel level, ServerPlayer player) {
        LogisticsData.LogisticsClient client = data.getClientByBlockPos(blockPos);

        var cityData = com.xiaoliang.simukraft.world.CityData.get(level);
        UUID cityId = cityData.getPlayerCityIdByName(player.getName().getString());
        if (cityId == null) {
            LOGGER.warn("[Logistics] 连接容器失败: 玩家 {} 没有城市", player.getName().getString());
            return;
        }

        List<BlockPos> containers = scanContainersInArea(level, areaMin, areaMax);
        if (containers.isEmpty()) {
            LOGGER.warn("[Logistics] 连接容器失败: 选区内没有容器");
            return;
        }

        if (client == null) {
            client = data.createClient(blockPos, cityId);
        }
        // 追加新容器（去重）
        for (BlockPos pos : containers) {
            if (!client.getPortPositions().contains(pos)) {
                client.getPortPositions().add(pos);
            }
        }
        data.setDirty();

        LOGGER.info("[Logistics] 连接容器成功: {} 包含 {} 个容器", client.getClientId(), containers.size());

        // 同步更新给客户端
        syncClientStatus(data, player, client.getClientId());
    }

    private void handleDeleteClientPort(LogisticsData data, ServerPlayer player) {
        LogisticsData.LogisticsClient client = data.getClientByBlockPos(blockPos);
        if (client == null) return;
        UUID clientId = client.getClientId();
        data.removeClient(clientId);
        LOGGER.info("[Logistics] 断开容器: {}", blockPos);

        // 同步更新给客户端
        syncClientStatus(data, player, clientId);
    }

    /**
     * 同步服务端状态给客户端
     */
    private void syncServerStatus(LogisticsData data, ServerPlayer player) {
        var warehouse = data.getWarehouseByBlockPos(blockPos);

        // 验证并清理已不存在的容器
        if (warehouse != null) {
            validateAndCleanContainers(player.serverLevel(), warehouse);
        }

        boolean hasWarehouse = warehouse != null && warehouse.hasContainers();
        int containerCount = warehouse != null ? warehouse.getContainerPositions().size() : 0;
        UUID warehouseId = warehouse != null ? warehouse.getWarehouseId() : null;

        // 获取雇佣状态
        var npcUuid = com.xiaoliang.simukraft.world.LogisticsHiredData.getServerBoxHiredNpc(
                player.serverLevel().getServer(), blockPos);

        var syncPacket = LogisticsSyncPacket.serverStatus(
                blockPos, npcUuid != null, hasWarehouse, containerCount, warehouseId
        );
        NetworkManager.sendToPlayer(syncPacket, player);
    }

    /**
     * 验证仓库容器列表，移除已不存在的容器
     */
    private void validateAndCleanContainers(net.minecraft.server.level.ServerLevel level,
                                            com.xiaoliang.simukraft.world.LogisticsData.Warehouse warehouse) {
        var containers = warehouse.getContainerPositions();
        int beforeCount = containers.size();

        // 使用迭代器安全移除
        java.util.Iterator<net.minecraft.core.BlockPos> iterator = containers.iterator();
        while (iterator.hasNext()) {
            net.minecraft.core.BlockPos pos = iterator.next();
            // 检查该位置是否仍然是有效的容器
            if (!ContainerUtils.isContainer(level, pos)) {
                iterator.remove();
            }
        }

        int afterCount = containers.size();
        if (beforeCount != afterCount) {
            // 有容器被移除，标记数据需要保存
            LogisticsData.get(level).setDirty();
        }
    }

    /**
     * 同步客户端状态给客户端
     */
    private void syncClientStatus(LogisticsData data, ServerPlayer player, UUID clientId) {
        var client = data.getClient(clientId);
        boolean hasPorts = client != null && client.hasPorts();
        int portCount = client != null ? client.getPortPositions().size() : 0;

        var syncPacket = LogisticsSyncPacket.clientStatus(
                blockPos, hasPorts, portCount, clientId
        );
        NetworkManager.sendToPlayer(syncPacket, player);
    }

    /**
     * 扫描区域内所有箱子/木桶位置
     */
    private List<BlockPos> scanContainersInArea(ServerLevel level, BlockPos min, BlockPos max) {
        List<BlockPos> result = new ArrayList<>();
        int minX = Math.min(min.getX(), max.getX());
        int minY = Math.min(min.getY(), max.getY());
        int minZ = Math.min(min.getZ(), max.getZ());
        int maxX = Math.max(min.getX(), max.getX());
        int maxY = Math.max(min.getY(), max.getY());
        int maxZ = Math.max(min.getZ(), max.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (ContainerUtils.isContainer(level, pos)) {
                        result.add(pos);
                    }
                }
            }
        }
        return result;
    }
}
