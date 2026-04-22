package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.utils.MessageUtils;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.PopulationData;
import com.xiaoliang.simukraft.world.SimukraftWorldData;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
@SuppressWarnings({"null", "unused"})
public class PlayerEvents {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final ResourceLocation FIRST_DREAM_ADVANCEMENT_ID =
            ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, "story/first_dream");
    private static final String FIRST_DREAM_CRITERION = "first_join";
    private static final String FIRST_DREAM_PLAYED_TAG = "simukraft_first_dream_played";
    // 玩家刚进世界时客户端尚未完全稳定，先延迟一小段时间再播音效更可靠
    private static final int FIRST_DREAM_START_DELAY_TICKS = 40;
    // first_dream.ogg 当前时长约 11 秒，换算为 220 tick
    private static final int FIRST_DREAM_SOUND_DURATION_TICKS = 220;
    private static final Map<UUID, FirstDreamPendingState> PENDING_FIRST_DREAM = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onPlayerFirstJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var server = player.getServer();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                try {
                    // 移除模式选择书发放逻辑，直接跳过模式选择检查
                    MessageUtils.sendModeSelectedMessages(player);

                    ServerTickHandler.scheduleDelayedNPCProcessing(player, 100);
                    
                    // 发送HUD数据同步包给新登录的玩家
                    ServerLevel level = player.serverLevel();
                    
                    // 获取当前天数
                    SimukraftWorldData worldData = SimukraftWorldData.get(level);
                    int currentDay = worldData.getCurrentDay();
                    
                    // 获取世界人口
                    PopulationData populationData = PopulationData.get(level);
                    int worldPopulation = populationData.getPopulation();
                    
                    // 获取玩家城市数据
                    String cityName = "";
                    double cityFunds = 0.0;
                    int cityPopulation = 0;

                    CityData cityData = CityData.get(level);
                    String playerName = player.getName().getString();
                    // 使用玩家名获取城市ID（支持官员身份）
                    UUID cityId = cityData.getPlayerCityIdByName(playerName);
                    if (cityId != null) {
                        CityData.CityInfo cityInfo = cityData.getCity(cityId);
                        if (cityInfo != null) {
                            cityName = cityInfo.getCityName();
                            cityFunds = cityInfo.getFunds();
                            cityPopulation = cityInfo.getCitizenIds().size();
                        }
                    }
                    
                    // 发送HUD数据同步包
                    NetworkManager.sendHUDDataToPlayer(currentDay, worldPopulation, cityName, cityFunds, cityPopulation, player);
                    
                    // 同步所有城市区块数据（供地图高亮显示）
                    NetworkManager.broadcastAllCityChunks(server);
                    // 同步所有城市核心位置（供地图标记显示）
                    NetworkManager.broadcastAllCityCores(server);
                    scheduleFirstDreamSequence(player);
                } catch (Exception e) {
                    LOGGER.error("Player join error", e);
                    player.sendSystemMessage(Component.translatable("message.simukraft.error.generic", e.getMessage()));
                }
            });
        }
    }


    @SubscribeEvent
    public static void onEntityDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof CustomEntity npc && !event.getEntity().level().isClientSide()) {
            LOGGER.debug("NPC death detected: {} (ID: {})", npc.getFullName(), npc.getNpcId());
            // 实际删除操作已在CustomEntity.die()中处理
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_FIRST_DREAM.isEmpty()) {
            return;
        }

        MinecraftServer server = event.getServer();
        for (Map.Entry<UUID, FirstDreamPendingState> entry : PENDING_FIRST_DREAM.entrySet()) {
            FirstDreamPendingState state = entry.getValue();
            int remainingTicks = state.remainingTicks() - 1;
            if (remainingTicks > 0) {
                PENDING_FIRST_DREAM.put(entry.getKey(), state.withRemainingTicks(remainingTicks));
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                PENDING_FIRST_DREAM.remove(entry.getKey());
                continue;
            }

            if (state.stage() == FirstDreamStage.WAITING_TO_PLAY_SOUND) {
                playFirstDreamSound(player);
                PENDING_FIRST_DREAM.put(entry.getKey(),
                        new FirstDreamPendingState(FirstDreamStage.WAITING_TO_GRANT_ADVANCEMENT,
                                FIRST_DREAM_SOUND_DURATION_TICKS));
                continue;
            }

            PENDING_FIRST_DREAM.remove(entry.getKey());
            grantFirstDreamAdvancement(player);
        }
    }

    private static void scheduleFirstDreamSequence(ServerPlayer player) {
        if (hasPlayedFirstDream(player) || PENDING_FIRST_DREAM.containsKey(player.getUUID())) {
            return;
        }

        Advancement advancement = player.server.getAdvancements().getAdvancement(FIRST_DREAM_ADVANCEMENT_ID);
        if (advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone()) {
            markFirstDreamPlayed(player);
            return;
        }

        PENDING_FIRST_DREAM.put(player.getUUID(),
                new FirstDreamPendingState(FirstDreamStage.WAITING_TO_PLAY_SOUND, FIRST_DREAM_START_DELAY_TICKS));
    }

    private static void playFirstDreamSound(ServerPlayer player) {
        SoundEvent sound = ModSoundEvents.FIRST_DREAM.get();
        // 改到音乐盒分类，避免玩家关闭背景音乐后完全听不见
        player.playNotifySound(sound, SoundSource.RECORDS, 1.0F, 1.0F);
    }

    private static void grantFirstDreamAdvancement(ServerPlayer player) {
        Advancement advancement = player.server.getAdvancements().getAdvancement(FIRST_DREAM_ADVANCEMENT_ID);
        if (advancement == null) {
            LOGGER.warn("Missing advancement: {}", FIRST_DREAM_ADVANCEMENT_ID);
            return;
        }

        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
        if (progress.isDone()) {
            markFirstDreamPlayed(player);
            return;
        }

        boolean awarded = player.getAdvancements().award(advancement, FIRST_DREAM_CRITERION);
        if (!awarded) {
            return;
        }

        markFirstDreamPlayed(player);
    }

    private static boolean hasPlayedFirstDream(ServerPlayer player) {
        return player.getPersistentData()
                .getCompound(Player.PERSISTED_NBT_TAG)
                .getBoolean(FIRST_DREAM_PLAYED_TAG);
    }

    private static void markFirstDreamPlayed(ServerPlayer player) {
        CompoundTag persistedData = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persistedData.putBoolean(FIRST_DREAM_PLAYED_TAG, true);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persistedData);
    }

    private enum FirstDreamStage {
        WAITING_TO_PLAY_SOUND,
        WAITING_TO_GRANT_ADVANCEMENT
    }

    private record FirstDreamPendingState(FirstDreamStage stage, int remainingTicks) {
        private FirstDreamPendingState withRemainingTicks(int updatedTicks) {
            return new FirstDreamPendingState(stage, updatedTicks);
        }
    }
}
