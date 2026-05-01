package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import com.xiaoliang.simukraft.employment.service.EmploymentCommands;
import com.xiaoliang.simukraft.employment.service.EmploymentErrorCode;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import com.xiaoliang.simukraft.employment.service.LegacyJobTypeMapper;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.init.ModBlocks;
import com.xiaoliang.simukraft.world.CityPermissionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings({"null", "unused"})
public class NPCWorkStatusPacket {
    private final UUID entityUUID;
    private final WorkStatus newStatus;
    private final BlockPos buildBoxPos;
    private final String job;
    private final String npcName;

    public NPCWorkStatusPacket(UUID entityUUID, WorkStatus newStatus, BlockPos buildBoxPos) {
        this(entityUUID, newStatus, buildBoxPos, "", "");
    }
    
    public NPCWorkStatusPacket(UUID entityUUID, WorkStatus newStatus, BlockPos buildBoxPos, String npcName) {
        this(entityUUID, newStatus, buildBoxPos, npcName, "");
    }
    
    public NPCWorkStatusPacket(UUID entityUUID, WorkStatus newStatus, BlockPos buildBoxPos, String npcName, String job) {
        this.entityUUID = entityUUID;
        this.newStatus = newStatus;
        this.buildBoxPos = buildBoxPos;
        this.npcName = npcName;
        // 如果提供了职业类型，使用提供的；否则根据工作状态和建筑类型设置
        if (job != null && !job.isEmpty()) {
            this.job = job;
        } else if (newStatus == WorkStatus.WORKING) {
            // 如果是羊毛农场控制箱，设置为牧羊人，否则为建筑师
            if (buildBoxPos != null) {
                // 这里暂时无法判断建筑类型，将在handle方法中动态设置
                this.job = "builder"; // 默认值，将在handle方法中修改
            } else {
                this.job = "builder";
            }
        } else {
            this.job = "unemployed";
        }
    }

    public NPCWorkStatusPacket(FriendlyByteBuf buf) {
        this.entityUUID = buf.readUUID();
        this.newStatus = WorkStatus.values()[buf.readInt()];
        this.buildBoxPos = buf.readBlockPos();
        this.job = buf.readUtf();
        this.npcName = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(entityUUID);
        buf.writeInt(newStatus.ordinal());
        buf.writeBlockPos(buildBoxPos);
        buf.writeUtf(job);
        buf.writeUtf(npcName);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                // 客户端接收到服务器广播的数据包，执行客户端逻辑
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClientSide(this));
                return;
            }
            // Deprecated server write path: all employment writes now go through EmploymentCommandPacket.
            // Keep this packet as a client sync/broadcast compatibility packet only.
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 根据UUID查找NPC实体
     */
    private CustomEntity findNPCByUuid(MinecraftServer server, UUID npcUuid) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CustomEntity npc && entity.getUUID().equals(npcUuid)) {
                    return npc;
                }
            }
        }
        return null;
    }

    /**
     * 解析实际职业类型
     */
    private String resolveActualJob(CustomEntity targetNPC) {
        String actualJob = job;
        if (newStatus == WorkStatus.WORKING && buildBoxPos != null && targetNPC != null) {
            actualJob = resolveJobByBlockType(targetNPC, buildBoxPos);
        } else if (newStatus == WorkStatus.IDLE) {
            actualJob = "unemployed";
        }
        return actualJob != null ? actualJob : "unemployed";
    }

    /**
     * 根据方块类型解析职业
     */
    private String resolveJobByBlockType(CustomEntity targetNPC, BlockPos pos) {
        var block = targetNPC.level().getBlockState(pos).getBlock();
        if (block == com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
            String buildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(
                (ServerLevel) targetNPC.level(), pos);
            var config = com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfig(
                buildingFileName != null ? buildingFileName : "industrial");
            return config != null ? config.getJobType() : job;
        } else if (block == com.xiaoliang.simukraft.init.ModBlocks.COMMERCIAL_CONTROL_BOX.get()) {
            String buildingFileName = com.xiaoliang.simukraft.utils.FileUtils.readCommercialBuildingFileNameCached(
                targetNPC.getServer(), pos);
            var config = CommercialBuildingManager.getConfig(buildingFileName);
            return config != null ? config.getJobType() : "merchant";
        } else if (block == com.xiaoliang.simukraft.init.ModBlocks.BUILD_BOX.get()) {
            return "builder";
        } else if (block == com.xiaoliang.simukraft.init.ModBlocks.NSUK_FARMLAND_BOX.get()) {
            return "farmer";
        }
        return job;
    }

    /**
     * 应用NPC副作用（状态、物品、传送等）
     */
    private void applyNpcSideEffects(MinecraftServer server, CustomEntity npc, WorkStatus status, 
                                     BlockPos workplacePos, String actualJob) {
        // 更新工作状态和职业
        npc.setWorkStatus(status);
        npc.setJob(actualJob);

        // 处理传送和重置
        if (status == WorkStatus.IDLE) {
            npc.resetToIdle();
        } else if (workplacePos != null) {
            npc.scheduleHireArrivalTeleport(workplacePos);
        }

        // 保存NPC数据
        com.xiaoliang.simukraft.utils.NPCDataManager.saveJobData(npc);

        // 根据职业设置手持物品
        updateHeldItemByJob(npc, actualJob);

        // 发送雇佣提示消息
        if (status != WorkStatus.IDLE) {
            sendHireMessage(server, npc, actualJob);
        }

        // 处理工业建筑特殊逻辑
        if (status == WorkStatus.WORKING && workplacePos != null) {
            handleIndustrialSpecialCase(npc, workplacePos);
        }
    }

    /**
     * 根据职业更新手持物品
     * 优先从商业建筑配置读取heldItem
     */
    private void updateHeldItemByJob(CustomEntity npc, String actualJob) {
        // 首先检查商业建筑配置
        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(actualJob)) {
            java.util.List<com.xiaoliang.simukraft.building.CommercialBuildingConfig> configs =
                com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfigsByJobType(actualJob);
            if (!configs.isEmpty()) {
                String heldItemId = configs.get(0).getHeldItem();
                if (heldItemId != null && !heldItemId.isEmpty()) {
                    try {
                        net.minecraft.resources.ResourceLocation itemId = 
                            net.minecraft.resources.ResourceLocation.tryParse(heldItemId);
                        if (itemId != null) {
                            var item = ForgeRegistries.ITEMS.getValue(itemId);
                            if (item != null) {
                                npc.setItemInHand(npc.getUsedItemHand(), new ItemStack(item));
                                return;
                            }
                        }
                    } catch (Exception e) {
                        // 解析失败，使用默认物品
                    }
                }
            }
        }
        
        ItemStack heldItem = switch (actualJob) {
            case "builder" -> new ItemStack(Items.COBBLESTONE);
            case "planner" -> new ItemStack(Items.IRON_SHOVEL);
            case "shepherd" -> new ItemStack(Items.SHEARS);
            case "butcher" -> new ItemStack(Items.GOLDEN_AXE);
            case "farmer" -> new ItemStack(Items.STONE_HOE);
            default -> ItemStack.EMPTY;
        };
        npc.setItemInHand(npc.getUsedItemHand(), heldItem);
    }

    /**
     * 发送雇佣提示消息
     */
    private void sendHireMessage(MinecraftServer server, CustomEntity npc, String actualJob) {
        String messageKey;
        
        // 首先检查是否是商业建筑职业（统一使用CommercialBuildingManager）
        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(actualJob)) {
            messageKey = "message.simukraft.commercial_employee.hired";
        } else {
            messageKey = switch (actualJob) {
                case "builder" -> "message.simukraft.builder.hired";
                case "planner" -> "message.simukraft.planner.hired";
                case "farmer" -> "message.simukraft.farmer.hired";
                case "shepherd", "butcher" -> "message.simukraft.industrial_employee.hired";
                default -> null;
            };
        }

        if (messageKey != null) {
            Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
            Component hireMessage = Component.translatable(messageKey, npcName)
                    .withStyle(style -> style.withColor(0x55FF55));
            com.xiaoliang.simukraft.utils.CityMessageUtils.sendToCityGroup(server, npc.getCityId(), hireMessage);
        }
    }

    /**
     * 处理工业建筑特殊逻辑
     */
    private void handleIndustrialSpecialCase(CustomEntity npc, BlockPos workplacePos) {
        var block = npc.level().getBlockState(workplacePos).getBlock();
        if (block == com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
            String buildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(
                (ServerLevel) npc.level(), workplacePos);
            com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.onIndustrialNpcHired(
                npc, (ServerLevel) npc.level(), workplacePos, 
                buildingFileName != null ? buildingFileName : "industrial");
        }
    }

    /**
     * 处理规划师特殊逻辑（取消活跃任务）
     */
    private void handlePlannerSpecialCase(MinecraftServer server, CustomEntity npc, BlockPos workplacePos) {
        // 清除规划师的活跃任务
        for (ServerLevel level : server.getAllLevels()) {
            var taskManager = com.xiaoliang.simukraft.planning.PlanningTaskManager.get(level);
            var activeTask = taskManager.getActiveTaskByNpc(npc.getUUID());
            if (activeTask != null) {
                taskManager.cancelTask(activeTask.getTaskId());
            }
        }
    }

    /**
     * 广播状态更新给所有客户端
     */
    private void broadcastStatusUpdate(MinecraftServer server, CustomEntity targetNPC, 
                                       UUID entityUUID, WorkStatus newStatus, BlockPos buildBoxPos) {
        String employeeName = targetNPC != null ? targetNPC.getFullName() 
            : com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, entityUUID);

        // 发送解雇消息
        if (newStatus == WorkStatus.IDLE && targetNPC != null) {
            Component fireMessage = Component.translatable("message.simukraft.builder.fired", employeeName)
                    .withStyle(style -> style.withColor(0xFF5555));
            com.xiaoliang.simukraft.utils.CityMessageUtils.sendToCityGroup(
                server, targetNPC.getCityId(), fireMessage);
        }

        // 发送状态更新数据包
        server.getPlayerList().getPlayers().forEach(p -> {
            NetworkManager.sendToPlayer(
                new NPCWorkStatusPacket(entityUUID, newStatus, buildBoxPos, employeeName),
                p
            );
        });
    }

    private static void syncEmploymentV2(MinecraftServer server, UUID npcUuid, WorkStatus status, BlockPos workplacePos, String legacyJob, ServerLevel levelHint) {
        try {
            var service = EmploymentServices.get(server);

            if (status == WorkStatus.IDLE) {
                var releaseResult = service.fireByNpc(new EmploymentCommands.FireByNpcCommand(npcUuid));
                if (releaseResult.success() && releaseResult.assignment() != null) {
                    // 清理对应的legacy数据，防止LegacyHireStatusResolver回填导致"复活"
                    cleanupLegacyDataOnFire(server, npcUuid, releaseResult.assignment());
                    server.getPlayerList().getPlayers().forEach(p ->
                            NetworkManager.sendToPlayer(new EmploymentStateChangedPacket(releaseResult.assignment()), p));
                } else {
                    // v2中未找到记录，也需要尝试清理可能存在的legacy数据
                    cleanupAllLegacyDataByNpc(server, npcUuid);
                }
                return;
            }

            if (workplacePos == null || levelHint == null) {
                return;
            }

            String dimensionId = levelHint.dimension().location().toString();
            String workBlockHint = resolveWorkBlockHint(levelHint, workplacePos, legacyJob);
            WorkBlockType workBlockType = WorkBlockType.fromLegacyKey(workBlockHint);
            if (workBlockType == null) {
                return;
            }

            // Reassignment is handled as release-then-hire in Phase 2.
            var existingResult = service.fireByNpc(new EmploymentCommands.FireByNpcCommand(npcUuid));
            if (existingResult.success() && existingResult.assignment() != null) {
                cleanupLegacyDataOnFire(server, npcUuid, existingResult.assignment());
            }

            var hireResult = service.hire(new EmploymentCommands.HireCommand(
                    npcUuid,
                    dimensionId,
                    workplacePos,
                    workBlockType,
                    LegacyJobTypeMapper.fromLegacy(legacyJob, workBlockHint)
            ));

            if (hireResult.success() && hireResult.assignment() != null) {
                // 双写：同步写入legacy数据，保持过渡期一致性
                writeLegacyDataOnHire(server, hireResult.assignment(), legacyJob);
                server.getPlayerList().getPlayers().forEach(p ->
                        NetworkManager.sendToPlayer(new EmploymentStateChangedPacket(hireResult.assignment()), p));
                return;
            }

            if (hireResult.code() != EmploymentErrorCode.NPC_ALREADY_ASSIGNED
                    && hireResult.code() != EmploymentErrorCode.WORKPLACE_ALREADY_OCCUPIED) {
                Simukraft.LOGGER.error("[NPCWorkStatusPacket] sync write failed: code={}, message={}", hireResult.code(), hireResult.message());
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCWorkStatusPacket] sync write exception", e);
        }
    }

    /**
     * 根据v2 assignment清理对应的legacy数据
     */
    private static void cleanupLegacyDataOnFire(MinecraftServer server, UUID npcUuid,
                                                 com.xiaoliang.simukraft.employment.domain.EmploymentAssignment assignment) {
        try {
            switch (assignment.workBlockType()) {
                case BUILD_BOX -> {
                    var hiredBuilders = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server);
                    if (hiredBuilders.remove(assignment.workplacePos()) != null) {
                        com.xiaoliang.simukraft.world.BuildBoxHiredData.saveHiredBuilders(server, hiredBuilders);
                    }
                    var hiredPlanners = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(server);
                    if (hiredPlanners.remove(assignment.workplacePos()) != null) {
                        com.xiaoliang.simukraft.world.BuildBoxHiredData.saveHiredPlanners(server, hiredPlanners);
                    }
                }
                case FARMLAND_BOX -> {
                    com.xiaoliang.simukraft.world.FarmlandHiredData.loadAllFarmlandData(server);
                    var hiredFarmers = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
                    boolean removed = false;
                    for (var entry : new java.util.ArrayList<>(hiredFarmers.entrySet())) {
                        if (entry.getValue().equals(npcUuid)) {
                            com.xiaoliang.simukraft.world.FarmlandHiredData.clearHiredFarmer(entry.getKey());
                            removed = true;
                        }
                    }
                    if (removed) {
                        com.xiaoliang.simukraft.world.FarmlandHiredData.saveAllFarmlandData(server);
                    }
                }
                case INDUSTRIAL_CONTROL_BOX -> {
                    var industrialData = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
                    if (industrialData.remove(assignment.workplacePos()) != null) {
                        com.xiaoliang.simukraft.world.IndustrialHiredData.saveHiredEmployees(server, industrialData);
                    }
                }
                case COMMERCIAL_CONTROL_BOX -> {
                    var commercialData = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
                    if (commercialData.remove(assignment.workplacePos()) != null) {
                        com.xiaoliang.simukraft.world.CommercialHiredData.saveHiredEmployees(server, commercialData);
                    }
                }
                case LOGISTICS_SERVER_BOX -> { /* LogisticsData 自管理 */ }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCWorkStatusPacket] legacy cleanup error", e);
        }
    }

    /**
     * 当v2中没有记录时，全面清理所有legacy数据中该NPC的条目
     */
    private static void cleanupAllLegacyDataByNpc(MinecraftServer server, UUID npcUuid) {
        try {
            // BuildBox builders
            var hiredBuilders = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server);
            boolean buildersChanged = hiredBuilders.entrySet().removeIf(e -> e.getValue().equals(npcUuid));
            if (buildersChanged) {
                com.xiaoliang.simukraft.world.BuildBoxHiredData.saveHiredBuilders(server, hiredBuilders);
            }
            // BuildBox planners
            var hiredPlanners = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(server);
            boolean plannersChanged = hiredPlanners.entrySet().removeIf(e -> e.getValue().equals(npcUuid));
            if (plannersChanged) {
                com.xiaoliang.simukraft.world.BuildBoxHiredData.saveHiredPlanners(server, hiredPlanners);
            }
            // Farmland
            com.xiaoliang.simukraft.world.FarmlandHiredData.loadAllFarmlandData(server);
            var hiredFarmers = com.xiaoliang.simukraft.world.FarmlandHiredData.getHiredFarmers();
            boolean farmersRemoved = false;
            for (var entry : new java.util.ArrayList<>(hiredFarmers.entrySet())) {
                if (entry.getValue().equals(npcUuid)) {
                    com.xiaoliang.simukraft.world.FarmlandHiredData.clearHiredFarmer(entry.getKey());
                    farmersRemoved = true;
                }
            }
            if (farmersRemoved) {
                com.xiaoliang.simukraft.world.FarmlandHiredData.saveAllFarmlandData(server);
            }
            // Industrial
            var industrialData = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
            boolean industrialChanged = industrialData.entrySet().removeIf(e -> e.getValue().getNpcUuid().equals(npcUuid));
            if (industrialChanged) {
                com.xiaoliang.simukraft.world.IndustrialHiredData.saveHiredEmployees(server, industrialData);
            }
            // Commercial
            var commercialData = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
            boolean commercialChanged = commercialData.entrySet().removeIf(e -> e.getValue().getNpcUuid().equals(npcUuid));
            if (commercialChanged) {
                com.xiaoliang.simukraft.world.CommercialHiredData.saveHiredEmployees(server, commercialData);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCWorkStatusPacket] legacy full cleanup error", e);
        }
    }

    /**
     * 双写：v2雇佣成功后同步写入legacy数据
     */
    private static void writeLegacyDataOnHire(MinecraftServer server,
                                              com.xiaoliang.simukraft.employment.domain.EmploymentAssignment assignment,
                                              String legacyJob) {
        try {
            switch (assignment.workBlockType()) {
                case BUILD_BOX -> {
                    if (assignment.jobType() == com.xiaoliang.simukraft.employment.domain.JobType.PLANNER) {
                        var hiredPlanners = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(server);
                        hiredPlanners.put(assignment.workplacePos(), assignment.npcUuid());
                        com.xiaoliang.simukraft.world.BuildBoxHiredData.saveHiredPlanners(server, hiredPlanners);
                    } else {
                        var hiredBuilders = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredBuilders(server);
                        hiredBuilders.put(assignment.workplacePos(), assignment.npcUuid());
                        com.xiaoliang.simukraft.world.BuildBoxHiredData.saveHiredBuilders(server, hiredBuilders);
                    }
                }
                case FARMLAND_BOX -> {
                    // HireFarmerPacket已经写了legacy数据，这里不重复写
                }
                case INDUSTRIAL_CONTROL_BOX -> {
                    var industrialData = com.xiaoliang.simukraft.world.IndustrialHiredData.loadHiredEmployees(server);
                    String buildingFileName = com.xiaoliang.simukraft.utils.FileUtils.readIndustrialBuildingFileNameCached(server, assignment.workplacePos());
                    String buildingName = buildingFileName != null
                            ? com.xiaoliang.simukraft.building.IndustrialBuildingManager.getBuildingDisplayName(buildingFileName)
                            : "";
                    industrialData.put(assignment.workplacePos(),
                            new com.xiaoliang.simukraft.world.IndustrialHiredData.IndustrialHireInfo(
                                    assignment.workplacePos(), assignment.npcUuid(), legacyJob,
                                    buildingFileName != null ? buildingFileName : "",
                                    buildingName != null ? buildingName : ""));
                    com.xiaoliang.simukraft.world.IndustrialHiredData.saveHiredEmployees(server, industrialData);
                }
                case COMMERCIAL_CONTROL_BOX -> {
                    var commercialData = com.xiaoliang.simukraft.world.CommercialHiredData.loadHiredEmployees(server);
                    String buildingFileName = com.xiaoliang.simukraft.utils.FileUtils.readCommercialBuildingFileNameCached(server, assignment.workplacePos());
                    String buildingName = buildingFileName != null
                            ? com.xiaoliang.simukraft.building.CommercialBuildingManager.getBuildingDisplayName(buildingFileName)
                            : "";
                    commercialData.put(assignment.workplacePos(),
                            new com.xiaoliang.simukraft.world.CommercialHiredData.CommercialHireInfo(
                                    assignment.workplacePos(), assignment.npcUuid(), legacyJob,
                                    buildingFileName != null ? buildingFileName : "",
                                    buildingName != null ? buildingName : ""));
                    com.xiaoliang.simukraft.world.CommercialHiredData.saveHiredEmployees(server, commercialData);
                }
                case LOGISTICS_SERVER_BOX -> { /* LogisticsData 自管理 */ }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCWorkStatusPacket] legacy dual-write error", e);
        }
    }

    private static String resolveWorkBlockHint(ServerLevel level, BlockPos workplacePos, String legacyJob) {
        var block = level.getBlockState(workplacePos).getBlock();
        if (block == ModBlocks.BUILD_BOX.get()) {
            return "build_box";
        }
        if (block == ModBlocks.NSUK_FARMLAND_BOX.get()) {
            return "farmland";
        }
        if (block == ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
            if ("shepherd".equals(legacyJob)) {
                return "wool_farm";
            }
            if ("butcher".equals(legacyJob)) {
                return "beef_farm";
            }
            return "industrial";
        }
        if (block == ModBlocks.COMMERCIAL_CONTROL_BOX.get()) {
            // 所有商业建筑统一返回 commercial 类型
            return "commercial";
        }
        return "industrial";
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void handleClientSide(NPCWorkStatusPacket message) {
        try {
            // 获取NPC实体（仅客户端）
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level != null) {
                // 使用EntityUUID查找NPC实体
                com.xiaoliang.simukraft.entity.CustomEntity targetNPC = null;
                for (var entity : minecraft.level.entitiesForRendering()) {
                    if (entity instanceof com.xiaoliang.simukraft.entity.CustomEntity npc && entity.getUUID().equals(message.entityUUID)) {
                        targetNPC = npc;
                        break;
                    }
                }

                if (targetNPC != null) {
                    if (message.newStatus == com.xiaoliang.simukraft.entity.WorkStatus.IDLE) {
                        // 客户端清除所有关联记�?
                        clearClientSideRecords(targetNPC);
                    } else {
                        // 客户端设置关联记�?
                        if (message.buildBoxPos != null) {
                            setClientSideRecord(targetNPC, message.buildBoxPos, minecraft.level.getBlockState(message.buildBoxPos).getBlock());
                        }
                    }
                } else {
                    // 找不到NPC实体时的处理
                    // 直接通过UUID更新客户端记�?
                    if (message.newStatus == com.xiaoliang.simukraft.entity.WorkStatus.IDLE) {
                        // 客户端通过UUID清除所有关联记�?
                        clearClientSideRecordsByUUIDOnly(message.entityUUID);
                    } else {
                        // 客户端通过UUID设置关联记录
                        if (message.buildBoxPos != null) {
                            setClientSideRecordByUUIDOnly(message.entityUUID, message.buildBoxPos, minecraft.level.getBlockState(message.buildBoxPos).getBlock());
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 静默处理错误
        }
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void clearClientSideRecordsByUUIDOnly(UUID npcUuid) {
        try {
            // 清除建筑盒记�?
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.BuildBoxData", "clearHiredBuilderByUuid", npcUuid);
            
            // 清除羊毛农场记录
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.WoolFarmData", "fireEmployeeByUUID", npcUuid);
            
            // 清除建材商店记录
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.CommercialBuildingClientData", "fireEmployeeByUUID", npcUuid);
            
            // 清除牛肉农场记录
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.BeefFarmData", "fireEmployeeByUUID", npcUuid);
            
            // 清除肉铺记录
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.CommercialBuildingClientData", "fireEmployeeByUUID", npcUuid);
            
            // 清除农田盒记�?
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.FarmlandData", "fireEmployeeByUUID", npcUuid);
        } catch (Exception e) {
            // 静默处理错误
        }
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void setClientSideRecordByUUIDOnly(UUID npcUuid, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block) {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            
            if (block == com.xiaoliang.simukraft.init.ModBlocks.BUILD_BOX.get()) {
                // 设置建筑盒记�?
                Class<?> buildBoxDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.BuildBoxData");
                buildBoxDataClass.getMethod("setHiredBuilder", net.minecraft.core.BlockPos.class, UUID.class)
                    .invoke(null, pos, npcUuid);
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
                // 设置工业控制箱记�?
                // 使用默认职业类型，实际职业将在服务器端同�?
                Class<?> industrialDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.IndustrialClientData");
                industrialDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, UUID.class, String.class)
                    .invoke(null, pos, npcUuid, "worker");
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.COMMERCIAL_CONTROL_BOX.get()) {
                // 设置商业建筑记录
                Class<?> commercialDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.CommercialClientData");
                commercialDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, UUID.class, String.class)
                    .invoke(null, pos, npcUuid, "merchant");
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.COMMERCIAL_CONTROL_BOX.get()) {
                // 设置肉铺记录
                Class<?> commercialDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.CommercialBuildingClientData");
                commercialDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, UUID.class, String.class)
                    .invoke(null, pos, npcUuid, "meat_shop");
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.NSUK_FARMLAND_BOX.get()) {
                // 设置农田盒记�?
                Class<?> farmlandDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.FarmlandData");
                farmlandDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, UUID.class)
                    .invoke(null, pos, npcUuid);
            }
        } catch (Exception e) {
            // 静默处理错误
        }
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void clearClientSideRecords(com.xiaoliang.simukraft.entity.CustomEntity npc) {
        try {
            // 获取NPC的UUID
            UUID npcUuid = npc.getUUID();
            
            // 清除建筑盒记�?
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.BuildBoxData", "clearHiredBuilderByUuid", npcUuid);
            
            // 清除羊毛农场记录
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.WoolFarmData", "fireEmployeeByUUID", npcUuid);
            
            // 清除建材商店记录
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.CommercialBuildingClientData", "fireEmployeeByUUID", npcUuid);
            
            // 清除牛肉农场记录
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.BeefFarmData", "fireEmployeeByUUID", npcUuid);
            
            // 清除肉铺记录
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.CommercialBuildingClientData", "fireEmployeeByUUID", npcUuid);
            
            // 清除农田盒记�?
            clearClientSideRecordsByUUID("com.xiaoliang.simukraft.client.gui.FarmlandData", "fireEmployeeByUUID", npcUuid);
        } catch (Exception e) {
            // 静默处理错误
        }
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void clearClientSideRecordsByUUID(String className, String methodName, UUID npcUuid) {
        try {
            Class<?> dataClass = Class.forName(className);
            dataClass.getMethod(methodName, UUID.class).invoke(null, npcUuid);
        } catch (Exception e) {
            // 静默处理错误
        }
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void setClientSideRecord(com.xiaoliang.simukraft.entity.CustomEntity npc, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block) {

        try {
            if (block == com.xiaoliang.simukraft.init.ModBlocks.BUILD_BOX.get()) {
                // 设置建筑盒记�?
                Class<?> buildBoxDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.BuildBoxData");
                buildBoxDataClass.getMethod("setHiredBuilder", net.minecraft.core.BlockPos.class, com.xiaoliang.simukraft.entity.CustomEntity.class)
                    .invoke(null, pos, npc);
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
                // 设置羊毛农场记录
                Class<?> woolFarmDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.WoolFarmData");
                woolFarmDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, com.xiaoliang.simukraft.entity.CustomEntity.class)
                    .invoke(null, pos, npc);
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.COMMERCIAL_CONTROL_BOX.get()) {
                // 设置商业建筑记录
                Class<?> commercialDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.CommercialClientData");
                commercialDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, com.xiaoliang.simukraft.entity.CustomEntity.class, String.class)
                    .invoke(null, pos, npc, "merchant");
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
                // 设置牛肉农场记录
                Class<?> beefFarmDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.BeefFarmData");
                beefFarmDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, com.xiaoliang.simukraft.entity.CustomEntity.class)
                    .invoke(null, pos, npc);
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.COMMERCIAL_CONTROL_BOX.get()) {
                // 设置肉铺记录
                Class<?> commercialDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.CommercialBuildingClientData");
                commercialDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, com.xiaoliang.simukraft.entity.CustomEntity.class, String.class)
                    .invoke(null, pos, npc, "meat_shop");
            } else if (block == com.xiaoliang.simukraft.init.ModBlocks.NSUK_FARMLAND_BOX.get()) {
                // 设置农田盒记�?
                Class<?> farmlandDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.FarmlandData");
                farmlandDataClass.getMethod("setHiredEmployee", net.minecraft.core.BlockPos.class, com.xiaoliang.simukraft.entity.CustomEntity.class)
                    .invoke(null, pos, npc);
            }
        } catch (Exception e) {
            // 静默处理错误
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void setClientSidePlannerRecord(com.xiaoliang.simukraft.entity.CustomEntity npc, net.minecraft.core.BlockPos pos) {
        try {
            // 设置规划师记�?
            Class<?> buildBoxDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.BuildBoxData");
            buildBoxDataClass.getMethod("setHiredPlanner", net.minecraft.core.BlockPos.class, com.xiaoliang.simukraft.entity.CustomEntity.class)
                .invoke(null, pos, npc);
        } catch (Exception e) {
            // 静默处理错误
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void setClientSidePlannerRecordByUUID(UUID npcUuid, net.minecraft.core.BlockPos pos) {
        try {
            // 通过UUID设置规划师记�?
            Class<?> buildBoxDataClass = Class.forName("com.xiaoliang.simukraft.client.gui.BuildBoxData");
            buildBoxDataClass.getMethod("setHiredPlanner", net.minecraft.core.BlockPos.class, UUID.class)
                .invoke(null, pos, npcUuid);
        } catch (Exception e) {
            // 静默处理错误
        }
    }
}

