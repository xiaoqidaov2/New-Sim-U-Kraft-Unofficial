package com.xiaoliang.simukraft.init;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.FloatingBuildBoxEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Simukraft.MOD_ID);

    public static final RegistryObject<EntityType<CustomEntity>> CUSTOM_ENTITY =
            ENTITIES.register("custom_entity",
                    () -> EntityType.Builder.of(CustomEntity::new, MobCategory.CREATURE)
                            // 碰撞箱尺寸与玩家一致（玩家也是0.6f x 1.8f）
                            .sized(0.6f, 1.8f)
                            .build("custom_entity"));
    
    public static final RegistryObject<EntityType<FloatingBuildBoxEntity>> FLOATING_BUILD_BOX =
            ENTITIES.register("floating_build_box",
                    () -> EntityType.Builder.of(FloatingBuildBoxEntity::new, MobCategory.MISC)
                            .sized(0.5f, 0.5f) // 从1.0f增加到2.0f，进一步增大尺寸
                            .noSummon() // 不能自然生成
                            .build("floating_build_box"));
}