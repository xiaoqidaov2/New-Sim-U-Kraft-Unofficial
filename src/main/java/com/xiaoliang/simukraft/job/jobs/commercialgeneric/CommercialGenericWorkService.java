package com.xiaoliang.simukraft.job.jobs.commercialgeneric;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.core.services.AbstractWorkService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

public class CommercialGenericWorkService extends AbstractWorkService {

    public void handleRestock(ServerLevel level, long dayTime) {
        CommercialWorkHandler.handleRestock(level, dayTime);
    }

    public void handleDailyWork(ServerLevel level) {
        CommercialWorkHandler.handleDailyWork(level);
    }

    public void handleNPCSellMode(CustomEntity npc, BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        CommercialWorkHandler.handleNPCSellMode(npc, pos, level, config);
    }

    public void handlePlayerSellMode(BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        CommercialWorkHandler.handlePlayerSellMode(pos, level, config);
    }

    public void handleMixedMode(CustomEntity npc, BlockPos pos, ServerLevel level, CommercialBuildingConfig config) {
        CommercialWorkHandler.handleMixedMode(npc, pos, level, config);
    }

    public void setHeldItemFromConfig(CustomEntity npc, CommercialBuildingConfig config) {
        CommercialWorkHandler.setHeldItemFromConfig(npc, config);
    }

    @Override
    public void restoreWorkState(CustomEntity npc, UUID npcUuid, ServerLevel level) {
        if (npc == null || level == null) return;

        if (npc.getWorkStatus() != WorkStatus.WORKING) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
            npc.setWorking(true);
        }
    }

    @Override
    public void handleContinuousWork(JobContext context) {
        if (context == null || context.level() == null) return;
        if (!dataInitialized) {
            dataInitialized = true;
        }
        handleDailyWork(context.level());
    }

    @Override
    protected void onServerStart0(MinecraftServer server, ServerLevel level) {
        CommercialWorkHandler.onServerStart(server);
    }

    @Override
    protected void onServerStop0(ServerLevel level) {
    }

    @Override
    protected void handleDailyXp0(ServerLevel level) {
    }
}
