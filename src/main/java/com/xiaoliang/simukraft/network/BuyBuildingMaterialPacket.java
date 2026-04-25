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
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@SuppressWarnings({"null", "unused"})
public class BuyBuildingMaterialPacket {
    private final Map<String, Integer> materials;
    private final double totalPrice;

    public BuyBuildingMaterialPacket(Map<String, Integer> materials, double totalPrice) {
        this.materials = materials;
        this.totalPrice = totalPrice;
    }

    public BuyBuildingMaterialPacket(FriendlyByteBuf buf) {
        int size = buf.readInt();
        this.materials = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String itemName = buf.readUtf();
            int quantity = buf.readInt();
            materials.put(itemName, quantity);
        }
        this.totalPrice = buf.readDouble();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(materials.size());
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeInt(entry.getValue());
        }
        buf.writeDouble(totalPrice);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) return;

            MinecraftServer server = player.server;
            if (server == null) return;

            // 获取城市数据并检查资金
            CityData cityData = CityData.get(server.overworld());
            String playerName = player.getName().getString();
            double currentFunds = cityData.getPlayerCityFunds(playerName);

            if (currentFunds < totalPrice) {
                player.sendSystemMessage(Component.translatable("message.simukraft.building_material.insufficient_funds",
                    String.format(Locale.US, "%.2f", currentFunds),
                    String.format(Locale.US, "%.2f", totalPrice)));
                return;
            }

            // 检查背包空间
            boolean hasEnoughSpace = true;
            for (Map.Entry<String, Integer> entry : materials.entrySet()) {
                String itemName = entry.getKey();
                int quantity = entry.getValue();
                Item item = server.registryAccess().registry(Registries.ITEM).flatMap(reg -> reg.getOptional(net.minecraft.resources.ResourceLocation.tryParse(itemName))).orElse(null);
                if (item == null) {
                    player.sendSystemMessage(Component.translatable("message.simukraft.building_material.unknown_item", itemName));
                    hasEnoughSpace = false;
                    break;
                }

                int totalItems = quantity * 64;
                int remaining = totalItems;
                while (remaining > 0) {
                    int stackSize = Math.min(64, remaining);
                    ItemStack stack = new ItemStack(item, stackSize);
                    // 只检查背包空间，不实际添加物品
                    if (player.getInventory().getFreeSlot() < 0) {
                        hasEnoughSpace = false;
                        break;
                    }
                    remaining -= stackSize;
                }

                if (!hasEnoughSpace) break;
            }

            if (!hasEnoughSpace) {
                player.sendSystemMessage(Component.translatable("message.simukraft.buy_material.no_space"));
                return;
            }

            // 创造模式下跳过资金扣除
            if (!ServerConfig.isCreativeModeEnabled()) {
                // 扣除资金
                double newFunds = currentFunds - totalPrice;
                cityData.setPlayerCityFunds(playerName, newFunds);
                cityData.setDirty();
                player.sendSystemMessage(Component.translatable("message.simukraft.building_material.purchase_success", String.format(Locale.US, "%.2f", totalPrice), String.format(Locale.US, "%.2f", newFunds)));
            } else {
                player.sendSystemMessage(Component.translatable("message.simukraft.building_material.purchase_success_creative"));
            }

            // 添加物品到背包
            for (Map.Entry<String, Integer> entry : materials.entrySet()) {
                String itemName = entry.getKey();
                int quantity = entry.getValue();
                Item item = server.registryAccess().registry(Registries.ITEM).flatMap(reg -> reg.getOptional(net.minecraft.resources.ResourceLocation.tryParse(itemName))).orElse(null);
                if (item == null) continue;

                int totalItems = quantity * 64;
                int remaining = totalItems;
                while (remaining > 0) {
                    int stackSize = Math.min(64, remaining);
                    ItemStack stack = new ItemStack(item, stackSize);
                    player.getInventory().add(stack);
                    remaining -= stackSize;
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}