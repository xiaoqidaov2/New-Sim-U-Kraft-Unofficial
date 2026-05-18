package com.xiaoliang.simukraft.client.preview;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.client.ClientCityChunkData;
import com.xiaoliang.simukraft.client.freecamera.FreeCameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("null")
public class BuildingPreviewManager {
    private static final List<SchematicBlockData> schematicBlocks = new ArrayList<>();
    private static final List<SchematicBlockData> surfaceBlocks = new ArrayList<>();
    private static BlockPos previewOrigin = BlockPos.ZERO;
    private static int rotation = 0;
    private static boolean isPreviewActive = false;
    private static String currentBuildingName = "";
    private static String currentCategory = "";
    private static String currentFileName = "";
    private static int previewRevision = 0;
    private static PreviewDimensions previewDimensions = null;

    private static PreviewMesh cachedMesh = null;
    private static BlockPos meshBuildOrigin = BlockPos.ZERO;

    public static void startPreview(String buildingName, String category, String fileName, String size, BlockPos origin) {
        if (!isOriginAllowed(origin)) {
            return;
        }
        currentBuildingName = buildingName;
        currentCategory = category;
        currentFileName = fileName;
        previewOrigin = origin;
        rotation = 0;
        isPreviewActive = true;
        previewDimensions = parsePreviewDimensions(size);
        loadBlocks();
        Simukraft.LOGGER.info(Component.translatable("message.preview.building.start", buildingName, origin).getString());
    }

    public static void loadBlocks() {
        schematicBlocks.clear();
        surfaceBlocks.clear();

        String filePath = "simukraftbuilding/" + currentCategory + "/" + currentFileName + ".nbt";
        List<SchematicNBTLoader.SchematicBlock> blocks = SchematicNBTLoader.loadSchematicBlocks(filePath);

        for (SchematicNBTLoader.SchematicBlock block : blocks) {
            BlockPos pos = block.pos();
            BlockPos rotatedPos = rotatePosition(pos, rotation);
            BlockPos newPos = new BlockPos(
                rotatedPos.getX() + previewOrigin.getX(),
                rotatedPos.getY() + previewOrigin.getY(),
                rotatedPos.getZ() + previewOrigin.getZ()
            );

            BlockState rotatedState = rotateBlockState(block.blockState(), rotation);
            schematicBlocks.add(new SchematicBlockData(newPos, rotatedState, 15728880, true));
        }

        rebuildSurfaceBlocks();
        previewRevision++;
        Simukraft.LOGGER.info(Component.translatable("message.preview.building.loaded", schematicBlocks.size()).getString());
        rebuildMesh();
    }

    private static void rebuildMesh() {
        closeMesh();
        meshBuildOrigin = previewOrigin;
        cachedMesh = PreviewMeshBuilder.build(schematicBlocks);
    }

    private static void closeMesh() {
        if (cachedMesh != null) {
            cachedMesh.close();
            cachedMesh = null;
        }
    }

    public static void movePreview(Direction direction) {
        if (!isPreviewActive) return;

        BlockPos offset = switch (direction) {
            case NORTH -> new BlockPos(0, 0, -1);
            case SOUTH -> new BlockPos(0, 0, 1);
            case WEST -> new BlockPos(-1, 0, 0);
            case EAST -> new BlockPos(1, 0, 0);
            case UP -> new BlockPos(0, 1, 0);
            case DOWN -> new BlockPos(0, -1, 0);
        };

        // 先更新方块位置，再更新原点
        BlockPos newOrigin = previewOrigin.offset(offset);
        if (!isOriginAllowed(newOrigin)) {
            return;
        }
        updateBlockPositions(offset);
        previewOrigin = newOrigin;
        Simukraft.LOGGER.info(Component.translatable("message.preview.building.moved", previewOrigin).getString());
    }

    public static void movePreviewVertical(int offset) {
        if (!isPreviewActive) return;

        BlockPos offsetPos = new BlockPos(0, offset, 0);
        BlockPos newOrigin = previewOrigin.offset(offsetPos);
        if (!isOriginAllowed(newOrigin)) {
            return;
        }
        updateBlockPositions(offsetPos);
        previewOrigin = newOrigin;
        Simukraft.LOGGER.info(Component.translatable("message.preview.building.moved_vertical", previewOrigin).getString());
    }

    /**
     * 基于相机方向移动预览图
     * @param right 左右移动量（正为右，负为左）
     * @param forward 前后移动量（正为前，负为后）
     */
    public static void movePreviewRelativeToCamera(int right, int forward) {
        if (!isPreviewActive) return;

        // 获取相机的 yaw 角度
        float yaw = FreeCameraManager.getYaw();
        float yawRad = (float) Math.toRadians(yaw);

        float cosYaw = (float) Math.cos(yawRad);
        float sinYaw = (float) Math.sin(yawRad);

        // 计算移动向量
        // forward > 0 表示向前，right > 0 表示向右
        int moveX = (int) Math.round(-sinYaw * forward - cosYaw * right);
        int moveZ = (int) Math.round(cosYaw * forward - sinYaw * right);

        // 如果计算结果为零但输入不为零，至少移动一格
        if (moveX == 0 && moveZ == 0) {
            if (forward != 0 || right != 0) {
                // 根据主要方向强制移动一格
                if (Math.abs(forward) >= Math.abs(right)) {
                    // 主要向前/后移动
                    moveX = (int) Math.round(-sinYaw * forward);
                    moveZ = (int) Math.round(cosYaw * forward);
                } else {
                    // 主要向左/右移动
                    moveX = (int) Math.round(-cosYaw * right);
                    moveZ = (int) Math.round(-sinYaw * right);
                }
            }
        }

        BlockPos offset = new BlockPos(moveX, 0, moveZ);
        BlockPos newOrigin = previewOrigin.offset(offset);
        if (!isOriginAllowed(newOrigin)) {
            return;
        }
        updateBlockPositions(offset);
        previewOrigin = newOrigin;
        Simukraft.LOGGER.info(Component.translatable("message.preview.building.moved_camera", previewOrigin, yaw).getString());
    }

    private static boolean isOriginAllowed(BlockPos origin) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return true;
        }
        ClientCityChunkData data = ClientCityChunkData.getInstance();
        java.util.UUID playerCityId = data.getCityId();
        if (playerCityId == null) {
            minecraft.player.displayClientMessage(Component.translatable("message.simukraft.city_placement.outside_city"), true);
            return false;
        }
        java.util.UUID owner = data.getChunkOwner(new net.minecraft.world.level.ChunkPos(origin).toLong());
        if (playerCityId.equals(owner)) {
            return true;
        }
        minecraft.player.displayClientMessage(Component.translatable("message.simukraft.city_placement.outside_city"), true);
        return false;
    }

    /**
     * 检查整个建筑范围是否都在玩家城市领地内
     * @return 如果所有方块都在领地内返回true，否则返回false
     */
    public static boolean isEntireBuildingInCityTerritory() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return true;
        }
        ClientCityChunkData data = ClientCityChunkData.getInstance();
        java.util.UUID playerCityId = data.getCityId();
        if (playerCityId == null) {
            return false;
        }

        for (SchematicBlockData block : schematicBlocks) {
            BlockPos pos = block.pos();
            java.util.UUID owner = data.getChunkOwner(new net.minecraft.world.level.ChunkPos(pos).toLong());
            if (!playerCityId.equals(owner)) {
                return false;
            }
        }
        return true;
    }

    public static void rotatePreview() {
        if (!isPreviewActive) return;

        rotation = (rotation + 90) % 360;
        loadBlocks();
        Simukraft.LOGGER.info(Component.translatable("message.preview.building.rotated", rotation).getString());
    }

    private static void updateBlockPositions(BlockPos offset) {
        List<SchematicBlockData> oldBlocks = new ArrayList<>(schematicBlocks);
        List<SchematicBlockData> oldSurfaceBlocks = new ArrayList<>(surfaceBlocks);
        schematicBlocks.clear();
        surfaceBlocks.clear();

        for (SchematicBlockData block : oldBlocks) {
            BlockPos newPos = block.pos().offset(offset);
            schematicBlocks.add(new SchematicBlockData(newPos, block.blockState(), block.packedLight(), block.translucent()));
        }
        for (SchematicBlockData block : oldSurfaceBlocks) {
            BlockPos newPos = block.pos().offset(offset);
            surfaceBlocks.add(new SchematicBlockData(newPos, block.blockState(), block.packedLight(), block.translucent()));
        }
        previewRevision++;
    }

    private static BlockPos rotatePosition(BlockPos pos, int rotation) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        for (int i = 0; i < rotation / 90; i++) {
            int newX = -z;
            int newZ = x;
            x = newX;
            z = newZ;
        }

        return new BlockPos(x, y, z);
    }

    private static BlockState rotateBlockState(BlockState state, int rotation) {
        if (state.isAir()) {
            return state;
        }

        int rotations = (rotation % 360) / 90;
        if (rotations == 0) {
            return state;
        }

        BlockState rotatedState = state;

        for (int i = 0; i < rotations; i++) {
            rotatedState = rotateBlockStateOnce(rotatedState);
        }

        return rotatedState;
    }

    @SuppressWarnings({"unchecked", "deprecation", "unused"})
    private static BlockState rotateBlockStateOnce(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            Direction newFacing = rotateDirection(facing);
            return state.setValue(BlockStateProperties.FACING, newFacing);
        }

        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction newFacing = rotateHorizontalDirection(facing);
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, newFacing);
        }

        if (state.hasProperty(BlockStateProperties.FACING_HOPPER)) {
            Direction facing = state.getValue(BlockStateProperties.FACING_HOPPER);
            Direction newFacing = rotateDirection(facing);
            return state.setValue(BlockStateProperties.FACING_HOPPER, newFacing);
        }

        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            int rot = state.getValue(BlockStateProperties.ROTATION_16);
            int newRotation = (rot + 4) % 16;
            return state.setValue(BlockStateProperties.ROTATION_16, newRotation);
        }

        if (state.hasProperty(BlockStateProperties.AXIS)) {
            net.minecraft.core.Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
            net.minecraft.core.Direction.Axis newAxis = rotateAxis(axis);
            return state.setValue(BlockStateProperties.AXIS, newAxis);
        }

        if (state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            net.minecraft.core.Direction.Axis axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
            net.minecraft.core.Direction.Axis newAxis = rotateHorizontalAxis(axis);
            return state.setValue(BlockStateProperties.HORIZONTAL_AXIS, newAxis);
        }

        if (state.hasProperty(BlockStateProperties.STAIRS_SHAPE)) {
            return state.rotate(Rotation.CLOCKWISE_90);
        }

        for (Property<?> property : state.getProperties()) {
            if (property instanceof EnumProperty<?> enumProperty) {
                Object value = state.getValue(property);
                if (value instanceof Direction direction) {
                    Direction newDirection = rotateDirection(direction);
                    return state.setValue((EnumProperty<Direction>) property, newDirection);
                }
            }
        }

        return state.rotate(Rotation.CLOCKWISE_90);
    }

    private static Direction rotateDirection(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
        };
    }

    private static Direction rotateHorizontalDirection(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> direction;
        };
    }

    private static net.minecraft.core.Direction.Axis rotateAxis(net.minecraft.core.Direction.Axis axis) {
        return switch (axis) {
            case X -> net.minecraft.core.Direction.Axis.Z;
            case Z -> net.minecraft.core.Direction.Axis.X;
            case Y -> net.minecraft.core.Direction.Axis.Y;
        };
    }

    private static net.minecraft.core.Direction.Axis rotateHorizontalAxis(net.minecraft.core.Direction.Axis axis) {
        return switch (axis) {
            case X -> net.minecraft.core.Direction.Axis.Z;
            case Z -> net.minecraft.core.Direction.Axis.X;
            case Y -> net.minecraft.core.Direction.Axis.Y;
        };
    }

    public static void clearPreview() {
        schematicBlocks.clear();
        surfaceBlocks.clear();
        isPreviewActive = false;
        rotation = 0;
        previewDimensions = null;
        previewRevision++;
        closeMesh();
        Simukraft.LOGGER.info(Component.translatable("message.preview.building.cleared").getString());
    }

    public static List<SchematicBlockData> getActiveBlocks() {
        return new ArrayList<>(schematicBlocks);
    }

    public static List<SchematicBlockData> getSurfaceBlocks() {
        return surfaceBlocks;
    }

    public static List<BlockPos> getBlockPositions() {
        List<BlockPos> positions = new ArrayList<>();
        for (SchematicBlockData block : schematicBlocks) {
            positions.add(block.pos());
        }
        return positions;
    }

    public static List<BlockState> getBlockStates() {
        List<BlockState> states = new ArrayList<>();
        for (SchematicBlockData block : schematicBlocks) {
            states.add(block.blockState());
        }
        return states;
    }

    public static BlockPos getPreviewOrigin() {
        return previewOrigin;
    }

    public static int getRotation() {
        return rotation;
    }

    public static boolean isPreviewActive() {
        return isPreviewActive;
    }

    public static String getCurrentBuildingName() {
        return currentBuildingName;
    }

    public static String getCurrentCategory() {
        return currentCategory;
    }

    public static String getCurrentFileName() {
        return currentFileName;
    }

    public static int getBlockCount() {
        return schematicBlocks.size();
    }

    public static int getPreviewRevision() {
        return previewRevision;
    }

    public static PreviewMesh getCachedMesh() {
        return cachedMesh;
    }

    public static BlockPos getMeshBuildOrigin() {
        return meshBuildOrigin;
    }

    public static AABB getPreviewBounds() {
        if (!isPreviewActive || previewDimensions == null) {
            return null;
        }

        PreviewDimensions rotatedDimensions = previewDimensions.rotate(rotation);
        return new AABB(
                previewOrigin.getX(),
                previewOrigin.getY(),
                previewOrigin.getZ(),
                previewOrigin.getX() + rotatedDimensions.width(),
                previewOrigin.getY() + rotatedDimensions.height(),
                previewOrigin.getZ() + rotatedDimensions.depth()
        );
    }

    private static void rebuildSurfaceBlocks() {
        surfaceBlocks.clear();
        Map<BlockPos, BlockState> stateMap = new HashMap<>(schematicBlocks.size());
        for (SchematicBlockData block : schematicBlocks) {
            stateMap.put(block.pos(), block.blockState());
        }

        for (SchematicBlockData block : schematicBlocks) {
            BlockState state = block.blockState();
            if (!state.canOcclude()) {
                surfaceBlocks.add(block);
                continue;
            }

            BlockPos pos = block.pos();
            boolean isSurface = false;
            for (Direction direction : Direction.values()) {
                BlockState neighborState = stateMap.get(pos.relative(direction));
                if (neighborState == null || !neighborState.canOcclude()) {
                    isSurface = true;
                    break;
                }
            }

            if (isSurface) {
                surfaceBlocks.add(block);
            }
        }
    }

    private static PreviewDimensions parsePreviewDimensions(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }

        String normalized = size.toLowerCase().replace(" ", "");
        String[] parts = normalized.split("x");
        if (parts.length != 3) {
            return null;
        }

        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            int depth = Integer.parseInt(parts[2]);
            if (width <= 0 || height <= 0 || depth <= 0) {
                return null;
            }
            return new PreviewDimensions(width, height, depth);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record PreviewDimensions(int width, int height, int depth) {
        private int volume() {
            return width * height * depth;
        }

        private PreviewDimensions rotate(int rotation) {
            int normalizedRotation = Math.floorMod(rotation, 360);
            if (normalizedRotation == 90 || normalizedRotation == 270) {
                return new PreviewDimensions(depth, height, width);
            }
            return this;
        }
    }
}
