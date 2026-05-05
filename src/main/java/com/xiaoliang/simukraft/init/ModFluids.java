package com.xiaoliang.simukraft.init;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.fluid.MilkFluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModFluids {
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(ForgeRegistries.FLUIDS, Simukraft.MOD_ID);

    public static final RegistryObject<FlowingFluid> SOURCE_MILK = FLUIDS.register("milk_fluid",
            () -> new MilkFluid.Source(ModFluids.MILK_FLUID_PROPERTIES));
    public static final RegistryObject<FlowingFluid> FLOWING_MILK = FLUIDS.register("flowing_milk",
            () -> new MilkFluid.Flowing(ModFluids.MILK_FLUID_PROPERTIES));

    public static final MilkFluid.Properties MILK_FLUID_PROPERTIES = new MilkFluid.Properties(
            ModFluidTypes.MILK_FLUID_TYPE, SOURCE_MILK, FLOWING_MILK)
            .slopeFindDistance(4).levelDecreasePerBlock(1).block(ModBlocks.MILK_BLOCK);
}