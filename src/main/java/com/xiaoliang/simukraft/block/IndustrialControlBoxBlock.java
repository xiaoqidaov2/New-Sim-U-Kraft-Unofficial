package com.xiaoliang.simukraft.block;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.ControlBoxDataManager;
import com.xiaoliang.simukraft.building.ConstructionBoxMapping;
import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import com.xiaoliang.simukraft.employment.domain.EmploymentStatus;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import com.xiaoliang.simukraft.employment.service.EmploymentCommands;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import com.xiaoliang.simukraft.employment.service.LegacyJobTypeMapper;
import com.xiaoliang.simukraft.utils.BuildingDataManager;
import com.xiaoliang.simukraft.utils.ClientRuntimeBridge;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.IndustrialHiredData;
import com.xiaoliang.simukraft.world.ConstructionBoxData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;


public class IndustrialControlBoxBlock extends Block {
    public IndustrialControlBoxBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(Objects.requireNonNull(MapColor.METAL))
                .strength(0.8F)
                .sound(Objects.requireNonNull(SoundType.METAL)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState oldState, boolean isMoving) {
        super.onPlace(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(oldState),
                isMoving
        );

        if (!level.isClientSide && !oldState.is(Objects.requireNonNull(state.getBlock()))) {
            MinecraftServer server = level.getServer();
            if (server != null) {
                // 从ConstructionBoxMapping获取建筑文件名和城市ID
                ConstructionBoxData.BoxInfo boxInfo = ConstructionBoxMapping.getBoxInfo(level, pos);
                String buildingFileName = boxInfo != null ? boxInfo.buildingFileName : "unknown";
                UUID cityId = boxInfo != null ? boxInfo.cityId : null;

                // 从BuildingDataManager获取建筑信息(包括job_type)
                BuildingDataManager.BuildingInfo buildingInfo = BuildingDataManager.getBuildingInfo("industry", buildingFileName + ".sk");
                String jobType = buildingInfo != null ? buildingInfo.getJobType() : null;

                // 创建工业建筑数据文件到 simukraft/industrial/ 目录
                ControlBoxDataManager.writeIndustrialControlBox(server, pos, buildingFileName, null, cityId);

                // 存储额外的工业建筑信息到工业数据文件
                writeIndustrialJobType(server, pos, buildingFileName, jobType, cityId);

                // 更新缓存，使新建建筑立即可被NPC识别
                com.xiaoliang.simukraft.utils.FileUtils.updateSkFileCache("industrial", pos, buildingFileName);

                // 移除已处理的控制盒
                ConstructionBoxMapping.removePendingBox(level, pos);

                Simukraft.LOGGER.info("[IndustrialControlBoxBlock] 工业控制盒已放置: {}, 建筑: {}, 职业: {}, 城市: {}",
                    pos, buildingFileName, jobType != null ? jobType : "未指定",
                    cityId != null ? cityId.toString().substring(0, 8) : "未指定");
            }
        }
    }

    /**
     * 写入工业建筑的job_type信息到数据文件
     */
    private void writeIndustrialJobType(MinecraftServer server, BlockPos pos, String buildingFileName, String jobType, UUID cityId) {
        try {
            Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
            Path industrialDir = worldPath.resolve("simukraft").resolve("industrial");
            Files.createDirectories(industrialDir);

            // 文件名：x_y_z.sk
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            // 使用UTF-8编码写入文件
            try (BufferedWriter writer = Files.newBufferedWriter(skFile, StandardCharsets.UTF_8)) {
                writer.write("position: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "\n");
                writer.write("type: industrial_control_box\n");
                writer.write("world: " + worldPath.getFileName().toString() + "\n");
                writer.write("building_file_name: " + buildingFileName + "\n");
                if (jobType != null && !jobType.isEmpty()) {
                    writer.write("job_type: " + jobType + "\n");
                }
                if (cityId != null) {
                    writer.write("cityid: " + cityId.toString() + "\n");
                }
            }

            Simukraft.LOGGER.info("[IndustrialControlBoxBlock] 写入工业建筑数据: {}, 建筑: {}, 职业: {}",
                skFile.toAbsolutePath(), buildingFileName, jobType != null ? jobType : "未指定");

        } catch (IOException e) {
            Simukraft.LOGGER.error("[IndustrialControlBoxBlock] 写入工业建筑数据失败", e);
        }
    }

    @Override
    public @Nonnull InteractionResult use(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull Player player, @Nonnull InteractionHand hand, @Nonnull BlockHitResult hit) {
        if (level.isClientSide) {
            try {
                // 从本地数据文件读取建筑文件名
                String buildingFileName = readBuildingFileNameFromLocal(pos);
                ClientRuntimeBridge.openScreen(
                        "com.xiaoliang.simukraft.client.gui.IndustrialControlBoxLDLibScreen",
                        new Class<?>[]{BlockPos.class, String.class},
                        pos,
                        buildingFileName
                );
            } catch (Exception e) {
                Simukraft.LOGGER.error("[IndustrialControlBoxBlock] 打开界面失败", e);
            }
        }
        return Objects.requireNonNull(InteractionResult.sidedSuccess(level.isClientSide));
    }

    /**
     * 从本地工业建筑数据文件读取建筑文件名
     */
    private String readBuildingFileNameFromLocal(BlockPos pos) {
        try {
            MinecraftServer singleplayerServer = ClientRuntimeBridge.getSingleplayerServer();
            if (singleplayerServer == null) {
                return "unknown";
            }

            java.nio.file.Path worldPath = singleplayerServer.getWorldPath(Objects.requireNonNull(net.minecraft.world.level.storage.LevelResource.ROOT));
            java.nio.file.Path industrialDir = worldPath.resolve("simukraft").resolve("industrial");
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            java.nio.file.Path skFile = industrialDir.resolve(fileName);

            if (java.nio.file.Files.exists(skFile)) {
                try (BufferedReader reader = java.nio.file.Files.newBufferedReader(skFile, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("building_file_name:")) {
                        return line.substring(19).trim();
                    }
                }
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[IndustrialControlBoxBlock] 读取建筑文件名失败", e);
        }
        return "unknown";
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState newState, boolean isMoving) {
        if (state.is(Objects.requireNonNull(newState.getBlock()))) {
            super.onRemove(state, level, pos, newState, isMoving);
            return;
        }

        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();

            var releaseResult = EmploymentServices.get(server).onWorkBlockRemoved(
                    new EmploymentCommands.WorkBlockRemovedCommand(serverLevel.dimension().location().toString(), pos)
            );
            boolean firedByService = releaseResult.success() && releaseResult.assignment() != null;
            if (releaseResult.success() && releaseResult.assignment() != null) {
                com.xiaoliang.simukraft.network.EmploymentCommandPacket.applyFireSideEffectsAndBroadcast(
                        server, releaseResult.assignment(), false
                );
            }

            // 从 IndustrialHiredData 加载雇佣信息
            Map<BlockPos, IndustrialHiredData.IndustrialHireInfo> hiredEmployees = IndustrialHiredData.loadHiredEmployees(server);

            if (hiredEmployees.containsKey(pos)) {
                IndustrialHiredData.IndustrialHireInfo hireInfo = hiredEmployees.get(pos);
                UUID npcUuid = hireInfo.getNpcUuid();

                if (!firedByService && npcUuid != null) {
                    EmploymentAssignment legacyAssignment = new EmploymentAssignment(
                            npcUuid,
                            serverLevel.dimension().location().toString(),
                            pos,
                            WorkBlockType.INDUSTRIAL_CONTROL_BOX,
                            LegacyJobTypeMapper.fromLegacy(hireInfo.getJobType(), hireInfo.getBuildingFileName()),
                            EmploymentStatus.ASSIGNED,
                            System.currentTimeMillis(),
                            System.currentTimeMillis()
                    );
                    com.xiaoliang.simukraft.network.EmploymentCommandPacket.applyFireSideEffectsAndBroadcast(
                            server, legacyAssignment, false
                    );
                    firedByService = true;
                }

                // 查找对应的NPC实体
                var npc = IndustrialHiredData.findNPCByUuid(server, npcUuid);
                if (npc != null) {
                    // 获取NPC雇佣信息以确定城市
                    UUID npcCityId = npc.getCityId();
                    if (npcCityId == null) {
                        CityData cityData = CityData.get(serverLevel);
                        for (CityData.CityInfo city : cityData.getAllCities()) {
                            if (city.getCitizenIds().contains(npcUuid)) {
                                npcCityId = city.getCityId();
                                break;
                            }
                        }
                    }

                    // 发送聊天提示（通过通知接口，按城市过滤）
                    String npcName = npc.getFullName();
                    if (npcCityId != null) {
                        com.xiaoliang.simukraft.utils.CityMessageUtils.sendToCityGroup(
                            server, npcCityId,
                            net.minecraft.network.chat.Component.translatable("message.simukraft.industrial_control_box.destroyed", npcName)
                        );
                    }
                }

                // 从雇佣记录中移除
                hiredEmployees.remove(pos);
                IndustrialHiredData.saveHiredEmployees(server, hiredEmployees);
            }

            // 删除对应的工业建筑数据文件
            deleteIndustrialDataFile(server, pos);

            // 从缓存移除，避免NPC继续识别已删除的建筑
            com.xiaoliang.simukraft.utils.FileUtils.removeFromSkFileCache("industrial", pos);

            // 使用ControlBoxDataManager删除控制盒数据
            ControlBoxDataManager.deleteControlBox(server, pos, "industrial_control_box");
        }
        super.onRemove(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(newState),
                isMoving
        );
    }

    /**
     * 删除工业建筑数据文件
     */
    private void deleteIndustrialDataFile(MinecraftServer server, BlockPos pos) {
        try {
            Path worldPath = server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT));
            Path industrialDir = worldPath.resolve("simukraft").resolve("industrial");
            String fileName = pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".sk";
            Path skFile = industrialDir.resolve(fileName);

            if (Files.exists(skFile)) {
                Files.delete(skFile);
                Simukraft.LOGGER.info("[IndustrialControlBoxBlock] 删除工业建筑数据文件: {}", skFile.toAbsolutePath());
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[IndustrialControlBoxBlock] 删除工业建筑数据文件失败", e);
        }
    }
}
