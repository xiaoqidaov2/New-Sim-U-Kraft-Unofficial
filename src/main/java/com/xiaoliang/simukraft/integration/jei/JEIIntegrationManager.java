package com.xiaoliang.simukraft.integration.jei;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

/**
 * simukraft: JEI 集成管理器
 * 统一管理 JEI 功能的可用性和调用
 */
@OnlyIn(Dist.CLIENT)
public class JEIIntegrationManager {

    // simukraft: JEI 是否加载
    private static final boolean JEI_LOADED = ModList.get().isLoaded("jei");

    // simukraft: 单例实例
    private static JEIIntegrationManager instance;

    private JEIIntegrationManager() {
    }

    /**
     * 获取单例实例
     */
    public static JEIIntegrationManager getInstance() {
        if (instance == null) {
            instance = new JEIIntegrationManager();
        }
        return instance;
    }

    /**
     * 检查 JEI 是否已加载
     */
    public static boolean isJEILoaded() {
        return JEI_LOADED;
    }

    /**
     * 清除所有拖拽目标（安全包装）
     * 注意：使用 JEI 的 IGhostIngredientHandler 后，此方法主要用于兼容性
     */
    public void clearDropTargets() {
        // simukraft: 使用 JEI 的标准 API 后，此方法保留用于兼容性
    }
}
