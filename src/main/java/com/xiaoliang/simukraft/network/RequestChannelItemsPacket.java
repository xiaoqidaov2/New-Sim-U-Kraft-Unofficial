package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.utils.ContainerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C→S 请求频道物品数据
 * 客户端请求服务器扫描指定仓库或客户端的物品
 */
@SuppressWarnings("null")
public class RequestChannelItemsPacket {
    private final BlockPos warehouseBlockPos;
    private final boolean isSend;
    private final UUID targetClientId; // 接收模式时使用

    public RequestChannelItemsPacket(BlockPos warehouseBlockPos, boolean isSend, UUID targetClientId) {
        this.warehouseBlockPos = warehouseBlockPos;
        this.isSend = isSend;
        this.targetClientId = targetClientId;
    }

    public RequestChannelItemsPacket(FriendlyByteBuf buf) {
        this.warehouseBlockPos = buf.readBlockPos();
        this.isSend = buf.readBoolean();
        this.targetClientId = buf.readBoolean() ? buf.readUUID() : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(warehouseBlockPos);
        buf.writeBoolean(isSend);
        buf.writeBoolean(targetClientId != null);
        if (targetClientId != null) {
            buf.writeUUID(targetClientId);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            var server = player.serverLevel().getServer();
            var level = server.overworld();
            var data = com.xiaoliang.simukraft.world.LogisticsData.get(level);

            // 获取仓库
            var warehouse = data.getWarehouseByBlockPos(warehouseBlockPos);
            if (warehouse == null) {
                // 仓库不存在，返回空数据
                sendEmptyResponse(player);
                return;
            }

            List<ItemEntry> itemList = new ArrayList<>();

            if (isSend) {
                // 发送模式：扫描仓库容器
                for (BlockPos pos : warehouse.getContainerPositions()) {
                    scanContainerItems(level, pos, itemList);
                }
            } else {
                // 接收模式：扫描目标客户端容器
                if (targetClientId != null) {
                    var client = data.getClient(targetClientId);
                    if (client != null) {
                        for (BlockPos pos : client.getPortPositions()) {
                            scanContainerItems(level, pos, itemList);
                        }
                    }
                }
            }

            // 发送响应
            var response = new ChannelItemsResponsePacket(warehouseBlockPos, isSend, itemList);
            NetworkManager.sendToPlayer(response, player);
        });
        ctx.get().setPacketHandled(true);
    }

    private void sendEmptyResponse(ServerPlayer player) {
        var response = new ChannelItemsResponsePacket(warehouseBlockPos, isSend, new ArrayList<>());
        NetworkManager.sendToPlayer(response, player);
    }

    private void scanContainerItems(net.minecraft.server.level.ServerLevel level, BlockPos pos, List<ItemEntry> itemList) {
        // 检查区块是否已加载
        if (!level.isLoaded(pos)) {
            return;
        }

        List<ItemStack> items = ContainerUtils.getAllItems(level, pos);
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key == null) continue;
            String itemId = key.toString();

            // 检查物品是否有NBT
            if (ContainerUtils.hasNBT(stack)) {
                // 有NBT的物品：每个单独添加，不合并
                int count = stack.getCount();
                for (int i = 0; i < count; i++) {
                    itemList.add(new ItemEntry(itemId, 1, stack.getTag()));
                }
            } else {
                // 无NBT的物品：尝试合并到已有条目
                boolean merged = false;
                for (ItemEntry entry : itemList) {
                    if (entry.itemId.equals(itemId) && !entry.hasNBT()) {
                        entry.count += stack.getCount();
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    itemList.add(new ItemEntry(itemId, stack.getCount(), null));
                }
            }
        }
    }

    /**
     * 物品条目（用于序列化）
     */
    public static class ItemEntry {
        public final String itemId;
        public int count;
        public final net.minecraft.nbt.CompoundTag nbt;

        public ItemEntry(String itemId, int count, net.minecraft.nbt.CompoundTag nbt) {
            this.itemId = itemId;
            this.count = count;
            this.nbt = nbt;
        }

        public boolean hasNBT() {
            return nbt != null && !nbt.isEmpty();
        }
    }
}
