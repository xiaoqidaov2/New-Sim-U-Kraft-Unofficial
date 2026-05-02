package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.utils.NPCDataManager;
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
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 请求居民信息的网络包（客户端 -> 服务器）
 */
@SuppressWarnings("null")
public class RequestResidentInfoPacket {
    private final BlockPos controlBoxPos;
    private final String controlBoxType;

    public RequestResidentInfoPacket(BlockPos pos) {
        this(pos, "residential");
    }

    public RequestResidentInfoPacket(BlockPos pos, String type) {
        this.controlBoxPos = pos;
        this.controlBoxType = type;
    }

    public RequestResidentInfoPacket(FriendlyByteBuf buf) {
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

            String residentName = getResidentNameFromServer(player, controlBoxPos, controlBoxType);

            // 发送响应给客户端
            NetworkManager.INSTANCE.sendTo(
                new ResidentInfoResponsePacket(controlBoxPos, residentName),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 从服务器读取居民名字
     * 新的sk文件格式使用位置作为文件名: x_y_z.sk
     */
    private String getResidentNameFromServer(ServerPlayer player, BlockPos pos, String type) {
        try {
            ServerLevel level = player.serverLevel();
            Path worldPath = level.getServer().getWorldPath(LevelResource.ROOT);

            // 根据类型确定子目录
            String subDir = getSubDirByType(type);
            if (subDir == null) {
                subDir = "residence"; // 默认使用residence目录
            }

            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);

            // 新的sk文件名格式: x_y_z.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            File skFile = controlBoxDir.resolve(fileName).toFile();

            if (!skFile.exists()) {
                return null;
            }

            // 读取sk文件内容，使用UTF-8编码
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(skFile), StandardCharsets.UTF_8))) {
                String line;
                UUID residentUuid = null;
                String residentName = null;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("resident_uuid:")) {
                        String uuidStr = line.substring("resident_uuid:".length()).trim();
                        if (!uuidStr.isEmpty()) {
                            try {
                                residentUuid = UUID.fromString(uuidStr);
                            } catch (IllegalArgumentException e) {
                                // UUID格式错误
                            }
                        }
                    } else if (line.startsWith("resident:")) {
                        // 直接读取resident字段的居民名字
                        residentName = line.substring("resident:".length()).trim();
                    }
                }

                // 优先使用resident_uuid获取NPC名字（更准确）
                if (residentUuid != null) {
                    String nameFromUuid = NPCDataManager.getNPCNameByUUID(level.getServer(), residentUuid);
                    if (nameFromUuid != null && !nameFromUuid.isEmpty()) {
                        return nameFromUuid;
                    }
                }

                // 如果没有resident_uuid或无法获取名字，直接返回resident字段的值
                if (residentName != null && !residentName.isEmpty()) {
                    return residentName;
                }
            }

        } catch (Exception e) {
            Simukraft.LOGGER.error("[RequestResidentInfoPacket] Failed to get resident name: {}", e.getMessage());
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
