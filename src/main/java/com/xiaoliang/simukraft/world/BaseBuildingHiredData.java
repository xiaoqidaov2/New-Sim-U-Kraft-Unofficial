package com.xiaoliang.simukraft.world;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.utils.NPCEntityLocator;
import net.minecraft.server.MinecraftServer;
import java.util.UUID;

/**
 * 建筑雇佣数据基类 - 仅保留静态辅助方法
 * 雇佣数据存储已迁移至V2统一存储系统 (EmploymentLegacyBridge)
 */
public final class BaseBuildingHiredData {

    private BaseBuildingHiredData() {}

    /**
     * 静态辅助方法，用于从服务器中查找NPC
     */
    public static CustomEntity findNPCByUuid(MinecraftServer server, UUID npcUuid) {
        return NPCEntityLocator.findNpc(server, npcUuid, true);
    }
}
