package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.building.ControlBoxDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 请求配方同步数据包
 * 客户端打开界面时请求服务器同步当前配方
 */
@SuppressWarnings("null")
public class RequestRecipeSyncPacket {
    private final BlockPos pos;

    public RequestRecipeSyncPacket(BlockPos pos) {
        this.pos = pos;
    }

    public RequestRecipeSyncPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            MinecraftServer server = player.getServer();
            if (server == null) return;

            // 从服务器获取当前配方
            String selectedRecipe = ControlBoxDataManager.getSelectedRecipe(server, pos);

            // 发送同步数据包回客户端
            NetworkManager.INSTANCE.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new SyncRecipePacket(pos, selectedRecipe != null ? selectedRecipe : "", selectedRecipe != null)
            );
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }
}
