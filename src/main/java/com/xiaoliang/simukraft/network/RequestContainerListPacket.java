package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.world.LogisticsData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * C→S 请求容器列表
 */
@SuppressWarnings("null")
public class RequestContainerListPacket {
    private final BlockPos clientBlockPos;

    public RequestContainerListPacket(BlockPos clientBlockPos) {
        this.clientBlockPos = clientBlockPos;
    }

    public RequestContainerListPacket(FriendlyByteBuf buf) {
        this.clientBlockPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(clientBlockPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ServerLevel level = player.serverLevel().getServer().overworld();
            LogisticsData data = LogisticsData.get(level);
            LogisticsData.LogisticsClient client = data.getClientByBlockPos(clientBlockPos);

            List<ContainerListResponsePacket.ContainerEntry> entries = new ArrayList<>();
            if (client != null) {
                for (BlockPos pos : client.getPortPositions()) {
                    String blockName = level.getBlockState(pos).getBlock().getName().getString();
                    List<ItemStack> items = ContainerUtils.getAllItems(level, pos);
                    int kinds = 0, total = 0;
                    for (ItemStack stack : items) {
                        if (!stack.isEmpty()) { kinds++; total += stack.getCount(); }
                    }
                    entries.add(new ContainerListResponsePacket.ContainerEntry(pos, blockName, kinds, total));
                }
            }

            NetworkManager.sendToPlayer(new ContainerListResponsePacket(clientBlockPos, entries), player);
        });
        ctx.get().setPacketHandled(true);
    }
}
