package com.xiaoliang.simukraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NPC寻路调试渲染器
 */
@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class NPCPathDebugRenderer {
    private static boolean pathDebugVisible = false;

    private static final int COLOR_PATH_LINE = 0xFF00FFFF;
    private static final int COLOR_NODE = 0xAA00FF00;
    private static final int COLOR_STEP_UP_NODE = 0xAA40A0FF;
    private static final int COLOR_CURRENT_NODE = 0xAAFFFF00;
    private static final int COLOR_TARGET_NODE = 0xAAFF4040;
    private static final int COLOR_BLOCKED = 0xFFFF3030;
    private static final int COLOR_LOW_COST = 0xAA00FF00;
    private static final int COLOR_MID_COST = 0xAAF0C020;
    private static final int COLOR_HIGH_COST = 0xAAFF6020;
    private static final int COLOR_MAX_COST = 0xAAFF2020;

    public static boolean isPathDebugVisible() {
        return pathDebugVisible || ClientConfig.isAlwaysShowNpcPathDebug();
    }

    public static void togglePathDebug() {
        pathDebugVisible = !pathDebugVisible;
        if (!isPathDebugVisible()) {
            NPCPathDebugClientCache.clear();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int pathCount = NPCPathDebugClientCache.getPaths().size();
            mc.player.displayClientMessage(
                    Component.literal((isPathDebugVisible() ? "NPC寻路调试显示: 开" : "NPC寻路调试显示: 关")
                            + " | 热键: " + (pathDebugVisible ? "开" : "关")
                            + " | 常显配置: " + (ClientConfig.isAlwaysShowNpcPathDebug() ? "开" : "关")
                            + " | 当前路径缓存: " + pathCount),
                    true
            );
        }
    }

    public static void refreshVisibilityState() {
        if (!isPathDebugVisible()) {
            NPCPathDebugClientCache.clear();
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (!isPathDebugVisible() || mc.level == null || mc.player == null) {
            return;
        }

        Map<UUID, NPCPathDebugClientCache.DebugPathData> paths = NPCPathDebugClientCache.getPaths();
        if (paths.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        for (NPCPathDebugClientCache.DebugPathData pathData : paths.values()) {
            renderPath(poseStack, cameraPos, pathData);
        }
    }

    private static void renderPath(PoseStack poseStack, Vec3 cameraPos, NPCPathDebugClientCache.DebugPathData pathData) {
        List<Vec3> nodes = pathData.nodes();
        List<String> nodeTypes = pathData.nodeTypes();
        List<Double> nodeCosts = pathData.nodeCosts();
        if (nodes.isEmpty()) {
            return;
        }

        int lineColor = pathData.blocked() ? COLOR_BLOCKED : COLOR_PATH_LINE;
        renderPathLines(poseStack, cameraPos, nodes, lineColor);

        int currentIndex = pathData.currentIndex();
        int targetIndex = nodes.size() - 1;
        for (int i = 0; i < nodes.size(); i++) {
            int color = getNodeColor(pathData, nodeTypes, nodeCosts, i, currentIndex, targetIndex);
            renderNodeBox(poseStack, cameraPos, nodes.get(i), color);
        }
    }

    private static int getNodeColor(NPCPathDebugClientCache.DebugPathData pathData, List<String> nodeTypes, List<Double> nodeCosts,
                                    int index, int currentIndex, int targetIndex) {
        if (pathData.blocked()) {
            return COLOR_BLOCKED;
        }
        if (index == currentIndex) {
            return COLOR_CURRENT_NODE;
        }
        if (index == targetIndex) {
            return COLOR_TARGET_NODE;
        }
        String nodeType = index < nodeTypes.size() ? nodeTypes.get(index) : "WALKABLE";
        if ("STEP_UP".equals(nodeType)) {
            return COLOR_STEP_UP_NODE;
        }
        double nodeCost = index < nodeCosts.size() ? nodeCosts.get(index) : 0.0D;
        return getColorForCost(nodeCost);
    }

    private static int getColorForCost(double nodeCost) {
        if (nodeCost >= 100.0D) {
            return COLOR_MAX_COST;
        }
        if (nodeCost >= 40.0D) {
            return COLOR_HIGH_COST;
        }
        if (nodeCost >= 10.0D) {
            return COLOR_MID_COST;
        }
        if (nodeCost > 0.0D) {
            return COLOR_LOW_COST;
        }
        return COLOR_NODE;
    }

    private static void renderPathLines(PoseStack poseStack, Vec3 cameraPos, List<Vec3> nodes, int color) {
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        float alpha = ((color >> 24) & 0xFF) / 255.0f;

        for (int i = 0; i < nodes.size() - 1; i++) {
            Vec3 from = nodes.get(i).add(0.0D, 0.05D, 0.0D);
            Vec3 to = nodes.get(i + 1).add(0.0D, 0.05D, 0.0D);
            drawLine(buffer, matrix,
                    from.x - cameraPos.x, from.y - cameraPos.y, from.z - cameraPos.z,
                    to.x - cameraPos.x, to.y - cameraPos.y, to.z - cameraPos.z,
                    red, green, blue, alpha);
        }

        tesselator.end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderNodeBox(PoseStack poseStack, Vec3 cameraPos, Vec3 pos, int color) {
        AABB box = new AABB(
                pos.x - 0.3D, pos.y + 0.02D, pos.z - 0.3D,
                pos.x + 0.3D, pos.y + 0.62D, pos.z + 0.3D
        );
        renderBoxOutline(poseStack, cameraPos, box, color);
    }

    private static void renderBoxOutline(PoseStack poseStack, Vec3 cameraPos, AABB box, int color) {
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        float alpha = ((color >> 24) & 0xFF) / 255.0f;

        double minX = box.minX - cameraPos.x;
        double minY = box.minY - cameraPos.y;
        double minZ = box.minZ - cameraPos.z;
        double maxX = box.maxX - cameraPos.x;
        double maxY = box.maxY - cameraPos.y;
        double maxZ = box.maxZ - cameraPos.z;

        Matrix4f matrix = poseStack.last().pose();

        drawLine(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, red, green, blue, alpha);

        drawLine(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue, alpha);

        drawLine(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);

        tesselator.end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }


    private static void drawLine(BufferBuilder buffer, Matrix4f matrix,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 float r, float g, float b, float a) {
        buffer.vertex(matrix, (float) x1, (float) y1, (float) z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float) x2, (float) y2, (float) z2).color(r, g, b, a).endVertex();
    }
}
