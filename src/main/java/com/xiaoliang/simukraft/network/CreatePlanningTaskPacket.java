package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.planning.PlanningTask;
import com.xiaoliang.simukraft.planning.PlanningTaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 创建规划任务网络包
 * 客户端发送给服务器创建规划任务
 */
@SuppressWarnings("null")
public class CreatePlanningTaskPacket {

    public enum TaskType {
        REPLACE,
        FILL,
        REMOVE
    }

    private final BlockPos buildBoxPos;
    private final List<BlockPos> targetBlocks;
    private final TaskType type;
    private final String targetBlockId; // 用于替换/填充
    private final Map<String, String> replacementMap; // 用于方块替换映射：原方块ID -> 目标方块ID

    public CreatePlanningTaskPacket(BlockPos buildBoxPos, List<BlockPos> targetBlocks, TaskType type) {
        this(buildBoxPos, targetBlocks, type, null, null);
    }

    public CreatePlanningTaskPacket(BlockPos buildBoxPos, List<BlockPos> targetBlocks, TaskType type, String targetBlockId) {
        this(buildBoxPos, targetBlocks, type, targetBlockId, null);
    }

    public CreatePlanningTaskPacket(BlockPos buildBoxPos, List<BlockPos> targetBlocks, TaskType type, 
                                     String targetBlockId, Map<String, String> replacementMap) {
        this.buildBoxPos = buildBoxPos;
        this.targetBlocks = targetBlocks;
        this.type = type;
        this.targetBlockId = targetBlockId;
        this.replacementMap = replacementMap != null ? replacementMap : new HashMap<>();
    }

    public static void encode(CreatePlanningTaskPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.buildBoxPos);
        buf.writeInt(packet.type.ordinal());
        buf.writeInt(packet.targetBlocks.size());
        for (BlockPos pos : packet.targetBlocks) {
            buf.writeBlockPos(pos);
        }
        if (packet.targetBlockId != null) {
            buf.writeBoolean(true);
            buf.writeUtf(packet.targetBlockId);
        } else {
            buf.writeBoolean(false);
        }
        // 编码替换映射
        buf.writeInt(packet.replacementMap.size());
        for (Map.Entry<String, String> entry : packet.replacementMap.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeUtf(entry.getValue());
        }
    }

    public static CreatePlanningTaskPacket decode(FriendlyByteBuf buf) {
        BlockPos buildBoxPos = buf.readBlockPos();
        TaskType type = TaskType.values()[buf.readInt()];
        int count = buf.readInt();
        List<BlockPos> blocks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            blocks.add(buf.readBlockPos());
        }
        String targetBlockId = null;
        if (buf.readBoolean()) {
            targetBlockId = buf.readUtf();
        }
        // 解码替换映射
        Map<String, String> replacementMap = new HashMap<>();
        int mapSize = buf.readInt();
        for (int i = 0; i < mapSize; i++) {
            String key = buf.readUtf();
            String value = buf.readUtf();
            replacementMap.put(key, value);
        }
        return new CreatePlanningTaskPacket(buildBoxPos, blocks, type, targetBlockId, replacementMap);
    }

    // 填充费用（元/方块）
    private static final double COST_FILL_PER_BLOCK = 0.02;
    // 拆除费用（元/方块）
    private static final double COST_REMOVE_PER_BLOCK = 0.02;
    // 替换费用（元/方块）
    private static final double COST_REPLACE_PER_BLOCK = 0.04;

    public static void handle(CreatePlanningTaskPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;

            ServerLevel level = player.serverLevel();

            // 获取规划师NPC（从建筑盒获取已雇佣的规划师）
            UUID plannerNpcId = getPlannerNpcId(level, packet.buildBoxPos);
            if (plannerNpcId == null) {
                player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.simukraft.planning.error.no_planner"),
                    false
                );
                return;
            }

            // 计算费用
            int chargeableBlocks = 0;
            double costPerBlock = 0.0;
            if (packet.type == TaskType.FILL) {
                // 填充：只计算空气和花草方块
                chargeableBlocks = countAirAndPlantsBlocks(level, packet.targetBlocks);
                costPerBlock = COST_FILL_PER_BLOCK;
                if (chargeableBlocks == 0) {
                    player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.simukraft.planning.no_fill_blocks"),
                        false
                    );
                    return;
                }
            } else if (packet.type == TaskType.REMOVE) {
                // 拆除：计算非空气方块
                chargeableBlocks = countNonAirBlocks(level, packet.targetBlocks);
                costPerBlock = COST_REMOVE_PER_BLOCK;
                if (chargeableBlocks == 0) {
                    player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.simukraft.planning.no_remove_blocks"),
                        false
                    );
                    return;
                }
            } else if (packet.type == TaskType.REPLACE) {
                // 替换：计算目标方块数量（需要被替换的原方块数量）
                chargeableBlocks = countReplaceableBlocks(level, packet.targetBlocks, packet.replacementMap);
                costPerBlock = COST_REPLACE_PER_BLOCK;
                if (chargeableBlocks == 0) {
                    player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.simukraft.planning.no_replace_blocks"),
                        false
                    );
                    return;
                }
            }
            double cost = chargeableBlocks * costPerBlock;

            // 扣除资金
            boolean deducted = com.xiaoliang.simukraft.utils.MoneyManager.deductMoney(player, cost);
            if (!deducted) {
                player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                        "message.simukraft.planning.insufficient_funds", String.format("%.2f", cost)
                    ),
                    false
                );
                return;
            }

            // 转换任务类型
            PlanningTask.TaskType planningType = convertTaskType(packet.type);

            // 根据任务类型对方块列表进行排序
            // 替换和拆除：从顶部开始（降序）
            // 填充：从底部开始（升序）
            List<BlockPos> sortedBlocks = new ArrayList<>(packet.targetBlocks);
            if (packet.type == TaskType.FILL) {
                // 填充：从底部开始（Y坐标升序）
                sortedBlocks.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
            } else {
                // 替换和拆除：从顶部开始（Y坐标降序）
                sortedBlocks.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
            }

            // 创建任务
            PlanningTaskManager manager = PlanningTaskManager.get(level);
            PlanningTask task = manager.createTask(
                plannerNpcId,
                packet.buildBoxPos,
                planningType,
                sortedBlocks
            );

            if (packet.targetBlockId != null) {
                task.setTargetBlockId(packet.targetBlockId);
            }

            // 设置替换映射
            if (packet.replacementMap != null && !packet.replacementMap.isEmpty()) {
                task.setReplacementMap(packet.replacementMap);
            }

            // 开始任务
            manager.startTask(task.getTaskId());

            // 保存任务到JSON持久化存储
            com.xiaoliang.simukraft.job.jobs.planner.PlannerWorkService.INSTANCE.savePlanningTask(level.getServer(), task);

            // 通知玩家
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                    "message.simukraft.planning.task_created",
                    getTaskTypeName(packet.type), chargeableBlocks, cost
                ),
                false
            );
        });
        context.setPacketHandled(true);
    }

    private static UUID getPlannerNpcId(ServerLevel level, BlockPos buildBoxPos) {
        // 从 BuildBoxHiredData 获取规划师信息
        java.util.Map<BlockPos, UUID> planners = com.xiaoliang.simukraft.world.BuildBoxHiredData.loadHiredPlanners(level.getServer());
        return planners.get(buildBoxPos);
    }

    private static PlanningTask.TaskType convertTaskType(TaskType type) {
        return switch (type) {
            case REPLACE -> PlanningTask.TaskType.REPLACE;
            case FILL -> PlanningTask.TaskType.FILL;
            case REMOVE -> PlanningTask.TaskType.REMOVE;
        };
    }

    private static String getTaskTypeName(TaskType type) {
        return switch (type) {
            case REPLACE -> "Replace Blocks";
            case FILL -> "Fill Blocks";
            case REMOVE -> "Remove Blocks";
        };
    }

    /**
     * 计算空气和可替换植物方块数量（用于填充任务）
     */
    private static int countAirAndPlantsBlocks(ServerLevel level, List<BlockPos> positions) {
        int count = 0;
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || isReplaceablePlant(state)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 计算非空气方块数量（用于拆除任务）
     */
    private static int countNonAirBlocks(ServerLevel level, List<BlockPos> positions) {
        int count = 0;
        for (BlockPos pos : positions) {
            if (!level.getBlockState(pos).isAir()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 计算可替换的方块数量（用于替换任务）
     */
    private static int countReplaceableBlocks(ServerLevel level, List<BlockPos> positions, Map<String, String> replacementMap) {
        if (replacementMap == null || replacementMap.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            String blockId = block.getDescriptionId();
            if (replacementMap.containsKey(blockId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查方块是否是可替换的植物（杂草、花等）
     */
    private static boolean isReplaceablePlant(BlockState state) {
        Block block = state.getBlock();
        return block == net.minecraft.world.level.block.Blocks.GRASS ||
               block == net.minecraft.world.level.block.Blocks.TALL_GRASS ||
               block == net.minecraft.world.level.block.Blocks.FERN ||
               block == net.minecraft.world.level.block.Blocks.LARGE_FERN ||
               block == net.minecraft.world.level.block.Blocks.DEAD_BUSH ||
               block == net.minecraft.world.level.block.Blocks.DANDELION ||
               block == net.minecraft.world.level.block.Blocks.POPPY ||
               block == net.minecraft.world.level.block.Blocks.BLUE_ORCHID ||
               block == net.minecraft.world.level.block.Blocks.ALLIUM ||
               block == net.minecraft.world.level.block.Blocks.AZURE_BLUET ||
               block == net.minecraft.world.level.block.Blocks.RED_TULIP ||
               block == net.minecraft.world.level.block.Blocks.ORANGE_TULIP ||
               block == net.minecraft.world.level.block.Blocks.WHITE_TULIP ||
               block == net.minecraft.world.level.block.Blocks.PINK_TULIP ||
               block == net.minecraft.world.level.block.Blocks.OXEYE_DAISY ||
               block == net.minecraft.world.level.block.Blocks.CORNFLOWER ||
               block == net.minecraft.world.level.block.Blocks.LILY_OF_THE_VALLEY ||
               block == net.minecraft.world.level.block.Blocks.WITHER_ROSE ||
               block == net.minecraft.world.level.block.Blocks.SUNFLOWER ||
               block == net.minecraft.world.level.block.Blocks.LILAC ||
               block == net.minecraft.world.level.block.Blocks.ROSE_BUSH ||
               block == net.minecraft.world.level.block.Blocks.PEONY ||
               block == net.minecraft.world.level.block.Blocks.TALL_SEAGRASS ||
               block == net.minecraft.world.level.block.Blocks.SEAGRASS ||
               block == net.minecraft.world.level.block.Blocks.KELP ||
               block == net.minecraft.world.level.block.Blocks.KELP_PLANT;
    }
}
