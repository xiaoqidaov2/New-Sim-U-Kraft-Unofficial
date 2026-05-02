package com.xiaoliang.simukraft.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C 客户端路径响应包 — 返回指定客户端关联的所有物流路径
 */
@SuppressWarnings("null")
public class ClientRoutesResponsePacket {
    private final List<RouteInfo> routes;

    public ClientRoutesResponsePacket(List<RouteInfo> routes) {
        this.routes = new ArrayList<>(routes);
    }

    public ClientRoutesResponsePacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        this.routes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf();
            String direction = buf.readUtf();
            boolean enabled = buf.readBoolean();
            String warehousePos = buf.readUtf();
            boolean warehouseHasNpc = buf.readBoolean();
            int itemCount = buf.readVarInt();
            List<String> itemNames = new ArrayList<>();
            for (int j = 0; j < itemCount; j++) {
                itemNames.add(buf.readUtf());
            }
            routes.add(new RouteInfo(name, direction, enabled, warehousePos, warehouseHasNpc, itemNames));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(routes.size());
        for (RouteInfo route : routes) {
            buf.writeUtf(route.name);
            buf.writeUtf(route.direction);
            buf.writeBoolean(route.enabled);
            buf.writeUtf(route.warehousePos);
            buf.writeBoolean(route.warehouseHasNpc);
            buf.writeVarInt(route.itemNames.size());
            for (String item : route.itemNames) {
                buf.writeUtf(item);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof ClientRoutesReceiver receiver) {
                receiver.onClientRoutesReceived(this);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public List<RouteInfo> getRoutes() { return routes; }

    public record RouteInfo(String name, String direction, boolean enabled,
                            String warehousePos, boolean warehouseHasNpc, List<String> itemNames) {}

    public interface ClientRoutesReceiver {
        void onClientRoutesReceived(ClientRoutesResponsePacket packet);
    }
}
