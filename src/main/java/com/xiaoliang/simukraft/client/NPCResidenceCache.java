package com.xiaoliang.simukraft.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC居住信息客户端缓存
 * 用于存储从服务端获取的NPC居住信息
 */
public class NPCResidenceCache {
    private static final long CACHE_VALIDITY = 30000L; // 缓存有效期30秒
    private static final int MAX_CACHE_SIZE = 256;
    private static final Map<String, ResidenceInfo> cache = new ConcurrentHashMap<>();
    private static volatile long lastCleanupTime = 0L;
    
    public static class ResidenceInfo {
        public final boolean hasResidence;
        public final String position;
        public final long timestamp;
        
        public ResidenceInfo(boolean hasResidence, String position) {
            this.hasResidence = hasResidence;
            this.position = position;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * 设置NPC居住信息
     */
    public static void setResidenceInfo(String npcName, boolean hasResidence, String position) {
        cleanupExpiredEntries(false);
        cache.put(npcName, new ResidenceInfo(hasResidence, position));
    }
    
    /**
     * 获取NPC居住信息
     */
    public static ResidenceInfo getResidenceInfo(String npcName) {
        cleanupExpiredEntries(false);
        ResidenceInfo info = cache.get(npcName);
        if (info == null) {
            return null;
        }
        
        // 检查缓存是否过期
        if (System.currentTimeMillis() - info.timestamp > CACHE_VALIDITY) {
            cache.remove(npcName);
            return null;
        }
        
        return info;
    }
    
    /**
     * 检查是否有缓存的居住信息
     */
    public static boolean hasCachedInfo(String npcName) {
        return getResidenceInfo(npcName) != null;
    }

    public static void remove(String npcName) {
        if (npcName == null || npcName.isEmpty()) {
            return;
        }
        cache.remove(npcName);
    }
    
    /**
     * 清除所有缓存
     */
    public static void clearCache() {
        cache.clear();
        lastCleanupTime = 0L;
    }

    private static void cleanupExpiredEntries(boolean force) {
        long now = System.currentTimeMillis();
        if (!force) {
            boolean recentlyCleaned = now - lastCleanupTime < 5000L;
            if (recentlyCleaned && cache.size() < MAX_CACHE_SIZE) {
                return;
            }
        }
        lastCleanupTime = now;
        cache.entrySet().removeIf(entry -> now - entry.getValue().timestamp > CACHE_VALIDITY);
    }
}
