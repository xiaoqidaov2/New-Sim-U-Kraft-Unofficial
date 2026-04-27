package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.PlacedBuildingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建筑界限渲染器（menglannnn: 在客户端渲染建筑界限）
 * 建筑界限：白色线条框
 */
@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class BuildingBoundsRenderer {
    // 存储需要显示界限的控制盒位置
    private static final Map<BlockPos, Boolean> buildingBoundsVisible = new ConcurrentHashMap<>();

    // 颜色定义 (ARGB)
    private static final int COLOR_BUILDING_BOUNDS = 0xFFFFFFFF; // 白色

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
     * 渲染事件处理
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 检查是否有需要渲染的界限
        if (buildingBoundsVisible.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        // 渲染建筑界限（白色线条）
        for (BlockPos controlBoxPos : buildingBoundsVisible.keySet()) {
            renderBuildingBounds(poseStack, cameraPos, controlBoxPos);
        }
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
