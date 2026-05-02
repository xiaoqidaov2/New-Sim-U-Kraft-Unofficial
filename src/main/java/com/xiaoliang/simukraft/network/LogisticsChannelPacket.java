package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.world.LogisticsData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C→S 频道操作包：创建/删除/切换启用频道。
 * 支持NBT物品传输
 */
@SuppressWarnings("null")
public class LogisticsChannelPacket {

    public enum Action { CREATE, DELETE, TOGGLE }

    private final Action action;
    private final BlockPos warehouseBlockPos;

    // CREATE 用
    private final UUID targetClientId;
    private final String channelName;
    private final String direction; // "SEND" or "RECEIVE"
    private final List<ItemStack> itemFilters; // 改为存储完整的ItemStack（包含NBT）

    // DELETE / TOGGLE 用
    private final UUID channelId;

    /** 创建频道 */
    public static LogisticsChannelPacket create(BlockPos warehousePos, UUID targetClientId,
                                                  String name, String direction, List<ItemStack> itemFilters) {
        return new LogisticsChannelPacket(Action.CREATE, warehousePos, targetClientId, name, direction, itemFilters, null);
    }

    /** 删除频道 */
    public static LogisticsChannelPacket delete(BlockPos warehousePos, UUID channelId) {
        return new LogisticsChannelPacket(Action.DELETE, warehousePos, null, "", "", List.of(), channelId);
    }

    /** 切换频道启用状态 */
    public static LogisticsChannelPacket toggle(BlockPos warehousePos, UUID channelId) {
        return new LogisticsChannelPacket(Action.TOGGLE, warehousePos, null, "", "", List.of(), channelId);
    }

    private LogisticsChannelPacket(Action action, BlockPos warehouseBlockPos, UUID targetClientId,
                                    String channelName, String direction, List<ItemStack> itemFilters, UUID channelId) {
        this.action = action;
        this.warehouseBlockPos = warehouseBlockPos;
        this.targetClientId = targetClientId;
        this.channelName = channelName;
        this.direction = direction;
        this.itemFilters = itemFilters;
        this.channelId = channelId;
    }

    public LogisticsChannelPacket(FriendlyByteBuf buf) {
        this.action = Action.values()[buf.readVarInt()];
        this.warehouseBlockPos = buf.readBlockPos();
        this.targetClientId = buf.readBoolean() ? buf.readUUID() : null;
        this.channelName = buf.readUtf(128);
        this.direction = buf.readUtf(16);

        // 读取物品过滤器（包含NBT）
        int count = buf.readVarInt();
        this.itemFilters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // 读取物品ID
            String itemId = buf.readUtf(256);
            // 读取是否有NBT
            boolean hasNBT = buf.readBoolean();
            CompoundTag nbtTag = null;
            if (hasNBT) {
                nbtTag = buf.readNbt();
            }

            // 创建ItemStack
            ResourceLocation itemLocation = ResourceLocation.tryParse(itemId);
            Item item = itemLocation == null ? null : ForgeRegistries.ITEMS.getValue(itemLocation);
            if (item != null) {
                ItemStack stack = new ItemStack(item);
                if (nbtTag != null) {
                    stack.setTag(nbtTag);
                }
                this.itemFilters.add(stack);
            }
        }

        this.channelId = buf.readBoolean() ? buf.readUUID() : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(action.ordinal());
        buf.writeBlockPos(warehouseBlockPos);
        buf.writeBoolean(targetClientId != null);
        if (targetClientId != null) buf.writeUUID(targetClientId);
        buf.writeUtf(channelName, 128);
        buf.writeUtf(direction, 16);

        // 写入物品过滤器（包含NBT）
        buf.writeVarInt(itemFilters.size());
        for (ItemStack stack : itemFilters) {
            // 写入物品ID
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            buf.writeUtf(key != null ? key.toString() : "minecraft:air", 256);

            // 写入NBT数据
            CompoundTag nbtTag = stack.getTag();
            buf.writeBoolean(nbtTag != null);
            if (nbtTag != null) {
                buf.writeNbt(nbtTag);
            }
        }

        buf.writeBoolean(channelId != null);
        if (channelId != null) buf.writeUUID(channelId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            LogisticsData data = LogisticsData.get(level);
            LogisticsData.Warehouse warehouse = data.getWarehouseByBlockPos(warehouseBlockPos);
            if (warehouse == null) return;

            switch (action) {
                case CREATE -> {
                    if (targetClientId == null) return;
                    LogisticsData.ChannelDirection dir = "RECEIVE".equals(direction)
                            ? LogisticsData.ChannelDirection.RECEIVE
                            : LogisticsData.ChannelDirection.SEND;

                    LogisticsData.LogisticsChannel channel = new LogisticsData.LogisticsChannel(
                            UUID.randomUUID(), channelName, targetClientId, dir);

                    // 直接使用传输过来的ItemStack（包含NBT）
                    for (ItemStack stack : itemFilters) {
                        if (!stack.isEmpty()) {
                            channel.getItemFilters().add(stack.copy());
                        }
                    }

                    warehouse.getChannels().add(channel);
                    data.setDirty();
                    Simukraft.LOGGER.info("[Logistics] 创建频道: {} 方向={} 物品数={}", 
                        channelName, dir, channel.getItemFilters().size());
                }
                case DELETE -> {
                    if (channelId == null) return;
                    warehouse.getChannels().removeIf(ch -> ch.getChannelId().equals(channelId));
                    data.setDirty();
                    Simukraft.LOGGER.info("[Logistics] 删除频道: {}", channelId);
                }
                case TOGGLE -> {
                    if (channelId == null) return;
                    LogisticsData.LogisticsChannel ch = warehouse.getChannel(channelId);
                    if (ch != null) {
                        ch.setEnabled(!ch.isEnabled());
                        data.setDirty();
                        Simukraft.LOGGER.info("[Logistics] 切换频道: {} -> {}", ch.getName(), ch.isEnabled());
                    }
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
