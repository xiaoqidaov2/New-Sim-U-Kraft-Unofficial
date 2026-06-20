package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

@Mod.EventBusSubscriber(modid = "simukraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class NPCTaskScheduler {
    private static final long SHUTDOWN_WAIT_MILLIS = 500L;
    private static final Logger LOGGER = LogManager.getLogger();
    
    private static final int MIN_MAIN_THREAD_TASKS_PER_TICK = 50;
    private static final int MAX_MAIN_THREAD_TASKS_PER_TICK = 200;
    private static final int DEFAULT_MAIN_THREAD_TASKS_PER_TICK = 100;
    
    private static final int CORE_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final int TASK_BATCH_SIZE = 50;
    private static final int MAX_PENDING_TASKS = 1000;
    private static final int MAX_MAIN_THREAD_QUEUE_SIZE = 500;

    private static ExecutorService taskExecutor;

    private static final AtomicInteger pendingTaskCount = new AtomicInteger(0);
    private static final AtomicInteger completedTaskCount = new AtomicInteger(0);
    private static final AtomicInteger rejectedTaskCount = new AtomicInteger(0);

    private static final PriorityBlockingQueue<PriorityRunnable> mainThreadTasks = new PriorityBlockingQueue<>();

    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();

    private static long lastStatsTime = 0;
    private static final long STATS_INTERVAL_MS = 30000;

    private static final Map<ResourceKey<Level>, CopyOnWriteArrayList<WeakNpcRef>> NPC_CACHE = new ConcurrentHashMap<>();
    
    private static final ReferenceQueue<CustomEntity> REFERENCE_QUEUE = new ReferenceQueue<>();
    
    private static final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    
    private static final AtomicLong lastCacheUpdate = new AtomicLong(0);
    private static final long CACHE_UPDATE_INTERVAL = 100;
    
    private static volatile boolean fullRefreshNeeded = true;
    
    private static final AtomicInteger cacheHits = new AtomicInteger(0);
    private static final AtomicInteger cacheMisses = new AtomicInteger(0);
    private static final AtomicInteger incrementalUpdates = new AtomicInteger(0);
    private static final AtomicInteger fullUpdates = new AtomicInteger(0);
    
    private static volatile int currentMainThreadTaskLimit = DEFAULT_MAIN_THREAD_TASKS_PER_TICK;
    private static long lastTpsCheckTime = 0;
    private static double currentTps = 20.0;
    private static final AtomicInteger queueOverflowWarnings = new AtomicInteger(0);

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

    public static void shutdown() {
        synchronized (initLock) {
            if (!initialized) return;

            LOGGER.info("正在关闭NPC任务调度器...");

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

            mainThreadTasks.clear();
            pendingTaskCount.set(0);
            invalidateCache();

            initialized = false;
            LOGGER.info("NPC任务调度器已关闭");
        }
    }

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
                enqueueMainThreadTask(() -> mainThreadCallback.accept(finalResult), TaskPriority.NORMAL, taskName + "-Callback");
            }
        }, taskName);
    }

    public static void enqueueMainThreadTask(Runnable mainThreadTask, String taskName) {
        enqueueMainThreadTask(mainThreadTask, TaskPriority.NORMAL, taskName);
    }

    public static void enqueueMainThreadTask(Runnable mainThreadTask, TaskPriority priority, String taskName) {
        if (mainThreadTask == null) {
            return;
        }
        if (!initialized) {
            initialize();
        }
        
        int currentQueueSize = mainThreadTasks.size();
        if (currentQueueSize >= MAX_MAIN_THREAD_QUEUE_SIZE) {
            if (queueOverflowWarnings.incrementAndGet() % 50 == 0) {
                LOGGER.warn("主线程任务队列已满({}个任务)，新任务 {} 将被丢弃！", currentQueueSize, taskName);
            }
            return;
        }
        
        if (taskExecutor == null || taskExecutor.isShutdown()) {
            LOGGER.warn("主线程任务队列不可用，任务 {} 将立即执行", taskName);
            mainThreadTask.run();
            return;
        }
        
        mainThreadTasks.offer(new PriorityRunnable(mainThreadTask, priority, taskName));
    }

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
        enqueueMainThreadTask(mainThreadTask, TaskPriority.HIGH, taskName);
    }

    public static void submitNPCTasks(List<CustomEntity> npcs, Consumer<CustomEntity> taskProcessor, String taskName) {
        if (npcs == null || npcs.isEmpty()) return;

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

    private static <T> List<List<T>> createBatches(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(new ArrayList<>(items.subList(i, Math.min(i + batchSize, items.size()))));
        }
        return batches;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!initialized) {
            initialize();
        }

        updateTpsEstimate(event.getServer());
        
        int dynamicLimit = calculateDynamicTaskLimit();
        int processedCount = drainMainThreadTasks(dynamicLimit);

        int remainingTasks = mainThreadTasks.size();
        if (remainingTasks > MAX_MAIN_THREAD_TASKS_PER_TICK) {
            LOGGER.warn("⚠️ 主线程任务队列严重积压: {} 个任务 (限制: {})", remainingTasks, dynamicLimit);
        } else if (remainingTasks > DEFAULT_MAIN_THREAD_TASKS_PER_TICK) {
            LOGGER.debug("主线程任务队列轻度积压: {} 个任务", remainingTasks);
        }

        cleanCollectedReferences();

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastStatsTime > STATS_INTERVAL_MS) {
            outputStats(processedCount);
            lastStatsTime = currentTime;
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof CustomEntity npc && !event.getLevel().isClientSide()) {
            addNpcToCache(npc, event.getLevel().dimension());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof CustomEntity npc && !event.getLevel().isClientSide()) {
            removeNpcFromCache(npc, event.getLevel().dimension());
        }
    }

    private static void updateTpsEstimate(MinecraftServer server) {
        if (server == null) return;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTpsCheckTime >= 5000) {
            currentTps = 20.0;
            lastTpsCheckTime = currentTime;
        }
    }

    private static int calculateDynamicTaskLimit() {
        if (currentTps >= 19.0) {
            currentMainThreadTaskLimit = MAX_MAIN_THREAD_TASKS_PER_TICK;
        } else if (currentTps >= 17.0) {
            currentMainThreadTaskLimit = DEFAULT_MAIN_THREAD_TASKS_PER_TICK;
        } else if (currentTps >= 15.0) {
            currentMainThreadTaskLimit = MIN_MAIN_THREAD_TASKS_PER_TICK;
        } else {
            currentMainThreadTaskLimit = Math.max(20, MIN_MAIN_THREAD_TASKS_PER_TICK / 2);
        }
        
        int queueSize = mainThreadTasks.size();
        if (queueSize > 300) {
            currentMainThreadTaskLimit = Math.min(currentMainThreadTaskLimit + 50, MAX_MAIN_THREAD_TASKS_PER_TICK);
        }
        
        return currentMainThreadTaskLimit;
    }

    private static void addNpcToCache(CustomEntity npc, ResourceKey<Level> levelKey) {
        if (npc == null || levelKey == null) return;
        
        cacheLock.writeLock().lock();
        try {
            CopyOnWriteArrayList<WeakNpcRef> levelCache = NPC_CACHE.computeIfAbsent(levelKey, k -> new CopyOnWriteArrayList<>());
            
            WeakNpcRef newRef = new WeakNpcRef(npc, REFERENCE_QUEUE);
            levelCache.add(newRef);
            
            incrementalUpdates.incrementAndGet();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private static void removeNpcFromCache(CustomEntity npc, ResourceKey<Level> levelKey) {
        if (npc == null || levelKey == null) return;
        
        cacheLock.writeLock().lock();
        try {
            CopyOnWriteArrayList<WeakNpcRef> levelCache = NPC_CACHE.get(levelKey);
            if (levelCache == null) return;
            
            levelCache.removeIf(ref -> {
                CustomEntity cached = ref.get();
                return cached == null || cached == npc || !cached.isAlive();
            });
            
            incrementalUpdates.incrementAndGet();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private static void cleanCollectedReferences() {
        Reference<? extends CustomEntity> ref;
        while ((ref = REFERENCE_QUEUE.poll()) != null) {
            if (ref instanceof WeakNpcRef weakRef) {
                ResourceKey<Level> levelKey = weakRef.getLevelKey();
                if (levelKey != null) {
                    CopyOnWriteArrayList<WeakNpcRef> levelCache = NPC_CACHE.get(levelKey);
                    if (levelCache != null) {
                        levelCache.remove(weakRef);
                    }
                }
            }
        }
    }

    private static int drainMainThreadTasks(int maxTasks) {
        int processedCount = 0;
        PriorityRunnable task;
        while (processedCount < maxTasks && (task = mainThreadTasks.poll()) != null) {
            try {
                task.run();
                processedCount++;
            } catch (Exception e) {
                LOGGER.error("执行主线程任务 [{}] 时发生错误", task.taskName, e);
            }
        }
        return processedCount;
    }

    private static void outputStats(int processedMainThreadTasks) {
        int pending = pendingTaskCount.get();
        int completed = completedTaskCount.getAndSet(0);
        int rejected = rejectedTaskCount.getAndSet(0);
        int queuedMainThreadTasks = mainThreadTasks.size();
        int hits = cacheHits.getAndSet(0);
        int misses = cacheMisses.getAndSet(0);
        int incrUpdates = incrementalUpdates.getAndSet(0);
        int fullUpd = fullUpdates.getAndSet(0);
        int overflowWarnings = queueOverflowWarnings.getAndSet(0);

        String tpsStatus = currentTps >= 19.0 ? "§a良好" : (currentTps >= 17.0 ? "§e一般" : "§c较差");
        
        LOGGER.info("NPC调度器统计 - TPS:{}({:.1f}), 待处理:{}, 已完成:{}, 拒绝:{}, " +
                   "主线程执行:{}, 主线程排队:{}/{}, 缓存命中:{}, 缺失:{}, " +
                   "增量更新:{}, 全量更新:{}, 溢出警告:{}",
                   tpsStatus, currentTps, pending, completed, rejected, 
                   processedMainThreadTasks, queuedMainThreadTasks, currentMainThreadTaskLimit,
                   hits, misses, incrUpdates, fullUpd, overflowWarnings);
    }

    public static List<CustomEntity> getAllNPCs(MinecraftServer server) {
        List<CustomEntity> allNPCs = new ArrayList<>();

        if (server == null) return allNPCs;

        cacheLock.readLock().lock();
        try {
            long currentTime = System.currentTimeMillis();
            long lastUpdate = lastCacheUpdate.get();
            boolean needFullRefresh = fullRefreshNeeded || 
                                     NPC_CACHE.isEmpty() || 
                                     (currentTime - lastUpdate > CACHE_UPDATE_INTERVAL * 50);

            if (needFullRefresh) {
                cacheLock.readLock().unlock();
                cacheLock.writeLock().lock();
                try {
                    if (fullRefreshNeeded || NPC_CACHE.isEmpty() || 
                        (currentTime - lastCacheUpdate.get() > CACHE_UPDATE_INTERVAL * 50)) {
                        fullRefresh(server);
                        fullUpdates.incrementAndGet();
                        fullRefreshNeeded = false;
                    } else {
                        cacheHits.incrementAndGet();
                    }
                } finally {
                    cacheLock.writeLock().unlock();
                    cacheLock.readLock().lock();
                }
            } else {
                cacheHits.incrementAndGet();
            }

            for (CopyOnWriteArrayList<WeakNpcRef> levelNPCs : NPC_CACHE.values()) {
                collectAliveNPCs(levelNPCs, allNPCs);
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        return allNPCs;
    }

    public static List<CustomEntity> getNPCsInLevel(ServerLevel level) {
        if (level == null) return new ArrayList<>();

        ResourceKey<Level> levelKey = level.dimension();
        List<CustomEntity> resolvedNPCs = new ArrayList<>();

        cacheLock.readLock().lock();
        try {
            long currentTime = System.currentTimeMillis();
            long lastUpdate = lastCacheUpdate.get();
            CopyOnWriteArrayList<WeakNpcRef> cachedNPCs = NPC_CACHE.get(levelKey);

            boolean needRefresh = cachedNPCs == null || 
                                 fullRefreshNeeded || 
                                 (currentTime - lastUpdate > CACHE_UPDATE_INTERVAL * 50);

            if (needRefresh) {
                cacheLock.readLock().unlock();
                cacheLock.writeLock().lock();
                try {
                    cachedNPCs = NPC_CACHE.get(levelKey);
                    if (cachedNPCs == null || fullRefreshNeeded || 
                        (currentTime - lastCacheUpdate.get() > CACHE_UPDATE_INTERVAL * 50)) {
                        refreshLevelCache(level);
                        fullUpdates.incrementAndGet();
                        fullRefreshNeeded = false;
                    } else {
                        cacheHits.incrementAndGet();
                    }
                } finally {
                    cacheLock.writeLock().unlock();
                    cacheLock.readLock().lock();
                    cachedNPCs = NPC_CACHE.get(levelKey);
                }
            } else {
                cacheHits.incrementAndGet();
            }

            collectAliveNPCs(cachedNPCs, resolvedNPCs);
        } finally {
            cacheLock.readLock().unlock();
        }

        return resolvedNPCs;
    }

    public static void invalidateCache() {
        cacheLock.writeLock().lock();
        try {
            NPC_CACHE.clear();
            lastCacheUpdate.set(0);
            fullRefreshNeeded = true;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private static void fullRefresh(MinecraftServer server) {
        NPC_CACHE.clear();
        for (ServerLevel level : server.getAllLevels()) {
            refreshLevelCacheInternal(level);
        }
        lastCacheUpdate.set(System.currentTimeMillis());
    }

    private static void refreshLevelCache(ServerLevel level) {
        if (level == null) return;
        refreshLevelCacheInternal(level);
        lastCacheUpdate.set(System.currentTimeMillis());
    }

    private static void refreshLevelCacheInternal(ServerLevel level) {
        ResourceKey<Level> levelKey = level.dimension();
        List<WeakNpcRef> levelNPCs = new ArrayList<>();
        
        level.getEntities().getAll().forEach(entity -> {
            if (entity instanceof CustomEntity npc && npc.isAlive()) {
                levelNPCs.add(new WeakNpcRef(npc, REFERENCE_QUEUE, levelKey));
            }
        });
        
        NPC_CACHE.put(levelKey, new CopyOnWriteArrayList<>(levelNPCs));
    }

    private static void collectAliveNPCs(CopyOnWriteArrayList<WeakNpcRef> cachedNPCs, List<CustomEntity> output) {
        if (cachedNPCs == null) {
            return;
        }
        
        cachedNPCs.removeIf(reference -> {
            CustomEntity npc = reference.get();
            return npc == null || !npc.isAlive() || npc.level().isClientSide();
        });
        
        for (WeakNpcRef reference : cachedNPCs) {
            CustomEntity npc = reference.get();
            if (npc != null && npc.isAlive()) {
                output.add(npc);
            }
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static int getPendingTaskCount() {
        return pendingTaskCount.get();
    }

    public static int getMainThreadQueueSize() {
        return mainThreadTasks.size();
    }

    public static double getCurrentTps() {
        return currentTps;
    }

    private static ThreadFactory createNamedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread thread = new Thread(r, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public enum TaskPriority {
        HIGH(0),
        NORMAL(1),
        LOW(2);

        private final int value;

        TaskPriority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {
        private final Runnable task;
        private final TaskPriority priority;
        private final String taskName;
        private final long submitTime;

        public PriorityRunnable(Runnable task, TaskPriority priority, String taskName) {
            this.task = task;
            this.priority = priority;
            this.taskName = taskName;
            this.submitTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(PriorityRunnable other) {
            int priorityCompare = Integer.compare(this.priority.getValue(), other.priority.getValue());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(this.submitTime, other.submitTime);
        }
    }

    private static class WeakNpcRef extends WeakReference<CustomEntity> {
        private final ResourceKey<Level> levelKey;

        public WeakNpcRef(CustomEntity referent, ReferenceQueue<? super CustomEntity> queue) {
            super(referent, queue);
            this.levelKey = referent.level().dimension();
        }

        public WeakNpcRef(CustomEntity referent, ReferenceQueue<? super CustomEntity> queue, ResourceKey<Level> levelKey) {
            super(referent, queue);
            this.levelKey = levelKey;
        }

        public ResourceKey<Level> getLevelKey() {
            return levelKey;
        }
    }
}
