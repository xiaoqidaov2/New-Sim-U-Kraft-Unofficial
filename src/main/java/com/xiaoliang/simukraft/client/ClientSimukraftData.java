package com.xiaoliang.simukraft.client;

import com.xiaoliang.simukraft.world.CityPermissionManager;

public class ClientSimukraftData {
    // 基础数据
    private static int currentDay = 1;
    private static int currentPopulation = 0;
    
    // 城市数据缓存
    private static String currentCityName = "";
    private static double currentCityFunds = 0.0;
    private static int currentCityPopulation = 0;
    
    // 权限数据缓存
    private static CityPermissionManager.PermissionLevel playerPermissionLevel = CityPermissionManager.PermissionLevel.NONE;
    private static boolean canManageCity = false;

    // 创造模式缓存
    private static boolean creativeMode = false;

    public static void setCurrentDay(int day) {
        currentDay = day;
    }

    public static int getCurrentDay() {
        return currentDay;
    }

    public static void setCurrentPopulation(int population) {
        currentPopulation = population;
    }

    public static int getCurrentPopulation() {
        return currentPopulation;
    }

    // 城市数据设置方法
    public static void setCurrentCityData(String cityName, double funds, int population) {
        currentCityName = cityName != null ? cityName : "";
        currentCityFunds = funds;
        currentCityPopulation = population;
    }

    public static void setCurrentCityName(String cityName) {
        currentCityName = cityName != null ? cityName : "";
    }

    public static String getCurrentCityName() {
        return currentCityName;
    }

    public static void setCurrentCityFunds(double funds) {
        currentCityFunds = funds;
    }

    public static double getCurrentCityFunds() {
        return currentCityFunds;
    }

    public static void setCurrentCityPopulation(int population) {
        currentCityPopulation = population;
    }

    public static int getCurrentCityPopulation() {
        return currentCityPopulation;
    }

    // 重置城市数据
    public static void resetCityData() {
        currentCityName = "";
        currentCityFunds = 0.0;
        currentCityPopulation = 0;
        playerPermissionLevel = CityPermissionManager.PermissionLevel.NONE;
        canManageCity = false;
    }

    public static void resetAllClientState() {
        currentDay = 1;
        currentPopulation = 0;
        creativeMode = false;
        resetCityData();
    }
    
    // 权限数据设置方法
    public static void setPlayerPermissionLevel(CityPermissionManager.PermissionLevel level) {
        playerPermissionLevel = level;
        canManageCity = level.isAtLeast(CityPermissionManager.PermissionLevel.OFFICIAL);
    }
    
    public static CityPermissionManager.PermissionLevel getPlayerPermissionLevel() {
        return playerPermissionLevel;
    }
    
    public static boolean canManageCity() {
        return canManageCity;
    }
    
    public static boolean isMayor() {
        return playerPermissionLevel == CityPermissionManager.PermissionLevel.MAYOR;
    }
    
    public static boolean isOfficial() {
        return playerPermissionLevel == CityPermissionManager.PermissionLevel.OFFICIAL;
    }
    
    public static boolean isMayorOrOfficial() {
        return playerPermissionLevel.isAtLeast(CityPermissionManager.PermissionLevel.OFFICIAL);
    }

    // 创造模式方法
    public static void setCreativeMode(boolean enabled) {
        creativeMode = enabled;
    }

    public static boolean isCreativeMode() {
        return creativeMode;
    }
}
