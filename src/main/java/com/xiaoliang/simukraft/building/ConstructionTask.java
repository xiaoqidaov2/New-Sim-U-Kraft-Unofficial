package com.xiaoliang.simukraft.building;

import com.xiaoliang.simukraft.client.preview.SchematicNBTLoader;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.IronBarsBlock;

import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings("null")
public class ConstructionTask {
    private static final Property<Direction> FACING_PROPERTY = requireProperty(BlockStateProperties.FACING);
    private static final Property<Direction> HORIZONTAL_FACING_PROPERTY = requireProperty(BlockStateProperties.HORIZONTAL_FACING);
    private static final Property<Direction> HOPPER_FACING_PROPERTY = requireProperty(BlockStateProperties.FACING_HOPPER);
    private static final Property<Integer> ROTATION_16_PROPERTY = requireProperty(BlockStateProperties.ROTATION_16);
    private static final Property<Direction.Axis> AXIS_PROPERTY = requireProperty(BlockStateProperties.AXIS);
    private static final Property<Direction.Axis> HORIZONTAL_AXIS_PROPERTY = requireProperty(BlockStateProperties.HORIZONTAL_AXIS);
    private static final Property<?> STAIRS_SHAPE_PROPERTY = requireProperty(BlockStateProperties.STAIRS_SHAPE);
    private static final Property<BedPart> BED_PART_PROPERTY = requireProperty(BedBlock.PART);
    private static final Property<Direction> BED_FACING_PROPERTY = requireProperty(BedBlock.FACING);
    private static final Property<DoubleBlockHalf> DOOR_HALF_PROPERTY = requireProperty(DoorBlock.HALF);

    @Nullable
    private CustomEntity builder;
    @Nonnull
    private final String buildingName;
    @Nonnull
    private final String category;
    @Nonnull
    private final BlockPos startPos;
    @Nonnull
    private final BlockPos buildBoxPos;
    @Nonnull
    private final Direction facing;
    @Nonnull
    private final String displayName;
    private final double cost;
    private int currentBlockIndex = 0;
    private boolean isCompleted = false;
    @Nonnull
    private final List<BlockInfo> blocksToPlace;
    @Nonnull
    private final Map<BlockPos, Integer> blockIndexLookup;
    @Nonnull
    private final List<BlockPos> controlBoxPositions;
    @Nullable
    private ChunkPos loadedChunkPos = null;
    @Nullable
    private ServerLevel runtimeLevel = null;
    // 修复：添加建筑盒周围区块的强制加载，确保箱子能被找到
    private final List<ChunkPos> forcedChunks = new ArrayList<>();
    // 修复：添加区块加载等待计数器，解决退出重进后箱子找不到的问题
    private int chunkLoadWaitTicks = 0;
    private boolean hasNotifiedChunkLoading = false;

    public ConstructionTask(@Nonnull CustomEntity builder, @Nonnull String buildingName, @Nonnull String category, @Nonnull BlockPos startPos,
                           @Nonnull BlockPos buildBoxPos, @Nonnull Direction facing, @Nonnull String displayName, double cost) {
        this.builder = builder;
        this.runtimeLevel = builder.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        this.buildingName = Objects.requireNonNull(buildingName);
        this.category = Objects.requireNonNull(category);
        this.startPos = Objects.requireNonNull(startPos);
        this.buildBoxPos = Objects.requireNonNull(buildBoxPos);
        this.facing = Objects.requireNonNull(facing);
        this.displayName = Objects.requireNonNull(displayName);
        this.cost = cost;
        this.blocksToPlace = Objects.requireNonNull(loadAndParseBlocks());
        this.blockIndexLookup = buildBlockIndexLookup(this.blocksToPlace);
        this.controlBoxPositions = collectControlBoxPositions(this.blocksToPlace);

        // 加载建筑所在区块以及建筑盒周围区块（确保箱子能被找到）
        if (builder.level() instanceof ServerLevel serverLevel) {
            // 加载建筑起始位置区块
            ChunkPos primaryChunk = new ChunkPos(this.startPos);
            this.loadedChunkPos = primaryChunk;
            serverLevel.setChunkForced(primaryChunk.x, primaryChunk.z, true);

            // 修复：加载建筑盒周围范围内的所有区块（箱子搜索范围）
            if (ServerConfig.isBuilderForceLoadChunks()) {
                int chunkRadius = ServerConfig.getBuilderChunkLoadRadius();
                ChunkPos buildBoxChunk = new ChunkPos(this.buildBoxPos);
                for (int x = -chunkRadius; x <= chunkRadius; x++) {
                    for (int z = -chunkRadius; z <= chunkRadius; z++) {
                        ChunkPos chunkPos = new ChunkPos(buildBoxChunk.x + x, buildBoxChunk.z + z);
                        if (!chunkPos.equals(loadedChunkPos)) {
                            serverLevel.setChunkForced(chunkPos.x, chunkPos.z, true);
                            forcedChunks.add(chunkPos);
                        }
                    }
                }
            }
        }
    }

    /**
     * 用于从持久化数据恢复建造任务的构造函数
     * 不关联具体的NPC实体，用于局域网开放模式下NPC休息后恢复任务
     */
    public ConstructionTask(@Nonnull String buildingName, @Nonnull String category, @Nonnull BlockPos startPos,
                           @Nonnull BlockPos buildBoxPos, @Nonnull String facingStr, @Nonnull String displayName, double cost,
                           @Nullable ServerLevel level) {
        this.builder = null;  // 恢复时不关联NPC
        this.runtimeLevel = level;
        this.buildingName = Objects.requireNonNull(buildingName);
        this.category = Objects.requireNonNull(category);
        this.startPos = Objects.requireNonNull(startPos);
        this.buildBoxPos = Objects.requireNonNull(buildBoxPos);
        Direction tempFacing = Direction.byName(facingStr);
        this.facing = tempFacing != null ? tempFacing : Direction.NORTH;
        this.displayName = Objects.requireNonNull(displayName);
        this.cost = cost;
        this.blocksToPlace = Objects.requireNonNull(loadAndParseBlocks());
        this.blockIndexLookup = buildBlockIndexLookup(this.blocksToPlace);
        this.controlBoxPositions = collectControlBoxPositions(this.blocksToPlace);

        // 加载建筑所在区块以及建筑盒周围区块
        if (level != null) {
            ChunkPos primaryChunk = new ChunkPos(this.startPos);
            this.loadedChunkPos = primaryChunk;
            level.setChunkForced(primaryChunk.x, primaryChunk.z, true);

            if (ServerConfig.isBuilderForceLoadChunks()) {
                int chunkRadius = ServerConfig.getBuilderChunkLoadRadius();
                ChunkPos buildBoxChunk = new ChunkPos(this.buildBoxPos);
                for (int x = -chunkRadius; x <= chunkRadius; x++) {
                    for (int z = -chunkRadius; z <= chunkRadius; z++) {
                        ChunkPos chunkPos = new ChunkPos(buildBoxChunk.x + x, buildBoxChunk.z + z);
                        if (!chunkPos.equals(loadedChunkPos)) {
                            level.setChunkForced(chunkPos.x, chunkPos.z, true);
                            forcedChunks.add(chunkPos);
                        }
                    }
                }
            }
        }

    }

    @Nonnull
    private List<BlockInfo> loadAndParseBlocks() {
        List<BlockInfo> blocks = new ArrayList<>();

        // 使用新的NBT加载方式
        String filePath = "simukraftbuilding/" + category + "/" + buildingName + ".nbt";
        List<SchematicNBTLoader.SchematicBlock> schematicBlocks = SchematicNBTLoader.loadSchematicBlocks(filePath);

        for (SchematicNBTLoader.SchematicBlock schematicBlock : schematicBlocks) {
            BlockPos pos = Objects.requireNonNull(schematicBlock.pos());
            BlockState state = Objects.requireNonNull(schematicBlock.blockState());

            // 根据朝向旋转坐标
            BlockPos rotatedPos = rotatePosition(pos);
            // 根据朝向旋转方块状态
            BlockState rotatedState = Objects.requireNonNull(rotateBlockState(state));

            // 计算最终位置
            BlockPos finalPos = new BlockPos(
                startPos.getX() + rotatedPos.getX(),
                startPos.getY() + rotatedPos.getY(),
                startPos.getZ() + rotatedPos.getZ()
            );

            blocks.add(new BlockInfo(finalPos, rotatedState));
        }

        // 按层排序，每层优先级：普通方块 > 需要支撑/重力方块 > 液体相关方块
        blocks.sort(new LayeredBlockComparator());

        return blocks;
    }

    /**
     * 层建造比较器
     * 按Y坐标分层，每层内按优先级排序：完整方块 > 不完整方块 > 需要支撑的方块 > 重力方块 > 液体相关方块
     */
    private static class LayeredBlockComparator implements Comparator<BlockInfo> {
        @Override
        public int compare(BlockInfo a, BlockInfo b) {
            // 首先按Y坐标排序（从低到高，先建底层）
            int yCompare = Integer.compare(a.pos().getY(), b.pos().getY());
            if (yCompare != 0) {
                return yCompare;
            }

            // 同一层内按优先级排序
            int priorityA = getBlockPriority(a.state());
            int priorityB = getBlockPriority(b.state());
            return Integer.compare(priorityA, priorityB);
        }

        /**
         * 获取方块优先级
         * 0 = 完整方块（最先建造）
         * 1 = 不完整方块（台阶、楼梯等）
         * 2 = 需要支撑的方块（门、活板门、按钮、拉杆、火把、灯笼等）
         * 3 = 重力方块（沙子、沙砾等，最后建造）
         * 4 = 液体相关方块（统一最后放置，避免流体更新影响前序建造）
         */
        private int getBlockPriority(BlockState state) {
            Block block = state.getBlock();

            if (!state.getFluidState().isEmpty()) {
                return 4;
            }

            // 重力方块优先级最低（最后建造）
            if (block instanceof FallingBlock) {
                return 3;
            }

            // 需要支撑的方块
            if (requiresSupport(block)) {
                return 2;
            }

            // 不完整方块
            if (isIncompleteBlock(block)) {
                return 1;
            }

            // 完整方块优先级最高（最先建造）
            return 0;
        }

        /**
         * 检查方块是否需要支撑（会掉落或需要依附在其他方块上）
         */
        private boolean requiresSupport(Block block) {
            return block instanceof DoorBlock ||
                   block instanceof TrapDoorBlock ||
                   block instanceof ButtonBlock ||
                   block instanceof LeverBlock ||
                   block instanceof TorchBlock ||
                   block instanceof LanternBlock ||
                   block instanceof ChainBlock ||
                   block instanceof IronBarsBlock;
        }

        /**
         * 检查方块是否为不完整方块
         */
        private boolean isIncompleteBlock(Block block) {
            return block instanceof SlabBlock ||
                   block instanceof StairBlock ||
                   block instanceof FenceBlock ||
                   block instanceof WallBlock;
        }
    }
    
    private BlockPos rotatePosition(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        
        // 根据朝向进行旋转
        switch (facing) {
            case NORTH:
                // 默认朝向，不旋转
                break;
            case EAST:
                // 顺时针90度
                int newX1 = -z;
                int newZ1 = x;
                x = newX1;
                z = newZ1;
                break;
            case SOUTH:
                // 180度
                x = -x;
                z = -z;
                break;
            case WEST:
                // 逆时针90度（顺时针270度）
                int newX2 = z;
                int newZ2 = -x;
                x = newX2;
                z = newZ2;
                break;
            default:
                break;
        }
        
        return new BlockPos(x, y, z);
    }
    
    private BlockState rotateBlockState(BlockState state) {
        if (state.isAir()) {
            return state;
        }

        int rotations = 0;
        switch (facing) {
            case NORTH:
                rotations = 0;
                break;
            case EAST:
                rotations = 1;
                break;
            case SOUTH:
                rotations = 2;
                break;
            case WEST:
                rotations = 3;
                break;
            default:
                rotations = 0;
                break;
        }

        if (rotations == 0) {
            return state;
        }

        BlockState rotatedState = state;
        
        for (int i = 0; i < rotations; i++) {
            rotatedState = rotateBlockStateOnce(rotatedState);
        }
        
        return rotatedState;
    }

    @Nonnull
    private static <T extends Comparable<T>> T getRequiredValue(@Nonnull BlockState state, @Nonnull Property<T> property) {
        return Objects.requireNonNull(state.getValue(Objects.requireNonNull(property)));
    }

    @Nonnull
    private static <T extends Comparable<T>> BlockState setRequiredValue(@Nonnull BlockState state, @Nonnull Property<T> property, @Nonnull T value) {
        return Objects.requireNonNull(state.setValue(Objects.requireNonNull(property), Objects.requireNonNull(value)));
    }

    @Nonnull
    private static <T extends Comparable<T>> Property<T> requireProperty(Property<T> property) {
        return Objects.requireNonNull(property);
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    private static BlockState setDirectionEnumValue(@Nonnull BlockState state, @Nonnull Property<?> property, @Nonnull Direction value) {
        return setRequiredValue(state, (Property<Direction>) property, value);
    }

    @Nonnull
    @SuppressWarnings({"deprecation", "null"})
    private BlockState rotateBlockStateOnce(BlockState state) {
        if (state.hasProperty(FACING_PROPERTY)) {
            Direction facing = getRequiredValue(state, FACING_PROPERTY);
            Direction newFacing = rotateDirection(facing);
            return setRequiredValue(state, FACING_PROPERTY, newFacing);
        }

        if (state.hasProperty(HORIZONTAL_FACING_PROPERTY)) {
            Direction facing = getRequiredValue(state, HORIZONTAL_FACING_PROPERTY);
            Direction newFacing = rotateHorizontalDirection(facing);
            return setRequiredValue(state, HORIZONTAL_FACING_PROPERTY, newFacing);
        }

        if (state.hasProperty(HOPPER_FACING_PROPERTY)) {
            Direction facing = getRequiredValue(state, HOPPER_FACING_PROPERTY);
            Direction newFacing = rotateDirection(facing);
            return setRequiredValue(state, HOPPER_FACING_PROPERTY, newFacing);
        }

        if (state.hasProperty(ROTATION_16_PROPERTY)) {
            int rotation = getRequiredValue(state, ROTATION_16_PROPERTY);
            int newRotation = (rotation + 4) % 16;
            return setRequiredValue(state, ROTATION_16_PROPERTY, newRotation);
        }

        if (state.hasProperty(AXIS_PROPERTY)) {
            net.minecraft.core.Direction.Axis axis = getRequiredValue(state, AXIS_PROPERTY);
            net.minecraft.core.Direction.Axis newAxis = rotateAxis(axis);
            return setRequiredValue(state, AXIS_PROPERTY, newAxis);
        }

        if (state.hasProperty(HORIZONTAL_AXIS_PROPERTY)) {
            net.minecraft.core.Direction.Axis axis = getRequiredValue(state, HORIZONTAL_AXIS_PROPERTY);
            net.minecraft.core.Direction.Axis newAxis = rotateHorizontalAxis(axis);
            return setRequiredValue(state, HORIZONTAL_AXIS_PROPERTY, newAxis);
        }

        if (state.hasProperty(STAIRS_SHAPE_PROPERTY)) {
            return state.rotate(Rotation.CLOCKWISE_90);
        }

        for (Property<?> property : state.getProperties()) {
            if (property instanceof EnumProperty<?>) {
                Object value = state.getValue(property);
                if (value instanceof Direction direction) {
                    Direction newDirection = rotateDirection(direction);
                    return setDirectionEnumValue(state, property, newDirection);
                }
            }
        }
        
        return state.rotate(Rotation.CLOCKWISE_90);
    }
    
    @Nonnull
    private Direction rotateDirection(@Nonnull Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
        };
    }
    
    @Nonnull
    private Direction rotateHorizontalDirection(@Nonnull Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> direction;
        };
    }
    
    @Nonnull
    private net.minecraft.core.Direction.Axis rotateAxis(@Nonnull net.minecraft.core.Direction.Axis axis) {
        return switch (axis) {
            case X -> net.minecraft.core.Direction.Axis.Z;
            case Z -> net.minecraft.core.Direction.Axis.X;
            case Y -> net.minecraft.core.Direction.Axis.Y;
        };
    }
    
    @Nonnull
    private net.minecraft.core.Direction.Axis rotateHorizontalAxis(@Nonnull net.minecraft.core.Direction.Axis axis) {
        return switch (axis) {
            case X -> net.minecraft.core.Direction.Axis.Z;
            case Z -> net.minecraft.core.Direction.Axis.X;
            case Y -> net.minecraft.core.Direction.Axis.Y;
        };
    }

    public boolean hasNextBlock() {
        return currentBlockIndex < blocksToPlace.size();
    }

    /**
     * 恢复持久化任务后重新绑定运行时建筑师。
     */
    public void attachBuilder(@Nullable CustomEntity builder) {
        this.builder = builder;
        if (builder != null && builder.level() instanceof ServerLevel serverLevel) {
            this.runtimeLevel = serverLevel;
        }
    }

    @Nullable
    private ServerLevel getRuntimeLevel() {
        if (builder != null && builder.level() instanceof ServerLevel serverLevel) {
            runtimeLevel = serverLevel;
            return serverLevel;
        }
        return runtimeLevel;
    }

    private long lastWarningTime = 0;
    
    // 性能优化：缓存附近容器的位置，避免每 tick 重复搜索
    private final List<BlockPos> cachedContainerPositions = new java.util.ArrayList<>();
    private long lastContainerSearchTime = 0;
    private static final long CONTAINER_CACHE_DURATION_MS = 30000; // 性能优化：缓存 30 秒（从5秒增加到30秒）

    @SuppressWarnings("null")
    private boolean consumeFromNearbyChests(BlockState state) {
        ServerLevel serverLevel = getRuntimeLevel();
        if (serverLevel == null) return false;

        // 使用新的材料管理器检查是否需要材料
        if (!com.xiaoliang.simukraft.utils.MaterialManager.requiresMaterial(state)) {
            return true; // 非材料方块直接允许放置
        }

        // 性能优化：只在区块未就绪时检查，避免每 tick 重复检查
        if (!areChunksReady(serverLevel)) {
            return false; // 区块未就绪，等待下次tick
        }

        long currentTime = System.currentTimeMillis();
        // 如果缓存已过期，或者缓存为空，重新搜索容器
        // 只搜索建筑盒六个面紧贴的容器
        if (currentTime - lastContainerSearchTime > CONTAINER_CACHE_DURATION_MS || cachedContainerPositions.isEmpty()) {
            cachedContainerPositions.clear();
            // 六个方向：上、下、北、南、西、东
            Direction[] containerDirections = new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            for (Direction dir : containerDirections) {
                BlockPos checkPos = buildBoxPos.relative(dir);
                if (serverLevel.isLoaded(checkPos) && ContainerUtils.isContainer(serverLevel, checkPos)) {
                    cachedContainerPositions.add(checkPos);
                    // 检查是否是大箱子，如果是，添加另一半位置
                    BlockPos otherHalf = getOtherChestHalf(serverLevel, checkPos);
                    if (otherHalf != null && !cachedContainerPositions.contains(otherHalf)) {
                        cachedContainerPositions.add(otherHalf);
                    }
                }
            }
            lastContainerSearchTime = currentTime;
        }

        // 使用缓存的容器位置尝试消耗材料
        for (java.util.Iterator<BlockPos> iterator = cachedContainerPositions.iterator(); iterator.hasNext(); ) {
            BlockPos checkPos = iterator.next();

            // 再次检查区块是否已加载
            if (!serverLevel.isLoaded(checkPos)) {
                continue; // 区块仍未加载，跳过
            }

            // 检查该位置是否仍然是容器方块
            if (ContainerUtils.isContainer(serverLevel, checkPos)) {
                if (tryConsumeFromContainer(checkPos, state)) {
                    return true;
                }
            } else {
                // 容器已不在该位置，移除缓存
                iterator.remove();
            }
        }

        // 没有找到材料，检查冷却时间后发送提示
        if (currentTime - lastWarningTime >= ServerConfig.getBuilderWarningCooldownMs()) {
            lastWarningTime = currentTime;
            // 使用材料管理器获取详细的材料需求信息（Component 版本，支持客户端翻译）
            Component materialInfo = com.xiaoliang.simukraft.utils.MaterialManager.getMaterialRequirementComponent(state);
            Component message = Component.translatable("message.simukraft.construction.need_materials", displayName, materialInfo, 1);
            // 使用原版消息系统广播给所有玩家
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(Objects.requireNonNull(message), false);
        }
        return false;
    }

    public BlockInfo getNextBlock() {
        return getNextBlock(null);
    }

    /**
     * 修复：在消耗材料之前检查方块是否已经存在
     * @param serverLevel 服务器世界，用于检查方块状态。如果为null，则跳过已存在方块检查
     */
    public BlockInfo getNextBlock(ServerLevel serverLevel) {
        while (hasNextBlock()) {
            BlockInfo next = blocksToPlace.get(currentBlockIndex);

            // 修复：跳过空气方块（NBT文件中的空气方块不需要放置）
            if (next.state().isAir()) {
                currentBlockIndex++;
                continue;
            }

            // 修复：在消耗材料之前检查方块是否已经存在
            if (serverLevel != null) {
                BlockState currentState = serverLevel.getBlockState(Objects.requireNonNull(next.pos()));
                // 如果目标方块已经存在且类型正确，跳过消耗材料
                if (next.state().getBlock() == currentState.getBlock()) {
                    currentBlockIndex++;
                    continue;
                }
            }

            // 检查是否需要消耗材料
            if (!ServerConfig.isBuilderRequireMaterials()) {
                // 不需要材料，直接返回
                currentBlockIndex++;
                return next;
            }

            // 处理双格方块（床和门）
            BlockState state = next.state();
            if (isDoubleBlock(state)) {
                // 获取双格方块的另一半位置
                BlockPos otherHalfPos = getOtherHalfPos(state, next.pos());
                if (otherHalfPos != null) {
                    // 查找另一半在列表中的索引
                    int otherHalfIndex = findBlockIndex(otherHalfPos, state);
                    if (otherHalfIndex != -1 && otherHalfIndex > currentBlockIndex) {
                        // 这是双格方块的第一个部分，消耗材料并跳过第二个部分
                        if (consumeFromNearbyChests(state)) {
                            currentBlockIndex++;
                            // 同时跳过另一半（不消耗额外材料）
                            if (otherHalfIndex == currentBlockIndex) {
                                currentBlockIndex++;
                            }
                            return next;
                        } else {
                            return null; // 材料不足，暂停建造
                        }
                    } else {
                        // 这是双格方块的第二个部分，已经消耗过材料了，直接跳过
                        currentBlockIndex++;
                        continue;
                    }
                }
            }

            // 普通方块的处理
            if (consumeFromNearbyChests(next.state())) {
                currentBlockIndex++;
                return next;
            } else {
                return null; // 材料不足，暂停建造
            }
        }
        return null;
    }

    /**
     * 检查是否是双格方块（床或门）
     */
    private boolean isDoubleBlock(BlockState state) {
        Block block = state.getBlock();
        // 检查是否是床
        if (block instanceof BedBlock) {
            return true;
        }
        // 检查是否是门
        if (block instanceof DoorBlock) {
            return true;
        }
        return false;
    }

    /**
     * 获取双格方块的另一半位置
     */
    @Nullable
    @SuppressWarnings("null")
    private BlockPos getOtherHalfPos(@Nonnull BlockState state, @Nonnull BlockPos pos) {
        Block block = state.getBlock();

        // 处理床
        if (block instanceof BedBlock) {
            if (state.hasProperty(BED_PART_PROPERTY)) {
                BedPart part = getRequiredValue(state, BED_PART_PROPERTY);
                // 根据朝向获取另一半位置
                if (state.hasProperty(BED_FACING_PROPERTY)) {
                    Direction facing = getRequiredValue(state, BED_FACING_PROPERTY);
                    if (part == BedPart.FOOT) {
                        // 脚在头部后面，所以头部在脚的前面
                        return pos.relative(facing);
                    } else {
                        // 头部在脚的前面，所以脚在头部的后面
                        return pos.relative(facing.getOpposite());
                    }
                }
            }
        }

        // 处理门
        if (block instanceof DoorBlock) {
            if (state.hasProperty(DOOR_HALF_PROPERTY)) {
                DoubleBlockHalf half = getRequiredValue(state, DOOR_HALF_PROPERTY);
                if (half == DoubleBlockHalf.LOWER) {
                    // 下半部分，上半部分在上面
                    return pos.above();
                } else {
                    // 上半部分，下半部分在下面
                    return pos.below();
                }
            }
        }

        return null;
    }

    /**
     * 在blocksToPlace列表中查找指定位置和状态的方块索引
     */
    private int findBlockIndex(BlockPos pos, BlockState state) {
        Integer index = blockIndexLookup.get(pos);
        if (index == null) {
            return -1;
        }
        BlockInfo info = blocksToPlace.get(index);
        return info.state().getBlock() == state.getBlock() ? index : -1;
    }

    @Nonnull
    private static Map<BlockPos, Integer> buildBlockIndexLookup(@Nonnull List<BlockInfo> blocks) {
        Map<BlockPos, Integer> indexLookup = new HashMap<>(Math.max(16, blocks.size()));
        for (int i = 0; i < blocks.size(); i++) {
            indexLookup.put(blocks.get(i).pos(), i);
        }
        return indexLookup;
    }

    @Nonnull
    private static List<BlockPos> collectControlBoxPositions(@Nonnull List<BlockInfo> blocks) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockInfo info : blocks) {
            if (isControlBoxBlock(info.state())) {
                positions.add(info.pos());
            }
        }
        return Objects.requireNonNull(List.copyOf(positions));
    }

    private static boolean isControlBoxBlock(@Nonnull BlockState state) {
        var blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return blockId != null && blockId.getPath().contains("control_box");
    }

    public void markCompleted() {
        this.isCompleted = true;
        // 修复：释放所有强制加载的区块
        releaseForcedChunks();

        // 注意：经验值现在在每放置一个方块时获得，不再在建造完成时获得
    }

    public void cancel() {
        this.isCompleted = true;
        this.currentBlockIndex = blocksToPlace.size();
        // 修复：释放所有强制加载的区块
        releaseForcedChunks();

        if (this.builder != null) {
            // 只清除任务引用，不调用resetToIdle避免循环
            builder.setConstructionTask(null);
        }
    }

    /**
     * 获取大箱子的另一半位置
     * @param level 世界
     * @param pos 箱子位置
     * @return 另一半位置，如果不是大箱子则返回null
     */
    @Nullable
    private BlockPos getOtherChestHalf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) {
            return null;
        }
        
        // 检查四个水平方向是否有相邻的箱子
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                // 检查是否是同一个大箱子的一部分
                if (state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE) && 
                    neighborState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
                    return neighborPos;
                }
            }
        }
        return null;
    }

    /**
     * 性能优化：检查区块是否就绪，避免每 tick 重复检查
     * 只在区块未就绪时执行完整检查逻辑
     */
    private boolean areChunksReady(ServerLevel serverLevel) {
        // 如果已经等待过区块加载，直接返回状态
        if (chunkLoadWaitTicks > 0) {
            return checkAndWaitChunks(serverLevel);
        }
        
        // 快速检查：只检查六个方向是否都已加载
        Direction[] directions = new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction dir : directions) {
            BlockPos checkPos = buildBoxPos.relative(dir);
            if (!serverLevel.isLoaded(checkPos)) {
                // 发现未加载的区块，进入等待模式
                return checkAndWaitChunks(serverLevel);
            }
        }
        return true; // 所有区块都已加载
    }
    
    /**
     * 检查并等待区块加载
     */
    private boolean checkAndWaitChunks(ServerLevel serverLevel) {
        int maxWaitTicks = ServerConfig.getBuilderChunkLoadWaitTicks();
        boolean allChunksLoaded = true;
        
        Direction[] directions = new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction dir : directions) {
            BlockPos checkPos = buildBoxPos.relative(dir);
            if (!serverLevel.isLoaded(checkPos)) {
                allChunksLoaded = false;
                ChunkPos chunkPos = new ChunkPos(checkPos);
                serverLevel.setChunkForced(chunkPos.x, chunkPos.z, true);
                break;
            }
        }

        if (!allChunksLoaded) {
            chunkLoadWaitTicks++;

            if (!hasNotifiedChunkLoading && chunkLoadWaitTicks == 1) {
                hasNotifiedChunkLoading = true;
                Component message = Component.translatable("message.simukraft.construction.chunk_loading", displayName)
                    .withStyle(style -> style.withColor(0xFFFF00));
                serverLevel.getServer().getPlayerList().broadcastSystemMessage(Objects.requireNonNull(message), false);
            }

            if (chunkLoadWaitTicks >= maxWaitTicks) {
                chunkLoadWaitTicks = 0;
            } else {
                return false;
            }
        } else {
            chunkLoadWaitTicks = 0;
        }
        return true;
    }

    /**
     * 修复：释放所有强制加载的区块
     */
    private void releaseForcedChunks() {
        ServerLevel serverLevel = getRuntimeLevel();
        if (serverLevel == null) {
            return;
        }
        // 释放主区块
        if (loadedChunkPos != null) {
            ChunkPos mainChunk = loadedChunkPos;
            serverLevel.setChunkForced(mainChunk.x, mainChunk.z, false);
        }
        // 释放建筑盒周围的区块
        for (ChunkPos chunkPos : forcedChunks) {
            serverLevel.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
        forcedChunks.clear();
    }

    public boolean isCompleted() {
        return isCompleted || currentBlockIndex >= blocksToPlace.size();
    }

    /**
     * 标记当前方块为已完成（用于跳过黑名单方块等情况）
     */
    public void markCurrentBlockComplete() {
        currentBlockIndex++;
    }

    /**
     * 从容器中尝试消耗材料（支持Container接口和IItemHandler Capability）
     * 性能优化：使用直接查找并消耗的方法，避免复制整个容器内容
     * @param containerPos 容器位置
     * @param state 要放置的方块状态
     * @return 是否成功消耗材料
     */
    private boolean tryConsumeFromContainer(BlockPos containerPos, BlockState state) {
        ServerLevel serverLevel = getRuntimeLevel();
        if (serverLevel == null) return false;

        // 性能优化：直接查找并消耗建筑材料，避免复制整个容器内容
        return ContainerUtils.findAndConsumeBuildingMaterial(serverLevel, containerPos, state);
    }

    public CustomEntity getBuilder() {
        return builder;
    }

    public String getBuildingName() {
        return displayName;
    }
    
    public String getInternalBuildingName() {
        return buildingName;
    }
    
    public String getCategory() {
        return category;
    }

    public BlockPos getStartPos() {
        return startPos;
    }

    public BlockPos getBuildBoxPos() {
        return buildBoxPos;
    }

    public Direction getFacing() {
        return facing;
    }

    public double getCost() {
        return cost;
    }

    public int getProgress() {
        if (blocksToPlace.isEmpty()) return 100;
        return (int) ((double) currentBlockIndex / blocksToPlace.size() * 100);
    }

    public int getCurrentBlockIndex() {
        return currentBlockIndex;
    }

    public void setCurrentBlockIndex(int index) {
        // 允许设置索引为 blocksToPlace.size() 表示建造完成
        this.currentBlockIndex = Math.max(0, Math.min(index, blocksToPlace.size()));
    }

    /**
     * 获取总方块数量
     */
    public int getTotalBlocks() {
        return blocksToPlace.size();
    }

    @Nonnull
    public List<BlockPos> getControlBoxPositions() {
        return controlBoxPositions;
    }

    /**
     * 获取显示名称（建筑的中文名称）
     */
    public String getDisplayName() {
        return displayName;
    }

    public record BlockInfo(@Nonnull BlockPos pos, @Nonnull BlockState state) {
    }
}
