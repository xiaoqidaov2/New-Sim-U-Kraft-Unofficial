package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * NPC任务调度器
 * 使用多线程处理NPC任务，避免主线程阻塞
 */
@Mod.EventBusSubscriber(modid = "simukraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class NPCTaskScheduler {
    private static final long SHUTDOWN_WAIT_MILLIS = 500L;
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_MAIN_THREAD_TASKS_PER_TICK = 100;

    // 线程池配置
    private static final int CORE_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final int TASK_BATCH_SIZE = 50; // 每批处理的NPC数量
    private static final int MAX_PENDING_TASKS = 1000; // 最大待处理任务数

    // 线程池
    private static ExecutorService taskExecutor;

    // 任务统计
    private static final AtomicInteger pendingTaskCount = new AtomicInteger(0);
    private static final AtomicInteger completedTaskCount = new AtomicInteger(0);
    private static final AtomicInteger rejectedTaskCount = new AtomicInteger(0);

    // 主线程任务队列（用于将异步结果同步回主线程）
    private static final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();

    // 初始化标志
    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();

    // 性能监控
    private static long lastStatsTime = 0;
    private static final long STATS_INTERVAL_MS = 30000; // 30秒输出一次统计

    // 仅缓存弱引用，避免世界卸载后仍被静态集合强持有
    private static final Map<ResourceKey<Level>, CopyOnWriteArrayList<WeakReference<CustomEntity>>> NPC_CACHE = new ConcurrentHashMap<>();
    private static volatile long lastCacheUpdate = 0;
    private static final long CACHE_UPDATE_INTERVAL = 100; // 每5秒更新一次缓存（100 ticks）

    /**
     * 初始化线程池
     */
    public static void initialize() {
        if (initialized) return;

        synchronized (initLock) {
            if (initialized) return;

            try {
                ThreadPoolExecutor executor = new ThreadPoolExecutor(
                        CORE_POOL_SIZE,
                        MAX_POOL_SIZE,
                        KEEP_ALIVE_TIME,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(MAX_PENDING_TASKS),
                        createNamedThreadFactory("NPCTask-Worker-"),
                        new ThreadPoolExecutor.CallerRunsPolicy()
                );
                executor.allowCoreThreadTimeOut(true);

                taskExecutor = executor;
                initialized = true;
                LOGGER.info("NPC任务调度器已初始化，核心线程数: {}, 最大线程数: {}", CORE_POOL_SIZE, MAX_POOL_SIZE);
            } catch (Exception e) {
                LOGGER.error("初始化NPC任务调度器失败", e);
            }
        }
    }

    /**
     * 关闭线程池
     */
    public static void shutdown() {
        synchronized (initLock) {
            if (!initialized) return;

            LOGGER.info("正在关闭NPC任务调度器...");

            // 拒绝新任务
            if (taskExecutor != null) {
                taskExecutor.shutdown();
                try {
                    if (!taskExecutor.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                        taskExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    taskExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // 清空队列
            mainThreadTasks.clear();
            pendingTaskCount.set(0);
            invalidateCache();

            initialized = false;
            LOGGER.info("NPC任务调度器已关闭");
        }
    }

    /**
     * 提交任务到线程池
     */
    public static void submitTask(Runnable task, String taskName) {
        if (task == null) {
            return;
        }
        if (!initialized) {
            initialize();
        }

        if (taskExecutor == null || taskExecutor.isShutdown()) {
            LOGGER.warn("任务调度器未初始化或已关闭，任务 {} 将在主线程执行", taskName);
            task.run();
            return;
        }

        try {
            pendingTaskCount.incrementAndGet();
            taskExecutor.submit(() -> {
                try {
                    long startTime = System.nanoTime();
                    task.run();
                    long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

                    if (duration > 100) {
                        LOGGER.warn("任务 {} 执行时间过长: {}ms", taskName, duration);
                    }
                } catch (Exception e) {
                    LOGGER.error("执行任务 {} 时发生错误", taskName, e);
                } finally {
                    pendingTaskCount.decrementAndGet();
                    completedTaskCount.incrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            rejectedTaskCount.incrementAndGet();
            LOGGER.warn("任务 {} 被拒绝，将在主线程执行", taskName);
            task.run();
        }
    }

    /**
     * 提交需要在主线程执行结果的任务
     */
    public static void submitTaskWithMainThreadCallback(Runnable asyncTask, Runnable mainThreadCallback, String taskName) {
        submitCallableWithMainThreadCallback(() -> {
            if (asyncTask != null) {
                asyncTask.run();
            }
            return null;
        }, ignored -> {
            if (mainThreadCallback != null) {
                mainThreadCallback.run();
            }
        }, taskName);
    }

    /**
     * 提交后台计算，并在主线程消费结果。
     * 适用于“异步准备数据 + 主线程应用结果”的两阶段流水线。
     */
    public static <T> void submitCallableWithMainThreadCallback(Callable<T> asyncTask,
                                                                Consumer<T> mainThreadCallback,
                                                                String taskName) {
        submitTask(() -> {
            T result;
            try {
                result = asyncTask.call();
            } catch (Exception e) {
                LOGGER.error("执行异步计算任务 {} 时发生错误", taskName, e);
                return;
            }

            if (mainThreadCallback != null) {
                final T finalResult = result;
                enqueueMainThreadTask(() -> mainThreadCallback.accept(finalResult), taskName + "-Callback");
            }
        }, taskName);
    }

    /**
     * 直接排队一个主线程任务，避免为纯主线程操作额外占用工作线程。
     */
    public static void enqueueMainThreadTask(Runnable mainThreadTask, String taskName) {
        if (mainThreadTask == null) {
            return;
        }
        if (!initialized) {
            initialize();
        }
        if (taskExecutor == null || taskExecutor.isShutdown()) {
            LOGGER.warn("主线程任务队列不可用，任务 {} 将立即执行", taskName);
            mainThreadTask.run();
            return;
        }
        mainThreadTasks.offer(mainThreadTask);
    }

    /**
     * 在需要时切换到主线程执行世界操作；如果当前已经位于主线程则立即执行。
     */
    public static void runOnMainThread(MinecraftServer server, Runnable mainThreadTask, String taskName) {
        if (mainThreadTask == null) {
            return;
        }
        if (server != null && server.isSameThread()) {
            try {
                mainThreadTask.run();
            } catch (Exception e) {
                LOGGER.error("执行主线程任务 {} 时发生错误", taskName, e);
            }
            return;
        }
        enqueueMainThreadTask(mainThreadTask, taskName);
    }

    /**
     * 批量提交NPC任务
     */
    public static void submitNPCTasks(List<CustomEntity> npcs, Consumer<CustomEntity> taskProcessor, String taskName) {
        if (npcs == null || npcs.isEmpty()) return;

        // 将NPC分批处理
        List<List<CustomEntity>> batches = createBatches(npcs, TASK_BATCH_SIZE);

        for (int i = 0; i < batches.size(); i++) {
            final List<CustomEntity> batch = batches.get(i);
            final int batchIndex = i;

            submitTask(() -> {
                for (CustomEntity npc : batch) {
                    try {
                        if (npc != null && npc.isAlive()) {
                            taskProcessor.accept(npc);
                        }
                    } catch (Exception e) {
                        LOGGER.error("处理NPC {} 的任务时发生错误", npc != null ? npc.getFullName() : "null", e);
                    }
                }
            }, taskName + "-Batch" + batchIndex);
        }
    }

    /**
     * 创建分批列表
     */
    private static <T> List<List<T>> createBatches(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(new ArrayList<>(items.subList(i, Math.min(i + batchSize, items.size()))));
        }
        return batches;
    }

    /**
     * 服务器tick事件处理
     * 在主线程执行待处理的任务回调
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 初始化（如果尚未初始化）
        if (!initialized) {
            initialize();
        }

        // 执行主线程任务（限制每tick执行数量，避免阻塞）
        int processedCount = drainMainThreadTasks(MAX_MAIN_THREAD_TASKS_PER_TICK);

        // 如果还有剩余任务，记录日志
        int remainingTasks = mainThreadTasks.size();
        if (remainingTasks > MAX_MAIN_THREAD_TASKS_PER_TICK) {
            LOGGER.debug("主线程任务队列积压: {} 个任务", remainingTasks);
        }

        // 定期输出统计信息
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastStatsTime > STATS_INTERVAL_MS) {
            outputStats(processedCount);
            lastStatsTime = currentTime;
        }
    }

    private static int drainMainThreadTasks(int maxTasks) {
        int processedCount = 0;
        Runnable task;
        while (processedCount < maxTasks && (task = mainThreadTasks.poll()) != null) {
            try {
                task.run();
                processedCount++;
            } catch (Exception e) {
                LOGGER.error("执行主线程任务时发生错误", e);
            }
        }
        return processedCount;
    }

    /**
     * 输出统计信息
     */
    private static void outputStats(int processedMainThreadTasks) {
        int pending = pendingTaskCount.get();
        int completed = completedTaskCount.getAndSet(0); // 重置计数器
        int rejected = rejectedTaskCount.getAndSet(0);
        int queuedMainThreadTasks = mainThreadTasks.size();

        if (pending > 0 || completed > 0 || rejected > 0 || queuedMainThreadTasks > 0 || processedMainThreadTasks > 0) {
            LOGGER.info("NPC任务调度器统计 - 待处理: {}, 已完成: {}, 被拒绝: {}, 主线程已执行: {}, 主线程排队: {}",
                    pending, completed, rejected, processedMainThreadTasks, queuedMainThreadTasks);
        }
    }

    /**
     * 获取所有NPC列表（从所有世界）- 使用缓存优化
     */
    public static List<CustomEntity> getAllNPCs(MinecraftServer server) {
        List<CustomEntity> allNPCs = new ArrayList<>();

        if (server == null) return allNPCs;

        // 检查是否需要更新缓存
        long currentTime = System.currentTimeMillis();
        boolean needUpdate = NPC_CACHE.isEmpty() || (currentTime - lastCacheUpdate > CACHE_UPDATE_INTERVAL * 50);

        if (needUpdate) {
            NPC_CACHE.clear();
            for (ServerLevel level : server.getAllLevels()) {
                refreshLevelCache(level);
            }
            lastCacheUpdate = currentTime;
        }

        // 从缓存中获取NPC列表
        for (CopyOnWriteArrayList<WeakReference<CustomEntity>> levelNPCs : NPC_CACHE.values()) {
            collectAliveNPCs(levelNPCs, allNPCs);
        }

        return allNPCs;
    }

    /**
     * 获取指定世界的NPC列表 - 使用缓存
     */
    public static List<CustomEntity> getNPCsInLevel(ServerLevel level) {
        if (level == null) return new ArrayList<>();

        // 检查缓存是否有效
        long currentTime = System.currentTimeMillis();
        ResourceKey<Level> levelKey = level.dimension();
        CopyOnWriteArrayList<WeakReference<CustomEntity>> cachedNPCs = NPC_CACHE.get(levelKey);

        if (cachedNPCs == null || (currentTime - lastCacheUpdate > CACHE_UPDATE_INTERVAL * 50)) {
            refreshLevelCache(level);
            lastCacheUpdate = currentTime;
        }

        List<CustomEntity> resolvedNPCs = new ArrayList<>();
        collectAliveNPCs(NPC_CACHE.get(levelKey), resolvedNPCs);
        return resolvedNPCs;
    }

    /**
     * 清除NPC缓存（在NPC被添加或移除时调用）
     */
    public static void invalidateCache() {
        NPC_CACHE.clear();
        lastCacheUpdate = 0;
    }

    private static void refreshLevelCache(ServerLevel level) {
        List<WeakReference<CustomEntity>> levelNPCs = new ArrayList<>();
        level.getEntities().getAll().forEach(entity -> {
            if (entity instanceof CustomEntity npc && npc.isAlive()) {
                levelNPCs.add(new WeakReference<>(npc));
            }
        });
        NPC_CACHE.put(level.dimension(), new CopyOnWriteArrayList<>(levelNPCs));
    }

    private static void collectAliveNPCs(CopyOnWriteArrayList<WeakReference<CustomEntity>> cachedNPCs, List<CustomEntity> output) {
        if (cachedNPCs == null) {
            return;
        }
        cachedNPCs.removeIf(reference -> {
            CustomEntity npc = reference.get();
            return npc == null || !npc.isAlive() || npc.level().isClientSide();
        });
        for (WeakReference<CustomEntity> reference : cachedNPCs) {
            CustomEntity npc = reference.get();
            if (npc != null && npc.isAlive()) {
                output.add(npc);
            }
        }
    }

    /**
     * 检查调度器是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取待处理任务数量
     */
    public static int getPendingTaskCount() {
        return pendingTaskCount.get();
    }

    private static ThreadFactory createNamedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread thread = new Thread(r, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
