package com.xiaoliang.simukraft.fluid;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import java.util.Objects;
import javax.annotation.Nonnull;

public abstract class MilkFluid extends ForgeFlowingFluid {
    protected MilkFluid(Properties properties) {
        super(properties);
    }

    @Nonnull
    private static Property<Integer> levelProperty() {
        return Objects.requireNonNull(LEVEL);
    }

    public static class Flowing extends MilkFluid {
        public Flowing(Properties properties) {
            super(properties);
        }

        @Override
        protected void createFluidStateDefinition(@Nonnull StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(Objects.requireNonNull(builder));
            builder.add(levelProperty());
        }

        @Override
        public int getAmount(@Nonnull FluidState state) {
            return state.getValue(levelProperty());
        }

        @Override
        public boolean isSource(@Nonnull FluidState state) {
            return false;
        }
    }

    public static class Source extends MilkFluid {
        public Source(Properties properties) {
            super(properties);
        }

        @Override
        public int getAmount(@Nonnull FluidState state) {
            return 8;
        }

        @Override
        public boolean isSource(@Nonnull FluidState state) {
            return true;
        }
    }
}
