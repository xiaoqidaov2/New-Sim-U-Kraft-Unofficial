package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.client.gui.guidebook.GuideBookLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 重载指南书数据包
 * 服务器发送通知到客户端，触发指南书热重载
 */
@SuppressWarnings({"null", "unused"})
public class ReloadGuideBookPacket {

    public ReloadGuideBookPacket() {
    }

    public ReloadGuideBookPacket(FriendlyByteBuf buf) {
        // 无数据需要读取
    }

    public void encode(FriendlyByteBuf buf) {
        // 无数据需要写入
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                handleClient();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private void handleClient() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getResourceManager() != null) {
                int pages = GuideBookLoader.reload(minecraft.getResourceManager());
                Simukraft.LOGGER.info("[ReloadGuideBookPacket] 客户端指南书已热重载，共 {} 页", pages);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[ReloadGuideBookPacket] 客户端重载指南书失败", e);
        }
    }
}
