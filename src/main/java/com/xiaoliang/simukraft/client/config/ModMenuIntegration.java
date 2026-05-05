package com.xiaoliang.simukraft.client.config;

import com.xiaoliang.simukraft.client.gui.ldlib.ConfigSelectionMenuScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModContainer;

import java.util.Objects;

/**
 * simukraft: ModMenu集成 - 菜单版本
 * 使用LDLibMenuScreen基类，被其他模组识别为普通菜单而非容器
 */
@OnlyIn(Dist.CLIENT)
public class ModMenuIntegration {

    public static void registerConfigScreen(ModContainer modContainer) {
        Objects.requireNonNull(modContainer).registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new ConfigSelectionMenuScreen(parent)
                )
        );
    }
}
