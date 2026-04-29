package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 同步农田盒数据包
 * 用于从服务器同步农田盒的雇佣数据、作物选择和区域大小到客户端
 */
public class SyncFarmlandDataPacket {
    // 客户端 -> 服务器：请求同步指定农田盒的数据
    public static class Request {
        private final BlockPos farmlandBoxPos;

        public Request(BlockPos farmlandBoxPos) {
            this.farmlandBoxPos = farmlandBoxPos;
        }

        public Request(FriendlyByteBuf buf) {
            this.farmlandBoxPos = Objects.requireNonNull(buf.readBlockPos());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(Objects.requireNonNull(farmlandBoxPos));
        }

        public static void handle(Request packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) {
                    Simukraft.LOGGER.debug("[SyncFarmlandDataPacket.Request] sync request for {}", packet.farmlandBoxPos);

                    // 从服务器加载农田数据
                    com.xiaoliang.simukraft.world.FarmlandHiredData.loadAllFarmlandData(player.server);

                    // 获取指定农田盒的数据
                    Map<BlockPos, UUID> allHiredFarmers = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
                    Map<BlockPos, String> allSelectedCrops = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedCrops();
                    Map<BlockPos, Integer> allSelectedAreas = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedAreas();
                    Map<BlockPos, FarmlandPlot> allSelectedPlots = com.xiaoliang.simukraft.world.FarmlandHiredData.getSelectedPlots();

                    UUID hiredFarmerUuid = allHiredFarmers.get(packet.farmlandBoxPos);
                    String selectedCrop = allSelectedCrops.get(packet.farmlandBoxPos);
                    Integer selectedArea = allSelectedAreas.get(packet.farmlandBoxPos);
                    FarmlandPlot selectedPlot = allSelectedPlots.get(packet.farmlandBoxPos);

                    // 获取NPC名称（如果存在）
                    String npcName = null;
                    if (hiredFarmerUuid != null) {
                        npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(player.server, hiredFarmerUuid);
                    }

                    // 发送响应给客户端
                    // 注意：如果没有选择区域，返回0而不是默认值10
                    Response response = new Response(
                            packet.farmlandBoxPos,
                            hiredFarmerUuid,
                            npcName,
                            selectedCrop,
                            selectedArea != null ? selectedArea : 0,
                            selectedPlot
                    );
                    NetworkManager.INSTANCE.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                    Simukraft.LOGGER.debug("[SyncFarmlandDataPacket.Request] sync response for {} - Farmer: {}, Crop: {}, Area: {}",
                            packet.farmlandBoxPos, hiredFarmerUuid != null ? "yes" : "no", selectedCrop, selectedArea);
                }
            });
            context.setPacketHandled(true);
        }
    }

    // 服务器 -> 客户端：响应同步请求
    public static class Response {
        private final BlockPos farmlandBoxPos;
        private final UUID hiredFarmerUuid;
        private final String npcName;
        private final String selectedCrop;
        private final int selectedArea;
        private final FarmlandPlot selectedPlot;

        public Response(BlockPos farmlandBoxPos, UUID hiredFarmerUuid, String npcName, String selectedCrop, int selectedArea) {
            this(farmlandBoxPos, hiredFarmerUuid, npcName, selectedCrop, selectedArea, null);
        }

        public Response(BlockPos farmlandBoxPos, UUID hiredFarmerUuid, String npcName, String selectedCrop, int selectedArea, FarmlandPlot selectedPlot) {
            this.farmlandBoxPos = farmlandBoxPos;
            this.hiredFarmerUuid = hiredFarmerUuid;
            this.npcName = npcName;
            this.selectedCrop = selectedCrop;
            this.selectedArea = selectedArea;
            this.selectedPlot = selectedPlot;
        }

        public Response(FriendlyByteBuf buf) {
            this.farmlandBoxPos = Objects.requireNonNull(buf.readBlockPos());
            boolean hasFarmer = buf.readBoolean();
            this.hiredFarmerUuid = hasFarmer ? Objects.requireNonNull(buf.readUUID()) : null;
            boolean hasName = buf.readBoolean();
            this.npcName = hasName ? Objects.requireNonNull(buf.readUtf()) : null;
            boolean hasCrop = buf.readBoolean();
            this.selectedCrop = hasCrop ? Objects.requireNonNull(buf.readUtf()) : null;
            this.selectedArea = buf.readInt();
            boolean hasPlot = buf.readBoolean();
            this.selectedPlot = hasPlot ? new FarmlandPlot(Objects.requireNonNull(buf.readBlockPos()), Objects.requireNonNull(buf.readBlockPos())) : null;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(Objects.requireNonNull(farmlandBoxPos));
            buf.writeBoolean(hiredFarmerUuid != null);
            if (hiredFarmerUuid != null) {
                buf.writeUUID(hiredFarmerUuid);
            }
            buf.writeBoolean(npcName != null);
            if (npcName != null) {
                buf.writeUtf(npcName);
            }
            buf.writeBoolean(selectedCrop != null);
            if (selectedCrop != null) {
                buf.writeUtf(selectedCrop);
            }
            buf.writeInt(selectedArea);
            buf.writeBoolean(selectedPlot != null);
            if (selectedPlot != null) {
                buf.writeBlockPos(selectedPlot.minPos());
                buf.writeBlockPos(selectedPlot.maxPos());
            }
        }

        public static void handle(Response packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                Simukraft.LOGGER.debug("[SyncFarmlandDataPacket.Response] received data for {} - Farmer: {}, Crop: {}, Area: {}",
                        packet.farmlandBoxPos, packet.hiredFarmerUuid != null ? "yes" : "no", packet.selectedCrop, packet.selectedArea);

                // 在客户端更新数据
                com.xiaoliang.simukraft.client.gui.FarmlandData.setHiredFarmerFromServer(
                        packet.farmlandBoxPos,
                        packet.hiredFarmerUuid,
                        packet.npcName
                );
                com.xiaoliang.simukraft.client.gui.FarmlandData.setSelectedCropFromServer(
                        packet.farmlandBoxPos,
                        packet.selectedCrop
                );
                com.xiaoliang.simukraft.client.gui.FarmlandData.setSelectedAreaFromServer(
                        packet.farmlandBoxPos,
                        packet.selectedArea
                );
                com.xiaoliang.simukraft.client.gui.FarmlandData.setSelectedPlotFromServer(
                        packet.farmlandBoxPos,
                        packet.selectedPlot
                );
            });
            context.setPacketHandled(true);
        }
    }
}
