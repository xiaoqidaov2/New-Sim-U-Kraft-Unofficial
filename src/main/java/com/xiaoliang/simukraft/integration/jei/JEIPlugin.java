package com.xiaoliang.simukraft.integration.jei;

import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
/**
 * simukraft: JEI 插件主类
 * 处理与 JEI 的集成，包括拖拽支持
 */
@JeiPlugin
@OnlyIn(Dist.CLIENT)
public class JEIPlugin implements IModPlugin {

    @Override
    public @Nonnull ResourceLocation getPluginUid() {
        return nn(ResourceLocation.fromNamespaceAndPath("simukraft", "jei_plugin"));
    }

    @Override
    public void registerGuiHandlers(@Nonnull IGuiHandlerRegistration registration) {
        // simukraft: 注册 GUI 处理器，用于处理 JEI 物品列表与界面的交互
        registration.addGlobalGuiHandler(new SimukraftGuiHandler());

        // simukraft: 注册幽灵物品处理器，支持从 JEI 拖拽物品到 LDLib 界面
        registration.addGhostIngredientHandler(ModularUIGuiContainer.class, new SimukraftGhostIngredientHandler<>());
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }
}
