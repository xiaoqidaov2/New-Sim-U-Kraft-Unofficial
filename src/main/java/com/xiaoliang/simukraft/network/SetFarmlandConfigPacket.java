package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 设置农田盒配置数据包
 * 用于将客户端的作物选择和区域选择同步到服务器
 */
@SuppressWarnings("null")
public class SetFarmlandConfigPacket {
    private final BlockPos farmlandBoxPos;
    private final String crop;
    private final int areaSize;
    private final FarmlandPlot plot;
    private final boolean hasCrop;
    private final boolean hasArea;
    private final boolean hasPlot;

    public SetFarmlandConfigPacket(BlockPos farmlandBoxPos, String crop, int areaSize) {
        this(farmlandBoxPos, crop, areaSize, null);
    }

    public SetFarmlandConfigPacket(BlockPos farmlandBoxPos, String crop, int areaSize, FarmlandPlot plot) {
        this.farmlandBoxPos = farmlandBoxPos;
        this.crop = crop;
        this.areaSize = areaSize;
        this.plot = plot;
        this.hasCrop = crop != null;
        this.hasArea = areaSize > 0;
        this.hasPlot = plot != null;
    }

    public SetFarmlandConfigPacket(FriendlyByteBuf buf) {
        this.farmlandBoxPos = Objects.requireNonNull(buf.readBlockPos());
        this.hasCrop = buf.readBoolean();
        this.crop = hasCrop ? Objects.requireNonNull(buf.readUtf()) : null;
        this.hasArea = buf.readBoolean();
        this.areaSize = hasArea ? buf.readInt() : 0;
        this.hasPlot = buf.readBoolean();
        this.plot = hasPlot ? new FarmlandPlot(Objects.requireNonNull(buf.readBlockPos()), Objects.requireNonNull(buf.readBlockPos())) : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(Objects.requireNonNull(farmlandBoxPos));
        buf.writeBoolean(hasCrop);
        if (hasCrop) {
            buf.writeUtf(Objects.requireNonNull(crop));
        }
        buf.writeBoolean(hasArea);
        if (hasArea) {
            buf.writeInt(areaSize);
        }
        buf.writeBoolean(hasPlot);
        if (hasPlot) {
            buf.writeBlockPos(Objects.requireNonNull(plot).minPos());
            buf.writeBlockPos(Objects.requireNonNull(plot).maxPos());
        }
    }

    public static void handle(SetFarmlandConfigPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                Simukraft.LOGGER.info("[SetFarmlandConfigPacket] Received config for farmland box at {} - Crop: {}, Area: {}",
                        packet.farmlandBoxPos, packet.crop, packet.areaSize);

                // 先加载现有数据，确保雇佣数据不丢失
                com.xiaoliang.simukraft.world.FarmlandHiredData.loadAllFarmlandData(player.server);

                // 在服务器端保存配置数据
                if (packet.hasCrop) {
                    com.xiaoliang.simukraft.world.FarmlandHiredData.setSelectedCrop(packet.farmlandBoxPos, packet.crop);
                }
                if (packet.hasArea) {
                    com.xiaoliang.simukraft.world.FarmlandHiredData.setSelectedArea(packet.farmlandBoxPos, packet.areaSize);
                }
                if (packet.hasPlot) {
                    BlockPos overlappingBox = com.xiaoliang.simukraft.world.FarmlandHiredData.findOverlappingPlotOwner(packet.farmlandBoxPos, packet.plot);
                    if (overlappingBox != null) {
                        player.displayClientMessage(
                                Objects.requireNonNull(net.minecraft.network.chat.Component.translatable("message.simukraft.farming.area_overlap", overlappingBox.getX(), overlappingBox.getY(), overlappingBox.getZ()).withStyle(style -> style.withColor(0xFF5555))),
                                false
                        );
                        Simukraft.LOGGER.warn("[SetFarmlandConfigPacket] Rejected overlapping farmland plot at {}, overlaps with {}", packet.farmlandBoxPos, overlappingBox);
                        return;
                    }
                    com.xiaoliang.simukraft.world.FarmlandHiredData.setSelectedPlot(packet.farmlandBoxPos, packet.plot);
                }

                // 立即保存到文件
                try {
                    com.xiaoliang.simukraft.world.FarmlandHiredData.saveAllFarmlandData(player.server);
                    Simukraft.LOGGER.info("[SetFarmlandConfigPacket] Config saved to server");
                } catch (Exception e) {
                    Simukraft.LOGGER.error("[SetFarmlandConfigPacket] Failed to save config: {}", e.getMessage());
                }
            }
        });
        context.setPacketHandled(true);
    }
}
