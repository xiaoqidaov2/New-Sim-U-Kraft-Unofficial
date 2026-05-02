package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S→C 物流路径同步包 — 将仓库的频道/路径信息同步给客户端GUI
 */
@SuppressWarnings("null")
public class LogisticsRoutesSyncPacket {

    private final BlockPos blockPos;
    private final List<RouteEntry> routes;

    public LogisticsRoutesSyncPacket(BlockPos blockPos, List<RouteEntry> routes) {
        this.blockPos = blockPos;
        this.routes = new ArrayList<>(routes);
    }

    public LogisticsRoutesSyncPacket(FriendlyByteBuf buf) {
        this.blockPos = buf.readBlockPos();
        int count = buf.readVarInt();
        this.routes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID channelId = buf.readUUID();
            String name = buf.readUtf();
            String direction = buf.readUtf();
            boolean enabled = buf.readBoolean();
            String clientPos = buf.readUtf();
            int itemCount = buf.readVarInt();
            List<String> itemNames = new ArrayList<>();
            for (int j = 0; j < itemCount; j++) {
                itemNames.add(buf.readUtf());
            }
            routes.add(new RouteEntry(channelId, name, direction, enabled, clientPos, itemNames));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeVarInt(routes.size());
        for (RouteEntry route : routes) {
            buf.writeUUID(route.channelId);
            buf.writeUtf(route.name);
            buf.writeUtf(route.direction);
            buf.writeBoolean(route.enabled);
            buf.writeUtf(route.clientPos);
            buf.writeVarInt(route.itemNames.size());
            for (String item : route.itemNames) {
                buf.writeUtf(item);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof LogisticsRoutesSyncReceiver receiver) {
                receiver.onRoutesSynced(this);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getBlockPos() { return blockPos; }
    public List<RouteEntry> getRoutes() { return routes; }

    public record RouteEntry(UUID channelId, String name, String direction, boolean enabled,
                             String clientPos, List<String> itemNames) {}

    public interface LogisticsRoutesSyncReceiver {
        void onRoutesSynced(LogisticsRoutesSyncPacket packet);
    }
}
