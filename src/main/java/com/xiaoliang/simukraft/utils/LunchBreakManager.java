package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.building.IndustrialBuildingConfig;
import com.xiaoliang.simukraft.building.IndustrialBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC午休管理器（menglannnn: 完全仿造休息的写法）
 * 午休时间：6000-8000 tick（中午12:00-16:00，游戏时间约12分钟）
 * 午休期间NPC可以闲逛或去购买食物
 *
 * 午休规则：
 * - 农民：固定午休
 * - 仓库管理员：固定午休
 * - 建筑师：固定午休
 * - 规划师：固定午休
 * - 工商业：根据配置文件决定是否午休（默认true）
 *
 * 状态持久化：使用NPC的NBT数据（workSubState字段）
 */
public class LunchBreakManager {

    // 午休时间范围
    public static final int LUNCH_BREAK_START = 6000;  // 中午12:00
    public static final int LUNCH_BREAK_END = 8000;    // 下午16:00

    // 固定午休的工作类型（不受配置文件影响）
    private static final Set<String> FIXED_LUNCH_BREAK_JOBS = Set.of(
        "farmer",            // 农民
        "warehouse_manager", // 仓库管理员
        "builder",           // 建筑师
        "planner"            // 规划师
    );

    // 可配置午休的工作类型（根据配置文件）
    private static final Set<String> CONFIGURABLE_LUNCH_BREAK_JOBS = Set.of(
        "industrial"         // 工业工人
    );

    // 记录NPC午休前的工作位置（用于午休结束后返回）
    // menglannnn: 这个Map只用于运行时，不需要持久化，因为NPC的workSubState已经持久化了
    private static final Map<UUID, BlockPos> lunchBreakWorkPositions = new ConcurrentHashMap<>();

    /**
     * 检查当前是否在午休时间
     */
    public static boolean isLunchBreakTime(long dayTime) {
        long timeOfDay = dayTime % 24000L;
        return timeOfDay >= LUNCH_BREAK_START && timeOfDay < LUNCH_BREAK_END;
    }

    /**
     * 检查NPC是否应该午休
     * menglannnn: 农民和仓库管理员固定午休，工商业根据配置决定
     */
    public static boolean shouldHaveLunchBreak(CustomEntity npc) {
        if (npc == null) return false;

        String job = npc.getJob();
        if (job == null || job.isEmpty()) return false;

        // 固定午休的职业
        if (FIXED_LUNCH_BREAK_JOBS.contains(job)) {
            return true;
        }

        // 可配置午休的职业
        if (CONFIGURABLE_LUNCH_BREAK_JOBS.contains(job)) {
            return shouldIndustrialHaveLunchBreak(npc);
        }

        // 商业职业（如肉铺老板、水果店老板等）
        if (isCommercialJob(job)) {
            return shouldCommercialHaveLunchBreak(npc);
        }

        return false;
    }

    /**
     * 检查工业工人是否应该午休（根据配置文件）
     */
    private static boolean shouldIndustrialHaveLunchBreak(CustomEntity npc) {
        BlockPos workPos = findIndustrialWorkPosition(npc.getUUID(), npc.level().getServer());
        if (workPos == null) return true; // 默认午休

        IndustrialBuildingConfig config = getIndustrialConfig(npc.level().getServer(), workPos);
        if (config == null) return true; // 默认午休

        String selectedRecipeId = com.xiaoliang.simukraft.building.ControlBoxDataManager.getSelectedRecipe(
            npc.level().getServer(), workPos);

        return config.isHasLunchBreakForRecipe(selectedRecipeId);
    }

    /**
     * 检查商业职业是否应该午休（根据配置文件）
     */
    private static boolean shouldCommercialHaveLunchBreak(CustomEntity npc) {
        BlockPos workPos = findCommercialWorkPosition(npc.getUUID(), npc.level().getServer());
        if (workPos == null) return true; // 默认午休

        CommercialBuildingConfig config = getCommercialConfig(npc.level().getServer(), workPos);
        if (config == null) return true; // 默认午休

        return config.isHasLunchBreak();
    }

    /**
     * 检查是否是商业职业
     */
    private static boolean isCommercialJob(String job) {
        // 商业职业通常是商店类型
        return job != null && (
            job.contains("shop") ||
            job.contains("store") ||
            job.contains("bakery") ||
            job.contains("butcher") ||
            job.equals("meat_shop") ||
            job.equals("fruit_shop") ||
            job.equals("bakery")
        );
    }

    /**
     * 检查NPC是否应该开始午休
     * menglannnn: 使用NPC的workSubState状态来判断，支持持久化
     */
    public static boolean shouldStartLunchBreak(CustomEntity npc, long dayTime) {
        if (npc == null) return false;
        if (!isLunchBreakTime(dayTime)) return false;

        // 如果已经在午休状态，不再重复开始（这个检查应该在最前面）
        if (npc.getWorkSubState() == WorkSubState.LUNCH_BREAK) {
            return false;
        }

        if (npc.getWorkStatus() != WorkStatus.WORKING) return false;

        return shouldHaveLunchBreak(npc);
    }

    /**
     * 检查NPC是否应该结束午休
     * menglannnn: 使用NPC的workSubState状态来判断，支持持久化
     */
    public static boolean shouldEndLunchBreak(CustomEntity npc, long dayTime) {
        if (npc == null) return false;
        if (isLunchBreakTime(dayTime)) return false; // 还在午休时间内
        if (npc.getWorkSubState() != WorkSubState.LUNCH_BREAK) return false;

        return true;
    }

    /**
     * 开始午休（完全仿造休息的写法）
     * menglannnn: 记录工作位置，设置午休状态，停止所有活动
     */
    public static void startLunchBreak(CustomEntity npc, BlockPos workPos) {
        if (npc == null) return;

        UUID npcId = npc.getUUID();

        // 记录工作位置
        lunchBreakWorkPositions.put(npcId, workPos.immutable());

        // 设置午休状态（这个状态会被保存到NBT）
        npc.setWorkSubState(WorkSubState.LUNCH_BREAK);
        npc.setStatusLabel("gui.npc.status.lunch_break");

        // 停止NPC的所有活动（仿造休息的写法）
        stopAllNPCActivities(npc);

        Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 开始午休，工作位置: {}",
            npc.getFullName(), workPos);
    }

    /**
     * 停止NPC的所有活动，但允许移动（仿造休息的写法）
     */
    private static void stopAllNPCActivities(CustomEntity npc) {
        if (npc == null) return;

        // 设置isWorking为false，允许NPC移动
        npc.setWorking(false);

        // 恢复AI
        npc.setNoAi(false);

        // 清除目标
        npc.setTarget(null);
        npc.setLastHurtByMob(null);

        // 停止建造任务
        if (npc.getConstructionTask() != null) {
            npc.setConstructionTask(null);
        }

        // 清除休息界限，让NPC午休期间可以自由活动
        com.xiaoliang.simukraft.entity.ai.RestrictedAreaGoal areaGoal = npc.getRestrictedAreaGoal();
        if (areaGoal != null) {
            areaGoal.clearRestrictedArea();
        }

        // simukraft: 清除寻路层面的边界限制
        if (npc.getNavigation() instanceof com.xiaoliang.simukraft.entity.ai.RestrictedGroundPathNavigation nav) {
            nav.disableRestriction();
        }

        // simukraft: 清除随机漫步的边界限制
        com.xiaoliang.simukraft.entity.ai.RestrictedRandomStrollGoal strollGoal = npc.getRestrictedRandomStrollGoal();
        if (strollGoal != null) {
            strollGoal.disableRestriction();
        }

        Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 的所有活动已停止，已设置为可移动状态", npc.getFullName());
    }

    /**
     * 结束午休，返回工作（完全仿造休息的写法）
     * menglannnn: 午休结束后恢复工作状态，传送回工作位置
     */
    public static void endLunchBreak(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null) return;

        UUID npcId = npc.getUUID();

        // 获取工作位置
        BlockPos workPos = lunchBreakWorkPositions.get(npcId);

        if (workPos != null) {
            // 检查距离
            double distance = npc.position().distanceTo(new net.minecraft.world.phys.Vec3(
                workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5));

            if (distance > 3.0) {
                // 距离太远，直接传送
                Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 距离工作位置{}格，传送回工作位置",
                    npc.getFullName(), distance);
                spawnTeleportParticles(npc);
                npc.teleportTo(workPos.getX() + 0.5, workPos.getY() + 1, workPos.getZ() + 0.5);
                spawnTeleportParticles(npc);
            }
        }

        // 恢复工作状态（仿造休息的写法）
        restoreWorkStatus(npc, npcId, workPos, level);

        // 清理运行时记录
        lunchBreakWorkPositions.remove(npcId);

        Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 结束午休，返回工作位置并恢复工作",
            npc.getFullName());
    }

    /**
     * 恢复NPC的工作状态（仿造休息的写法）
     */
    private static void restoreWorkStatus(CustomEntity npc, UUID npcId, BlockPos workPos, ServerLevel level) {
        // 恢复NPC的工作状态
        npc.setWorkStatus(WorkStatus.WORKING);
        npc.setWorkSubState(WorkSubState.WORKING);
        npc.setWorking(true);

        // 清除状态标签
        npc.setStatusLabel(null);

        // 清除休息界限，防止NPC被限制在住宅范围内无法前往工作岗位
        com.xiaoliang.simukraft.entity.ai.RestrictedAreaGoal areaGoal = npc.getRestrictedAreaGoal();
        if (areaGoal != null) {
            areaGoal.clearRestrictedArea();
        }

        // simukraft: 清除寻路层面的边界限制
        if (npc.getNavigation() instanceof com.xiaoliang.simukraft.entity.ai.RestrictedGroundPathNavigation nav) {
            nav.disableRestriction();
        }

        // simukraft: 清除随机漫步的边界限制
        com.xiaoliang.simukraft.entity.ai.RestrictedRandomStrollGoal strollGoal = npc.getRestrictedRandomStrollGoal();
        if (strollGoal != null) {
            strollGoal.disableRestriction();
        }

        // 恢复建筑师的建造任务
        String job = npc.getJob();
        if ("builder".equals(job) && level != null && level.getServer() != null) {
            npc.setConstructionTask(null);
            BlockPos buildBoxPos = findBuilderWorkPosition(npcId, level.getServer());
            if (buildBoxPos != null) {
                restoreBuilderTaskFromJson(level.getServer(), npc, buildBoxPos);
            }
        }
    }

    /**
     * 生成传送粒子效果（仿造休息的写法）
     */
    private static void spawnTeleportParticles(CustomEntity npc) {
        if (npc.level() instanceof ServerLevel serverLevel) {
            for (int i = 0; i < 20; i++) {
                serverLevel.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.PORTAL,
                    npc.getX(), npc.getY() + 1.0, npc.getZ(),
                    1,
                    0.5, 0.5, 0.5,
                    0.1
                );
            }
        }
    }

    /**
     * 从JSON恢复建筑师的建造任务（完全仿造休息的写法）
     */
    private static void restoreBuilderTaskFromJson(MinecraftServer server, CustomEntity npc, BlockPos buildBoxPos) {
        if (server == null || npc == null || buildBoxPos == null) return;

        // 如果NPC已经有建造任务，不需要恢复
        if (npc.getConstructionTask() != null) {
            return;
        }

        // 从持久化存储中加载建造任务
        com.xiaoliang.simukraft.world.ConstructionTaskData.TaskInfo taskInfo =
            com.xiaoliang.simukraft.world.ConstructionTaskData.loadTask(server, npc.getUUID());
        if (taskInfo == null) {
            Simukraft.LOGGER.debug("[LunchBreakManager] NPC {} 没有保存的建造任务", npc.getFullName());
            return;
        }

        try {
            // 验证taskInfo的必需字段
            if (taskInfo.buildingName == null || taskInfo.category == null ||
                taskInfo.startPos == null || taskInfo.buildBoxPos == null ||
                taskInfo.facing == null || taskInfo.displayName == null) {
                Simukraft.LOGGER.warn("[LunchBreakManager] 建造任务数据不完整，无法恢复 - NPC: {}",
                    npc.getFullName());
                return;
            }

            // 验证建筑盒是否还存在
            ServerLevel level = server.overworld();
            net.minecraft.world.level.block.state.BlockState buildBoxState = level.getBlockState(taskInfo.buildBoxPos);
            if (buildBoxState.isAir()) {
                Simukraft.LOGGER.warn("[LunchBreakManager] 建筑盒已不存在，移除建造任务 - NPC: {}",
                    npc.getFullName());
                com.xiaoliang.simukraft.world.ConstructionTaskData.removeTask(server, npc.getUUID());
                return;
            }

            // 重新创建建造任务
            com.xiaoliang.simukraft.building.ConstructionTask task = new com.xiaoliang.simukraft.building.ConstructionTask(
                taskInfo.buildingName,
                taskInfo.category,
                taskInfo.startPos,
                taskInfo.buildBoxPos,
                taskInfo.facing,
                taskInfo.displayName,
                taskInfo.cost,
                level
            );

            // 恢复建造进度
            task.setCurrentBlockIndex(taskInfo.currentBlockIndex);

            // 设置NPC的建造任务
            npc.setConstructionTask(task);

            Simukraft.LOGGER.debug("[LunchBreakManager] 成功恢复建造任务 - NPC: {}, 建筑: {}, 进度: {}/{}",
                npc.getFullName(),
                taskInfo.displayName,
                taskInfo.currentBlockIndex,
                task.getTotalBlocks());
        } catch (Exception e) {
            Simukraft.LOGGER.error("[LunchBreakManager] 恢复建造任务时出错 - NPC: {}", npc.getFullName(), e);
        }
    }

    /**
     * 恢复午休状态
     * menglannnn: NPC加载时调用，恢复工作位置等运行时数据
     */
    public static void restoreLunchBreakState(CustomEntity npc) {
        if (npc == null) return;

        // 如果NPC处于午休状态，但工作位置记录丢失了，尝试重新查找
        if (npc.getWorkSubState() == WorkSubState.LUNCH_BREAK) {
            UUID npcId = npc.getUUID();
            if (!lunchBreakWorkPositions.containsKey(npcId)) {
                BlockPos workPos = findWorkPosition(npc);
                if (workPos != null) {
                    lunchBreakWorkPositions.put(npcId, workPos);
                    Simukraft.LOGGER.debug("[LunchBreakManager] 恢复NPC {} 的午休工作位置: {}",
                        npc.getFullName(), workPos);
                }
            }
        }
    }

    /**
     * 获取NPC的工作位置
     */
    @Nullable
    public static BlockPos getWorkPosition(UUID npcId) {
        return lunchBreakWorkPositions.get(npcId);
    }

    /**
     * 处理所有NPC的午休逻辑
     * menglannnn: 由WorldEvents.tick事件调用
     */
    public static void handleLunchBreak(ServerLevel level) {
        if (level == null) return;

        long dayTime = level.getDayTime();

        // 遍历所有在线NPC
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof CustomEntity npc)) continue;

            // 恢复午休状态（用于NPC重新加载后的情况）
            restoreLunchBreakState(npc);

            // 检查是否应该开始午休
            if (shouldStartLunchBreak(npc, dayTime)) {
                BlockPos workPos = findWorkPosition(npc);
                if (workPos != null) {
                    startLunchBreak(npc, workPos);
                }
            }

            // 检查是否应该结束午休
            if (shouldEndLunchBreak(npc, dayTime)) {
                endLunchBreak(npc, level);
            }
        }
    }

    /**
     * 查找NPC的工作位置
     * menglannnn: 根据职业类型从不同数据源获取
     */
    @Nullable
    private static BlockPos findWorkPosition(CustomEntity npc) {
        if (npc == null) return null;

        String job = npc.getJob();
        UUID npcId = npc.getUUID();
        MinecraftServer server = npc.level().getServer();
        if (server == null) return null;

        return switch (job) {
            case "builder" -> findBuilderWorkPosition(npcId, server);
            case "planner" -> findPlannerWorkPosition(npcId, server);
            case "farmer" -> findFarmerWorkPosition(npcId);
            case "warehouse_manager" -> findWarehouseWorkPosition(npcId, server);
            case "industrial" -> findIndustrialWorkPosition(npcId, server);
            default -> findCommercialWorkPosition(npcId, server);
        };
    }

    /**
     * 查找建筑师的工作位置
     */
    @Nullable
    private static BlockPos findBuilderWorkPosition(UUID npcId, MinecraftServer server) {
        var hires = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server);
        for (var entry : hires.entrySet()) {
            if (entry.getValue().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 查找规划师的工作位置
     */
    @Nullable
    private static BlockPos findPlannerWorkPosition(UUID npcId, MinecraftServer server) {
        var hires = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(server);
        for (var entry : hires.entrySet()) {
            if (entry.getValue().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 查找农民的工作位置
     */
    @Nullable
    private static BlockPos findFarmerWorkPosition(UUID npcId) {
        Map<BlockPos, UUID> hires = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
        for (var entry : hires.entrySet()) {
            if (entry.getValue().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 查找仓库管理员的工作位置
     */
    @Nullable
    private static BlockPos findWarehouseWorkPosition(UUID npcId, MinecraftServer server) {
        var hires = com.xiaoliang.simukraft.world.LogisticsHiredData.getServerBoxHiredNpcs(server);
        for (var entry : hires.entrySet()) {
            if (entry.getValue().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 查找工业工人的工作位置
     */
    @Nullable
    static BlockPos findIndustrialWorkPosition(UUID npcId, MinecraftServer server) {
        var hires = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : hires.entrySet()) {
            if (entry.getValue().getNpcUuid().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 查找商业职业的工作位置
     */
    @Nullable
    static BlockPos findCommercialWorkPosition(UUID npcId, MinecraftServer server) {
        var hires = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        for (var entry : hires.entrySet()) {
            if (entry.getValue().getNpcUuid().equals(npcId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取工业建筑配置
     */
    @Nullable
    private static IndustrialBuildingConfig getIndustrialConfig(MinecraftServer server, BlockPos pos) {
        var hires = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        var hireInfo = hires.get(pos);
        if (hireInfo == null) return null;

        return IndustrialBuildingManager.getConfig(hireInfo.getBuildingFileName());
    }

    /**
     * 获取商业建筑配置
     */
    @Nullable
    private static CommercialBuildingConfig getCommercialConfig(MinecraftServer server, BlockPos pos) {
        var hires = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        var hireInfo = hires.get(pos);
        if (hireInfo == null) return null;

        return CommercialBuildingManager.getConfig(hireInfo.getBuildingFileName());
    }
}
