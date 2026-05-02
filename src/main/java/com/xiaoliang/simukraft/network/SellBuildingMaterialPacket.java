package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;

import java.util.Locale;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class SellBuildingMaterialPacket {
    private final String itemName;
    private final int quantity;

    public SellBuildingMaterialPacket(String itemName, int quantity) {
        this.itemName = itemName;
        this.quantity = quantity;
    }

    public SellBuildingMaterialPacket(FriendlyByteBuf buf) {
        this.itemName = buf.readUtf();
        this.quantity = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(itemName);
        buf.writeInt(quantity);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) return;

            MinecraftServer server = player.server;
            if (server == null) return;

            // 获取物品
            Item item = server.registryAccess().registry(Registries.ITEM).flatMap(reg -> reg.getOptional(net.minecraft.resources.ResourceLocation.tryParse(itemName))).orElse(null);
            if (item == null) {
                player.sendSystemMessage(Component.translatable("message.simukraft.sell_material.unknown_item", itemName));
                return;
            }

            // 检查手持物品
            ItemStack heldItem = player.getMainHandItem();
            if (heldItem.isEmpty() || heldItem.getItem() != item || heldItem.getCount() < quantity) {
                player.sendSystemMessage(Component.translatable("message.simukraft.sell_material.not_enough_items"));
                return;
            }

            // 计算价格
            double price = getMaterialPrice(item);
            if (price == 0) {
                player.sendSystemMessage(Component.translatable("message.simukraft.sell_material.cannot_sell"));
                return;
            }

            // 获取城市数据
            CityData cityData = CityData.get(server.overworld());
            String playerName = player.getName().getString();
            double currentFunds = cityData.getPlayerCityFunds(playerName);
            double totalPrice = price;

            // 扣除物品
            heldItem.shrink(quantity);

            // 创造模式下不增加资金
            if (!ServerConfig.isCreativeModeEnabled()) {
                // 增加资金
                double newFunds = currentFunds + totalPrice;
                cityData.setPlayerCityFunds(playerName, newFunds);
                cityData.setDirty();
                player.sendSystemMessage(Component.translatable("message.simukraft.sell_material.success", String.format(Locale.US, "%.2f", totalPrice), String.format(Locale.US, "%.2f", newFunds)));
            } else {
                player.sendSystemMessage(Component.translatable("message.simukraft.sell_material.success_creative", String.format(Locale.US, "%.2f", totalPrice)));
            }
        });
        context.get().setPacketHandled(true);
    }

    private double getMaterialPrice(Item item) {
        // 建材价格表（一组64个）
        if (item == Items.OAK_PLANKS) return 1.54;     // 橡木木板一组价格
        if (item == Items.OAK_LOG) return 6.56;       // 橡木原木一组价格
        if (item == Items.COBBLESTONE) return 0.50;   // 圆石一组价格
        if (item == Items.STONE) return 0.78;         // 石头一组价格
        if (item == Items.GLASS) return 1.54;          // 普通玻璃一组价格
        if (item == Items.WHITE_WOOL) return 0.98;    // 白色羊毛一组价格
        if (item == Items.BRICKS) return 2.43;        // 红色砖块一组价格
        if (item == Items.STONE_BRICKS) return 3.98;  // 石砖一组价格
        if (item == Items.OAK_FENCE) return 1.43;     // 橡木栅栏一组价格
        return 0; // 不是可出售的建材
    }
}