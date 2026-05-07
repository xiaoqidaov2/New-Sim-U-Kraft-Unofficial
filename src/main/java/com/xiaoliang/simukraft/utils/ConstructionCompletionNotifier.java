package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.notification.MessageCategory;
import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Objects;

public class ConstructionCompletionNotifier {

    public static void notifyConstructionCompletion(CustomEntity npc, String buildingFileName) {
        if (npc.level() instanceof ServerLevel serverLevel) {
            // 获取建筑的游戏内显示名称
            String displayBuildingName = getBuildingDisplayName(buildingFileName);

            // 创建提示消息
            Component npcName = npc.getCustomName() != null
                    ? npc.getCustomName()
                    : Component.literal(Objects.requireNonNull(npc.getFullName()));
            Component messageText = Component.translatable("message.simukraft.construction.completed", npcName, displayBuildingName);

            // 发送建筑完成通知到城市群组
            CityMessageUtils.sendToCityGroup(
                    serverLevel.getServer(), npc.getCityId(),
                    messageText, MessageCategory.CONSTRUCTION);

            // 播放建设完成音效给市长
            CityData cityData = CityData.get(serverLevel);
            CityData.CityInfo cityInfo = cityData.getCity(npc.getCityId());
            if (cityInfo != null) {
                java.util.UUID mayorId = cityInfo.getMayorId();
                if (mayorId != null) {
                    ServerPlayer mayor = serverLevel.getServer().getPlayerList().getPlayer(mayorId);
                    if (mayor != null) {
                        SoundEvent sound = Objects.requireNonNull(ModSoundEvents.CONSTRUCTION_COMPLETE.get());
                        mayor.playNotifySound(sound, SoundSource.PLAYERS, 1.0F, 1.0F);
                    }
                }
            }
        }
    }
    
    private static String getBuildingDisplayName(String fileName) {
        // 从文件名中提取建筑名称（去掉.sk扩展名）
        String buildingName = fileName.replace(".sk", "");
        
        // 尝试从所有类别中查找建筑信息
        String[] categories = {"residential", "commercial", "industry", "public", "other"};
        
        for (String category : categories) {
            // 首先尝试精确匹配文件�?
            BuildingDataManager.BuildingInfo info = BuildingDataManager.getBuildingInfo(category, fileName);
            if (info != null) {
                // 返回游戏内显示的名称
                return info.getName();
            }
            
            // 如果精确匹配失败，尝试匹配去掉扩展名的文件名
            info = BuildingDataManager.getBuildingInfo(category, buildingName);
            if (info != null) {
                // 返回游戏内显示的名称
                return info.getName();
            }
            
            // 最后尝试遍历所有建筑进行匹�?
            for (BuildingDataManager.BuildingInfo building : BuildingDataManager.getBuildingsByCategory(category)) {
                if (building.getFileName().equals(fileName) || 
                    building.getFileName().replace(".sk", "").equals(buildingName)) {
                    return building.getName();
                }
            }
        }
        
        // 如果找不到建筑信息，返回文件名（去掉扩展名）
        return buildingName;
    }
}
