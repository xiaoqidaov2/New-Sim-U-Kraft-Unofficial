package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class MaterialRequirementsRequestPacket {
    private final BlockPos cityCorePos;

    public MaterialRequirementsRequestPacket(BlockPos cityCorePos) {
        this.cityCorePos = cityCorePos;
    }

    public MaterialRequirementsRequestPacket(FriendlyByteBuf buf) {
        this.cityCorePos = Objects.requireNonNull(buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(Objects.requireNonNull(this.cityCorePos));
    }

    public static MaterialRequirementsRequestPacket decode(FriendlyByteBuf buf) {
        return new MaterialRequirementsRequestPacket(buf);
    }

    public static void handle(MaterialRequirementsRequestPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                ServerLevel level = player.serverLevel();
                CityData cityData = CityData.get(level);

                CityData.CityInfo city = cityData.getCityByCorePos(packet.cityCorePos);
                if (city != null) {
                    List<MaterialRequirementsResponsePacket.TaskMaterialInfo> taskInfos = new ArrayList<>();

                    // 遍历城市的所有市民，收集每个建造任务的材料需求
                    for (UUID citizenId : city.getCitizenIds()) {
                        Entity entity = level.getEntity(Objects.requireNonNull(citizenId));
                        if (entity instanceof CustomEntity npc) {
                            ConstructionTask task = npc.getConstructionTask();
                            if (task != null && !task.isCompleted()) {
                                // 收集单个任务的材料（扣除箱子中已有的）
                                List<MaterialRequirementsResponsePacket.MaterialInfo> materials =
                                    collectMaterialsForTask(task, level);
                                
                                // 过滤掉数量为0的材料
                                materials.removeIf(m -> m.count <= 0);
                                
                                if (!materials.isEmpty()) {
                                    String taskName = task.getBuildingName();
                                    String builderName = npc.getFullName();
                                    if (builderName == null || builderName.isEmpty()) {
                                        builderName = "Unknown NPC";
                                    }
                                    
                                    taskInfos.add(new MaterialRequirementsResponsePacket.TaskMaterialInfo(
                                        taskName,
                                        builderName,
                                        materials
                                    ));
                                }
                            }
                        }
                    }

                    MaterialRequirementsResponsePacket responsePacket =
                        new MaterialRequirementsResponsePacket(taskInfos);
                    NetworkManager.sendToPlayer(responsePacket, player);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 从建造任务中收集材料需求（扣除箱子中已有的）
     */
    public static List<MaterialRequirementsResponsePacket.MaterialInfo> collectMaterialsForTask(ConstructionTask task, ServerLevel level) {
        // 获取建筑盒位置
        BlockPos buildBoxPos = task.getBuildBoxPos();

        // 首先统计箱子中已有的材料
        Map<String, Integer> chestMaterials = countMaterialsInChests(level, buildBoxPos);

        // 统计任务需要的材料（合并双格方块）
        Map<String, MaterialData> materialMap = new HashMap<>();
        // 用于追踪已处理的双格方块位置，避免重复计数
        Set<BlockPos> processedDoubleBlocks = new HashSet<>();

        // 直接复用任务的只读方块列表，避免每次请求复制整份蓝图数据。
        int currentIndex = task.getCurrentBlockIndex();
        List<ConstructionTask.BlockInfo> blocks = task.getBlocksToPlace();

        for (int i = currentIndex; i < blocks.size(); i++) {
            ConstructionTask.BlockInfo blockInfo = blocks.get(i);
            BlockState state = blockInfo.state();
            Block block = state.getBlock();
            BlockPos pos = blockInfo.pos();
            String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();

            // 检查是否是材料方块
            if (isMaterialBlock(blockId, block)) {
                ItemStack itemStack = new ItemStack(Objects.requireNonNull(block.asItem()));
                if (!itemStack.isEmpty()) {
                    // 处理双格方块（床和门）
                    if (isDoubleBlock(state)) {
                        // 如果这个位置的双格方块已经处理过，跳过
                        if (processedDoubleBlocks.contains(pos)) {
                            continue;
                        }
                        // 标记这个位置已处理
                        processedDoubleBlocks.add(pos);
                        // 同时标记另一半的位置
                        BlockPos otherHalfPos = getOtherHalfPos(state, Objects.requireNonNull(pos));
                        if (otherHalfPos != null) {
                            processedDoubleBlocks.add(otherHalfPos);
                        }
                        // 双格方块只计为一个物品
                        materialMap.computeIfAbsent(blockId, k -> new MaterialData(blockId))
                                   .count++;
                    } else {
                        // 普通方块正常计数
                        materialMap.computeIfAbsent(blockId, k -> new MaterialData(blockId))
                                   .count++;
                    }
                }
            }
        }

        // 扣除箱子中已有的材料
        for (Map.Entry<String, MaterialData> entry : materialMap.entrySet()) {
            String blockId = entry.getKey();
            MaterialData data = entry.getValue();
            int chestCount = chestMaterials.getOrDefault(blockId, 0);
            data.count = Math.max(0, data.count - chestCount);
        }

        // 转换为列表
        List<MaterialRequirementsResponsePacket.MaterialInfo> result = new ArrayList<>();
        for (MaterialData data : materialMap.values()) {
            if (data.count > 0) {
                // 不再传输中文名称，客户端会本地进行映射
                result.add(new MaterialRequirementsResponsePacket.MaterialInfo(
                    data.blockId,
                    "", // displayName由客户端本地映射生成
                    data.count
                ));
            }
        }

        // 按数量排序（从多到少）
        result.sort((a, b) -> Integer.compare(b.count, a.count));

        return result;
    }

    /**
     * 检查是否是双格方块（床或门）
     */
    private static boolean isDoubleBlock(BlockState state) {
        Block block = state.getBlock();
        // 检查是否是床
        if (block instanceof BedBlock) {
            return true;
        }
        // 检查是否是门
        if (block instanceof DoorBlock) {
            return true;
        }
        return false;
    }

    /**
     * 获取双格方块的另一半位置
     */
    private static BlockPos getOtherHalfPos(BlockState state, BlockPos pos) {
        Block block = state.getBlock();

        // 处理床
        if (block instanceof BedBlock) {
            if (state.hasProperty(Objects.requireNonNull(BedBlock.PART))) {
                BedPart part = state.getValue(Objects.requireNonNull(BedBlock.PART));
                // 根据朝向获取另一半位置
                if (state.hasProperty(Objects.requireNonNull(BedBlock.FACING))) {
                    net.minecraft.core.Direction facing = state.getValue(Objects.requireNonNull(BedBlock.FACING));
                    if (part == BedPart.FOOT) {
                        // 脚在头部后面，所以头部在脚的前面
                        return pos.relative(Objects.requireNonNull(facing));
                    } else {
                        // 头部在脚的前面，所以脚在头部的后面
                        return pos.relative(Objects.requireNonNull(facing.getOpposite()));
                    }
                }
            }
        }

        // 处理门
        if (block instanceof DoorBlock) {
            if (state.hasProperty(Objects.requireNonNull(DoorBlock.HALF))) {
                DoubleBlockHalf half = state.getValue(Objects.requireNonNull(DoorBlock.HALF));
                if (half == DoubleBlockHalf.LOWER) {
                    // 下半部分，上半部分在上面
                    return pos.above();
                } else {
                    // 上半部分，下半部分在下面
                    return pos.below();
                }
            }
        }

        return null;
    }

    /**
     * 统计建筑盒周围容器中的材料数量（支持Container接口和IItemHandler Capability）
     */
    private static Map<String, Integer> countMaterialsInChests(ServerLevel level, BlockPos buildBoxPos) {
        Map<String, Integer> chestMaterials = new HashMap<>();
        BlockPos safeBuildBoxPos = Objects.requireNonNull(buildBoxPos);

        // 搜索范围：以建筑盒为中心上下左右5格
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    BlockPos checkPos = safeBuildBoxPos.offset(x, y, z);

                    // 检查是否是容器（支持Container和IItemHandler）
                    if (ContainerUtils.isContainer(level, checkPos)) {
                        // 获取容器中的所有物品
                        List<ItemStack> items = ContainerUtils.getAllItems(level, checkPos);
                        for (ItemStack stack : items) {
                            if (!stack.isEmpty()) {
                                String blockId = getBlockIdFromItem(stack);
                                if (blockId != null) {
                                    chestMaterials.put(blockId, chestMaterials.getOrDefault(blockId, 0) + stack.getCount());
                                }
                            }
                        }
                    }
                }
            }
        }

        return chestMaterials;
    }

    /**
     * 根据物品获取对应的方块ID
     */
    private static String getBlockIdFromItem(ItemStack stack) {
        // 尝试找到对应的方块
        // 大多数建筑方块物品的ID与方块ID相同
        Block block = net.minecraft.world.level.block.Block.byItem(Objects.requireNonNull(stack.getItem()));
        if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
            return Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(block)).toString();
        }
        
        return null;
    }

    /**
     * 检查是否是材料方块（根据配置系统）
     */
    private static boolean isMaterialBlock(String blockId, Block block) {
        if (blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air") || blockId.equals("minecraft:void_air")) {
            return false;
        }

        // 使用MaterialManager根据配置判断是否需要材料
        return com.xiaoliang.simukraft.utils.MaterialManager.requiresMaterial(block.defaultBlockState());
    }

    private static class MaterialData {
        final String blockId;
        int count;

        MaterialData(String blockId) {
            this.blockId = blockId;
            this.count = 0;
        }
    }
}
