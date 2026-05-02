package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 请求同步库存数据包
 * 客户端 -> 服务器
 */
@SuppressWarnings("null")
public class RequestStockSyncPacket {
    private final BlockPos pos;
    private final String buildingFileName;

    public RequestStockSyncPacket(BlockPos pos, String buildingFileName) {
        this.pos = pos;
        this.buildingFileName = buildingFileName;
    }

    public static void encode(RequestStockSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.buildingFileName);
    }

    public static RequestStockSyncPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String buildingFileName = buf.readUtf();
        return new RequestStockSyncPacket(pos, buildingFileName);
    }

    public static void handle(RequestStockSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            MinecraftServer server = player.getServer();
            if (server == null) return;

            // 统一使用 CommercialHiredData 发送库存数据
            sendUnifiedStock(player, packet.pos, server, packet.buildingFileName);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 发送统一库存数据（所有商店类型都使用 CommercialHiredData）
     * 对于需要原料的商品，实时计算箱子中的原料库存
     */
    private static void sendUnifiedStock(ServerPlayer player, BlockPos pos, MinecraftServer server, String buildingFileName) {
        // 加载数据
        CommercialHiredData.loadStockData(server);
        ServerLevel level = server.overworld();
        if (level == null) return;

        // 获取建筑配置
        CommercialBuildingConfig config = CommercialBuildingManager.getConfig(buildingFileName);
        CommercialWorkHandler.ensureStockInitialized(pos, level, config);

        Map<String, CommercialHiredData.StockInfo> stockMap = CommercialHiredData.getAllStockAtPos(pos);

        Map<String, SyncBuildingMaterialStockPacket.StockData> syncMap = new HashMap<>();

        // 同步已有库存记录的商品
        if (stockMap != null) {
            for (Map.Entry<String, CommercialHiredData.StockInfo> entry : stockMap.entrySet()) {
                CommercialHiredData.StockInfo stock = entry.getValue();
                int currentStock = stock.getCurrentStock();

                // 如果需要原料，实时计算箱子中的原料库存
                if (config != null) {
                    CommercialBuildingConfig.TradeItem trade = config.getTradeByItemId(stock.getItemId());
                    if (trade != null && trade.requiresMaterial()) {
                        currentStock = calculateStockFromMaterials(level, pos, trade);
                    }
                }

                syncMap.put(entry.getKey(), new SyncBuildingMaterialStockPacket.StockData(
                    stock.getItemId(),
                    currentStock,
                    stock.getMaxStock() > 0 ? stock.getMaxStock() : currentStock,
                    64, // 默认补货64
                    stock.getLastRestockTime()
                ));
            }
        }

        // 同步配置中的交易物品（可能没有库存记录的）
        if (config != null) {
            for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
                if (!syncMap.containsKey(trade.getItemId())) {
                    int currentStock = 0;
                    if (trade.requiresMaterial()) {
                        currentStock = calculateStockFromMaterials(level, pos, trade);
                    } else if (stockMap != null) {
                        CommercialHiredData.StockInfo stockInfo = stockMap.get(trade.getItemId());
                        if (stockInfo != null) {
                            currentStock = stockInfo.getCurrentStock();
                        }
                    }
                    syncMap.put(trade.getItemId(), new SyncBuildingMaterialStockPacket.StockData(
                        trade.getItemId(),
                        currentStock,
                        trade.getMaxStock(),
                        trade.getRestockAmount(),
                        level.getDayTime()
                    ));
                }
            }
        }

        if (syncMap.isEmpty()) return;

        SyncBuildingMaterialStockPacket packet = new SyncBuildingMaterialStockPacket(
            pos,
            syncMap,
            level.getDayTime()
        );

        NetworkManager.INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    /**
     * 从箱子中的原料计算可售库存
     */
    private static int calculateStockFromMaterials(ServerLevel level, BlockPos buildingPos,
                                                   CommercialBuildingConfig.TradeItem trade) {
        if (!trade.requiresMaterial()) {
            return Integer.MAX_VALUE;
        }

        String requiredMaterial = trade.getRequiredMaterial();
        int requiredCount = trade.getRequiredMaterialCount();

        ItemStack materialTemplate = parseItemStack(requiredMaterial);
        if (materialTemplate.isEmpty()) {
            return 0;
        }

        // 计算箱子中的原料总数
        int totalMaterials = countMaterialsInNearbyContainers(level, buildingPos, materialTemplate);

        // 计算可以生产多少商品
        return totalMaterials / requiredCount;
    }

    /**
     * 计算附近箱子中的原料数量
     * 搜索范围：以 centerPos 为中心，水平半径5格，垂直半径2格
     */
    private static int countMaterialsInNearbyContainers(ServerLevel level, BlockPos centerPos, ItemStack itemTemplate) {
        if (level == null || centerPos == null || itemTemplate.isEmpty()) {
            return 0;
        }

        return ContainerUtils.executeOnMainThread(level, () -> {
            int totalCount = 0;
            // 搜索范围：水平半径5格，垂直半径2格
            for (int dx = -5; dx <= 5; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        BlockPos checkPos = centerPos.offset(dx, dy, dz);
                        if (ContainerUtils.isContainer(level, checkPos)) {
                            totalCount += ContainerUtils.countItem(level, checkPos, itemTemplate);
                        }
                    }
                }
            }
            return totalCount;
        });
    }

    /**
     * 解析物品ID为ItemStack
     */
    private static ItemStack parseItemStack(String itemId) {
        try {
            net.minecraft.resources.ResourceLocation resourceLocation =
                net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (resourceLocation == null) return ItemStack.EMPTY;

            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(resourceLocation);
            if (item == null) return ItemStack.EMPTY;

            return new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }
}
