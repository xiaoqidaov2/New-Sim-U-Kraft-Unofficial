package com.xiaoliang.simukraft.job.jobs.industrialgeneric;

import com.xiaoliang.simukraft.building.IndustrialBuildingConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.core.services.AbstractWorkService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

public class IndustrialGenericWorkService extends AbstractWorkService {

    public void handleDailyWork(ServerLevel level) {
        IndustrialWorkHandler.handleDailyWork(level);
    }

    public void onIndustrialNpcHired(CustomEntity npc, ServerLevel level, BlockPos farmPos, String buildingFileName) {
        IndustrialWorkHandler.onIndustrialNpcHired(npc, level, farmPos, buildingFileName);
    }

    public void onIndustrialNpcTeleported(CustomEntity npc, ServerLevel level, BlockPos farmPos, String buildingFileName) {
        IndustrialWorkHandler.onIndustrialNpcTeleported(npc, level, farmPos, buildingFileName);
    }

    public void restoreNpcAfterRest(CustomEntity npc, ServerLevel level, BlockPos farmPos, String buildingFileName) {
        IndustrialWorkHandler.restoreNpcAfterRest(npc, level, farmPos, buildingFileName);
    }

    public void setNpcHeldItem(CustomEntity npc, IndustrialBuildingConfig config, String selectedRecipeId) {
        IndustrialWorkHandler.setNpcHeldItem(npc, config, selectedRecipeId);
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
        IndustrialWorkHandler.onServerStart(server);
    }

    @Override
    protected void onServerStop0(ServerLevel level) {
    }

    @Override
    protected void handleDailyXp0(ServerLevel level) {
    }
}
