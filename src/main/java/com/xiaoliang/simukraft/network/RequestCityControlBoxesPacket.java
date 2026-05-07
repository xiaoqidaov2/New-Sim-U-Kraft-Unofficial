package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.building.ControlBoxDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.NetworkEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C→S 请求城市所有控制盒数据
 * 用于城市地图显示控制盒标记
 */
@SuppressWarnings({"null", "unused"})
public class RequestCityControlBoxesPacket {
    private final UUID cityId;

    public RequestCityControlBoxesPacket(UUID cityId) {
        this.cityId = cityId;
    }

    public RequestCityControlBoxesPacket(FriendlyByteBuf buf) {
        this.cityId = buf.readUUID();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(cityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            var server = player.serverLevel().getServer();
            List<ControlBoxInfo> controlBoxes = new ArrayList<>();

            // 加载所有类型的控制盒
            controlBoxes.addAll(loadControlBoxesByType(server, "industrial", cityId));
            controlBoxes.addAll(loadControlBoxesByType(server, "commercial", cityId));
            controlBoxes.addAll(loadControlBoxesByType(server, "residential", cityId));
            controlBoxes.addAll(loadControlBoxesByType(server, "public", cityId));
            controlBoxes.addAll(loadControlBoxesByType(server, "other", cityId));

            // 发送响应
            NetworkManager.sendToPlayer(new CityControlBoxesResponsePacket(controlBoxes), player);
        });
        ctx.get().setPacketHandled(true);
    }

    private List<ControlBoxInfo> loadControlBoxesByType(net.minecraft.server.MinecraftServer server, String type, UUID cityId) {
        List<ControlBoxInfo> result = new ArrayList<>();
        String subDir = getSubDirByType(type);
        if (subDir == null) return result;

        try {
            Path worldPath = server.getWorldPath(LevelResource.ROOT);
            Path controlBoxDir = worldPath.resolve("simukraft").resolve(subDir);

            if (!java.nio.file.Files.exists(controlBoxDir)) {
                return result;
            }

            File[] files = controlBoxDir.toFile().listFiles((dir, name) -> name.endsWith(".sk"));
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName().replace(".sk", "");
                    String[] parts = fileName.split("_");
                    if (parts.length == 3) {
                        try {
                            BlockPos pos = new BlockPos(
                                Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2])
                            );
                            ControlBoxData data = parseControlBoxFile(file, pos);
                            if (data != null && cityId.equals(data.cityId)) {
                                result.add(new ControlBoxInfo(pos, type, data.buildingName));
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    private String getSubDirByType(String type) {
        return switch (type) {
            case "industrial" -> "industrial";
            case "commercial" -> "commercial";
            case "residential" -> "residence";
            case "public" -> "public";
            case "other" -> "other";
            default -> null;
        };
    }

    private ControlBoxData parseControlBoxFile(File file, BlockPos pos) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            ControlBoxData data = new ControlBoxData();
            data.position = pos;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("type:")) {
                    data.type = line.substring(5).trim();
                } else if (line.startsWith("building_name:")) {
                    data.buildingName = line.substring(14).trim();
                } else if (line.startsWith("cityid:")) {
                    String uuidStr = line.substring(7).trim();
                    try {
                        data.cityId = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return data;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static class ControlBoxData {
        BlockPos position;
        String type;
        String buildingName;
        UUID cityId;
    }

    /**
     * 控制盒信息数据类
     */
    public static class ControlBoxInfo {
        public final BlockPos position;
        public final String type;
        public final String buildingName;

        public ControlBoxInfo(BlockPos position, String type, String buildingName) {
            this.position = position;
            this.type = type;
            this.buildingName = buildingName != null ? buildingName : "";
        }
    }
}
