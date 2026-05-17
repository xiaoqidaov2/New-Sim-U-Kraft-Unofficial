package com.xiaoliang.simukraft.init;

import com.xiaoliang.simukraft.block.*;
import com.xiaoliang.simukraft.block.BankControlBoxBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Objects;

@SuppressWarnings("null")
public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, com.xiaoliang.simukraft.Simukraft.MOD_ID);

    public static final RegistryObject<Block> BUILD_BOX = BLOCKS.register("build_box", BuildBoxBlock::new);
    public static final RegistryObject<Item> BUILD_BOX_ITEM = ModItems.ITEMS.register("build_box",
            () -> new BlockItem(nn(BUILD_BOX.get()), new Item.Properties()));

    public static final RegistryObject<Block> CITY_CORE = BLOCKS.register("city_core", CityCoreBlock::new);
    public static final RegistryObject<Item> CITY_CORE_ITEM = ModItems.ITEMS.register("city_core",
            () -> new BlockItem(nn(CITY_CORE.get()), new Item.Properties()));

    // 统一的住宅控制盒
    public static final RegistryObject<Block> RESIDENTIAL_CONTROL_BOX = BLOCKS.register("residential_control_box", ResidentialControlBoxBlock::new);
    public static final RegistryObject<Item> RESIDENTIAL_CONTROL_BOX_ITEM = ModItems.ITEMS.register("residential_control_box",
            () -> new BlockItem(nn(RESIDENTIAL_CONTROL_BOX.get()), new Item.Properties()));

    public static final RegistryObject<Block> COMMERCIAL_CONTROL_BOX = BLOCKS.register("commercial_control_box", CommercialControlBoxBlock::new);
    public static final RegistryObject<Item> COMMERCIAL_CONTROL_BOX_ITEM = ModItems.ITEMS.register("commercial_control_box",
            () -> new BlockItem(nn(COMMERCIAL_CONTROL_BOX.get()), new Item.Properties()));

    public static final RegistryObject<Block> INDUSTRIAL_CONTROL_BOX = BLOCKS.register("industrial_control_box", IndustrialControlBoxBlock::new);
    public static final RegistryObject<Item> INDUSTRIAL_CONTROL_BOX_ITEM = ModItems.ITEMS.register("industrial_control_box",
            () -> new BlockItem(nn(INDUSTRIAL_CONTROL_BOX.get()), new Item.Properties()));

    public static final RegistryObject<Block> OTHER_CONTROL_BOX = BLOCKS.register("other_control_box", OtherControlBoxBlock::new);
    public static final RegistryObject<Item> OTHER_CONTROL_BOX_ITEM = ModItems.ITEMS.register("other_control_box",
            () -> new BlockItem(nn(OTHER_CONTROL_BOX.get()), new Item.Properties()));

    public static final RegistryObject<Block> WHITE_LIGHT_BLOCK = BLOCKS.register("white_light_block", WhiteLightBlock::new);
    public static final RegistryObject<Item> WHITE_LIGHT_BLOCK_ITEM = ModItems.ITEMS.register("white_light_block",
            () -> new BlockItem(nn(WHITE_LIGHT_BLOCK.get()), new Item.Properties()));

    public static final RegistryObject<Block> YELLOW_LIGHT_BLOCK = BLOCKS.register("yellow_light_block", YellowLightBlock::new);
    public static final RegistryObject<Item> YELLOW_LIGHT_BLOCK_ITEM = ModItems.ITEMS.register("yellow_light_block",
            () -> new BlockItem(nn(YELLOW_LIGHT_BLOCK.get()), new Item.Properties()));

    public static final RegistryObject<Block> RED_LIGHT_BLOCK = BLOCKS.register("red_light_block", RedLightBlock::new);
    public static final RegistryObject<Item> RED_LIGHT_BLOCK_ITEM = ModItems.ITEMS.register("red_light_block",
            () -> new BlockItem(nn(RED_LIGHT_BLOCK.get()), new Item.Properties()));

    public static final RegistryObject<Block> BLUE_LIGHT_BLOCK = BLOCKS.register("blue_light_block", BlueLightBlock::new);
    public static final RegistryObject<Item> BLUE_LIGHT_BLOCK_ITEM = ModItems.ITEMS.register("blue_light_block",
            () -> new BlockItem(nn(BLUE_LIGHT_BLOCK.get()), new Item.Properties()));

    public static final RegistryObject<Block> GREEN_LIGHT_BLOCK = BLOCKS.register("green_light_block", GreenLightBlock::new);
    public static final RegistryObject<Item> GREEN_LIGHT_BLOCK_ITEM = ModItems.ITEMS.register("green_light_block",
            () -> new BlockItem(nn(GREEN_LIGHT_BLOCK.get()), new Item.Properties()));

    public static final RegistryObject<Block> ORANGE_LIGHT_BLOCK = BLOCKS.register("orange_light_block", OrangeLightBlock::new);
    public static final RegistryObject<Item> ORANGE_LIGHT_BLOCK_ITEM = ModItems.ITEMS.register("orange_light_block",
            () -> new BlockItem(nn(ORANGE_LIGHT_BLOCK.get()), new Item.Properties()));

    public static final RegistryObject<Block> PURPLE_LIGHT_BLOCK = BLOCKS.register("purple_light_block", PurpleLightBlock::new);
    public static final RegistryObject<Item> PURPLE_LIGHT_BLOCK_ITEM = ModItems.ITEMS.register("purple_light_block",
            () -> new BlockItem(nn(PURPLE_LIGHT_BLOCK.get()), new Item.Properties()));

    public static final RegistryObject<Block> RAINBOW_LIGHT_BLOCK = BLOCKS.register("rainbow_light_block", RainbowLightBlock::new);
    public static final RegistryObject<Item> RAINBOW_LIGHT_BLOCK_ITEM = ModItems.ITEMS.register("rainbow_light_block",
            () -> new BlockItem(nn(RAINBOW_LIGHT_BLOCK.get()), new Item.Properties()));

    public static final RegistryObject<Block> WHITE_CARPET = BLOCKS.register("white_carpet", WhiteCarpetBlock::new);
    public static final RegistryObject<Item> WHITE_CARPET_ITEM = ModItems.ITEMS.register("white_carpet",
            () -> new BlockItem(nn(WHITE_CARPET.get()), new Item.Properties()));

    public static final RegistryObject<Block> WOODEN_FENCE = BLOCKS.register("wooden_fence", WoodenFenceBlock::new);
    public static final RegistryObject<Item> WOODEN_FENCE_ITEM = ModItems.ITEMS.register("wooden_fence",
            () -> new BlockItem(nn(WOODEN_FENCE.get()), new Item.Properties()));

    // 物流盒服务端（仓库）
    public static final RegistryObject<Block> LOGISTICS_SERVER_BOX = BLOCKS.register("logistics_server_box", LogisticsServerBlock::new);
    public static final RegistryObject<Item> LOGISTICS_SERVER_BOX_ITEM = ModItems.ITEMS.register("logistics_server_box",
            () -> new BlockItem(nn(LOGISTICS_SERVER_BOX.get()), new Item.Properties()));

    // 物流盒客户端（端口）
    public static final RegistryObject<Block> LOGISTICS_CLIENT_BOX = BLOCKS.register("logistics_client_box", LogisticsClientBlock::new);
    public static final RegistryObject<Item> LOGISTICS_CLIENT_BOX_ITEM = ModItems.ITEMS.register("logistics_client_box",
            () -> new BlockItem(nn(LOGISTICS_CLIENT_BOX.get()), new Item.Properties()));

    // NSUK-农田盒（农业建筑，不是商业建筑）
    public static final RegistryObject<Block> NSUK_FARMLAND_BOX = BLOCKS.register("nsuk_farmland_box", NSUKFarmlandBoxBlock::new);
    public static final RegistryObject<Item> NSUK_FARMLAND_BOX_ITEM = ModItems.ITEMS.register("nsuk_farmland_box",
            () -> new BlockItem(nn(NSUK_FARMLAND_BOX.get()), new Item.Properties()));

    // 奶酪块
    public static final RegistryObject<Block> CHEESE_BLOCK = BLOCKS.register("cheese_block", 
            () -> new Block(net.minecraft.world.level.block.state.BlockBehaviour.Properties.copy(net.minecraft.world.level.block.Blocks.SLIME_BLOCK).sound(net.minecraft.world.level.block.SoundType.SLIME_BLOCK)));
    public static final RegistryObject<Item> CHEESE_BLOCK_ITEM = ModItems.ITEMS.register("cheese_block",
            () -> new BlockItem(nn(CHEESE_BLOCK.get()), new Item.Properties()));

    // 牛奶液体方块
    public static final RegistryObject<net.minecraft.world.level.block.LiquidBlock> MILK_BLOCK = BLOCKS.register("milk_fluid",
            () -> new com.xiaoliang.simukraft.block.MilkLiquidBlock(ModFluids.SOURCE_MILK, net.minecraft.world.level.block.state.BlockBehaviour.Properties.copy(net.minecraft.world.level.block.Blocks.WATER).noLootTable().randomTicks()));

    // NSUK-银行控制箱
    public static final RegistryObject<Block> BANK_CONTROL_BOX = BLOCKS.register("bank_control_box", BankControlBoxBlock::new);
    public static final RegistryObject<Item> BANK_CONTROL_BOX_ITEM = ModItems.ITEMS.register("bank_control_box",
            () -> new BlockItem(nn(BANK_CONTROL_BOX.get()), new Item.Properties()));

    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }
}
