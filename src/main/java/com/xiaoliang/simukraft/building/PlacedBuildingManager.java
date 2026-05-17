package com.xiaoliang.simukraft.building;

import com.xiaoliang.simukraft.client.preview.SchematicNBTLoader;
import com.xiaoliang.simukraft.client.preview.SchematicNBTLoader.SchematicBlock;
import com.xiaoliang.simukraft.employment.service.EmploymentCommands;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已放置建筑管理器（管理建筑整体结构，支持一键拆除和NPC识别）
 */
@SuppressWarnings("null")
public class PlacedBuildingManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final LevelResource WORLD_ROOT = LevelResource.ROOT;

    // 存储所有已放置的建筑结构数据
    private static final Map<UUID, PlacedBuildingData> placedBuildings = new ConcurrentHashMap<>();

    // 按控制盒位置索引的建筑
    private static final Map<BlockPos, UUID> controlBoxToBuilding = new ConcurrentHashMap<>();

    // simukraft: 服务器实例引用，用于自动保存（menglannnn: 在loadFromWorld时设置）
    private static MinecraftServer serverInstance = null;

    /**
     * 已放置建筑数据结构
     */
    public static class PlacedBuildingData {
        public final UUID buildingId;           // 建筑唯一ID
        public final BlockPos controlBoxPos;    // 控制盒位置
        public final String buildingName;       // 建筑名称
        public final String category;           // 建筑类别
        public final String worldId;            // 世界ID
        public final List<BlockEntry> blocks;   // 建筑包含的所有方块
        public final BlockPos minPos;           // 最小边界
        public final BlockPos maxPos;           // 最大边界
        public final long placedTime;           // 放置时间

        public PlacedBuildingData(UUID buildingId, BlockPos controlBoxPos, String buildingName,
                                  String category, String worldId, List<BlockEntry> blocks,
                                  BlockPos minPos, BlockPos maxPos) {
            this.buildingId = buildingId;
            this.controlBoxPos = controlBoxPos;
            this.buildingName = buildingName;
            this.category = category;
            this.worldId = worldId;
            this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
            this.minPos = minPos;
            this.maxPos = maxPos;
            this.placedTime = System.currentTimeMillis();
        }
    }

    /**
     * 方块条目（记录方块位置和类型）
     */
    public static class BlockEntry {
        public final BlockPos relativePos;      // 相对控制盒的位置
        public final String blockId;            // 方块ID
        public final CompoundTag blockState;    // 方块状态NBT
        public final BlockPos originalNbtPos;   // 原始NBT结构坐标

        public BlockEntry(BlockPos relativePos, String blockId, CompoundTag blockState) {
            this(relativePos, blockId, blockState, null);
        }

        public BlockEntry(BlockPos relativePos, String blockId, CompoundTag blockState, BlockPos originalNbtPos) {
            this.relativePos = relativePos;
            this.blockId = blockId;
            this.blockState = blockState != null ? blockState.copy() : null;
            this.originalNbtPos = originalNbtPos;
        }
    }

    /**
     * 注册新放置的建筑（从NBT文件读取结构并存储）
     * @param controlBoxPos 控制盒位置
     * @param buildingName 建筑名称
     * @param category 建筑类别
     * @param worldId 世界ID
     * @param nbtFilePath NBT文件路径
     * @return 建筑ID，失败返回null
     */
    @Nullable
    public static UUID registerPlacedBuilding(BlockPos controlBoxPos, String buildingName,
                                               String category, String worldId, String nbtFilePath) {
        try {
            // simukraft: 通过显示名称获取正确的文件名（menglannnn: 如"1单元"对应"u1"）
            String actualFileName = com.xiaoliang.simukraft.utils.BuildingDataManager.getFileNameByDisplayName(category, buildingName);
            if (actualFileName == null) {
                // 如果找不到，尝试使用buildingName作为文件名
                actualFileName = buildingName;
                LOGGER.warn("[PlacedBuildingManager] 找不到建筑'{}'的文件名映射，使用原名", buildingName);
            }

            // simukraft: 加载NBT文件（支持文件路径和资源路径）（menglannnn: 优先从文件加载，失败则从资源加载）
            List<SchematicBlock> schematicBlocks;
            File nbtFile = new File(nbtFilePath);

            if (nbtFile.exists()) {
                // 从文件加载
                schematicBlocks = SchematicNBTLoader.loadSchematicBlocks(nbtFilePath);
            } else {
                // 从资源文件加载，使用正确的文件名
                String resourcePath = "assets/simukraft/building/" + category + "/" + actualFileName + ".nbt";
                java.io.InputStream is = SchematicNBTLoader.class.getClassLoader().getResourceAsStream(resourcePath);
                if (is == null) {
                    LOGGER.error("[PlacedBuildingManager] 找不到建筑NBT资源: {}", resourcePath);
                    return null;
                }
                schematicBlocks = SchematicNBTLoader.loadSchematicBlocksFromStream(is);
            }

            if (schematicBlocks.isEmpty()) {
                LOGGER.error("[PlacedBuildingManager] 无法加载建筑NBT文件: {}", nbtFilePath);
                return null;
            }

            // simukraft: 找到控制盒在NBT中的位置（menglannnn: 用于计算偏移量）
            BlockPos controlBoxInNBT = null;
            for (SchematicBlock sb : schematicBlocks) {
                String blockId = ForgeRegistries.BLOCKS.getKey(sb.blockState().getBlock()).toString();
                if (blockId.contains("control_box")) {
                    controlBoxInNBT = sb.pos();
                    break;
                }
            }

            if (controlBoxInNBT == null) {
                LOGGER.warn("[PlacedBuildingManager] 建筑NBT文件中未找到控制盒，使用原点作为参考: {}", buildingName);
                controlBoxInNBT = BlockPos.ZERO;
            }

            // 转换为BlockEntry列表（坐标转换为相对于控制盒）
            List<BlockEntry> blockEntries = new ArrayList<>();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (SchematicBlock sb : schematicBlocks) {
                // simukraft: 将NBT坐标转换为相对于控制盒的坐标
                BlockPos relPos = sb.pos().subtract(controlBoxInNBT);
                String blockId = ForgeRegistries.BLOCKS.getKey(sb.blockState().getBlock()).toString();

                // 序列化方块状态
                CompoundTag stateTag = new CompoundTag();
                sb.blockState().getProperties().forEach(prop -> {
                    stateTag.putString(prop.getName(), sb.blockState().getValue(prop).toString());
                });

                blockEntries.add(new BlockEntry(relPos, blockId, stateTag, sb.pos()));

                // 更新边界
                minX = Math.min(minX, relPos.getX());
                minY = Math.min(minY, relPos.getY());
                minZ = Math.min(minZ, relPos.getZ());
                maxX = Math.max(maxX, relPos.getX());
                maxY = Math.max(maxY, relPos.getY());
                maxZ = Math.max(maxZ, relPos.getZ());
            }

            // 创建建筑数据
            UUID buildingId = UUID.randomUUID();
            BlockPos minPos = new BlockPos(minX, minY, minZ);
            BlockPos maxPos = new BlockPos(maxX, maxY, maxZ);

            PlacedBuildingData buildingData = new PlacedBuildingData(
                buildingId, controlBoxPos, buildingName, category, worldId,
                blockEntries, minPos, maxPos
            );

            // 存储
            placedBuildings.put(buildingId, buildingData);
            controlBoxToBuilding.put(controlBoxPos, buildingId);

            LOGGER.info("[PlacedBuildingManager] 注册建筑: {} (ID: {}, 方块数: {})",
                buildingName, buildingId, blockEntries.size());

            // simukraft: 立即保存到世界存档，防止数据丢失（menglannnn: 注册后立即持久化）
            if (serverInstance != null) {
                saveToWorld(serverInstance);
                LOGGER.info("[PlacedBuildingManager] 建筑数据已自动保存");
            }

            return buildingId;

        } catch (Exception e) {
            LOGGER.error("[PlacedBuildingManager] 注册建筑失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 注册新放置的建筑（从ConstructionTask传入已放置的方块列表）
     * @param controlBoxPos 控制盒位置
     * @param buildingName 建筑名称
     * @param category 建筑类别
     * @param worldId 世界ID
     * @param placedBlocks 已放置的方块列表（世界坐标）
     * @return 建筑ID，失败返回null
     */
    @Nullable
    public static UUID registerPlacedBuildingFromTask(BlockPos controlBoxPos, String buildingName,
                                                       String category, String worldId,
                                                       List<ConstructionTask.BlockInfo> placedBlocks) {
        try {
            if (placedBlocks == null || placedBlocks.isEmpty()) {
                LOGGER.error("[PlacedBuildingManager] 方块列表为空，无法注册建筑: {}", buildingName);
                return null;
            }

            // simukraft: 将世界坐标转换为相对于控制盒的坐标
            List<BlockEntry> blockEntries = new ArrayList<>();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (ConstructionTask.BlockInfo blockInfo : placedBlocks) {
                // 计算相对于控制盒的坐标
                BlockPos relPos = blockInfo.pos().subtract(controlBoxPos);
                String blockId = ForgeRegistries.BLOCKS.getKey(blockInfo.state().getBlock()).toString();

                // 序列化方块状态
                CompoundTag stateTag = new CompoundTag();
                blockInfo.state().getProperties().forEach(prop -> {
                    stateTag.putString(prop.getName(), blockInfo.state().getValue(prop).toString());
                });

                blockEntries.add(new BlockEntry(relPos, blockId, stateTag, blockInfo.originalNbtPos()));

                // 更新边界
                minX = Math.min(minX, relPos.getX());
                minY = Math.min(minY, relPos.getY());
                minZ = Math.min(minZ, relPos.getZ());
                maxX = Math.max(maxX, relPos.getX());
                maxY = Math.max(maxY, relPos.getY());
                maxZ = Math.max(maxZ, relPos.getZ());
            }

            // 创建建筑数据
            UUID buildingId = UUID.randomUUID();
            BlockPos minPos = new BlockPos(minX, minY, minZ);
            BlockPos maxPos = new BlockPos(maxX, maxY, maxZ);

            PlacedBuildingData buildingData = new PlacedBuildingData(
                buildingId, controlBoxPos, buildingName, category, worldId,
                blockEntries, minPos, maxPos
            );

            // 存储
            placedBuildings.put(buildingId, buildingData);
            controlBoxToBuilding.put(controlBoxPos, buildingId);

            LOGGER.info("[PlacedBuildingManager] 注册建筑(来自任务): {} (ID: {}, 方块数: {})",
                buildingName, buildingId, blockEntries.size());

            // simukraft: 立即保存到世界存档
            if (serverInstance != null) {
                saveToWorld(serverInstance);
                LOGGER.info("[PlacedBuildingManager] 建筑数据已自动保存");
            }

            return buildingId;

        } catch (Exception e) {
            LOGGER.error("[PlacedBuildingManager] 从任务注册建筑失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 一键拆除建筑（根据存储的结构数据逐个移除方块）
     * @param buildingId 建筑ID
     * @param level 服务器世界
     * @return 是否成功
     */
    public static boolean demolishBuilding(UUID buildingId, ServerLevel level) {
        PlacedBuildingData building = placedBuildings.get(buildingId);
        if (building == null) {
            LOGGER.error("[PlacedBuildingManager] 找不到建筑: {}", buildingId);
            return false;
        }

        if (!building.worldId.equals(level.dimension().location().toString())) {
            LOGGER.error("[PlacedBuildingManager] 建筑不在当前世界: {}", buildingId);
            return false;
        }

        LOGGER.info("[PlacedBuildingManager] 开始拆除建筑: {} ({})", building.buildingName, buildingId);

        // menglannnn: 拆除建筑前先解雇该建筑的所有员工
        // 直接移除方块不会触发onRemove方法，需要手动解雇
        MinecraftServer server = level.getServer();
        if (server != null) {
            String dimensionId = level.dimension().location().toString();
            var releaseResult = EmploymentServices.get(server).onWorkBlockRemoved(
                    new EmploymentCommands.WorkBlockRemovedCommand(dimensionId, building.controlBoxPos)
            );
            if (releaseResult.success() && releaseResult.assignment() != null) {
                com.xiaoliang.simukraft.network.EmploymentCommandPacket.applyFireSideEffectsAndBroadcast(
                        server, releaseResult.assignment(), false
                );
                LOGGER.info("[PlacedBuildingManager] 已解雇建筑 {} 的员工", building.buildingName);
            }
        }

        // 按从外到内、从上到下的顺序拆除
        List<BlockEntry> sortedBlocks = new ArrayList<>(building.blocks);
        sortedBlocks.sort((a, b) -> {
            // 先按Y从高到低
            int yCompare = Integer.compare(b.relativePos.getY(), a.relativePos.getY());
            if (yCompare != 0) return yCompare;
            // 再按距离中心远近
            double distA = a.relativePos.distSqr(BlockPos.ZERO);
            double distB = b.relativePos.distSqr(BlockPos.ZERO);
            return Double.compare(distB, distA);
        });

        int removedCount = 0;
        int skippedCount = 0;
        for (BlockEntry entry : sortedBlocks) {
            BlockPos worldPos = building.controlBoxPos.offset(entry.relativePos);
            BlockState currentState = level.getBlockState(worldPos);

            // 只移除非空气方块
            if (!currentState.isAir()) {
                // simukraft: 检查该位置是否属于其他建筑（menglannnn: 避免拆除重叠部分）
                if (isBlockInOtherBuilding(worldPos, building.buildingId)) {
                    LOGGER.debug("[PlacedBuildingManager] 跳过与其他建筑重叠的方块: {}", worldPos);
                    skippedCount++;
                    continue;
                }

                level.removeBlock(worldPos, false);
                removedCount++;
            }
        }

        // 移除控制盒本身
        level.removeBlock(building.controlBoxPos, false);

        LOGGER.info("[PlacedBuildingManager] 拆除完成: {}，移除了 {} 个方块，跳过 {} 个重叠方块",
            building.buildingName, removedCount, skippedCount);

        // 从存储中移除
        placedBuildings.remove(buildingId);
        controlBoxToBuilding.remove(building.controlBoxPos);

        return true;
    }

    /**
     * 检查方块是否属于其他建筑（menglannnn: 用于拆除时跳过重叠部分）
     * @param pos 方块位置
     * @param excludeBuildingId 要排除的建筑ID（当前正在拆除的建筑）
     * @return 是否属于其他建筑
     */
    private static boolean isBlockInOtherBuilding(BlockPos pos, UUID excludeBuildingId) {
        for (PlacedBuildingData otherBuilding : placedBuildings.values()) {
            if (otherBuilding.buildingId.equals(excludeBuildingId)) {
                continue; // 跳过当前正在拆除的建筑
            }

            // 检查该方块是否在其他建筑的范围内
            for (BlockEntry entry : otherBuilding.blocks) {
                BlockPos otherWorldPos = otherBuilding.controlBoxPos.offset(entry.relativePos);
                if (otherWorldPos.equals(pos)) {
                    return true; // 找到重叠
                }
            }
        }
        return false;
    }

    /**
     * 获取建筑数据（供NPC识别使用）
     * @param buildingId 建筑ID
     * @return 建筑数据，找不到返回null
     */
    @Nullable
    public static PlacedBuildingData getBuilding(UUID buildingId) {
        return placedBuildings.get(buildingId);
    }

    /**
     * 通过控制盒位置获取建筑（供NPC识别使用）
     * @param controlBoxPos 控制盒位置
     * @return 建筑数据，找不到返回null
     */
    @Nullable
    public static PlacedBuildingData getBuildingByControlBox(BlockPos controlBoxPos) {
        UUID buildingId = controlBoxToBuilding.get(controlBoxPos);
        if (buildingId != null) {
            return placedBuildings.get(buildingId);
        }
        return null;
    }

    /**
     * 获取所有已放置的建筑
     * @return 建筑数据列表
     */
    public static Collection<PlacedBuildingData> getAllBuildings() {
        return Collections.unmodifiableCollection(placedBuildings.values());
    }

    /**
     * 获取指定世界的所有建筑
     * @param worldId 世界ID
     * @return 建筑数据列表
     */
    public static List<PlacedBuildingData> getBuildingsInWorld(String worldId) {
        return placedBuildings.values().stream()
            .filter(b -> b.worldId.equals(worldId))
            .toList();
    }

    /**
     * 检查位置是否在建筑范围内（供NPC识别使用）
     * @param buildingId 建筑ID
     * @param pos 要检查的位置
     * @return 是否在建筑范围内
     */
    public static boolean isPosInBuilding(UUID buildingId, BlockPos pos) {
        PlacedBuildingData building = placedBuildings.get(buildingId);
        if (building == null) return false;

        BlockPos relativePos = pos.subtract(building.controlBoxPos);
        return relativePos.getX() >= building.minPos.getX() && relativePos.getX() <= building.maxPos.getX() &&
               relativePos.getY() >= building.minPos.getY() && relativePos.getY() <= building.maxPos.getY() &&
               relativePos.getZ() >= building.minPos.getZ() && relativePos.getZ() <= building.maxPos.getZ();
    }

    /**
     * 获取位置所在的建筑（供NPC识别使用）
     * @param pos 位置
     * @param worldId 世界ID
     * @return 建筑数据，不在任何建筑内返回null
     */
    @Nullable
    public static PlacedBuildingData getBuildingAtPos(BlockPos pos, String worldId) {
        for (PlacedBuildingData building : placedBuildings.values()) {
            if (!building.worldId.equals(worldId)) continue;
            if (isPosInBuilding(building.buildingId, pos)) {
                return building;
            }
        }
        return null;
    }

    /**
     * 保存所有建筑数据到世界存档（持久化存储）
     * @param server 服务器
     */
    public static void saveToWorld(MinecraftServer server) {
        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            File dataFile = new File(worldPath.toFile(), "simukraft/placed_buildings.dat");
            dataFile.getParentFile().mkdirs();

            CompoundTag rootTag = new CompoundTag();
            ListTag buildingsList = new ListTag();

            for (PlacedBuildingData building : placedBuildings.values()) {
                CompoundTag buildingTag = new CompoundTag();
                buildingTag.putUUID("buildingId", building.buildingId);
                buildingTag.putLong("controlBoxX", building.controlBoxPos.getX());
                buildingTag.putLong("controlBoxY", building.controlBoxPos.getY());
                buildingTag.putLong("controlBoxZ", building.controlBoxPos.getZ());
                buildingTag.putString("buildingName", building.buildingName);
                buildingTag.putString("category", building.category);
                buildingTag.putString("worldId", building.worldId);
                buildingTag.putLong("placedTime", building.placedTime);

                // 保存边界
                CompoundTag minTag = new CompoundTag();
                minTag.putInt("x", building.minPos.getX());
                minTag.putInt("y", building.minPos.getY());
                minTag.putInt("z", building.minPos.getZ());
                buildingTag.put("minPos", minTag);

                CompoundTag maxTag = new CompoundTag();
                maxTag.putInt("x", building.maxPos.getX());
                maxTag.putInt("y", building.maxPos.getY());
                maxTag.putInt("z", building.maxPos.getZ());
                buildingTag.put("maxPos", maxTag);

                // 保存方块列表
                ListTag blocksList = new ListTag();
                for (BlockEntry entry : building.blocks) {
                    CompoundTag blockTag = new CompoundTag();
                    blockTag.putInt("relX", entry.relativePos.getX());
                    blockTag.putInt("relY", entry.relativePos.getY());
                    blockTag.putInt("relZ", entry.relativePos.getZ());
                    blockTag.putString("blockId", entry.blockId);
                    if (entry.blockState != null) {
                        blockTag.put("blockState", entry.blockState);
                    }
                    if (entry.originalNbtPos != null) {
                        blockTag.putInt("nbtX", entry.originalNbtPos.getX());
                        blockTag.putInt("nbtY", entry.originalNbtPos.getY());
                        blockTag.putInt("nbtZ", entry.originalNbtPos.getZ());
                    }
                    blocksList.add(blockTag);
                }
                buildingTag.put("blocks", blocksList);

                buildingsList.add(buildingTag);
            }

            rootTag.put("buildings", buildingsList);

            try (FileOutputStream fos = new FileOutputStream(dataFile)) {
                NbtIo.writeCompressed(rootTag, fos);
            }

            LOGGER.info("[PlacedBuildingManager] 保存了 {} 个建筑数据", placedBuildings.size());

        } catch (IOException e) {
            LOGGER.error("[PlacedBuildingManager] 保存建筑数据失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从世界存档加载建筑数据（持久化加载）
     * @param server 服务器
     */
    public static void loadFromWorld(MinecraftServer server) {
        // simukraft: 保存服务器实例引用（menglannnn: 用于自动保存）
        serverInstance = server;

        placedBuildings.clear();
        controlBoxToBuilding.clear();

        try {
            Path worldPath = server.getWorldPath(WORLD_ROOT);
            File dataFile = new File(worldPath.toFile(), "simukraft/placed_buildings.dat");

            if (!dataFile.exists()) {
                LOGGER.info("[PlacedBuildingManager] 没有找到建筑数据文件");
                return;
            }

            try (FileInputStream fis = new FileInputStream(dataFile)) {
                CompoundTag rootTag = NbtIo.readCompressed(fis);
                ListTag buildingsList = rootTag.getList("buildings", 10);

                for (int i = 0; i < buildingsList.size(); i++) {
                    CompoundTag buildingTag = buildingsList.getCompound(i);

                    UUID buildingId = buildingTag.getUUID("buildingId");
                    BlockPos controlBoxPos = new BlockPos(
                        (int) buildingTag.getLong("controlBoxX"),
                        (int) buildingTag.getLong("controlBoxY"),
                        (int) buildingTag.getLong("controlBoxZ")
                    );
                    String buildingName = buildingTag.getString("buildingName");
                    String category = buildingTag.getString("category");
                    String worldId = buildingTag.getString("worldId");
                    // placedTime 读取但不使用，保留用于未来扩展

                    // 加载边界
                    CompoundTag minTag = buildingTag.getCompound("minPos");
                    BlockPos minPos = new BlockPos(
                        minTag.getInt("x"), minTag.getInt("y"), minTag.getInt("z")
                    );
                    CompoundTag maxTag = buildingTag.getCompound("maxPos");
                    BlockPos maxPos = new BlockPos(
                        maxTag.getInt("x"), maxTag.getInt("y"), maxTag.getInt("z")
                    );

                    // 加载方块列表
                    List<BlockEntry> blockEntries = new ArrayList<>();
                    ListTag blocksList = buildingTag.getList("blocks", 10);
                    for (int j = 0; j < blocksList.size(); j++) {
                        CompoundTag blockTag = blocksList.getCompound(j);
                        BlockPos relPos = new BlockPos(
                            blockTag.getInt("relX"),
                            blockTag.getInt("relY"),
                            blockTag.getInt("relZ")
                        );
                        String blockId = blockTag.getString("blockId");
                        CompoundTag stateTag = blockTag.contains("blockState") ?
                            blockTag.getCompound("blockState") : null;
                        BlockPos originalNbtPos = blockTag.contains("nbtX") && blockTag.contains("nbtY") && blockTag.contains("nbtZ")
                                ? new BlockPos(blockTag.getInt("nbtX"), blockTag.getInt("nbtY"), blockTag.getInt("nbtZ"))
                                : null;
                        blockEntries.add(new BlockEntry(relPos, blockId, stateTag, originalNbtPos));
                    }

                    // 创建建筑数据（使用特殊构造函数绕过final限制）
                    PlacedBuildingData buildingData = new PlacedBuildingData(
                        buildingId, controlBoxPos, buildingName, category, worldId,
                        blockEntries, minPos, maxPos
                    );

                    placedBuildings.put(buildingId, buildingData);
                    controlBoxToBuilding.put(controlBoxPos, buildingId);
                }

                LOGGER.info("[PlacedBuildingManager] 加载了 {} 个建筑数据", placedBuildings.size());
            }

        } catch (IOException e) {
            LOGGER.error("[PlacedBuildingManager] 加载建筑数据失败: {}", e.getMessage(), e);
        }
    }
}
