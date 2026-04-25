package com.xiaoliang.simukraft.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 城市权限管理器
 * 统一管理玩家对城市的权限检查，只有市长和官员两种身份
 */
public class CityPermissionManager {

    private static CityPermissionManager instance;

    private CityPermissionManager() {}

    public static CityPermissionManager getInstance() {
        if (instance == null) {
            instance = new CityPermissionManager();
        }
        return instance;
    }

    /**
     * 权限级别枚举
     * 只有三种状态：无权限、官员、市长
     */
    public enum PermissionLevel {
        NONE(0),        // 无权限
        OFFICIAL(1),    // 官员
        MAYOR(2);       // 市长

        private final int level;

        PermissionLevel(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public boolean isAtLeast(PermissionLevel other) {
            return this.level >= other.level;
        }
    }

    /**
     * 获取玩家在指定城市的权限级别（使用玩家名）
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @param cityId 城市UUID
     * @return 权限级别
     */
    public PermissionLevel getPermissionLevel(ServerLevel level, String playerName, UUID cityId) {
        if (playerName == null || cityId == null) {
            return PermissionLevel.NONE;
        }

        CityData cityData = CityData.get(level);
        CityData.CityInfo city = cityData.getCity(cityId);

        if (city == null) {
            return PermissionLevel.NONE;
        }

        if (city.isMayor(playerName)) {
            return PermissionLevel.MAYOR;
        }

        if (city.isOfficial(playerName)) {
            return PermissionLevel.OFFICIAL;
        }

        return PermissionLevel.NONE;
    }

    /**
     * 获取玩家在指定城市的权限级别（使用UUID，仅用于市长检查）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 权限级别
     */
    public PermissionLevel getPermissionLevel(ServerLevel level, UUID playerId, UUID cityId) {
        if (playerId == null || cityId == null) {
            return PermissionLevel.NONE;
        }

        CityData cityData = CityData.get(level);
        CityData.CityInfo city = cityData.getCity(cityId);

        if (city == null) {
            return PermissionLevel.NONE;
        }

        // 只检查市长（使用UUID）
        if (city.isMayor(playerId)) {
            return PermissionLevel.MAYOR;
        }

        return PermissionLevel.NONE;
    }

    /**
     * 获取玩家所在城市的权限级别（自动查找玩家所属城市）
     * @param level 服务器世界
     * @param player 玩家对象
     * @return 权限级别，如果玩家不在任何城市返回NONE
     */
    public PermissionLevel getPlayerPermissionLevel(ServerLevel level, ServerPlayer player) {
        if (player == null) {
            return PermissionLevel.NONE;
        }

        String playerName = player.getName().getString();
        CityData cityData = CityData.get(level);
        UUID cityId = cityData.refreshPlayerCityAccess(player);

        if (cityId == null) {
            return PermissionLevel.NONE;
        }

        return getPermissionLevel(level, playerName, cityId);
    }

    /**
     * 获取玩家所在城市的权限级别（使用UUID）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @return 权限级别，如果玩家不在任何城市返回NONE
     */
    public PermissionLevel getPlayerPermissionLevel(ServerLevel level, UUID playerId) {
        if (playerId == null) {
            return PermissionLevel.NONE;
        }

        CityData cityData = CityData.get(level);
        UUID cityId = cityData.getPlayerCityId(playerId);

        if (cityId == null) {
            return PermissionLevel.NONE;
        }

        return getPermissionLevel(level, playerId, cityId);
    }

    /**
     * 检查玩家是否可以管理城市（市长或官员）
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @param cityId 城市UUID
     * @return 是否可以管理
     */
    public boolean canManageCity(ServerLevel level, String playerName, UUID cityId) {
        PermissionLevel perm = getPermissionLevel(level, playerName, cityId);
        return perm.isAtLeast(PermissionLevel.OFFICIAL);
    }

    /**
     * 检查玩家是否可以管理城市（市长或官员）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否可以管理
     */
    public boolean canManageCity(ServerLevel level, UUID playerId, UUID cityId) {
        PermissionLevel perm = getPermissionLevel(level, playerId, cityId);
        return perm.isAtLeast(PermissionLevel.OFFICIAL);
    }

    /**
     * 检查玩家是否可以管理城市（自动查找玩家所属城市）
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @return 是否可以管理
     */
    public boolean canManageCity(ServerLevel level, String playerName) {
        CityData cityData = CityData.get(level);
        UUID cityId = cityData.getPlayerCityIdByName(playerName);
        if (cityId == null) {
            return false;
        }
        return canManageCity(level, playerName, cityId);
    }

    /**
     * 检查玩家是否可以管理城市（自动查找玩家所属城市）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @return 是否可以管理
     */
    public boolean canManageCity(ServerLevel level, UUID playerId) {
        PermissionLevel perm = getPlayerPermissionLevel(level, playerId);
        return perm.isAtLeast(PermissionLevel.OFFICIAL);
    }

    /**
     * 检查玩家是否可以管理NPC（雇佣、解雇等）
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @param cityId 城市UUID
     * @return 是否可以管理NPC
     */
    public boolean canManageNPCs(ServerLevel level, String playerName, UUID cityId) {
        // 官员和市长都可以管理NPC
        return canManageCity(level, playerName, cityId);
    }

    /**
     * 检查玩家是否可以管理NPC（雇佣、解雇等）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否可以管理NPC
     */
    public boolean canManageNPCs(ServerLevel level, UUID playerId, UUID cityId) {
        // 官员和市长都可以管理NPC
        return canManageCity(level, playerId, cityId);
    }

    /**
     * 检查玩家是否可以管理NPC（自动查找玩家所属城市）
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @return 是否可以管理NPC
     */
    public boolean canManageNPCs(ServerLevel level, String playerName) {
        return canManageCity(level, playerName);
    }

    /**
     * 检查玩家是否可以管理NPC（自动查找玩家所属城市）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @return 是否可以管理NPC
     */
    public boolean canManageNPCs(ServerLevel level, UUID playerId) {
        return canManageCity(level, playerId);
    }

    /**
     * 检查玩家是否可以使用城市资金
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @param cityId 城市UUID
     * @return 是否可以使用资金
     */
    public boolean canUseCityFunds(ServerLevel level, String playerName, UUID cityId) {
        // 官员和市长都可以使用城市资金
        return canManageCity(level, playerName, cityId);
    }

    /**
     * 检查玩家是否可以使用城市资金
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否可以使用资金
     */
    public boolean canUseCityFunds(ServerLevel level, UUID playerId, UUID cityId) {
        // 官员和市长都可以使用城市资金
        return canManageCity(level, playerId, cityId);
    }

    /**
     * 检查玩家是否可以使用城市资金（自动查找玩家所属城市）
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @return 是否可以使用资金
     */
    public boolean canUseCityFunds(ServerLevel level, String playerName) {
        return canManageCity(level, playerName);
    }

    /**
     * 检查玩家是否可以使用城市资金（自动查找玩家所属城市）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @return 是否可以使用资金
     */
    public boolean canUseCityFunds(ServerLevel level, UUID playerId) {
        return canManageCity(level, playerId);
    }

    /**
     * 检查玩家是否可以删除城市（仅市长）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否可以删除城市
     */
    public boolean canDeleteCity(ServerLevel level, UUID playerId, UUID cityId) {
        PermissionLevel perm = getPermissionLevel(level, playerId, cityId);
        return perm == PermissionLevel.MAYOR;
    }

    /**
     * 检查玩家是否可以管理官员（添加/移除官员，仅市长）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否可以管理官员
     */
    public boolean canManageOfficials(ServerLevel level, UUID playerId, UUID cityId) {
        PermissionLevel perm = getPermissionLevel(level, playerId, cityId);
        return perm == PermissionLevel.MAYOR;
    }

    /**
     * 检查玩家是否可以查看城市NPC列表
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @param cityId 城市UUID
     * @return 是否可以查看NPC列表
     */
    public boolean canViewNPCList(ServerLevel level, String playerName, UUID cityId) {
        // 官员及以上可以查看NPC列表
        return canManageCity(level, playerName, cityId);
    }

    /**
     * 检查玩家是否可以查看城市NPC列表
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否可以查看NPC列表
     */
    public boolean canViewNPCList(ServerLevel level, UUID playerId, UUID cityId) {
        // 官员及以上可以查看NPC列表
        return canManageCity(level, playerId, cityId);
    }

    /**
     * 获取玩家所在城市ID（通过玩家名称）
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @return 城市ID，如果不在任何城市返回null
     */
    @Nullable
    public UUID getPlayerCityId(ServerLevel level, String playerName) {
        CityData cityData = CityData.get(level);
        return cityData.getPlayerCityIdByName(playerName);
    }

    /**
     * 获取玩家所在城市ID（通过UUID）
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @return 城市ID，如果不在任何城市返回null
     */
    @Nullable
    public UUID getPlayerCityId(ServerLevel level, UUID playerId) {
        CityData cityData = CityData.get(level);
        return cityData.getPlayerCityId(playerId);
    }

    /**
     * 便捷方法：检查玩家是否是市长
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @param cityId 城市UUID
     * @return 是否是市长
     */
    public boolean isMayor(ServerLevel level, String playerName, UUID cityId) {
        return getPermissionLevel(level, playerName, cityId) == PermissionLevel.MAYOR;
    }

    /**
     * 便捷方法：检查玩家是否是市长
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否是市长
     */
    public boolean isMayor(ServerLevel level, UUID playerId, UUID cityId) {
        return getPermissionLevel(level, playerId, cityId) == PermissionLevel.MAYOR;
    }

    /**
     * 便捷方法：检查玩家是否是官员
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @param cityId 城市UUID
     * @return 是否是官员
     */
    public boolean isOfficial(ServerLevel level, String playerName, UUID cityId) {
        return getPermissionLevel(level, playerName, cityId) == PermissionLevel.OFFICIAL;
    }

    /**
     * 便捷方法：检查玩家是否是官员
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否是官员
     */
    public boolean isOfficial(ServerLevel level, UUID playerId, UUID cityId) {
        return getPermissionLevel(level, playerId, cityId) == PermissionLevel.OFFICIAL;
    }

    /**
     * 便捷方法：检查玩家是否是市长或官员
     * @param level 服务器世界
     * @param playerName 玩家名称
     * @param cityId 城市UUID
     * @return 是否是市长或官员
     */
    public boolean isMayorOrOfficial(ServerLevel level, String playerName, UUID cityId) {
        PermissionLevel perm = getPermissionLevel(level, playerName, cityId);
        return perm == PermissionLevel.MAYOR || perm == PermissionLevel.OFFICIAL;
    }

    /**
     * 便捷方法：检查玩家是否是市长或官员
     * @param level 服务器世界
     * @param playerId 玩家UUID
     * @param cityId 城市UUID
     * @return 是否是市长或官员
     */
    public boolean isMayorOrOfficial(ServerLevel level, UUID playerId, UUID cityId) {
        PermissionLevel perm = getPermissionLevel(level, playerId, cityId);
        return perm == PermissionLevel.MAYOR || perm == PermissionLevel.OFFICIAL;
    }
}
