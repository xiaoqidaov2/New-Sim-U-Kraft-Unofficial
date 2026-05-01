package com.xiaoliang.simukraft.block;

import com.xiaoliang.simukraft.employment.service.EmploymentCommands;
import com.xiaoliang.simukraft.employment.service.DefaultEmploymentService;
import com.xiaoliang.simukraft.employment.service.EmploymentServices;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.NPCWorkStatusPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.planning.PlanningTaskManager;
import com.xiaoliang.simukraft.utils.BuildBoxFloatingEntityManager;
import com.xiaoliang.simukraft.utils.ClientRuntimeBridge;
import com.xiaoliang.simukraft.world.BuildBoxHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

public class BuildBoxBlock extends Block {
    // 用于跟踪已生成实体的建筑盒位置（现在使用持久化数据）
    // private static final Set<BlockPos> buildBoxesWithEntities = new HashSet<>();
    // private static final Map<BlockPos, UUID> buildBoxEntityMap = new HashMap<>();
    
    public BuildBoxBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(Objects.requireNonNull(MapColor.WOOD))
                .strength(0.8F)
                .sound(Objects.requireNonNull(SoundType.WOOD)));
    }
    
    //音效
    @Override
    public void playerWillDestroy(@Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState state, @Nonnull Player player) {
        SoundEvent sound = Objects.requireNonNull(ModSoundEvents.BUILD_BOX_BREAK.get());
        level.playSound(player, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
        
        // 移除关联的浮动建筑盒实体
        removeFloatingBuildBoxEntity(level, pos);
        
        super.playerWillDestroy(level, pos, state, player);
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
            DefaultEmploymentService employmentService = EmploymentServices.get(server);
            String dimensionId = serverLevel.dimension().location().toString();
            
            // 1. 取消该建筑盒关联的所有规划任务
            var taskManager = PlanningTaskManager.get(serverLevel);
            List<com.xiaoliang.simukraft.planning.PlanningTask> tasks = taskManager.getTasksByBuildBox(pos);
            for (com.xiaoliang.simukraft.planning.PlanningTask task : tasks) {
                if (task.getStatus() == com.xiaoliang.simukraft.planning.PlanningTask.TaskStatus.PENDING || 
                    task.getStatus() == com.xiaoliang.simukraft.planning.PlanningTask.TaskStatus.IN_PROGRESS) {
                    taskManager.cancelTask(task.getTaskId());
                }
            }

            // 2. 获取并解雇所有在该建筑盒工作的NPC（建筑师和规划师）
            employmentService.listAssignedByWorkplace(dimensionId, pos)
                    .forEach(assignment -> {
                        UUID npcUuid = assignment.npcUuid();
                        var npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);
                        
                        // 执行解雇逻辑
                        var releaseResult = employmentService.fireByNpc(new EmploymentCommands.FireByNpcCommand(npcUuid));
                        if (releaseResult.success() && releaseResult.assignment() != null) {
                            NetworkManager.sendToAll(new com.xiaoliang.simukraft.network.EmploymentStateChangedPacket(releaseResult.assignment()), serverLevel);
                        }

                        if (npc != null) {
                            // 根据职业清除状态
                            if (assignment.jobType() == com.xiaoliang.simukraft.employment.domain.JobType.BUILDER) {
                                clearBuilderState(serverLevel, npcUuid, npc);
                            } else if (assignment.jobType() == com.xiaoliang.simukraft.employment.domain.JobType.PLANNER) {
                                clearPlannerState(serverLevel, npcUuid, npc);
                            } else {
                                resetNpcAfterWorkStopped(npc);
                            }

                            // 发送状态更新数据包给客户端
                            server.getPlayerList().getPlayers().forEach(player -> {
                                NetworkManager.sendToPlayer(
                                    new NPCWorkStatusPacket(npc.getUUID(), WorkStatus.IDLE, pos),
                                    player
                                );
                            });

                            // 发送通知消息
                            String npcName = npc.getFullName();
                            UUID cityId = npc.getCityId();
                            if (cityId != null) {
                                Component message = assignment.jobType() == com.xiaoliang.simukraft.employment.domain.JobType.BUILDER 
                                    ? Component.translatable("message.simukraft.build_box.destroyed_builder", npcName)
                                    : Component.translatable("message.simukraft.build_box.destroyed_planner", npcName);
                                    
                                com.xiaoliang.simukraft.utils.CityMessageUtils.sendToCityGroup(
                                    server, cityId, message,
                                    com.xiaoliang.simukraft.notification.MessageCategory.CONSTRUCTION
                                );
                            }
                        }
                    });

            // 3. 同步清理旧系统的映射关系（如果有遗留）
            Map<BlockPos, UUID> hiredBuilders = BuildBoxHiredData.loadHiredBuilders(server);
            if (hiredBuilders.containsKey(pos)) {
                hiredBuilders.remove(pos);
                BuildBoxHiredData.saveHiredBuilders(server, hiredBuilders);
            }
            
            Map<BlockPos, UUID> hiredPlanners = BuildBoxHiredData.loadHiredPlanners(server);
            if (hiredPlanners.containsKey(pos)) {
                hiredPlanners.remove(pos);
                BuildBoxHiredData.saveHiredPlanners(server, hiredPlanners);
            }

            // 4. 通知所有客户端该建筑盒已销毁，清理本地缓存
            server.getPlayerList().getPlayers().forEach(player -> {
                NetworkManager.sendToPlayer(
                    new com.xiaoliang.simukraft.network.BuildBoxDestroyedPacket(pos),
                    player
                );
            });

            // 移除关联的浮动建筑盒实体
            removeFloatingBuildBoxEntity(level, pos);
        }
        super.onRemove(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(newState),
                isMoving
        );
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState oldState, boolean isMoving) {
        if (!oldState.is(Objects.requireNonNull(state.getBlock()))) {
            SoundEvent sound = Objects.requireNonNull(ModSoundEvents.BUILD_BOX_PLACE.get());
            level.playSound(null, Objects.requireNonNull(pos), sound, SoundSource.BLOCKS, 1.0F, 1.0F);
            
            // 发送聊天提示
            if (!level.isClientSide) {
                Component message = Objects.requireNonNull(Component.translatable("message.simukraft.build_direction_instruction"))
                        .withStyle(style -> style.withColor(0x00FF00)); // 绿色

                level.players().forEach(player -> {
                    if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0) {
                        player.sendSystemMessage(Objects.requireNonNull(message));
                    }
                });
            }
        }
        super.onPlace(
                Objects.requireNonNull(state),
                Objects.requireNonNull(level),
                Objects.requireNonNull(pos),
                Objects.requireNonNull(oldState),
                isMoving
        );
    }

    @Override
    public @Nonnull InteractionResult use(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull Player player, @Nonnull InteractionHand hand, @Nonnull BlockHitResult hit) {
        if (level.isClientSide) {
            try {
                ClientRuntimeBridge.openScreen(
                        "com.xiaoliang.simukraft.client.gui.BuildBoxScreen",
                        new Class<?>[]{BlockPos.class},
                        pos
                );
            } catch (Exception e) {
                System.err.println("[BuildBoxBlock] 打开GUI失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return Objects.requireNonNull(InteractionResult.sidedSuccess(level.isClientSide));
    }
    
    /**
     * 移除与建筑盒关联的浮动建筑盒实体
     */
    private void removeFloatingBuildBoxEntity(Level level, BlockPos buildBoxPos) {
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            BuildBoxFloatingEntityManager.remove(serverLevel, buildBoxPos);
        }
    }

    private void clearBuilderState(ServerLevel serverLevel, UUID npcUuid, com.xiaoliang.simukraft.entity.CustomEntity npc) {
        if (npc.getConstructionTask() != null) {
            npc.getConstructionTask().cancel();
        }
        com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.removeConstructionTask(serverLevel.getServer(), npcUuid);
        resetNpcAfterWorkStopped(npc);
    }

    private void clearPlannerState(ServerLevel serverLevel, UUID npcUuid, com.xiaoliang.simukraft.entity.CustomEntity npc) {
        var taskManager = PlanningTaskManager.get(serverLevel);
        var activeTask = taskManager.getActiveTaskByNpc(npcUuid);
        if (activeTask != null) {
            taskManager.cancelTask(activeTask.getTaskId());
        }
        resetNpcAfterWorkStopped(npc);
    }

    private void resetNpcAfterWorkStopped(com.xiaoliang.simukraft.entity.CustomEntity npc) {
        npc.setWorkStatus(WorkStatus.IDLE);
        npc.setWorking(false);
        npc.setJob("unemployed");
        npc.resetToIdle();
        npc.setItemInHand(InteractionHand.MAIN_HAND, Objects.requireNonNull(ItemStack.EMPTY));
        npc.setItemInHand(InteractionHand.OFF_HAND, Objects.requireNonNull(ItemStack.EMPTY));
        com.xiaoliang.simukraft.utils.NPCDataManager.saveJobData(npc);
    }
}
