package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CityPermissionManager;
import com.xiaoliang.simukraft.world.PopulationData;
import com.xiaoliang.simukraft.world.SimukraftWorldData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public class NetworkManager {
    private static final String PROTOCOL_VERSION = "1";
    private static final Map<UUID, HudSyncState> LAST_HUD_SYNC = new ConcurrentHashMap<>();
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("simukraft", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int id = 0;

    public static void register() {
        INSTANCE.registerMessage(id++, SyncDayPacket.class,
                SyncDayPacket::encode,
                SyncDayPacket::new,
                SyncDayPacket::handle);

        INSTANCE.registerMessage(id++, SyncPopulationPacket.class,
                SyncPopulationPacket::encode,
                SyncPopulationPacket::new,
                SyncPopulationPacket::handle);

        INSTANCE.registerMessage(id++,
                SyncNPCPathDebugPacket.class,
                SyncNPCPathDebugPacket::encode,
                SyncNPCPathDebugPacket::new,
                SyncNPCPathDebugPacket::handle);
                
        INSTANCE.registerMessage(id++, SyncHUDDataPacket.class,
                SyncHUDDataPacket::encode,
                SyncHUDDataPacket::new,
                SyncHUDDataPacket::handle);

        INSTANCE.registerMessage(id++,
                NPCWorkStatusPacket.class,
                NPCWorkStatusPacket::encode,
                NPCWorkStatusPacket::new,
                NPCWorkStatusPacket::handle);

        INSTANCE.registerMessage(id++,
                NPCListPacket.class,
                NPCListPacket::encode,
                NPCListPacket::new,
                NPCListPacket::handle);

        INSTANCE.registerMessage(id++,
                RequestIdleNPCsPacket.class,
                RequestIdleNPCsPacket::encode,
                buf -> new RequestIdleNPCsPacket(),
                RequestIdleNPCsPacket::handle);

        INSTANCE.registerMessage(id++,
                CreateCityPacket.class,
                CreateCityPacket::encode,
                CreateCityPacket::decode,
                CreateCityPacket::handle);

        INSTANCE.registerMessage(id++,
                GetCityNamePacket.class,
                GetCityNamePacket::encode,
                GetCityNamePacket::new,
                GetCityNamePacket::handle);

        INSTANCE.registerMessage(id++,
                CityNameResponsePacket.class,
                CityNameResponsePacket::encode,
                CityNameResponsePacket::new,
                CityNameResponsePacket::handle);

        INSTANCE.registerMessage(id++,
                StartConstructionPacket.class,
                StartConstructionPacket::encode,
                StartConstructionPacket::new,
                StartConstructionPacket::handle);

        INSTANCE.registerMessage(id++,
                ConstructionProgressPacket.class,
                ConstructionProgressPacket::encode,
                ConstructionProgressPacket::new,
                ConstructionProgressPacket::handle);

        INSTANCE.registerMessage(id++,
                CheckCityStatusPacket.class,
                CheckCityStatusPacket::encode,
                CheckCityStatusPacket::decode,
                CheckCityStatusPacket::handle);

        INSTANCE.registerMessage(id++,
                CityStatusResponsePacket.class,
                CityStatusResponsePacket::encode,
                CityStatusResponsePacket::decode,
                CityStatusResponsePacket::handle);

        INSTANCE.registerMessage(id++,
                GetCityInfoPacket.class,
                GetCityInfoPacket::encode,
                GetCityInfoPacket::decode,
                GetCityInfoPacket::handle);

        INSTANCE.registerMessage(id++,
                CityInfoResponsePacket.class,
                CityInfoResponsePacket::encode,
                CityInfoResponsePacket::decode,
                CityInfoResponsePacket::handle);

        INSTANCE.registerMessage(id++,
                GetCityChunksPacket.class,
                GetCityChunksPacket::encode,
                GetCityChunksPacket::decode,
                GetCityChunksPacket::handle);

        INSTANCE.registerMessage(id++,
                CityChunksResponsePacket.class,
                CityChunksResponsePacket::encode,
                CityChunksResponsePacket::decode,
                CityChunksResponsePacket::handle);

        INSTANCE.registerMessage(id++,
                BuyChunkPacket.class,
                BuyChunkPacket::encode,
                BuyChunkPacket::decode,
                BuyChunkPacket::handle);

        INSTANCE.registerMessage(id++,
                BuyChunkResponsePacket.class,
                BuyChunkResponsePacket::encode,
                BuyChunkResponsePacket::decode,
                BuyChunkResponsePacket::handle);

        // 建筑列表请求和响应
        INSTANCE.registerMessage(id++,
                BuildingListRequestPacket.class,
                BuildingListRequestPacket::encode,
                BuildingListRequestPacket::decode,
                BuildingListRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                BuildingListResponsePacket.class,
                BuildingListResponsePacket::encode,
                BuildingListResponsePacket::new,
                BuildingListResponsePacket::handle);

        // 建筑NBT请求和响应
        INSTANCE.registerMessage(id++,
                BuildingNBTRequestPacket.class,
                BuildingNBTRequestPacket::encode,
                BuildingNBTRequestPacket::decode,
                BuildingNBTRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                BuildingNBTResponsePacket.class,
                BuildingNBTResponsePacket::encode,
                BuildingNBTResponsePacket::new,
                BuildingNBTResponsePacket::handle);

        // 建材商店购买和出售
        INSTANCE.registerMessage(id++,
                BuyBuildingMaterialPacket.class,
                BuyBuildingMaterialPacket::encode,
                BuyBuildingMaterialPacket::new,
                BuyBuildingMaterialPacket::handle);

        // 注册出售物品给NPC的数据包
        INSTANCE.registerMessage(id++,
                SellToNPCPacket.class,
                SellToNPCPacket::encode,
                SellToNPCPacket::decode,
                SellToNPCPacket::handle);

        // 注册商业建筑购买数据包（带库存减少）
        INSTANCE.registerMessage(id++,
                CommercialBuyPacket.class,
                CommercialBuyPacket::encode,
                CommercialBuyPacket::decode,
                CommercialBuyPacket::handle);

        INSTANCE.registerMessage(id++,
                SellBuildingMaterialPacket.class,
                SellBuildingMaterialPacket::encode,
                SellBuildingMaterialPacket::new,
                SellBuildingMaterialPacket::handle);
                
        // Toast显示数据包
        INSTANCE.registerMessage(id++,
                ShowToastPacket.class,
                ShowToastPacket::encode,
                ShowToastPacket::decode,
                ShowToastPacket::handle);
        
        // 城市等级请求和响应数据包
        INSTANCE.registerMessage(id++,
                GetCityLevelPacket.class,
                GetCityLevelPacket::encode,
                GetCityLevelPacket::decode,
                GetCityLevelPacket::handle);
                
        INSTANCE.registerMessage(id++,
                CityLevelResponsePacket.class,
                CityLevelResponsePacket::encode,
                CityLevelResponsePacket::decode,
                CityLevelResponsePacket::handle);
                
        // 城市升级请求数据包
        INSTANCE.registerMessage(id++,
                CityUpgradeRequestPacket.class,
                CityUpgradeRequestPacket::encode,
                CityUpgradeRequestPacket::new,
                CityUpgradeRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                CityUpgradeResultPacket.class,
                CityUpgradeResultPacket::encode,
                CityUpgradeResultPacket::decode,
                CityUpgradeResultPacket::handle);
    
        // 注册开始耕种数据包
        INSTANCE.registerMessage(
            id++,
            StartFarmingPacket.class,
            StartFarmingPacket::encode,
            StartFarmingPacket::new,
            StartFarmingPacket::handle
        );

        // 居民信息请求和响应
        INSTANCE.registerMessage(id++,
                RequestResidentInfoPacket.class,
                RequestResidentInfoPacket::encode,
                RequestResidentInfoPacket::new,
                RequestResidentInfoPacket::handle);

        INSTANCE.registerMessage(id++,
                ResidentInfoResponsePacket.class,
                ResidentInfoResponsePacket::encode,
                ResidentInfoResponsePacket::new,
                ResidentInfoResponsePacket::handle);
        
        // 保存农田盒数据数据包
        INSTANCE.registerMessage(id++,
                SaveFarmlandDataPacket.class,
                SaveFarmlandDataPacket::encode,
                SaveFarmlandDataPacket::new,
                SaveFarmlandDataPacket::handle);
        
        // 雇佣农民数据包
        INSTANCE.registerMessage(id++,
                HireFarmerPacket.class,
                HireFarmerPacket::encode,
                HireFarmerPacket::new,
                HireFarmerPacket::handle);

        // 农田盒数据同步请求（客户端->服务器）
        INSTANCE.registerMessage(id++,
                SyncFarmlandDataPacket.Request.class,
                SyncFarmlandDataPacket.Request::encode,
                SyncFarmlandDataPacket.Request::new,
                SyncFarmlandDataPacket.Request::handle);

        // 农田盒数据同步响应（服务器->客户端）
        INSTANCE.registerMessage(id++,
                SyncFarmlandDataPacket.Response.class,
                SyncFarmlandDataPacket.Response::encode,
                SyncFarmlandDataPacket.Response::new,
                SyncFarmlandDataPacket.Response::handle);

        // 农田盒配置设置（客户端->服务器）
        INSTANCE.registerMessage(id++,
                SetFarmlandConfigPacket.class,
                SetFarmlandConfigPacket::encode,
                SetFarmlandConfigPacket::new,
                SetFarmlandConfigPacket::handle);

        // 控制盒信息请求和响应
        INSTANCE.registerMessage(id++,
                RequestControlBoxInfoPacket.class,
                RequestControlBoxInfoPacket::encode,
                RequestControlBoxInfoPacket::new,
                RequestControlBoxInfoPacket::handle);

        INSTANCE.registerMessage(id++,
                ControlBoxInfoResponsePacket.class,
                ControlBoxInfoResponsePacket::encode,
                ControlBoxInfoResponsePacket::new,
                ControlBoxInfoResponsePacket::handle);

        // 市民列表请求和响应
        INSTANCE.registerMessage(id++,
                CitizenListRequestPacket.class,
                CitizenListRequestPacket::encode,
                CitizenListRequestPacket::decode,
                CitizenListRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                CitizenListResponsePacket.class,
                CitizenListResponsePacket::encode,
                CitizenListResponsePacket::new,
                CitizenListResponsePacket::handle);

        // NPC重命名
        INSTANCE.registerMessage(id++,
                RenameNPCPacket.class,
                RenameNPCPacket::encode,
                RenameNPCPacket::decode,
                RenameNPCPacket::handle);
                
        // NPC名称同步
        INSTANCE.registerMessage(id++,
                SyncNPCNamePacket.class,
                SyncNPCNamePacket::encode,
                SyncNPCNamePacket::decode,
                SyncNPCNamePacket::handle);
                
        // NPC删除
        INSTANCE.registerMessage(id++,
                DeleteNPCPacket.class,
                DeleteNPCPacket::encode,
                DeleteNPCPacket::new,
                DeleteNPCPacket::handle);
                
        // NPC删除同步
        INSTANCE.registerMessage(id++,
                SyncNPCDeletePacket.class,
                SyncNPCDeletePacket::encode,
                SyncNPCDeletePacket::new,
                SyncNPCDeletePacket::handle);
                
        // 城市删除
        INSTANCE.registerMessage(id++,
                DeleteCityPacket.class,
                DeleteCityPacket::encode,
                DeleteCityPacket::new,
                DeleteCityPacket::handle);

        // 材料需求请求和响应
        INSTANCE.registerMessage(id++,
                MaterialRequirementsRequestPacket.class,
                MaterialRequirementsRequestPacket::encode,
                MaterialRequirementsRequestPacket::decode,
                MaterialRequirementsRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                MaterialRequirementsResponsePacket.class,
                MaterialRequirementsResponsePacket::encode,
                MaterialRequirementsResponsePacket::decode,
                MaterialRequirementsResponsePacket::handle);

        // 创建规划任务数据包
        INSTANCE.registerMessage(id++,
                CreatePlanningTaskPacket.class,
                CreatePlanningTaskPacket::encode,
                CreatePlanningTaskPacket::decode,
                CreatePlanningTaskPacket::handle);

        // 方块替换数据包
        INSTANCE.registerMessage(id++,
                BlockReplacementPacket.class,
                BlockReplacementPacket::encode,
                BlockReplacementPacket::new,
                BlockReplacementPacket::handle);

        // 箱子扫描请求和响应数据包
        INSTANCE.registerMessage(id++,
                ChestScanRequestPacket.class,
                ChestScanRequestPacket::encode,
                ChestScanRequestPacket::new,
                ChestScanRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                ChestScanResponsePacket.class,
                ChestScanResponsePacket::encode,
                ChestScanResponsePacket::new,
                ChestScanResponsePacket::handle);

        // 雇员列表请求和响应
        INSTANCE.registerMessage(id++,
                EmployeeListRequestPacket.class,
                EmployeeListRequestPacket::encode,
                buf -> new EmployeeListRequestPacket(),
                EmployeeListRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                EmployeeListResponsePacket.class,
                EmployeeListResponsePacket::encode,
                EmployeeListResponsePacket::new,
                EmployeeListResponsePacket::handle);

        // 建筑盒雇佣状态同步
        INSTANCE.registerMessage(id++,
                RequestBuildBoxHireStatusPacket.class,
                RequestBuildBoxHireStatusPacket::encode,
                RequestBuildBoxHireStatusPacket::new,
                RequestBuildBoxHireStatusPacket::handle);

        INSTANCE.registerMessage(id++,
                SyncBuildBoxHireStatusPacket.class,
                SyncBuildBoxHireStatusPacket::encode,
                SyncBuildBoxHireStatusPacket::new,
                SyncBuildBoxHireStatusPacket::handle);

        // 建筑盒被拆除通知
        INSTANCE.registerMessage(id++,
                BuildBoxDestroyedPacket.class,
                BuildBoxDestroyedPacket::encode,
                BuildBoxDestroyedPacket::new,
                BuildBoxDestroyedPacket::handle);

        // 通用工作方块雇佣状态同步
        INSTANCE.registerMessage(id++,
                RequestWorkBlockHireStatusPacket.class,
                RequestWorkBlockHireStatusPacket::encode,
                RequestWorkBlockHireStatusPacket::new,
                RequestWorkBlockHireStatusPacket::handle);

        INSTANCE.registerMessage(id++,
                SyncWorkBlockHireStatusPacket.class,
                SyncWorkBlockHireStatusPacket::encode,
                SyncWorkBlockHireStatusPacket::new,
                SyncWorkBlockHireStatusPacket::handle);

        // Employment v2 packets (Phase 1)
        INSTANCE.registerMessage(id++,
                EmploymentCommandPacket.class,
                EmploymentCommandPacket::encode,
                EmploymentCommandPacket::new,
                EmploymentCommandPacket::handle);

        INSTANCE.registerMessage(id++,
                EmploymentSnapshotPacket.class,
                EmploymentSnapshotPacket::encode,
                EmploymentSnapshotPacket::new,
                EmploymentSnapshotPacket::handle);

        INSTANCE.registerMessage(id++,
                EmploymentStateChangedPacket.class,
                EmploymentStateChangedPacket::encode,
                EmploymentStateChangedPacket::new,
                EmploymentStateChangedPacket::handle);

        // 配置重载数据包
        INSTANCE.registerMessage(id++,
                ReloadConfigPacket.class,
                ReloadConfigPacket::encode,
                buf -> new ReloadConfigPacket(),
                ReloadConfigPacket::handle);

        // 配置同步数据包
        INSTANCE.registerMessage(id++,
                SyncConfigPacket.class,
                SyncConfigPacket::encode,
                SyncConfigPacket::new,
                SyncConfigPacket::handle);

        // 配置重置数据包
        INSTANCE.registerMessage(id++,
                ResetConfigPacket.class,
                ResetConfigPacket::encode,
                buf -> new ResetConfigPacket(),
                ResetConfigPacket::handle);
                
        // NPC居住信息请求和响应
        INSTANCE.registerMessage(id++,
                RequestNPCResidencePacket.class,
                RequestNPCResidencePacket::encode,
                RequestNPCResidencePacket::new,
                RequestNPCResidencePacket::handle);

        INSTANCE.registerMessage(id++,
                NPCResidenceResponsePacket.class,
                NPCResidenceResponsePacket::encode,
                NPCResidenceResponsePacket::new,
                NPCResidenceResponsePacket::handle);
                
        // 官员管理数据包
        INSTANCE.registerMessage(id++,
                OfficialListRequestPacket.class,
                OfficialListRequestPacket::encode,
                OfficialListRequestPacket::new,
                OfficialListRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                OfficialListResponsePacket.class,
                OfficialListResponsePacket::encode,
                OfficialListResponsePacket::new,
                OfficialListResponsePacket::handle);
                
        INSTANCE.registerMessage(id++,
                AddOfficialPacket.class,
                AddOfficialPacket::encode,
                AddOfficialPacket::new,
                AddOfficialPacket::handle);
                
        INSTANCE.registerMessage(id++,
                RemoveOfficialPacket.class,
                RemoveOfficialPacket::encode,
                RemoveOfficialPacket::new,
                RemoveOfficialPacket::handle);

        // 官员邀请系统数据包
        INSTANCE.registerMessage(id++,
                SendOfficialInvitationPacket.class,
                SendOfficialInvitationPacket::encode,
                SendOfficialInvitationPacket::new,
                SendOfficialInvitationPacket::handle);

        INSTANCE.registerMessage(id++,
                OfficialInvitationResponsePacket.class,
                OfficialInvitationResponsePacket::encode,
                OfficialInvitationResponsePacket::new,
                OfficialInvitationResponsePacket::handle);

        // 物流系统数据包
        INSTANCE.registerMessage(id++,
                LogisticsActionPacket.class,
                LogisticsActionPacket::encode,
                LogisticsActionPacket::new,
                LogisticsActionPacket::handle);

        INSTANCE.registerMessage(id++,
                LogisticsChannelPacket.class,
                LogisticsChannelPacket::encode,
                LogisticsChannelPacket::new,
                LogisticsChannelPacket::handle);

        INSTANCE.registerMessage(id++,
                LogisticsSyncPacket.class,
                LogisticsSyncPacket::encode,
                LogisticsSyncPacket::new,
                LogisticsSyncPacket::handle);

        // 物流客户端重命名数据包
        INSTANCE.registerMessage(id++,
                LogisticsClientRenamePacket.class,
                LogisticsClientRenamePacket::encode,
                LogisticsClientRenamePacket::decode,
                LogisticsClientRenamePacket::handle);

        // 物流客户端重命名同步数据包（客户端接收）
        INSTANCE.registerMessage(id++,
                LogisticsClientRenamePacket.ClientRenameSyncPacket.class,
                LogisticsClientRenamePacket.ClientRenameSyncPacket::encode,
                LogisticsClientRenamePacket.ClientRenameSyncPacket::decode,
                LogisticsClientRenamePacket.ClientRenameSyncPacket::handle);

        // 聊天系统已拆分，相关数据包注册已移除

        INSTANCE.registerMessage(id++,
                SyncAllCityChunksPacket.class,
                SyncAllCityChunksPacket::encode,
                SyncAllCityChunksPacket::decode,
                SyncAllCityChunksPacket::handle);

        // 建材商店库存同步数据包
        INSTANCE.registerMessage(id++,
                SyncBuildingMaterialStockPacket.class,
                SyncBuildingMaterialStockPacket::encode,
                SyncBuildingMaterialStockPacket::decode,
                SyncBuildingMaterialStockPacket::handle);

        INSTANCE.registerMessage(id++,
                RequestStockSyncPacket.class,
                RequestStockSyncPacket::encode,
                RequestStockSyncPacket::decode,
                RequestStockSyncPacket::handle);

        // 打开仓库网格菜单数据包
        INSTANCE.registerMessage(id++,
                OpenWarehouseGridPacket.class,
                OpenWarehouseGridPacket::encode,
                OpenWarehouseGridPacket::new,
                OpenWarehouseGridPacket::handle);

        // 仓库网格请求数据包
        INSTANCE.registerMessage(id++,
                WarehouseGridRequestPacket.class,
                WarehouseGridRequestPacket::encode,
                WarehouseGridRequestPacket::new,
                WarehouseGridRequestPacket::handle);

        // 仓库网格响应数据包
        INSTANCE.registerMessage(id++,
                WarehouseGridResponsePacket.class,
                WarehouseGridResponsePacket::encode,
                WarehouseGridResponsePacket::new,
                WarehouseGridResponsePacket::handle);

        // 仓库网格提取物品数据包
        INSTANCE.registerMessage(id++,
                WarehouseGridExtractPacket.class,
                WarehouseGridExtractPacket::encode,
                WarehouseGridExtractPacket::new,
                WarehouseGridExtractPacket::handle);

        // 仓库网格插入物品数据包
        INSTANCE.registerMessage(id++,
                WarehouseGridInsertPacket.class,
                WarehouseGridInsertPacket::encode,
                WarehouseGridInsertPacket::new,
                WarehouseGridInsertPacket::handle);

        // 仓库网格Shift+点击数据包
        INSTANCE.registerMessage(id++,
                WarehouseGridShiftClickPacket.class,
                WarehouseGridShiftClickPacket::encode,
                WarehouseGridShiftClickPacket::new,
                WarehouseGridShiftClickPacket::handle);

        // 请求物流仓库状态数据包
        INSTANCE.registerMessage(id++,
                RequestLogisticsStatusPacket.class,
                RequestLogisticsStatusPacket::encode,
                RequestLogisticsStatusPacket::new,
                RequestLogisticsStatusPacket::handle);

        // 同步物流盒子雇佣状态数据包
        INSTANCE.registerMessage(id++,
                SyncLogisticsHireStatusPacket.class,
                SyncLogisticsHireStatusPacket::encode,
                SyncLogisticsHireStatusPacket::new,
                SyncLogisticsHireStatusPacket::handle);

        // 同步物流客户端数据包（容器位置）
        INSTANCE.registerMessage(id++,
                SyncLogisticsClientDataPacket.class,
                SyncLogisticsClientDataPacket::encode,
                SyncLogisticsClientDataPacket::new,
                SyncLogisticsClientDataPacket::handle);

        // 客户端存储物品请求和响应
        INSTANCE.registerMessage(id++,
                ClientStorageRequestPacket.class,
                ClientStorageRequestPacket::encode,
                ClientStorageRequestPacket::new,
                ClientStorageRequestPacket::handle);

        INSTANCE.registerMessage(id++,
                ClientStorageResponsePacket.class,
                ClientStorageResponsePacket::encode,
                ClientStorageResponsePacket::new,
                ClientStorageResponsePacket::handle);

        // 物流网络数据请求和响应
        INSTANCE.registerMessage(id++,
                RequestLogisticsNetworkPacket.class,
                RequestLogisticsNetworkPacket::encode,
                RequestLogisticsNetworkPacket::new,
                RequestLogisticsNetworkPacket::handle);

        INSTANCE.registerMessage(id++,
                LogisticsNetworkResponsePacket.class,
                LogisticsNetworkResponsePacket::encode,
                LogisticsNetworkResponsePacket::new,
                LogisticsNetworkResponsePacket::handle);

        // 频道物品数据请求和响应
        INSTANCE.registerMessage(id++,
                RequestChannelItemsPacket.class,
                RequestChannelItemsPacket::encode,
                RequestChannelItemsPacket::new,
                RequestChannelItemsPacket::handle);

        INSTANCE.registerMessage(id++,
                ChannelItemsResponsePacket.class,
                ChannelItemsResponsePacket::encode,
                ChannelItemsResponsePacket::new,
                ChannelItemsResponsePacket::handle);

        // 物流路径同步数据包
        INSTANCE.registerMessage(id++,
                LogisticsRoutesSyncPacket.class,
                LogisticsRoutesSyncPacket::encode,
                LogisticsRoutesSyncPacket::new,
                LogisticsRoutesSyncPacket::handle);

        // 客户端路径请求和响应
        INSTANCE.registerMessage(id++,
                RequestClientRoutesPacket.class,
                RequestClientRoutesPacket::encode,
                RequestClientRoutesPacket::new,
                RequestClientRoutesPacket::handle);

        INSTANCE.registerMessage(id++,
                ClientRoutesResponsePacket.class,
                ClientRoutesResponsePacket::encode,
                ClientRoutesResponsePacket::new,
                ClientRoutesResponsePacket::handle);

        // 容器列表请求和响应
        INSTANCE.registerMessage(id++,
                RequestContainerListPacket.class,
                RequestContainerListPacket::encode,
                RequestContainerListPacket::new,
                RequestContainerListPacket::handle);

        INSTANCE.registerMessage(id++,
                ContainerListResponsePacket.class,
                ContainerListResponsePacket::encode,
                ContainerListResponsePacket::new,
                ContainerListResponsePacket::handle);

        // 解绑容器数据包
        INSTANCE.registerMessage(id++,
                UnbindContainerPacket.class,
                UnbindContainerPacket::encode,
                UnbindContainerPacket::new,
                UnbindContainerPacket::handle);

        // 城市控制盒数据请求和响应
        INSTANCE.registerMessage(id++,
                RequestCityControlBoxesPacket.class,
                RequestCityControlBoxesPacket::encode,
                RequestCityControlBoxesPacket::new,
                RequestCityControlBoxesPacket::handle);

        INSTANCE.registerMessage(id++,
                CityControlBoxesResponsePacket.class,
                CityControlBoxesResponsePacket::encode,
                CityControlBoxesResponsePacket::new,
                CityControlBoxesResponsePacket::handle);

        // 城市核心位置同步数据包
        INSTANCE.registerMessage(id++,
                SyncAllCityCoresPacket.class,
                SyncAllCityCoresPacket::encode,
                SyncAllCityCoresPacket::decode,
                SyncAllCityCoresPacket::handle);

        // 工业建筑配方选择数据包
        INSTANCE.registerMessage(id++,
                SelectRecipePacket.class,
                SelectRecipePacket::encode,
                SelectRecipePacket::new,
                SelectRecipePacket::handle);

        // 工业建筑配方同步数据包
        INSTANCE.registerMessage(id++,
                SyncRecipePacket.class,
                SyncRecipePacket::encode,
                SyncRecipePacket::new,
                SyncRecipePacket::handle);

        // 请求配方同步数据包
        INSTANCE.registerMessage(id++,
                RequestRecipeSyncPacket.class,
                RequestRecipeSyncPacket::encode,
                RequestRecipeSyncPacket::new,
                RequestRecipeSyncPacket::handle);

        INSTANCE.registerMessage(id++,
                FeedNPCPacket.class,
                FeedNPCPacket::encode,
                FeedNPCPacket::decode,
                FeedNPCPacket::handle);

        // 指南书热重载数据包（服务器->客户端）
        INSTANCE.registerMessage(id++,
                ReloadGuideBookPacket.class,
                ReloadGuideBookPacket::encode,
                buf -> new ReloadGuideBookPacket(),
                ReloadGuideBookPacket::handle);

        // 拆除建筑数据包（客户端->服务器）
        INSTANCE.registerMessage(id++,
                DemolishBuildingPacket.class,
                DemolishBuildingPacket::encode,
                DemolishBuildingPacket::new,
                DemolishBuildingPacket::handle);

        // 界限显示切换数据包（客户端->服务器）
        INSTANCE.registerMessage(id++,
                ToggleBoundsDisplayPacket.class,
                ToggleBoundsDisplayPacket::encode,
                ToggleBoundsDisplayPacket::new,
                ToggleBoundsDisplayPacket::handle);
    }

    public static void sendToPlayer(SyncDayPacket packet, ServerPlayer player) {
        INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    public static void sendToPlayer(SyncLogisticsHireStatusPacket packet, ServerPlayer player) {
        INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    public static <T> void sendTo(T packet, ServerPlayer player) {
        INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    public static void sendToAll(SyncDayPacket packet, ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            sendToPlayer(packet, player);
        }
    }

    public static void sendToPlayer(SyncPopulationPacket packet, ServerPlayer player) {
        INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    public static void sendToAll(SyncPopulationPacket packet, ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            sendToPlayer(packet, player);
        }
    }

    public static void sendToPlayer(Object packet, ServerPlayer player) {
        INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
    
    /**
     * 发送HUD数据同步包给指定玩家
     */
    public static void sendHUDDataToPlayer(int currentDay, int worldPopulation, String cityName, double cityFunds, int cityPopulation, ServerPlayer player) {
        // 获取玩家权限级别（使用玩家名）
        CityPermissionManager permManager = CityPermissionManager.getInstance();
        CityPermissionManager.PermissionLevel level = permManager.getPlayerPermissionLevel(player.serverLevel(), player);

        // 获取创造模式状态
        boolean creativeMode = ServerConfig.isCreativeModeEnabled();

        HudSyncState nextState = new HudSyncState(
                currentDay,
                worldPopulation,
                Objects.requireNonNullElse(cityName, ""),
                cityFunds,
                cityPopulation,
                level.getLevel(),
                creativeMode
        );
        HudSyncState previousState = LAST_HUD_SYNC.put(player.getUUID(), nextState);
        if (nextState.equals(previousState)) {
            return;
        }

        SyncHUDDataPacket packet = new SyncHUDDataPacket(
                nextState.currentDay(),
                nextState.worldPopulation(),
                nextState.cityName(),
                nextState.cityFunds(),
                nextState.cityPopulation(),
                nextState.permissionLevel(),
                nextState.creativeMode()
        );
        sendToPlayer(packet, player);
    }

    /**
     * 发送HUD数据同步包给所有玩家（为每个玩家单独计算权限）
     */
    public static void sendHUDDataToAll(int currentDay, int worldPopulation, String cityName, double cityFunds, int cityPopulation, ServerLevel level) {
        CityPermissionManager permManager = CityPermissionManager.getInstance();

        // 获取创造模式状态
        boolean creativeMode = ServerConfig.isCreativeModeEnabled();

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            // 为每个玩家单独计算权限（使用玩家名）
            CityPermissionManager.PermissionLevel permLevel = permManager.getPlayerPermissionLevel(player.serverLevel(), player);
            SyncHUDDataPacket packet = new SyncHUDDataPacket(currentDay, worldPopulation, cityName, cityFunds, cityPopulation, permLevel.getLevel(), creativeMode);
            sendToPlayer(packet, player);
        }
    }
    
    /**
     * 同步单个城市的HUD数据给所有相关玩家（市长和官员）
     */
    public static void syncCityHUDData(UUID cityId, ServerLevel level) {
        CityData cityData = CityData.get(level);
        CityData.CityInfo city = cityData.getCity(cityId);
        if (city != null) {
            // 获取当前天数
            SimukraftWorldData worldData = SimukraftWorldData.get(level);
            int currentDay = worldData.getCurrentDay();

            // 获取世界人口
            PopulationData populationData = PopulationData.get(level);
            int worldPopulation = populationData.getPopulation();

            // 发送给市长
            ServerPlayer mayor = level.getServer().getPlayerList().getPlayer(city.getMayorId());
            if (mayor != null) {
                sendHUDDataToPlayer(
                        currentDay,
                        worldPopulation,
                        city.getCityName(),
                        city.getFunds(),
                        city.getCitizenIds().size(),
                        mayor
                );
            }

            // 发送给所有官员（共用资金和NPC信息）
            for (String officialName : city.getOfficials()) {
                ServerPlayer official = level.getServer().getPlayerList().getPlayerByName(officialName);
                if (official != null) {
                    sendHUDDataToPlayer(
                            currentDay,
                            worldPopulation,
                            city.getCityName(),
                            city.getFunds(),
                            city.getCitizenIds().size(),
                            official
                    );
                }
            }
        }
    }
    
    /**
     * 发送NPC删除同步包给指定玩家
     */
    public static void sendToPlayer(SyncNPCDeletePacket packet, ServerPlayer player) {
        INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    /**
     * 发送NPC删除同步包给所有玩家
     */
    public static void sendToAll(SyncNPCDeletePacket packet, ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            sendToPlayer(packet, player);
        }
    }

    public static void clearHUDSyncState(UUID playerId) {
        if (playerId != null) {
            LAST_HUD_SYNC.remove(playerId);
        }
    }

    public static void clearAllHUDSyncState() {
        LAST_HUD_SYNC.clear();
    }

    private record HudSyncState(int currentDay, int worldPopulation, String cityName, double cityFunds,
                                int cityPopulation, int permissionLevel, boolean creativeMode) {
    }


    /**
     * 发送数据包给所有玩家（通用方法）
     */
    public static void sendToAll(Object packet, ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            sendToPlayer(packet, player);
        }
    }

    /**
     * 发送数据包到服务器
     */
    public static void sendToServer(Object packet) {
        INSTANCE.sendToServer(packet);
    }


    public static void broadcastAllCityChunks(net.minecraft.server.MinecraftServer server) {
        com.xiaoliang.simukraft.world.CityChunkData cityChunkData =
                com.xiaoliang.simukraft.world.CityChunkData.get(server.overworld());
        java.util.Map<UUID, java.util.Set<Long>> allChunks = new java.util.HashMap<>();
        for (java.util.Map.Entry<UUID, java.util.Set<Long>> entry : cityChunkData.getAllCityChunks().entrySet()) {
            allChunks.put(entry.getKey(), new java.util.HashSet<>(entry.getValue()));
        }
        SyncAllCityChunksPacket packet = new SyncAllCityChunksPacket(allChunks);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendToPlayer(packet, player);
        }
    }

    public static void broadcastAllCityCores(net.minecraft.server.MinecraftServer server) {
        CityData cityData = CityData.get(server.overworld());
        java.util.Map<UUID, SyncAllCityCoresPacket.CoreInfo> cores = new java.util.HashMap<>();
        for (CityData.CityInfo city : cityData.getAllCities()) {
            if (city.getCityCorePos() != null) {
                cores.put(city.getCityId(), new SyncAllCityCoresPacket.CoreInfo(
                        city.getCityCorePos(), city.getCityName()));
            }
        }
        Simukraft.LOGGER.debug("[SimuKraft] broadcastAllCityCores: sending {} city cores to {} players", cores.size(), server.getPlayerList().getPlayers().size());
        SyncAllCityCoresPacket packet = new SyncAllCityCoresPacket(cores);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendToPlayer(packet, player);
        }
    }
}
