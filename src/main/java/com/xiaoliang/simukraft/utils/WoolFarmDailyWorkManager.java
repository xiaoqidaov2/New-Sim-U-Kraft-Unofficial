package com.xiaoliang.simukraft.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "simukraft")
public class WoolFarmDailyWorkManager {

    /**
     * 服务器每tick触发的事件，用于检测傍晚时分并处理每日工作
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 获取服务器实例
        if (event.getServer() == null) return;

        // 遍历所有服务器世界
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // 每tick都检查
            // 使用统一的IndustrialWorkHandler处理工业控制箱工作
            com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.handleDailyWork(level);
        }
    }
}
