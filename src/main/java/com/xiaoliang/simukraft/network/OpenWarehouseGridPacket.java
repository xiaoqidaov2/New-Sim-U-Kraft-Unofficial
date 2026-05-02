package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.inventory.WarehouseGridMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

/**
 * 打开仓库网格菜单的数据包
 */
@SuppressWarnings("null")
public class OpenWarehouseGridPacket {

    private final BlockPos warehousePos;

    public OpenWarehouseGridPacket(BlockPos warehousePos) {
        this.warehousePos = warehousePos;
    }

    public OpenWarehouseGridPacket(FriendlyByteBuf buf) {
        this.warehousePos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(warehousePos);
    }

    public void handle(Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            var level = player.serverLevel();
            var data = com.xiaoliang.simukraft.world.LogisticsData.get(level);
            var warehouse = data.getWarehouseByBlockPos(warehousePos);

            if (warehouse == null || warehouse.getContainerPositions().isEmpty()) {
                return;
            }

            // 使用 NetworkHooks 打开菜单并传递额外数据
            NetworkHooks.openScreen(player, new SimpleMenuProvider(
                    (windowId, playerInventory, p) -> new WarehouseGridMenu(windowId, playerInventory, warehousePos),
                    Component.translatable("container.warehouse")
            ), buf -> buf.writeBlockPos(warehousePos));
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getWarehousePos() {
        return warehousePos;
    }
}
