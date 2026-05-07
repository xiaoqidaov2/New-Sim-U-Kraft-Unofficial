package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.utils.NPCTaskScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID)
@SuppressWarnings({"null", "deprecation"})
public class NPCTeleportEventHandler {
    private static final int TELEPORT_SCAN_INTERVAL_TICKS = 10;
    
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }

        // 工业/农业到岗传送本身已经有事件链路处理，这里只做低频兜底，避免每tick扫描造成掉刻。
        if (server.getTickCount() % TELEPORT_SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        // 复用NPC缓存，避免每tick遍历所有实体。
        for (ServerLevel level : server.getAllLevels()) {
            for (CustomEntity npc : NPCTaskScheduler.getNPCsInLevel(level)) {
                if (npc == null || !npc.isAlive() || npc.isTeleportingForWork()) {
                    continue;
                }
                if (com.xiaoliang.simukraft.utils.SelfFeedingManager.shouldBlockWorkPull(npc)) {
                    continue;
                }
                String job = npc.getJob();
                if ("farmer".equals(job)) {
                    handleFarmerTeleport(npc, level);
                }
            }
        }
    }
    
    /**
     * 处理农民传送后的状态恢复
     */
    private static void handleFarmerTeleport(CustomEntity npc, ServerLevel level) {
        if (npc.isUsingCustomPathfinder()) {
            return;
        }
        if (com.xiaoliang.simukraft.utils.SelfFeedingManager.shouldBlockWorkPull(npc)) {
            return;
        }
        BlockPos farmlandBoxPos = com.xiaoliang.simukraft.world.FarmlandHiredData.getFarmlandPosByNpc(npc.getUUID());
        
        if (farmlandBoxPos != null) {
            BlockPos npcPos = npc.blockPosition();
            double distance = npcPos.distSqr(farmlandBoxPos);
            
            if (distance > 900) {
                BlockPos targetPos = findSafePositionNearFarmland(farmlandBoxPos, level);
                if (targetPos != null) {
                    // menglannnn: 只使用寻路回来，不再强制 TP 导致抽搐
                    if (!npc.isPathfindingTo(targetPos) && npc.moveToWithNewPathfinder(targetPos, 1.0D)) {
                        Simukraft.LOGGER.info("农民寻路恢复：{} 开始前往农田盒附近 {}", npc.getFullName(), targetPos);
                    }
                }
            }
        }
    }
    
    /**
     * 在农田盒附近寻找安全位置
     */
    private static BlockPos findSafePositionNearFarmland(BlockPos farmlandBoxPos, ServerLevel level) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                BlockPos checkPos = farmlandBoxPos.offset(x, 0, z);
                BlockPos abovePos = checkPos.above();
                BlockPos belowPos = checkPos.below();
                
                if (level.getBlockState(belowPos).isSolid() && 
                    level.isEmptyBlock(checkPos) && 
                    level.isEmptyBlock(abovePos)) {
                    return checkPos;
                }
            }
        }
        return farmlandBoxPos.above();
    }
}
