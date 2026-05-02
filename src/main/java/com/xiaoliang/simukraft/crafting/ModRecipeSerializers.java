package com.xiaoliang.simukraft.crafting;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 配方序列化器注册
 */
public class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, Simukraft.MOD_ID);

    public static final RegistryObject<RecipeSerializer<ManifestClearRecipe>> MANIFEST_CLEAR =
            RECIPE_SERIALIZERS.register("manifest_clear",
                    () -> new SimpleCraftingRecipeSerializer<>(ManifestClearRecipe::new));
}
