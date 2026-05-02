package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.world.CityPermissionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class HireFarmerPacket {
    private final BlockPos farmlandBoxPos;
    private final UUID npcUuid;

    public HireFarmerPacket(BlockPos farmlandBoxPos, UUID npcUuid) {
        this.farmlandBoxPos = farmlandBoxPos;
        this.npcUuid = npcUuid;
    }

    public HireFarmerPacket(FriendlyByteBuf buf) {
        this.farmlandBoxPos = buf.readBlockPos();
        this.npcUuid = buf.readUUID();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(farmlandBoxPos);
        buf.writeUUID(npcUuid);
    }

    public static void handle(HireFarmerPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                Simukraft.LOGGER.debug("[HireFarmerPacket] request player={}, farmlandBoxPos={}, npcUuid={}",
                        player.getName().getString(), packet.farmlandBoxPos, packet.npcUuid);

                // 检查权限 - 只有市长或官员可以雇佣NPC（使用玩家名）
                CityPermissionManager permManager = CityPermissionManager.getInstance();
                ServerLevel level = player.serverLevel();
                String playerName = player.getName().getString();

                if (!permManager.canManageNPCs(level, playerName)) {
                    player.displayClientMessage(
                        Component.translatable("message.city.no_permission_hire_npc")
                            .withStyle(ChatFormatting.RED),
                        false
                    );
                    Simukraft.LOGGER.debug("[HireFarmerPacket] Permission denied for player {}", player.getName().getString());
                    return;
                }

                // 在服务器端设置雇佣数据
                com.xiaoliang.simukraft.world.FarmlandHiredData.setHiredFarmer(packet.farmlandBoxPos, packet.npcUuid);

                // 获取NPC的城市ID，并更新农田盒的SK文件
                var npc = com.xiaoliang.simukraft.world.FarmlandHiredData.findNPCByUuid(player.server, packet.npcUuid);
                if (npc != null) {
                    UUID cityId = npc.getCityId();
                    if (cityId != null) {
                        // 更新农田盒SK文件的城市ID
                        com.xiaoliang.simukraft.utils.FileUtils.updateFarmlandBoxCityId(player.server, packet.farmlandBoxPos, cityId);
                        Simukraft.LOGGER.debug("[HireFarmerPacket] Updated farmland box cityId prefix={}", cityId.toString().substring(0, 8));
                    } else {
                        Simukraft.LOGGER.debug("[HireFarmerPacket] NPC has no cityId, skip farmland box city sync");
                    }

                    // 设置NPC职业为农民
                    npc.setJob("farmer");
                    Simukraft.LOGGER.debug("[HireFarmerPacket] Set NPC job to farmer: {}", npc.getFullName());
                }

                // 立即保存到文件
                try {
                    com.xiaoliang.simukraft.world.FarmlandHiredData.saveAllFarmlandData(player.server);
                    Simukraft.LOGGER.debug("[HireFarmerPacket] Farmer hire data saved");
                } catch (Exception e) {
                    Simukraft.LOGGER.error("[HireFarmerPacket] Failed to save hire data", e);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
