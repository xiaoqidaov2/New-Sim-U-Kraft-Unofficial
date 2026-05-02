package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.BlockFillScreen;
import com.xiaoliang.simukraft.client.gui.BlockReplacementScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 箱子扫描响应数据包
 * 服务器返回箱子内容给客户端
 */
@SuppressWarnings("null")
public class ChestScanResponsePacket {
    private final BlockPos chestPos;
    private final Map<Block, Integer> chestContents;

    public ChestScanResponsePacket(BlockPos chestPos, Map<Block, Integer> chestContents) {
        this.chestPos = chestPos;
        this.chestContents = chestContents;
    }

    public ChestScanResponsePacket(FriendlyByteBuf buf) {
        this.chestPos = buf.readBlockPos();
        int size = buf.readInt();
        this.chestContents = new HashMap<>();
        for (int i = 0; i < size; i++) {
            int blockId = buf.readInt();
            int count = buf.readInt();
            BlockState state = Block.stateById(blockId);
            if (state != null) {
                this.chestContents.put(state.getBlock(), count);
            }
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(chestPos);
        buf.writeInt(chestContents.size());
        for (Map.Entry<Block, Integer> entry : chestContents.entrySet()) {
            buf.writeInt(Block.getId(entry.getKey().defaultBlockState()));
            buf.writeInt(entry.getValue());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 在客户端更新箱子内容（替换界面和填充界面）
            BlockReplacementScreen.updateChestContents(chestPos, chestContents);
            BlockFillScreen.updateChestContents(chestPos, chestContents);
        });
        ctx.get().setPacketHandled(true);
    }
}
