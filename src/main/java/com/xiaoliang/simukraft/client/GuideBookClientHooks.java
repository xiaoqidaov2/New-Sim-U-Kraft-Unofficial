package com.xiaoliang.simukraft.client;

import com.xiaoliang.simukraft.client.gui.guidebook.GuideBookScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * 指南客户端打开桥接。
 */
public final class GuideBookClientHooks {

    private GuideBookClientHooks() {
    }

    public static void openGuideScreen(ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack previewStack = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        minecraft.setScreen(new GuideBookScreen(previewStack));
    }
}
