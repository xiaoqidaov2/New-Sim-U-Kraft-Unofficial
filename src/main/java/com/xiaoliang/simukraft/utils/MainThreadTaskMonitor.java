package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

public class MainThreadTaskMonitor {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private static final AtomicLong totalEnqueuedTasks = new AtomicLong(0);
    private static final AtomicLong totalDequeuedTasks = new AtomicLong(0);
    private static final AtomicLong totalRejectedTasks = new AtomicLong(0);
    private static final AtomicLong maxQueueSize = new AtomicLong(0);
    
    private static volatile long lastReportTime = 0;
    private static final long REPORT_INTERVAL_MS = 30000;

    public static void recordEnqueue() {
        totalEnqueuedTasks.incrementAndGet();
        
        int currentSize = NPCTaskScheduler.getMainThreadQueueSize();
        long currentMax = maxQueueSize.get();
        while (currentSize > currentMax) {
            if (maxQueueSize.compareAndSet(currentMax, currentSize)) {
                break;
            }
            currentMax = maxQueueSize.get();
        }
    }

    public static void recordDequeue() {
        totalDequeuedTasks.incrementAndGet();
    }

    public static void recordRejection() {
        totalRejectedTasks.incrementAndGet();
    }

    public static void checkAndReport(MinecraftServer server) {
        if (server == null) return;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastReportTime < REPORT_INTERVAL_MS) {
            return;
        }
        
        lastReportTime = currentTime;
        
        long enqueued = totalEnqueuedTasks.getAndSet(0);
        long dequeued = totalDequeuedTasks.getAndSet(0);
        long rejected = totalRejectedTasks.getAndSet(0);
        long maxQSize = maxQueueSize.getAndSet(0);
        
        int currentQSize = NPCTaskScheduler.getMainThreadQueueSize();
        double tps = NPCTaskScheduler.getCurrentTps();
        
        double processingRate = enqueued > 0 ? (double) dequeued / enqueued : 0;
        
        StringBuilder report = new StringBuilder();
        report.append("\n========== 主线程任务队列监控报告 ==========\n");
        report.append(String.format("当前TPS: %.1f\n", tps));
        report.append(String.format("当前队列大小: %d\n", currentQSize));
        report.append(String.format("最大队列大小: %d\n", maxQSize));
        report.append(String.format("入队任务数: %d\n", enqueued));
        report.append(String.format("出队任务数: %d\n", dequeued));
        report.append(String.format("拒绝任务数: %d\n", rejected));
        report.append(String.format("处理率: %.2f%%\n", processingRate * 100));
        
        String status;
        if (tps >= 19.0 && currentQSize < 50) {
            status = "§a健康";
        } else if (tps >= 17.0 && currentQSize < 150) {
            status = "§e正常";
        } else if (tps >= 15.0 && currentQSize < 300) {
            status = "§6警告";
        } else {
            status = "§c危险";
        }
        
        report.append(String.format("状态: %s\n", status));
        report.append("===========================================");
        
        LOGGER.info(report.toString());
        
        if (rejected > 0) {
            LOGGER.warn("⚠️ 有 {} 个任务被拒绝，可能需要优化任务提交逻辑！", rejected);
        }
        
        if (maxQSize > 400) {
            LOGGER.warn("⚠️ 队列峰值过高({}个)，建议增加处理能力或减少任务提交！", maxQSize);
        }
    }

    public static void reset() {
        totalEnqueuedTasks.set(0);
        totalDequeuedTasks.set(0);
        totalRejectedTasks.set(0);
        maxQueueSize.set(0);
        lastReportTime = 0;
    }
}
