package com.xiaoliang.simukraft.world;

import com.xiaoliang.simukraft.employment.bridge.EmploymentLegacyBridge;
import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.utils.NPCEntityLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;

/**
 * 建筑盒雇佣数据存储 - 使用V2统一雇佣存储系统
 */
public class BuildBoxHiredData {
    private static final BuildBoxHiredData INSTANCE = new BuildBoxHiredData();

    private BuildBoxHiredData() {}

    public static BuildBoxHiredData getInstance() {
        return INSTANCE;
    }

    // 静态方法，用于兼容现有代码，使用V2统一存储
    public static void saveHiredBuilders(MinecraftServer server, Map<BlockPos, UUID> hiredBuilders) {
        EmploymentLegacyBridge.saveAssignments(server, WorkBlockType.BUILD_BOX, JobType.BUILDER, hiredBuilders);
    }

    public static Map<BlockPos, UUID> loadHiredBuilders(MinecraftServer server) {
        return EmploymentLegacyBridge.loadAssignments(server, WorkBlockType.BUILD_BOX, JobType.BUILDER);
    }

    // 静态方法，用于兼容现有代码
    public static void saveHiredEmployees(MinecraftServer server, Map<BlockPos, UUID> hiredEmployees) {
        saveHiredBuilders(server, hiredEmployees);
    }

    public static Map<BlockPos, UUID> loadHiredEmployees(MinecraftServer server) {
        return loadHiredBuilders(server);
    }

    // 规划师数据存储方法
    public static void saveHiredPlanners(MinecraftServer server, Map<BlockPos, UUID> hiredPlanners) {
        EmploymentLegacyBridge.saveAssignments(server, WorkBlockType.BUILD_BOX, JobType.PLANNER, hiredPlanners);
    }

    public static Map<BlockPos, UUID> loadHiredPlanners(MinecraftServer server) {
        return EmploymentLegacyBridge.loadAssignments(server, WorkBlockType.BUILD_BOX, JobType.PLANNER);
    }

    /**
     * 根据UUID查找NPC实体
     * 在所有维度中搜索指定UUID的NPC
     */
    public static CustomEntity findNPCByUuid(MinecraftServer server, UUID uuid) {
        return NPCEntityLocator.findNpc(server, uuid, true);
    }
}
