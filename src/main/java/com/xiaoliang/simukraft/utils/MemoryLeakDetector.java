package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.Map;

public class MemoryLeakDetector {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    
    private static final Map<String, Long> baselineMemory = new HashMap<>();
    private static volatile long lastCheckTime = 0;
    private static final long CHECK_INTERVAL_MS = 60000;

    public static void recordBaseline(String phase) {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        baselineMemory.put(phase, heapUsage.getUsed());
        Simukraft.LOGGER.info("[MemoryLeakDetector] 记录内存基线 [{}]: {} MB", 
            phase, bytesToMB(heapUsage.getUsed()));
    }

    public static void checkForLeaks(String phase) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCheckTime < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckTime = currentTime;

        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long currentUsed = heapUsage.getUsed();
        long currentMax = heapUsage.getMax();
        double usagePercent = (double) currentUsed / currentMax * 100;

        StringBuilder report = new StringBuilder();
        report.append("\n========== 内存使用报告 [").append(phase).append("] ==========\n");
        report.append(String.format("当前堆使用: %d MB (%.1f%%)\n", bytesToMB(currentUsed), usagePercent));
        report.append(String.format("最大堆内存: %d MB\n", bytesToMB(currentMax)));
        report.append(String.format("已提交内存: %d MB\n", bytesToMB(heapUsage.getCommitted())));

        Long baseline = baselineMemory.get("startup");
        if (baseline != null) {
            long growth = currentUsed - baseline;
            report.append(String.format("较启动时增长: %d MB\n", bytesToMB(growth)));
            
            if (growth > 200 * 1024 * 1024) {
                report.append("⚠️  警告: 内存增长超过200MB，可能存在内存泄漏！\n");
            }
        }

        report.append("===============================================");
        LOGGER.info(report.toString());

        if (usagePercent > 85.0) {
            LOGGER.warn("⚠️  内存使用率超过85%，建议执行垃圾回收或检查内存泄漏！");
            System.gc();
        }
    }

    public static void forceGcAndReport() {
        LOGGER.info("[MemoryLeakDetector] 执行强制垃圾回收...");
        System.gc();
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        LOGGER.info("[MemoryLeakDetector] GC后堆内存: {} MB (最大: {} MB)", 
            bytesToMB(heapUsage.getUsed()), bytesToMB(heapUsage.getMax()));
    }

    private static long bytesToMB(long bytes) {
        return bytes / 1024 / 1024;
    }

    public static void reset() {
        baselineMemory.clear();
        lastCheckTime = 0;
    }
}
