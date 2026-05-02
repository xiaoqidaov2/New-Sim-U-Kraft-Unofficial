package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.building.PlacedBuildingManager;
import com.xiaoliang.simukraft.world.CityChunkData;
import com.xiaoliang.simukraft.world.CityPermissionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 拆除建筑网络包（客户端 -> 服务器）
 * menglannnn: 仅本城市官员和市长可以拆除本城市建筑
 */
@SuppressWarnings("null")
public class DemolishBuildingPacket {
    private static final Logger LOGGER = LogManager.getLogger();
    private final BlockPos controlBoxPos;

    public DemolishBuildingPacket(BlockPos pos) {
        this.controlBoxPos = pos;
    }

    public DemolishBuildingPacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ServerLevel level = player.serverLevel();

            // 获取建筑数据
            PlacedBuildingManager.PlacedBuildingData building =
                PlacedBuildingManager.getBuildingByControlBox(controlBoxPos);

            if (building == null) {
                player.sendSystemMessage(Component.translatable("message.demolish.no_building"));
                return;
            }

            // simukraft: 权限检查 - 仅本城市官员和市长可以拆除
            if (!hasDemolishPermission(player, level, controlBoxPos)) {
                player.sendSystemMessage(Component.translatable("message.demolish.no_permission"));
                return;
            }

            // simukraft: 清除控制盒数据（删除sk文件）
            deleteControlBoxData(level, controlBoxPos, building.category);

            // simukraft: 直接拆除建筑，不返还材料
            boolean success = PlacedBuildingManager.demolishBuilding(building.buildingId, level);

            if (success) {
                player.sendSystemMessage(Component.translatable("message.demolish.success",
                    building.buildingName));
            } else {
                player.sendSystemMessage(Component.translatable("message.demolish.failed"));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 检查玩家是否有拆除权限（menglannnn: 必须是建筑所在城市的官员或市长）
     * @param player 玩家
     * @param level 服务器世界
     * @param controlBoxPos 控制盒位置
     * @return 是否有权限
     */
    private boolean hasDemolishPermission(ServerPlayer player, ServerLevel level, BlockPos controlBoxPos) {
        // 获取建筑所在区块的城市ID
        ChunkPos chunkPos = new ChunkPos(controlBoxPos);
        CityChunkData chunkData = CityChunkData.get(level);
        UUID cityId = chunkData.getChunkOwner(chunkPos.toLong());

        if (cityId == null) {
            // 建筑不在任何城市范围内，允许拆除（可能是野外建筑）
            LOGGER.debug("[DemolishBuildingPacket] 建筑不在任何城市范围内，允许拆除: {}", controlBoxPos);
            return true;
        }

        // 检查玩家是否是该城市的官员或市长
        CityPermissionManager.PermissionLevel permLevel =
            CityPermissionManager.getInstance().getPermissionLevel(level, player.getName().getString(), cityId);

        boolean hasPermission = permLevel.isAtLeast(CityPermissionManager.PermissionLevel.OFFICIAL);

        if (!hasPermission) {
            LOGGER.info("[DemolishBuildingPacket] 玩家 {} 没有权限拆除城市 {} 的建筑",
                player.getName().getString(), cityId);
        }

        return hasPermission;
    }

    /**
     * 删除控制盒数据（menglannnn: 根据类别删除对应的sk文件）
     * @param level 世界
     * @param controlBoxPos 控制盒位置
     * @param category 建筑类别（residential/commercial/industrial/other）
     */
    private void deleteControlBoxData(ServerLevel level, BlockPos controlBoxPos, String category) {
        try {
            Path worldPath = level.getServer().getWorldPath(Objects.requireNonNull(LevelResource.ROOT));

            // 根据类别确定目录
            String dirName;
            switch (category) {
                case "residential":
                    dirName = "residence";
                    break;
                case "commercial":
                    dirName = "commercial";
                    break;
                case "industrial":
                    dirName = "industrial";
                    break;
                case "other":
                    dirName = "other";
                    break;
                default:
                    dirName = category; // 使用类别名作为目录名
            }

            Path dataDir = worldPath.resolve("simukraft").resolve(dirName);

            if (!Files.exists(dataDir)) {
                return;
            }

            // 构建sk文件名: x_y_z.sk
            String fileName = controlBoxPos.getX() + "_" + controlBoxPos.getY() + "_" + controlBoxPos.getZ() + ".sk";
            Path skFile = dataDir.resolve(fileName);

            if (Files.exists(skFile)) {
                Files.delete(skFile);
                LOGGER.info("[DemolishBuildingPacket] 已删除{}控制盒数据文件: {}", category, skFile);
            }
        } catch (Exception e) {
            LOGGER.error("[DemolishBuildingPacket] 删除{}控制盒数据文件失败: {}", category, e.getMessage(), e);
        }
    }
}
