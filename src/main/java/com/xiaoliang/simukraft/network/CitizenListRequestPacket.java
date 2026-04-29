package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.utils.NPCDataManager;
import com.xiaoliang.simukraft.utils.ResidentManager;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CityPermissionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 请求城市市民列表的数据包
 */
@SuppressWarnings({"null", "unused"})
public record CitizenListRequestPacket(BlockPos cityCorePos) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cityCorePos);
    }

    public static CitizenListRequestPacket decode(FriendlyByteBuf buf) {
        return new CitizenListRequestPacket(buf.readBlockPos());
    }

    public static void handle(CitizenListRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;

            MinecraftServer server = player.getServer();
            if (server == null) return;

            // 获取城市数据
            ServerLevel level = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return;

            CityData cityData = CityData.get(level);

            // 查找对应的城市
            UUID targetCityId = null;
            for (CityData.CityInfo city : cityData.getAllCities()) {
                if (city.getCityCorePos().equals(packet.cityCorePos())) {
                    targetCityId = city.getCityId();
                    break;
                }
            }

            if (targetCityId == null) {
                // 城市不存在，返回空列表
                CitizenListResponsePacket response = new CitizenListResponsePacket(new ArrayList<>());
                NetworkManager.INSTANCE.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                return;
            }

            // 使用权限管理器检查玩家是否有权限查看NPC列表（市长或官员）
            CityPermissionManager permManager = CityPermissionManager.getInstance();
            String playerName = player.getName().getString();
            boolean hasPermission = permManager.canViewNPCList(level, playerName, targetCityId);
            if (!hasPermission) {
                // 没有权限，返回空列表
                CitizenListResponsePacket response = new CitizenListResponsePacket(new ArrayList<>());
                NetworkManager.INSTANCE.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                return;
            }

            // 获取城市的市民UUID列表
            CityData.CityInfo cityInfo = cityData.getCity(targetCityId);
            List<UUID> citizenIds = cityInfo.getCitizenIds();

            // 收集NPC信息
            List<CitizenListResponsePacket.CitizenInfo> citizenInfos = new ArrayList<>();
            Set<UUID> addedNpcUuids = new HashSet<>();

            // 使用final变量存储targetCityId以便在lambda中使用
            final UUID finalCityId = targetCityId;
            final List<UUID> finalCitizenIds = citizenIds;

            // 从jobdata.sk文件加载职业信息（这是权威数据源）

            for (ServerLevel serverLevel : server.getAllLevels()) {
                // 使用getAllEntities获取所有实体，然后筛选CustomEntity
                for (var entity : serverLevel.getAllEntities()) {
                    if (entity instanceof CustomEntity npc) {
                        UUID npcUuid = npc.getUUID();
                        UUID npcCityId = npc.getCityId();

                        // 检查NPC是否属于该城市（通过UUID或cityId）
                        boolean isCitizen = finalCitizenIds.contains(npcUuid) ||
                                             (npcCityId != null && npcCityId.equals(finalCityId));

                        if (isCitizen) {
                            // 从V2存储获取职业（优先从工商业数据获取）
                            String job = getJobFromV2Storage(server, npcUuid);

                            // 处理null值情况
                            if (job == null || job.isEmpty()) {
                                job = "unemployed";
                            }

                            // 检查NPC是否有住宅
                            boolean hasResidence = ResidentManager.hasResidenceAssigned(server, npc.getFullName());
                            // 获取NPC皮肤路径
                            String skinPath = npc.getSkinPath();
                            // 获取NPC等级和经验值
                            int npcLevel = NPCDataManager.getNPCLevel(server, npcUuid);
                            int npcXp = NPCDataManager.getNPCXp(server, npcUuid);
                            citizenInfos.add(new CitizenListResponsePacket.CitizenInfo(
                                npcUuid,
                                npc.getName().getString(),
                                npc.getNpcId(),
                                hasResidence,
                                job,  // 发送原始英文职业，让客户端进行翻译
                                skinPath,
                                npcLevel,
                                npcXp
                            ));
                            addedNpcUuids.add(npcUuid);
                        }
                    }
                }
            }

            for (UUID npcUuid : NPCDataManager.getAllNPCUuids(server)) {
                if (addedNpcUuids.contains(npcUuid)) {
                    continue;
                }

                String npcCityIdStr = NPCDataManager.getNPCCityId(server, npcUuid);
                boolean isCitizen = finalCitizenIds.contains(npcUuid);
                if (!isCitizen && npcCityIdStr != null && !npcCityIdStr.isEmpty()) {
                    try {
                        isCitizen = UUID.fromString(npcCityIdStr).equals(finalCityId);
                    } catch (IllegalArgumentException ignored) {
                        isCitizen = false;
                    }
                }
                if (!isCitizen) {
                    continue;
                }

                String npcName = NPCDataManager.getNPCName(server, npcUuid);
                if (npcName == null || npcName.isEmpty()) {
                    continue;
                }
                String job = getJobFromV2Storage(server, npcUuid);
                if (job == null || job.isEmpty()) {
                    job = "unemployed";
                }
                citizenInfos.add(new CitizenListResponsePacket.CitizenInfo(
                        npcUuid,
                        npcName,
                        Math.abs(npcUuid.hashCode()),
                        ResidentManager.hasResidenceAssigned(server, npcUuid),
                        job,
                        NPCDataManager.getNPCSkinPath(server, npcUuid),
                        NPCDataManager.getNPCLevel(server, npcUuid),
                        NPCDataManager.getNPCXp(server, npcUuid)
                ));
                addedNpcUuids.add(npcUuid);
            }

            // 发送响应包
            CitizenListResponsePacket response = new CitizenListResponsePacket(citizenInfos);
            NetworkManager.INSTANCE.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        });
        context.setPacketHandled(true);
    }

    /**
     * 从V2存储获取职业信息
     * 优先从工商业数据获取，如果没有则使用NPC实体数据
     */
    private static String getJobFromV2Storage(MinecraftServer server, UUID npcUuid) {
        // 1. 首先检查商业建筑雇佣数据
        var commercialEmployees = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        for (var entry : commercialEmployees.entrySet()) {
            var hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid)) {
                return hireInfo.getJobType();
            }
        }

        // 2. 然后检查工业建筑雇佣数据
        var industrialEmployees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : industrialEmployees.entrySet()) {
            var hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid().equals(npcUuid)) {
                return hireInfo.getJobType();
            }
        }

        // 3. 最后检查V2统一雇佣存储
        var v2Assignments = com.xiaoliang.simukraft.employment.bridge.EmploymentLegacyBridge.loadAllHiredNPCs(server);
        var assignment = v2Assignments.get(npcUuid);
        if (assignment != null && assignment.jobType() != null) {
            return assignment.jobType().name().toLowerCase();
        }

        // 4. 如果没有找到，返回null（调用方会处理为unemployed）
        return null;
    }
}
