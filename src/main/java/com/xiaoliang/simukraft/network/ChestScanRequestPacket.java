package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 箱子扫描请求数据包
 * 客户端请求服务器扫描指定位置的箱子内容
 */
@SuppressWarnings("null")
public class ChestScanRequestPacket {
    private final BlockPos chestPos;

    public ChestScanRequestPacket(BlockPos chestPos) {
        this.chestPos = chestPos;
    }

    public ChestScanRequestPacket(FriendlyByteBuf buf) {
        this.chestPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(chestPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ServerLevel level = player.serverLevel();

            // 在服务器端扫描箱子
            Map<Block, Integer> chestContents = scanChestContents(level, chestPos);

            // 发送响应回客户端
            ChestScanResponsePacket response = new ChestScanResponsePacket(chestPos, chestContents);
            NetworkManager.sendToPlayer(response, player);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 在服务器端扫描箱子内容
     */
    private static Map<Block, Integer> scanChestContents(ServerLevel level, BlockPos chestPos) {
        Map<Block, Integer> contents = new HashMap<>();

        BlockState state = level.getBlockState(chestPos);
        BlockEntity blockEntity = level.getBlockEntity(chestPos);

        Container container = null;

        // 如果是箱子，使用ChestBlock.getContainer来获取完整容器（支持大箱子）
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            container = ChestBlock.getContainer(chestBlock, state, level, chestPos, true);
        } else if (blockEntity instanceof Container cont) {
            container = cont;
        }

        if (container != null) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                    Block block = blockItem.getBlock();
                    contents.merge(block, stack.getCount(), Integer::sum);
                }
            }
        }

        return contents;
    }
}
