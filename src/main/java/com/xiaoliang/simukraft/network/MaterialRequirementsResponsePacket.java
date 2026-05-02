package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.MaterialRequirementsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class MaterialRequirementsResponsePacket {
    private final List<TaskMaterialInfo> tasks;

    public MaterialRequirementsResponsePacket(List<TaskMaterialInfo> tasks) {
        this.tasks = tasks;
    }

    public MaterialRequirementsResponsePacket(FriendlyByteBuf buf) {
        int taskSize = buf.readInt();
        this.tasks = new ArrayList<>();
        for (int t = 0; t < taskSize; t++) {
            String taskName = buf.readUtf();
            String builderName = buf.readUtf();
            int materialSize = buf.readInt();
            List<MaterialInfo> materials = new ArrayList<>();
            for (int i = 0; i < materialSize; i++) {
                String blockId = buf.readUtf();
                String displayName = buf.readUtf();
                int count = buf.readInt();
                materials.add(new MaterialInfo(blockId, displayName, count));
            }
            this.tasks.add(new TaskMaterialInfo(taskName, builderName, materials));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(tasks.size());
        for (TaskMaterialInfo task : tasks) {
            buf.writeUtf(task.taskName);
            buf.writeUtf(task.builderName);
            buf.writeInt(task.materials.size());
            for (MaterialInfo material : task.materials) {
                buf.writeUtf(material.blockId);
                buf.writeUtf(material.displayName);
                buf.writeInt(material.count);
            }
        }
    }

    public static MaterialRequirementsResponsePacket decode(FriendlyByteBuf buf) {
        return new MaterialRequirementsResponsePacket(buf);
    }

    public static void handle(MaterialRequirementsResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                handleClientSide(packet);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClientSide(MaterialRequirementsResponsePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MaterialRequirementsScreen screen) {
            List<MaterialRequirementsScreen.TaskMaterialInfo> taskInfos = new ArrayList<>();
            for (TaskMaterialInfo task : packet.tasks) {
                List<MaterialRequirementsScreen.MaterialInfo> materialInfos = new ArrayList<>();
                for (MaterialInfo material : task.materials) {
                    ItemStack itemStack = createItemStackFromBlockId(material.blockId);
                    // 客户端本地进行中文映射，不使用服务端传来的displayName
                    String chineseName = com.xiaoliang.simukraft.utils.BlockNameTranslator.getItemName(itemStack);
                    materialInfos.add(new MaterialRequirementsScreen.MaterialInfo(
                        itemStack,
                        chineseName,
                        material.count
                    ));
                }
                taskInfos.add(new MaterialRequirementsScreen.TaskMaterialInfo(
                    task.taskName,
                    task.builderName,
                    materialInfos
                ));
            }
            screen.setTasks(taskInfos);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static ItemStack createItemStackFromBlockId(String blockId) {
        try {
            net.minecraft.resources.ResourceLocation resourceLocation = net.minecraft.resources.ResourceLocation.parse(blockId);
            net.minecraft.world.level.block.Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(resourceLocation);
            if (block != null && block.asItem() != Items.AIR) {
                return new ItemStack(block.asItem());
            }
        } catch (Exception e) {
            // 无法创建ItemStack
        }
        return new ItemStack(Items.BARRIER);
    }

    public static class TaskMaterialInfo {
        public final String taskName;
        public final String builderName;
        public final List<MaterialInfo> materials;

        public TaskMaterialInfo(String taskName, String builderName, List<MaterialInfo> materials) {
            this.taskName = taskName;
            this.builderName = builderName;
            this.materials = materials;
        }
    }

    public static class MaterialInfo {
        public final String blockId;
        public final String displayName;
        public final int count;

        public MaterialInfo(String blockId, String displayName, int count) {
            this.blockId = blockId;
            this.displayName = displayName;
            this.count = count;
        }
    }
}
