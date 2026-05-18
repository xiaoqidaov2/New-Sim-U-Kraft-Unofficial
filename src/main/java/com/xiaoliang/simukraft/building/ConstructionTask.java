package com.xiaoliang.simukraft.building;

import com.xiaoliang.simukraft.client.preview.SchematicNBTLoader;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

public class ConstructionTask {
    private static final long MATERIAL_SLEEP_TICKS = 60L;
    private static final int PREPARATION_PARSE_BATCH_SIZE = 384;
    private static final int PREPARATION_FINALIZE_BATCH_SIZE = 768;
    private static final int PREPARATION_PARSE_BATCH_MAX = 4096;
    private static final int PREPARATION_FINALIZE_BATCH_MAX = 8192;
    private static final int BLOCK_PRIORITY_BUCKETS = 6;
    @Nonnull
    private static final Property<Direction> FACING_PROPERTY = requireProperty(BlockStateProperties.FACING);
    @Nonnull
    private static final Property<Direction> HORIZONTAL_FACING_PROPERTY = requireProperty(BlockStateProperties.HORIZONTAL_FACING);
    @Nonnull
    private static final Property<Direction> HOPPER_FACING_PROPERTY = requireProperty(BlockStateProperties.FACING_HOPPER);
    @Nonnull
    private static final Property<Integer> ROTATION_16_PROPERTY = requireProperty(BlockStateProperties.ROTATION_16);
    @Nonnull
    private static final Property<Direction.Axis> AXIS_PROPERTY = requireProperty(BlockStateProperties.AXIS);
    @Nonnull
    private static final Property<Direction.Axis> HORIZONTAL_AXIS_PROPERTY = requireProperty(BlockStateProperties.HORIZONTAL_AXIS);
    @Nonnull
    private static final Property<?> STAIRS_SHAPE_PROPERTY = requireProperty(BlockStateProperties.STAIRS_SHAPE);
    @Nonnull
    private static final Property<BedPart> BED_PART_PROPERTY = requireProperty(BedBlock.PART);
    @Nonnull
    private static final Property<Direction> BED_FACING_PROPERTY = requireProperty(BedBlock.FACING);
    @Nonnull
    private static final Property<DoubleBlockHalf> DOOR_HALF_PROPERTY = requireProperty(DoorBlock.HALF);
    @Nonnull
    private static final SchematicNBTLoader.SchematicBlock RELEASED_SOURCE_BLOCK =
            new SchematicNBTLoader.SchematicBlock(BlockPos.ZERO, Blocks.AIR.defaultBlockState());

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

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
    private List<SchematicNBTLoader.SchematicBlock> sourceBlocks = nn(new ArrayList<>());
    @Nonnull
    private final List<BlockInfo> blocksToPlace = new ArrayList<>();
    @Nonnull
    private final Map<BlockPos, Integer> blockIndexLookup = new HashMap<>();
    @Nonnull
    private final List<BlockPos> controlBoxPositions = new ArrayList<>();
    @Nonnull
    private final Map<String, Integer> requiredMaterialCounts = new LinkedHashMap<>();
    @Nonnull
    private final List<LayerRange> layerRanges = new ArrayList<>();
    private int currentLayerRangeIndex = 0;
    @Nullable
    private ServerLevel runtimeLevel = null;
    @Nonnull
    private final Set<ChunkPos> requiredWorkflowChunks = new LinkedHashSet<>();
    @Nonnull
    private final Set<ChunkPos> workflowForcedChunks = new LinkedHashSet<>();
    @Nonnull
    private final NavigableMap<Integer, LayerBuckets> pendingLayerBuckets = new TreeMap<>();
    @Nonnull
    private List<Integer> pendingLayerOrder = nn(List.of());
    private int pendingSourceIndex = 0;
    private int pendingLayerOrderIndex = 0;
    private int pendingPriorityIndex = 0;
    private int pendingPriorityBlockIndex = 0;
    private int pendingLayerStartIndex = -1;
    private int totalBlueprintBlocks = 0;
    private boolean preparationComplete = false;
    private boolean persistedForcedChunksReconciled = false;
    // 修复：添加区块加载等待计数器，解决退出重进后箱子找不到的问题
    private int chunkLoadWaitTicks = 0;
    private boolean hasNotifiedChunkLoading = false;
    @Nonnull
    private final BuilderMaterialCache materialCache;
    private boolean waitingForMaterials = false;
    private long nextMaterialCheckTick = Long.MIN_VALUE;

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
        this.materialCache = new BuilderMaterialCache(this.buildBoxPos);
        initializeBlueprintSource();

        if (builder.level() instanceof ServerLevel serverLevel) {
            ensureWorkflowChunksForced(serverLevel);
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
        this.materialCache = new BuilderMaterialCache(this.buildBoxPos);
        initializeBlueprintSource();

        if (level != null) {
            ensureWorkflowChunksForced(level);
        }

    }

    private void initializeBlueprintSource() {
        String filePath = "simukraftbuilding/" + category + "/" + buildingName + ".nbt";
        List<SchematicNBTLoader.SchematicBlock> loadedBlocks = SchematicNBTLoader.loadSchematicBlocks(filePath);
        this.sourceBlocks = loadedBlocks != null ? new ArrayList<>(loadedBlocks) : new ArrayList<>();
        this.totalBlueprintBlocks = this.sourceBlocks.size();
        addStaticWorkflowChunks();
        precomputeBlueprintMetadata();
        if (this.sourceBlocks.isEmpty()) {
            this.preparationComplete = true;
        }
    }

    /**
     * 预计算施工启动阶段必须使用的轻量元数据，避免为超大建筑再额外构造整份投影缓存。
     */
    private void precomputeBlueprintMetadata() {
        for (SchematicNBTLoader.SchematicBlock sourceBlock : sourceBlocks) {
            BlockPos originalPos = Objects.requireNonNull(sourceBlock.pos());
            BlockState sourceState = Objects.requireNonNull(sourceBlock.blockState());
            BlockPos rotatedPos = rotatePosition(originalPos);
            int finalX = startPos.getX() + rotatedPos.getX();
            int finalY = startPos.getY() + rotatedPos.getY();
            int finalZ = startPos.getZ() + rotatedPos.getZ();

            requiredWorkflowChunks.add(new ChunkPos(finalX >> 4, finalZ >> 4));
            collectMaterialRequirement(requiredMaterialCounts, sourceState);

            if (isControlBoxBlock(sourceState)) {
                controlBoxPositions.add(new BlockPos(finalX, finalY, finalZ));
            }
        }
    }

    private void addStaticWorkflowChunks() {
        addRequiredWorkflowChunk(startPos);
        addRequiredWorkflowChunk(buildBoxPos);
        for (Direction direction : Direction.values()) {
            addRequiredWorkflowChunk(nn(buildBoxPos.relative(nn(direction))));
        }
    }

    private void addRequiredWorkflowChunk(@Nonnull BlockPos pos) {
        addRequiredWorkflowChunk(new ChunkPos(pos));
    }

    private void addRequiredWorkflowChunk(@Nonnull ChunkPos chunkPos) {
        requiredWorkflowChunks.add(chunkPos);
    }

    /**
     * 获取方块优先级
     * 0 = 完整方块（最先建造）
     * 1 = 不完整方块（台阶、楼梯等）
     * 2 = 需要支撑的方块（门、活板门、按钮、拉杆、火把、灯笼等）
     * 3 = 重力方块（最后建造）
     * 4 = 液体相关方块（统一最后放置）
     * 5 = 空气方块（拆除任务放到层尾）
     */
    private static int getBlockPriority(BlockState state) {
        Block block = state.getBlock();

        if (state.isAir()) {
            return 5;
        }

        if (!state.getFluidState().isEmpty()) {
            return 4;
        }

        if (block instanceof FallingBlock) {
            return 3;
        }

        if (requiresSupport(block)) {
            return 2;
        }

        if (isIncompleteBlock(block)) {
            return 1;
        }

        return 0;
    }

    private static boolean requiresSupport(Block block) {
        return block instanceof DoorBlock ||
               block instanceof TrapDoorBlock ||
               block instanceof ButtonBlock ||
               block instanceof LeverBlock ||
               block instanceof TorchBlock ||
               block instanceof LanternBlock ||
               block instanceof ChainBlock ||
               block instanceof IronBarsBlock;
    }

    private static boolean isIncompleteBlock(Block block) {
        return block instanceof SlabBlock ||
               block instanceof StairBlock ||
               block instanceof FenceBlock ||
               block instanceof WallBlock;
    }

    public void tickPreparation() {
        if (preparationComplete) {
            return;
        }

        processPendingSourceBlocks(getPreparationParseBatchSize());
        if (pendingSourceIndex >= sourceBlocks.size()) {
            finalizePreparedBlocks(getPreparationFinalizeBatchSize());
        }
    }

    public boolean isPreparing() {
        return !preparationComplete;
    }

    /**
     * 只有真正生成出可施工队列后，才算进入可放置阶段。
     */
    public boolean hasReadyBlocksToPlace() {
        return preparationComplete && currentBlockIndex < blocksToPlace.size();
    }

    private void processPendingSourceBlocks(int budget) {
        int processed = 0;
        while (pendingSourceIndex < sourceBlocks.size() && processed < budget) {
            int sourceIndex = pendingSourceIndex++;
            SchematicNBTLoader.SchematicBlock schematicBlock = sourceBlocks.get(sourceIndex);
            BlockPos originalPos = Objects.requireNonNull(schematicBlock.pos());
            BlockState originalState = Objects.requireNonNull(schematicBlock.blockState());
            releaseProcessedSourceBlock(sourceIndex);

            BlockPos rotatedPos = rotatePosition(originalPos);
            BlockState rotatedState = Objects.requireNonNull(rotateBlockState(originalState));
            BlockPos finalPos = new BlockPos(
                    startPos.getX() + rotatedPos.getX(),
                    startPos.getY() + rotatedPos.getY(),
                    startPos.getZ() + rotatedPos.getZ()
            );

            BlockInfo blockInfo = new BlockInfo(finalPos, rotatedState, originalPos);
            pendingLayerBuckets
                    .computeIfAbsent(finalPos.getY(), ignored -> new LayerBuckets())
                    .add(getBlockPriority(rotatedState), blockInfo);

            processed++;
        }
    }

    private void releaseProcessedSourceBlock(int sourceIndex) {
        if (sourceIndex >= 0 && sourceIndex < sourceBlocks.size()) {
            sourceBlocks.set(sourceIndex, RELEASED_SOURCE_BLOCK);
        }
    }

    private void finalizePreparedBlocks(int budget) {
        if (pendingLayerOrder.isEmpty()) {
            pendingLayerOrder = new ArrayList<>(pendingLayerBuckets.keySet());
            if (pendingLayerOrder.isEmpty()) {
                finishPreparation();
                return;
            }
        }

        int processed = 0;
        while (pendingLayerOrderIndex < pendingLayerOrder.size() && processed < budget) {
            int y = pendingLayerOrder.get(pendingLayerOrderIndex);
            LayerBuckets layerBuckets = pendingLayerBuckets.get(y);
            if (layerBuckets == null) {
                pendingLayerOrderIndex++;
                pendingPriorityIndex = 0;
                pendingPriorityBlockIndex = 0;
                pendingLayerStartIndex = -1;
                continue;
            }

            if (pendingLayerStartIndex < 0) {
                pendingLayerStartIndex = blocksToPlace.size();
            }

            while (pendingPriorityIndex < BLOCK_PRIORITY_BUCKETS && processed < budget) {
                List<BlockInfo> bucket = layerBuckets.get(pendingPriorityIndex);
                while (pendingPriorityBlockIndex < bucket.size() && processed < budget) {
                    BlockInfo blockInfo = bucket.get(pendingPriorityBlockIndex++);
                    blockIndexLookup.put(blockInfo.pos(), blocksToPlace.size());
                    blocksToPlace.add(blockInfo);
                    processed++;
                }

                if (pendingPriorityBlockIndex >= bucket.size()) {
                    pendingPriorityIndex++;
                    pendingPriorityBlockIndex = 0;
                }
            }

            if (pendingPriorityIndex >= BLOCK_PRIORITY_BUCKETS) {
                if (blocksToPlace.size() > pendingLayerStartIndex) {
                    layerRanges.add(new LayerRange(y, pendingLayerStartIndex, blocksToPlace.size() - 1));
                }
                pendingLayerBuckets.remove(y);
                pendingLayerOrderIndex++;
                pendingPriorityIndex = 0;
                pendingPriorityBlockIndex = 0;
                pendingLayerStartIndex = -1;
            }
        }

        if (pendingLayerOrderIndex >= pendingLayerOrder.size()) {
            finishPreparation();
        }
    }

    private void finishPreparation() {
        this.preparationComplete = true;
        this.pendingLayerBuckets.clear();
        this.pendingLayerOrder = nn(List.of());
        this.sourceBlocks = nn(List.of());
        syncCurrentLayerRangeIndex();
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
    @SuppressWarnings("deprecation")
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
            return nn(state.rotate(Rotation.CLOCKWISE_90));
        }

        for (Property<?> property : state.getProperties()) {
            Property<?> candidateProperty = nn(property);
            if (candidateProperty instanceof EnumProperty<?>) {
                Object value = state.getValue(candidateProperty);
                if (value instanceof Direction direction) {
                    Direction newDirection = rotateDirection(direction);
                    return setDirectionEnumValue(state, candidateProperty, newDirection);
                }
            }
        }
        
        return nn(state.rotate(Rotation.CLOCKWISE_90));
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
        return !preparationComplete || currentBlockIndex < blocksToPlace.size();
    }

    /**
     * 恢复持久化任务后重新绑定运行时建筑师。
     */
    public void attachBuilder(@Nullable CustomEntity builder) {
        this.builder = builder;
        if (builder != null && builder.level() instanceof ServerLevel serverLevel) {
            this.runtimeLevel = serverLevel;
            ensureWorkflowChunksForced(serverLevel);
        }
    }

    public void detachBuilder() {
        releaseForcedChunks();
        this.builder = null;
    }

    @Nullable
    private ServerLevel getRuntimeLevel() {
        if (builder != null && builder.level() instanceof ServerLevel serverLevel) {
            runtimeLevel = serverLevel;
            return serverLevel;
        }
        return runtimeLevel;
    }

    private void ensureWorkflowChunksForced(@Nonnull ServerLevel serverLevel) {
        reconcilePersistedForcedChunks(serverLevel);
        Set<ChunkPos> desiredChunks = getDesiredRuntimeChunks();
        Iterator<ChunkPos> iterator = workflowForcedChunks.iterator();
        while (iterator.hasNext()) {
            ChunkPos chunkPos = iterator.next();
            if (desiredChunks.contains(chunkPos)) {
                continue;
            }
            serverLevel.setChunkForced(chunkPos.x, chunkPos.z, false);
            iterator.remove();
        }

        for (ChunkPos chunkPos : desiredChunks) {
            ensureWorkflowChunkForced(serverLevel, nn(chunkPos));
        }
    }

    private void reconcilePersistedForcedChunks(@Nonnull ServerLevel serverLevel) {
        if (persistedForcedChunksReconciled) {
            return;
        }

        for (ChunkPos chunkPos : getManagedWorkflowChunkScope()) {
            long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
            if (serverLevel.getForcedChunks().contains(chunkKey)) {
                workflowForcedChunks.add(chunkPos);
            }
        }
        persistedForcedChunksReconciled = true;
    }

    private void ensureWorkflowChunkForced(@Nonnull ServerLevel serverLevel, @Nonnull ChunkPos chunkPos) {
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!workflowForcedChunks.contains(chunkPos) && !serverLevel.getForcedChunks().contains(chunkKey)) {
            serverLevel.setChunkForced(chunkPos.x, chunkPos.z, true);
            workflowForcedChunks.add(chunkPos);
        }
        serverLevel.getChunk(chunkPos.x, chunkPos.z);
    }

    private long lastWarningTime = 0;

    private boolean consumeFromNearbyChests(@Nonnull BlockState state) {
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

        if (shouldDelayMaterialCheck(serverLevel)) {
            return false;
        }

        if (materialCache.tryConsume(serverLevel, state)) {
            clearMaterialSleep();
            return true;
        }

        enterMaterialSleep(serverLevel);

        // 没有找到材料，检查冷却时间后发送提示
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastWarningTime >= ServerConfig.getBuilderWarningCooldownMs()) {
            lastWarningTime = currentTime;
            // 使用材料管理器获取详细的材料需求信息（Component 版本，支持客户端翻译）
            Component materialInfo = com.xiaoliang.simukraft.utils.MaterialManager.getMaterialRequirementComponent(state);
            Component message = Component.translatable("message.simukraft.construction.need_materials", displayName, materialInfo, 1);
            // 使用原版消息系统广播给所有玩家
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(nn(message), false);
        }
        return false;
    }

    private boolean shouldDelayMaterialCheck(@Nonnull ServerLevel serverLevel) {
        if (!waitingForMaterials) {
            return false;
        }

        if (serverLevel.getGameTime() < nextMaterialCheckTick) {
            return true;
        }

        clearMaterialSleep();
        materialCache.markDirty();
        return false;
    }

    private void enterMaterialSleep(@Nonnull ServerLevel serverLevel) {
        waitingForMaterials = true;
        long wakeTick = serverLevel.getGameTime() + MATERIAL_SLEEP_TICKS;
        if (nextMaterialCheckTick == Long.MIN_VALUE) {
            nextMaterialCheckTick = wakeTick;
            return;
        }
        nextMaterialCheckTick = Math.min(nextMaterialCheckTick, wakeTick);
    }

    private void clearMaterialSleep() {
        waitingForMaterials = false;
        nextMaterialCheckTick = Long.MIN_VALUE;
    }

    public void requestMaterialRefresh(long delayTicks) {
        ServerLevel serverLevel = getRuntimeLevel();
        materialCache.markDirty();
        if (serverLevel == null) {
            clearMaterialSleep();
            return;
        }

        long safeDelayTicks = Math.max(0L, delayTicks);
        if (safeDelayTicks == 0L) {
            clearMaterialSleep();
            return;
        }

        waitingForMaterials = true;
        long wakeTick = serverLevel.getGameTime() + safeDelayTicks;
        if (nextMaterialCheckTick == Long.MIN_VALUE) {
            nextMaterialCheckTick = wakeTick;
            return;
        }
        nextMaterialCheckTick = Math.min(nextMaterialCheckTick, wakeTick);
    }

    public boolean isWaitingForMaterials() {
        return waitingForMaterials;
    }

    public boolean handlesContainerInteraction(@Nonnull ServerLevel serverLevel, @Nonnull BlockPos containerPos) {
        return materialCache.tracksContainer(serverLevel, containerPos);
    }

    @Nullable
    public BlockInfo getNextBlock() {
        return getNextBlock(null);
    }

    /**
     * 修复：在消耗材料之前检查方块是否已经存在
     * @param serverLevel 服务器世界，用于检查方块状态。如果为null，则跳过已存在方块检查
     */
    @Nullable
    public BlockInfo getNextBlock(@Nullable ServerLevel serverLevel) {
        if (!preparationComplete) {
            return null;
        }
        syncCurrentLayerRangeIndex();
        while (hasNextBlock()) {
            LayerRange currentLayerRange = getCurrentLayerRange();
            if (currentLayerRange != null && currentBlockIndex > currentLayerRange.endIndex()) {
                currentLayerRangeIndex++;
                continue;
            }

            BlockInfo next = nn(blocksToPlace.get(currentBlockIndex));

            if (serverLevel != null && shouldSkipWithoutPlacement(serverLevel, next)) {
                currentBlockIndex++;
                continue;
            }

            // menglannnn: 允许放置空气方块（用于拆除/替换已有方块）
            // 空气方块直接返回，不需要消耗材料
            if (next.state().isAir()) {
                currentBlockIndex++;
                return next;
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

    private boolean shouldSkipWithoutPlacement(@Nonnull ServerLevel serverLevel, @Nonnull BlockInfo next) {
        BlockState targetState = next.state();
        BlockPos targetPos = next.pos();

        if (targetState.isAir()) {
            return serverLevel.getBlockState(targetPos).isAir();
        }

        BlockState currentState = serverLevel.getBlockState(targetPos);
        if (targetState.getBlock() == currentState.getBlock()) {
            return true;
        }

        if (!isDoubleBlock(targetState)) {
            return false;
        }

        BlockPos otherHalfPos = getOtherHalfPos(targetState, targetPos);
        if (otherHalfPos == null) {
            return false;
        }

        int otherHalfIndex = findBlockIndex(otherHalfPos, targetState);
        return otherHalfIndex != -1 && otherHalfIndex < currentBlockIndex;
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
                        return pos.relative(nn(facing.getOpposite()));
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

    private void syncCurrentLayerRangeIndex() {
        while (currentLayerRangeIndex < layerRanges.size()) {
            LayerRange currentRange = layerRanges.get(currentLayerRangeIndex);
            if (currentBlockIndex < currentRange.startIndex()) {
                return;
            }
            if (currentBlockIndex <= currentRange.endIndex()) {
                return;
            }
            currentLayerRangeIndex++;
        }
    }

    @Nullable
    private LayerRange getCurrentLayerRange() {
        syncCurrentLayerRangeIndex();
        if (currentLayerRangeIndex < 0 || currentLayerRangeIndex >= layerRanges.size()) {
            return null;
        }
        return layerRanges.get(currentLayerRangeIndex);
    }

    private static boolean isControlBoxBlock(@Nonnull BlockState state) {
        var blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId == null) return false;
        String path = blockId.getPath();
        return path.contains("control_box") || path.contains("farmland_box");
    }

    public void markCompleted() {
        this.isCompleted = true;
        // 修复：释放所有强制加载的区块
        releaseForcedChunks();

        // 注意：经验值现在在每放置一个方块时获得，不再在建造完成时获得
    }

    public void cancel() {
        this.isCompleted = true;
        this.currentBlockIndex = totalBlueprintBlocks;
        // 修复：释放所有强制加载的区块
        releaseForcedChunks();

        if (this.builder != null) {
            // 只清除任务引用，不调用resetToIdle避免循环
            builder.setConstructionTask(null);
        }
    }

    /**
     * 性能优化：检查区块是否就绪，避免每 tick 重复检查
     * 只在区块未就绪时执行完整检查逻辑
     */
    private boolean areChunksReady(@Nonnull ServerLevel serverLevel) {
        ensureWorkflowChunksForced(serverLevel);
        // 如果已经等待过区块加载，直接返回状态
        if (chunkLoadWaitTicks > 0) {
            return checkAndWaitChunks(serverLevel);
        }
        
        for (ChunkPos chunkPos : getDesiredRuntimeChunks()) {
            if (!serverLevel.hasChunk(chunkPos.x, chunkPos.z)) {
                // 发现未加载的区块，进入等待模式
                return checkAndWaitChunks(serverLevel);
            }
        }
        return true; // 所有区块都已加载
    }
    
    /**
     * 检查并等待区块加载
     */
    private boolean checkAndWaitChunks(@Nonnull ServerLevel serverLevel) {
        int maxWaitTicks = ServerConfig.getBuilderChunkLoadWaitTicks();
        boolean allChunksLoaded = true;
        Set<ChunkPos> desiredChunks = getDesiredRuntimeChunks();
        
        for (ChunkPos chunkPos : desiredChunks) {
            if (!serverLevel.hasChunk(chunkPos.x, chunkPos.z)) {
                allChunksLoaded = false;
                ensureWorkflowChunkForced(serverLevel, chunkPos);
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
        for (ChunkPos chunkPos : workflowForcedChunks) {
            serverLevel.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
        workflowForcedChunks.clear();
    }

    public boolean isCompleted() {
        return isCompleted || (preparationComplete && currentBlockIndex >= blocksToPlace.size());
    }

    /**
     * 标记当前方块为已完成（用于跳过黑名单方块等情况）
     */
    public void markCurrentBlockComplete() {
        currentBlockIndex++;
    }

    @Nullable
    public CustomEntity getBuilder() {
        return builder;
    }

    public String getBuildingName() {
        return displayName;
    }

    @Nonnull
    public List<BlockInfo> getBlocksToPlace() {
        return blocksToPlace;
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

    public Set<ChunkPos> getRequiredWorkflowChunks() {
        return Set.copyOf(requiredWorkflowChunks);
    }

    public int getProgress() {
        if (totalBlueprintBlocks <= 0) return 100;
        return (int) ((double) currentBlockIndex / totalBlueprintBlocks * 100);
    }

    public int getCurrentBlockIndex() {
        return currentBlockIndex;
    }

    public void setCurrentBlockIndex(int index) {
        // 允许设置索引为 blocksToPlace.size() 表示建造完成
        this.currentBlockIndex = Math.max(0, Math.min(index, totalBlueprintBlocks));
        this.currentLayerRangeIndex = 0;
        if (preparationComplete) {
            syncCurrentLayerRangeIndex();
        }
    }

    /**
     * 获取总方块数量
     */
    public int getTotalBlocks() {
        return totalBlueprintBlocks;
    }

    @Nonnull
    public List<BlockPos> getControlBoxPositions() {
        return nn(List.copyOf(controlBoxPositions));
    }

    /**
     * 获取显示名称（建筑的中文名称）
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取建造所需的所有材料清单
     * menglannnn: 用于清单物品显示材料需求
     * @return 材料ID到数量的映射
     */
    @Nonnull
    public Map<String, Integer> getRequiredMaterials() {
        return new LinkedHashMap<>(requiredMaterialCounts);
    }

    @Nonnull
    private Set<ChunkPos> getDesiredRuntimeChunks() {
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        addStaticRuntimeChunks(chunks);

        if (!preparationComplete) {
            return chunks;
        }

        LayerRange currentRange = getCurrentLayerRange();
        if (currentRange != null) {
            for (int i = currentRange.startIndex(); i <= currentRange.endIndex() && i < blocksToPlace.size(); i++) {
                chunks.add(new ChunkPos(blocksToPlace.get(i).pos()));
            }
        } else if (currentBlockIndex < blocksToPlace.size()) {
            chunks.add(new ChunkPos(blocksToPlace.get(currentBlockIndex).pos()));
        }

        if (currentBlockIndex > 0 && currentBlockIndex - 1 < blocksToPlace.size()) {
            chunks.add(new ChunkPos(blocksToPlace.get(currentBlockIndex - 1).pos()));
        }
        if (currentBlockIndex + 1 < blocksToPlace.size()) {
            chunks.add(new ChunkPos(blocksToPlace.get(currentBlockIndex + 1).pos()));
        }

        return chunks;
    }

    @Nonnull
    private Set<ChunkPos> getManagedWorkflowChunkScope() {
        return nn(Set.copyOf(requiredWorkflowChunks));
    }

    private void addStaticRuntimeChunks(@Nonnull Set<ChunkPos> chunks) {
        chunks.add(new ChunkPos(startPos));
        chunks.add(new ChunkPos(buildBoxPos));
        for (Direction direction : Direction.values()) {
            chunks.add(new ChunkPos(nn(buildBoxPos.relative(nn(direction)))));
        }
    }

    private void collectMaterialRequirement(@Nonnull Map<String, Integer> materials, @Nonnull BlockState state) {
        // 跳过空气方块
        if (state.isAir()) {
            return;
        }
            
        // 检查是否需要材料
        if (!com.xiaoliang.simukraft.utils.MaterialManager.requiresMaterial(state)) {
            return;
        }

        // 获取方块ID作为材料ID
        String blockId = com.xiaoliang.simukraft.utils.MaterialManager.getBlockId(state.getBlock());
        materials.merge(blockId, 1, (existingCount, addedCount) -> Integer.valueOf(existingCount + addedCount));
    }

    private int getPreparationParseBatchSize() {
        if (totalBlueprintBlocks <= 0) {
            return PREPARATION_PARSE_BATCH_SIZE;
        }
        int dynamicBudget = Math.max(PREPARATION_PARSE_BATCH_SIZE, totalBlueprintBlocks / 16);
        return Math.min(PREPARATION_PARSE_BATCH_MAX, dynamicBudget);
    }

    private int getPreparationFinalizeBatchSize() {
        if (totalBlueprintBlocks <= 0) {
            return PREPARATION_FINALIZE_BATCH_SIZE;
        }
        int dynamicBudget = Math.max(PREPARATION_FINALIZE_BATCH_SIZE, totalBlueprintBlocks / 8);
        return Math.min(PREPARATION_FINALIZE_BATCH_MAX, dynamicBudget);
    }

    private record LayerRange(int y, int startIndex, int endIndex) {
    }

    private static final class LayerBuckets {
        private final List<List<BlockInfo>> buckets = List.of(
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
        );

        private void add(int priority, @Nonnull BlockInfo blockInfo) {
            buckets.get(priority).add(blockInfo);
        }

        @Nonnull
        private List<BlockInfo> get(int priority) {
            return nn(buckets.get(priority));
        }
    }

    public record BlockInfo(@Nonnull BlockPos pos, @Nonnull BlockState state, @Nullable BlockPos originalNbtPos) {
        public BlockInfo(@Nonnull BlockPos pos, @Nonnull BlockState state) {
            this(pos, state, null);
        }
    }
}
