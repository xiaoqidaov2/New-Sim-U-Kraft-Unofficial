package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.ChannelCreateScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C 频道物品数据响应
 * 服务器返回扫描到的物品数据
 */
@SuppressWarnings("null")
public class ChannelItemsResponsePacket {
    private final BlockPos warehouseBlockPos;
    private final boolean isSend;
    private final List<ItemEntry> items;

    public ChannelItemsResponsePacket(BlockPos warehouseBlockPos, boolean isSend, List<RequestChannelItemsPacket.ItemEntry> itemEntries) {
        this.warehouseBlockPos = warehouseBlockPos;
        this.isSend = isSend;
        this.items = new ArrayList<>();
        for (RequestChannelItemsPacket.ItemEntry entry : itemEntries) {
            this.items.add(new ItemEntry(entry.itemId, entry.count, entry.nbt));
        }
    }

    public ChannelItemsResponsePacket(FriendlyByteBuf buf) {
        this.warehouseBlockPos = buf.readBlockPos();
        this.isSend = buf.readBoolean();
        int count = buf.readVarInt();
        this.items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String itemId = buf.readUtf(256);
            int itemCount = buf.readVarInt();
            CompoundTag nbt = buf.readBoolean() ? buf.readNbt() : null;
            this.items.add(new ItemEntry(itemId, itemCount, nbt));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(warehouseBlockPos);
        buf.writeBoolean(isSend);
        buf.writeVarInt(items.size());
        for (ItemEntry entry : items) {
            buf.writeUtf(entry.itemId, 256);
            buf.writeVarInt(entry.count);
            buf.writeBoolean(entry.nbt != null);
            if (entry.nbt != null) {
                buf.writeNbt(entry.nbt);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();

            // 通知当前打开的 ChannelCreateScreen
            if (mc.screen instanceof ChannelCreateScreenReceiver receiver) {
                // 转换为 ChannelCreateScreen.ItemEntry 列表
                List<ChannelCreateScreen.ItemEntry> screenItems = new ArrayList<>();
                for (ItemEntry entry : items) {
                    net.minecraft.resources.ResourceLocation itemLocation =
                            net.minecraft.resources.ResourceLocation.tryParse(entry.itemId);
                    net.minecraft.world.item.Item item = itemLocation == null
                            ? null
                            : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(itemLocation);
                    if (item != null) {
                        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
                        if (entry.nbt != null) {
                            stack.setTag(entry.nbt);
                        }
                        screenItems.add(new ChannelCreateScreen.ItemEntry(stack, entry.itemId, entry.count));
                    }
                }
                receiver.onChannelItemsReceived(screenItems);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getWarehouseBlockPos() {
        return warehouseBlockPos;
    }

    public boolean isSend() {
        return isSend;
    }

    public List<ItemEntry> getItems() {
        return items;
    }

    /**
     * 物品条目
     */
    public static class ItemEntry {
        public final String itemId;
        public final int count;
        public final CompoundTag nbt;

        public ItemEntry(String itemId, int count, CompoundTag nbt) {
            this.itemId = itemId;
            this.count = count;
            this.nbt = nbt;
        }
    }

    /**
     * GUI 实现此接口以接收物品数据
     */
    public interface ChannelCreateScreenReceiver {
        void onChannelItemsReceived(List<ChannelCreateScreen.ItemEntry> items);
    }
}
