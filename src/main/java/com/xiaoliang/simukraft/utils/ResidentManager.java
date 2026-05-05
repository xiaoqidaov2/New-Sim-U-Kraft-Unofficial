package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ResidentManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String RESIDENCE_DIR = "residence";
    private static final String SK_FILE_EXTENSION = ".sk";
    private static final String HOMELESS_FILE = "homeless_npcs.sk";

    // 灞呮皯鍒嗛厤闂撮殧锛堟父鎴忓埢锛夛紝姣?绉掓鏌ヤ竴娆?
    // 5 绉?= 100 娓告垙鍒?

    // 娴佹氮NPC鍒楄〃锛堝唴瀛樹腑缁存姢锛?
    private static final Set<String> homelessNPCs = new HashSet<>();

    // 鎬ц兘浼樺寲锛氭坊鍔犵紦瀛橀伩鍏嶉绻佹枃浠惰鍙?
    private static final Set<String> residenceCache = new HashSet<>();
    private static long lastCacheUpdate = 0;
    private static final long CACHE_UPDATE_INTERVAL = 5000; // 5绉掔紦瀛樻洿鏂伴棿闅?

    private static ServerLevel getOverworld(MinecraftServer server) {
        return server.getLevel(Objects.requireNonNull(net.minecraft.world.level.Level.OVERWORLD));
    }

    private static Path getWorldPath(MinecraftServer server) {
        Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
        return worldPath.toAbsolutePath().normalize();
    }

    /**
     * NPC淇℃伅绫伙紝鐢ㄤ簬鎺掑簭
     */
    private static class NPCInfo {
        final UUID uuid;
        final String name;
        final int npcId;
        final boolean hasResidence;

        NPCInfo(UUID uuid, String name, int npcId, boolean hasResidence) {
            this.uuid = uuid;
            this.name = name;
            this.npcId = npcId;
            this.hasResidence = hasResidence;
        }
    }

    /**
     * 褰撴柊鐨勪綇瀹呮帶鍒剁鏀剧疆鏃讹紝浼樺厛鍒嗛厤缁欏悓鍩庡競涓璶pcid鏈€灏忕殑NPC
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param controlBoxPos 鎺у埗绠变綅缃?
     * @param cityId 鍩庡競UUID
     * @return 鏄惁鎴愬姛鍒嗛厤浣忓畢
     */
    public static boolean assignResidenceToCityNPCs(MinecraftServer server, BlockPos controlBoxPos, UUID cityId) {
        try {
            LOGGER.debug("[ResidentManager] 尝试为城市 {} 的 NPC 分配住宅，位置 {}", cityId, controlBoxPos);

            // 1. 棣栧厛妫€鏌ヨ繖涓綇瀹呮槸鍚︿负绌猴紙resident瀛楁涓嶅瓨鍦ㄦ垨涓虹┖锛?
            if (!isResidenceEmpty(server, controlBoxPos)) {
                LOGGER.debug("[ResidentManager] 住宅 {} 已有居民，跳过分配", controlBoxPos);
                return false;
            }

            // 2. 鑾峰彇璇ュ煄甯傜殑鎵€鏈塏PC
            List<NPCInfo> cityNPCs = getCityNPCs(server, cityId);
            if (cityNPCs.isEmpty()) {
                LOGGER.debug("[ResidentManager] 鍩庡競 {} 娌℃湁NPC锛岄渶瑕佺敓鎴愭柊NPC", cityId);
                return false; // 鍩庡競娌℃湁NPC锛岄渶瑕佺敓鎴愭柊NPC
            }

            // 3. 杩囨护鍑烘病鏈変綇瀹呯殑NPC
            List<NPCInfo> homelessCityNPCs = cityNPCs.stream()
                    .filter(npc -> !npc.hasResidence)
                    .sorted(Comparator.comparingInt(npc -> npc.npcId)) // 鎸塶pcid浠庡皬鍒板ぇ鎺掑簭
                    .toList();

            if (homelessCityNPCs.isEmpty()) {
                LOGGER.debug("[ResidentManager] 鍩庡競 {} 鐨勬墍鏈塏PC閮芥湁浣忓畢浜嗭紝绛夊緟涓崍鐢熸垚鏂癗PC", cityId);
                // 涓嶇珛鍗崇敓鎴怤PC锛岃€屾槸鍦ㄤ腑鍗堟椂缁熶竴鐢熸垚
                return false;
            }

            // 4. 浼樺厛鍒嗛厤缁檔pcid鏈€灏忕殑NPC
            NPCInfo targetNPC = homelessCityNPCs.get(0);
            LOGGER.debug("[ResidentManager] 浼樺厛鍒嗛厤缁檔pcid鏈€灏忕殑NPC: {} (npcid={})", targetNPC.name, targetNPC.npcId);

            // 5. 鍒嗛厤浣忓畢
            boolean success = assignResidenceToNPC(server, controlBoxPos, targetNPC, cityId);
            if (success) {
                LOGGER.info("[ResidentManager] 鎴愬姛涓?{} 鍒嗛厤浣忓畢", targetNPC.name);
            }

            return success;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 为城市 NPC 分配住宅时发生错误", e);
            return false;
        }
    }

    /**
     * 鐢熸垚鏂癗PC骞跺垎閰嶅埌鎸囧畾浣忓畢
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param controlBoxPos 鎺у埗绠变綅缃?
     * @param cityId 鍩庡競UUID
     * @return 鏄惁鎴愬姛鐢熸垚骞跺垎閰?
     */
    private static boolean spawnNewNPCAndAssignResidence(MinecraftServer server, BlockPos controlBoxPos, UUID cityId) {
        try {
            LOGGER.debug("[ResidentManager] 寮€濮嬬敓鎴愭柊NPC骞跺垎閰嶅埌浣忓畢: {}", controlBoxPos);

            // 鑾峰彇涓讳笘鐣?
            ServerLevel level = getOverworld(server);

            if (level == null) {
                LOGGER.error("[ResidentManager] 无法获取主世界");
                return false;
            }

            // 鍒涘缓鏂癗PC
            com.xiaoliang.simukraft.entity.CustomEntity npc = new com.xiaoliang.simukraft.entity.CustomEntity(
                    com.xiaoliang.simukraft.init.ModEntities.CUSTOM_ENTITY.get(), level);

            // 璁剧疆NPC浣嶇疆锛堝湪浣忓畢闄勮繎锛?
            npc.setPos(controlBoxPos.getX() + 0.5, controlBoxPos.getY() + 1, controlBoxPos.getZ() + 0.5);

            // 璁剧疆NPC灞炴€?
            npc.setCityId(cityId);
            npc.initializeName();

            // 娣诲姞NPC鍒板煄甯?
            com.xiaoliang.simukraft.world.CityData cityData = com.xiaoliang.simukraft.world.CityData.get(level);
            cityData.addCitizenToCity(cityId, npc.getUUID());

            // 鐢熸垚NPC鍒颁笘鐣?
            level.addFreshEntity(npc);

            LOGGER.info("[ResidentManager] 鏂癗PC鐢熸垚鎴愬姛: {} ({})", npc.getFullName(), npc.getUUID());

            // 绛夊緟NPC鍒濆鍖栧畬鎴愬悗鍐嶅垎閰嶄綇瀹?
            // 鐢变簬NPC鐨刣ataRecorded鍦╰ick涓缃紝鎴戜滑闇€瑕佺洿鎺ュ垎閰?
            String npcName = npc.getFullName();
            UUID npcUuid = npc.getUUID();

            // 鍙戦€?鏉ュ埌姝ゅ尯鍩?鎻愮ず缁欏競闀?
            notifyMayorOfNPCArrival(server, cityId, npcName, controlBoxPos);

            // 鐩存帴鍒嗛厤浣忓畢缁欐柊NPC
            boolean assigned = assignResidenceToNPCDirectly(server, controlBoxPos, npcUuid, npcName, cityId);

            if (assigned) {
                LOGGER.info("[ResidentManager] 鎴愬姛涓烘柊NPC {} 鍒嗛厤浣忓畢: {}", npcName, controlBoxPos);
                // 鍙戦€佹秷鎭粰鍩庡競甯傞暱
                notifyMayorOfNewCitizen(server, cityId, npcName, controlBoxPos);
            } else {
                LOGGER.error("[ResidentManager] 涓烘柊NPC鍒嗛厤浣忓畢澶辫触: {}", npcName);
            }

            return assigned;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 鐢熸垚鏂癗PC骞跺垎閰嶄綇瀹呮椂鍙戠敓閿欒", e);
            return false;
        }
    }


    private static CityData getcitydata(MinecraftServer server) {
        try {
            return CityData.get(Objects.requireNonNull(getOverworld(server)));
        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 获取城市数据时发生错误", e);
            return null;
        }
    }

    /**
     * 閫氱煡鍩庡競甯傞暱鏈夋柊甯傛皯鍔犲叆
     */
    private static void notifyMayorOfNewCitizen(MinecraftServer server, UUID cityId, String npcName, BlockPos residencePos) {
        try {
            com.xiaoliang.simukraft.world.CityData cityData = getcitydata(server);
            if(cityData == null) return;
            // 鏌ユ壘鍩庡競
            for (com.xiaoliang.simukraft.world.CityData.CityInfo city : cityData.getAllCities()) {
                if (city.getCityId().equals(cityId)) {
                    // 鐩存帴浠庢湇鍔″櫒鐜╁鍒楄〃鏌ユ壘甯傞暱锛堜笉閬嶅巻缁村害锛岄伩鍏嶉噸澶嶅彂閫侊級
                    CityMessageUtils.sendToMayorViaService(server, cityId,
                            net.minecraft.network.chat.Component.translatable("notify.title.housing"),
                            net.minecraft.network.chat.Component.translatable(
                                    "message.simukraft.residence.moved_in", npcName, residencePos.getX(), residencePos.getY(), residencePos.getZ()),
                            com.xiaoliang.simukraft.notification.MessageCategory.CITIZEN);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 通知市长时发生错误", e);
        }
    }

    /**
     * 閫氱煡鍩庡競甯傞暱鏈塏PC鏉ュ埌姝ゅ尯鍩?
     */
    private static void notifyMayorOfNPCArrival(MinecraftServer server, UUID cityId, String npcName, BlockPos pos) {
        try {
            com.xiaoliang.simukraft.world.CityData cityData = getcitydata(server);
            if(cityData == null) return;
            // 鏌ユ壘鍩庡競
            for (com.xiaoliang.simukraft.world.CityData.CityInfo city : cityData.getAllCities()) {
                if (city.getCityId().equals(cityId)) {
                    CityMessageUtils.sendToMayorViaService(server, cityId,
                            net.minecraft.network.chat.Component.translatable("notify.title.npc"),
                            net.minecraft.network.chat.Component.translatable(
                                    "message.simukraft.npc.arrived", npcName),
                            com.xiaoliang.simukraft.notification.MessageCategory.CITIZEN);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 通知市长 NPC 到达时发生错误", e);
        }
    }

    /**
     * 鍦ㄤ腑鍗?2:00鐢熸垚鏂癗PC锛堝鏋滄湁绌洪棽浣忓畢涓旀墍鏈塏PC閮芥湁浣忓畢锛?
     * 杩欎釜鏂规硶鐢盨erverTickHandler鍦ㄤ腑鍗堣皟鐢?
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     */
    public static void spawnNPCAtNoon(MinecraftServer server) {
        try {
            LOGGER.debug("[ResidentManager] 涓崍妫€鏌ワ細寮€濮嬫鏌ュ悇鍩庡競鏄惁闇€瑕佺敓鎴愭柊NPC");

            // 鑾峰彇涓讳笘鐣?
            ServerLevel level = getOverworld(server);

            if (level == null) {
                LOGGER.error("[ResidentManager] 中午检查时无法获取主世界");
                return;
            }

            // 鑾峰彇鎵€鏈夊煄甯?
            CityData cityData = CityData.get(level);

            java.util.Collection<CityData.CityInfo> allCities = cityData.getAllCities();
            LOGGER.debug("[ResidentManager] 中午检查时共有 {} 个城市", allCities.size());

            // 閬嶅巻姣忎釜鍩庡競
            for (CityData.CityInfo city : allCities) {
                UUID cityId = city.getCityId();
                LOGGER.debug("[ResidentManager] 涓崍妫€鏌ワ細妫€鏌ュ煄甯?{}", cityId);

                // 鑾峰彇璇ュ煄甯傜殑鎵€鏈塏PC
                List<NPCInfo> cityNPCs = getCityNPCs(server, cityId);
                LOGGER.debug("[ResidentManager] 涓崍妫€鏌ワ細鍩庡競 {} 鏈?{} 涓狽PC", cityId, cityNPCs.size());

                // 妫€鏌ユ槸鍚︽墍鏈塏PC閮芥湁浣忓畢
                boolean allHaveResidence = true;
                for (NPCInfo npc : cityNPCs) {
                    if (!npc.hasResidence) {
                        allHaveResidence = false;
                        break;
                    }
                }

                if (!allHaveResidence) {
                    LOGGER.debug("[ResidentManager] 涓崍妫€鏌ワ細鍩庡競 {} 鏈塏PC娌℃湁浣忓畢锛屼笉鐢熸垚鏂癗PC", cityId);
                    continue;
                }

                // 鏌ユ壘璇ュ煄甯傜殑绌洪棽浣忓畢
                BlockPos availableResidence = findAvailableResidenceForCity(server, cityId);
                if (availableResidence == null) {
                    LOGGER.debug("[ResidentManager] 涓崍妫€鏌ワ細鍩庡競 {} 娌℃湁绌洪棽浣忓畢", cityId);
                    continue;
                }

                LOGGER.info("[ResidentManager] 涓崍妫€鏌ワ細鍩庡競 {} 鎵€鏈塏PC閮芥湁浣忓畢涓旀湁绌洪棽浣忓畢锛岀敓鎴愭柊NPC", cityId);

                // 鐢熸垚鏂癗PC骞跺垎閰嶄綇瀹?
                boolean success = spawnNewNPCAndAssignResidence(server, availableResidence, cityId);
                if (success) {
                    LOGGER.info("[ResidentManager] 涓崍妫€鏌ワ細鎴愬姛涓哄煄甯?{} 鐢熸垚鏂癗PC", cityId);
                } else {
                    LOGGER.error("[ResidentManager] 涓崍妫€鏌ワ細涓哄煄甯?{} 鐢熸垚鏂癗PC澶辫触", cityId);
                }
            }

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 中午生成 NPC 时发生错误", e);
        }
    }

    /**
     * 鏌ユ壘鎸囧畾鍩庡競鐨勭┖闂蹭綇瀹?
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param cityId 鍩庡競UUID
     * @return 绌洪棽浣忓畢鐨勪綅缃紝濡傛灉娌℃湁杩斿洖null
     */
    private static BlockPos findAvailableResidenceForCity(MinecraftServer server, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                return null;
            }

            // 鏌ユ壘鎵€鏈塻k鏂囦欢
            List<Path> skFiles = findSkFiles(residenceDir);

            for (Path skFile : skFiles) {
                List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
                String fileCityId = null;
                boolean isAvailable = true;

                for (String line : lines) {
                    String trimmedLine = line.trim();
                    if (trimmedLine.startsWith("cityid:")) {
                        fileCityId = trimmedLine.substring("cityid:".length()).trim();
                    } else if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                        String resident = trimmedLine.substring("resident:".length()).trim();
                        isAvailable = resident.isEmpty();
                    }
                }

                // 妫€鏌ユ槸鍚﹀睘浜庤鍩庡競涓斾负绌虹疆
                if (fileCityId != null && fileCityId.equals(cityId.toString()) && isAvailable) {
                    // 浠庢枃浠跺悕鎻愬彇浣嶇疆
                    String fileName = skFile.getFileName().toString().replace(".sk", "");
                    String[] parts = fileName.split("_");
                    if (parts.length == 3) {
                        try {
                            int x = Integer.parseInt(parts[0]);
                            int y = Integer.parseInt(parts[1]);
                            int z = Integer.parseInt(parts[2]);
                            return new BlockPos(x, y, z);
                        } catch (NumberFormatException e) {
                            LOGGER.error("[ResidentManager] 瑙ｆ瀽浣忓畢浣嶇疆澶辫触: {}", fileName);
                        }
                    }
                }
            }

            return null;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 查找空闲住宅时发生错误", e);
            return null;
        }
    }

    /**
     * 妫€鏌ユ寚瀹氫綅缃殑浣忓畢鏄惁涓虹┖锛坮esident瀛楁涓嶅瓨鍦ㄦ垨涓虹┖锛?
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param controlBoxPos 鎺у埗绠变綅缃?
     * @return 濡傛灉浣忓畢涓虹┖杩斿洖true锛屽惁鍒欒繑鍥瀎alse
     */
    private static boolean isResidenceEmpty(MinecraftServer server, BlockPos controlBoxPos) {
        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                return true; // 鐩綍涓嶅瓨鍦紝璁や负鏄┖浣忓畢
            }

            // 鏋勫缓SK鏂囦欢鍚? x_y_z.sk
            String fileName = controlBoxPos.getX() + "_" + controlBoxPos.getY() + "_" + controlBoxPos.getZ() + ".sk";
            Path skFile = residenceDir.resolve(fileName);

            if (!Files.exists(skFile)) {
                return true; // 鏂囦欢涓嶅瓨鍦紝璁や负鏄┖浣忓畢
            }

            // 璇诲彇鏂囦欢妫€鏌esident瀛楁
            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmedLine = line.trim();
                // 妫€鏌esident瀛楁锛堜絾涓嶆槸resident_uuid锛?
                if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                    String residentValue = trimmedLine.substring("resident:".length()).trim();
                    // 濡傛灉resident瀛楁鏈夊€硷紝浣忓畢涓嶄负绌?
                    return residentValue.isEmpty();
                }
            }

            // 濡傛灉娌℃湁鎵惧埌resident瀛楁锛岃涓烘槸绌轰綇瀹?
            return true;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 妫€鏌ヤ綇瀹呮槸鍚︿负绌烘椂鍙戠敓閿欒: {}", controlBoxPos, e);
            return true; // 鍑洪敊鏃跺亣璁句负绌轰綇瀹?
        }
    }

    /**
     * 鑾峰彇鍩庡競鐨勬墍鏈塏PC淇℃伅
     */
    private static List<NPCInfo> getCityNPCs(MinecraftServer server, UUID cityId) {
        List<NPCInfo> npcList = new ArrayList<>();

        try {
            // 浠嶤ityData鑾峰彇鍩庡競鐨刢itizen UUID鍒楄〃
            com.xiaoliang.simukraft.world.CityData cityData = getcitydata(server);

            if (cityData == null) {
                return npcList;
            }

            // 鏌ユ壘瀵瑰簲鐨勫煄甯?
            CityData.CityInfo cityInfo = null;
            for (CityData.CityInfo city : cityData.getAllCities()) {
                if (city.getCityId().equals(cityId)) {
                    cityInfo = city;
                    break;
                }
            }

            if (cityInfo == null) {
                LOGGER.debug("[ResidentManager] 找不到城市 {}", cityId);
                return npcList;
            }

            List<UUID> citizenIds = cityInfo.getCitizenIds();
            LOGGER.debug("[ResidentManager] 城市 {} 当前有 {} 个居民", cityId, citizenIds.size());

            // 鑾峰彇鎵€鏈変笘鐣岀殑NPC瀹炰綋
            for (ServerLevel level : server.getAllLevels()) {
                List<CustomEntity> allNPCs = level.getEntitiesOfClass(
                        CustomEntity.class,
                        new AABB(-30000000, -30000000, -30000000, 30000000, 30000000, 30000000)
                );

                for (CustomEntity npc : allNPCs) {
                    if (citizenIds.contains(npc.getUUID())) {
                        boolean hasResidence = hasResidenceAssigned(server, npc.getUUID());
                        npcList.add(new NPCInfo(
                                npc.getUUID(),
                                npc.getFullName(),
                                npc.getNpcId(),
                                hasResidence
                        ));
                        LOGGER.debug("[ResidentManager] 鎵惧埌鍩庡競NPC: {} (npcid={}, hasResidence={})", npc.getFullName(), npc.getNpcId(), hasResidence);
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 获取城市 NPC 列表时发生错误", e);
        }

        return npcList;
    }

    /**
     * 涓烘寚瀹歂PC鍒嗛厤鎸囧畾浣嶇疆鐨勪綇瀹?
     */
    private static boolean assignResidenceToNPC(MinecraftServer server, BlockPos controlBoxPos, NPCInfo npcInfo, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                Files.createDirectories(residenceDir);
            }

            // 鏋勫缓SK鏂囦欢鍚? x_y_z.sk
            String fileName = controlBoxPos.getX() + "_" + controlBoxPos.getY() + "_" + controlBoxPos.getZ() + ".sk";
            Path skFile = residenceDir.resolve(fileName);

            // 璇诲彇鐜版湁鍐呭鎴栧垱寤烘柊鏂囦欢
            List<String> lines;
            if (Files.exists(skFile)) {
                lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            } else {
                lines = new ArrayList<>();
            }

            // 鏇存柊鎴栨坊鍔爎esident鍜宺esident_uuid瀛楁
            List<String> newLines = new ArrayList<>();
            boolean residentUpdated = false;
            boolean residentUuidUpdated = false;
            String position = controlBoxPos.getX() + ", " + controlBoxPos.getY() + ", " + controlBoxPos.getZ();

            for (String line : lines) {
                String trimmedLine = line.trim();
                // 妫€鏌ユ槸鍚︿互resident:寮€澶达紙浣嗕笉鏄痳esident_uuid:锛?
                if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                    newLines.add("resident: " + npcInfo.name);
                    residentUpdated = true;
                } else if (trimmedLine.startsWith("resident_uuid:")) {
                    newLines.add("resident_uuid: " + npcInfo.uuid.toString());
                    residentUuidUpdated = true;
                } else {
                    // 淇濈暀鍘熷琛岋紝浣嗙‘淇濇病鏈夋畫鐣欑殑residentresident:杩欐牱鐨勯敊璇?
                    if (!trimmedLine.startsWith("residentresident:")) {
                        newLines.add(line);
                    }
                }
            }

            // 濡傛灉娌℃湁鎵惧埌瀛楁锛屽湪鏂囦欢鏈熬娣诲姞
            if (!residentUpdated) {
                newLines.add("resident: " + npcInfo.name);
            }
            if (!residentUuidUpdated) {
                newLines.add("resident_uuid: " + npcInfo.uuid.toString());
            }

            // 鍐欏洖鏂囦欢
            Files.write(skFile, newLines, StandardCharsets.UTF_8);

            //LOGGER.info("[ResidentManager] 宸插啓鍏ヤ綇瀹呭垎閰? {} -> {}", skFile, npcInfo.name);

            // 鍙戦€佹彁绀烘秷鎭紙浠呭競闀垮彲瑙侊級
            sendResidenceAssignmentMessage(server, cityId, npcInfo.name, position, ".");

            // 鏇存柊缂撳瓨
            residenceCache.add(npcInfo.name);

            return true;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 涓篘PC鍒嗛厤浣忓畢鏃跺彂鐢熼敊璇? {}", npcInfo.name, e);
            return false;
        }
    }

    /**
     * 鐩存帴涓烘寚瀹歂PC鍒嗛厤鎸囧畾浣嶇疆鐨勪綇瀹咃紙鐢ㄤ簬鏂癗PC鐢熸垚鏃讹級
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param controlBoxPos 鎺у埗绠变綅缃?
     * @param npcUuid NPC鐨刄UID
     * @param npcName NPC鐨勫悕瀛?
     * @param cityId 鍩庡競UUID
     * @return 鏄惁鎴愬姛鍒嗛厤浣忓畢
     */
    public static boolean assignResidenceToNPCDirectly(MinecraftServer server, BlockPos controlBoxPos, UUID npcUuid, String npcName, UUID cityId) {
        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                Files.createDirectories(residenceDir);
            }

            // 鏋勫缓SK鏂囦欢鍚? x_y_z.sk
            String fileName = controlBoxPos.getX() + "_" + controlBoxPos.getY() + "_" + controlBoxPos.getZ() + ".sk";
            Path skFile = residenceDir.resolve(fileName);

            if (!Files.exists(skFile)) {
                LOGGER.debug("[ResidentManager] SK鏂囦欢涓嶅瓨鍦? {}", skFile);
                return false;
            }

            // 璇诲彇鐜版湁鍐呭
            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);

            // 鏇存柊鎴栨坊鍔爎esident鍜宺esident_uuid瀛楁
            List<String> newLines = new ArrayList<>();
            boolean residentUpdated = false;
            boolean residentUuidUpdated = false;
            String position = controlBoxPos.getX() + ", " + controlBoxPos.getY() + ", " + controlBoxPos.getZ();

            for (String line : lines) {
                String trimmedLine = line.trim();
                // 妫€鏌ユ槸鍚︿互resident:寮€澶达紙浣嗕笉鏄痳esident_uuid:锛?
                if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                    newLines.add("resident: " + npcName);
                    residentUpdated = true;
                } else if (trimmedLine.startsWith("resident_uuid:")) {
                    newLines.add("resident_uuid: " + npcUuid.toString());
                    residentUuidUpdated = true;
                } else {
                    // 淇濈暀鍘熷琛岋紝浣嗙‘淇濇病鏈夋畫鐣欑殑residentresident:杩欐牱鐨勯敊璇?
                    if (!trimmedLine.startsWith("residentresident:")) {
                        newLines.add(line);
                    }
                }
            }

            // 濡傛灉娌℃湁鎵惧埌瀛楁锛屽湪鏂囦欢鏈熬娣诲姞
            if (!residentUpdated) {
                newLines.add("resident: " + npcName);
            }
            if (!residentUuidUpdated) {
                newLines.add("resident_uuid: " + npcUuid.toString());
            }

            // 鍐欏洖鏂囦欢
            Files.write(skFile, newLines, StandardCharsets.UTF_8);

            LOGGER.info("[ResidentManager] 鐩存帴鍒嗛厤浣忓畢鎴愬姛: {} -> {}", skFile, npcName);

            // 鍙戦€佹彁绀烘秷鎭紙浠呭競闀垮彲瑙侊級
            sendResidenceAssignmentMessage(server, cityId, npcName, position, ".");

            // 鏇存柊缂撳瓨
            residenceCache.add(npcName);

            return true;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 鐩存帴涓篘PC鍒嗛厤浣忓畢鏃跺彂鐢熼敊璇? {}", npcName, e);
            return false;
        }
    }

    /**
     * 浠庢寚瀹氫綇瀹呬腑绉婚櫎灞呮皯锛堝綋鎺у埗鐩掕鎽ф瘉鏃惰皟鐢級
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param controlBoxPos 鎺у埗鐩掍綅缃?
     * @return 鏄惁鎴愬姛绉婚櫎
     */
    public static boolean removeResidentFromResidence(MinecraftServer server, BlockPos controlBoxPos) {
        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                return false;
            }

            // 鏋勫缓SK鏂囦欢鍚? x_y_z.sk
            String fileName = controlBoxPos.getX() + "_" + controlBoxPos.getY() + "_" + controlBoxPos.getZ() + ".sk";
            Path skFile = residenceDir.resolve(fileName);

            if (!Files.exists(skFile)) {
                LOGGER.debug("[ResidentManager] 浣忓畢鏂囦欢涓嶅瓨鍦紝鏃犻渶绉婚櫎灞呮皯: {}", skFile);
                return false;
            }

            // 璇诲彇鏂囦欢鍐呭
            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            String residentName = null;
            // 鏌ユ壘灞呮皯淇℃伅
            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                    residentName = trimmedLine.substring("resident:".length()).trim();
                } else if (trimmedLine.startsWith("resident_uuid:")) {
                    String uuidStr = trimmedLine.substring("resident_uuid:".length()).trim();
                    if (!uuidStr.isEmpty()) {
                        try {
                            UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException e) {
                            LOGGER.error("[ResidentManager] 鏃犳晥鐨刄UID鏍煎紡: {}", uuidStr);
                        }
                    }
                }
            }

            // 娓呯┖resident鍜宺esident_uuid瀛楁
            List<String> newLines = new ArrayList<>();
            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                    newLines.add("resident: ");
                } else if (trimmedLine.startsWith("resident_uuid:")) {
                    newLines.add("resident_uuid: ");
                } else {
                    newLines.add(line);
                }
            }

            // 鍐欏洖鏂囦欢
            Files.write(skFile, newLines, StandardCharsets.UTF_8);

            if (residentName != null && !residentName.isEmpty()) {
                LOGGER.info("[ResidentManager] 已将居民 {} 从住宅 {} 移除，进入无家可归状态", residentName, controlBoxPos);
                // 浠庣紦瀛樹腑绉婚櫎
                residenceCache.remove(residentName);
            } else {
                LOGGER.debug("[ResidentManager] 住宅 {} 没有居民，无需移除", controlBoxPos);
            }

            return true;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 浠庝綇瀹呬腑绉婚櫎灞呮皯鏃跺彂鐢熼敊璇? {}", controlBoxPos, e);
            return false;
        }
    }

    /**
     * 涓篘PC鑷姩鍒嗛厤浣忓畢锛堟棫鏂规硶锛屼繚鐣欑敤浜庡吋瀹规€э級
     * 淇锛氱幇鍦ㄥ彧浼氬垎閰嶅悓鍩庡競鐨勪綇瀹?
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param npcName NPC鐨勫悕瀛?
     * @return 鏄惁鎴愬姛鍒嗛厤浣忓畢
     */
    public static boolean assignResidenceToNPC(MinecraftServer server, String npcName) {
        try {
            // 棣栧厛鎵惧埌NPC瀹炰綋锛岃幏鍙栧叾鍩庡競ID
            UUID npcCityId = findNPCCityId(server, npcName);
            if (npcCityId == null) {
                LOGGER.debug("[ResidentManager] 鏃犳硶鎵惧埌NPC {} 鐨勫煄甯備俊鎭紝鏃犳硶鍒嗛厤浣忓畢", npcName);
                markAsHomeless(server, npcName);
                return false;
            }

            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            // 妫€鏌esidence鐩綍鏄惁瀛樺湪
            if (!Files.exists(residenceDir)) {
                LOGGER.debug("Residence directory does not exist: {}", residenceDir);
                // 濡傛灉娌℃湁浣忓畢鐩綍锛屾爣璁颁负娴佹氮
                markAsHomeless(server, npcName);
                return false;
            }

            // 鏌ユ壘鎵€鏈塻k鏂囦欢
            List<Path> skFiles = findSkFiles(residenceDir);
            if (skFiles.isEmpty()) {
                LOGGER.debug("No sk files found in residence directory");
                // 濡傛灉娌℃湁浣忓畢鏂囦欢锛屾爣璁颁负娴佹氮
                markAsHomeless(server, npcName);
                return false;
            }

            // 鏌ユ壘璇ュ煄甯傜殑鍙敤浣忓畢锛堜慨澶嶏細鍙煡鎵惧悓鍩庡競鐨勪綇瀹咃級
            Path availableResidence = findAvailableResidenceForCity(skFiles, npcCityId);
            if (availableResidence == null) {
                LOGGER.debug("No available residence found for NPC: {} in city: {}", npcName, npcCityId);
                LOGGER.info("[ResidentManager] NPC {} 鍦ㄥ煄甯?{} 涓病鏈夊彲鐢ㄧ殑浣忓畢", npcName, npcCityId);
                // 濡傛灉娌℃湁鍙敤浣忓畢锛屾爣璁颁负娴佹氮
                markAsHomeless(server, npcName);
                return false;
            }

            // 鍒嗛厤浣忓畢缁橬PC
            boolean success = assignResidence(server, availableResidence, npcName);
            if (success) {
                // 濡傛灉鍒嗛厤鎴愬姛锛屼粠娴佹氮鍒楄〃涓Щ闄?
                removeFromHomeless(server, npcName);
                LOGGER.info("Successfully assigned residence to NPC: {} -> {}", npcName, availableResidence.getFileName());
                LOGGER.info("[ResidentManager] NPC {} assigned to residence: {} in city: {}", npcName, availableResidence.getFileName(), npcCityId);
            }

            return success;

        } catch (Exception e) {
            LOGGER.error("Failed to assign residence to NPC: " + npcName, e);
            return false;
        }
    }

    /**
     * 鏍规嵁NPC鍚嶇О鏌ユ壘NPC鐨勫煄甯侷D
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param npcName NPC鐨勫悕瀛?
     * @return 鍩庡競UUID锛屽鏋滄壘涓嶅埌杩斿洖null
     */
    private static UUID findNPCCityId(MinecraftServer server, String npcName) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                List<CustomEntity> allNPCs = level.getEntitiesOfClass(
                        CustomEntity.class,
                        new AABB(-30000000, -30000000, -30000000, 30000000, 30000000, 30000000)
                );

                for (CustomEntity npc : allNPCs) {
                    // 淇锛氳烦杩囨浜＄殑NPC
                    if (npc.isDeadOrDying()) {
                        continue;
                    }
                    if (npc.getFullName().equals(npcName)) {
                        UUID cityId = npc.getCityId();
                        if (cityId != null) {
                            LOGGER.debug("[ResidentManager] 鎵惧埌NPC {} 鐨勫煄甯侷D: {}", npcName, cityId);
                        }
                        return cityId;
                    }
                }
            }
            LOGGER.debug("[ResidentManager] 找不到 NPC {} 的实体", npcName);
        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 查找 NPC 城市 ID 时发生错误: {}", npcName, e);
        }
        return null;
    }

    /**
     * 鏌ユ壘鎸囧畾鍩庡競鐨勫彲鐢ㄤ綇瀹?
     * @param skFiles 鎵€鏈変綇瀹呮枃浠跺垪琛?
     * @param cityId 鍩庡競UUID
     * @return 鍙敤浣忓畢鐨勬枃浠惰矾寰勶紝濡傛灉娌℃湁杩斿洖null
     */
    private static Path findAvailableResidenceForCity(List<Path> skFiles, UUID cityId) {
        for (Path skFile : skFiles) {
            if (isResidenceAvailableForCity(skFile, cityId)) {
                return skFile;
            }
        }
        return null;
    }

    /**
     * 妫€鏌ヤ綇瀹呮槸鍚﹀睘浜庢寚瀹氬煄甯備笖鍙敤
     * @param skFile 浣忓畢鏂囦欢璺緞
     * @param cityId 鍩庡競UUID
     * @return 鏄惁鍙敤
     */
    private static boolean isResidenceAvailableForCity(Path skFile, UUID cityId) {
        try {
            if (!Files.exists(skFile)) {
                return false;
            }

            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            String fileCityId = null;
            boolean isAvailable = false;

            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("cityid:")) {
                    fileCityId = trimmedLine.substring("cityid:".length()).trim();
                } else if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                    String residentValue = trimmedLine.substring("resident:".length()).trim();
                    isAvailable = residentValue.isEmpty();
                }
            }

            // 妫€鏌ユ槸鍚﹀睘浜庢寚瀹氬煄甯備笖涓虹┖缃?
            return cityId.toString().equals(fileCityId) && isAvailable;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 妫€鏌ヤ綇瀹呭彲鐢ㄦ€ф椂鍙戠敓閿欒: {}", skFile, e);
            return false;
        }
    }

    /**
     * 涓烘祦娴狽PC浼樺厛鍒嗛厤浣忓畢锛堝綋鏂版帶鍒剁鏀剧疆鏃惰皟鐢級
     * 淇锛氱幇鍦ㄥ彧浼氫负娴佹氮NPC鍒嗛厤鍚屽煄甯傜殑浣忓畢
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @return 鏄惁鎴愬姛鍒嗛厤浣忓畢缁欐祦娴狽PC
     */
    public static boolean assignResidenceToHomelessNPCs(MinecraftServer server) {
        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            // 妫€鏌esidence鐩綍鏄惁瀛樺湪
            if (!Files.exists(residenceDir)) {
                return false;
            }

            // 鏌ユ壘鎵€鏈塻k鏂囦欢
            List<Path> skFiles = findSkFiles(residenceDir);
            if (skFiles.isEmpty()) {
                return false;
            }

            // 浼樺厛涓烘祦娴狽PC鍒嗛厤浣忓畢锛堝彧鍒嗛厤鍚屽煄甯傜殑浣忓畢锛?
            List<String> homelessList = getHomelessNPCs(server);
            for (String npcName : homelessList) {
                // 鑾峰彇NPC鐨勫煄甯侷D
                UUID npcCityId = findNPCCityId(server, npcName);
                if (npcCityId == null) {
                    continue; // 璺宠繃鎵句笉鍒板煄甯備俊鎭殑NPC
                }

                // 鏌ユ壘璇ュ煄甯傜殑鍙敤浣忓畢
                Path availableResidence = findAvailableResidenceForCity(skFiles, npcCityId);
                if (availableResidence == null) {
                    continue; // 璇ュ煄甯傛病鏈夊彲鐢ㄤ綇瀹咃紝灏濊瘯涓嬩竴涓祦娴狽PC
                }

                // 鍒嗛厤浣忓畢缁橬PC
                boolean success = assignResidence(server, availableResidence, npcName);
                if (success) {
                    removeFromHomeless(server, npcName);
                    LOGGER.info("Priority assigned residence to homeless NPC: {} -> {}", npcName, availableResidence.getFileName());
                    LOGGER.info("[ResidentManager] Priority assigned residence to homeless NPC: {} in city: {}", npcName, npcCityId);
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            LOGGER.error("Failed to assign residence to homeless NPCs", e);
            return false;
        }
    }

    /**
     * 鍒嗛厤浣忓畢缁橬PC骞跺彂閫佹彁绀烘秷鎭紙鏀寔鎵€鏈変綇瀹呯被鎺у埗绠憋級
     */
    private static boolean assignResidence(MinecraftServer server, Path skFile, String npcName) {
        try {
            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            List<String> newLines = new ArrayList<>();
            boolean residentFieldUpdated = false;
            String position = "";
            String world = "";
            String cityIdStr = "";
            boolean isResidentialControlBox = false;

            // 鎻愬彇鎺у埗绠卞潗鏍囧拰涓栫晫淇℃伅
            for (String line : lines) {
                if (line.trim().startsWith("resident:")) {
                    // 鏇存柊resident瀛楁
                    newLines.add("resident: " + npcName);
                    residentFieldUpdated = true;
                } else if (line.trim().startsWith("position:")) {
                    position = line.substring("position:".length()).trim();
                    newLines.add(line);
                } else if (line.trim().startsWith("world:")) {
                    world = line.substring("world:".length()).trim();
                    newLines.add(line);
                } else if (line.trim().startsWith("cityid:")) {
                    cityIdStr = line.substring("cityid:".length()).trim();
                    newLines.add(line);
                } else if (line.trim().startsWith("type:")) {
                    String type = line.substring("type:".length()).trim();
                    newLines.add(line);
                    // 妫€鏌ユ槸鍚︿负浣忓畢绫绘帶鍒剁
                    if ("residential_control_box".equals(type)) {
                        isResidentialControlBox = true;
                    }
                } else {
                    newLines.add(line);
                }
            }

            // 濡傛灉娌℃湁鎵惧埌resident瀛楁锛屽湪鏂囦欢鏈熬娣诲姞
            if (!residentFieldUpdated) {
                newLines.add("resident: " + npcName);
            }

            // 鍐欏洖鏂囦欢
            Files.write(skFile, newLines, StandardCharsets.UTF_8);

            // 濡傛灉鏄綇瀹呯被鎺у埗绠变笖鎴愬姛鍒嗛厤浣忓畢锛屽彂閫佺櫧鑹插瓧浣撴彁绀烘秷鎭紙浠呭競闀垮彲瑙侊級
            if (isResidentialControlBox && !position.isEmpty() && !world.isEmpty() && !cityIdStr.isEmpty()) {
                try {
                    UUID cityId = UUID.fromString(cityIdStr);
                    sendResidenceAssignmentMessage(server, cityId, npcName, position, world);
                } catch (IllegalArgumentException e) {
                    LOGGER.error("[ResidentManager] 鏃犳晥鐨勫煄甯侷D: {}", cityIdStr);
                }
            }

            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to assign residence: {}", skFile.getFileName(), e);
            return false;
        }
    }

    /**
     * 鍙戦€佷綇瀹呭垎閰嶆彁绀烘秷鎭紙鍙戦€佸埌鍩庡競缇ょ粍锛屽彧鍦–hatScreen鏄剧ず锛屼笉鍦ㄨ亰澶╂爮鏄剧ず锛?
     */
    private static void sendResidenceAssignmentMessage(MinecraftServer server, UUID cityId, String npcName, String position, String world) {
        try {
            // 鍒涘缓娑堟伅鍐呭
            Component messageContent = Component.translatable("message.simukraft.residence.assigned", npcName, position);

            // 鑱婂ぉ绯荤粺宸叉媶鍒嗭紱褰撳墠缁熶竴璧板煄甯傛秷鎭伐鍏凤紙鍚€氱煡鏈嶅姟涓庣郴缁熸秷鎭檷绾э級銆?
            CityMessageUtils.sendToCityGroup(server, cityId, messageContent);

            LOGGER.info("Sent residence assignment message: {} -> {}", npcName, position);
            LOGGER.info("[ResidentManager] {} assigned to residence at {}", npcName, position);

        } catch (Exception e) {
            LOGGER.error("Failed to send residence assignment message for NPC: {}", npcName, e);
        }
    }

    /**
     * 鏍囪NPC涓烘祦娴姸鎬?
     */
    private static void markAsHomeless(MinecraftServer server, String npcName) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve("npc");
            Path homelessFile = npcDir.resolve(HOMELESS_FILE);

            if (!Files.exists(npcDir)) {
                Files.createDirectories(npcDir);
            }

            // 璇诲彇鐜版湁鐨勬祦娴狽PC鍒楄〃
            Set<String> homelessSet = new HashSet<>();
            if (Files.exists(homelessFile)) {
                List<String> lines = Files.readAllLines(homelessFile, StandardCharsets.UTF_8);
                homelessSet.addAll(lines);
            }

            // 娣诲姞鏂扮殑娴佹氮NPC
            homelessSet.add(npcName);

            // 鍐欏洖鏂囦欢
            Files.write(homelessFile, new ArrayList<>(homelessSet), StandardCharsets.UTF_8);

            // 鏇存柊鍐呭瓨涓殑娴佹氮鍒楄〃
            homelessNPCs.add(npcName);

        } catch (Exception e) {
            LOGGER.error("Failed to mark NPC as homeless: " + npcName, e);
        }
    }

    /**
     * 浠庢祦娴垪琛ㄤ腑绉婚櫎NPC
     */
    private static void removeFromHomeless(MinecraftServer server, String npcName) {
        try {
            Path worldDir = getWorldPath(server);
            Path npcDir = worldDir.resolve(FileUtils.MODE_DIR).resolve("npc");
            Path homelessFile = npcDir.resolve(HOMELESS_FILE);

            if (!Files.exists(homelessFile)) {
                return;
            }

            // 璇诲彇鐜版湁鐨勬祦娴狽PC鍒楄〃
            Set<String> homelessSet = new HashSet<>();
            List<String> lines = Files.readAllLines(homelessFile, StandardCharsets.UTF_8);
            homelessSet.addAll(lines);

            // 绉婚櫎NPC
            homelessSet.remove(npcName);

            // 鍐欏洖鏂囦欢
            Files.write(homelessFile, new ArrayList<>(homelessSet), StandardCharsets.UTF_8);

            // 鏇存柊鍐呭瓨涓殑娴佹氮鍒楄〃
            homelessNPCs.remove(npcName);

            LOGGER.info("Removed NPC from homeless list: {}", npcName);

        } catch (Exception e) {
            LOGGER.error("Failed to remove NPC from homeless list: " + npcName, e);
        }
    }

    /**
     * 鑾峰彇鎵€鏈夋祦娴狽PC鍒楄〃锛堜娇鐢ㄥ唴瀛樼紦瀛橈級
     */
    public static List<String> getHomelessNPCs(MinecraftServer server) {
        // 鎬ц兘浼樺寲锛氱洿鎺ヨ繑鍥炲唴瀛樼紦瀛樼殑鍓湰
        return new ArrayList<>(homelessNPCs);
    }

    /**
     * 妫€鏌PC鏄惁鍦ㄦ祦娴姸鎬侊紙浣跨敤鍐呭瓨缂撳瓨锛?
     */
    public static boolean isNPCHomeless(MinecraftServer server, String npcName) {
        // 鎬ц兘浼樺寲锛氱洿鎺ヤ娇鐢ㄥ唴瀛樼紦瀛橈紝閬垮厤鏂囦欢璇诲彇
        return homelessNPCs.contains(npcName);
    }

    /**
     * 鏌ユ壘residence鐩綍涓殑鎵€鏈塻k鏂囦欢
     */
    private static List<Path> findSkFiles(Path residenceDir) throws IOException {
        List<Path> skFiles = new ArrayList<>();

        if (Files.exists(residenceDir) && Files.isDirectory(residenceDir)) {
            Files.list(residenceDir)
                    .filter(path -> path.toString().toLowerCase().endsWith(SK_FILE_EXTENSION))
                    .forEach(skFiles::add);
        }

        return skFiles;
    }

    /**
     * 褰撴帶鍒剁琚媶闄ゆ椂锛岄噴鏀句綇瀹呭苟鏍囪NPC涓烘祦娴?
     */
    public static boolean releaseResidenceAndMarkHomeless(MinecraftServer server, String npcName) {
        try {
            // 棣栧厛閲婃斁浣忓畢
            boolean released = releaseResidence(server, npcName);

            if (released) {
                // 鐒跺悗鏍囪NPC涓烘祦娴姸鎬?
                markAsHomeless(server, npcName);
                LOGGER.info("Released residence and marked NPC as homeless: {}", npcName);
            }

            return released;

        } catch (Exception e) {
            LOGGER.error("Failed to release residence and mark NPC as homeless: " + npcName, e);
            return false;
        }
    }

    /**
     * 褰撴帶鍒剁琚媶闄ゆ椂锛岄噴鏀句綇瀹咃紙娓呯┖resident瀛楁锛?
     */
    public static boolean releaseResidence(MinecraftServer server, String npcName) {
        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                return false;
            }

            List<Path> skFiles = findSkFiles(residenceDir);
            boolean released = false;

            for (Path skFile : skFiles) {
                if (releaseResidenceFromFile(skFile, npcName)) {
                    released = true;
                    LOGGER.info("Released residence for NPC: {}", npcName);
                }
            }
            return released;

        } catch (Exception e) {
            LOGGER.error("Failed to release residence for NPC: " + npcName, e);
            return false;
        }
    }

    /**
     * 浠庡崟涓枃浠朵腑閲婃斁浣忓畢
     */
    private static boolean releaseResidenceFromFile(Path skFile, String npcName) {
        try {
            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            List<String> newLines = new ArrayList<>();
            boolean updated = false;

            for (String line : lines) {
                if (line.trim().startsWith("resident:") && line.contains(npcName)) {
                    // 娓呯┖resident瀛楁
                    newLines.add("resident: ");
                    updated = true;
                } else if (line.trim().startsWith("resident_uuid:")) {
                    // 鍚屾椂娓呯┖resident_uuid瀛楁
                    newLines.add("resident_uuid: ");
                    updated = true;
                } else {
                    newLines.add(line);
                }
            }
            if (updated) {
                Files.write(skFile, newLines, StandardCharsets.UTF_8);
                return true;
            }

            return false;

        } catch (Exception e) {
            LOGGER.error("Error releasing residence from file: " + skFile.getFileName(), e);
            return false;
        }
    }

    /**
     * 妫€鏌PC鏄惁宸叉湁浣忓畢鍒嗛厤锛堜娇鐢ㄧ紦瀛樹紭鍖栵級
     */
    public static boolean hasResidenceAssigned(MinecraftServer server, String npcName) {
        try {
            // 鎬ц兘浼樺寲锛氭鏌ョ紦瀛樻槸鍚︽湁鏁?
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCacheUpdate > CACHE_UPDATE_INTERVAL || residenceCache.isEmpty()) {
                // 鏇存柊缂撳瓨
                updateResidenceCache(server);
                lastCacheUpdate = currentTime;
            }

            // 浣跨敤缂撳瓨妫€鏌?
            return residenceCache.contains(npcName);

        } catch (Exception e) {
            LOGGER.error("Error checking residence assignment for NPC: " + npcName, e);
            return false;
        }
    }

    /**
     * 按住宅控制盒绑定的 resident_uuid 检查 NPC 是否已分配住宅。
     */
    public static boolean hasResidenceAssigned(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) {
            return false;
        }

        return getNPCResidenceControlBoxPos(server, npcUuid) != null;
    }

    /**
     * 鏇存柊浣忓畢鍒嗛厤缂撳瓨
     */
    private static void updateResidenceCache(MinecraftServer server) {
        residenceCache.clear();

        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                return;
            }

            List<Path> skFiles = findSkFiles(residenceDir);

            for (Path skFile : skFiles) {
                String resident = getResidentFromFile(skFile);
                if (resident != null && !resident.isEmpty()) {
                    residenceCache.add(resident);
                }
            }

            LOGGER.debug("Updated residence cache: {} NPCs with residences", residenceCache.size());

        } catch (Exception e) {
            LOGGER.error("Error updating residence cache", e);
        }
    }

    /**
     * 浠庢枃浠朵腑鑾峰彇灞呮皯鍚嶇О
     */
    private static String getResidentFromFile(Path skFile) {
        try {
            if (!Files.exists(skFile)) {
                return null;
            }

            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);

            for (String line : lines) {
                if (line.trim().startsWith("resident:")) {
                    String resident = line.substring(line.indexOf(":") + 1).trim();
                    return resident.isEmpty() ? null : resident;
                }
            }

            return null;

        } catch (Exception e) {
            LOGGER.error("Error reading resident from file: " + skFile, e);
            return null;
        }
    }

    /**
     * 鑾峰彇NPC浣忓畢鐨勬帶鍒剁鍧愭爣
     */
    public static String getNPCResidencePosition(MinecraftServer server, String npcName) {
        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                return null;
            }

            List<Path> skFiles = findSkFiles(residenceDir);

            for (Path skFile : skFiles) {
                String position = getPositionFromResidenceFile(skFile, npcName);
                if (position != null) {
                    return position;
                }
            }

            return null;

        } catch (Exception e) {
            LOGGER.error("Error getting residence position for NPC: " + npcName, e);
            return null;
        }
    }

    /**
     * 按住宅控制盒 resident_uuid 绑定关系获取住宅控制盒坐标。
     */
    public static BlockPos getNPCResidenceControlBoxPos(MinecraftServer server, UUID npcUuid) {
        if (server == null || npcUuid == null) {
            return null;
        }

        try {
            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                return null;
            }

            List<Path> skFiles = findSkFiles(residenceDir);

            for (Path skFile : skFiles) {
                BlockPos position = getPositionFromResidenceFileByUuid(skFile, npcUuid);
                if (position != null) {
                    return position;
                }
            }

            return null;

        } catch (Exception e) {
            LOGGER.error("Error getting residence position for NPC UUID: " + npcUuid, e);
            return null;
        }
    }

    /**
     * 浠庝綇瀹呮枃浠朵腑鑾峰彇鎺у埗绠卞潗鏍?
     */
    private static String getPositionFromResidenceFile(Path skFile, String npcName) {
        try {
            if (!Files.exists(skFile)) {
                return null;
            }

            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            String position = null;
            boolean npcResiding = false;

            for (String line : lines) {
                if (line.trim().startsWith("resident:") && line.contains(npcName)) {
                    npcResiding = true;
                } else if (line.trim().startsWith("position:")) {
                    position = line.substring("position:".length()).trim();
                }
            }

            return npcResiding ? position : null;

        } catch (Exception e) {
            LOGGER.error("Error getting position from residence file: " + skFile.getFileName(), e);
            return null;
        }
    }

    private static BlockPos getPositionFromResidenceFileByUuid(Path skFile, UUID npcUuid) {
        try {
            if (!Files.exists(skFile) || npcUuid == null) {
                return null;
            }

            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            String position = null;
            UUID boundResidentUuid = null;

            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("resident_uuid:")) {
                    String uuidStr = trimmedLine.substring("resident_uuid:".length()).trim();
                    if (!uuidStr.isEmpty()) {
                        try {
                            boundResidentUuid = UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("[ResidentManager] 住宅文件 resident_uuid 非法: {}", skFile.getFileName());
                            return null;
                        }
                    }
                } else if (trimmedLine.startsWith("position:")) {
                    position = trimmedLine.substring("position:".length()).trim();
                }
            }

            if (!npcUuid.equals(boundResidentUuid)) {
                return null;
            }

            return parseResidencePosition(position);

        } catch (Exception e) {
            LOGGER.error("Error getting position from residence file by UUID: " + skFile.getFileName(), e);
            return null;
        }
    }

    private static BlockPos parseResidencePosition(String position) {
        if (position == null || position.isBlank()) {
            return null;
        }

        try {
            String[] parts = position.replace("(", "").replace(")", "").split(",");
            if (parts.length != 3) {
                return null;
            }

            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            LOGGER.error("Error parsing residence position: {}", position, e);
            return null;
        }
    }

    /**
     * 褰揘PC姝讳骸鏃舵竻闄ゅ叾浣忓畢鏁版嵁
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     * @param npcUuid NPC鐨刄UID
     * @param npcName NPC鐨勫悕绉?
     */
    public static void clearResidenceOnNPCDeath(MinecraftServer server, UUID npcUuid, String npcName) {
        try {
            LOGGER.info("[ResidentManager] NPC姝讳骸锛屾竻闄や綇瀹呮暟鎹? {} ({})", npcName, npcUuid);

            Path worldDir = getWorldPath(server);
            Path residenceDir = worldDir.resolve(FileUtils.MODE_DIR).resolve(RESIDENCE_DIR);

            if (!Files.exists(residenceDir)) {
                LOGGER.info("[ResidentManager] 浣忓畢鐩綍涓嶅瓨鍦紝鏃犻渶娓呴櫎");
                return;
            }

            List<Path> skFiles = findSkFiles(residenceDir);
            boolean cleared = false;

            for (Path skFile : skFiles) {
                if (clearResidenceFromFileByUUID(skFile, npcUuid, npcName)) {
                    cleared = true;
                    LOGGER.info("[ResidentManager] 宸叉竻闄PC {} 鐨勪綇瀹呮暟鎹? {}", npcName, skFile.getFileName());
                }
            }

            if (cleared) {
                // 浠庣紦瀛樹腑绉婚櫎
                residenceCache.remove(npcName);
                LOGGER.info("[ResidentManager] NPC {} 鐨勪綇瀹呮暟鎹凡瀹屽叏娓呴櫎", npcName);
            } else {
                LOGGER.info("[ResidentManager] NPC {} 没有分配住宅", npcName);
            }

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 娓呴櫎NPC姝讳骸鏃剁殑浣忓畢鏁版嵁澶辫触: " + npcName, e);
        }
    }

    /**
     * 浠庡崟涓枃浠朵腑娓呴櫎鎸囧畾NPC鐨勪綇瀹呮暟鎹紙閫氳繃UUID鍜屽悕绉板尮閰嶏級
     */
    private static boolean clearResidenceFromFileByUUID(Path skFile, UUID npcUuid, String npcName) {
        try {
            List<String> lines = Files.readAllLines(skFile, StandardCharsets.UTF_8);
            List<String> newLines = new ArrayList<>();
            boolean updated = false;
            boolean isTargetNPC = false;

            // 绗竴閬嶆壂鎻忥細纭畾鏄惁鏄洰鏍嘚PC锛堥€氳繃UUID鍖归厤锛?
            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("resident_uuid:")) {
                    String uuidStr = trimmedLine.substring("resident_uuid:".length()).trim();
                    if (!uuidStr.isEmpty() && uuidStr.equals(npcUuid.toString())) {
                        isTargetNPC = true;
                        break;
                    }
                }
            }

            // 绗簩閬嶏細娓呴櫎鐩爣NPC鐨勬暟鎹?
            for (String line : lines) {
                String trimmedLine = line.trim();

                // 妫€鏌ユ槸鍚︽槸鐩爣NPC锛堥€氳繃鍚嶇О鎴朥UID鍖归厤锛?
                if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                    String residentName = trimmedLine.substring("resident:".length()).trim();
                    if (residentName.equals(npcName) || isTargetNPC) {
                        newLines.add("resident: ");
                        updated = true;
                        LOGGER.info("[ResidentManager] 娓呴櫎resident瀛楁: {}", npcName);
                    } else {
                        newLines.add(line);
                    }
                } else if (trimmedLine.startsWith("resident_uuid:")) {
                    String uuidStr = trimmedLine.substring("resident_uuid:".length()).trim();
                    if (isTargetNPC || (!uuidStr.isEmpty() && uuidStr.equals(npcUuid.toString()))) {
                        newLines.add("resident_uuid: ");
                        updated = true;
                        LOGGER.info("[ResidentManager] 娓呴櫎resident_uuid瀛楁: {}", npcUuid);
                    } else {
                        newLines.add(line);
                    }
                } else {
                    newLines.add(line);
                }
            }

            if (updated) {
                Files.write(skFile, newLines, StandardCharsets.UTF_8);
                return true;
            }

            return false;

        } catch (Exception e) {
            LOGGER.error("[ResidentManager] 浠庢枃浠舵竻闄や綇瀹呮暟鎹け璐? " + skFile.getFileName(), e);
            return false;
        }
    }
}
