package com.xiaoliang.simukraft.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

public class CustomEntityModel<T extends LivingEntity> extends PlayerModel<T> {
    @Nonnull
    private static <V> V nn(@Nullable V value) {
        return Objects.requireNonNull(value);
    }

    public CustomEntityModel(ModelPart root, boolean slim) {
        super(nn(root), slim);
    }

    public static LayerDefinition createPlayerLikeLayer() {
        // 创建Alex纤细手臂模型（slim=true）
        MeshDefinition mesh = nn(createMesh(nn(CubeDeformation.NONE), true));
        return LayerDefinition.create(mesh, 64, 64);
    }

    public static LayerDefinition createSteveLayer() {
        // 创建Steve粗手臂模型（slim=false）
        MeshDefinition mesh = nn(createMesh(nn(CubeDeformation.NONE), false));
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(@Nonnull T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(nn(entity), limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        // 添加自定义动画逻辑
        this.hat.copyFrom(nn(this.head));

        // 检查是否为建筑师NPC或牧羊人NPC且正在工作
        if (entity instanceof CustomEntity customEntity) {
            if (customEntity.getWorkStatus() == WorkStatus.WORKING && 
                ("builder".equals(customEntity.getJob()) || "shepherd".equals(customEntity.getJob()) || "butcher".equals(customEntity.getJob()))) {
                // 挥手动画现在完全由CustomEntity的getAttackAnim方法控制
                // 这里不再需要额外的动画逻辑
            }
        }
    }

    @Override
    public void renderToBuffer(@Nonnull PoseStack poseStack, @Nonnull VertexConsumer buffer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        // 渲染两层皮肤
        this.head.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.hat.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.body.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.jacket.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.leftArm.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.leftSleeve.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.rightArm.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.rightSleeve.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.leftLeg.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);


        this.leftPants.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.rightLeg.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
        this.rightPants.render(nn(poseStack), nn(buffer), packedLight, packedOverlay);
    }
}
