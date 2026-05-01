package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import com.xiaoliang.simukraft.world.FarmlandHiredData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FarmlandData {
    private static final Map<BlockPos, CustomEntity> hiredFarmers = new HashMap<>();
    private static final Map<BlockPos, String> selectedCrops = new HashMap<>();
    private static final Map<BlockPos, Integer> selectedAreas = new HashMap<>();
    private static final Map<BlockPos, FarmlandPlot> selectedPlots = new HashMap<>();
    
    // 添加UUID相关的数据结构
    private static final Map<BlockPos, UUID> hiredFarmerUuids = new HashMap<>();
    private static final Map<UUID, String> npcNames = new HashMap<>();
    private static boolean dataLoaded = false;

    // 初始化方法，在服务器启动时调用（仅单人游戏使用）
    public static void init(MinecraftServer server) {
        // 如果数据已加载或服务器为null，则跳过初始化
        if (dataLoaded || server == null) {
            return;
        }

        // 强制重新加载数据，确保数据是最新的
        dataLoaded = false;

        // 清空现有数据
        hiredFarmers.clear();
        selectedCrops.clear();
        selectedAreas.clear();
        selectedPlots.clear();
        hiredFarmerUuids.clear();
        npcNames.clear();

        // 从持久化数据加载农田盒数据
        FarmlandHiredData.loadAllFarmlandData(server);

        // 将持久化数据同步到客户端数据结构
        Map<BlockPos, UUID> savedHiredFarmers = FarmlandHiredData.getHiredFarmers();
        Map<BlockPos, String> savedSelectedCrops = FarmlandHiredData.getSelectedCrops();
        Map<BlockPos, Integer> savedSelectedAreas = FarmlandHiredData.getSelectedAreas();
        Map<BlockPos, FarmlandPlot> savedSelectedPlots = FarmlandHiredData.getSelectedPlots();

        hiredFarmerUuids.putAll(savedHiredFarmers);
        selectedCrops.putAll(savedSelectedCrops);
        selectedAreas.putAll(savedSelectedAreas);
        selectedPlots.putAll(savedSelectedPlots);

        // 尝试加载NPC实体
        for (Map.Entry<BlockPos, UUID> entry : savedHiredFarmers.entrySet()) {
            BlockPos pos = entry.getKey();
            UUID npcUuid = entry.getValue();

            // 尝试找到NPC实体
            CustomEntity npc = FarmlandHiredData.findNPCByUuid(server, npcUuid);
            if (npc != null) {
                hiredFarmers.put(pos, npc);
                npcNames.put(npcUuid, npc.getFullName());
            } else {
                // 如果实体不存在，从npcdata获取名称
                String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                if (npcName != null) {
                    npcNames.put(npcUuid, npcName);
                }
            }
        }

        dataLoaded = true;
    }
    
    /**
     * 强制重新加载数据，用于客户端重新连接时
     */
    public static void reloadData(MinecraftServer server) {
        if (server == null) return;
        
        dataLoaded = false;
        init(server);
    }

    // 保存所有数据到文件
    public static void saveAllData(MinecraftServer server) {
        // 确保数据已加载
        if (!dataLoaded) {
            init(server);
        }
        
        // 保存数据到文件
        FarmlandHiredData.saveAllFarmlandData(server);
    }

    public static boolean hasHiredFarmer(BlockPos farmlandBoxPos) {
        return hiredFarmers.containsKey(farmlandBoxPos) || hiredFarmerUuids.containsKey(farmlandBoxPos);
    }

    public static CustomEntity getHiredFarmer(BlockPos farmlandBoxPos) {
        return hiredFarmers.get(farmlandBoxPos);
    }

    /**
     * 获取雇佣农民的UUID
     */
    public static UUID getHiredFarmerUUID(BlockPos farmlandBoxPos) {
        return hiredFarmerUuids.get(farmlandBoxPos);
    }

    /**
     * 获取所有雇佣农民的UUID
     */
    public static Map<BlockPos, UUID> getAllHiredFarmerUuids() {
        return new HashMap<>(hiredFarmerUuids);
    }

    /**
     * 通过UUID获取NPC名称
     */
    public static String getNPCNameByUUID(UUID npcUuid) {
        return npcNames.get(npcUuid);
    }

    public static void setHiredFarmer(BlockPos farmlandBoxPos, CustomEntity npc) {
        hiredFarmers.put(farmlandBoxPos, npc);
        if (npc != null) {
            UUID npcUuid = npc.getUUID();
            hiredFarmerUuids.put(farmlandBoxPos, npcUuid);
            npcNames.put(npcUuid, npc.getFullName());
            
            // 保存到持久化数据
            FarmlandHiredData.setHiredFarmer(farmlandBoxPos, npcUuid);
            
            // 立即保存到文件，确保数据不丢失
            if (npc.level() != null && npc.level().getServer() != null) {
                FarmlandHiredData.saveAllFarmlandData(npc.level().getServer());
            }
        }
    }
    
    /**
     * 通过UUID设置雇佣农民
     */
    public static void setHiredEmployee(BlockPos farmlandBoxPos, UUID npcUuid) {
        // 尝试找到对应的NPC实体
        var minecraft = Minecraft.getInstance();
        CustomEntity npc = null;
        
        if (minecraft.level != null) {
            for (var entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof CustomEntity && entity.getUUID().equals(npcUuid)) {
                    npc = (CustomEntity) entity;
                    npcNames.put(npcUuid, npc.getFullName());
                    break;
                }
            }
        }
        
        // 保存UUID和实体引用
        hiredFarmerUuids.put(farmlandBoxPos, npcUuid);
        hiredFarmers.put(farmlandBoxPos, npc);
        
        // 保存到持久化数据
        FarmlandHiredData.setHiredFarmer(farmlandBoxPos, npcUuid);
        
        // 直接从npcdata获取NPC名称（如果实体不存在）
        if (npc == null) {
            var server = minecraft.getSingleplayerServer();
            if (server != null) {
                String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, npcUuid);
                if (npcName != null) {
                    npcNames.put(npcUuid, npcName);
                }
            }
        }
    }
    
    /**
     * 通过UUID设置雇佣农民（实体版本）
     */
    public static void setHiredEmployee(BlockPos farmlandBoxPos, CustomEntity npc) {
        setHiredFarmer(farmlandBoxPos, npc);
    }

    public static void clearHiredFarmer(BlockPos farmlandBoxPos) {
        // 清除NPC名称映射
        UUID npcUuid = hiredFarmerUuids.remove(farmlandBoxPos);
        if (npcUuid != null) {
            npcNames.remove(npcUuid);
        }
        hiredFarmers.remove(farmlandBoxPos);
        
        // 清除持久化数据
        FarmlandHiredData.clearHiredFarmer(farmlandBoxPos);
    }
    
    /**
     * 通过UUID解雇农民
     */
    public static void fireEmployeeByUUID(UUID npcUuid) {
        // 创建一个集合来存储需要清除的农田盒位置
        java.util.Set<BlockPos> farmlandPositionsToClear = new java.util.HashSet<>();
        
        // 找出所有关联的农田盒位置
        for (var entry : hiredFarmerUuids.entrySet()) {
            if (entry.getValue().equals(npcUuid)) {
                farmlandPositionsToClear.add(entry.getKey());
            }
        }
        
        // 清除所有关联的农田盒数据
        for (BlockPos farmlandPos : farmlandPositionsToClear) {
            clearHiredFarmer(farmlandPos);
        }
        
        // 清除NPC名称映射
        npcNames.remove(npcUuid);
    }

    public static boolean hasSelectedCrop(BlockPos farmlandBoxPos) {
        return selectedCrops.containsKey(farmlandBoxPos);
    }

    public static String getSelectedCrop(BlockPos farmlandBoxPos) {
        return selectedCrops.get(farmlandBoxPos);
    }

    public static void setSelectedCrop(BlockPos farmlandBoxPos, String crop) {
        selectedCrops.put(farmlandBoxPos, crop);

        // 立即保存到文件，确保数据不丢失
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            // 单人游戏：先将客户端的所有数据同步到服务器端，然后再保存
            syncClientDataToServer(Minecraft.getInstance().getSingleplayerServer());
        } else {
            // 多人游戏：发送数据包到服务器
            int areaSize = selectedAreas.getOrDefault(farmlandBoxPos, 0);
            com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                    new com.xiaoliang.simukraft.network.SetFarmlandConfigPacket(farmlandBoxPos, crop, areaSize));
        }
    }

    public static void clearSelectedCrop(BlockPos farmlandBoxPos) {
        selectedCrops.remove(farmlandBoxPos);
        // 清除持久化数据
        FarmlandHiredData.clearSelectedCrop(farmlandBoxPos);
    }

    public static boolean hasSelectedArea(BlockPos farmlandBoxPos) {
        return selectedAreas.containsKey(farmlandBoxPos);
    }

    public static int getSelectedAreaSize(BlockPos farmlandBoxPos) {
        return selectedAreas.getOrDefault(farmlandBoxPos, 0);
    }

    public static void setSelectedArea(BlockPos farmlandBoxPos, int areaSize) {
        selectedAreas.put(farmlandBoxPos, areaSize);

        // 立即保存到文件，确保数据不丢失
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            // 单人游戏：先将客户端的所有数据同步到服务器端，然后再保存
            syncClientDataToServer(Minecraft.getInstance().getSingleplayerServer());
        } else {
            // 多人游戏：发送数据包到服务器
            String crop = selectedCrops.get(farmlandBoxPos);
            com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                    new com.xiaoliang.simukraft.network.SetFarmlandConfigPacket(farmlandBoxPos, crop, areaSize));
        }
    }

    public static void clearSelectedArea(BlockPos farmlandBoxPos) {
        selectedAreas.remove(farmlandBoxPos);
        // 清除持久化数据
        FarmlandHiredData.clearSelectedArea(farmlandBoxPos);
    }

    public static boolean hasSelectedPlot(BlockPos farmlandBoxPos) {
        return selectedPlots.containsKey(farmlandBoxPos);
    }

    public static FarmlandPlot getSelectedPlot(BlockPos farmlandBoxPos) {
        return selectedPlots.get(farmlandBoxPos);
    }

    public static BlockPos findOverlappingPlotOwner(BlockPos farmlandBoxPos, FarmlandPlot plot) {
        if (farmlandBoxPos == null || plot == null) {
            return null;
        }
        for (Map.Entry<BlockPos, FarmlandPlot> entry : selectedPlots.entrySet()) {
            BlockPos otherBoxPos = entry.getKey();
            if (farmlandBoxPos.equals(otherBoxPos)) {
                continue;
            }
            FarmlandPlot otherPlot = entry.getValue();
            if (otherPlot != null && plot.intersects(otherPlot)) {
                return otherBoxPos;
            }
        }
        return null;
    }

    public static void setSelectedPlot(BlockPos farmlandBoxPos, FarmlandPlot plot) {
        if (plot == null) {
            return;
        }
        if (findOverlappingPlotOwner(farmlandBoxPos, plot) != null) {
            return;
        }
        selectedPlots.put(farmlandBoxPos, plot);
        selectedAreas.put(farmlandBoxPos, Math.max(plot.widthX(), plot.depthZ()));

        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            syncClientDataToServer(Minecraft.getInstance().getSingleplayerServer());
        } else {
            String crop = selectedCrops.get(farmlandBoxPos);
            int areaSize = selectedAreas.getOrDefault(farmlandBoxPos, 0);
            com.xiaoliang.simukraft.network.NetworkManager.INSTANCE.sendToServer(
                    new com.xiaoliang.simukraft.network.SetFarmlandConfigPacket(farmlandBoxPos, crop, areaSize, plot));
        }
    }

    public static void setSelectedPlotFromServer(BlockPos farmlandBoxPos, FarmlandPlot plot) {
        if (plot != null) {
            selectedPlots.put(farmlandBoxPos, plot);
            selectedAreas.put(farmlandBoxPos, Math.max(plot.widthX(), plot.depthZ()));
        } else {
            selectedPlots.remove(farmlandBoxPos);
        }
    }

    public static void clearSelectedPlot(BlockPos farmlandBoxPos) {
        selectedPlots.remove(farmlandBoxPos);
        FarmlandHiredData.clearSelectedPlot(farmlandBoxPos);
    }

    // ==================== 服务器数据同步方法 ====================

    /**
     * 从服务器同步雇佣农民数据
     * 用于多人游戏中客户端接收服务器数据
     */
    public static void setHiredFarmerFromServer(BlockPos farmlandBoxPos, UUID npcUuid, String npcName) {
        if (npcUuid != null) {
            hiredFarmerUuids.put(farmlandBoxPos, npcUuid);
            if (npcName != null) {
                npcNames.put(npcUuid, npcName);
            }
        } else {
            hiredFarmerUuids.remove(farmlandBoxPos);
            hiredFarmers.remove(farmlandBoxPos);
        }
    }

    /**
     * 从服务器同步选中作物数据
     * 用于多人游戏中客户端接收服务器数据
     */
    public static void setSelectedCropFromServer(BlockPos farmlandBoxPos, String crop) {
        if (crop != null) {
            selectedCrops.put(farmlandBoxPos, crop);
        } else {
            selectedCrops.remove(farmlandBoxPos);
        }
    }

    /**
     * 从服务器同步选中区域数据
     * 用于多人游戏中客户端接收服务器数据
     */
    public static void setSelectedAreaFromServer(BlockPos farmlandBoxPos, int areaSize) {
        if (areaSize > 0) {
            selectedAreas.put(farmlandBoxPos, areaSize);
        } else {
            selectedAreas.remove(farmlandBoxPos);
        }
    }

    /**
     * 将客户端的所有数据同步到服务器端
     * 用于单人游戏中确保数据一致性
     */
    private static void syncClientDataToServer(MinecraftServer server) {
        // 同步雇佣农民数据
        for (Map.Entry<BlockPos, UUID> entry : hiredFarmerUuids.entrySet()) {
            FarmlandHiredData.setHiredFarmer(entry.getKey(), entry.getValue());
        }
        // 同步作物数据
        for (Map.Entry<BlockPos, String> entry : selectedCrops.entrySet()) {
            FarmlandHiredData.setSelectedCrop(entry.getKey(), entry.getValue());
        }
        // 同步区域数据
        for (Map.Entry<BlockPos, Integer> entry : selectedAreas.entrySet()) {
            FarmlandHiredData.setSelectedArea(entry.getKey(), entry.getValue());
        }
        // 同步真实地块数据
        for (Map.Entry<BlockPos, FarmlandPlot> entry : selectedPlots.entrySet()) {
            if (!FarmlandHiredData.hasOverlappingPlot(entry.getKey(), entry.getValue())) {
                FarmlandHiredData.setSelectedPlot(entry.getKey(), entry.getValue());
            }
        }
        // 保存到文件
        FarmlandHiredData.saveAllFarmlandData(server);
    }
}
