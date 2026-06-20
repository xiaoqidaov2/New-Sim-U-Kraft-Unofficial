package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局资源清理管理器
 * 负责在世界卸载/服务器关闭时清理所有静态缓存和集合
 */
public class GlobalResourceCleaner {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private static volatile boolean initialized = false;
    private static final Set<String> registeredResources = ConcurrentHashMap.newKeySet();

    /**
     * 注册需要清理的资源
     */
    public static void registerCleanableResource(String name, Runnable cleaner) {
        if (name == null || cleaner == null) return;
        
        ResourceRegistry.register(name, cleaner);
        registeredResources.add(name);
        
        Simukraft.LOGGER.debug("[GlobalResourceCleaner] 已注册清理资源: {}", name);
    }

    /**
     * 清理所有注册的资源
     */
    public static void cleanupAll() {
        if (!initialized) {
            initialize();
        }
        
        LOGGER.info("[GlobalResourceCleaner] 开始清理 {} 个注册的资源...", registeredResources.size());
        
        int successCount = 0;
        int failureCount = 0;
        
        for (String resourceName : registeredResources) {
            try {
                ResourceRegistry.executeCleanup(resourceName);
                successCount++;
            } catch (Exception e) {
                failureCount++;
                LOGGER.error("[GlobalResourceCleaner] 清理资源失败: {}", resourceName, e);
            }
        }
        
        LOGGER.info("[GlobalResourceCleaner] 清理完成 - 成功: {}, 失败: {}", successCount, failureCount);
    }

    /**
     * 清理指定资源
     */
    public static void cleanupResource(String resourceName) {
        if (resourceName == null) return;
        
        try {
            if (ResourceRegistry.executeCleanup(resourceName)) {
                LOGGER.info("[GlobalResourceCleaner] 已清理资源: {}", resourceName);
            }
        } catch (Exception e) {
            LOGGER.error("[GlobalResourceCleaner] 清理资源失败: {}", resourceName, e);
        }
    }

    /**
     * 获取已注册资源数量
     */
    public static int getRegisteredResourceCount() {
        return registeredResources.size();
    }

    private static void initialize() {
        if (initialized) return;
        synchronized (GlobalResourceCleaner.class) {
            if (initialized) return;
            initialized = true;
            LOGGER.info("[GlobalResourceCleaner] 全局资源清理器已初始化");
        }
    }

    private static class ResourceRegistry {
        private static final Map<String, Runnable> CLEANERS = new ConcurrentHashMap<>();

        static void register(String name, Runnable cleaner) {
            CLEANERS.put(name, cleaner);
        }

        static boolean executeCleanup(String name) {
            Runnable cleaner = CLEANERS.get(name);
            if (cleaner != null) {
                cleaner.run();
                return true;
            }
            return false;
        }
    }
}
