package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.utils.BlockReplacementHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 方块替换请求数据包
 * 客户端发送选区内的方块替换请求到服务器
 */
@SuppressWarnings("null")
public class BlockReplacementPacket {
    private final BlockPos selectionStart;
    private final BlockPos selectionEnd;
    private final BlockPos chestPos;
    private final Map<Block, Block> replacementMap; // 原方块 -> 目标方块

    public BlockReplacementPacket(BlockPos selectionStart, BlockPos selectionEnd, BlockPos chestPos, Map<Block, Block> replacementMap) {
        this.selectionStart = Objects.requireNonNull(selectionStart);
        this.selectionEnd = Objects.requireNonNull(selectionEnd);
        this.chestPos = Objects.requireNonNull(chestPos);
        this.replacementMap = Objects.requireNonNull(replacementMap);
    }

    public BlockReplacementPacket(FriendlyByteBuf buf) {
        this.selectionStart = Objects.requireNonNull(buf.readBlockPos());
        this.selectionEnd = Objects.requireNonNull(buf.readBlockPos());
        this.chestPos = Objects.requireNonNull(buf.readBlockPos());
        int size = buf.readInt();
        this.replacementMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            Block fromBlock = Block.stateById(buf.readInt()).getBlock();
            Block toBlock = Block.stateById(buf.readInt()).getBlock();
            this.replacementMap.put(fromBlock, toBlock);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(selectionStart);
        buf.writeBlockPos(selectionEnd);
        buf.writeBlockPos(chestPos);
        buf.writeInt(replacementMap.size());
        for (Map.Entry<Block, Block> entry : replacementMap.entrySet()) {
            buf.writeInt(Block.getId(entry.getKey().defaultBlockState()));
            buf.writeInt(Block.getId(entry.getValue().defaultBlockState()));
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ServerLevel level = player.serverLevel();

            // 执行方块替换
            BlockReplacementHandler.handleReplacement(player, level, selectionStart, selectionEnd, chestPos, replacementMap);
        });
        ctx.get().setPacketHandled(true);
    }
}
