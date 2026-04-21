package com.xiaoliang.simukraft.client.config;

import com.xiaoliang.simukraft.client.gui.ldlib.ConfigSelectionMenuScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * simukraft: ModMenu集成 - 菜单版本
 * 使用LDLibMenuScreen基类，被其他模组识别为普通菜单而非容器
 */
@OnlyIn(Dist.CLIENT)
public class ModMenuIntegration {

    @SuppressWarnings("removal")
    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new ConfigSelectionMenuScreen(parent)
                )
        );
    }
}
