package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.notification.NotificationServiceManager;
import com.xiaoliang.simukraft.utils.*;
import com.xiaoliang.simukraft.world.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID)
@SuppressWarnings({"null", "unused"})
public class WorldEvents {
    private static final long HUD_SYNC_INTERVAL_TICKS = 20L;
    private static long lastRecordedDay = -1;
    private static long lastRecordedTimeOfDay = -1;

    // 存储需要延迟播放收钱音效的玩家和时间戳
    private static final Map<UUID, Long> pendingMoneySounds = new ConcurrentHashMap<>();

    // 新增：记录今天是否已经收过租，避免重复收租
    private static long lastRentCollectionDay = -1;
    // 按城市记录当天已结算收入，避免“同城多官员重复入账”
    private static long lastCityIncomeCollectionDay = -1;
    private static final Map<UUID, CityIncome> collectedIncomeByCity = new ConcurrentHashMap<>();

    // 新增：NPC年龄增长计数器，每7个游戏日增长1岁
    private static int ageIncreaseCounter = 0;
    private static final int AGE_INCREASE_INTERVAL = 7; // 7个游戏日增长1岁

    @SubscribeEvent
    public static void onWorldTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (event.level.isClientSide()) return;

        ServerLevel serverLevel = (ServerLevel) event.level;
        if (serverLevel.dimension() != Level.OVERWORLD) return;

        // 物流传输引擎
        com.xiaoliang.simukraft.job.jobs.warehousemanager.LogisticsWorkHandler.onServerTick(serverLevel);

        // 新职业运行时
        com.xiaoliang.simukraft.job.core.JobRuntimeService.get().tick(serverLevel);

        // simukraft: 处理NPC午休逻辑
        com.xiaoliang.simukraft.utils.LunchBreakManager.handleLunchBreak(serverLevel);

        // 处理延迟的收钱音效和房租收集
        processPendingMoneySounds(serverLevel);

        // 修复：延迟恢复建造盒雇佣状态，使用 BuilderWorkService 持续尝试恢复
        if (buildBoxRestoreScheduled) {
            buildBoxRestoreTickCounter++;
            // 每100tick（5秒）尝试恢复一次，持续5分钟
            if (buildBoxRestoreTickCounter % 100 == 0) {
                com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.startDailyWork(serverLevel);
                // 同时恢复仓库管理员工作状态
                com.xiaoliang.simukraft.job.jobs.warehousemanager.WarehouseManagerWorkService.INSTANCE.startDailyWork(serverLevel);
                // 同时恢复规划师工作状态
                com.xiaoliang.simukraft.job.jobs.planner.PlannerWorkService.INSTANCE.startDailyWork(serverLevel);
            }
            // 5分钟后停止尝试
            if (buildBoxRestoreTickCounter > 6000) {
                buildBoxRestoreScheduled = false;
                Simukraft.LOGGER.info("[WorldEvents] 建造盒雇佣状态恢复完成（超时）");
            }
        }

        // 使用游戏日期来判断新的一天
        long currentDay = serverLevel.getDayTime() / 24000L;

        // 只在游戏日期真正变化时增加一天
        if (lastRecordedDay != -1 && currentDay > lastRecordedDay) {
            incrementDay(serverLevel);
        }

        // 更新记录的游戏日期
        lastRecordedDay = currentDay;

        // 检测从夜晚到早晨的过渡（自然等到早晨）
        detectMorningTransition(serverLevel);

        // HUD 采用周期保底同步，避免每 tick 重复发包和重复解析城市快照
        if (serverLevel.getGameTime() % HUD_SYNC_INTERVAL_TICKS == 0L) {
            syncHUDDataToAllPlayers(serverLevel);
        }
    }

    /**
     * 处理延迟的收钱音效和房租收集
     */
    private static void processPendingMoneySounds(ServerLevel level) {
        long currentTime = System.currentTimeMillis();
        pendingMoneySounds.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            long triggerTime = entry.getValue();

            if (currentTime >= triggerTime) {
                // 播放收钱音效并收集房租
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
                if (player != null && player.level() == level) {
                    // 播放收钱音效
                    level.playSound(
                        null,
                        player.blockPosition(),
                        ModSoundEvents.MONEY_COLLECT.get(),
                        SoundSource.PLAYERS,
                        1.0F,
                        1.0F
                    );

                    // 收集房租
                    collectRent(player);

                    if (Simukraft.LOGGER.isDebugEnabled()) {
                        Simukraft.LOGGER.debug("收钱音效触发给玩家: {}", player.getScoreboardName());
                    }
                }
                return true; // 移除已处理的条目
            }
            return false; // 保留未到时间的条目
        });
    }

    /**
     * 收集房租和企业税收：基于城市分别计算（支持多人游戏）
     * 每个玩家只收取自己城市的税收
     */
    private static void collectRent(ServerPlayer player) {
        try {
            Path worldDir = player.serverLevel().getServer().getWorldPath(LevelResource.ROOT);
            Path simukraftDir = worldDir.resolve("simukraft");
            Path residenceDir = simukraftDir.resolve("residence");
            Path commercialDir = simukraftDir.resolve("commercial");

            CityData cityData = CityData.get(player.serverLevel());
            String playerName = player.getName().getString();
            UUID playerCityId = cityData.getPlayerCityId(playerName);

            if (playerCityId == null) {
                sendRentMessage(player, 0, 0, 0);
                if (Simukraft.LOGGER.isDebugEnabled()) {
                    Simukraft.LOGGER.debug("玩家 {} 没有城市，不收取税收", player.getScoreboardName());
                }
                return;
            }

            String cityIdStr = playerCityId.toString();
            if (Simukraft.LOGGER.isDebugEnabled()) {
                Simukraft.LOGGER.debug("检查玩家 {} 的城市 {} 的税收...", player.getScoreboardName(), cityIdStr.substring(0, 8));
            }

            long currentDay = player.serverLevel().getDayTime() / 24000L;
            if (lastCityIncomeCollectionDay != currentDay) {
                collectedIncomeByCity.clear();
                lastCityIncomeCollectionDay = currentDay;
            }

            CityIncome income = collectedIncomeByCity.get(playerCityId);
            if (income == null) {
                income = collectCityIncome(player.serverLevel(), residenceDir, commercialDir, cityIdStr, playerCityId);
                collectedIncomeByCity.put(playerCityId, income);
                if (income.totalAmount() > 0) {
                    MoneyManager.addMoney(player, income.totalAmount());
                }
            }

            sendRentMessage(player, income.rentAmount(), income.taxAmount(), income.totalAmount());
            if (Simukraft.LOGGER.isDebugEnabled()) {
                Simukraft.LOGGER.debug("收集收入 (玩家: {}): 房租{}个文件({}元) + 企业税{}个文件({}元) = 共{}元",
                        player.getScoreboardName(), income.rentFileCount(), income.rentAmount(),
                        income.taxFileCount(), income.taxAmount(), income.totalAmount());
            }
        } catch (IOException e) {
            Simukraft.LOGGER.error("收集income时出错: {}", e.getMessage());
            sendRentMessage(player, 0, 0, 0);
        }
    }

    /**
     * 发送收入收集消息给玩家（通过通知接口）
     * 只发送给当前玩家（如果玩家是市长或官员）
     */
    private static void sendRentMessage(ServerPlayer player, double rentAmount, double taxAmount, double totalAmount) {
        String formattedRent = String.format(Locale.US, "%.2f", rentAmount);
        String formattedTax = String.format(Locale.US, "%.2f", taxAmount);
        String formattedTotal = String.format(Locale.US, "%.2f", totalAmount);
        Component contentComp = Component.translatable("message.simukraft.daily_income.summary", formattedRent, formattedTax, formattedTotal);
        Component titleComp = Component.translatable("notify.title.income");

        CityData cityData = CityData.get(player.serverLevel());
        String playerName = player.getName().getString();
        UUID playerCityId = cityData.getPlayerCityId(playerName);
        
        if (playerCityId != null) {
            CityData.CityInfo cityInfo = cityData.getCity(playerCityId);
            if (cityInfo != null) {
                MinecraftServer server = player.getServer();
                
                // 检查玩家是否是市长或官员
                boolean isMayor = playerName.equals(cityInfo.getMayorName());
                boolean isOfficial = cityInfo.isOfficial(playerName);
                
                // 市长通过通知服务发送消息，官员直接发送消息给自己
                if (isMayor) {
                    com.xiaoliang.simukraft.utils.CityMessageUtils.sendToMayorViaService(
                            server, playerCityId, titleComp, contentComp,
                            com.xiaoliang.simukraft.notification.MessageCategory.FINANCE);
                } else {
                    // 官员直接发送消息给自己
                    player.sendSystemMessage(contentComp);
                }
            } else {
                player.sendSystemMessage(contentComp);
            }
        } else {
            player.sendSystemMessage(contentComp);
        }
    }

    /**
     * 定期同步HUD数据给所有玩家
     */
    private static void syncHUDDataToAllPlayers(ServerLevel level) {
        SimukraftWorldData worldData = SimukraftWorldData.get(level);
        int currentDay = worldData.getCurrentDay();

        PopulationData populationData = PopulationData.get(level);
        int worldPopulation = populationData.getPopulation();

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            PlayerCitySnapshot citySnapshot = resolvePlayerCitySnapshot(level, player);
            NetworkManager.sendHUDDataToPlayer(currentDay, worldPopulation, citySnapshot.cityName(), citySnapshot.cityFunds(), citySnapshot.cityPopulation(), player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            pendingMoneySounds.remove(serverPlayer.getUUID());
            NetworkManager.clearHUDSyncState(serverPlayer.getUUID());
        }
    }

    private static void incrementDay(ServerLevel level) {
        SimukraftWorldData data = SimukraftWorldData.get(level);
        data.incrementDay();

        // 同步HUD数据给所有玩家
        syncHUDDataToAllPlayers(level);

        // 处理农民每日经验值
        com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService.INSTANCE.handleDailyXp(level);

        // 处理NPC年龄增长（每7个游戏日增加1岁）
        handleNPCAgeIncrease(level);
    }

    /**
     * 处理所有NPC的年龄增长
     * 每7个游戏日增加1岁，并检查是否达到寿命上限
     */
    private static void handleNPCAgeIncrease(ServerLevel level) {
        // 增加计数器
        ageIncreaseCounter++;

        // 只有达到间隔天数时才增加年龄
        if (ageIncreaseCounter < AGE_INCREASE_INTERVAL) {
            Simukraft.LOGGER.debug("[NPC年龄] 年龄增长计数: {}/{} 天", ageIncreaseCounter, AGE_INCREASE_INTERVAL);
            return;
        }

        // 重置计数器
        ageIncreaseCounter = 0;

        // 遍历所有NPC实体并增加年龄
        level.getAllEntities().forEach(entity -> {
            if (entity instanceof com.xiaoliang.simukraft.entity.CustomEntity npc) {
                // 增加年龄
                int currentAge = npc.getNpcAge();
                int newAge = currentAge + 1;
                npc.setNpcAge(newAge);

                // 更新NPC数据到文件
                NPCDataManager.updateNPCAge(level.getServer(), npc.getUUID(), newAge);

                Simukraft.LOGGER.info("[NPC年龄] {} 年龄增长至 {} 岁", npc.getFullName(), newAge);

                // 检查是否达到寿命上限
                if (npc.isAtEndOfLife()) {
                    Simukraft.LOGGER.info("[NPC寿命] {} 已达到寿命上限（{}岁），即将去世",
                            npc.getFullName(), npc.getLifespan());
                }
            }
        });
    }

    /**
     * 检测从夜晚到早晨的过渡
     * 当时间从夜晚（13000-23999）过渡到早晨（0-12000）时触发音效和收租
     * 新增每日强制收租机制，确保每天至少收租一次
     */
    private static void detectMorningTransition(ServerLevel level) {
        long currentTimeOfDay = level.getDayTime() % 24000L;
        long currentDay = level.getDayTime() / 24000L;

        // 如果是第一次记录，只记录不触发
        if (lastRecordedTimeOfDay == -1) {
            lastRecordedTimeOfDay = currentTimeOfDay;
            return;
        }

        // 既支持“夜里直接睡到天亮”的时间跳变，也支持自然走到清晨时的每日结算。
        boolean crossedNightToMorning = lastRecordedTimeOfDay >= 13000
                && currentTimeOfDay <= 12000
                && lastRecordedTimeOfDay != currentTimeOfDay;
        boolean isEarlyMorningWindow = currentTimeOfDay >= 0 && currentTimeOfDay <= 200;

        // 只要还是新的游戏日，并且已经进入清晨窗口或发生了跨夜跳变，就触发一次。
        boolean shouldTriggerRent = currentDay > lastRentCollectionDay
                && (crossedNightToMorning || isEarlyMorningWindow);

        // 新增：每日一次限制，避免重复触发
        if (shouldTriggerRent) {
            // 检查今天是否已经触发过
            if (lastRentCollectionDay != currentDay) {
                // 只触发早晨音效，不收租
                playMorningSound(level);
                Simukraft.LOGGER.debug("[WorldEvents] 早晨音效触发：time {} -> {}，day={}", lastRecordedTimeOfDay, currentTimeOfDay, currentDay);

                // 记录今天已经触发过
                lastRentCollectionDay = currentDay;
            }
        }

        // 更新记录的时间
        lastRecordedTimeOfDay = currentTimeOfDay;
    }

    /**
     * 播放早晨音效给所有在线玩家
     * 先播放鸡鸣叫音效，然后延迟播放收租音效
     */
    private static void playMorningSound(ServerLevel level) {
        // 给所有在线玩家播放鸡鸣叫音效
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level) {
                // 先播放鸡鸣叫音效
                level.playSound(
                    null,
                    player.blockPosition(),
                    ModSoundEvents.PLAYER_WAKE_UP.get(),
                    SoundSource.PLAYERS,
                    1.0F,
                    1.0F
                );
                Simukraft.LOGGER.debug("[WorldEvents] 播放鸡鸣叫音效给玩家: {}", player.getScoreboardName());

                // 延迟3秒（60游戏刻）后播放收租音效
                scheduleDelayedMoneySound(player.getUUID(), 3000); // 3000毫秒 = 3秒
            }
        }

        // 恢复仓库管理员工作状态（每天早上6点）
        com.xiaoliang.simukraft.job.jobs.warehousemanager.WarehouseManagerWorkService.INSTANCE.startDailyWork(level);
    }

    // 注意：早晨音效和税收已经通过 detectMorningTransition 方法基于时间检测触发
    // 不再使用 PlayerWakeUpEvent，确保即使玩家不睡觉也能正常触发

    /**
     * 安排延迟的收钱音效播放（用于早晨收租）
     */
    private static void scheduleDelayedMoneySound(UUID playerId, long delayMs) {
        long triggerTime = System.currentTimeMillis() + delayMs;
        pendingMoneySounds.put(playerId, triggerTime);
    }

    @SubscribeEvent
    public static void onWorldLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        lastRecordedDay = -1;
        lastRecordedTimeOfDay = -1;
        lastRentCollectionDay = -1;
        lastCityIncomeCollectionDay = -1;
        collectedIncomeByCity.clear();

        if (event.getLevel() instanceof ServerLevel serverLevel) {
            if (serverLevel.dimension() == Level.OVERWORLD) {
                // 以当前游戏日作为基准，避免服务器刚启动时在白天立刻补发一次房租。
                lastRentCollectionDay = serverLevel.getDayTime() / 24000L;
            }
            // 初始化职业运行时状态存储
            com.xiaoliang.simukraft.job.core.JobRuntimeService.get().onLevelLoad(serverLevel);

            // 初始化城市区块数据，确保数据文件被生成
            com.xiaoliang.simukraft.world.CityChunkData.get(serverLevel);

            // 初始化工业建筑配置管理器（如果还没有初始化）
            Simukraft.LOGGER.debug("[WorldEvents] onWorldLoad - 准备初始化 IndustrialBuildingManager");
            com.xiaoliang.simukraft.building.IndustrialBuildingManager.init(serverLevel.getServer());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level().isClientSide()) return;

        ServerPlayer serverPlayer = (ServerPlayer) event.getEntity();
        ServerLevel serverLevel = serverPlayer.serverLevel();

        SimukraftWorldData worldData = SimukraftWorldData.get(serverLevel);
        int currentDay = worldData.getCurrentDay();

        PopulationData populationData = PopulationData.get(serverLevel);
        int worldPopulation = populationData.getPopulation();

        PlayerCitySnapshot citySnapshot = resolvePlayerCitySnapshot(serverLevel, serverPlayer);
        NetworkManager.sendHUDDataToPlayer(currentDay, worldPopulation, citySnapshot.cityName(), citySnapshot.cityFunds(), citySnapshot.cityPopulation(), serverPlayer);
    }

    private static boolean firstLoad = true;

    private static long lastProcessedDay = -1;
    private static long lastGameTime = -1;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 在服务器启动后首次tick加载数据
        if (firstLoad && event.getServer() != null) {
            // 服务器端不需要加载客户端GUI数据
            firstLoad = false;
        }

        // 处理工业控制箱每日工作（统一处理牧羊人和屠夫）
        if (event.getServer() != null) {
            // 修复：执行延迟的工商业雇佣状态恢复
            executeCommercialIndustrialHireStatusRestore(event.getServer());

            for (ServerLevel level : event.getServer().getAllLevels()) {
                com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.handleDailyWork(level);
                // 处理商业建筑每日工作
                com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.handleDailyWork(level);
            }

            // 每天执行一次NPC原材料收集任务
            // 修复：正确处理时间跳跃（如/time set指令）
            long gameTime = event.getServer().overworld().getDayTime();
            long currentDay = gameTime / 24000;
            
            // 检测时间是否前进（包括正常流逝和时间跳跃）
            boolean shouldProcess = false;
            if (lastGameTime < 0) {
                // 首次运行
                shouldProcess = true;
            } else if (gameTime < lastGameTime) {
                // 时间被调回（如/time set指令），立即执行
                shouldProcess = true;
            } else if (currentDay > lastProcessedDay) {
                // 正常进入新的一天
                shouldProcess = true;
            }
            
            if (shouldProcess) {
                lastProcessedDay = currentDay;
                // 生产链路功能已移除，原料改为从附近箱子获取
            }
            lastGameTime = gameTime;
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        Simukraft.LOGGER.info("[WorldEvents] onServerStarting");
        NotificationServiceManager.bindServer(event.getServer());

        // 服务器启动时初始化SK文件缓存
        com.xiaoliang.simukraft.utils.FileUtils.initSkFileCache(event.getServer());

        // 服务器启动时初始化工业控制箱工作处理器（统一处理牧羊人和屠夫）
        com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.onServerStart(event.getServer());
        // 服务器启动时初始化商业建筑工作处理器
        com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler.onServerStart(event.getServer());

        // 服务器启动时加载动物生成数据
        IndustrialHiredData.loadAnimalGenerationData(event.getServer());

        // 服务器启动时加载农田盒数据
        FarmlandHiredData.loadAllFarmlandData(event.getServer());

        runEmploymentStartupMaintenance(event.getServer());

        // 服务器启动时初始化农民工作处理器（重要：确保农民状态被正确恢复）
        com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService.INSTANCE.onServerStart(event.getServer().overworld());

        // 服务器启动时加载NPC休息处理器的工作状态数据
        com.xiaoliang.simukraft.utils.NPCRestHandler.onServerStart(event.getServer());

        // simukraft: 服务器启动时加载已放置的建筑结构数据（支持一键拆除和NPC识别）
        com.xiaoliang.simukraft.building.PlacedBuildingManager.loadFromWorld(event.getServer());

        // 修复：延迟恢复建造盒雇佣状态，等待世界和实体完全加载
        // 使用 BuilderWorkService 在服务器启动后持续尝试恢复建筑师状态
        scheduleBuildBoxHiredStatusRestore();

        // 修复：延迟恢复工商业雇佣状态，等待世界和NPC实体完全加载
        scheduleCommercialIndustrialHireStatusRestore();

        com.xiaoliang.simukraft.integration.IntegrationBridge.onServerStarted(event.getServer());

        Simukraft.LOGGER.info("[WorldEvents] 工业/商业/农田/NPC 工作处理器和相关持久化数据已初始化");
    }

    // 修复：用于延迟恢复工商业雇佣状态的计数器
    private static int commercialIndustrialRestoreTickCounter = 0;
    private static boolean commercialIndustrialRestoreScheduled = false;
    private static Map<UUID, RestorationInfo> pendingCommercialRestorations = new HashMap<>();
    private static Map<UUID, RestorationInfo> pendingIndustrialRestorations = new HashMap<>();
    private static boolean commercialIndustrialRestorePlanLoading = false;
    private static boolean commercialIndustrialRestorePlanReady = false;

    /**
     * 恢复信息内部类，保存NPC恢复所需的信息
     */
    private static class RestorationInfo {
        final String jobType;
        final String buildingFileName;
        final BlockPos workplacePos;

        RestorationInfo(String jobType, String buildingFileName, BlockPos workplacePos) {
            this.jobType = jobType;
            this.buildingFileName = buildingFileName;
            this.workplacePos = workplacePos;
        }
    }

    /**
     * 工商业恢复计划快照。
     * 后台线程只负责组装纯数据，主线程再整体替换引用，避免跨线程共享可变 Map。
     */
    private record RestorationPlan(Map<UUID, RestorationInfo> commercial,
                                   Map<UUID, RestorationInfo> industrial) {
    }

    @FunctionalInterface
    private interface RestorationExecutor {
        boolean resume(CustomEntity npc, ServerLevel level, RestorationInfo info);
    }

    /**
     * 修复：延迟恢复工商业雇佣状态，等待世界和NPC实体完全加载
     */
    private static void scheduleCommercialIndustrialHireStatusRestore() {
        commercialIndustrialRestoreTickCounter = 0;
        commercialIndustrialRestoreScheduled = true;
        pendingCommercialRestorations.clear();
        pendingIndustrialRestorations.clear();
        commercialIndustrialRestorePlanLoading = false;
        commercialIndustrialRestorePlanReady = false;
        Simukraft.LOGGER.debug("[WorldEvents] 已计划延迟恢复工商业雇佣状态");
    }

    /**
     * 执行工商业雇佣状态恢复
     * 在服务器tick中调用，持续尝试恢复直到所有NPC都被找到
     */
    private static void executeCommercialIndustrialHireStatusRestore(MinecraftServer server) {
        if (!commercialIndustrialRestoreScheduled) return;

        commercialIndustrialRestoreTickCounter++;

        // 首次执行时异步加载恢复计划，把纯数据读取从主线程剥离出来。
        if (commercialIndustrialRestoreTickCounter == 1 && !commercialIndustrialRestorePlanLoading && !commercialIndustrialRestorePlanReady) {
            commercialIndustrialRestorePlanLoading = true;
            NPCTaskScheduler.submitCallableWithMainThreadCallback(
                    () -> loadCommercialIndustrialRestorePlan(server),
                    plan -> {
                        applyCommercialIndustrialRestorePlan(plan);
                        commercialIndustrialRestorePlanLoading = false;
                        commercialIndustrialRestorePlanReady = true;
                        Simukraft.LOGGER.info("[WorldEvents] 已装载工商业恢复计划，商业 {} 个，工业 {} 个",
                                pendingCommercialRestorations.size(), pendingIndustrialRestorations.size());
                    },
                    "LoadCommercialIndustrialRestorePlan"
            );
        }

        if (!commercialIndustrialRestorePlanReady) {
            if (commercialIndustrialRestoreTickCounter >= 600) {
                commercialIndustrialRestoreScheduled = false;
                commercialIndustrialRestorePlanLoading = false;
                Simukraft.LOGGER.warn("[WorldEvents] 工商业雇佣状态恢复超时，恢复计划尚未准备完成");
            }
            return;
        }

        int commercialRestored = restorePendingAssignments(server, pendingCommercialRestorations, "商业",
                (npc, npcLevel, info) -> NPCWorkResumeCoordinator.resumeCommercialWork(npc, npcLevel, info.workplacePos, info.buildingFileName));
        int industrialRestored = restorePendingAssignments(server, pendingIndustrialRestorations, "工业",
                (npc, npcLevel, info) -> NPCWorkResumeCoordinator.resumeIndustrialWork(npc, npcLevel, info.workplacePos, info.buildingFileName));

        // 如果所有雇佣状态都已恢复，或者超过最大尝试次数，停止恢复
        if (pendingCommercialRestorations.isEmpty() && pendingIndustrialRestorations.isEmpty()) {
            commercialIndustrialRestoreScheduled = false;
            Simukraft.LOGGER.info("[WorldEvents] 所有工商业雇佣状态已恢复完成");
        } else if (commercialIndustrialRestoreTickCounter >= 600) { // 30秒后停止（600 ticks）
            commercialIndustrialRestoreScheduled = false;
            Simukraft.LOGGER.warn("[WorldEvents] 工商业雇佣状态恢复超时，仍有 {} 个商业建筑、{} 个工业建筑未恢复",
                pendingCommercialRestorations.size(), pendingIndustrialRestorations.size());
        }
    }

    private static RestorationPlan loadCommercialIndustrialRestorePlan(MinecraftServer server) {
        Map<UUID, RestorationInfo> loadedCommercialRestorations = new HashMap<>();
        Map<UUID, RestorationInfo> loadedIndustrialRestorations = new HashMap<>();

        var commercialEmployees = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
        for (var entry : commercialEmployees.entrySet()) {
            var hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid() != null) {
                loadedCommercialRestorations.put(hireInfo.getNpcUuid(),
                        new RestorationInfo(hireInfo.getJobType(), hireInfo.getBuildingFileName(), entry.getKey()));
            }
        }

        var industrialEmployees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
        for (var entry : industrialEmployees.entrySet()) {
            var hireInfo = entry.getValue();
            if (hireInfo != null && hireInfo.getNpcUuid() != null) {
                loadedIndustrialRestorations.put(hireInfo.getNpcUuid(),
                        new RestorationInfo(hireInfo.getJobType(), hireInfo.getBuildingFileName(), entry.getKey()));
            }
        }

        return new RestorationPlan(loadedCommercialRestorations, loadedIndustrialRestorations);
    }

    private static void applyCommercialIndustrialRestorePlan(RestorationPlan plan) {
        pendingCommercialRestorations = new HashMap<>(plan.commercial());
        pendingIndustrialRestorations = new HashMap<>(plan.industrial());
    }

    private static int restorePendingAssignments(MinecraftServer server,
                                                 Map<UUID, RestorationInfo> pendingRestorations,
                                                 String categoryName,
                                                 RestorationExecutor executor) {
        int restoredCount = 0;
        Iterator<Map.Entry<UUID, RestorationInfo>> iterator = pendingRestorations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RestorationInfo> entry = iterator.next();
            UUID npcUuid = entry.getKey();
            RestorationInfo info = entry.getValue();

            CustomEntity npc = BaseBuildingHiredData.findNPCByUuid(server, npcUuid);
            if (npc == null || !(npc.level() instanceof ServerLevel npcLevel)) {
                continue;
            }

            npc.setJob(info.jobType);
            if (!executor.resume(npc, npcLevel, info)) {
                continue;
            }

            Simukraft.LOGGER.debug("[WorldEvents] 恢复{}建筑雇佣状态: NPC={}, job={}", categoryName, npc.getFullName(), info.jobType);
            iterator.remove();
            restoredCount++;
        }
        return restoredCount;
    }

    /**
     * 从配置文件缓存读取held_item并设置NPC手持物品
     */
    private static void setupHeldItemFromConfig(CustomEntity npc, String buildingFileName, boolean isCommercial) {
        if (buildingFileName == null || buildingFileName.isEmpty()) {
            return;
        }

        try {
            String heldItemId = null;
            String buildingId = buildingFileName.replace(".sk", "").toLowerCase();

            if (isCommercial) {
                // 从商业建筑管理器缓存获取配置
                com.xiaoliang.simukraft.building.CommercialBuildingConfig config = 
                    com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(buildingId);
                if (config != null && config.getHeldItem() != null && !config.getHeldItem().isEmpty()) {
                    heldItemId = config.getHeldItem();
                }
            } else {
                // 从工业建筑管理器缓存获取配置
                com.xiaoliang.simukraft.building.IndustrialBuildingConfig config = 
                    com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfig(buildingId);
                if (config != null && config.getHeldItem() != null && !config.getHeldItem().isEmpty()) {
                    heldItemId = config.getHeldItem();
                }
            }

            if (heldItemId != null) {
                net.minecraft.resources.ResourceLocation itemId = net.minecraft.resources.ResourceLocation.tryParse(heldItemId);
                if (itemId != null) {
                    net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(itemId);
                    if (item != null && item != net.minecraft.world.item.Items.AIR) {
                        npc.setItemInHand(npc.getUsedItemHand(), new net.minecraft.world.item.ItemStack(item));
                        Simukraft.LOGGER.debug("[WorldEvents] 设置NPC手持物品: NPC={}, item={}", npc.getFullName(), heldItemId);
                    } else {
                        Simukraft.LOGGER.warn("[WorldEvents] 无法找到物品: {}", heldItemId);
                    }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[WorldEvents] 从配置文件设置手持物品时发生错误: {}", buildingFileName, e);
        }
    }

    // 修复：用于延迟恢复建造盒雇佣状态的计数器
    private static int buildBoxRestoreTickCounter = 0;
    private static boolean buildBoxRestoreScheduled = false;

    /**
     * 修复：延迟恢复建造盒雇佣状态，等待世界和NPC实体完全加载
     */
    private static void scheduleBuildBoxHiredStatusRestore() {
        buildBoxRestoreTickCounter = 0;
        buildBoxRestoreScheduled = true;
        Simukraft.LOGGER.debug("[WorldEvents] 已计划延迟恢复建造盒雇佣状态");
    }

    private static void runEmploymentStartupMaintenance(net.minecraft.server.MinecraftServer server) {
        // 职业系统已经统一到 v2 仓储，启动阶段不再执行旧版迁移和对账。
    }

    private static PlayerCitySnapshot resolvePlayerCitySnapshot(ServerLevel level, ServerPlayer player) {
        CityData cityData = CityData.get(level);
        String playerName = player.getName().getString();
        UUID cityId = cityData.getPlayerCityId(playerName);
        if (cityId == null) {
            return PlayerCitySnapshot.EMPTY;
        }

        CityData.CityInfo cityInfo = cityData.getCity(cityId);
        if (cityInfo == null) {
            return PlayerCitySnapshot.EMPTY;
        }

        return new PlayerCitySnapshot(cityInfo.getCityName(), cityInfo.getFunds(), cityInfo.getCitizenIds().size());
    }

    private static CityIncome collectCityIncome(ServerLevel level, Path residenceDir, Path commercialDir, String cityIdStr, UUID cityId) throws IOException {
        // 收集住宅租金（30%计入城市收入）
        List<Path> rentSkFiles = listCitySkFiles(residenceDir, cityIdStr);
        double rentTotal = 0.0;
        int rentFileCount = 0;
        for (Path skFile : rentSkFiles) {
            double rent = parseRent(skFile);
            if (rent >= 0) {
                rentTotal += rent;
                rentFileCount++;
            }
        }
        // 房租为住宅租金的30%
        double rentAmount = rentTotal * 0.3;

        // 收集商业建筑租金并计算40%作为企业税
        List<Path> commercialSkFiles = listCitySkFiles(commercialDir, cityIdStr);
        double commercialRentTotal = 0.0;
        int taxFileCount = 0;
        for (Path skFile : commercialSkFiles) {
            double rent = parseAmount(skFile);
            if (rent >= 0) {
                commercialRentTotal += rent;
                taxFileCount++;
            }
        }
        // 企业税为商业建筑租金的40% + NPC交易税
        double businessTaxFromRent = commercialRentTotal * 0.4;
        double npcTradeTax = com.xiaoliang.simukraft.utils.NPCFoodMarket.getAndClearNPCTradeTax(cityId);
        double taxAmount = businessTaxFromRent + npcTradeTax;

        if (!Files.exists(commercialDir) || !Files.isDirectory(commercialDir)) {
            Simukraft.LOGGER.debug("[WorldEvents] 商业建筑目录不存在或不是目录: {}", commercialDir);
        }

        return new CityIncome(rentFileCount, rentAmount, taxFileCount, taxAmount);
    }

    private static List<Path> listCitySkFiles(Path dir, String cityIdStr) throws IOException {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".sk"))
                    .filter(path -> isOwnedByCity(path, cityIdStr))
                    .toList();
        }
    }

    private static boolean isOwnedByCity(Path path, String cityIdStr) {
        try {
            String content = Files.readString(path);
            return content.contains("cityid: " + cityIdStr)
                    || content.contains("cityId: " + cityIdStr)
                    || content.contains("city_id: " + cityIdStr);
        } catch (IOException e) {
            return false;
        }
    }

    private static double parseRent(Path skFile) {
        try {
            if (!Files.exists(skFile)) {
                Simukraft.LOGGER.debug("[WorldEvents] collectRent skip missing file: {}", skFile.getFileName());
                return -1;
            }

            for (String line : Files.readAllLines(skFile)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("rent:")) {
                    String rentStr = trimmed.substring(5).trim().replace("元", "").trim();
                    double rent = Double.parseDouble(rentStr);
                    Simukraft.LOGGER.debug("[WorldEvents] collectRent file {} => {}", skFile.getFileName(), rent);
                    return rent;
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.warn("[WorldEvents] 读取住宅租金失败: {}", skFile.getFileName(), e);
        }
        return -1;
    }

    /**
     * 从商业建筑sk文件中读取amount字段（租金）
     */
    private static double parseAmount(Path skFile) {
        try {
            if (!Files.exists(skFile)) {
                Simukraft.LOGGER.debug("[WorldEvents] collectAmount skip missing commercial file: {}", skFile.getFileName());
                return -1;
            }

            for (String line : Files.readAllLines(skFile)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("amount:")) {
                    String amountStr = trimmed.substring(7).trim().replace("元", "").trim();
                    double amount = Double.parseDouble(amountStr);
                    Simukraft.LOGGER.debug("[WorldEvents] collectAmount file {} => {}", skFile.getFileName(), amount);
                    return amount;
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.warn("[WorldEvents] 读取商业建筑租金失败: {}", skFile.getFileName(), e);
        }
        return -1;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NotificationServiceManager.clearServer();

        // 服务器停止时保存动物生成数据
        com.xiaoliang.simukraft.job.core.JobRuntimeService.get().onServerStopping(event.getServer().overworld());
        com.xiaoliang.simukraft.employment.service.EmploymentServices.clear(event.getServer());

        IndustrialHiredData.saveAnimalGenerationData(event.getServer());
        FarmlandHiredData.saveAllFarmlandData(event.getServer());
        pendingMoneySounds.clear();
        collectedIncomeByCity.clear();
        NetworkManager.clearAllHUDSyncState();
        com.xiaoliang.simukraft.utils.NPCTaskScheduler.invalidateCache();
        com.xiaoliang.simukraft.utils.NPCTaskScheduler.shutdown();

        // simukraft: 服务器停止时保存已放置的建筑结构数据（支持一键拆除和NPC识别）
        com.xiaoliang.simukraft.building.PlacedBuildingManager.saveToWorld(event.getServer());

        Simukraft.LOGGER.info("[WorldEvents] 动物生成数据、农田盒数据、建筑结构数据已保存，NPC任务调度器已关闭");
    }

    /**
     * 修复：监听方块破坏事件，确保工作方块被摧毁时解雇NPC
     * 作为onRemove的备用方案，处理某些特殊情况（如爆炸、命令替换等）
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;

        var level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        var state = event.getState();
        var pos = event.getPos();
        var server = serverLevel.getServer();

        // 检查是否是工作方块
        boolean isWorkBlock = state.is(com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get())
            || state.is(com.xiaoliang.simukraft.init.ModBlocks.COMMERCIAL_CONTROL_BOX.get())
            || state.is(com.xiaoliang.simukraft.init.ModBlocks.NSUK_FARMLAND_BOX.get())
            || state.is(com.xiaoliang.simukraft.init.ModBlocks.BUILD_BOX.get())
            || state.is(com.xiaoliang.simukraft.init.ModBlocks.LOGISTICS_SERVER_BOX.get())
            || state.is(com.xiaoliang.simukraft.init.ModBlocks.RESIDENTIAL_CONTROL_BOX.get());

        if (!isWorkBlock) return;

        // 使用V2存储解雇该位置的NPC
        var releaseResult = com.xiaoliang.simukraft.employment.service.EmploymentServices.get(server)
            .onWorkBlockRemoved(new com.xiaoliang.simukraft.employment.service.EmploymentCommands.WorkBlockRemovedCommand(
                serverLevel.dimension().location().toString(), pos));

        if (releaseResult.success() && releaseResult.assignment() != null) {
            var assignment = releaseResult.assignment();
            UUID npcUuid = assignment.npcUuid();

            // 查找NPC并重置状态
            var npc = com.xiaoliang.simukraft.world.BaseBuildingHiredData.findNPCByUuid(server, npcUuid);
            if (npc != null) {
                npc.setWorkStatus(com.xiaoliang.simukraft.entity.WorkStatus.IDLE);
                npc.resetToIdle();
                Simukraft.LOGGER.info("[WorldEvents] 工作方块被摧毁，已解雇NPC: {}, 位置: {}", npc.getFullName(), pos);
            }

            // 根据方块类型清理对应的雇佣数据
            if (state.is(com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get())) {
                var hiredEmployees = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
                if (hiredEmployees.containsKey(pos)) {
                    hiredEmployees.remove(pos);
                    com.xiaoliang.simukraft.world.IndustrialHiredData.saveHiredEmployees(server, hiredEmployees);
                }
            } else if (state.is(com.xiaoliang.simukraft.init.ModBlocks.COMMERCIAL_CONTROL_BOX.get())) {
                var hiredEmployees = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
                if (hiredEmployees.containsKey(pos)) {
                    hiredEmployees.remove(pos);
                    com.xiaoliang.simukraft.world.CommercialHiredData.saveHiredEmployees(server, hiredEmployees);
                }
            }

            // 发送同步数据包
            com.xiaoliang.simukraft.network.NetworkManager.sendToAll(
                new com.xiaoliang.simukraft.network.EmploymentStateChangedPacket(assignment), serverLevel);
        }
    }

    private record CityIncome(int rentFileCount, double rentAmount, int taxFileCount, double taxAmount) {
        private double totalAmount() {
            return rentAmount + taxAmount;
        }
    }

    private record PlayerCitySnapshot(String cityName, double cityFunds, int cityPopulation) {
        private static final PlayerCitySnapshot EMPTY = new PlayerCitySnapshot("", 0.0, 0);
    }
}
