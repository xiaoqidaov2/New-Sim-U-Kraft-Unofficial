package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.LogisticsData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 请求物流盒子雇佣状态 - 照抄 RequestBuildBoxHireStatusPacket
 */
@SuppressWarnings("null")
public class RequestLogisticsStatusPacket {
    private final BlockPos blockPos;
    private final boolean isServerBox; // true=服务端盒子, false=客户端盒子

    public RequestLogisticsStatusPacket(BlockPos pos, boolean isServerBox) {
        this.blockPos = pos;
        this.isServerBox = isServerBox;
    }

    public RequestLogisticsStatusPacket(FriendlyByteBuf buf) {
        this.blockPos = buf.readBlockPos();
        this.isServerBox = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeBoolean(isServerBox);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 照抄建筑盒模式：从 LogisticsHiredData 读取雇佣状态
            var server = player.serverLevel().getServer();
            var level = server.overworld();
            var data = LogisticsData.get(level);

            if (isServerBox) {
                // 处理服务端盒子（仓库）
                var npcUuid = com.xiaoliang.simukraft.world.LogisticsHiredData.getServerBoxHiredNpc(server, blockPos);
                var syncPacket = new SyncLogisticsHireStatusPacket(
                        blockPos, npcUuid, null, null
                );
                NetworkManager.sendToPlayer(syncPacket, player);

                // 同时发送 LogisticsSyncPacket 来更新 LogisticsServerScreen
                var warehouse = data.getWarehouseByBlockPos(blockPos);

                // 验证并清理已不存在的容器
                if (warehouse != null) {
                    validateAndCleanContainers(level, warehouse);
                }

                boolean hasNpc = npcUuid != null;
                boolean hasWarehouse = warehouse != null && warehouse.hasContainers();
                int containerCount = warehouse != null ? warehouse.getContainerPositions().size() : 0;
                UUID warehouseId = warehouse != null ? warehouse.getWarehouseId() : null;

                var logisticsSyncPacket = LogisticsSyncPacket.serverStatus(
                        blockPos, hasNpc, hasWarehouse, containerCount, warehouseId
                );
                NetworkManager.sendToPlayer(logisticsSyncPacket, player);

                // 同时发送路径数据
                if (warehouse != null) {
                    List<LogisticsRoutesSyncPacket.RouteEntry> routeEntries = new ArrayList<>();
                    for (LogisticsData.LogisticsChannel ch : warehouse.getChannels()) {
                        LogisticsData.LogisticsClient client = data.getClient(ch.getTargetClientId());
                        String clientPos = client != null
                                ? client.getBlockPos().getX() + "," + client.getBlockPos().getY() + "," + client.getBlockPos().getZ()
                                : "?";
                        List<String> itemNames = new ArrayList<>();
                        for (ItemStack stack : ch.getItemFilters()) {
                            itemNames.add(stack.getHoverName().getString());
                        }
                        routeEntries.add(new LogisticsRoutesSyncPacket.RouteEntry(
                                ch.getChannelId(), ch.getName(), ch.getDirection().name(),
                                ch.isEnabled(), clientPos, itemNames));
                    }
                    NetworkManager.sendToPlayer(new LogisticsRoutesSyncPacket(blockPos, routeEntries), player);
                }
            } else {
                // 处理客户端盒子
                var client = data.getClientByBlockPos(blockPos);
                if (client != null) {
                    // 验证并清理已不存在的容器
                    validateAndCleanClientContainers(level, client);
                }

                boolean hasPorts = client != null && client.hasPorts();
                int portCount = client != null ? client.getPortPositions().size() : 0;
                UUID clientId = client != null ? client.getClientId() : null;

                // 发送客户端状态同步包
                var clientSyncPacket = LogisticsSyncPacket.clientStatus(
                        blockPos, hasPorts, portCount, clientId
                );
                NetworkManager.sendToPlayer(clientSyncPacket, player);

                // 同时发送客户端容器位置数据
                if (client != null && client.hasPorts()) {
                    var clientDataPacket = new SyncLogisticsClientDataPacket(
                            blockPos, client.getClientId(), client.getPortPositions()
                    );
                    NetworkManager.sendToPlayer(clientDataPacket, player);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public boolean isServerBox() {
        return isServerBox;
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
            if (!com.xiaoliang.simukraft.utils.ContainerUtils.isContainer(level, pos)) {
                iterator.remove();
            }
        }

        int afterCount = containers.size();
        if (beforeCount != afterCount) {
            // 有容器被移除，标记数据需要保存
            com.xiaoliang.simukraft.world.LogisticsData.get(level).setDirty();
        }
    }

    /**
     * 验证客户端容器列表，移除已不存在的容器
     */
    private void validateAndCleanClientContainers(net.minecraft.server.level.ServerLevel level,
                                                  com.xiaoliang.simukraft.world.LogisticsData.LogisticsClient client) {
        var containers = client.getPortPositions();
        int beforeCount = containers.size();

        // 使用迭代器安全移除
        java.util.Iterator<net.minecraft.core.BlockPos> iterator = containers.iterator();
        while (iterator.hasNext()) {
            net.minecraft.core.BlockPos pos = iterator.next();
            // 检查该位置是否仍然是有效的容器
            if (!com.xiaoliang.simukraft.utils.ContainerUtils.isContainer(level, pos)) {
                iterator.remove();
            }
        }

        int afterCount = containers.size();
        if (beforeCount != afterCount) {
            // 有容器被移除，标记数据需要保存
            com.xiaoliang.simukraft.world.LogisticsData.get(level).setDirty();
        }
    }
}
