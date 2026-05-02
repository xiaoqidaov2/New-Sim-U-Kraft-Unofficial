package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C 客户端存储物品数据响应
 * 服务器返回扫描到的容器物品数据
 */
@SuppressWarnings("null")
public class ClientStorageResponsePacket {
    private final BlockPos clientBlockPos;
    private final List<ItemEntry> items;
    private final int emptyContainers;
    private final int totalItemStacks;

    public ClientStorageResponsePacket(BlockPos clientBlockPos, List<ClientStorageRequestPacket.ItemData> itemDataList,
                                       int emptyContainers, int totalItemStacks) {
        this.clientBlockPos = clientBlockPos;
        this.emptyContainers = emptyContainers;
        this.totalItemStacks = totalItemStacks;
        this.items = new ArrayList<>();

        // 转换 ItemData 为 ItemEntry
        for (ClientStorageRequestPacket.ItemData data : itemDataList) {
            items.add(new ItemEntry(data.itemId, data.count));
        }
    }

    public ClientStorageResponsePacket(FriendlyByteBuf buf) {
        this.clientBlockPos = buf.readBlockPos();
        this.emptyContainers = buf.readVarInt();
        this.totalItemStacks = buf.readVarInt();
        int count = buf.readVarInt();
        this.items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String itemId = buf.readUtf(256);
            int itemCount = buf.readVarInt();
            items.add(new ItemEntry(itemId, itemCount));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(clientBlockPos);
        buf.writeVarInt(emptyContainers);
        buf.writeVarInt(totalItemStacks);
        buf.writeVarInt(items.size());
        for (ItemEntry entry : items) {
            buf.writeUtf(entry.itemId, 256);
            buf.writeVarInt(entry.count);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 客户端接收数据，通知当前打开的 ClientStorageScreen
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof ClientStorageScreenReceiver receiver) {
                receiver.onClientStorageDataReceived(this);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getClientBlockPos() {
        return clientBlockPos;
    }

    public List<ItemEntry> getItems() {
        return items;
    }

    public int getEmptyContainers() {
        return emptyContainers;
    }

    public int getTotalItemStacks() {
        return totalItemStacks;
    }

    /**
     * 物品条目（用于网络传输）
     */
    public static class ItemEntry {
        public final String itemId;
        public final int count;

        public ItemEntry(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }

        /**
         * 创建 ItemStack（客户端使用）
         */
        public ItemStack createItemStack() {
            ResourceLocation itemLocation = ResourceLocation.tryParse(itemId);
            Item item = itemLocation == null ? null : ForgeRegistries.ITEMS.getValue(itemLocation);
            if (item == null) return ItemStack.EMPTY;
            return new ItemStack(item, 1);
        }
    }

    /**
     * GUI 实现此接口以接收存储数据
     */
    public interface ClientStorageScreenReceiver {
        void onClientStorageDataReceived(ClientStorageResponsePacket packet);
    }
}
