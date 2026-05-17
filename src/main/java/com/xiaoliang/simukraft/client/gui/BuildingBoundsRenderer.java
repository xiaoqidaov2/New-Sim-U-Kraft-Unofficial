package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.PlacedBuildingManager;
import com.xiaoliang.simukraft.client.ClientCityChunkData;
import com.xiaoliang.simukraft.client.preview.BuildingPreviewManager;
import com.xiaoliang.simukraft.client.preview.SchematicBlockData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建筑界限渲染器（menglannnn: 在客户端渲染建筑界限和预览侵入检测）
 * 功能：
 * 1. 已放置建筑界限：白色线条框
 * 2. 建造预览时检测与附近建筑界限的侵入关系，以不同颜色高亮显示冲突方块
 */
@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class BuildingBoundsRenderer {
    // 存储需要显示界限的控制盒位置
    private static final Map<BlockPos, Boolean> buildingBoundsVisible = new ConcurrentHashMap<>();

    // 颜色定义 (ARGB)
    private static final int COLOR_BUILDING_BOUNDS = 0xFFFFFFFF; // 白色
    private static final int COLOR_INTRUSION_AIR = 0x60FFFF00;   // 半透明黄色（侵入空气）
    private static final int COLOR_INTRUSION_BLOCK = 0x60FF0000; // 半透明红色（侵入方块）
    private static final int COLOR_INTRUSION_SAME = 0x60FF8000;  // 半透明橙色（同种类方块）
    private static final int COLOR_CITY_BORDER = 0x553C66FF;
    private static final int CITY_BORDER_MIN_HEIGHT = -64;
    private static final int CITY_BORDER_MAX_HEIGHT = 320;
    private static final double CITY_BORDER_RENDER_DISTANCE = 192.0;

    // 预览侵入检测开关（仅放置者可见）
    private static UUID previewPlayerId = null;

    /**
     * 设置建筑界限显示状态
     * @param controlBoxPos 控制盒位置
     * @param visible 是否显示
     */
    public static void setBuildingBoundsVisible(BlockPos controlBoxPos, boolean visible) {
        BlockPos immutablePos = controlBoxPos.immutable();
        if (visible) {
            buildingBoundsVisible.put(immutablePos, true);
        } else {
            buildingBoundsVisible.remove(immutablePos);
        }
    }

    /**
     * 检查建筑界限是否正在显示
     * @param controlBoxPos 控制盒位置
     * @return 是否显示
     */
    public static boolean isBuildingBoundsVisible(BlockPos controlBoxPos) {
        return buildingBoundsVisible.containsKey(controlBoxPos.immutable());
    }

    /**
     * 设置当前正在放置预览的玩家ID（menglannnn: 用于限制侵入检测仅放置者可见）
     * @param playerId 玩家UUID，null表示没有玩家正在放置
     */
    public static void setPreviewPlayerId(UUID playerId) {
        previewPlayerId = playerId;
    }

    /**
     * 获取当前正在放置预览的玩家ID
     */
    public static UUID getPreviewPlayerId() {
        return previewPlayerId;
    }

    /**
     * 渲染事件处理
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        // 渲染已放置建筑的界限（白色线条）
        if (!buildingBoundsVisible.isEmpty()) {
            for (BlockPos controlBoxPos : buildingBoundsVisible.keySet()) {
                renderBuildingBounds(poseStack, cameraPos, controlBoxPos);
            }
        }

        // 渲染预览侵入检测（仅放置者可见）
        if (BuildingPreviewManager.isPreviewActive() && previewPlayerId != null
                && mc.player.getUUID().equals(previewPlayerId)) {
            renderCityBoundaryWall(poseStack, cameraPos, mc);
            renderPreviewIntrusions(poseStack, cameraPos, mc);
        }
    }

    private static void renderCityBoundaryWall(PoseStack poseStack, Vec3 cameraPos, Minecraft mc) {
        ClientCityChunkData cityData = ClientCityChunkData.getInstance();
        Set<Long> cityChunks = cityData.getCurrentCityChunks();
        if (cityChunks.isEmpty()) return;

        Set<Long> renderedEdges = new HashSet<>();
        double maxDistanceSqr = CITY_BORDER_RENDER_DISTANCE * CITY_BORDER_RENDER_DISTANCE;

        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float red = ((COLOR_CITY_BORDER >> 16) & 0xFF) / 255.0f;
        float green = ((COLOR_CITY_BORDER >> 8) & 0xFF) / 255.0f;
        float blue = (COLOR_CITY_BORDER & 0xFF) / 255.0f;
        float alpha = ((COLOR_CITY_BORDER >> 24) & 0xFF) / 255.0f;
        Matrix4f matrix = poseStack.last().pose();

        for (long chunkLong : cityChunks) {
            int chunkX = (int) chunkLong;
            int chunkZ = (int) (chunkLong >> 32);
            if (!isChunkNearCamera(chunkX, chunkZ, cameraPos, maxDistanceSqr)) {
                continue;
            }

            addBoundaryFaceIfNeeded(buffer, matrix, renderedEdges, cityChunks, chunkX, chunkZ, 0, -1, red, green, blue, alpha, cameraPos);
            addBoundaryFaceIfNeeded(buffer, matrix, renderedEdges, cityChunks, chunkX, chunkZ, 0, 1, red, green, blue, alpha, cameraPos);
            addBoundaryFaceIfNeeded(buffer, matrix, renderedEdges, cityChunks, chunkX, chunkZ, -1, 0, red, green, blue, alpha, cameraPos);
            addBoundaryFaceIfNeeded(buffer, matrix, renderedEdges, cityChunks, chunkX, chunkZ, 1, 0, red, green, blue, alpha, cameraPos);
        }

        tesselator.end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static boolean isChunkNearCamera(int chunkX, int chunkZ, Vec3 cameraPos, double maxDistanceSqr) {
        double centerX = chunkX * 16.0 + 8.0;
        double centerZ = chunkZ * 16.0 + 8.0;
        double dx = centerX - cameraPos.x;
        double dz = centerZ - cameraPos.z;
        return dx * dx + dz * dz <= maxDistanceSqr;
    }

    private static void addBoundaryFaceIfNeeded(BufferBuilder buffer, Matrix4f matrix, Set<Long> renderedEdges,
                                                Set<Long> cityChunks, int chunkX, int chunkZ, int offsetX, int offsetZ,
                                                float red, float green, float blue, float alpha, Vec3 cameraPos) {
        long neighbor = net.minecraft.world.level.ChunkPos.asLong(chunkX + offsetX, chunkZ + offsetZ);
        if (cityChunks.contains(neighbor)) {
            return;
        }

        long edgeKey = makeBoundaryEdgeKey(chunkX, chunkZ, offsetX, offsetZ);
        if (!renderedEdges.add(edgeKey)) {
            return;
        }

        double minY = CITY_BORDER_MIN_HEIGHT - cameraPos.y;
        double maxY = CITY_BORDER_MAX_HEIGHT - cameraPos.y;
        double minX = chunkX * 16.0 - cameraPos.x;
        double maxX = minX + 16.0;
        double minZ = chunkZ * 16.0 - cameraPos.z;
        double maxZ = minZ + 16.0;

        if (offsetZ < 0) {
            drawQuad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        } else if (offsetZ > 0) {
            drawQuad(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
        } else if (offsetX < 0) {
            drawQuad(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, red, green, blue, alpha);
        } else if (offsetX > 0) {
            drawQuad(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, red, green, blue, alpha);
        }
    }

    private static long makeBoundaryEdgeKey(int chunkX, int chunkZ, int offsetX, int offsetZ) {
        int edgeX = offsetX < 0 ? chunkX * 2 : offsetX > 0 ? chunkX * 2 + 2 : chunkX * 2 + 1;
        int edgeZ = offsetZ < 0 ? chunkZ * 2 : offsetZ > 0 ? chunkZ * 2 + 2 : chunkZ * 2 + 1;
        return (((long) edgeX) << 32) ^ (edgeZ & 0xFFFFFFFFL);
    }

    private static void drawQuad(BufferBuilder buffer, Matrix4f matrix,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 double x3, double y3, double z3,
                                 double x4, double y4, double z4,
                                 float red, float green, float blue, float alpha) {
        buffer.vertex(matrix, (float)x1, (float)y1, (float)z1).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y2, (float)z2).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)x3, (float)y3, (float)z3).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)x4, (float)y4, (float)z4).color(red, green, blue, alpha).endVertex();
    }

    /**
     * 渲染建筑界限（白色线条框）
     * menglannnn: minPos/maxPos是相对于控制盒的坐标，需要加上控制盒位置转换为世界坐标
     */
    private static void renderBuildingBounds(PoseStack poseStack, Vec3 cameraPos, BlockPos controlBoxPos) {
        PlacedBuildingManager.PlacedBuildingData building =
            PlacedBuildingManager.getBuildingByControlBox(controlBoxPos);

        if (building == null) {
            return;
        }

        // 计算建筑边界（相对坐标 + 控制盒位置 = 世界坐标）
        BlockPos minPos = building.minPos.offset(controlBoxPos);
        BlockPos maxPos = building.maxPos.offset(controlBoxPos);

        // 创建AABB
        AABB bounds = new AABB(
            minPos.getX(), minPos.getY(), minPos.getZ(),
            maxPos.getX() + 1, maxPos.getY() + 1, maxPos.getZ() + 1
        );

        // 渲染白色线条框
        renderBoxOutline(poseStack, cameraPos, bounds, COLOR_BUILDING_BOUNDS);
    }

    /**
     * 渲染预览图与附近建筑的侵入检测（menglannnn: 检测预览方块是否侵入已有建筑界限）
     * 仅放置预览图的玩家可见，同时显示被侵入建筑的白色界限框
     */
    private static void renderPreviewIntrusions(PoseStack poseStack, Vec3 cameraPos, Minecraft mc) {
        List<SchematicBlockData> previewBlocks = BuildingPreviewManager.getActiveBlocks();
        if (previewBlocks.isEmpty()) return;

        // 获取所有已放置的建筑
        var allBuildings = PlacedBuildingManager.getAllBuildings();
        if (allBuildings.isEmpty()) return;

        String currentWorldId = mc.level.dimension().location().toString();

        // 记录被侵入的建筑，用于后续渲染界限框（menglannnn: 避免重复渲染同一建筑的界限）
        java.util.Set<PlacedBuildingManager.PlacedBuildingData> intrudedBuildings = new java.util.HashSet<>();

        for (SchematicBlockData previewBlock : previewBlocks) {
            BlockPos pos = previewBlock.pos();
            BlockState previewState = previewBlock.blockState();

            for (PlacedBuildingManager.PlacedBuildingData building : allBuildings) {
                // 只检测同一世界的建筑
                if (!building.worldId.equals(currentWorldId)) continue;

                // 检查预览方块是否在该建筑界限内
                if (isPosInBuildingBounds(pos, building)) {
                    BlockState worldState = mc.level.getBlockState(pos);
                    int color;

                    if (worldState.isAir()) {
                        // 侵入部分是空气 -> 半透明黄色
                        color = COLOR_INTRUSION_AIR;
                    } else if (worldState.getBlock() == previewState.getBlock()) {
                        // 侵入部分是同种类方块 -> 半透明橙色
                        color = COLOR_INTRUSION_SAME;
                    } else {
                        // 侵入部分是其他方块 -> 半透明红色
                        color = COLOR_INTRUSION_BLOCK;
                    }

                    // 渲染该位置的半透明方块面
                    renderIntrusiveBlock(poseStack, cameraPos, pos, color);

                    // 记录被侵入的建筑
                    intrudedBuildings.add(building);
                    break; // 找到一个侵入即可，不需要检查其他建筑
                }
            }
        }

        // 渲染所有被侵入建筑的白色界限框（menglannnn: 让玩家清楚看到被侵入的建筑范围）
        for (PlacedBuildingManager.PlacedBuildingData building : intrudedBuildings) {
            renderBuildingBoundsForIntrusion(poseStack, cameraPos, building);
        }
    }

    /**
     * 渲染被侵入建筑的界限框（menglannnn: 专用于侵入检测时显示被侵入建筑的范围）
     */
    private static void renderBuildingBoundsForIntrusion(PoseStack poseStack, Vec3 cameraPos,
                                                          PlacedBuildingManager.PlacedBuildingData building) {
        // 计算建筑边界（相对坐标 + 控制盒位置 = 世界坐标）
        BlockPos minPos = building.minPos.offset(building.controlBoxPos);
        BlockPos maxPos = building.maxPos.offset(building.controlBoxPos);

        // 创建AABB
        AABB bounds = new AABB(
            minPos.getX(), minPos.getY(), minPos.getZ(),
            maxPos.getX() + 1, maxPos.getY() + 1, maxPos.getZ() + 1
        );

        // 渲染白色线条框
        renderBoxOutline(poseStack, cameraPos, bounds, COLOR_BUILDING_BOUNDS);
    }

    /**
     * 检查位置是否在建筑界限内（menglannnn: 包含边界）
     */
    private static boolean isPosInBuildingBounds(BlockPos pos, PlacedBuildingManager.PlacedBuildingData building) {
        BlockPos relPos = pos.subtract(building.controlBoxPos);
        return relPos.getX() >= building.minPos.getX() && relPos.getX() <= building.maxPos.getX()
            && relPos.getY() >= building.minPos.getY() && relPos.getY() <= building.maxPos.getY()
            && relPos.getZ() >= building.minPos.getZ() && relPos.getZ() <= building.maxPos.getZ();
    }

    /**
     * 渲染侵入方块的高亮面（menglannnn: 半透明彩色面覆盖在方块上）
     */
    private static void renderIntrusiveBlock(PoseStack poseStack, Vec3 cameraPos, BlockPos pos, int color) {
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        float alpha = ((color >> 24) & 0xFF) / 255.0f;

        double minX = pos.getX() - cameraPos.x;
        double minY = pos.getY() - cameraPos.y;
        double minZ = pos.getZ() - cameraPos.z;
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;

        Matrix4f matrix = poseStack.last().pose();

        // 六个面
        // 底面 (Y-)
        buffer.vertex(matrix, (float)minX, (float)minY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)minY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)minY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)minX, (float)minY, (float)maxZ).color(red, green, blue, alpha).endVertex();

        // 顶面 (Y+)
        buffer.vertex(matrix, (float)minX, (float)maxY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)minX, (float)maxY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)maxY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)maxY, (float)minZ).color(red, green, blue, alpha).endVertex();

        // 前面 (Z-)
        buffer.vertex(matrix, (float)minX, (float)minY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)minX, (float)maxY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)maxY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)minY, (float)minZ).color(red, green, blue, alpha).endVertex();

        // 后面 (Z+)
        buffer.vertex(matrix, (float)minX, (float)minY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)minY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)maxY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)minX, (float)maxY, (float)maxZ).color(red, green, blue, alpha).endVertex();

        // 左面 (X-)
        buffer.vertex(matrix, (float)minX, (float)minY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)minX, (float)minY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)minX, (float)maxY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)minX, (float)maxY, (float)minZ).color(red, green, blue, alpha).endVertex();

        // 右面 (X+)
        buffer.vertex(matrix, (float)maxX, (float)minY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)maxY, (float)minZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)maxY, (float)maxZ).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, (float)maxX, (float)minY, (float)maxZ).color(red, green, blue, alpha).endVertex();

        tesselator.end();

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * 渲染立方体边框（线条）
     */
    private static void renderBoxOutline(PoseStack poseStack, Vec3 cameraPos, AABB box, int color) {
        // 设置渲染状态
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // 使用位置颜色着色器
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // 提取颜色分量
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        float alpha = ((color >> 24) & 0xFF) / 255.0f;

        // 相对于相机的偏移
        double minX = box.minX - cameraPos.x;
        double minY = box.minY - cameraPos.y;
        double minZ = box.minZ - cameraPos.z;
        double maxX = box.maxX - cameraPos.x;
        double maxY = box.maxY - cameraPos.y;
        double maxZ = box.maxZ - cameraPos.z;

        Matrix4f matrix = poseStack.last().pose();

        // 绘制12条边
        // 底面4条边
        drawLine(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, red, green, blue, alpha);

        // 顶面4条边
        drawLine(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue, alpha);

        // 垂直4条边
        drawLine(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);

        tesselator.end();

        // 恢复渲染状态
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * 绘制线段
     */
    private static void drawLine(BufferBuilder buffer, Matrix4f matrix,
                                  double x1, double y1, double z1,
                                  double x2, double y2, double z2,
                                  float r, float g, float b, float a) {
        buffer.vertex(matrix, (float)x1, (float)y1, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y2, (float)z2).color(r, g, b, a).endVertex();
    }
}
