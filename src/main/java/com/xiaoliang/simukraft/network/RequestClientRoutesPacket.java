package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.LogisticsData;
import com.xiaoliang.simukraft.world.LogisticsHiredData;
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
 * C→S 请求客户端关联的所有物流路径
 */
@SuppressWarnings("null")
public class RequestClientRoutesPacket {
    private final BlockPos clientBlockPos;

    public RequestClientRoutesPacket(BlockPos clientBlockPos) {
        this.clientBlockPos = clientBlockPos;
    }

    public RequestClientRoutesPacket(FriendlyByteBuf buf) {
        this.clientBlockPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(clientBlockPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            var server = player.serverLevel().getServer();
            var level = server.overworld();
            var data = LogisticsData.get(level);

            LogisticsData.LogisticsClient client = data.getClientByBlockPos(clientBlockPos);
            if (client == null) {
                NetworkManager.sendToPlayer(new ClientRoutesResponsePacket(new ArrayList<>()), player);
                return;
            }

            UUID clientId = client.getClientId();
            List<ClientRoutesResponsePacket.RouteInfo> routeInfos = new ArrayList<>();

            for (LogisticsData.Warehouse warehouse : data.getAllWarehouses()) {
                for (LogisticsData.LogisticsChannel ch : warehouse.getChannels()) {
                    if (!ch.getTargetClientId().equals(clientId)) continue;

                    String warehousePos = warehouse.getBlockPos().getX() + ", "
                            + warehouse.getBlockPos().getY() + ", " + warehouse.getBlockPos().getZ();
                    boolean warehouseHasNpc = LogisticsHiredData.hasServerBoxHired(server, warehouse.getBlockPos());

                    List<String> itemNames = new ArrayList<>();
                    for (ItemStack stack : ch.getItemFilters()) {
                        itemNames.add(stack.getHoverName().getString());
                    }

                    routeInfos.add(new ClientRoutesResponsePacket.RouteInfo(
                            ch.getName(), ch.getDirection().name(), ch.isEnabled(),
                            warehousePos, warehouseHasNpc, itemNames));
                }
            }

            NetworkManager.sendToPlayer(new ClientRoutesResponsePacket(routeInfos), player);
        });
        ctx.get().setPacketHandled(true);
    }
}
