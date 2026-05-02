package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.NetworkEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 请求控制盒信息的网络包（客户端 -> 服务器）
 */
@SuppressWarnings("null")
public class RequestControlBoxInfoPacket {
    private final BlockPos controlBoxPos;
    private final String controlBoxType;

    public RequestControlBoxInfoPacket(BlockPos pos, String type) {
        this.controlBoxPos = pos;
        this.controlBoxType = type;
    }

    public RequestControlBoxInfoPacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
        this.controlBoxType = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controlBoxPos);
        buf.writeUtf(controlBoxType);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            String buildingName = getBuildingNameFromServer(player, controlBoxPos, controlBoxType);

            // 发送响应给客户端
            NetworkManager.INSTANCE.sendTo(
                new ControlBoxInfoResponsePacket(controlBoxPos, buildingName),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 从服务器读取建筑名称
     */
    private String getBuildingNameFromServer(ServerPlayer player, BlockPos pos, String type) {
        try {
            ServerLevel level = player.serverLevel();
            Path worldPath = level.getServer().getWorldPath(LevelResource.ROOT);
            
            // 根据类型确定子目录
            String subDir = getSubDirByType(type);
            if (subDir == null) return null;
            
            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);

            // 构建控制盒对应的sk文件名: x_y_z.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            File skFile = controlBoxDir.resolve(fileName).toFile();

            if (!skFile.exists()) {
                return null;
            }

            // 读取sk文件内容，使用UTF-8编码，查找building_name字段
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(skFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("building_name:")) {
                        String buildingValue = line.substring("building_name:".length()).trim();
                        if (!buildingValue.isEmpty()) {
                            return buildingValue;
                        }
                    }
                }
            }

        } catch (Exception e) {
            Simukraft.LOGGER.error("[RequestControlBoxInfoPacket] Failed to get building name: {}", e.getMessage());
        }

        return null;
    }
    
    /**
     * 根据类型获取子目录
     */
    private String getSubDirByType(String type) {
        return switch (type) {
            case "residential", "residence" -> "residence";
            case "commercial", "business" -> "commercial";
            case "industrial", "industry" -> "industrial";
            case "other" -> "other";
            default -> null;
        };
    }
}
