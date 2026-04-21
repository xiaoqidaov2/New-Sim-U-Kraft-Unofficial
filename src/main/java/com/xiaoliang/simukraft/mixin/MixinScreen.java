package com.xiaoliang.simukraft.mixin;

import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * simukraft: 阻止模组在LDLib容器屏幕上检查玩家实体
 * 解决Inventory Tweaks等模组在屏幕初始化时因玩家为null而崩溃的问题
 */
@Mixin(Screen.class)
public class MixinScreen {

    @Inject(method = "repositionElements", at = @At("HEAD"), cancellable = true)
    private void onRepositionElements(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;

        // 只对LDLib容器界面进行保护，防止主菜单时玩家为null导致崩溃
        if (self instanceof ModularUIGuiContainer) {
            if (Minecraft.getInstance().player == null) {
                ci.cancel();
            }
        }
    }
}
