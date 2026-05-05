package com.xiaoliang.simukraft.utils;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.farmland.CropDefinition;
import com.xiaoliang.simukraft.farmland.CropRegistry;
import com.xiaoliang.simukraft.farmland.FarmlandPlot;
import com.xiaoliang.simukraft.network.EmploymentStateChangedPacket;
import com.xiaoliang.simukraft.network.NPCWorkStatusPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.notification.MessageCategory;
import com.xiaoliang.simukraft.world.FarmlandHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.UUID;

/**
 * 农场统一管理服务类 (Farmland Unified Manager)
 * 整合了绑定箱子、雇佣验证、工作流启停及失效处理逻辑。
 * 遵循单一职责与高内聚原则，作为农场业务的唯一入口。
 */
@SuppressWarnings("null")
public final class FarmlandManager {
    public static final int SEARCH_RADIUS = 8;

    private FarmlandManager() {}

    /**
     * 自动探测并绑定最近的容器
     */
    public static BlockPos resolveOrBindNearestChest(ServerLevel level, BlockPos farmlandBoxPos) {
        BlockPos boundChestPos = FarmlandHiredData.getBoundChest(farmlandBoxPos);
        if (isUsableContainer(level, boundChestPos)) {
            return boundChestPos;
        }

        BlockPos nearestChestPos = findNearestContainer(level, farmlandBoxPos);
        if (nearestChestPos != null) {
            if (!nearestChestPos.equals(boundChestPos)) {
                FarmlandHiredData.setBoundChest(farmlandBoxPos, nearestChestPos);
                FarmlandHiredData.saveBoundChests(level.getServer());
            }
            return nearestChestPos;
        }

        if (boundChestPos != null) {
            FarmlandHiredData.clearBoundChest(farmlandBoxPos);
            FarmlandHiredData.saveBoundChests(level.getServer());
        }
        return null;
    }

    public static BlockPos getBoundChestIfValid(ServerLevel level, BlockPos farmlandBoxPos) {
        BlockPos boundChestPos = FarmlandHiredData.getBoundChest(farmlandBoxPos);
        return isUsableContainer(level, boundChestPos) ? boundChestPos : null;
    }

    public static BlockPos findNearestContainer(ServerLevel level, BlockPos centerPos) {
        BlockPos nearestPos = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    BlockPos checkPos = centerPos.offset(x, y, z);
                    if (!ContainerUtils.isContainer(level, checkPos)) continue;

                    double distance = checkPos.distSqr(centerPos);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestPos = checkPos.immutable();
                    }
                }
            }
        }
        return nearestPos;
    }

    private static boolean isUsableContainer(ServerLevel level, net.minecraft.core.BlockPos pos) {
        return pos != null && ContainerUtils.isContainer(level, pos);
    }

    /**
     * 当农田盒放置时调用：创建数据文件并提示绑定。
     */
    public static void onBoxPlaced(ServerLevel level, BlockPos boxPos) {
        MinecraftServer server = level.getServer();
        FileUtils.createFarmlandBoxFile(server, boxPos);
        
        // 自动探测并提示绑定
        BlockPos nearestChest = findNearestContainer(level, boxPos);
        Component message = nearestChest != null 
            ? Component.translatable("message.simukraft.farmland_box.placement_hint").withStyle(s -> s.withColor(0x00FF00))
            : Component.translatable("message.simukraft.farmland_box.no_chest_hint").withStyle(s -> s.withColor(0xFF5555));

        level.players().forEach(player -> {
            if (player.distanceToSqr(boxPos.getX() + 0.5, boxPos.getY() + 0.5, boxPos.getZ() + 0.5) <= 64.0) {
                player.sendSystemMessage(Objects.requireNonNull(message));
            }
        });
    }

    /**
     * 当农田盒移除时调用：触发失效流程并删除文件。
     */
    public static void onBoxRemoved(ServerLevel level, BlockPos boxPos) {
        invalidateWorkflow(level, boxPos, null, true);
        FileUtils.deleteFarmlandBoxFile(level.getServer(), boxPos);
    }

    /**
     * 启动耕种工作流。
     * 包含：雇佣验证、箱子绑定、种子校验、区域清理及种植。
     */
    public static boolean startFarming(ServerPlayer player, BlockPos boxPos, String crop, int areaSize) {
        ServerLevel level = player.serverLevel();
        MinecraftServer server = level.getServer();

        // 1. 验证雇佣状态
        UUID npcUuid = FarmlandHiredData.getHiredFarmer(boxPos);
        CustomEntity npc = npcUuid != null ? FarmlandHiredData.findNPCByUuid(server, npcUuid) : null;
        if (npc == null || !npc.isAlive() || !"farmer".equals(npc.getJob())) {
            player.displayClientMessage(
                    Objects.requireNonNull(
                            Component.translatable("message.simukraft.farming.no_farmer_hired").withStyle(s -> s.withColor(0xFF5555))
                    ),
                    false
            );
            return false;
        }

        // 2. 绑定或获取箱子
        BlockPos chestPos = resolveOrBindNearestChest(level, boxPos);
        if (chestPos == null) {
            player.displayClientMessage(
                    Objects.requireNonNull(
                            Component.translatable("message.simukraft.farming.no_chest").withStyle(s -> s.withColor(0xFF5555))
                    ),
                    false
            );
            return false;
        }

        // 3. 种子校验
        CropDefinition cropDefinition = CropRegistry.resolve(crop).orElse(null);
        if (cropDefinition == null) {
            player.displayClientMessage(
                    Objects.requireNonNull(
                            Component.translatable("message.simukraft.farming.failed").withStyle(s -> s.withColor(0xFF5555))
                    ),
                    false
            );
            return false;
        }
        Direction facing = Objects.requireNonNull(player.getDirection());
        FarmlandPlot plot = FarmlandHiredData.getSelectedPlot(boxPos);
        if (plot == null) {
            plot = FarmlandPlot.fromLegacy(boxPos, facing, areaSize);
        }
        BlockPos overlappingBox = FarmlandHiredData.findOverlappingPlotOwner(boxPos, plot);
        if (overlappingBox != null) {
            player.displayClientMessage(
                    Objects.requireNonNull(
                            Component.translatable("message.simukraft.farming.area_overlap", overlappingBox.getX(), overlappingBox.getY(), overlappingBox.getZ())
                                    .withStyle(s -> s.withColor(0xFF5555))
                    ),
                    false
            );
            return false;
        }
        ItemStack seeds = new ItemStack(cropDefinition.seedItem());
        int requiredSeeds = plot.countPlantingSlots(cropDefinition.layoutType());
        int totalSeeds = ContainerUtils.countItem(level, chestPos, seeds);
        if (totalSeeds < requiredSeeds) {
            player.displayClientMessage(
                    Objects.requireNonNull(
                            Component.translatable("message.simukraft.farming.not_enough_seeds", requiredSeeds, crop, totalSeeds)
                                    .withStyle(s -> s.withColor(0xFF5555))
                    ),
                    false
            );
            return false;
        }

        // 4. 执行物理种植逻辑
        if (!checkAndClearPlot(level, plot)) {
            player.displayClientMessage(
                    Objects.requireNonNull(
                            Component.translatable("message.simukraft.farming.failed").withStyle(s -> s.withColor(0xFF5555))
                    ),
                    false
            );
            return false;
        }

        boolean planted = executeInitialPlanting(level, plot, cropDefinition, chestPos, seeds);
        if (planted) {
            // 更新持久化配置
            FarmlandHiredData.setSelectedCrop(boxPos, CropRegistry.normalizeSelectionId(crop));
            FarmlandHiredData.setSelectedArea(boxPos, areaSize);
            FarmlandHiredData.setSelectedPlot(boxPos, plot);
            FarmlandHiredData.saveAllFarmlandData(server);
            
            // 恢复NPC状态
            npc.setWorkStatus(WorkStatus.WORKING);
            npc.setWorkSubState(WorkSubState.WORKING);
            
            player.displayClientMessage(
                    Objects.requireNonNull(
                            Component.translatable("message.simukraft.farming.started", crop, requiredSeeds)
                                    .withStyle(s -> s.withColor(0x55FF55))
                    ),
                    false
            );
        }
        return planted;
    }

    /**
     * 失效并重置整个农场流程。
     * 当箱子被破坏、方块被破坏或手动终止时调用。
     */
    public static void invalidateWorkflow(ServerLevel level, BlockPos boxPos, CustomEntity npc, boolean workplaceRemoved) {
        MinecraftServer server = level.getServer();
        
        // 1. 获取关联NPC
        if (npc == null) {
            UUID npcUuid = FarmlandHiredData.getHiredFarmer(boxPos);
            npc = npcUuid != null ? FarmlandHiredData.findNPCByUuid(server, npcUuid) : null;
        }

        // 2. 释放雇佣合同 (使用统一的服务端 EmploymentServices)
        var employmentService = com.xiaoliang.simukraft.employment.service.EmploymentServices.get(server);
        var releaseResult = workplaceRemoved
                ? employmentService.onWorkBlockRemoved(new com.xiaoliang.simukraft.employment.service.EmploymentCommands.WorkBlockRemovedCommand(level.dimension().location().toString(), boxPos))
                : employmentService.fireByWorkplace(new com.xiaoliang.simukraft.employment.service.EmploymentCommands.FireByWorkplaceCommand(level.dimension().location().toString(), boxPos));
        
        if (releaseResult.success() && releaseResult.assignment() != null) {
            NetworkManager.sendToAll(new EmploymentStateChangedPacket(releaseResult.assignment()), level);
        }

        // 3. 重置NPC状态
        if (npc != null) {
            npc.setWorkStatus(WorkStatus.IDLE);
            npc.resetToIdle();
            CustomEntity finalNpc = npc;
            server.getPlayerList().getPlayers().forEach(p -> 
                NetworkManager.sendToPlayer(new NPCWorkStatusPacket(finalNpc.getUUID(), WorkStatus.IDLE, boxPos), p)
            );
        }

        // 4. 清理持久化数据与运行时计时器
        FarmlandHiredData.clearHiredFarmer(boxPos);
        FarmlandHiredData.clearSelectedCrop(boxPos);
        FarmlandHiredData.clearSelectedArea(boxPos);
        FarmlandHiredData.clearSelectedPlot(boxPos);
        FarmlandHiredData.clearBoundChest(boxPos);
        com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService.INSTANCE.clearTimers(boxPos);
        FarmlandHiredData.saveAllFarmlandData(server);

        // 5. 发送通知
        UUID cityId = npc != null ? npc.getCityId() : getFarmlandBoxCityId(server, boxPos);
        if (cityId != null) {
            CityMessageUtils.sendToCityGroup(server, cityId, Component.translatable("message.simukraft.farmland.invalidated"), MessageCategory.FARMING);
        }
    }

    // --- 内部辅助方法 (从 Packet 迁移) ---

    private static boolean checkAndClearPlot(ServerLevel level, FarmlandPlot plot) {
        for (BlockPos p : plot.positions()) {
            if (!canBeFarmland(level, p)) return false;
            BlockPos above = Objects.requireNonNull(p.above());
            if (!level.isEmptyBlock(above) && !isReplaceable(level.getBlockState(above))) return false;
        }

        for (BlockPos p : plot.positions()) {
            level.setBlock(p, Objects.requireNonNull(Blocks.DIRT.defaultBlockState()), 3);
            for (int y = p.getY() + 1; y < level.getMaxBuildHeight(); y++) {
                BlockPos cp = Objects.requireNonNull(new BlockPos(p.getX(), y, p.getZ()));
                if (level.isEmptyBlock(cp)) break;
                level.destroyBlock(cp, true);
            }
        }
        return true;
    }

    private static boolean executeInitialPlanting(ServerLevel level, FarmlandPlot plot, CropDefinition cropDefinition, BlockPos chestPos, ItemStack seeds) {
        int planted = 0;

        for (BlockPos p : plot.positions()) {
            level.setBlock(
                    p,
                    Objects.requireNonNull(
                            Objects.requireNonNull(Blocks.FARMLAND.defaultBlockState())
                                    .setValue(Objects.requireNonNull(FarmBlock.MOISTURE), 7)
                    ),
                    3
            );

            if (plot.shouldPlantAt(p, cropDefinition.layoutType())) {
                BlockPos cp = Objects.requireNonNull(p.above());
                if (ContainerUtils.consumeItem(level, chestPos, seeds)) {
                    level.setBlock(cp, Objects.requireNonNull(cropDefinition.cropBlock().defaultBlockState()), 3);
                    planted++;
                }
            }
        }
        return planted > 0;
    }

    private static boolean canBeFarmland(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(Objects.requireNonNull(pos));
        return s.is(Objects.requireNonNull(Blocks.DIRT))
                || s.is(Objects.requireNonNull(Blocks.GRASS_BLOCK))
                || s.is(Objects.requireNonNull(Blocks.FARMLAND));
    }

    private static boolean isReplaceable(BlockState s) {
        return s.isAir() || s.canBeReplaced();
    }

    private static UUID getFarmlandBoxCityId(MinecraftServer server, BlockPos pos) {
        try {
            java.nio.file.Path worldDir = server.getWorldPath(
                    Objects.requireNonNull(net.minecraft.world.level.storage.LevelResource.ROOT)
            );
            java.nio.file.Path simukraftDir = worldDir.resolve(FileUtils.MODE_DIR);
            java.nio.file.Path fruitDir = simukraftDir.resolve(FileUtils.FRUIT_DIR);

            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            java.nio.file.Path skFile = fruitDir.resolve(fileName);

            if (!java.nio.file.Files.exists(skFile)) {
                return null;
            }

            try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(skFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("city_id: ")) {
                        String cityIdStr = line.substring("city_id: ".length()).trim();
                        try {
                            return UUID.fromString(cityIdStr);
                        } catch (IllegalArgumentException ignored) {
                            return null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (com.xiaoliang.simukraft.config.ServerConfig.isDebugLogEnabled()) {
                Simukraft.LOGGER.debug("[FarmlandManager] Failed to resolve cityId for farmland box {}", pos, e);
            }
        }
        return null;
    }
}
