package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class ConstructionProgressPacket {
    private final BlockPos buildBoxPos;
    private final String buildingName;
    private final int progress;

    public ConstructionProgressPacket(BlockPos buildBoxPos, String buildingName, int progress) {
        this.buildBoxPos = buildBoxPos;
        this.buildingName = buildingName;
        this.progress = progress;
    }

    public ConstructionProgressPacket(FriendlyByteBuf buf) {
        this.buildBoxPos = buf.readBlockPos();
        this.buildingName = buf.readUtf();
        this.progress = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(buildBoxPos);
        buf.writeUtf(buildingName);
        buf.writeInt(progress);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 使用DistExecutor确保客户端逻辑只在客户端执行
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClientSide(this));
        });
        ctx.get().setPacketHandled(true);
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void handleClientSide(ConstructionProgressPacket message) {
        try {
            // 使用反射调用BuildBoxData.setBuildProgress方法
            Class<?> buildBoxDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.BuildBoxData");
            buildBoxDataClass.getMethod("setBuildProgress", BlockPos.class, int.class).invoke(null, message.buildBoxPos, message.progress);
            
            // 当建筑完成时（进度100%），不再自动返回建筑盒初始界面，只显示提示语和音效
            if (message.progress >= 100) {
                // 使用反射获取Minecraft实例
                Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
                Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
                
                // 使用反射执行客户端逻辑
                minecraftClass.getMethod("execute", Runnable.class).invoke(minecraft, (Runnable) () -> {
                    try {
                        // 添加绿色字体提示语并播放音效
                        Object player = minecraftClass.getField("player").get(minecraft);
                        if (player != null) {
                            Class<?> playerClass = Class.forName("net.minecraft.client.player.LocalPlayer");
                            Component messageComponent = Component.translatable("message.simukraft.construction.complete_reminder").withStyle(style -> style.withColor(0x00FF00));
                            playerClass.getMethod("displayClientMessage", Component.class, boolean.class).invoke(player, messageComponent, false);
                            
                            // 播放建筑开始音效
                            Class<?> soundEventsClass = Class.forName("com.xiaoliang.simukraft.init.ModSoundEvents");
                            Object buildingStartSound = soundEventsClass.getField("BUILDING_START").get(null);
                            
                            Class<?> soundInstanceClass = Class.forName("net.minecraft.client.resources.sounds.SimpleSoundInstance");
                            Class<?> soundEventClass = Class.forName("net.minecraft.sounds.SoundEvent");
                            Object soundInstance = soundInstanceClass.getMethod("forUI", soundEventClass, float.class, float.class)
                                .invoke(null, buildingStartSound, 1.0F, 1.0F);
                            
                            Class<?> soundManagerClass = Class.forName("net.minecraft.client.sounds.SoundManager");
                            Object soundManager = minecraftClass.getMethod("getSoundManager").invoke(minecraft);
                            soundManagerClass.getMethod("play", soundInstanceClass).invoke(soundManager, soundInstance);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public BlockPos getBuildBoxPos() {
        return buildBoxPos;
    }

    public String getBuildingName() {
        return buildingName;
    }

    public int getProgress() {
        return progress;
    }
}