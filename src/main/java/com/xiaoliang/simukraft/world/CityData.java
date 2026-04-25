package com.xiaoliang.simukraft.world;

import com.xiaoliang.simukraft.network.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import com.xiaoliang.simukraft.event.CityDataChangedEvent;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class CityData extends SavedData {
    private static final String DATA_NAME = "simukraft_city_data";
    private final Map<UUID, CityInfo> cities = new HashMap<>();
    private final Map<String, UUID> playerCityMap = new HashMap<>(); // playerName -> cityUUID

    private static boolean isGeneratedMayorPlaceholder(String playerName, UUID playerId) {
        if (playerName == null || playerName.isBlank() || playerId == null) {
            return true;
        }

        String uuidPrefix = playerId.toString().substring(0, 8);
        return playerName.startsWith("Player_") || uuidPrefix.equals(playerName);
    }

    private static boolean isUsableStoredPlayerName(String playerName, UUID playerId) {
        return !isGeneratedMayorPlaceholder(playerName, playerId);
    }

    public static class CityInfo {
        private final UUID cityId;
        private String cityName;
        private final UUID mayorId;
        private String mayorName;
        private final List<UUID> citizenIds = new ArrayList<>();
        private final BlockPos cityCorePos;
        private double funds;
        private int cityLevel = 0; // 城市等级，初始为0级（拓荒者）
        
        // 官员列表：存储官员的玩家名称
        private final List<String> officials = new ArrayList<>();

        // 保留原来的构造函数，添加默认市长名称
        public CityInfo(UUID cityId, String cityName, UUID mayorId, BlockPos cityCorePos) {
            this.cityId = cityId;
            this.cityName = cityName;
            this.mayorId = mayorId;
            this.mayorName = mayorId.toString().substring(0, 8); // 使用UUID的前8位作为默认市长名称
            this.cityCorePos = cityCorePos;
            this.funds = 20.0; // 初始资金20元
        }
        
        // 添加新的构造函数，支持自定义市长名称
        public CityInfo(UUID cityId, String cityName, UUID mayorId, String mayorName, BlockPos cityCorePos) {
            this.cityId = cityId;
            this.cityName = cityName;
            this.mayorId = mayorId;
            this.mayorName = mayorName;
            this.cityCorePos = cityCorePos;
            this.funds = 20.0; // 初始资金20元
        }
        
        // 官员管理方法
        public List<String> getOfficials() {
            return new ArrayList<>(officials);
        }

        public boolean addOfficial(String playerName) {
            if (!officials.contains(playerName) && !mayorName.equals(playerName)) {
                officials.add(playerName);
                return true;
            }
            return false;
        }

        public boolean removeOfficial(String playerName) {
            return officials.remove(playerName);
        }

        public boolean isOfficial(String playerName) {
            return officials.contains(playerName);
        }

        public boolean isMayor(UUID playerId) {
            return mayorId.equals(playerId);
        }

        public boolean isMayor(String playerName) {
            return mayorName.equals(playerName);
        }

        public boolean canManageCity(UUID playerId) {
            return isMayor(playerId);
        }

        public boolean canManageCity(String playerName) {
            return isMayor(playerName) || isOfficial(playerName);
        }

        public UUID getCityId() {
            return cityId;
        }

        public String getCityName() {
            return cityName;
        }

        public void setCityName(String cityName) {
            this.cityName = cityName;
        }

        public UUID getMayorId() {
            return mayorId;
        }
        
        public String getMayorName() {
            return mayorName;
        }
        
        public void setMayorName(String mayorName) {
            this.mayorName = mayorName;
        }

        public List<UUID> getCitizenIds() {
            return new ArrayList<>(citizenIds);
        }

        public void addCitizen(UUID citizenId) {
            if (!citizenIds.contains(citizenId)) {
                citizenIds.add(citizenId);
            }
        }

        public void removeCitizen(UUID citizenId) {
            citizenIds.remove(citizenId);
        }

        public BlockPos getCityCorePos() {
            return cityCorePos;
        }

        public double getFunds() {
            return funds;
        }

        public void setFunds(double funds) {
            this.funds = funds;
        }
        
        public int getCityLevel() {
            return cityLevel;
        }
        
        public void setCityLevel(int cityLevel) {
            this.cityLevel = cityLevel;
        }

        public CompoundTag serialize() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("cityId", Objects.requireNonNull(cityId));
            tag.putString("cityName", Objects.requireNonNull(cityName));
            tag.putUUID("mayorId", Objects.requireNonNull(mayorId));
            tag.putString("mayorName", Objects.requireNonNull(mayorName));
            tag.putLong("cityCorePos", cityCorePos.asLong());
            tag.putDouble("funds", funds);
            tag.putInt("cityLevel", cityLevel);
            
            ListTag citizens = new ListTag();
            for (UUID citizenId : citizenIds) {
                CompoundTag citizenTag = new CompoundTag();
                citizenTag.putUUID("id", Objects.requireNonNull(citizenId));
                citizens.add(citizenTag);
            }
            tag.put("citizens", citizens);
            
            // 序列化官员列表（使用玩家名）
            ListTag officialsTag = new ListTag();
            for (String officialName : officials) {
                CompoundTag officialTag = new CompoundTag();
                officialTag.putString("name", Objects.requireNonNull(officialName));
                officialsTag.add(officialTag);
            }
            tag.put("officials_v2", officialsTag);
            
            return tag;
        }

        public static CityInfo deserialize(CompoundTag tag) {
            UUID cityId = tag.getUUID("cityId");
            String cityName = tag.getString("cityName");
            UUID mayorId = tag.getUUID("mayorId");
            
            // 兼容旧数据：mayorName 字段可能不存在
            String mayorName;
            if (tag.contains("mayorName", Tag.TAG_STRING)) {
                mayorName = tag.getString("mayorName");
                // 只有为空时才生成默认值，保持原有值（包括UUID前8位）以便与playerCityMap匹配
                if (mayorName == null || mayorName.isEmpty()) {
                    mayorName = "Player_" + mayorId.toString().substring(0, 8);
                }
            } else {
                // 旧数据没有 mayorName 字段，使用UUID前8位以便与旧playerCityMap兼容
                mayorName = mayorId.toString().substring(0, 8);
            }
            
            BlockPos cityCorePos = BlockPos.of(tag.getLong("cityCorePos"));
            
            CityInfo info = new CityInfo(cityId, cityName, mayorId, mayorName, cityCorePos);
            info.setFunds(tag.getDouble("funds"));
            info.setCityLevel(tag.getInt("cityLevel")); // 读取城市等级
            
            ListTag citizens = tag.getList("citizens", Tag.TAG_COMPOUND);
            for (Tag citizenTag : citizens) {
                UUID citizenId = ((CompoundTag)citizenTag).getUUID("id");
                info.addCitizen(citizenId);
            }
            
            // 反序列化官员列表（新版本使用玩家名）
            if (tag.contains("officials_v2", Tag.TAG_LIST)) {
                ListTag officialsTag = tag.getList("officials_v2", Tag.TAG_COMPOUND);
                for (Tag officialTag : officialsTag) {
                    String officialName = ((CompoundTag)officialTag).getString("name");
                    if (officialName != null && !officialName.isEmpty()) {
                        info.addOfficial(officialName);
                    }
                }
            } else if (tag.contains("officials", Tag.TAG_LIST)) {
                // 兼容旧数据：旧版本使用UUID，需要转换为玩家名
                // 由于无法从UUID获取玩家名，旧官员数据将被忽略
                // 玩家需要重新被添加为官员
                System.out.println("[CityData] 检测到旧版官员数据格式，已忽略。官员需要重新添加。");
            }
            
            return info;
        }
    }

    public CityData() {}

    @Override
    public void setDirty() {
        super.setDirty();
        fireCityDataChangedEvent();
    }

    private void fireCityDataChangedEvent() {
        List<CityDataChangedEvent.CitySnapshot> snapshots = new ArrayList<>();
        for (CityInfo city : cities.values()) {
            snapshots.add(new CityDataChangedEvent.CitySnapshot(
                    city.getCityId(),
                    city.getCityName(),
                    city.getMayorId(),
                    city.getOfficials()
            ));
        }
        MinecraftForge.EVENT_BUS.post(new CityDataChangedEvent(snapshots));
    }

    public static CityData get(ServerLevel level) {
        // 确保总是使用主世界的数据存储，而不是当前维度的数据存储
        ServerLevel overworld = level.getServer().getLevel(Objects.requireNonNull(Level.OVERWORLD));
        if (overworld == null) {
            overworld = level;
        }
        DimensionDataStorage storage = overworld.getDataStorage();
        return storage.computeIfAbsent(CityData::load, CityData::new, DATA_NAME);
    }

    public boolean hasCity(String playerName) {
        return getPlayerCityId(playerName) != null;
    }

    public boolean hasCity(UUID playerId) {
        return getPlayerCityId(playerId) != null;
    }

    public UUID getPlayerCityId(String playerName) {
        return getPlayerCityIdByName(playerName);
    }

    public UUID getPlayerCityId(UUID playerId) {
        if (playerId == null) {
            return null;
        }

        // 遍历所有城市，检查玩家是否是市长
        for (CityInfo city : cities.values()) {
            if (city.getMayorId().equals(playerId)) {
                return city.getCityId();
            }
        }
        return null;
    }

    public UUID getPlayerCityIdByName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        // 首先检查playerCityMap
        if (playerCityMap.containsKey(playerName)) {
            return playerCityMap.get(playerName);
        }
        // 兼容旧数据：playerCityMap 可能未包含市长名，回退扫描市长
        for (CityInfo city : cities.values()) {
            if (city.isMayor(playerName)) {
                return city.getCityId();
            }
        }
        // 遍历所有城市，检查玩家是否是官员
        for (CityInfo city : cities.values()) {
            if (city.isOfficial(playerName)) {
                return city.getCityId();
            }
        }
        return null;
    }

    public UUID refreshPlayerCityAccess(ServerPlayer player) {
        if (player == null) {
            return null;
        }

        String playerName = player.getGameProfile().getName();
        UUID playerId = player.getUUID();
        boolean changed = false;

        UUID mappedCityId = playerCityMap.get(playerName);
        if (mappedCityId != null && !cities.containsKey(mappedCityId)) {
            playerCityMap.remove(playerName);
            mappedCityId = null;
            changed = true;
        }

        UUID cityId = mappedCityId;
        if (cityId == null) {
            cityId = getPlayerCityId(playerId);
        }
        if (cityId == null) {
            cityId = getPlayerCityIdByName(playerName);
        }
        if (cityId == null) {
            if (changed) {
                setDirty();
            }
            return null;
        }

        CityInfo city = getCity(cityId);
        if (city == null) {
            if (changed) {
                setDirty();
            }
            return null;
        }

        if (!cityId.equals(playerCityMap.get(playerName))) {
            playerCityMap.put(playerName, cityId);
            changed = true;
        }

        if (city.getMayorId().equals(playerId)) {
            String previousMayorName = city.getMayorName();
            if (!playerName.equals(previousMayorName)) {
                city.setMayorName(playerName);
                changed = true;
            }

            if (previousMayorName != null
                    && !previousMayorName.equals(playerName)
                    && isGeneratedMayorPlaceholder(previousMayorName, playerId)
                    && cityId.equals(playerCityMap.get(previousMayorName))) {
                playerCityMap.remove(previousMayorName);
                changed = true;
            }
        }

        if (changed) {
            setDirty();
        }
        return cityId;
    }

    public CityInfo getCity(UUID cityId) {
        return cities.get(cityId);
    }

    public Collection<CityInfo> getAllCities() {
        return cities.values();
    }

    public CityInfo createCity(String playerName, UUID playerId, String cityName, BlockPos cityCorePos, ServerLevel level) {
        UUID cityId = UUID.randomUUID();
        CityInfo cityInfo = new CityInfo(cityId, cityName, playerId, playerName, cityCorePos);
        cities.put(cityId, cityInfo);
        playerCityMap.put(playerName, cityId);
        setDirty();
        
        // 同步HUD数据给玩家
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(Objects.requireNonNull(playerId));
        if (player != null) {
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
                    cityName,
                    cityInfo.getFunds(),
                    cityInfo.getCitizenIds().size(),
                    player
            );
        }
        
        return cityInfo;
    }
    
    // 重载方法，兼容旧代码
    public CityInfo createCity(UUID playerId, String cityName, BlockPos cityCorePos) {
        String playerName = playerId.toString().substring(0, 8);
        UUID cityId = UUID.randomUUID();
        CityInfo cityInfo = new CityInfo(cityId, cityName, playerId, playerName, cityCorePos);
        cities.put(cityId, cityInfo);
        playerCityMap.put(playerName, cityId);
        setDirty();
        return cityInfo;
    }

    public boolean addCitizenToCity(UUID cityId, UUID citizenId, ServerLevel level) {
        CityInfo city = cities.get(cityId);
        if (city != null) {
            city.addCitizen(citizenId);
            setDirty();
            // 同步HUD数据
            syncHUDDataForCity(cityId, level);
            return true;
        }
        return false;
    }

    // 重载方法，兼容旧代码
    public boolean addCitizenToCity(UUID cityId, UUID citizenId) {
        CityInfo city = cities.get(cityId);
        if (city != null) {
            city.addCitizen(citizenId);
            setDirty();
            return true;
        }
        return false;
    }

    public boolean removeCitizenFromCity(UUID cityId, UUID citizenId, ServerLevel level) {
        CityInfo city = cities.get(cityId);
        if (city != null) {
            city.removeCitizen(citizenId);
            setDirty();
            // 同步HUD数据
            syncHUDDataForCity(cityId, level);
            return true;
        }
        return false;
    }

    // 重载方法，兼容旧代码
    public boolean removeCitizenFromCity(UUID cityId, UUID citizenId) {
        CityInfo city = cities.get(cityId);
        if (city != null) {
            city.removeCitizen(citizenId);
            setDirty();
            return true;
        }
        return false;
    }

    public boolean isMayorOfCity(UUID playerId, UUID cityId) {
        CityInfo city = cities.get(cityId);
        return city != null && city.getMayorId().equals(playerId);
    }
    
    // 官员管理方法
    public boolean addOfficialToCity(UUID cityId, String playerName, ServerLevel level) {
        return addOfficialToCity(cityId, playerName, null, level);
    }
    
    /**
     * 添加官员到城市（带UUID版本）
     * @param cityId 城市ID
     * @param playerName 玩家名称
     * @param playerId 玩家UUID（如果已知）
     * @param level 服务器世界
     * @return 是否成功
     */
    public boolean addOfficialToCity(UUID cityId, String playerName, UUID playerId, ServerLevel level) {
        CityInfo city = cities.get(cityId);
        if (city != null) {
            boolean result = city.addOfficial(playerName);
            if (result) {
                // 将官员添加到playerCityMap，使其能够正常访问城市功能
                playerCityMap.put(playerName, cityId);
                setDirty();
                
                // 使用新的通知服务替代旧的聊天系统
                if (level != null && playerId != null) {
                    com.xiaoliang.simukraft.notification.MessageNotification notification = new com.xiaoliang.simukraft.notification.MessageNotification(
                        city.getCityName(), 
                        "OFFICIAL_INVITATION", 
                        "notify.title.official_added", 
                        "notify.content.official_added", 
                        playerId, 
                        com.xiaoliang.simukraft.notification.MessageCategory.OFFICIAL
                    );
                    notification.setRelatedEntityId(cityId);
                    notification.setRelatedEntityType("CITY");
                    notification.putMetadata("city_name", city.getCityName());
                    notification.putMetadata("player_name", playerName);
                    
                    com.xiaoliang.simukraft.notification.NotificationServiceManager.getService().sendNotification(notification);
                }
            }
            return result;
        }
        return false;
    }

    public boolean removeOfficialFromCity(UUID cityId, String playerName) {
        CityInfo city = cities.get(cityId);
        if (city != null) {
            boolean result = city.removeOfficial(playerName);
            if (result) {
                // 从playerCityMap中移除官员
                playerCityMap.remove(playerName);
                setDirty();
            }
            return result;
        }
        return false;
    }

    public boolean isOfficialOfCity(String playerName, UUID cityId) {
        CityInfo city = cities.get(cityId);
        return city != null && city.isOfficial(playerName);
    }

    public boolean canManageCity(String playerName, UUID cityId) {
        CityInfo city = cities.get(cityId);
        return city != null && city.canManageCity(playerName);
    }

    public List<String> getCityOfficials(UUID cityId) {
        CityInfo city = cities.get(cityId);
        if (city != null) {
            return city.getOfficials();
        }
        return new ArrayList<>();
    }

    public List<CityInfo> getCitiesByMayor(UUID mayorId) {
        List<CityInfo> result = new ArrayList<>();
        for (CityInfo city : cities.values()) {
            if (city.getMayorId().equals(mayorId)) {
                result.add(city);
            }
        }
        return result;
    }
    
    /**
     * 根据城市核心位置获取城市信息
     */
    public CityInfo getCityByCorePos(BlockPos corePos) {
        for (CityInfo city : cities.values()) {
            if (city.getCityCorePos().equals(corePos)) {
                return city;
            }
        }
        return null;
    }

    public double getPlayerCityFunds(String playerName) {
        UUID cityId = getPlayerCityId(playerName);
        if (cityId != null) {
            CityInfo city = getCity(cityId);
            if (city != null) {
                return city.getFunds();
            }
        }
        return 0.0;
    }

    public double getPlayerCityFunds(UUID playerId) {
        UUID cityId = getPlayerCityId(playerId);
        if (cityId != null) {
            CityInfo city = getCity(cityId);
            if (city != null) {
                return city.getFunds();
            }
        }
        return 0.0;
    }

    @SuppressWarnings("unused")
    private ServerLevel getServerLevel(UUID playerId) {
        // 由于无法直接从UUID获取ServerLevel，我们返回主世界
        // 实际使用中，这个方法可能需要调整
        return null;
    }

    private void syncHUDDataForCity(UUID cityId, ServerLevel level) {
        CityInfo city = getCity(cityId);
        if (city != null) {
            MinecraftServer server = level.getServer();
            if (server != null) {
                // 获取当前天数
                SimukraftWorldData worldData = SimukraftWorldData.get(level);
                int currentDay = worldData.getCurrentDay();
                
                // 获取世界人口
                PopulationData populationData = PopulationData.get(level);
                int worldPopulation = populationData.getPopulation();
                
                // 发送给市长
                ServerPlayer mayor = server.getPlayerList().getPlayer(Objects.requireNonNull(city.getMayorId()));
                if (mayor != null) {
                    NetworkManager.sendHUDDataToPlayer(
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
                    ServerPlayer official = server.getPlayerList().getPlayerByName(Objects.requireNonNull(officialName));
                    if (official != null) {
                        NetworkManager.sendHUDDataToPlayer(
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
    }

    /**
     * 同步城市HUD数据给城市市长和官员
     */
    public void syncCityHUDData(UUID cityId, ServerLevel level) {
        CityInfo city = getCity(cityId);
        if (city != null) {
            // 获取当前天数
            SimukraftWorldData worldData = SimukraftWorldData.get(level);
            int currentDay = worldData.getCurrentDay();
            
            // 获取世界人口
            PopulationData populationData = PopulationData.get(level);
            int worldPopulation = populationData.getPopulation();
            
            MinecraftServer server = level.getServer();
            if (server != null) {
                // 发送给市长
                ServerPlayer mayor = server.getPlayerList().getPlayer(Objects.requireNonNull(city.getMayorId()));
                if (mayor != null) {
                    NetworkManager.sendHUDDataToPlayer(
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
                    ServerPlayer official = server.getPlayerList().getPlayerByName(Objects.requireNonNull(officialName));
                    if (official != null) {
                        NetworkManager.sendHUDDataToPlayer(
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
    }

    public boolean setPlayerCityFunds(String playerName, double funds) {
        UUID cityId = getPlayerCityId(playerName);
        if (cityId != null) {
            CityInfo city = getCity(cityId);
            if (city != null) {
                // 使用BigDecimal进行四舍五入，保留2位小数
                BigDecimal bd = new BigDecimal(funds);
                bd = bd.setScale(2, RoundingMode.HALF_UP);
                city.setFunds(bd.doubleValue());
                setDirty();
                return true;
            }
        }
        return false;
    }

    public boolean setPlayerCityFunds(UUID playerId, double funds) {
        UUID cityId = getPlayerCityId(playerId);
        if (cityId != null) {
            CityInfo city = getCity(cityId);
            if (city != null) {
                BigDecimal bd = new BigDecimal(funds);
                bd = bd.setScale(2, RoundingMode.HALF_UP);
                city.setFunds(bd.doubleValue());
                setDirty();
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unused")
    private ServerLevel getOverworld(UUID cityId) {
        CityInfo city = getCity(cityId);
        if (city != null && city.getMayorId() != null) {
            // 实际使用中，我们需要从游戏中获取服务器实例
            // 这里简化处理，返回null
            return null;
        }
        return null;
    }

    public String getPlayerCityName(String playerName) {
        UUID cityId = getPlayerCityId(playerName);
        if (cityId != null) {
            CityInfo city = getCity(cityId);
            if (city != null) {
                return city.getCityName();
            }
        }
        return "";
    }

    public String getPlayerCityName(UUID playerId) {
        UUID cityId = getPlayerCityId(playerId);
        if (cityId != null) {
            CityInfo city = getCity(cityId);
            if (city != null) {
                return city.getCityName();
            }
        }
        return "";
    }
    
    /**
     * 删除城市
     * @param cityId 城市UUID
     * @param level 服务器世界
     * @return 是否删除成功
     */
    public boolean deleteCity(UUID cityId, ServerLevel level) {
        CityInfo city = cities.remove(cityId);
        if (city != null) {
            // 从玩家城市映射中移除
            playerCityMap.entrySet().removeIf(entry -> entry.getValue().equals(cityId));
            
            // 从城市区块数据中移除该城市的所有区块
            CityChunkData cityChunkData = CityChunkData.get(level);
            cityChunkData.removeCityChunks(cityId);
            
            setDirty();
            return true;
        }
        return false;
    }

    @Override
    public CompoundTag save(@Nonnull CompoundTag tag) {
        ListTag citiesList = new ListTag();
        for (CityInfo city : cities.values()) {
            citiesList.add(city.serialize());
        }
        tag.put("cities", citiesList);

        ListTag playerCityList = new ListTag();
        for (Map.Entry<String, UUID> entry : playerCityMap.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("playerName", Objects.requireNonNull(entry.getKey()));
            entryTag.putUUID("cityId", Objects.requireNonNull(entry.getValue()));
            playerCityList.add(entryTag);
        }
        tag.put("playerCityMap", playerCityList);

        return tag;
    }

    public static CityData load(CompoundTag tag) {
        CityData data = new CityData();

        // 第一步：先加载所有城市信息
        ListTag citiesList = tag.getList("cities", Tag.TAG_COMPOUND);
        for (Tag cityTag : citiesList) {
            CityInfo city = CityInfo.deserialize((CompoundTag) cityTag);
            data.cities.put(city.getCityId(), city);
        }

        // 第二步：加载玩家城市映射
        ListTag playerCityList = tag.getList("playerCityMap", Tag.TAG_COMPOUND);
        for (Tag entryTag : playerCityList) {
            CompoundTag entryCompound = (CompoundTag) entryTag;
            
            // 新格式：使用玩家名
            if (entryCompound.contains("playerName", Tag.TAG_STRING)) {
                String playerName = entryCompound.getString("playerName");
                UUID cityId = entryCompound.getUUID("cityId");
                data.playerCityMap.put(playerName, cityId);
            }
            // 旧格式兼容：使用玩家UUID，需要转换为玩家名
            else if (entryCompound.contains("playerId", Tag.TAG_INT_ARRAY)) {
                UUID playerId = entryCompound.getUUID("playerId");
                UUID cityId = entryCompound.getUUID("cityId");
                
                // 首先尝试从城市信息中查找对应的玩家名
                String playerName = data.getPlayerNameFromCity(cityId, playerId);
                
                // 如果找不到或名称无效，尝试从所有城市中查找
                if (!isUsableStoredPlayerName(playerName, playerId)) {
                    String foundName = data.findPlayerNameFromCities(playerId);
                    if (isUsableStoredPlayerName(foundName, playerId)) {
                        playerName = foundName;
                    }
                }
                
                // 如果还是找不到有效名称，使用UUID前8位作为临时名称
                if (playerName == null || playerName.isEmpty()) {
                    playerName = playerId.toString().substring(0, 8);
                }
                
                data.playerCityMap.put(playerName, cityId);
                
                // 同时更新城市信息中的市长名称（如果无效）
                CityInfo city = data.cities.get(cityId);
                if (city != null && city.getMayorId().equals(playerId)) {
                    if (!isUsableStoredPlayerName(city.getMayorName(), playerId)) {
                        city.setMayorName(playerName);
                    }
                }
            }
        }

        return data;
    }

    /**
     * 从城市信息中获取玩家名称
     * 用于旧数据格式转换
     */
    private String getPlayerNameFromCity(UUID cityId, UUID playerId) {
        CityInfo city = cities.get(cityId);
        if (city != null && city.getMayorId().equals(playerId)) {
            return city.getMayorName();
        }
        return null;
    }

    /**
     * 从所有城市中查找指定UUID对应的玩家名称
     * 用于旧数据格式转换的备用查找
     */
    private String findPlayerNameFromCities(UUID playerId) {
        // 遍历所有城市，查找市长ID匹配的城市
        for (CityInfo city : cities.values()) {
            if (city.getMayorId().equals(playerId)) {
                String mayorName = city.getMayorName();
                if (isUsableStoredPlayerName(mayorName, playerId)) {
                    return mayorName;
                }
            }
        }
        return null;
    }
}
