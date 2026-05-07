package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.NPCPathDebugClientCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * NPC寻路调试同步包
 */
@SuppressWarnings("null")
public class SyncNPCPathDebugPacket {
    private final UUID npcUuid;
    private final int currentIndex;
    private final List<Vec3> nodes;
    private final List<String> nodeTypes;
    private final List<Double> nodeCosts;
    private final List<String> nodeCostReasons;
    private final boolean clear;
    private final boolean blocked;

    public SyncNPCPathDebugPacket(UUID npcUuid, int currentIndex, List<Vec3> nodes, List<String> nodeTypes,
                                  List<Double> nodeCosts, List<String> nodeCostReasons, boolean clear, boolean blocked) {
        this.npcUuid = npcUuid;
        this.currentIndex = currentIndex;
        this.nodes = nodes;
        this.nodeTypes = nodeTypes;
        this.nodeCosts = nodeCosts;
        this.nodeCostReasons = nodeCostReasons;
        this.clear = clear;
        this.blocked = blocked;
    }

    public SyncNPCPathDebugPacket(FriendlyByteBuf buf) {
        this.npcUuid = buf.readUUID();
        this.currentIndex = buf.readInt();
        this.clear = buf.readBoolean();
        this.blocked = buf.readBoolean();
        int size = buf.readVarInt();
        this.nodes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            this.nodes.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        int typeSize = buf.readVarInt();
        this.nodeTypes = new ArrayList<>(typeSize);
        for (int i = 0; i < typeSize; i++) {
            this.nodeTypes.add(buf.readUtf());
        }
        int costSize = buf.readVarInt();
        this.nodeCosts = new ArrayList<>(costSize);
        for (int i = 0; i < costSize; i++) {
            this.nodeCosts.add(buf.readDouble());
        }
        int reasonSize = buf.readVarInt();
        this.nodeCostReasons = new ArrayList<>(reasonSize);
        for (int i = 0; i < reasonSize; i++) {
            this.nodeCostReasons.add(buf.readUtf());
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(npcUuid);
        buf.writeInt(currentIndex);
        buf.writeBoolean(clear);
        buf.writeBoolean(blocked);
        buf.writeVarInt(nodes.size());
        for (Vec3 pos : nodes) {
            buf.writeDouble(pos.x);
            buf.writeDouble(pos.y);
            buf.writeDouble(pos.z);
        }
        buf.writeVarInt(nodeTypes.size());
        for (String nodeType : nodeTypes) {
            buf.writeUtf(nodeType);
        }
        buf.writeVarInt(nodeCosts.size());
        for (Double nodeCost : nodeCosts) {
            buf.writeDouble(nodeCost == null ? 0.0D : nodeCost);
        }
        buf.writeVarInt(nodeCostReasons.size());
        for (String nodeCostReason : nodeCostReasons) {
            buf.writeUtf(nodeCostReason == null ? "normal" : nodeCostReason);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (clear) {
                NPCPathDebugClientCache.removePath(npcUuid);
            } else {
                NPCPathDebugClientCache.updatePath(npcUuid, currentIndex, nodes, nodeTypes, nodeCosts, nodeCostReasons, blocked);
            }
            //Simukraft.LOGGER.info("[SyncNPCPathDebugPacket] 客户端收到NPC路径调试数据: npc={}, clear={}, blocked={}, nodes={}", npcUuid, clear, blocked, nodes.size());
        });
        ctx.get().setPacketHandled(true);
    }
}
