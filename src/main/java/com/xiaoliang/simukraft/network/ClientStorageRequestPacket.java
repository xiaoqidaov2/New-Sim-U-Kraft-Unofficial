package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.utils.ContainerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * C→S 请求客户端存储物品数据
 * 客户端请求服务器扫描指定容器的物品并返回结果
 */
@SuppressWarnings("null")
public class ClientStorageRequestPacket {
    private final BlockPos clientBlockPos;  // 客户端盒子位置

    public ClientStorageRequestPacket(BlockPos clientBlockPos) {
        this.clientBlockPos = clientBlockPos;
    }

    public ClientStorageRequestPacket(FriendlyByteBuf buf) {
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
            var data = com.xiaoliang.simukraft.world.LogisticsData.get(level);

            // 获取客户端数据
            var client = data.getClientByBlockPos(clientBlockPos);
            if (client == null || !client.hasPorts()) {
                // 客户端不存在或没有端口，返回空数据
                var response = new ClientStorageResponsePacket(clientBlockPos, new ArrayList<>(), 0, 0);
                NetworkManager.sendToPlayer(response, player);
                return;
            }

            // 扫描所有容器的物品
            List<BlockPos> positions = client.getPortPositions();
            Map<String, ItemData> itemMap = new HashMap<>();
            int emptyContainers = 0;
            int totalItemStacks = 0;

            for (BlockPos pos : positions) {
                // 检查区块是否已加载
                if (!level.isLoaded(pos)) {
                    continue;
                }

                List<ItemStack> items = ContainerUtils.getAllItems(level, pos);
                if (items.isEmpty()) {
                    emptyContainers++;
                }
                for (ItemStack stack : items) {
                    if (stack.isEmpty()) continue;
                    totalItemStacks++;

                    var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    String itemId = key != null ? key.toString() : "unknown";

                    ItemData itemData = itemMap.computeIfAbsent(itemId, k -> new ItemData(
                            itemId,
                            stack.getItem(),
                            stack.getCount()
                    ));
                    itemData.count += stack.getCount();
                }
            }

            // 发送响应
            var response = new ClientStorageResponsePacket(
                    clientBlockPos,
                    new ArrayList<>(itemMap.values()),
                    emptyContainers,
                    totalItemStacks
            );
            NetworkManager.sendToPlayer(response, player);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 物品数据（用于序列化）
     */
    public static class ItemData {
        public final String itemId;
        public final net.minecraft.world.item.Item item;
        public int count;

        public ItemData(String itemId, net.minecraft.world.item.Item item, int count) {
            this.itemId = itemId;
            this.item = item;
            this.count = count;
        }
    }
}
