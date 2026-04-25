package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.CityPermissionManager;
import com.xiaoliang.simukraft.world.PopulationData;
import com.xiaoliang.simukraft.world.SimukraftWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public class MoneyManager {

    // 创造模式下的无限金额
    public static final double INFINITE_MONEY = Double.MAX_VALUE;

    /**
     * 将金额四舍五入到两位小数
     */
    private static double roundToTwoDecimals(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 检查是否处于创造模式（无限金钱）
     */
    private static boolean isCreativeMode() {
        return ServerConfig.isCreativeModeEnabled();
    }

    public static double getMoney(ServerPlayer player) {
        // 创造模式下返回无限金额
        if (isCreativeMode()) {
            return INFINITE_MONEY;
        }
        System.out.println("[MoneyManager] 获取玩家资金，玩家: " + player.getName().getString());
        CityData cityData = CityData.get(player.serverLevel());
        String playerName = player.getName().getString();
        double money = cityData.getPlayerCityFunds(playerName);
        System.out.println("[MoneyManager] 玩家 " + player.getName().getString() + " 当前资金: " + money + " 元");
        return money;
    }

    public static boolean hasEnoughMoney(ServerPlayer player, double amount) {
        // 创造模式下永远有足够金钱
        if (isCreativeMode()) {
            return true;
        }
        double money = getMoney(player);
        boolean hasEnough = money >= amount;
        System.out.println("[MoneyManager] 玩家 " + player.getName().getString() + " 是否有足够资金 (" + amount + " 元): " + hasEnough + " (当前: " + money + " 元)");
        return hasEnough;
    }

    public static boolean deductMoney(ServerPlayer player, double amount) {
        // 创造模式下不扣除金钱，直接返回成功
        if (isCreativeMode()) {
            System.out.println("[MoneyManager] 创造模式：跳过扣除 " + amount + " 元");
            return true;
        }
        // 修复：对金额进行四舍五入，保留两位小数
        amount = roundToTwoDecimals(amount);
        System.out.println("[MoneyManager] 尝试扣除玩家资金，玩家: " + player.getName().getString() + "，金额: " + amount + " 元");
        CityData cityData = CityData.get(player.serverLevel());
        String playerName = player.getName().getString();
        double currentFunds = cityData.getPlayerCityFunds(playerName);
        System.out.println("[MoneyManager] 扣除前资金: " + currentFunds + " 元");

        if (currentFunds < amount) {
            System.out.println("[MoneyManager] 扣除失败: 资金不足，需要 " + amount + " 元，当前: " + currentFunds + " 元");
            return false;
        }

        // 修复：对计算结果进行四舍五入，保留两位小数
        double newFunds = roundToTwoDecimals(currentFunds - amount);
        System.out.println("[MoneyManager] 扣除后资金: " + newFunds + " 元");
        cityData.setPlayerCityFunds(playerName, newFunds);
        System.out.println("[MoneyManager] 扣除成功: 玩家 " + player.getName().getString() + " 资金减少 " + amount + " 元");

        // 同步HUD数据
        syncHUDData(player);

        return true;
    }

    public static void addMoney(ServerPlayer player, double amount) {
        // 创造模式下不增加金钱，保持无限
        if (isCreativeMode()) {
            System.out.println("[MoneyManager] 创造模式：跳过增加 " + amount + " 元");
            return;
        }
        // 修复：对金额进行四舍五入，保留两位小数
        amount = roundToTwoDecimals(amount);
        System.out.println("[MoneyManager] 尝试增加玩家资金，玩家: " + player.getName().getString() + "，金额: " + amount + " 元");
        CityData cityData = CityData.get(player.serverLevel());
        String playerName = player.getName().getString();
        double currentFunds = cityData.getPlayerCityFunds(playerName);
        System.out.println("[MoneyManager] 增加前资金: " + currentFunds + " 元");
        // 修复：对计算结果进行四舍五入，保留两位小数
        double newFunds = roundToTwoDecimals(currentFunds + amount);
        System.out.println("[MoneyManager] 增加后资金: " + newFunds + " 元");
        cityData.setPlayerCityFunds(playerName, newFunds);
        System.out.println("[MoneyManager] 增加成功: 玩家 " + player.getName().getString() + " 资金增加 " + amount + " 元");

        // 同步HUD数据
        syncHUDData(player);
    }
    
    /**
     * 同步HUD数据给玩家
     */
    private static void syncHUDData(ServerPlayer player) {
        System.out.println("[MoneyManager] 同步HUD数据给玩家: " + player.getName().getString());
        ServerLevel level = player.serverLevel();
        
        // 使用权限管理器获取玩家城市ID（支持官员）
        CityPermissionManager permManager = CityPermissionManager.getInstance();
        UUID cityId = permManager.getPlayerCityId(level, player.getUUID());
        
        if (cityId != null) {
            CityData cityData = CityData.get(level);
            CityData.CityInfo city = cityData.getCity(cityId);
            if (city != null) {
                // 获取当前天数
                SimukraftWorldData worldData = SimukraftWorldData.get(level);
                int currentDay = worldData.getCurrentDay();
                
                // 获取世界人口
                PopulationData populationData = PopulationData.get(level);
                int worldPopulation = populationData.getPopulation();
                
                // 发送HUD数据同步包给玩家
                NetworkManager.sendHUDDataToPlayer(
                        currentDay,
                        worldPopulation,
                        city.getCityName(),
                        city.getFunds(),
                        city.getCitizenIds().size(),
                        player
                );
                System.out.println("[MoneyManager] HUD数据同步完成: " + player.getName().getString());
            }
        }
    }
}