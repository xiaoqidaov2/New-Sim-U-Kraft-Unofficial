package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class CachePerformanceMonitor {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final ConcurrentHashMap<String, AtomicLong> METRICS = new ConcurrentHashMap<>();
    
    private static volatile long lastReportTime = 0;
    private static final long REPORT_INTERVAL_MS = 60000;

    public static void recordMetric(String name, long value) {
        METRICS.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(value);
    }

    public static void incrementCounter(String name) {
        METRICS.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    public static void logMetrics(MinecraftServer server) {
        if (server == null) return;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastReportTime < REPORT_INTERVAL_MS) {
            return;
        }
        
        lastReportTime = currentTime;
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== NPC缓存性能报告 ==========\n");
        
        METRICS.forEach((key, value) -> {
            sb.append(String.format("%-30s: %d\n", key, value.get()));
        });
        
        sb.append("=====================================");
        LOGGER.info(sb.toString());
        
        METRICS.clear();
    }

    public static void reset() {
        METRICS.clear();
        lastReportTime = 0;
    }
}
