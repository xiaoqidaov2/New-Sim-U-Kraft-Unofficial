package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.utils.FarmlandManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.function.Supplier;

public class StartFarmingPacket {
    private static final int MIN_AREA_SIZE = 1;
    private static final int MAX_AREA_SIZE = 64;
    private final BlockPos farmlandBoxPos;
    private final String crop;
    private final int areaSize;

    public StartFarmingPacket(BlockPos farmlandBoxPos, String crop, int areaSize) {
        this.farmlandBoxPos = farmlandBoxPos;
        this.crop = crop;
        this.areaSize = areaSize;
    }

    public StartFarmingPacket(FriendlyByteBuf buf) {
        this.farmlandBoxPos = Objects.requireNonNull(buf.readBlockPos());
        this.crop = Objects.requireNonNull(buf.readUtf());
        this.areaSize = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(Objects.requireNonNull(farmlandBoxPos));
        buf.writeUtf(Objects.requireNonNull(crop));
        buf.writeInt(areaSize);
    }

    public static void handle(StartFarmingPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                String crop = packet.crop == null ? "wheat" : packet.crop.trim().toLowerCase();
                int areaSize = packet.areaSize;
                
                Simukraft.LOGGER.debug("[StartFarmingPacket] Delegating farming request to FarmlandManager, player={}", player.getName().getString());
                
                if (!com.xiaoliang.simukraft.farmland.CropRegistry.isSupported(crop)) {
                    player.displayClientMessage(
                            Objects.requireNonNull(net.minecraft.network.chat.Component.translatable("message.simukraft.farming.failed").withStyle(style -> style.withColor(0xFF5555))),
                            false
                    );
                    return;
                }

                com.xiaoliang.simukraft.world.FarmlandHiredData.loadAllFarmlandData(player.server);
                boolean hasSavedPlot = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedPlot(packet.farmlandBoxPos) != null;
                if ((areaSize < MIN_AREA_SIZE || areaSize > MAX_AREA_SIZE) && !hasSavedPlot) {
                    player.displayClientMessage(
                            Objects.requireNonNull(net.minecraft.network.chat.Component.translatable("message.simukraft.farming.failed").withStyle(style -> style.withColor(0xFF5555))),
                            false
                    );
                    return;
                }
                if (areaSize < MIN_AREA_SIZE || areaSize > MAX_AREA_SIZE) {
                    areaSize = MIN_AREA_SIZE;
                }
                
                // 调用统一的管理器
                FarmlandManager.startFarming(player, packet.farmlandBoxPos, crop, areaSize);
            }
        });
        context.setPacketHandled(true);
    }
}
