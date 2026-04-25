package com.xiaoliang.simukraft.client;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;

@SuppressWarnings("null")
public class ModModelLayers {
    // Alex纤细手臂模型
    public static final ModelLayerLocation CUSTOM_ENTITY =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, "custom_entity"), "main");

    // Steve粗手臂模型（皮肤文件名以_f结尾时使用）
    public static final ModelLayerLocation CUSTOM_ENTITY_STEVE =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, "custom_entity_steve"), "main");

    public static final ModelLayerLocation FLOATING_BUILD_BOX =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, "floating_build_box"), "main");
}
