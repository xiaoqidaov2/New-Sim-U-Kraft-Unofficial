package com.xiaoliang.simukraft.init;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.Objects;

@SuppressWarnings("null")
public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Simukraft.MOD_ID);

    //方块物品添加到simukraft_tab里
    public static final RegistryObject<CreativeModeTab> SIMUKRAFT_TAB = CREATIVE_MODE_TABS.register("simukraft_tab",
            () -> CreativeModeTab.builder()
                    .title(nn(Component.translatable("itemGroup.simukraft")))
                    .icon(() -> new ItemStack(nn(ModBlocks.BUILD_BOX.get())))
                    .displayItems((params, output) -> {
                        output.accept(nn(ModBlocks.BUILD_BOX.get()));
                        output.accept(nn(ModBlocks.NSUK_FARMLAND_BOX.get()));
                        output.accept(nn(ModBlocks.CITY_CORE.get()));
                        // 统一的住宅控制盒
                        output.accept(nn(ModBlocks.RESIDENTIAL_CONTROL_BOX.get()));
                        output.accept(nn(ModBlocks.COMMERCIAL_CONTROL_BOX.get()));
                        output.accept(nn(ModBlocks.INDUSTRIAL_CONTROL_BOX.get()));
                        output.accept(nn(ModBlocks.OTHER_CONTROL_BOX.get()));
                        output.accept(nn(ModBlocks.LOGISTICS_SERVER_BOX.get()));
                        output.accept(nn(ModBlocks.LOGISTICS_CLIENT_BOX.get()));
                        output.accept(nn(ModBlocks.WHITE_LIGHT_BLOCK.get()));
                        output.accept(nn(ModBlocks.YELLOW_LIGHT_BLOCK.get()));
                        output.accept(nn(ModBlocks.RED_LIGHT_BLOCK.get()));
                        output.accept(nn(ModBlocks.BLUE_LIGHT_BLOCK.get()));
                        output.accept(nn(ModBlocks.GREEN_LIGHT_BLOCK.get()));
                        output.accept(nn(ModBlocks.ORANGE_LIGHT_BLOCK.get()));
                        output.accept(nn(ModBlocks.PURPLE_LIGHT_BLOCK.get()));
                        output.accept(nn(ModBlocks.RAINBOW_LIGHT_BLOCK.get()));
                        output.accept(nn(ModBlocks.WHITE_CARPET.get()));
                        // 便携城市核心
                        output.accept(nn(ModItems.PORTABLE_CITY_CORE.get()));
                        // 指南
                        output.accept(nn(ModItems.GUIDE_BOOK.get()));
                        // 清单
                        output.accept(nn(ModItems.MANIFEST.get()));
                        // 食物
                        output.accept(nn(ModItems.HAMBURGER.get()));
                        output.accept(nn(ModItems.FRENCH_FRIES.get()));
                        output.accept(nn(ModItems.CHEESE_CHUNK.get()));
                        output.accept(nn(ModItems.CHEESE_BURGER.get()));
                    })
                    .build());

    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }
}
