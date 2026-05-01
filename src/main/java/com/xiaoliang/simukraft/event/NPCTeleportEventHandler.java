package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.init.ModBlocks;
import com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler;
import com.xiaoliang.simukraft.utils.NPCTaskScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID)
@SuppressWarnings({"null", "unused", "deprecation"})
public class NPCTeleportEventHandler {
    
    // 存储NPC的传送状态
    private static final Map<Integer, TeleportState> npcTeleportStates = new HashMap<>();
    
    private static class TeleportState {
        BlockPos targetPos;
        int ticksAtTarget;
        String jobType;
        String buildingFileName; // 新增：建筑文件名
        
        TeleportState(BlockPos targetPos, String jobType, String buildingFileName) {
            this.targetPos = targetPos;
            this.ticksAtTarget = 0;
            this.jobType = jobType;
            this.buildingFileName = buildingFileName;
        }
    }
    
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }

        // 复用NPC缓存，避免每tick遍历所有实体。
        for (ServerLevel level : server.getAllLevels()) {
            for (CustomEntity npc : NPCTaskScheduler.getNPCsInLevel(level)) {
                String job = npc.getJob();
                if ("shepherd".equals(job) || "butcher".equals(job) || "worker".equals(job)) {
                    handleIndustrialNPCTeleport(npc, level);
                } else if ("farmer".equals(job)) {
                    handleFarmerTeleport(npc, level);
                }
            }
        }

        npcTeleportStates.entrySet().removeIf(entry -> isTeleportStateExpired(server, entry.getKey(), entry.getValue()));
    }

    private static boolean isTeleportStateExpired(MinecraftServer server, Integer npcId, TeleportState state) {
        for (ServerLevel level : server.getAllLevels()) {
            var entity = level.getEntity(npcId);
            if (entity instanceof CustomEntity npc) {
                return !state.targetPos.equals(npc.blockPosition());
            }
        }
        return true;
    }
    
    private static void handleIndustrialNPCTeleport(CustomEntity npc, ServerLevel level) {
        int npcId = npc.getId();
        BlockPos currentPos = npc.blockPosition();
        
        TeleportState state = npcTeleportStates.get(npcId);
        
        if (state == null) {
            // 新传送，检查是否传送到工业控制箱
            if (level.getBlockState(currentPos).getBlock() == ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
                String job = npc.getJob();
                // 获取建筑文件名
                String buildingFileName = IndustrialWorkHandler.getBuildingFileName(level, currentPos);
                if (buildingFileName == null) {
                    buildingFileName = "industrial"; // 默认值
                }
                npcTeleportStates.put(npcId, new TeleportState(currentPos, job, buildingFileName));
            }
            return;
        }
        
        // 检查NPC是否还在目标位置
        if (currentPos.equals(state.targetPos)) {
            state.ticksAtTarget++;
            
            // 如果NPC在目标位置停留了足够长时间（约1秒），认为传送完成
            if (state.ticksAtTarget >= 20) {
                // 传送完成，使用统一的处理器
                IndustrialWorkHandler.onIndustrialNpcTeleported(npc, level, state.targetPos, state.buildingFileName);
                
                // 移除状态记录
                npcTeleportStates.remove(npcId);
            }
        } else {
            // NPC移动了，清除状态
            npcTeleportStates.remove(npcId);
        }
    }
    
    /**
     * 处理农民传送后的状态恢复
     */
    private static void handleFarmerTeleport(CustomEntity npc, ServerLevel level) {
        int npcId = npc.getId();
        
        if (!npcTeleportStates.containsKey(npcId)) {
            var hiredFarmers = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
            BlockPos farmlandBoxPos = null;
            
            for (var entry : hiredFarmers.entrySet()) {
                if (entry.getValue().equals(npc.getUUID())) {
                    farmlandBoxPos = entry.getKey();
                    break;
                }
            }
            
            if (farmlandBoxPos != null) {
                BlockPos npcPos = npc.blockPosition();
                double distance = npcPos.distSqr(farmlandBoxPos);
                
                if (distance > 900) {
                    BlockPos targetPos = findSafePositionNearFarmland(farmlandBoxPos, level);
                    if (targetPos != null) {
                        if (npc.moveToWithNewPathfinder(targetPos, 1.0D)) {
                            Simukraft.LOGGER.info("农民寻路恢复：{} 开始前往农田盒附近 {}", npc.getFullName(), targetPos);
                        } else {
                            npc.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
                            npc.stopNewPathfinder();
                            npcTeleportStates.put(npcId, new TeleportState(targetPos, "farmer", null));
                            Simukraft.LOGGER.info("农民传送：{} 传送到农田盒附近 {}", npc.getFullName(), targetPos);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 农民传送完成后恢复工作状态
     */
    private static void onFarmerTeleported(CustomEntity npc, ServerLevel level) {
        npc.setWorkStatus(com.xiaoliang.simukraft.entity.WorkStatus.WORKING);
        npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
        
        net.minecraft.network.chat.Component npcName = npc.getCustomName() != null ? npc.getCustomName() : net.minecraft.network.chat.Component.literal(npc.getFullName());
        net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.translatable("message.simukraft.farmer.teleport_complete", npcName);
        java.util.UUID cityId = npc.getCityId();
        if (cityId != null) {
            com.xiaoliang.simukraft.utils.CityMessageUtils.sendToCityGroup(
                level.getServer(), cityId, message,
                com.xiaoliang.simukraft.notification.MessageCategory.FARMING
            );
        }

        Simukraft.LOGGER.info(message.getString());
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
