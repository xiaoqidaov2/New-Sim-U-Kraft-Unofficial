package com.xiaoliang.simukraft.client.gui;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * NPC路径调试客户端缓存
 */
public class NPCPathDebugClientCache {
    private static final Map<UUID, DebugPathData> PATHS = new ConcurrentHashMap<>();

    public static void updatePath(UUID npcUuid, int currentIndex, List<Vec3> nodes, List<String> nodeTypes, boolean blocked) {
        List<String> normalizedNodeTypes = new ArrayList<>(nodeTypes);
        while (normalizedNodeTypes.size() < nodes.size()) {
            normalizedNodeTypes.add("WALKABLE");
        }
        if (normalizedNodeTypes.size() > nodes.size()) {
            normalizedNodeTypes = normalizedNodeTypes.stream().limit(nodes.size()).collect(Collectors.toList());
        }
        PATHS.put(npcUuid, new DebugPathData(currentIndex, new ArrayList<>(nodes), normalizedNodeTypes, blocked));
    }

    public static void removePath(UUID npcUuid) {
        PATHS.remove(npcUuid);
    }

    public static Map<UUID, DebugPathData> getPaths() {
        return PATHS;
    }

    public static void clear() {
        PATHS.clear();
    }

    public record DebugPathData(int currentIndex, List<Vec3> nodes, List<String> nodeTypes, boolean blocked) {
    }
}
