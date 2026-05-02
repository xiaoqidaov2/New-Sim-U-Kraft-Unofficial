package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.ConstructionTasksScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class ConstructionTasksResponsePacket {
    private final List<TaskInfo> tasks;

    public ConstructionTasksResponsePacket(List<TaskInfo> tasks) {
        this.tasks = tasks;
    }

    public ConstructionTasksResponsePacket(FriendlyByteBuf buf) {
        int size = buf.readInt();
        this.tasks = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String buildingName = buf.readUtf();
            String builderName = buf.readUtf();
            int progress = buf.readInt();
            boolean completed = buf.readBoolean();
            this.tasks.add(new TaskInfo(buildingName, builderName, progress, completed));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(tasks.size());
        for (TaskInfo task : tasks) {
            buf.writeUtf(task.buildingName);
            buf.writeUtf(task.builderName);
            buf.writeInt(task.progress);
            buf.writeBoolean(task.completed);
        }
    }

    public static ConstructionTasksResponsePacket decode(FriendlyByteBuf buf) {
        return new ConstructionTasksResponsePacket(buf);
    }

    public static void handle(ConstructionTasksResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 只在客户端处理
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                handleClientSide(packet);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClientSide(ConstructionTasksResponsePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ConstructionTasksScreen screen) {
            // 转换任务信息
            List<ConstructionTasksScreen.ConstructionTaskInfo> taskInfos = new ArrayList<>();
            for (TaskInfo task : packet.tasks) {
                taskInfos.add(new ConstructionTasksScreen.ConstructionTaskInfo(
                    task.buildingName,
                    task.builderName,
                    task.progress,
                    task.completed
                ));
            }
            screen.setTasks(taskInfos);
        }
    }

    /**
     * 任务信息类
     */
    public static class TaskInfo {
        public final String buildingName;
        public final String builderName;
        public final int progress;
        public final boolean completed;

        public TaskInfo(String buildingName, String builderName, int progress, boolean completed) {
            this.buildingName = buildingName;
            this.builderName = builderName;
            this.progress = progress;
            this.completed = completed;
        }
    }
}
