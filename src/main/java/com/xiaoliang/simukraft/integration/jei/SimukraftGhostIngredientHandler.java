package com.xiaoliang.simukraft.integration.jei;

import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.xiaoliang.simukraft.client.gui.ConfigurableListScreenLDLib;
import com.xiaoliang.simukraft.client.gui.MaterialCategoryGroupsScreenLDLib;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * simukraft: JEI 幽灵物品处理器
 * 处理从 JEI 拖拽物品到模组配置界面的逻辑
 */
@OnlyIn(Dist.CLIENT)
public class SimukraftGhostIngredientHandler<T extends ModularUIGuiContainer> implements IGhostIngredientHandler<T> {

    @Override
    public @Nonnull <I> List<Target<I>> getTargetsTyped(@Nonnull T gui, @Nonnull ITypedIngredient<I> ingredient, boolean doStart) {
        List<Target<I>> targets = new ArrayList<>();

        // simukraft: 检查当前界面类型
        if (gui instanceof ConfigurableListScreenLDLib listScreen) {
            // simukraft: 为黑名单界面添加一个全屏的拖拽目标
            targets.add(createBlacklistTarget(listScreen));
        } else if (gui instanceof MaterialCategoryGroupsScreenLDLib groupsScreen) {
            // simukraft: 为通类匹配界面添加多个拖拽目标（组头和组员区域）
            targets.addAll(createMaterialGroupTargets(groupsScreen));
        }

        return targets;
    }

    @Override
    public void onComplete() {
        // simukraft: 拖拽完成时的回调
    }

    @Override
    public boolean shouldHighlightTargets() {
        // simukraft: 返回 true 启用 JEI 的绿色高亮效果
        return true;
    }

    // simukraft: 黑名单界面列表区域的位置和大小（相对于 GUI 窗口内部）
    private static final int BLACKLIST_LIST_X = 10;
    private static final int BLACKLIST_LIST_Y = 101;
    private static final int BLACKLIST_LIST_WIDTH = 380;
    private static final int BLACKLIST_LIST_HEIGHT = 194;

    // simukraft: 通类匹配界面面板区域（相对于 GUI 窗口内部）
    // 中间面板: panelX = 15 + 180 + 15 = 210, panelY = 50 + 10 = 60
    // 列表在面板内位置: (5, 50), 所以绝对位置: 215, 110
    private static final int HEADER_LIST_X = 215;
    private static final int HEADER_LIST_Y = 110;
    private static final int HEADER_LIST_WIDTH = 170;
    private static final int HEADER_LIST_HEIGHT = 225;

    // 右侧面板: panelX = 15 + 180*2 + 30 = 405, panelY = 60
    // 列表在面板内位置: (5, 50), 所以绝对位置: 410, 110
    private static final int MEMBER_LIST_X = 410;
    private static final int MEMBER_LIST_Y = 110;
    private static final int MEMBER_LIST_WIDTH = 170;
    private static final int MEMBER_LIST_HEIGHT = 225;

    /**
     * 获取 GUI 窗口在屏幕上的偏移量
     * LDLib 的界面居中显示，需要计算偏移来转换坐标
     */
    private static int getGuiLeft(ModularUIGuiContainer gui) {
        return (gui.width - gui.modularUI.getWidth()) / 2;
    }

    private static int getGuiTop(ModularUIGuiContainer gui) {
        return (gui.height - gui.modularUI.getHeight()) / 2;
    }

    /**
     * 创建黑名单界面的拖拽目标
     */
    private <I> Target<I> createBlacklistTarget(ConfigurableListScreenLDLib gui) {
        return new Target<I>() {
            @Override
            public Rect2i getArea() {
                // simukraft: 计算相对于屏幕的绝对坐标
                int guiLeft = getGuiLeft(gui);
                int guiTop = getGuiTop(gui);
                return new Rect2i(
                    guiLeft + BLACKLIST_LIST_X,
                    guiTop + BLACKLIST_LIST_Y,
                    BLACKLIST_LIST_WIDTH,
                    BLACKLIST_LIST_HEIGHT
                );
            }

            @Override
            public void accept(I ingredient) {
                // simukraft: 处理物品被拖拽到目标
                if (ingredient instanceof ItemStack itemStack) {
                    String itemId = getItemIdFromStack(itemStack);
                    if (itemId != null) {
                        gui.addItemById(itemId);
                    }
                }
            }
        };
    }

    /**
     * 创建通类匹配界面的拖拽目标
     */
    private <I> List<Target<I>> createMaterialGroupTargets(MaterialCategoryGroupsScreenLDLib screen) {
        List<Target<I>> targets = new ArrayList<>();

        // simukraft: 计算 GUI 窗口偏移
        int guiLeft = getGuiLeft(screen);
        int guiTop = getGuiTop(screen);

        // simukraft: 组头区域目标
        targets.add(new Target<I>() {
            @Override
            public Rect2i getArea() {
                // simukraft: 中间面板的区域（组头列表），转换为屏幕坐标
                return new Rect2i(
                    guiLeft + HEADER_LIST_X,
                    guiTop + HEADER_LIST_Y,
                    HEADER_LIST_WIDTH,
                    HEADER_LIST_HEIGHT
                );
            }

            @Override
            public void accept(I ingredient) {
                if (ingredient instanceof ItemStack itemStack) {
                    screen.handleJEIItemDropToHeader(itemStack);
                }
            }
        });

        // simukraft: 组员区域目标
        targets.add(new Target<I>() {
            @Override
            public Rect2i getArea() {
                // simukraft: 右侧面板的区域（组员列表），转换为屏幕坐标
                return new Rect2i(
                    guiLeft + MEMBER_LIST_X,
                    guiTop + MEMBER_LIST_Y,
                    MEMBER_LIST_WIDTH,
                    MEMBER_LIST_HEIGHT
                );
            }

            @Override
            public void accept(I ingredient) {
                if (ingredient instanceof ItemStack itemStack) {
                    screen.handleJEIItemDropToMember(itemStack);
                }
            }
        });

        return targets;
    }

    /**
     * 从物品堆栈获取物品ID
     */
    @Nullable
    private static String getItemIdFromStack(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return null;
        }

        // simukraft: 获取物品的注册名
        var item = itemStack.getItem();
        // simukraft: 使用 ForgeRegistries 获取物品ID
        var location = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        if (location != null) {
            return location.toString();
        }

        return null;
    }
}
