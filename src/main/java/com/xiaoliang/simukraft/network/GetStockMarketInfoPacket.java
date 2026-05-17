package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.StockMarketService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class GetStockMarketInfoPacket {
    private final BlockPos controlBoxPos;

    public GetStockMarketInfoPacket(BlockPos controlBoxPos) {
        this.controlBoxPos = controlBoxPos;
    }

    public GetStockMarketInfoPacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
    }
@SuppressWarnings("null")
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
    }

    public static GetStockMarketInfoPacket decode(FriendlyByteBuf buf) {
        return new GetStockMarketInfoPacket(buf);
    }

    public static void handle(GetStockMarketInfoPacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) {
                return;
            }

            NetworkManager.sendToPlayer(new StockMarketInfoResponsePacket(
                    message.controlBoxPos,
                    StockMarketService.createSnapshot(player.serverLevel(), player)
            ), player);
        });
        context.get().setPacketHandled(true);
    }
}
