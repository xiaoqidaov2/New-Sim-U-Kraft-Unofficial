package com.xiaoliang.simukraft.init;

import com.xiaoliang.simukraft.item.GuideBookItem;
import com.xiaoliang.simukraft.item.ManifestItem;
import com.xiaoliang.simukraft.item.PortableCityCoreItem;
import com.xiaoliang.simukraft.item.food.BuffFoodItem;
import com.xiaoliang.simukraft.item.food.ModFoods;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@SuppressWarnings("null")
public class ModItems {
    public static final DeferredRegister<Item> ITEMS = 
            DeferredRegister.create(ForgeRegistries.ITEMS, com.xiaoliang.simukraft.Simukraft.MOD_ID);

    // 便携城市核心
    public static final RegistryObject<Item> PORTABLE_CITY_CORE = ITEMS.register("portable_city_core",
            PortableCityCoreItem::new);

    // 指南
    public static final RegistryObject<Item> GUIDE_BOOK = ITEMS.register("guide_book",
            GuideBookItem::new);

    // 清单
    public static final RegistryObject<Item> MANIFEST = ITEMS.register("manifest",
            ManifestItem::new);

    // ========== 食物 ==========

    // 汉堡
    public static final RegistryObject<Item> HAMBURGER = ITEMS.register("hamburger",
            () -> new BuffFoodItem(new Item.Properties().food(ModFoods.HAMBURGER)));

    // 薯条
    public static final RegistryObject<Item> FRENCH_FRIES = ITEMS.register("french_fries",
            () -> new BuffFoodItem(new Item.Properties().food(ModFoods.FRENCH_FRIES)));

    // 奶酪块
    public static final RegistryObject<Item> CHEESE_CHUNK = ITEMS.register("cheese_chunk",
            () -> new BuffFoodItem(new Item.Properties().food(ModFoods.CHEESE_CHUNK)));

    // 奶酪汉堡
    public static final RegistryObject<Item> CHEESE_BURGER = ITEMS.register("cheese_burger",
            () -> new BuffFoodItem(new Item.Properties().food(ModFoods.CHEESE_BURGER)));
}
