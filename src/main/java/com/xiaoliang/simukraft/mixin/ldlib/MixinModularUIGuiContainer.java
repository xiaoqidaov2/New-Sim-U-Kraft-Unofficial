package com.xiaoliang.simukraft.mixin.ldlib;

import com.lowdragmc.lowdraglib.gui.modular.ModularUIContainer;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * simukraft - 允许LDLib在主菜单等场景下创建UI
 * 当玩家实体为null时，使用空Inventory避免NPE
 */
@Mixin(value = ModularUIGuiContainer.class, remap = false)
public abstract class MixinModularUIGuiContainer extends AbstractContainerScreen<ModularUIContainer> {

    public MixinModularUIGuiContainer(ModularUIContainer menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getInventory()Lnet/minecraft/world/entity/player/Inventory;"
            ),
            remap = true
    )
    @SuppressWarnings("null")
    private static Inventory simukraft$redirectGetInventory(Player player) {
        return player != null ? player.getInventory() : new Inventory(null);
    }
}
