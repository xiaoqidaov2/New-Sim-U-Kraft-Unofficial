package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
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
    private final FarmlandPlot plot;

    public StartFarmingPacket(BlockPos farmlandBoxPos, String crop, int areaSize) {
        this(farmlandBoxPos, crop, areaSize, null);
    }

    public StartFarmingPacket(BlockPos farmlandBoxPos, String crop, int areaSize, FarmlandPlot plot) {
        this.farmlandBoxPos = farmlandBoxPos;
        this.crop = crop;
        this.areaSize = areaSize;
        this.plot = plot;
    }

    public StartFarmingPacket(FriendlyByteBuf buf) {
        this.farmlandBoxPos = Objects.requireNonNull(buf.readBlockPos());
        this.crop = Objects.requireNonNull(buf.readUtf());
        this.areaSize = buf.readInt();
        boolean hasPlot = buf.readBoolean();
        this.plot = hasPlot
                ? new FarmlandPlot(Objects.requireNonNull(buf.readBlockPos()), Objects.requireNonNull(buf.readBlockPos()))
                : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(Objects.requireNonNull(farmlandBoxPos));
        buf.writeUtf(Objects.requireNonNull(crop));
        buf.writeInt(areaSize);
        buf.writeBoolean(plot != null);
        if (plot != null) {
            buf.writeBlockPos(Objects.requireNonNull(plot.minPos()));
            buf.writeBlockPos(Objects.requireNonNull(plot.maxPos()));
        }
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
                if (packet.plot != null) {
                    BlockPos overlappingBox = com.xiaoliang.simukraft.world.FarmlandHiredData.findOverlappingPlotOwner(
                            packet.farmlandBoxPos,
                            packet.plot
                    );
                    if (overlappingBox != null) {
                        player.displayClientMessage(
                                Objects.requireNonNull(net.minecraft.network.chat.Component.translatable(
                                        "message.simukraft.farming.area_overlap",
                                        overlappingBox.getX(),
                                        overlappingBox.getY(),
                                        overlappingBox.getZ()
                                ).withStyle(style -> style.withColor(0xFF5555))),
                                false
                        );
                        return;
                    }
                    com.xiaoliang.simukraft.world.FarmlandHiredData.setSelectedPlot(packet.farmlandBoxPos, packet.plot);
                    com.xiaoliang.simukraft.world.FarmlandHiredData.setSelectedArea(
                            packet.farmlandBoxPos,
                            Math.max(packet.plot.widthX(), packet.plot.depthZ())
                    );
                }

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
                
                com.xiaoliang.simukraft.world.FarmlandHiredData.setSelectedCrop(packet.farmlandBoxPos, crop);

                // 调用统一的管理器
                FarmlandManager.startFarming(player, packet.farmlandBoxPos, crop, areaSize);
            }
        });
        context.setPacketHandled(true);
    }
}
