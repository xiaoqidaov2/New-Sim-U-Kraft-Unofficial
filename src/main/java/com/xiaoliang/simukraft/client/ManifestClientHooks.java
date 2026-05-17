package com.xiaoliang.simukraft.client;

import com.xiaoliang.simukraft.client.gui.ManifestScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * 清单客户端打开桥接，避免公共物品类直接引用客户端 Screen。
 */
public final class ManifestClientHooks {

    private ManifestClientHooks() {
    }

    public static void openManifestScreen(ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        ItemStack previewStack = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        minecraft.setScreen(new ManifestScreen(previewStack));
    }
}
