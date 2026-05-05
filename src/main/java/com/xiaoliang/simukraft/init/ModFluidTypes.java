package com.xiaoliang.simukraft.init;

import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModFluidTypes {
    public static final ResourceLocation WATER_STILL_RL = ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_still");
    public static final ResourceLocation WATER_FLOWING_RL = ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_flow");
    public static final ResourceLocation MILK_OVERLAY_RL = ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_overlay");

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, Simukraft.MOD_ID);

    public static final RegistryObject<FluidType> MILK_FLUID_TYPE = FLUID_TYPES.register("milk",
            () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid.simukraft.milk")
                    .fallDistanceModifier(0F)
                    .canExtinguish(true)
                    .canConvertToSource(false)
                    .supportsBoating(true)
                    .sound(net.minecraftforge.common.SoundActions.BUCKET_FILL, net.minecraft.sounds.SoundEvents.BUCKET_FILL)
                    .sound(net.minecraftforge.common.SoundActions.BUCKET_EMPTY, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY)
                    .sound(net.minecraftforge.common.SoundActions.FLUID_VAPORIZE, net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH)) {
                @Override
                public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions> consumer) {
                    consumer.accept(new net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions() {
                        private static final ResourceLocation STILL_TEXTURE = ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, "block/milk_still");
                        private static final ResourceLocation FLOWING_TEXTURE = ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, "block/milk_flow");

                        @Override
                        public ResourceLocation getStillTexture() {
                            return STILL_TEXTURE;
                        }

                        @Override
                        public ResourceLocation getFlowingTexture() {
                            return FLOWING_TEXTURE;
                        }

                        @Override
                        public int getTintColor() {
                            return 0xFFFFFFFF; // Milk is white
                        }
                    });
                }
            });
}
