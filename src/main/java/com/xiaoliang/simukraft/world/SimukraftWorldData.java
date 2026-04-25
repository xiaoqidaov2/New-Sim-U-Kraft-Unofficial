package com.xiaoliang.simukraft.world;

import com.xiaoliang.simukraft.network.NetworkManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import javax.annotation.Nonnull;
import java.util.UUID;

public class SimukraftWorldData extends SavedData {
    private static final String DATA_NAME = "simukraft_world_data";
    private int currentDay = 1; // 从第1天开始

    public int getCurrentDay() {
        return currentDay;
    }

    public void incrementDay(ServerLevel level) {
        currentDay++;
        setDirty(); // 标记数据已更改
        // 同步天数变化到所有玩家的HUD
        syncHUDData(level);
    }

    // 重载方法，兼容旧代码
    public void incrementDay() {
        currentDay++;
        setDirty(); // 标记数据已更改
    }

    public void setDay(int day, ServerLevel level) {
        currentDay = day;
        setDirty();
        // 同步天数变化到所有玩家的HUD
        syncHUDData(level);
    }

    // 重载方法，兼容旧代码
    public void setDay(int day) {
        currentDay = day;
        setDirty();
    }
    
    private void syncHUDData(ServerLevel level) {
        // 获取世界人口
        PopulationData populationData = PopulationData.get(level);
        int worldPopulation = populationData.getPopulation();
        
        // 发送HUD数据同步包给所有玩家
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            // 获取玩家城市信息
            String cityName = "";
            double cityFunds = 0.0;
            int cityPopulation = 0;
            
            CityData cityData = CityData.get(level);
            UUID cityId = cityData.refreshPlayerCityAccess(player);
            if (cityId != null) {
                CityData.CityInfo city = cityData.getCity(cityId);
                if (city != null) {
                    cityName = city.getCityName();
                    cityFunds = city.getFunds();
                    cityPopulation = city.getCitizenIds().size();
                }
            }
            
            // 发送HUD数据同步包
            NetworkManager.sendHUDDataToPlayer(
                    currentDay,
                    worldPopulation,
                    cityName,
                    cityFunds,
                    cityPopulation,
                    player
            );
        }
    }

    @Override
    public CompoundTag save(@Nonnull CompoundTag tag) {
        tag.putInt("currentDay", currentDay);
        return tag;
    }

    public static SimukraftWorldData load(CompoundTag tag) {
        SimukraftWorldData data = new SimukraftWorldData();
        data.currentDay = tag.getInt("currentDay");
        return data;
    }

    public static SimukraftWorldData get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(
                SimukraftWorldData::load,
                SimukraftWorldData::new,
                DATA_NAME
        );
    }
}
