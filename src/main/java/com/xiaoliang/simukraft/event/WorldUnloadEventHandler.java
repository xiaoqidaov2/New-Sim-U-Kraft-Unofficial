package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.utils.GlobalResourceCleaner;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WorldUnloadEventHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            LOGGER.info("[WorldUnload] 服务器世界卸载，执行资源清理...");
            GlobalResourceCleaner.cleanupAll();
        } else {
            LOGGER.info("[WorldUnload] 客户端世界卸载，执行资源清理...");
            GlobalResourceCleaner.cleanupAll();
        }
    }
}
