package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import com.xiaoliang.simukraft.employment.service.EmploymentCommands;
import com.xiaoliang.simukraft.employment.service.EmploymentErrorCode;
import com.xiaoliang.simukraft.employment.service.EmploymentResult;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import com.xiaoliang.simukraft.employment.service.LegacyJobTypeMapper;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.world.BuildBoxHiredData;
import com.xiaoliang.simukraft.world.CommercialHiredData;
import com.xiaoliang.simukraft.world.LogisticsHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class EmploymentCommandPacket {
    private final EmploymentCommandType commandType;
    private final UUID npcUuid;
    private final BlockPos workplacePos;
    private final String workBlockType;
    private final String jobType;
    private final String dimensionId;

    public EmploymentCommandPacket(EmploymentCommandType commandType, UUID npcUuid, BlockPos workplacePos, String workBlockType, String jobType, String dimensionId) {
        this.commandType = commandType;
        this.npcUuid = npcUuid;
        this.workplacePos = workplacePos;
        this.workBlockType = workBlockType;
        this.jobType = jobType;
        this.dimensionId = dimensionId;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建雇佣命令包
     * @param npcUuid NPC的UUID
     * @param workplacePos 工作位置
     * @param workBlockType 工作方块类型 (如 "build_box", "industrial", "farmland" 等)
     * @param jobType 职业类型 (如 "builder", "planner", "shepherd" 等)
     * @param dimensionId 维度ID (如 "minecraft:overworld")
     */
    public static EmploymentCommandPacket hire(UUID npcUuid, BlockPos workplacePos, 
                                               String workBlockType, String jobType, String dimensionId) {
        return new EmploymentCommandPacket(EmploymentCommandType.HIRE, npcUuid, workplacePos, 
                                          workBlockType, jobType, dimensionId);
    }

    /**
     * 创建解雇命令包（通过NPC）
     * @param npcUuid NPC的UUID
     */
    public static EmploymentCommandPacket fireByNpc(UUID npcUuid) {
        return new EmploymentCommandPacket(EmploymentCommandType.FIRE_BY_NPC, npcUuid, null, "", "", "");
    }

    /**
     * 创建解雇命令包（通过工作地点）
     * @param workplacePos 工作位置
     * @param dimensionId 维度ID
     */
    public static EmploymentCommandPacket fireByWorkplace(BlockPos workplacePos, String dimensionId) {
        return new EmploymentCommandPacket(EmploymentCommandType.FIRE_BY_WORKPLACE, null, workplacePos, "", "", dimensionId);
    }

    /**
     * 创建查询快照命令包
     * @param workplacePos 工作位置
     * @param workBlockType 工作方块类型
     * @param dimensionId 维度ID
     */
    public static EmploymentCommandPacket querySnapshot(BlockPos workplacePos, String workBlockType, String dimensionId) {
        return new EmploymentCommandPacket(EmploymentCommandType.QUERY_SNAPSHOT, null, workplacePos, 
                                          workBlockType, "", dimensionId);
    }

    public EmploymentCommandPacket(FriendlyByteBuf buf) {
        this.commandType = EmploymentCommandType.values()[buf.readInt()];
        this.npcUuid = buf.readBoolean() ? buf.readUUID() : null;
        this.workplacePos = buf.readBoolean() ? buf.readBlockPos() : null;
        this.workBlockType = buf.readUtf();
        this.jobType = buf.readUtf();
        this.dimensionId = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(commandType.ordinal());
        buf.writeBoolean(npcUuid != null);
        if (npcUuid != null) {
            buf.writeUUID(npcUuid);
        }
        buf.writeBoolean(workplacePos != null);
        if (workplacePos != null) {
            buf.writeBlockPos(workplacePos);
        }
        buf.writeUtf(workBlockType != null ? workBlockType : "");
        buf.writeUtf(jobType != null ? jobType : "");
        buf.writeUtf(dimensionId != null ? dimensionId : "");
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }

            String actualDimension = dimensionId == null || dimensionId.isBlank()
                    ? player.serverLevel().dimension().location().toString()
                    : dimensionId;

            var service = EmploymentServices.get(player.getServer());
            Simukraft.LOGGER.debug("[EmploymentCommandPacket] command={}, npcUuid={}, workplacePos={}, workBlockType={}, jobType={}",
                    commandType, npcUuid, workplacePos, workBlockType, jobType);

            // 如果雇佣失败因为NPC已分配，尝试先解雇再雇佣
            EmploymentResult initialResult = executeCommand(service, actualDimension, player.getServer());
            final EmploymentResult result;
            if (!initialResult.success() && initialResult.code() == EmploymentErrorCode.NPC_ALREADY_ASSIGNED) {
                Simukraft.LOGGER.debug("[EmploymentCommandPacket] NPC already assigned, retrying after legacy cleanup. npcUuid={}", npcUuid);
                // 强制清理该NPC的所有雇佣记录
                cleanupAllLegacyDataByNpc(player.getServer(), npcUuid);
                // 尝试解雇v2记录
                service.fireByNpc(new EmploymentCommands.FireByNpcCommand(npcUuid));
                // 重新尝试雇佣
                result = executeCommand(service, actualDimension, player.getServer());
            } else {
                result = initialResult;
            }

            Simukraft.LOGGER.debug("[EmploymentCommandPacket] result success={}, code={}, message={}",
                    result.success(), result.code(), result.message());

            // Push one command result to caller for deterministic GUI refresh.
            var snapshot = resolveSnapshot(service, actualDimension);

            NetworkManager.sendToPlayer(
                    new EmploymentSnapshotPacket(
                            result.success(),
                            result.code().name(),
                            result.message(),
                            snapshot.orElse(null),
                            actualDimension,
                            workplacePos
                    ),
                    player
            );

            if (result.success() && result.assignment() != null) {
                Simukraft.LOGGER.debug("[EmploymentCommandPacket] applying side effects for assignment={}", result.assignment());
                applyNpcSideEffects(player.getServer(), result.assignment(), commandType, workplacePos);

                var stateChanged = new EmploymentStateChangedPacket(result.assignment());
                player.getServer().getPlayerList().getPlayers().forEach(p -> NetworkManager.sendToPlayer(stateChanged, p));

                String npcName = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(player.getServer(), result.assignment().npcUuid());
                WorkStatus broadcastStatus = resolveBroadcastStatus(commandType, result.assignment().jobType());

                // 对于商业建筑，使用原始的jobType（支持自定义职业）
                String broadcastJob = result.assignment().jobType() == JobType.COMMERCIAL_GENERIC && jobType != null && !jobType.isBlank()
                    ? jobType  // 使用原始的jobType（如 boatShopOwner）
                    : LegacyJobTypeMapper.toLegacy(result.assignment().jobType());  // 使用转换后的legacyJob

                player.getServer().getPlayerList().getPlayers().forEach(p ->
                        NetworkManager.sendToPlayer(
                                new NPCWorkStatusPacket(
                                        result.assignment().npcUuid(),
                                        broadcastStatus,
                                        result.assignment().workplacePos(),
                                        npcName,
                                        broadcastJob
                                ),
                                p
                        )
                );

                broadcastLegacyClientSync(player.getServer(), result.assignment(), npcName, broadcastJob, commandType);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private EmploymentResult executeCommand(com.xiaoliang.simukraft.employment.service.DefaultEmploymentService service, String actualDimension, MinecraftServer server) {
        return switch (commandType) {
            case HIRE -> executeHire(service, actualDimension);
            case FIRE_BY_NPC -> {
                var result = service.fireByNpc(new EmploymentCommands.FireByNpcCommand(npcUuid));
                if (!result.success() && npcUuid != null && server != null) {
                    // v2中无记录，检查是否有legacy数据（BuildBoxHiredData等）
                    var legacyResult = cleanupLegacyDataByNpcAndCreateAssignment(server, npcUuid, actualDimension);
                    if (legacyResult != null) {
                        // 找到了legacy数据并清理，返回成功的结果
                        yield legacyResult;
                    }
                    // 没有legacy数据，只清理其他残留数据
                    cleanupAllLegacyDataByNpc(server, npcUuid);
                    // 即使没有找到legacy记录，也尝试清除建造任务
                    // 这种情况可能发生在v2系统无记录但JSON有任务数据时
                    CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);
                    if (npc != null && npc.getConstructionTask() != null) {
                        Simukraft.LOGGER.info("[EmploymentCommandPacket] v2和legacy都无记录，但NPC有建造任务，执行清理 - NPC: {}",
                            npcUuid.toString().substring(0, 8));
                        var constructionTask = npc.getConstructionTask();
                        if (!constructionTask.isCompleted()) {
                            constructionTask.cancel();
                        }
                        npc.setConstructionTask(null);
                        com.xiaoliang.simukraft.world.ConstructionTaskData.removeTask(server, npcUuid);
                        Simukraft.LOGGER.info("[EmploymentCommandPacket] 建筑师 {} 被解雇，建造任务已取消（无记录路径）", npc.getFullName());
                        // 返回成功的结果，以便执行副作用（设为空闲、发送消息等）
                        var fallbackAssignment = new com.xiaoliang.simukraft.employment.domain.EmploymentAssignment(
                            npcUuid, actualDimension, npc.blockPosition(),
                            com.xiaoliang.simukraft.employment.domain.WorkBlockType.BUILD_BOX,
                            com.xiaoliang.simukraft.employment.domain.JobType.BUILDER,
                            com.xiaoliang.simukraft.employment.domain.EmploymentStatus.ASSIGNED,
                            System.currentTimeMillis(),
                            System.currentTimeMillis()
                        );
                        yield EmploymentResult.ok("Fired with construction task cleanup", fallbackAssignment);
                    }
                }
                yield result;
            }
            case FIRE_BY_WORKPLACE -> service.fireByWorkplace(new EmploymentCommands.FireByWorkplaceCommand(actualDimension, workplacePos));
            case QUERY_SNAPSHOT -> EmploymentResult.ok("Snapshot queried", null);
        };
    }

    private EmploymentResult executeHire(com.xiaoliang.simukraft.employment.service.DefaultEmploymentService service, String actualDimension) {
        if (npcUuid == null || workplacePos == null) {
            Simukraft.LOGGER.debug("[EmploymentCommandPacket] executeHire aborted because npcUuid/workplacePos is null");
            return EmploymentResult.error(EmploymentErrorCode.INVALID_COMMAND, "npcUuid/workplacePos required for HIRE");
        }

        WorkBlockType resolvedType = WorkBlockType.fromLegacyKey(workBlockType);
        Simukraft.LOGGER.debug("[EmploymentCommandPacket] resolved workBlockType {} -> {}", workBlockType, resolvedType);
        if (resolvedType == null) {
            return EmploymentResult.error(EmploymentErrorCode.INVALID_WORKBLOCK, "Unknown work block type: " + workBlockType);
        }

        JobType resolvedJob = LegacyJobTypeMapper.fromLegacy(jobType, workBlockType);
        Simukraft.LOGGER.debug("[EmploymentCommandPacket] resolved jobType {} -> {}", jobType, resolvedJob);
        return service.hire(new EmploymentCommands.HireCommand(
                npcUuid,
                actualDimension,
                workplacePos,
                resolvedType,
                resolvedJob
        ));
    }

    private java.util.Optional<com.xiaoliang.simukraft.employment.domain.EmploymentAssignment> resolveSnapshot(
            com.xiaoliang.simukraft.employment.service.DefaultEmploymentService service,
            String actualDimension
    ) {
        if (workplacePos == null) {
            return java.util.Optional.empty();
        }

        WorkBlockType resolvedType = WorkBlockType.fromLegacyKey(workBlockType);
        if (resolvedType == WorkBlockType.BUILD_BOX) {
            JobType resolvedJob = LegacyJobTypeMapper.fromLegacy(jobType, workBlockType);
            return service.findByWorkplaceAndJob(actualDimension, workplacePos, resolvedJob);
        }

        return service.findByWorkplace(actualDimension, workplacePos);
    }

    private void applyNpcSideEffects(MinecraftServer server,
                                     com.xiaoliang.simukraft.employment.domain.EmploymentAssignment assignment,
                                     EmploymentCommandType type,
                                     BlockPos targetPos) {
        Simukraft.LOGGER.info("[EmploymentCommandPacket] applyNpcSideEffects type={}, workBlockType={}, jobType={}",
                type, assignment.workBlockType(), assignment.jobType());
        CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, assignment.npcUuid());
        Simukraft.LOGGER.info("[EmploymentCommandPacket] side-effect npc resolved={}, npcUuid={}", npc != null, assignment.npcUuid());

        if (type == EmploymentCommandType.FIRE_BY_NPC || type == EmploymentCommandType.FIRE_BY_WORKPLACE) {
            // 1. 物流系统仍保留独立存储，其余职业关系已统一到 v2 仓储
            cleanupLegacyDataOnFire(server, assignment.npcUuid(), assignment);
            syncFarmlandClientStateOnFire(server, assignment);

            // 2. 取消规划师活跃任务
            if (assignment.jobType() == JobType.PLANNER) {
                for (ServerLevel level : server.getAllLevels()) {
                    var taskManager = com.xiaoliang.simukraft.planning.PlanningTaskManager.get(level);
                    var activeTask = taskManager.getActiveTaskByNpc(assignment.npcUuid());
                    if (activeTask != null) {
                        taskManager.cancelTask(activeTask.getTaskId());
                        // 从JSON持久化存储中移除规划任务
                        com.xiaoliang.simukraft.job.jobs.planner.PlannerWorkService.INSTANCE.removePlanningTask(server, activeTask.getTaskId());
                        Simukraft.LOGGER.info("[EmploymentCommandPacket] 规划师 {} 被解雇，规划任务已取消", assignment.npcUuid().toString().substring(0, 8));
                    }
                }
                // 同时尝试从JSON中移除该NPC的所有规划任务（以防活跃任务未找到）
                com.xiaoliang.simukraft.job.jobs.planner.PlannerWorkService.INSTANCE.removePlanningTaskByNpc(server, assignment.npcUuid());
            }

            // 2.5. 取消建筑师建造任务
            if (assignment.jobType() == JobType.BUILDER) {
                if (npc != null) {
                    var constructionTask = npc.getConstructionTask();
                    if (constructionTask != null && !constructionTask.isCompleted()) {
                        constructionTask.cancel();
                    }
                    npc.setConstructionTask(null);
                    Simukraft.LOGGER.info("[EmploymentCommandPacket] 建筑师 {} 被解雇，建造任务已取消", npc.getFullName());
                }
                // 无论NPC是否存在，都清除JSON中的建造任务记录
                com.xiaoliang.simukraft.world.ConstructionTaskData.removeTask(server, assignment.npcUuid());
            }

            // 3. 物流仓库：清除 npcUuid - 照抄建筑盒模式
            if (assignment.workBlockType() == WorkBlockType.LOGISTICS_SERVER_BOX) {
                // 照抄建筑盒：发送 SyncLogisticsHireStatusPacket（npcUuid为null表示解雇）
                var syncPacket = new SyncLogisticsHireStatusPacket(
                        assignment.workplacePos(), null,
                        null, null
                );
                server.getPlayerList().getPlayers().forEach(p ->
                        NetworkManager.sendToPlayer(syncPacket, p)
                );
            }

            // 4. NPC实体副作用
            if (npc != null) {
                npc.setWorkStatus(WorkStatus.IDLE);
                npc.setJob("unemployed");
                npc.resetToIdle();
                npc.setItemInHand(npc.getUsedItemHand(), ItemStack.EMPTY);
                com.xiaoliang.simukraft.utils.NPCDataManager.saveJobData(npc);

                // 5. 发送解雇消息到城市群组（根据职业类型使用不同的本地化键）
                String legacyJob = LegacyJobTypeMapper.toLegacy(assignment.jobType());
                String fireMessageKey;
                
                // 首先检查是否是商业建筑职业
                if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(legacyJob)) {
                    fireMessageKey = "message.simukraft.commercial_employee.fired";
                } else {
                    fireMessageKey = switch (legacyJob) {
                        case "builder" -> "message.simukraft.builder.fired";
                        case "planner" -> "message.simukraft.planner.fired";
                        case "farmer" -> "message.simukraft.farmer.fired";
                        case "shepherd", "butcher" -> "message.simukraft.industrial_employee.fired";
                        case "warehouse_manager" -> "message.simukraft.warehouse_manager.fired";
                        default -> "message.simukraft.builder.fired";
                    };
                }
                Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                Component fireMsg = Component.translatable(fireMessageKey, npcName)
                        .withStyle(style -> style.withColor(0xFF5555));
                com.xiaoliang.simukraft.utils.CityMessageUtils.sendToCityGroup(server, npc.getCityId(), fireMsg);
            } else {
                // NPC未加载，直接修改jobdata.sk文件
                Simukraft.LOGGER.info("[EmploymentCommandPacket] NPC未加载，直接修改jobdata.sk文件 - NPC: {}",
                    assignment.npcUuid().toString().substring(0, 8));
                String npcId = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCIdByUUID(server, assignment.npcUuid());
                if (npcId != null) {
                    com.xiaoliang.simukraft.utils.NPCDataManager.saveJobData(server, npcId, "idle", "unemployed");
                    Simukraft.LOGGER.info("[EmploymentCommandPacket] 已设置NPC为空闲状态 - NPC ID: {}", npcId);
                } else {
                    Simukraft.LOGGER.warn("[EmploymentCommandPacket] 无法找到NPC ID，无法设置空闲状态 - UUID: {}",
                        assignment.npcUuid().toString().substring(0, 8));
                }
            }
            return;
        }

        // HIRE path
        // 1. 物流系统仍保留独立存储，其余职业关系已统一到 v2 仓储
        writeLegacyDataOnHire(server, assignment, LegacyJobTypeMapper.toLegacy(assignment.jobType()));

        // 2. NPC实体副作用
        if (npc == null) {
            Simukraft.LOGGER.debug("[EmploymentCommandPacket] hire side-effects skipped because npc entity is not loaded. npcUuid={}", assignment.npcUuid());
            return;
        }

        String legacyJob = LegacyJobTypeMapper.toLegacy(assignment.jobType());
        
        // 从建筑配置文件读取正确的 jobType
        String npcJob;
        if (assignment.workBlockType() == WorkBlockType.COMMERCIAL_CONTROL_BOX) {
            // 优先使用传入的jobType参数（支持自定义职业）
            if (jobType != null && !jobType.isBlank() && !"shopkeeper".equals(jobType)) {
                npcJob = jobType;
                Simukraft.LOGGER.debug("[EmploymentCommandPacket] commercial job using provided jobType: {}", npcJob);
            } else {
                // 从商业建筑配置文件读取 jobType (使用缓存)
                String buildingFileName = com.xiaoliang.simukraft.utils.FileUtils.readCommercialBuildingFileNameCached(server, assignment.workplacePos());
                String jobTypeFromConfig = readJobTypeFromCommercialConfig(server, buildingFileName);
                npcJob = jobTypeFromConfig != null ? jobTypeFromConfig : legacyJob;
                Simukraft.LOGGER.debug("[EmploymentCommandPacket] commercial job resolved to {} from {}", npcJob, buildingFileName);
            }
        } else if (assignment.workBlockType() == WorkBlockType.INDUSTRIAL_CONTROL_BOX) {
            // 优先使用传入的jobType参数（支持自定义职业）
            if (jobType != null && !jobType.isBlank()) {
                npcJob = jobType;
                Simukraft.LOGGER.debug("[EmploymentCommandPacket] industrial job using provided jobType: {}", npcJob);
            } else {
                // 从工业建筑配置文件读取 jobType (使用缓存)
                String buildingFileName = com.xiaoliang.simukraft.utils.FileUtils.readIndustrialBuildingFileNameCached(server, assignment.workplacePos());
                String jobTypeFromConfig = readJobTypeFromIndustrialConfig(server, buildingFileName);
                npcJob = jobTypeFromConfig != null ? jobTypeFromConfig : legacyJob;
                Simukraft.LOGGER.debug("[EmploymentCommandPacket] industrial job resolved to {} from {}", npcJob, buildingFileName);
            }
        } else if (assignment.jobType() == JobType.COMMERCIAL_GENERIC && jobType != null && !jobType.isBlank()) {
            // 对于其他商业建筑类型，使用原始的jobType
            npcJob = jobType;
        } else {
            npcJob = legacyJob;
        }

        Simukraft.LOGGER.debug("[EmploymentCommandPacket] set npc status=WORKING, npcJob={}, sourceJobType={}, legacyJob={}",
                npcJob, jobType, legacyJob);
        npc.setWorkStatus(WorkStatus.WORKING);
        npc.setJob(npcJob);

        if (targetPos != null) {
            Simukraft.LOGGER.debug("[EmploymentCommandPacket] schedule hire arrival teleport to {}", targetPos);
            npc.scheduleHireArrivalTeleport(targetPos);
        } else {
            Simukraft.LOGGER.debug("[EmploymentCommandPacket] targetPos is null, skip hire arrival teleport");
        }

        // 3. 根据职业设置手持物品
        // 优先从商业建筑配置读取heldItem
        ItemStack heldItem = ItemStack.EMPTY;
        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(npcJob)) {
            java.util.List<com.xiaoliang.simukraft.building.CommercialBuildingConfig> configs =
                com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfigsByJobType(npcJob);
            if (!configs.isEmpty()) {
                String heldItemId = configs.get(0).getHeldItem();
                if (heldItemId != null && !heldItemId.isEmpty()) {
                    try {
                        net.minecraft.resources.ResourceLocation itemId = 
                            net.minecraft.resources.ResourceLocation.tryParse(heldItemId);
                        if (itemId != null) {
                            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(itemId);
                            heldItem = item != null ? new ItemStack(item) : ItemStack.EMPTY;
                        }
                    } catch (Exception e) {
                        // 解析失败，使用默认物品
                    }
                }
            }
        }
        
        // 如果没有从配置读取到，使用默认逻辑
        if (heldItem.isEmpty()) {
            heldItem = switch (legacyJob) {
                case "builder" -> new ItemStack(Items.COBBLESTONE);
                case "planner" -> new ItemStack(Items.IRON_SHOVEL);
                case "shepherd" -> new ItemStack(Items.SHEARS);
                case "butcher" -> new ItemStack(Items.GOLDEN_AXE);
                case "farmer" -> new ItemStack(Items.STONE_HOE);
                case "warehouse_manager" -> new ItemStack(Items.BOOK);
                default -> ItemStack.EMPTY;
            };
        }
        npc.setItemInHand(npc.getUsedItemHand(), heldItem);

        com.xiaoliang.simukraft.utils.NPCDataManager.saveJobData(npc);

        // 4. 发送雇佣消息到城市群组
        // 使用 npcJob（从配置文件读取的正确职业）来判断消息键
        String messageKey;
        
        // 首先检查是否是商业建筑职业（统一使用CommercialBuildingManager）
        if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(npcJob)) {
            messageKey = "message.simukraft.commercial_employee.hired";
        } else {
            messageKey = switch (npcJob) {
                case "builder" -> "message.simukraft.builder.hired";
                case "planner" -> "message.simukraft.planner.hired";
                case "farmer" -> "message.simukraft.farmer.hired";
                case "shepherd", "butcher" -> "message.simukraft.industrial_employee.hired";
                case "warehouse_manager" -> "message.simukraft.warehouse_manager.hired";
                default -> null;
            };
        }
        
        // 对于工业建筑，使用通用消息
        if (messageKey == null && assignment.workBlockType() == WorkBlockType.INDUSTRIAL_CONTROL_BOX) {
            messageKey = "message.simukraft.industrial_employee.hired";
        }
        
        if (messageKey != null) {
            Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
            Component msg = Component.translatable(messageKey, npcName)
                    .withStyle(style -> style.withColor(0x55FF55));
            com.xiaoliang.simukraft.utils.CityMessageUtils.sendToCityGroup(server, npc.getCityId(), msg);
        }

        // 5. 工业建筑特殊逻辑
        if (targetPos != null) {
            var block = npc.level().getBlockState(targetPos).getBlock();
            if (block == com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
                String buildingFileName = com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.getBuildingFileName(
                        (ServerLevel) npc.level(), targetPos);
                com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler.onIndustrialNpcHired(
                        npc, (ServerLevel) npc.level(), targetPos,
                        buildingFileName != null ? buildingFileName : "industrial");
            }
        }

        // 6. 物流仓库：同步 npcUuid - 照抄建筑盒模式
        if (assignment.workBlockType() == WorkBlockType.LOGISTICS_SERVER_BOX && targetPos != null) {
            // 照抄建筑盒：发送 SyncLogisticsHireStatusPacket 给所有玩家
            var syncPacket = new SyncLogisticsHireStatusPacket(
                    targetPos, assignment.npcUuid(),
                    null, null
            );
            server.getPlayerList().getPlayers().forEach(p ->
                    NetworkManager.sendToPlayer(syncPacket, p)
            );
        }
    }

    /**
     * 仅保留仍未并入统一职业仓储的物流数据清理。
     */
    private void cleanupLegacyDataOnFire(MinecraftServer server, UUID npcUuid,
                                         com.xiaoliang.simukraft.employment.domain.EmploymentAssignment assignment) {
        try {
            switch (assignment.workBlockType()) {
                case COMMERCIAL_CONTROL_BOX -> CommercialHiredData.clearHiredEmployeesCache();
                case LOGISTICS_SERVER_BOX -> {
                    LogisticsHiredData.removeServerBoxHired(server, assignment.workplacePos());
                }
                case BUILD_BOX -> {
                    // 清除BuildBoxHiredData中的建筑师和规划师记录
                    if (assignment.jobType() == JobType.BUILDER) {
                        var hiredBuilders = BuildBoxHiredData.loadHiredBuilders(server);
                        if (hiredBuilders.remove(assignment.workplacePos()) != null) {
                            BuildBoxHiredData.saveHiredBuilders(server, hiredBuilders);
                            Simukraft.LOGGER.info("[EmploymentCommandPacket] 清除BuildBoxHiredData建筑师记录 - NPC: {}, 建筑盒: {}",
                                npcUuid.toString().substring(0, 8), assignment.workplacePos());
                        }
                    } else if (assignment.jobType() == JobType.PLANNER) {
                        var hiredPlanners = BuildBoxHiredData.loadHiredPlanners(server);
                        if (hiredPlanners.remove(assignment.workplacePos()) != null) {
                            BuildBoxHiredData.saveHiredPlanners(server, hiredPlanners);
                            Simukraft.LOGGER.info("[EmploymentCommandPacket] 清除BuildBoxHiredData规划师记录 - NPC: {}, 建筑盒: {}",
                                npcUuid.toString().substring(0, 8), assignment.workplacePos());
                        }
                    }
                }
                default -> {
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[EmploymentCommandPacket] legacy cleanup error", e);
        }
    }

    /**
     * 仅保留仍未并入统一职业仓储的物流数据同步。
     */
    private void writeLegacyDataOnHire(MinecraftServer server,
                                       com.xiaoliang.simukraft.employment.domain.EmploymentAssignment assignment,
                                       String legacyJob) {
        try {
            switch (assignment.workBlockType()) {
                case COMMERCIAL_CONTROL_BOX -> CommercialHiredData.clearHiredEmployeesCache();
                case LOGISTICS_SERVER_BOX -> {
                    LogisticsHiredData.setServerBoxHired(server, assignment.workplacePos(), assignment.npcUuid());
                }
                default -> {
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[EmploymentCommandPacket] legacy dual-write error", e);
        }
    }

    private WorkStatus resolveBroadcastStatus(EmploymentCommandType type, JobType jobType) {
        if (type == EmploymentCommandType.FIRE_BY_NPC || type == EmploymentCommandType.FIRE_BY_WORKPLACE) {
            return WorkStatus.IDLE;
        }
        return WorkStatus.WORKING;
    }

    /**
     * 仅保留物流系统的兼容清理。
     */
    private void cleanupAllLegacyDataByNpc(MinecraftServer server, UUID npcUuid) {
        try {
            CommercialHiredData.clearHiredEmployeesCache();
            BlockPos logisticsPos = LogisticsHiredData.findByNpcUuid(server, npcUuid);
            if (logisticsPos != null) {
                LogisticsHiredData.removeServerBoxHired(server, logisticsPos);
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[EmploymentCommandPacket] legacy full cleanup error", e);
        }
    }

    /**
     * 根据NPC UUID清理legacy数据（BuildBoxHiredData等），并创建临时Assignment用于执行解雇副作用
     * @return 如果找到了legacy数据并清理，返回成功的EmploymentResult；否则返回null
     */
    private EmploymentResult cleanupLegacyDataByNpcAndCreateAssignment(MinecraftServer server, UUID npcUuid, String dimensionId) {
        try {
            // 1. 检查BuildBoxHiredData（建筑师）
            var hiredBuilders = BuildBoxHiredData.loadHiredBuilders(server);
            for (var entry : hiredBuilders.entrySet()) {
                if (entry.getValue().equals(npcUuid)) {
                    BlockPos buildBoxPos = entry.getKey();
                    Simukraft.LOGGER.info("[EmploymentCommandPacket] 在BuildBoxHiredData中找到建筑师记录，执行legacy解雇清理 - NPC: {}, 建筑盒: {}",
                        npcUuid.toString().substring(0, 8), buildBoxPos);
                    // 从BuildBoxHiredData中移除
                    hiredBuilders.remove(buildBoxPos);
                    BuildBoxHiredData.saveHiredBuilders(server, hiredBuilders);
                    // 清除建造任务（如果存在）
                    CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);
                    if (npc != null && npc.getConstructionTask() != null) {
                        var constructionTask = npc.getConstructionTask();
                        if (!constructionTask.isCompleted()) {
                            constructionTask.cancel();
                        }
                        npc.setConstructionTask(null);
                        com.xiaoliang.simukraft.world.ConstructionTaskData.removeTask(server, npcUuid);
                        Simukraft.LOGGER.info("[EmploymentCommandPacket] 建筑师 {} 被解雇，建造任务已取消（legacy建筑师路径）", npc.getFullName());
                    }
                    // 创建临时Assignment用于执行解雇副作用
                    var legacyAssignment = new com.xiaoliang.simukraft.employment.domain.EmploymentAssignment(
                        npcUuid, dimensionId, buildBoxPos,
                        com.xiaoliang.simukraft.employment.domain.WorkBlockType.BUILD_BOX,
                        com.xiaoliang.simukraft.employment.domain.JobType.BUILDER,
                        com.xiaoliang.simukraft.employment.domain.EmploymentStatus.ASSIGNED,
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                    );
                    return EmploymentResult.ok("Fired from BuildBoxHiredData legacy", legacyAssignment);
                }
            }

            // 2. 检查BuildBoxHiredData（规划师）
            var hiredPlanners = BuildBoxHiredData.loadHiredPlanners(server);
            for (var entry : hiredPlanners.entrySet()) {
                if (entry.getValue().equals(npcUuid)) {
                    BlockPos buildBoxPos = entry.getKey();
                    Simukraft.LOGGER.info("[EmploymentCommandPacket] 在BuildBoxHiredData中找到规划师记录，执行legacy解雇清理 - NPC: {}, 建筑盒: {}",
                        npcUuid.toString().substring(0, 8), buildBoxPos);
                    // 从BuildBoxHiredData中移除
                    hiredPlanners.remove(buildBoxPos);
                    BuildBoxHiredData.saveHiredPlanners(server, hiredPlanners);
                    // 创建临时Assignment用于执行解雇副作用
                    var legacyAssignment = new com.xiaoliang.simukraft.employment.domain.EmploymentAssignment(
                        npcUuid, dimensionId, buildBoxPos,
                        com.xiaoliang.simukraft.employment.domain.WorkBlockType.BUILD_BOX,
                        com.xiaoliang.simukraft.employment.domain.JobType.PLANNER,
                        com.xiaoliang.simukraft.employment.domain.EmploymentStatus.ASSIGNED,
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                    );
                    return EmploymentResult.ok("Fired from BuildBoxHiredData legacy (planner)", legacyAssignment);
                }
            }
            
            // 3. 如果以上都没有找到，但NPC有建造任务，也需要清理
            // 这种情况可能发生在v2系统无记录但JSON有任务数据时
            CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);
            if (npc != null && npc.getConstructionTask() != null) {
                Simukraft.LOGGER.info("[EmploymentCommandPacket] 在BuildBoxHiredData中无记录，但NPC有建造任务，执行清理 - NPC: {}",
                    npcUuid.toString().substring(0, 8));
                var constructionTask = npc.getConstructionTask();
                if (!constructionTask.isCompleted()) {
                    constructionTask.cancel();
                }
                npc.setConstructionTask(null);
                com.xiaoliang.simukraft.world.ConstructionTaskData.removeTask(server, npcUuid);
                Simukraft.LOGGER.info("[EmploymentCommandPacket] 建筑师 {} 被解雇，建造任务已取消（legacy路径）", npc.getFullName());
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[EmploymentCommandPacket] legacy cleanup by NPC error", e);
        }
        return null;
    }

    /**
     * 从商业建筑配置文件中读取 jobType
     */
    private String readJobTypeFromCommercialConfig(MinecraftServer server, String buildingFileName) {
        try {
            if (buildingFileName != null && !buildingFileName.isEmpty()) {
                var config = com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(
                    buildingFileName.replace(".sk", "").toLowerCase()
                );
                if (config != null && config.getJobType() != null) {
                    return config.getJobType();
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[EmploymentCommandPacket] failed to read commercial jobType for {}", buildingFileName, e);
        }
        return null;
    }

    /**
     * 从工业建筑配置文件中读取 jobType
     */
    private String readJobTypeFromIndustrialConfig(MinecraftServer server, String buildingFileName) {
        try {
            if (buildingFileName != null && !buildingFileName.isEmpty()) {
                var config = com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfig(
                    buildingFileName.replace(".sk", "").toLowerCase()
                );
                if (config != null && config.getJobType() != null) {
                    return config.getJobType();
                }
            }
        } catch (Exception e) {
            Simukraft.LOGGER.error("[EmploymentCommandPacket] failed to read industrial jobType for {}", buildingFileName, e);
        }
        return null;
    }

    private void syncFarmlandClientStateOnFire(MinecraftServer server,
                                               com.xiaoliang.simukraft.employment.domain.EmploymentAssignment assignment) {
        if (assignment.workBlockType() != WorkBlockType.FARMLAND_BOX) {
            return;
        }

        BlockPos farmlandPos = assignment.workplacePos();
        com.xiaoliang.simukraft.world.FarmlandHiredData.clearHiredFarmer(farmlandPos);
        com.xiaoliang.simukraft.world.FarmlandHiredData.clearSelectedCrop(farmlandPos);
        com.xiaoliang.simukraft.world.FarmlandHiredData.clearSelectedArea(farmlandPos);
        com.xiaoliang.simukraft.world.FarmlandHiredData.clearBoundChest(farmlandPos);
        com.xiaoliang.simukraft.job.jobs.farmer.FarmerWorkService.INSTANCE.clearTimers(farmlandPos);
        com.xiaoliang.simukraft.world.FarmlandHiredData.saveAllFarmlandData(server);

        SyncFarmlandDataPacket.Response syncPacket = new SyncFarmlandDataPacket.Response(
                farmlandPos,
                null,
                null,
                null,
                0
        );
        server.getPlayerList().getPlayers().forEach(player -> NetworkManager.sendToPlayer(syncPacket, player));
    }

    private void broadcastLegacyClientSync(MinecraftServer server,
                                           com.xiaoliang.simukraft.employment.domain.EmploymentAssignment assignment,
                                           String npcName,
                                           String broadcastJob,
                                           EmploymentCommandType commandType) {
        switch (assignment.workBlockType()) {
            case BUILD_BOX -> {
                var service = EmploymentServices.get(server);
                String dimensionId = assignment.dimensionId();

                UUID builderUuid = service.findByWorkplaceAndJob(dimensionId, assignment.workplacePos(), JobType.BUILDER)
                        .map(com.xiaoliang.simukraft.employment.domain.EmploymentAssignment::npcUuid)
                        .orElse(null);
                UUID plannerUuid = service.findByWorkplaceAndJob(dimensionId, assignment.workplacePos(), JobType.PLANNER)
                        .map(com.xiaoliang.simukraft.employment.domain.EmploymentAssignment::npcUuid)
                        .orElse(null);

                String builderName = builderUuid != null
                        ? com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, builderUuid)
                        : null;
                String plannerName = plannerUuid != null
                        ? com.xiaoliang.simukraft.utils.NPCDataManager.getNPCNameByUUID(server, plannerUuid)
                        : null;

                SyncBuildBoxHireStatusPacket syncPacket = new SyncBuildBoxHireStatusPacket(
                        assignment.workplacePos(),
                        builderUuid,
                        plannerUuid,
                        builderName,
                        plannerName
                );
                server.getPlayerList().getPlayers().forEach(player -> NetworkManager.sendToPlayer(syncPacket, player));
            }
            case COMMERCIAL_CONTROL_BOX -> {
                boolean hired = commandType == EmploymentCommandType.HIRE;
                String buildingFileName = com.xiaoliang.simukraft.utils.FileUtils.readCommercialBuildingFileNameCached(server, assignment.workplacePos());
                String syncType = resolveCommercialSyncType(assignment.jobType(), buildingFileName);
                String syncJobType = readJobTypeFromCommercialConfig(server, buildingFileName);
                if (syncJobType == null || syncJobType.isBlank()) {
                    syncJobType = broadcastJob;
                }
                SyncWorkBlockHireStatusPacket syncPacket = new SyncWorkBlockHireStatusPacket(
                        assignment.workplacePos(),
                        syncType,
                        hired ? assignment.npcUuid() : null,
                        hired ? npcName : null,
                        syncJobType,
                        buildingFileName
                );
                server.getPlayerList().getPlayers().forEach(player -> NetworkManager.sendToPlayer(syncPacket, player));
            }
            case INDUSTRIAL_CONTROL_BOX -> {
                boolean hired = commandType == EmploymentCommandType.HIRE;
                String buildingFileName = com.xiaoliang.simukraft.utils.FileUtils.readIndustrialBuildingFileNameCached(server, assignment.workplacePos());
                String syncJobType = readJobTypeFromIndustrialConfig(server, buildingFileName);
                if (syncJobType == null || syncJobType.isBlank()) {
                    syncJobType = broadcastJob;
                }
                SyncWorkBlockHireStatusPacket syncPacket = new SyncWorkBlockHireStatusPacket(
                        assignment.workplacePos(),
                        resolveIndustrialSyncType(assignment.jobType()),
                        hired ? assignment.npcUuid() : null,
                        hired ? npcName : null,
                        syncJobType,
                        buildingFileName
                );
                server.getPlayerList().getPlayers().forEach(player -> NetworkManager.sendToPlayer(syncPacket, player));
            }
            default -> {
            }
        }
    }

    private String resolveCommercialSyncType(JobType jobType, String buildingFileName) {
        // 优先从JSON配置文件读取建筑类型
        if (buildingFileName != null && !buildingFileName.isBlank()) {
            var config = com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfig(buildingFileName.replace(".sk", "").toLowerCase());
            if (config != null) {
                String buildingType = config.getBuildingName();
                if (buildingType != null && !buildingType.isBlank()) {
                    return buildingType;
                }
                // 备用：使用workBlockHint
                if (config.getWorkBlockHint() != null && !config.getWorkBlockHint().isBlank()) {
                    return config.getWorkBlockHint();
                }
            }
        }
        return "commercial";
    }

    private String resolveIndustrialSyncType(JobType jobType) {
        return "industrial";
    }
}
