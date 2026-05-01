package com.xiaoliang.simukraft.job.jobs.planner;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.notification.MessageCategory;
import com.xiaoliang.simukraft.planning.PlanningTask;
import com.xiaoliang.simukraft.utils.CityMessageUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

public final class PlannerNotificationService {
    private final CustomEntity npc;
    private long lastWarningTime;

    public PlannerNotificationService(CustomEntity npc) {
        this.npc = npc;
    }

    public void sendMaterialInsufficientMessage(ServerLevel level, PlanningTask task, Component blockNameComponent) {
        if (shouldSkipWarning()) {
            return;
        }

        UUID cityId = npc.getCityId();
        if (cityId == null) return;

        String taskTypeKey = switch (task.getType()) {
            case REMOVE -> "gui.planning.task.remove";
            case REPLACE -> "gui.planning.task.replace";
            case FILL -> "gui.planning.task.fill";
        };

        Component content = Component.translatable("message.simukraft.planning.need_materials", taskTypeKey, blockNameComponent, 5);
        CityMessageUtils.sendToMayorViaService(level.getServer(), cityId,
                Component.translatable("notify.title.planning"), content,
                MessageCategory.CONSTRUCTION);
    }

    public void sendChestFullMessage(ServerLevel level, PlanningTask task) {
        if (shouldSkipWarning()) {
            return;
        }

        UUID cityId = npc.getCityId();
        if (cityId == null) return;

        Component taskTypeComp = task != null ? switch (task.getType()) {
            case REMOVE -> Component.translatable("message.simukraft.planning.task_type.remove");
            case REPLACE -> Component.translatable("message.simukraft.planning.task_type.replace");
            case FILL -> Component.translatable("message.simukraft.planning.task_type.fill");
        } : Component.translatable("message.simukraft.planning.task_type.plan");

        Component content = Component.translatable("message.simukraft.planning.chest_full", taskTypeComp);
        CityMessageUtils.sendToMayorViaService(level.getServer(), cityId,
                Component.translatable("notify.title.planning"), content,
                MessageCategory.CONSTRUCTION);
    }

    public void sendCompletionMessage(ServerLevel level, PlanningTask task) {
        UUID cityId = npc.getCityId();
        if (cityId == null) return;

        Component taskTypeComp = switch (task.getType()) {
            case REMOVE -> Component.translatable("message.simukraft.planning.task_type.remove");
            case REPLACE -> Component.translatable("message.simukraft.planning.task_type.replace");
            case FILL -> Component.translatable("message.simukraft.planning.task_type.fill");
        };

        Component content = Component.translatable(
                "message.simukraft.planning.completed",
                npc.getName().getString(), taskTypeComp, task.getTotalBlocks());

        CityMessageUtils.sendToMayorViaService(level.getServer(), cityId,
                Component.translatable("notify.title.planning"), content,
                MessageCategory.CONSTRUCTION);
    }

    private boolean shouldSkipWarning() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastWarningTime < ServerConfig.getPlannerWarningCooldownMs()) {
            return true;
        }
        lastWarningTime = currentTime;
        return false;
    }
}
