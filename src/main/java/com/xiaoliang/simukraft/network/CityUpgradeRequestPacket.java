package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityUpgradeRequestPacket {
    private final BlockPos cityCorePos;
    private final int targetLevel;

    public CityUpgradeRequestPacket(BlockPos cityCorePos, int targetLevel) {
        this.cityCorePos = cityCorePos;
        this.targetLevel = targetLevel;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.cityCorePos);
        buf.writeInt(this.targetLevel);
    }

    public CityUpgradeRequestPacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
        this.targetLevel = buf.readInt();
    }

    public static void handle(CityUpgradeRequestPacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isServer()) {
                handleServerSide(message, ctx);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleServerSide(CityUpgradeRequestPacket message, Supplier<NetworkEvent.Context> ctx) {
        // 服务端处理城市升级请求
        net.minecraft.server.level.ServerPlayer player = ctx.get().getSender();
        if (player == null || player.getServer() == null) {
            ctx.get().setPacketHandled(true);
            return;
        }

        net.minecraft.server.level.ServerLevel level = player.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (level == null) {
            sendResult(player, false, "failed", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        // 调用城市升级逻辑
        com.xiaoliang.simukraft.world.CityData cityData = com.xiaoliang.simukraft.world.CityData.get(level);
        com.xiaoliang.simukraft.world.CityUpgradeManager upgradeManager = com.xiaoliang.simukraft.world.CityUpgradeManager.getInstance();

        // 获取城市信息
        com.xiaoliang.simukraft.world.CityData.CityInfo cityInfo = cityData.getCityByCorePos(message.cityCorePos);
        if (cityInfo == null) {
            sendResult(player, false, "city_not_found", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        // 检查玩家是否有权限管理城市（市长或官员，使用玩家名）
        String playerName = player.getName().getString();
        if (!cityInfo.canManageCity(playerName)) {
            sendResult(player, false, "no_permission", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        // 只允许升级到下一级，避免客户端与服务端状态不同步时出现跳级请求
        if (message.targetLevel != cityInfo.getCityLevel() + 1) {
            sendResult(player, false, "invalid_target", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        if (!upgradeManager.canUpgrade(cityInfo, message.targetLevel)) {
            sendResult(player, false, "not_upgradeable", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        com.xiaoliang.simukraft.world.CityUpgradeManager.CityUpgrade upgrade = upgradeManager.getUpgrade(message.targetLevel);
        if (upgrade == null) {
            sendResult(player, false, "not_upgradeable", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        com.xiaoliang.simukraft.world.CityUpgradeManager.Requirements requirements = upgrade.requirements();

        // 逐项返回失败原因，避免界面直接关闭但玩家不知道哪里不满足
        if (!checkItems(player, requirements)) {
            sendResult(player, false, "insufficient_items", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        if (cityInfo.getFunds() < requirements.funds()) {
            sendResult(player, false, "insufficient_funds", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        if (cityInfo.getCitizenIds().size() < requirements.population()) {
            sendResult(player, false, "insufficient_population", message.targetLevel);
            ctx.get().setPacketHandled(true);
            return;
        }

        // 扣除物品
        removeItems(player, requirements);

        // 扣除资金
        cityInfo.setFunds(cityInfo.getFunds() - requirements.funds());

        // 升级城市
        cityInfo.setCityLevel(message.targetLevel);
        cityData.setDirty();

        // 同步HUD数据
        NetworkManager.syncCityHUDData(cityInfo.getCityId(), level);

        // 根据升级等级选择toast类型（0~4使用w1，5~8使用w2，9~11使用g1）
        String toastType;
        if (message.targetLevel <= 4) {
            toastType = "w1";
        } else if (message.targetLevel <= 8) {
            toastType = "w2";
        } else {
            toastType = "g1";
        }

        // 发送升级成功toast通知，包含升级等级
        ShowToastPacket.sendToPlayer(player, toastType, message.targetLevel);
        sendResult(player, true, "success", message.targetLevel);
        ctx.get().setPacketHandled(true);
    }

    private static void sendResult(net.minecraft.server.level.ServerPlayer player, boolean success, String resultCode, int targetLevel) {
        NetworkManager.sendToPlayer(new CityUpgradeResultPacket(success, resultCode, targetLevel), player);
    }
    
    /**
     * 检查玩家背包中是否有足够的物品
     */
    private static boolean checkItems(net.minecraft.server.level.ServerPlayer player, com.xiaoliang.simukraft.world.CityUpgradeManager.Requirements requirements) {
        if (requirements.wood() > 0) {
            if (countItems(player, net.minecraft.world.item.Items.OAK_LOG) < requirements.wood()) {
                return false;
            }
        }
        if (requirements.cobblestone() > 0) {
            if (countItems(player, net.minecraft.world.item.Items.COBBLESTONE) < requirements.cobblestone()) {
                return false;
            }
        }
        if (requirements.ironIngot() > 0) {
            if (countItems(player, net.minecraft.world.item.Items.IRON_INGOT) < requirements.ironIngot()) {
                return false;
            }
        }
        if (requirements.goldIngot() > 0) {
            if (countItems(player, net.minecraft.world.item.Items.GOLD_INGOT) < requirements.goldIngot()) {
                return false;
            }
        }
        if (requirements.diamond() > 0) {
            if (countItems(player, net.minecraft.world.item.Items.DIAMOND) < requirements.diamond()) {
                return false;
            }
        }
        if (requirements.lapisLazuli() > 0) {
            return countItems(player, net.minecraft.world.item.Items.LAPIS_LAZULI) >= requirements.lapisLazuli();
        }
        return true;
    }
    
    /**
     * 从玩家背包中扣除物品
     */
    private static void removeItems(net.minecraft.server.level.ServerPlayer player, com.xiaoliang.simukraft.world.CityUpgradeManager.Requirements requirements) {
        if (requirements.wood() > 0) {
            removeItems(player, net.minecraft.world.item.Items.OAK_LOG, requirements.wood());
        }
        if (requirements.cobblestone() > 0) {
            removeItems(player, net.minecraft.world.item.Items.COBBLESTONE, requirements.cobblestone());
        }
        if (requirements.ironIngot() > 0) {
            removeItems(player, net.minecraft.world.item.Items.IRON_INGOT, requirements.ironIngot());
        }
        if (requirements.goldIngot() > 0) {
            removeItems(player, net.minecraft.world.item.Items.GOLD_INGOT, requirements.goldIngot());
        }
        if (requirements.diamond() > 0) {
            removeItems(player, net.minecraft.world.item.Items.DIAMOND, requirements.diamond());
        }
        if (requirements.lapisLazuli() > 0) {
            removeItems(player, net.minecraft.world.item.Items.LAPIS_LAZULI, requirements.lapisLazuli());
        }
    }
    
    /**
     * 计算玩家背包中指定物品的数量
     */
    private static int countItems(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }
    
    /**
     * 从玩家背包中扣除指定数量的物品
     */
    private static void removeItems(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.Item item, int amount) {
        int toRemove = amount;
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == item && toRemove > 0) {
                int removed = Math.min(toRemove, stack.getCount());
                stack.shrink(removed);
                toRemove -= removed;
                if (toRemove == 0) {
                    break;
                }
            }
        }
    }
}
