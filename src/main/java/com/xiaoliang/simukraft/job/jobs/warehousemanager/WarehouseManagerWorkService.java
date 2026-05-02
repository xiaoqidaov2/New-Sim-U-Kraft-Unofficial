package com.xiaoliang.simukraft.job.jobs.warehousemanager;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.core.services.AbstractWorkService;
import com.xiaoliang.simukraft.world.BaseBuildingHiredData;
import com.xiaoliang.simukraft.world.LogisticsHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class WarehouseManagerWorkService extends AbstractWorkService {

    public static final WarehouseManagerWorkService INSTANCE = new WarehouseManagerWorkService();

    public void startDailyWork(ServerLevel level) {
        if (level == null) return;

        MinecraftServer server = level.getServer();
        Map<BlockPos, UUID> hiredManagers = LogisticsHiredData.getServerBoxHiredNpcs(server);

        if (hiredManagers.isEmpty()) {
            return;
        }

        for (Map.Entry<BlockPos, UUID> entry : hiredManagers.entrySet()) {
            UUID npcUuid = entry.getValue();
            CustomEntity npc = BaseBuildingHiredData.findNPCByUuid(server, npcUuid);
            if (npc != null) {
                restoreWorkState(npc, npcUuid, level);
            }
        }
    }

    public void onLogisticsServerTick(ServerLevel level) {
        LogisticsWorkHandler.onServerTick(level);
    }

    @Override
    public void restoreWorkState(CustomEntity npc, UUID npcUuid, ServerLevel level) {
        if (npc == null || npcUuid == null || level == null) return;

        if (npc.getWorkStatus() != WorkStatus.WORKING) {
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(com.xiaoliang.simukraft.entity.WorkSubState.WORKING);
            npc.setWorking(true);
        }

        if (!"warehouse_manager".equals(npc.getJob())) {
            npc.setJob("warehouse_manager");
        }

        ItemStack heldItem = new ItemStack(Objects.requireNonNull(Items.BOOK));
        npc.setItemInHand(Objects.requireNonNull(npc.getUsedItemHand()), heldItem);
        correctWorkplaceDrift(npc, npcUuid, level);
    }

    private void correctWorkplaceDrift(CustomEntity npc, UUID npcUuid, ServerLevel level) {
        Map<BlockPos, UUID> hiredManagers = LogisticsHiredData.getServerBoxHiredNpcs(level.getServer());
        for (Map.Entry<BlockPos, UUID> entry : hiredManagers.entrySet()) {
            if (!entry.getValue().equals(npcUuid)) {
                continue;
            }
            BlockPos workplacePos = entry.getKey();
            double distanceSqr = npc.distanceToSqr(workplacePos.getX() + 0.5D, workplacePos.getY() + 1.0D, workplacePos.getZ() + 0.5D);
            if (distanceSqr > 36.0D && !npc.moveToWithNewPathfinder(workplacePos, 2.0D)) {
                npc.scheduleHireArrivalTeleport(workplacePos);
            }
            return;
        }
    }

    @Override
    public void handleContinuousWork(JobContext context) {
        if (context == null || context.level() == null) return;
        if (!dataInitialized) {
            dataInitialized = true;
        }
        onLogisticsServerTick(context.level());
    }

    @Override
    protected void onServerStart0(MinecraftServer server, ServerLevel level) {
        dataInitialized = false;
    }

    @Override
    protected void onServerStop0(ServerLevel level) {
    }

    @Override
    protected void handleDailyXp0(ServerLevel level) {
    }
}
