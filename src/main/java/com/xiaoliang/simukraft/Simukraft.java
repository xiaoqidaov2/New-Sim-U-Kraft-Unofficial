package com.xiaoliang.simukraft;

import com.mojang.logging.LogUtils;
import com.xiaoliang.simukraft.client.ClientToastHUDOverlay;
import com.xiaoliang.simukraft.client.ClientHUDOverlay;
import com.xiaoliang.simukraft.client.ClientSimukraftData;
import com.xiaoliang.simukraft.client.ModModelLayers;
import com.xiaoliang.simukraft.client.config.ModMenuIntegration;
import com.xiaoliang.simukraft.config.ClientConfigSpec;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.client.model.CustomEntityModel;
import com.xiaoliang.simukraft.client.model.FloatingBuildBoxModel;
import com.xiaoliang.simukraft.client.renderer.CustomEntityRenderer;
import com.xiaoliang.simukraft.client.renderer.FloatingBuildBoxRenderer;
import com.xiaoliang.simukraft.command.CommandSimukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.FloatingBuildBoxEntity;
import com.xiaoliang.simukraft.event.PlayerEvents;
import com.xiaoliang.simukraft.event.WorldEvents;
import com.xiaoliang.simukraft.crafting.ModRecipeSerializers;
import com.xiaoliang.simukraft.init.*;
import com.xiaoliang.simukraft.logging.SimukraftLogConfigurator;
import com.xiaoliang.simukraft.network.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.Objects;


@Mod(Simukraft.MOD_ID)
public class Simukraft {
    public static final String MOD_ID = "simukraft";
    public static final Logger LOGGER = LogUtils.getLogger();
    
    public static String getVersion() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(ModContainer::getModInfo)
                .map(modInfo -> modInfo.getVersion().toString())
                .orElse("unknown");
    }
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    
    public Simukraft(FMLJavaModLoadingContext context) {
        SimukraftLogConfigurator.install();
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);

        ModItems.ITEMS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModSoundEvents.SOUND_EVENTS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModCreativeModeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModRecipeSerializers.RECIPE_SERIALIZERS.register(modEventBus);

        // 注册服务器配置
        ServerConfig.register();
        ClientConfigSpec.register();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(PlayerEvents.class);
        MinecraftForge.EVENT_BUS.register(WorldEvents.class);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onEntityJoin);

    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            NetworkManager.register();
            com.xiaoliang.simukraft.job.ModJobs.register();
            com.xiaoliang.simukraft.utils.BuildingDataManager.init();
            com.xiaoliang.simukraft.utils.NPCTaskScheduler.initialize();
            com.xiaoliang.simukraft.building.CommercialBuildingManager.init(null);
            com.xiaoliang.simukraft.building.IndustrialBuildingManager.init(null);
            com.xiaoliang.simukraft.integration.ModIntegrationManager.init();
            if (com.xiaoliang.simukraft.integration.ModIntegrationManager.isXaeroWorldMapPresent()) {
                com.xiaoliang.simukraft.integration.xaero.XaeroWorldMapIntegration.init();
            }
            if (com.xiaoliang.simukraft.integration.ModIntegrationManager.isOpenPACPresent()) {
                com.xiaoliang.simukraft.integration.pac.OPACIntegration.init();
            }
        });
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if(event.getTabKey() == ModCreativeModeTabs.SIMUKRAFT_TAB.getKey()) {
            event.accept(ModBlocks.BUILD_BOX_ITEM);
        }
        else if(event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModBlocks.BUILD_BOX_ITEM);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        // 注册统一的 SimuKraft 指令
        CommandSimukraft.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof CustomEntity entity && !event.getLevel().isClientSide()) {
            if (!entity.isNameInitialized()) {
                entity.initializeName();
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class CommonModEvents {
        @SubscribeEvent
        public static void registerAttributes(EntityAttributeCreationEvent event) {
            event.put(ModEntities.CUSTOM_ENTITY.get(), CustomEntity.createAttributes().build());
            event.put(ModEntities.FLOATING_BUILD_BOX.get(), FloatingBuildBoxEntity.createAttributes().build());
        }
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            EntityRenderers.register(Objects.requireNonNull(ModEntities.CUSTOM_ENTITY.get()), CustomEntityRenderer::new);
            EntityRenderers.register(Objects.requireNonNull(ModEntities.FLOATING_BUILD_BOX.get()), FloatingBuildBoxRenderer::new);
            // 注册模组配置界面
            ModMenuIntegration.registerConfigScreen();

            // 注册菜单屏幕
            event.enqueueWork(() -> {
                net.minecraft.client.gui.screens.MenuScreens.register(
                        Objects.requireNonNull(ModMenus.WAREHOUSE_GRID_MENU.get()),
                        com.xiaoliang.simukraft.client.gui.WarehouseGridContainerScreen::new
                );
            });
        }



        @SubscribeEvent
        public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
            // 注册Alex纤细手臂模型
            event.registerLayerDefinition(
                    Objects.requireNonNull(ModModelLayers.CUSTOM_ENTITY),
                    CustomEntityModel::createPlayerLikeLayer
            );
            // 注册Steve粗手臂模型（皮肤文件名以_f结尾时使用）
            event.registerLayerDefinition(
                    Objects.requireNonNull(ModModelLayers.CUSTOM_ENTITY_STEVE),
                    CustomEntityModel::createSteveLayer
            );
            event.registerLayerDefinition(
                    Objects.requireNonNull(ModModelLayers.FLOATING_BUILD_BOX),
                    FloatingBuildBoxModel::createBodyLayer
            );
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAbove(
                    Objects.requireNonNull(VanillaGuiOverlay.PLAYER_HEALTH.id()),
                    "simukraft_toast",
                    Objects.requireNonNull(ClientToastHUDOverlay.INSTANCE)
            );
            event.registerAbove(
                    Objects.requireNonNull(VanillaGuiOverlay.PLAYER_HEALTH.id()),
                    "simukraft_hud",
                    Objects.requireNonNull(ClientHUDOverlay.INSTANCE)
            );
        }
    }
    
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientForgeEvents {
        private static boolean hasLoadedData = false;

        /**
         * 客户端加入世界时初始化地图系统。
         * 每次进入新存档/世界都会触发，确保地图数据从全新状态开始，不残留上一个存档的数据。
         */
        @SubscribeEvent
        public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            com.xiaoliang.simukraft.client.map.SimuMapManager mgr =
                    com.xiaoliang.simukraft.client.map.SimuMapManager.getInstance();
            // 若旧实例未正确关闭（如异常退出），先强制 shutdown 清理残留数据
            if (com.xiaoliang.simukraft.client.map.SimuMapManager.isAvailable()) {
                mgr.shutdown();
            }
            // 重新获取实例（shutdown 会把 instance 置 null，需要重新 getInstance）
            com.xiaoliang.simukraft.client.map.SimuMapManager.getInstance().init();
            hasLoadedData = false;

            // 客户端也需要初始化建筑配置管理器（用于多人游戏）
            // 在单人游戏中，服务器已经初始化了这些管理器，重复初始化不会有问题
            com.xiaoliang.simukraft.building.CommercialBuildingManager.init(null);
            com.xiaoliang.simukraft.building.IndustrialBuildingManager.init(null);
            LOGGER.info("[Simukraft] 客户端已初始化建筑配置管理器");
        }

        /**
         * 客户端离开世界时关闭地图系统，释放所有区域数据和 GPU 纹理。
         * 确保下次进入不同存档时不会看到旧数据。
         */
        @SubscribeEvent
        public static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            if (com.xiaoliang.simukraft.client.map.SimuMapManager.isAvailable()) {
                com.xiaoliang.simukraft.client.map.SimuMapManager.getInstance().shutdown();
            }
            com.xiaoliang.simukraft.client.CityNameCache.clear();
            com.xiaoliang.simukraft.client.NPCResidenceCache.clearCache();
            ClientSimukraftData.resetAllClientState();
            ClientToastHUDOverlay.clearAllToasts();
            hasLoadedData = false;
        }

        private static com.xiaoliang.simukraft.client.gui.ConfigButtonHandler configButtonHandler;
        private static boolean pathDebugHotkeyPressedLastTick;

        @SubscribeEvent
        public static void onScreenInit(net.minecraftforge.client.event.ScreenEvent.Init.Post event) {
            // 注册配置按钮处理器（单例模式）
            if (configButtonHandler == null) {
                configButtonHandler = new com.xiaoliang.simukraft.client.gui.ConfigButtonHandler();
            }
            configButtonHandler.onTitleScreenInit(event);
            configButtonHandler.onPauseScreenInit(event);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            // 更新按钮状态
            if (configButtonHandler != null) {
                configButtonHandler.updateButtonState();
            }

            Minecraft mc = Minecraft.getInstance();

            if (mc.player != null && mc.level != null) {
                long window = mc.getWindow().getWindow();
                boolean f3Pressed = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_F3) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                boolean kPressed = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_K) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                boolean hotkeyPressed = f3Pressed && kPressed;
                if (hotkeyPressed && !pathDebugHotkeyPressedLastTick) {
                    com.xiaoliang.simukraft.client.gui.NPCPathDebugRenderer.togglePathDebug();
                }
                pathDebugHotkeyPressedLastTick = hotkeyPressed;
            } else {
                pathDebugHotkeyPressedLastTick = false;
            }

            // 只在客户端初始化完成后加载一次数据
            if (!hasLoadedData && mc.level != null) {
                hasLoadedData = true;
                // 加载雇佣的NPC数据
                if (mc.getSingleplayerServer() != null) {
                    com.xiaoliang.simukraft.client.gui.BuildBoxData.loadHiredBuilders(
                            mc.getSingleplayerServer()
                    );
                }
            }

            while (com.xiaoliang.simukraft.client.MapKeyBindings.getOpenMapKey().consumeClick()) {
                com.xiaoliang.simukraft.client.gui.map.CityChunkMapScreen.open();
            }
        }
    }
}
