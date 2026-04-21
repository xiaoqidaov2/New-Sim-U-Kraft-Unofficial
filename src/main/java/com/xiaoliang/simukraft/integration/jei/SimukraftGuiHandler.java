package com.xiaoliang.simukraft.integration.jei;

import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import net.minecraft.client.renderer.Rect2i;

import java.util.Collection;
import java.util.Collections;

/**
 * simukraft: JEI GUI 处理器
 * 处理 JEI 物品列表与模组的 LDLib 界面的交互
 */
public class SimukraftGuiHandler implements IGlobalGuiHandler {

    @Override
    public Collection<Rect2i> getGuiExtraAreas() {
        // simukraft: 返回额外的 GUI 区域，用于 JEI 避开这些区域显示
        // LDLib 界面不需要额外区域，返回空列表
        return Collections.emptyList();
    }
}
