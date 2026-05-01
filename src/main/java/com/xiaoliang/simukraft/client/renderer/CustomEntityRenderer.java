package com.xiaoliang.simukraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xiaoliang.simukraft.client.ModModelLayers;
import com.xiaoliang.simukraft.client.model.CustomEntityModel;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.Gender;
import com.xiaoliang.simukraft.utils.SkinManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import javax.annotation.Nonnull;

@SuppressWarnings("null")
public class CustomEntityRenderer extends MobRenderer<CustomEntity, CustomEntityModel<CustomEntity>> {

    // : 缓存两种模型，根据皮肤文件名动态切换
    private final CustomEntityModel<CustomEntity> alexModel;
    private final CustomEntityModel<CustomEntity> steveModel;

    public CustomEntityRenderer(EntityRendererProvider.Context context) {
        // : 初始化时创建两种模型
        super(context, null, 0.5f);
        this.alexModel = new CustomEntityModel<>(context.bakeLayer(ModModelLayers.CUSTOM_ENTITY), true);
        this.steveModel = new CustomEntityModel<>(context.bakeLayer(ModModelLayers.CUSTOM_ENTITY_STEVE), false);
        this.model = this.alexModel; // 默认使用Alex模型
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public void render(@Nonnull CustomEntity entity, float entityYaw, float partialTicks, @Nonnull PoseStack poseStack,
                       @Nonnull MultiBufferSource buffer, int packedLight) {
        // : 根据皮肤文件名选择模型（_f结尾为Steve粗手臂）
        String skinPath = entity.getSkinPath();
        if (skinPath != null && !skinPath.isEmpty()) {
            // 检查文件名是否以_f结尾（粗手臂）
            String fileName = skinPath;
            int lastSlash = fileName.lastIndexOf('/');
            if (lastSlash >= 0) {
                fileName = fileName.substring(lastSlash + 1);
            }
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0) {
                fileName = fileName.substring(0, lastDot);
            }
            if (fileName.endsWith("_f")) {
                this.model = this.steveModel;
            } else {
                this.model = this.alexModel;
            }
        } else {
            this.model = this.alexModel;
        }

        // 如果NPC处于不可见状态（传送中），跳过所有层的渲染（包括手持物品）
        if (entity.isInvisible()) {
            renderInvisibleEntity(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
            return;
        }
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(@Nonnull CustomEntity entity) {
        String skinPath = entity.getSkinPath();
        Gender gender = entity.getGender();

        // 使用SkinManager获取有效的纹理资源位置
        if (skinPath != null && !skinPath.isEmpty() && SkinManager.isValidSkinPath(skinPath)) {
            return SkinManager.getTextureResourceLocation(skinPath);
        }

        // 如果皮肤路径无效，使用默认皮肤
        if (gender == Gender.FEMALE) {
            return SkinManager.getTextureResourceLocation(SkinManager.getDefaultSkinPath(Gender.FEMALE));
        } else {
            return SkinManager.getTextureResourceLocation(SkinManager.getDefaultSkinPath(Gender.MALE));
        }
    }

    @Override
    protected void renderNameTag(@Nonnull CustomEntity entity, @Nonnull Component component, @Nonnull PoseStack poseStack,
                                 @Nonnull MultiBufferSource bufferSource, int packedLight) {

        if (shouldShowName(entity)) {
            // 基于实体高度抬升标签，增加高度避免与头部重叠
            double nameOffset = entity.getBbHeight() + 0.5D;

            // 第三行（最下面）：饱食度状态标签（始终显示，亮黄色）
            Component hungerLine = buildHungerAlertLine(entity);
            poseStack.pushPose();
            poseStack.translate(0.0, nameOffset, 0.0);
            poseStack.scale(0.75F, 0.75F, 0.75F);
            renderTransparentNameTag(entity, hungerLine, poseStack, bufferSource, packedLight, 0xFFFF00);
            poseStack.popPose();

            // 第二行：工作状态标签（始终显示，亮黄色）
            Component workStatusLine = buildWorkStatusLine(entity);
            poseStack.pushPose();
            poseStack.translate(0.0, nameOffset + 0.22D, 0.0);
            poseStack.scale(0.75F, 0.75F, 0.75F);
            renderTransparentNameTag(entity, workStatusLine, poseStack, bufferSource, packedLight, 0xFFFF00);
            poseStack.popPose();

            // 第一行（最上面）：名字（白色）
            poseStack.pushPose();
            poseStack.translate(0.0, nameOffset + 0.44D, 0.0);
            renderTransparentNameTag(entity, component, poseStack, bufferSource, packedLight, 0xFFFFFF);
            poseStack.popPose();
        }
    }

    private static Component buildWorkStatusLine(CustomEntity entity) {
        return entity.getStatusDisplayComponent();
    }

    private static Component buildHungerAlertLine(CustomEntity entity) {
        // 使用与 CustomEntity.getHungerLevelKey() 相同的阈值逻辑
        String hungerKey = entity.getHungerLevelKey();
        return Component.translatable(hungerKey);
    }

    private void renderTransparentNameTag(CustomEntity entity, Component component, PoseStack poseStack,
                                      MultiBufferSource bufferSource, int packedLight, int color) {
        double d0 = this.entityRenderDispatcher.distanceToSqr(entity);
        if (d0 > 4096.0) {
            return;
        }

        boolean flag = !entity.isDiscrete();
        // 外层renderNameTag已经计算并应用了标签高度，这里不再重复叠加偏移
        float f = 0.0F;
        int i = "deadmau5".equals(component.getString()) ? -10 : 0;

        poseStack.pushPose();
        poseStack.translate(0.0, f, 0.0);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        Matrix4f matrix4f = poseStack.last().pose();

        // 调整背景不透明度为30%（0.3f），让背景更透明一些
        float backgroundOpacity = 0.3f; // 30% 不透明度（更透明）
        int backgroundColor = (int)(backgroundOpacity * 255.0F) << 24;

        float f2 = (float)(-Minecraft.getInstance().font.width(component) / 2);

        // 使用SEE_THROUGH模式绘制带背景的文本
        // 这会自动添加黑色透明背景，不受游戏设置影响
        Minecraft.getInstance().font.drawInBatch(component, f2, (float)i, color, false, matrix4f, bufferSource,
                Font.DisplayMode.SEE_THROUGH, backgroundColor, packedLight);

        if (flag) {
            // 正常模式绘制文本（无背景）
            Minecraft.getInstance().font.drawInBatch(component, f2, (float)i, color, false, matrix4f, bufferSource,
                    Font.DisplayMode.NORMAL, 0, packedLight);
        }

        poseStack.popPose();
    }

    /**
     * 渲染不可见的实体，跳过所有层的渲染（用于传送时隐藏手持物品和头顶标签）
     */
    private void renderInvisibleEntity(@Nonnull CustomEntity entity, float entityYaw, float partialTicks,
                                        @Nonnull PoseStack poseStack, @Nonnull MultiBufferSource buffer, int packedLight) {
        // NPC处于不可见状态，不需要渲染任何内容
        // 这个方法被调用时，entity.isInvisible()返回true
        // 因此不需要渲染实体本身、手持物品或头顶标签
        // 所有内容都会在NPC重新可见时恢复显示
    }

    @Override
    protected boolean shouldShowName(@Nonnull CustomEntity entity) {
        // 如果NPC处于不可见状态（传送中），不显示头顶标签
        if (entity.isInvisible()) {
            return false;
        }

        Camera camera = this.entityRenderDispatcher.camera;
        if (camera == null) return false;

        double distance = camera.getPosition().distanceTo(entity.position());
        return distance < 45.0 || entity.hasCustomName() && entity == camera.getEntity();
    }
}
