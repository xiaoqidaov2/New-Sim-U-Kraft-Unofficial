package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.utils.BuildingDataManager;
import com.xiaoliang.simukraft.utils.MoneyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public class StartConstructionPacket {
    private final String buildingName;
    private final String category;
    private final BlockPos buildBoxPos;
    private final BlockPos previewOrigin;
    private final int rotation;

    public StartConstructionPacket(String buildingName, String category, BlockPos buildBoxPos, BlockPos previewOrigin, int rotation) {
        this.buildingName = buildingName;
        this.category = category;
        this.buildBoxPos = buildBoxPos;
        this.previewOrigin = previewOrigin;
        this.rotation = rotation;
    }

    public StartConstructionPacket(FriendlyByteBuf buf) {
        this.buildingName = Objects.requireNonNull(buf.readUtf());
        this.category = Objects.requireNonNull(buf.readUtf());
        this.buildBoxPos = Objects.requireNonNull(buf.readBlockPos());
        this.previewOrigin = Objects.requireNonNull(buf.readBlockPos());
        this.rotation = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(Objects.requireNonNull(buildingName));
        buf.writeUtf(Objects.requireNonNull(category));
        buf.writeBlockPos(Objects.requireNonNull(buildBoxPos));
        buf.writeBlockPos(Objects.requireNonNull(previewOrigin));
        buf.writeInt(rotation);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getSender() != null) {
                ServerPlayer player = ctx.get().getSender();
                Simukraft.LOGGER.info("[StartConstructionPacket] Processing construction request, player: {}", player.getName().getString());
                Simukraft.LOGGER.info("[StartConstructionPacket] Building: {}, category: {}, buildBoxPos: {}, previewOrigin: {}, rotation: {}",
                                    buildingName, category, buildBoxPos, previewOrigin, rotation);

                try {
                    var server = player.getServer();
                    if (server == null) {
                        Simukraft.LOGGER.error("[StartConstructionPacket] Server instance is null, cannot process request");
                        return;
                    }
                    
                    // 优先读取统一雇佣服务，避免建筑盒双槽位时被兼容层旧视图覆盖。
                    String dimensionId = player.serverLevel().dimension().location().toString();
                    var builderUuid = EmploymentServices.get(server)
                            .findByWorkplaceAndJob(dimensionId, buildBoxPos, JobType.BUILDER)
                            .map(com.xiaoliang.simukraft.employment.domain.EmploymentAssignment::npcUuid)
                            .orElseGet(() -> com.xiaoliang.simukraft.world.BuildBoxHiredData
                                    .loadHiredBuilders(server)
                                    .get(buildBoxPos));
                    
                    if (builderUuid == null) {
                        Simukraft.LOGGER.error("[StartConstructionPacket] No builder hired for build box at: {}", buildBoxPos);
                        player.displayClientMessage(
                                Objects.requireNonNull(Component.translatable("message.simukraft.construction.no_builder_hired")),
                                false
                        );
                        return;
                    }
                    
                    // 根据UUID查找NPC实体
                    CustomEntity builder = com.xiaoliang.simukraft.world.BuildBoxHiredData.findNPCByUuid(server, builderUuid);
                    if (builder == null) {
                        Simukraft.LOGGER.error("[StartConstructionPacket] Builder entity not found, UUID: {}", builderUuid);
                        player.displayClientMessage(
                                Objects.requireNonNull(Component.translatable("message.simukraft.construction.builder_not_found")),
                                false
                        );
                        return;
                    }
                    
                    Simukraft.LOGGER.info("[StartConstructionPacket] Found builder entity: {}", builder.getName().getString());

                    // 检查是否是建筑师职业
                    if (!"builder".equals(builder.getJob())) {
                        player.displayClientMessage(
                            Objects.requireNonNull(
                                    Component.translatable("message.simukraft.construction.not_builder")
                                            .withStyle(net.minecraft.ChatFormatting.RED)
                            ),
                            true
                        );
                        return;
                    }
                    
                    // 获取建筑信息
                    List<BuildingDataManager.BuildingInfo> allBuildings = BuildingDataManager.getBuildingsByCategory(category);
                    BuildingDataManager.BuildingInfo info = allBuildings.stream()
                            .filter(b -> {
                                String baseName = b.getFileName().replaceFirst("\\.[^.]+$", "");
                                return baseName.equals(buildingName);
                            })
                            .findFirst()
                            .orElse(null);

                    if (info == null) {
                        Simukraft.LOGGER.error("[StartConstructionPacket] Building info not found, name: {}, category: {}", buildingName, category);
                        player.displayClientMessage(
                                Objects.requireNonNull(Component.translatable("message.simukraft.construction.building_info_not_found")),
                                false
                        );
                        return;
                    }

                    // 解析建筑成本（修复：保留小数，不转换为整数）
                    String amountStr = info.getAmount().replace("元", "");
                    double cost = Double.parseDouble(amountStr);

                    // 检查资金
                    if (!MoneyManager.hasEnoughMoney(player, cost)) {
                        player.displayClientMessage(
                                Objects.requireNonNull(Component.translatable("message.simukraft.construction.insufficient_funds")),
                                false
                        );
                        return;
                    }

                    // 扣除资金
                    MoneyManager.deductMoney(player, cost);
                    Simukraft.LOGGER.info("[StartConstructionPacket] Funds deducted, remaining: {} yuan", MoneyManager.getMoney(player));

                    // 播放开始建造音效
                    if (player.level() instanceof ServerLevel serverLevel) {
                        serverLevel.playSound(
                            null,
                            Objects.requireNonNull(player.blockPosition()),
                            Objects.requireNonNull(ModSoundEvents.BUILDING_START.get()),
                            SoundSource.PLAYERS,
                            1.0F,
                            1.0F
                        );
                    }

                    // 创建建造任务 - 使用预览的原点和旋转
                    // 根据旋转角度确定朝向
                    Direction facing = Direction.NORTH;
                    switch (rotation) {
                        case 0:
                            facing = Direction.NORTH;
                            break;
                        case 90:
                            facing = Direction.EAST;
                            break;
                        case 180:
                            facing = Direction.SOUTH;
                            break;
                        case 270:
                            facing = Direction.WEST;
                            break;
                    }

                    // 创建ConstructionTask，使用预览的原点和朝向，并传递建筑盒位置
                    ConstructionTask task = new ConstructionTask(
                        builder, 
                        Objects.requireNonNull(buildingName), 
                        Objects.requireNonNull(category), 
                        Objects.requireNonNull(previewOrigin), 
                        Objects.requireNonNull(buildBoxPos), 
                        facing, 
                        Objects.requireNonNull(info.getName()), 
                        cost
                    );

                    // 设置NPC建造任务
                    builder.setConstructionTask(task);

                    // 保存建造任务到持久化存储（用于局域网开放模式下恢复）
                    com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.saveConstructionTask(
                        server, builder
                    );

                    prepareBuilderForConstruction(builder, buildBoxPos);

                    // 解析建筑蓝图，找出所有控制盒位置并注册
                    List<BlockPos> controlBoxPositions = parseControlBoxesFromBlueprint(
                        buildingName, category, previewOrigin, facing
                    );
                    if (!controlBoxPositions.isEmpty()) {
                        UUID builderCityId = builder.getCityId();
                        
                        // 如果建筑师没有城市ID，创建一个默认城市ID
                        if (builderCityId == null) {
                            Simukraft.LOGGER.warn("[StartConstructionPacket] Builder has no city ID, creating default");
                            // 使用建筑师的UUID作为城市ID的基础
                            builderCityId = UUID.nameUUIDFromBytes(("city_" + builder.getUUID()).getBytes());
                            builder.setCityId(builderCityId);
                        }
                        
                        if (builderCityId != null) {
                            // 传入中文建筑名称和英文文件名
                            String buildingFileName = Objects.requireNonNull(info.getFileName()).replace(".sk", "");
                            // 修复：传入level参数以支持持久化存储
                            com.xiaoliang.simukraft.building.ConstructionBoxMapping.registerPendingBoxes(
                                builder.level(), controlBoxPositions, builderCityId, info.getName(), buildingFileName
                            );
                            Simukraft.LOGGER.info("[StartConstructionPacket] Registered {} control box positions, cityId: {}, building: {}, file: {}",
                                controlBoxPositions.size(), builderCityId.toString().substring(0, 8), info.getName(), buildingFileName);
                        }
                    }

                    player.displayClientMessage(
                        Objects.requireNonNull(
                                Component.translatable(
                                        "message.simukraft.construction.started",
                                        builder.getDisplayName(),
                                        Objects.requireNonNull(info.getName())
                                )
                        ),
                        false
                    );

                    // 更新客户端
                    NetworkManager.sendToPlayer(new ConstructionProgressPacket(buildBoxPos, buildingName, 0), player);
                    Simukraft.LOGGER.info("[StartConstructionPacket] Construction request processed");

                } catch (Exception e) {
                    Simukraft.LOGGER.error("[StartConstructionPacket] Exception during processing: {}", e.getMessage());
                    e.printStackTrace();
                    player.displayClientMessage(
                            Objects.requireNonNull(
                                    Component.translatable("message.simukraft.error.construction_exception", e.getMessage())
                            ),
                            false
                    );
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 解析建筑蓝图，找出所有控制盒位置
     * @param buildingName 建筑名称
     * @param category 建筑类别
     * @param previewOrigin 预览原点位置
     * @param facing 朝向
     * @return 控制盒位置列表
     */
    private List<BlockPos> parseControlBoxesFromBlueprint(String buildingName, String category,
                                                           BlockPos previewOrigin, Direction facing) {
        List<BlockPos> controlBoxPositions = new ArrayList<>();

        try {
            // 加载建筑蓝图
            String filePath = "simukraftbuilding/" + category + "/" + buildingName + ".nbt";
            List<com.xiaoliang.simukraft.client.preview.SchematicNBTLoader.SchematicBlock> blocks =
                com.xiaoliang.simukraft.client.preview.SchematicNBTLoader.loadSchematicBlocks(filePath);

            for (com.xiaoliang.simukraft.client.preview.SchematicNBTLoader.SchematicBlock block : blocks) {
                BlockState state = block.blockState();

                // 检查是否是控制盒方块
                if (isControlBoxBlock(state)) {
                    BlockPos relativePos = block.pos();
                    // 根据朝向旋转坐标
                    BlockPos rotatedPos = rotatePosition(relativePos, facing);
                    // 计算最终位置
                    BlockPos finalPos = new BlockPos(
                        previewOrigin.getX() + rotatedPos.getX(),
                        previewOrigin.getY() + rotatedPos.getY(),
                        previewOrigin.getZ() + rotatedPos.getZ()
                    );
                    controlBoxPositions.add(finalPos);
                    Simukraft.LOGGER.info("[StartConstructionPacket] Found control box position: {}", finalPos);
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[StartConstructionPacket] Failed to parse blueprint: {}", e.getMessage());
        }

        return controlBoxPositions;
    }

    /**
     * 检查方块是否是控制盒
     */
    private boolean isControlBoxBlock(BlockState state) {
        String blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
        return blockId.contains("control_box") ||
               blockId.contains("_control_box") ||
               blockId.equals("simukraft:unit1_control_box") ||
               blockId.equals("simukraft:wooden_matchbox_control_box") ||
               blockId.equals("simukraft:two_story_residence_control_box") ||
               blockId.equals("simukraft:two_bedroom_control_box") ||
               blockId.equals("simukraft:stone_cottage_control_box") ||
               blockId.equals("simukraft:cobblestone_cottage_control_box") ||
               blockId.equals("simukraft:kms_apartment_control_box") ||
               blockId.equals("simukraft:kms_medium_house_control_box") ||
               blockId.equals("simukraft:noble_city_apartment_control_box") ||
               blockId.equals("simukraft:dexin_hotel_control_box") ||
               blockId.equals("simukraft:medieval_cottage_control_box") ||
               blockId.equals("simukraft:tuff_residence_control_box") ||
               blockId.equals("simukraft:big_house_b_control_box") ||
               blockId.equals("simukraft:red_brick_round_house_control_box") ||
               blockId.equals("simukraft:huge_stone_brick_villa_control_box") ||
               blockId.equals("simukraft:giant_tree_house_control_box") ||
               blockId.equals("simukraft:double_layer_wooden_house_control_box") ||
               blockId.equals("simukraft:red_eave_wooden_house_control_box") ||
               blockId.equals("simukraft:wooden_double_layer_villa_control_box") ||
               blockId.equals("simukraft:mushroom_house_control_box") ||
               blockId.equals("simukraft:stone_built_cottage_control_box") ||
               blockId.equals("simukraft:residential_control_box") ||
               blockId.equals("simukraft:commercial_control_box") ||
               blockId.equals("simukraft:industrial_control_box") ||
               blockId.equals("simukraft:other_control_box");
    }

    /**
     * 根据朝向旋转坐标
     */
    private BlockPos rotatePosition(BlockPos pos, Direction facing) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        switch (facing) {
            case NORTH:
                // 默认朝向，不旋转
                break;
            case EAST:
                // 顺时针90度
                int newX1 = -z;
                int newZ1 = x;
                x = newX1;
                z = newZ1;
                break;
            case SOUTH:
                // 180度
                x = -x;
                z = -z;
                break;
            case WEST:
                // 逆时针90度
                int newX2 = z;
                int newZ2 = -x;
                x = newX2;
                z = newZ2;
                break;
            default:
                break;
        }

        return new BlockPos(x, y, z);
    }

    private void prepareBuilderForConstruction(CustomEntity builder, BlockPos buildBoxPos) {
        if (builder == null || buildBoxPos == null) {
            return;
        }

        double distanceToWorkBlock = builder.position().distanceTo(
                new net.minecraft.world.phys.Vec3(
                        buildBoxPos.getX() + 0.5,
                        buildBoxPos.getY() + 1.0,
                        buildBoxPos.getZ() + 0.5
                )
        );

        if (distanceToWorkBlock <= 3.0D) {
            builder.setWorkStatus(WorkStatus.WORKING);
            builder.setWorking(true);
            return;
        }

        // 建筑师待命时允许自由游荡；真正开工前先回到工程方块，再由传送完成逻辑切回工作状态。
        builder.scheduleTeleport(buildBoxPos);
    }
}
