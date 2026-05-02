package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C 容器列表响应包 — 返回客户端盒子绑定的容器信息
 */
@SuppressWarnings("null")
public class ContainerListResponsePacket {
    private final BlockPos clientBlockPos;
    private final List<ContainerEntry> entries;

    public ContainerListResponsePacket(BlockPos clientBlockPos, List<ContainerEntry> entries) {
        this.clientBlockPos = clientBlockPos;
        this.entries = new ArrayList<>(entries);
    }

    public ContainerListResponsePacket(FriendlyByteBuf buf) {
        this.clientBlockPos = buf.readBlockPos();
        int count = buf.readVarInt();
        this.entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            String blockName = buf.readUtf();
            int kinds = buf.readVarInt();
            int total = buf.readVarInt();
            entries.add(new ContainerEntry(pos, blockName, kinds, total));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(clientBlockPos);
        buf.writeVarInt(entries.size());
        for (ContainerEntry entry : entries) {
            buf.writeBlockPos(entry.pos);
            buf.writeUtf(entry.blockName);
            buf.writeVarInt(entry.kinds);
            buf.writeVarInt(entry.total);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof ContainerListReceiver receiver) {
                receiver.onContainerListReceived(this);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getClientBlockPos() { return clientBlockPos; }
    public List<ContainerEntry> getEntries() { return entries; }

    public record ContainerEntry(BlockPos pos, String blockName, int kinds, int total) {}

    public interface ContainerListReceiver {
        void onContainerListReceived(ContainerListResponsePacket packet);
    }
}
