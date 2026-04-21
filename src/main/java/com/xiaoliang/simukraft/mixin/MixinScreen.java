package com.xiaoliang.simukraft.mixin;

import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen Mixin - 阻止模组在LDLib屏幕上检查玩家实体
 * 解决Inventory Tweaks等模组在屏幕初始化时因玩家为null而崩溃的问题
 *
 * 原理：当LDLib屏幕初始化且玩家为null时，阻止repositionElements的执行
 * 这样就不会触发InitScreenEvent.Post事件，其他模组就不会收到事件
 */
@Mixin(Screen.class)
public class MixinScreen {

    /**
     * 在repositionElements方法开始时注入
     * 如果是LDLib屏幕且玩家为null，取消执行以防止触发事件
     */
    @Inject(method = "repositionElements", at = @At("HEAD"), cancellable = true)
    private void onRepositionElements(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;

        // 检查是否是LDLib的ModularUIGuiContainer或其子类
        if (self instanceof ModularUIGuiContainer) {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;

            // 如果玩家为null，取消repositionElements的执行
            // 这样就不会触发InitScreenEvent.Post事件
            if (player == null) {
                ci.cancel();
            }
        }
    }
}
