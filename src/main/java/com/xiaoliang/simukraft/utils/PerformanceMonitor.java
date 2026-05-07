package com.xiaoliang.simukraft.utils;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量级性能监控器。
 * 目标是持续采集主热点耗时与吞吐量，方便对傍晚卡顿和物流扫描问题做对比回归。
 */
public final class PerformanceMonitor {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final long REPORT_INTERVAL_TICKS = 600L;
    private static final int TOP_SECTION_LIMIT = 8;

    private static final Map<String, SectionStat> SECTION_STATS = new ConcurrentHashMap<>();
    private static final Map<String, NumericStat> NUMERIC_STATS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_REPORT_TICKS = new ConcurrentHashMap<>();

    private PerformanceMonitor() {
    }

    public static long beginSection() {
        return System.nanoTime();
    }

    public static void endSection(ServerLevel level, String sectionName, long startNanos) {
        if (sectionName == null || sectionName.isBlank()) {
            return;
        }
        long elapsed = Math.max(0L, System.nanoTime() - startNanos);
        SECTION_STATS.computeIfAbsent(sectionName, ignored -> new SectionStat()).record(elapsed);

        if (level != null) {
            recordValue("npc.active." + level.dimension().location(), NPCTaskScheduler.getNPCsInLevel(level).size());
        }
    }

    public static void recordCount(String metricName) {
        recordValue(metricName, 1L);
    }

    public static void recordValue(String metricName, long value) {
        if (metricName == null || metricName.isBlank()) {
            return;
        }
        NUMERIC_STATS.computeIfAbsent(metricName, ignored -> new NumericStat()).record(value);
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }

        long gameTime = level.getGameTime();
        Long lastTick = LAST_REPORT_TICKS.putIfAbsent(level.dimension(), gameTime);
        if (lastTick == null) {
            return;
        }
        if (gameTime - lastTick < REPORT_INTERVAL_TICKS) {
            return;
        }

        LAST_REPORT_TICKS.put(level.dimension(), gameTime);
        flush(level);
    }

    private static synchronized void flush(ServerLevel level) {
        List<SectionSnapshot> sectionSnapshots = new ArrayList<>();
        for (Map.Entry<String, SectionStat> entry : SECTION_STATS.entrySet()) {
            SectionSnapshot snapshot = entry.getValue().snapshotAndReset(entry.getKey());
            if (snapshot.calls() > 0L) {
                sectionSnapshots.add(snapshot);
            }
        }

        List<NumericSnapshot> numericSnapshots = new ArrayList<>();
        for (Map.Entry<String, NumericStat> entry : NUMERIC_STATS.entrySet()) {
            NumericSnapshot snapshot = entry.getValue().snapshotAndReset(entry.getKey());
            if (snapshot.samples() > 0L) {
                numericSnapshots.add(snapshot);
            }
        }

        if (sectionSnapshots.isEmpty() && numericSnapshots.isEmpty()) {
            return;
        }

        sectionSnapshots.sort(Comparator.comparingLong(SectionSnapshot::totalNanos).reversed());
        numericSnapshots.sort(Comparator.comparingLong(NumericSnapshot::totalValue).reversed());

        Runtime runtime = Runtime.getRuntime();
        long usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long totalMemoryMb = runtime.totalMemory() / (1024L * 1024L);
        long maxMemoryMb = runtime.maxMemory() / (1024L * 1024L);
        long timeOfDay = level.getDayTime() % 24000L;

        StringBuilder builder = new StringBuilder();
        builder.append("[Perf] dim=")
                .append(level.dimension().location())
                .append(" tick=")
                .append(level.getGameTime())
                .append(" timeOfDay=")
                .append(timeOfDay)
                .append(" mem=")
                .append(usedMemoryMb)
                .append("MB/")
                .append(totalMemoryMb)
                .append("MB max=")
                .append(maxMemoryMb)
                .append("MB");

        int sectionLimit = Math.min(sectionSnapshots.size(), TOP_SECTION_LIMIT);
        for (int i = 0; i < sectionLimit; i++) {
            SectionSnapshot snapshot = sectionSnapshots.get(i);
            long totalMs = snapshot.totalNanos() / 1_000_000L;
            long avgMicros = snapshot.calls() == 0L ? 0L : snapshot.totalNanos() / snapshot.calls() / 1_000L;
            long maxMicros = snapshot.maxNanos() / 1_000L;
            builder.append(" | ")
                    .append(snapshot.name())
                    .append(":total=")
                    .append(totalMs)
                    .append("ms,calls=")
                    .append(snapshot.calls())
                    .append(",avg=")
                    .append(avgMicros)
                    .append("us,max=")
                    .append(maxMicros)
                    .append("us");
        }

        int numericLimit = Math.min(numericSnapshots.size(), 6);
        for (int i = 0; i < numericLimit; i++) {
            NumericSnapshot snapshot = numericSnapshots.get(i);
            long avgValue = snapshot.samples() == 0L ? 0L : snapshot.totalValue() / snapshot.samples();
            builder.append(" | ")
                    .append(snapshot.name())
                    .append(":sum=")
                    .append(snapshot.totalValue())
                    .append(",avg=")
                    .append(avgValue)
                    .append(",max=")
                    .append(snapshot.maxValue());
        }

        LOGGER.info(builder.toString());
    }

    private static final class SectionStat {
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();

        private void record(long nanos) {
            totalNanos.addAndGet(nanos);
            calls.incrementAndGet();
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        private SectionSnapshot snapshotAndReset(String name) {
            long total = totalNanos.getAndSet(0L);
            long callCount = calls.getAndSet(0L);
            long max = maxNanos.getAndSet(0L);
            return new SectionSnapshot(name, total, callCount, max);
        }
    }

    private static final class NumericStat {
        private final AtomicLong totalValue = new AtomicLong();
        private final AtomicLong samples = new AtomicLong();
        private final AtomicLong maxValue = new AtomicLong();

        private void record(long value) {
            totalValue.addAndGet(value);
            samples.incrementAndGet();
            maxValue.accumulateAndGet(value, Math::max);
        }

        private NumericSnapshot snapshotAndReset(String name) {
            long total = totalValue.getAndSet(0L);
            long sampleCount = samples.getAndSet(0L);
            long max = maxValue.getAndSet(0L);
            return new NumericSnapshot(name, total, sampleCount, max);
        }
    }

    private record SectionSnapshot(String name, long totalNanos, long calls, long maxNanos) {
    }

    private record NumericSnapshot(String name, long totalValue, long samples, long maxValue) {
    }
}
