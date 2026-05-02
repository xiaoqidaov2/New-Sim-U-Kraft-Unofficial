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
 * C→S 请求物流网络数据
 * 客户端请求服务器发送仓库和客户端列表数据
 */
@SuppressWarnings("null")
public class RequestLogisticsNetworkPacket {
    private final BlockPos warehouseBlockPos;
    private final UUID warehouseId;

    public RequestLogisticsNetworkPacket(BlockPos warehouseBlockPos, UUID warehouseId) {
        this.warehouseBlockPos = warehouseBlockPos;
        this.warehouseId = warehouseId;
    }

    public RequestLogisticsNetworkPacket(FriendlyByteBuf buf) {
        this.warehouseBlockPos = buf.readBlockPos();
        this.warehouseId = buf.readUUID();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(warehouseBlockPos);
        buf.writeUUID(warehouseId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            var server = player.serverLevel().getServer();
            var level = server.overworld();
            var data = LogisticsData.get(level);

            // 获取仓库数据
            LogisticsData.Warehouse warehouse = data.getWarehouse(warehouseId);
            if (warehouse == null) return;

            // 获取该城市的所有客户端
            List<LogisticsData.LogisticsClient> clients = data.getClientsByCity(warehouse.getCityId());

            // 构建客户端数据列表
            List<ClientData> clientDataList = new ArrayList<>();
            for (LogisticsData.LogisticsClient client : clients) {
                clientDataList.add(new ClientData(
                        client.getClientId(),
                        client.getBlockPos(),
                        client.getCityId(),
                        client.getPortPositions(),
                        client.getName()
                ));
            }

            // 构建频道数据列表
            List<LogisticsNetworkResponsePacket.ChannelData> channelDataList = new ArrayList<>();
            for (LogisticsData.LogisticsChannel ch : warehouse.getChannels()) {
                List<String> itemNames = new ArrayList<>();
                for (ItemStack stack : ch.getItemFilters()) {
                    itemNames.add(stack.getHoverName().getString());
                }
                channelDataList.add(new LogisticsNetworkResponsePacket.ChannelData(
                        ch.getChannelId(), ch.getName(), ch.getTargetClientId(),
                        ch.getDirection().name(), ch.isEnabled(), itemNames));
            }

            // 发送响应
            var response = new LogisticsNetworkResponsePacket(
                    warehouseBlockPos,
                    warehouseId,
                    warehouse.getContainerPositions(),
                    clientDataList,
                    channelDataList
            );
            NetworkManager.sendToPlayer(response, player);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 客户端数据（用于序列化）
     */
    public static class ClientData {
        public final UUID clientId;
        public final BlockPos blockPos;
        public final UUID cityId;
        public final List<BlockPos> portPositions;
        public final String name;

        public ClientData(UUID clientId, BlockPos blockPos, UUID cityId, List<BlockPos> portPositions, String name) {
            this.clientId = clientId;
            this.blockPos = blockPos;
            this.cityId = cityId;
            this.portPositions = new ArrayList<>(portPositions);
            this.name = name != null ? name : "";
        }
    }
}
