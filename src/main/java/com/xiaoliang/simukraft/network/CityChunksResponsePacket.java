package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.ClientCityChunkData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityChunksResponsePacket {
    private final UUID currentCityId;
    private final Map<UUID, Set<Long>> allCityChunks;

    public CityChunksResponsePacket(UUID currentCityId, Map<UUID, Set<Long>> allCityChunks) {
        this.currentCityId = currentCityId;
        this.allCityChunks = allCityChunks;
    }

    public CityChunksResponsePacket(FriendlyByteBuf buf) {
        this.currentCityId = buf.readUUID();
        int cityCount = buf.readInt();
        this.allCityChunks = new HashMap<>(cityCount);
        for (int i = 0; i < cityCount; i++) {
            UUID cityId = buf.readUUID();
            int chunkCount = buf.readInt();
            Set<Long> chunks = new HashSet<>(chunkCount);
            for (int j = 0; j < chunkCount; j++) {
                chunks.add(buf.readLong());
            }
            this.allCityChunks.put(cityId, chunks);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(this.currentCityId);
        buf.writeInt(this.allCityChunks.size());
        for (Map.Entry<UUID, Set<Long>> entry : this.allCityChunks.entrySet()) {
            buf.writeUUID(entry.getKey());
            buf.writeInt(entry.getValue().size());
            for (long chunkLong : entry.getValue()) {
                buf.writeLong(chunkLong);
            }
        }
    }

    public static CityChunksResponsePacket decode(FriendlyByteBuf buf) {
        return new CityChunksResponsePacket(buf);
    }

    public static void handle(CityChunksResponsePacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 只在客户端处理
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                // 更新客户端城市区块数据
                ClientCityChunkData.getInstance().updateAllCityChunks(message.currentCityId, message.allCityChunks);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
