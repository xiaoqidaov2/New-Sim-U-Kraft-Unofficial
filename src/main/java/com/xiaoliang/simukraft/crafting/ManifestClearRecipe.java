package com.xiaoliang.simukraft.crafting;

import com.xiaoliang.simukraft.init.ModItems;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;

/**
 * 清单清空配方
 * 将包含数据的清单放入合成栏，合成后获得空白清单
 */
@SuppressWarnings("null")
public class ManifestClearRecipe extends CustomRecipe {

    public ManifestClearRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(@Nonnull CraftingContainer container, @Nonnull Level level) {
        int manifestCount = 0;
        boolean hasData = false;

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                if (stack.is(ModItems.MANIFEST.get())) {
                    manifestCount++;
                    // 检查是否有数据
                    CompoundTag tag = stack.getTag();
                    if (tag != null && !tag.isEmpty()) {
                        hasData = true;
                    }
                } else {
                    return false; // 包含非清单物品
                }
            }
        }

        return manifestCount == 1 && hasData;
    }

    @Nonnull
    @Override
    public ItemStack assemble(@Nonnull CraftingContainer container, @Nonnull RegistryAccess registryAccess) {
        // 返回空白清单
        return new ItemStack(ModItems.MANIFEST.get());
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 1;
    }

    @Nonnull
    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.MANIFEST_CLEAR.get();
    }
}
