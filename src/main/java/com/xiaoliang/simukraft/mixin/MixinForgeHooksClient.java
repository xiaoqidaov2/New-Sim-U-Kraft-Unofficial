package com.xiaoliang.simukraft.mixin;

import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * simukraft: 拦截鼠标/键盘事件，防止在主菜单打开LDLib容器界面时传播到Mouse Tweaks等模组
 * 这些模组会尝试访问Minecraft.player.inventory，但主菜单时player为null
 */
@Mixin(ForgeHooksClient.class)
public class MixinForgeHooksClient {

    private static boolean shouldCancelEvent(Screen screen) {
        // 只在玩家为null且当前是LDLib容器界面时取消事件
        return Minecraft.getInstance().player == null
                && screen instanceof ModularUIGuiContainer;
    }

    @Inject(method = "onScreenMouseClickedPre", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onScreenMouseClickedPre(Screen screen, double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (shouldCancelEvent(screen)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onScreenMouseReleasedPre", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onScreenMouseReleasedPre(Screen screen, double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (shouldCancelEvent(screen)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onScreenMouseScrollPre", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onScreenMouseScrollPre(MouseHandler mouseHandler, Screen screen, double scrollDelta, CallbackInfoReturnable<Boolean> cir) {
        if (shouldCancelEvent(screen)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onScreenMouseDragPre", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onScreenMouseDragPre(Screen screen, double mouseX, double mouseY, int button, double dragX, double dragY, CallbackInfoReturnable<Boolean> cir) {
        if (shouldCancelEvent(screen)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onScreenKeyPressedPre", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onScreenKeyPressedPre(Screen screen, int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (shouldCancelEvent(screen)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onScreenKeyReleasedPre", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onScreenKeyReleasedPre(Screen screen, int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (shouldCancelEvent(screen)) {
            cir.setReturnValue(false);
        }
    }
}
