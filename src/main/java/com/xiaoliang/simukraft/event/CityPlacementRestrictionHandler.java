package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.init.ModBlocks;
import com.xiaoliang.simukraft.world.CityChunkData;
import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Objects;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
@SuppressWarnings("null")
public final class CityPlacementRestrictionHandler {
    private CityPlacementRestrictionHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide) {
            return;
        }
        ItemStack itemStack = event.getItemStack();
        if (!(itemStack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        BlockPos targetPos = resolveTargetPos(event.getPos(), Objects.requireNonNull(event.getFace()), level, blockItem);
        if (!shouldBlockPlacement(level, targetPos, blockItem.getBlock(), event.getEntity())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getPlacedBlock().getBlock() == null) {
            return;
        }
        if (!shouldBlockPlacement((Level) event.getLevel(), event.getPos(), event.getPlacedBlock().getBlock(), player)) {
            return;
        }
        event.setCanceled(true);
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.removeBlock(event.getPos(), false);
            serverLevel.sendBlockUpdated(event.getPos(), event.getBlockSnapshot().getCurrentBlock(), event.getBlockSnapshot().getCurrentBlock(), 3);
        }
    }

    private static BlockPos resolveTargetPos(BlockPos clickedPos, net.minecraft.core.Direction face, Level level, BlockItem blockItem) {
        BlockState clickedState = level.getBlockState(clickedPos);
        if (clickedState.canBeReplaced()) {
            return clickedPos;
        }
        return clickedPos.relative(face);
    }

    private static boolean shouldBlockPlacement(Level level, BlockPos targetPos, Block block, Player player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (player.isCreative()) {
            return false;
        }
        if (isExemptBlock(block)) {
            return false;
        }
        if (!isRestrictedBlock(block)) {
            return false;
        }

        CityData cityData = CityData.get(serverLevel);
        UUID playerCityId = cityData.getPlayerCityIdByName(player.getName().getString());
        if (playerCityId == null) {
            sendDeniedMessage(player);
            return true;
        }

        CityChunkData chunkData = CityChunkData.get(serverLevel);
        UUID ownerCityId = chunkData.getChunkOwner(new ChunkPos(targetPos).toLong());
        if (playerCityId.equals(ownerCityId)) {
            return false;
        }

        sendDeniedMessage(player);
        return true;
    }

    private static boolean isRestrictedBlock(Block block) {
        return block == ModBlocks.BUILD_BOX.get()
                || block == ModBlocks.NSUK_FARMLAND_BOX.get()
                || block == ModBlocks.LOGISTICS_SERVER_BOX.get()
                || block == ModBlocks.RESIDENTIAL_CONTROL_BOX.get()
                || block == ModBlocks.COMMERCIAL_CONTROL_BOX.get()
                || block == ModBlocks.INDUSTRIAL_CONTROL_BOX.get()
                || block == ModBlocks.OTHER_CONTROL_BOX.get();
    }

    private static boolean isExemptBlock(Block block) {
        return block == ModBlocks.LOGISTICS_CLIENT_BOX.get()
                || block == ModBlocks.CITY_CORE.get();
    }

    private static void sendDeniedMessage(Player player) {
        player.displayClientMessage(Component.translatable("message.simukraft.city_placement.outside_city"), true);
    }
}
