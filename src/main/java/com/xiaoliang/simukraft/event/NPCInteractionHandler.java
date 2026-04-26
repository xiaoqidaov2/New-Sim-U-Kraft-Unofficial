package com.xiaoliang.simukraft.event;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.client.gui.CommercialClientData;
import com.xiaoliang.simukraft.client.gui.IndustrialClientData;
import com.xiaoliang.simukraft.employment.client.WorkBlockHireClientCache;
import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.Gender;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
@SuppressWarnings("null")
public class NPCInteractionHandler {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos clickedPos = event.getPos();
        BlockState blockState = serverLevel.getBlockState(clickedPos);

        // simukraft: 右键床唤醒睡在上面的NPC
        if (blockState.getBlock() instanceof BedBlock && event.getSide().isServer()) {
            if (tryWakeUpNPCOnBed(serverLevel, clickedPos, event.getEntity())) {
                event.setCanceled(true);
                return;
            }
        }

        if (!ContainerUtils.isContainer(serverLevel, clickedPos)) {
            return;
        }

        // 玩家开箱后延迟 1 秒再刷新，给拖拽物品/物流写入留出时间。
        ServerTickHandler.scheduleBuilderContainerRefresh(serverLevel, clickedPos, 20);
    }

    /**
     * 尝试唤醒睡在床上的NPC（玩家右键床唤醒）
     * @param level 服务器世界
     * @param bedPos 床的位置
     * @param player 玩家
     * @return 是否成功唤醒
     */
    private static boolean tryWakeUpNPCOnBed(ServerLevel level, BlockPos bedPos, Player player) {
        // 查找睡在该床上的NPC
        CustomEntity sleepingNpc = findNPCsleepingOnBed(level, bedPos);
        if (sleepingNpc == null) {
            return false;
        }

        // 唤醒NPC
        wakeUpNPC(sleepingNpc, player);
        return true;
    }

    /**
     * 查找睡在指定床上的NPC（原版风格，检查床的两个部分）
     * @param level 服务器世界
     * @param bedPos 床的位置（可以是床头或床尾）
     * @return 睡在该床上的NPC，如果没有返回null
     */
    private static CustomEntity findNPCsleepingOnBed(ServerLevel level, BlockPos bedPos) {
        BlockState bedState = level.getBlockState(bedPos);
        if (!(bedState.getBlock() instanceof BedBlock)) {
            return null;
        }

        // 获取床头和床尾位置（原版风格：床由两部分组成）
        BlockPos headPos = bedPos;
        BlockPos footPos = bedPos;

        if (bedState.hasProperty(BedBlock.FACING)) {
            net.minecraft.core.Direction facing = bedState.getValue(BedBlock.FACING);
            // 检查当前是床头还是床尾
            if (bedState.hasProperty(BedBlock.PART)) {
                net.minecraft.world.level.block.state.properties.BedPart part = bedState.getValue(BedBlock.PART);
                if (part == net.minecraft.world.level.block.state.properties.BedPart.FOOT) {
                    // 当前是床尾，床头在朝向方向
                    headPos = bedPos.relative(facing);
                } else {
                    // 当前是床头，床尾在反方向
                    footPos = bedPos.relative(facing.getOpposite());
                }
            }
        }

        // 检查床头和床尾周围是否有睡觉的NPC
        BlockPos[] checkPositions = {headPos, footPos};
        for (BlockPos checkPos : checkPositions) {
            for (CustomEntity npc : level.getEntitiesOfClass(CustomEntity.class,
                    new net.minecraft.world.phys.AABB(checkPos).inflate(1.5D))) {
                if (npc.isSleeping() &&
                    npc.getSleepingPos().isPresent()) {
                    BlockPos npcBedPos = npc.getSleepingPos().get();
                    // NPC睡在床头或床尾都算作睡在这张床上
                    if (npcBedPos.equals(headPos) || npcBedPos.equals(footPos)) {
                        return npc;
                    }
                }
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof CustomEntity npc && event.getHand() == InteractionHand.MAIN_HAND) {
            Player player = event.getEntity();

            // simukraft: NPC正在睡觉时，右键不打开界面，提示玩家右键床唤醒
            if (npc.isSleeping() && event.getSide().isServer()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.simukraft.npc_sleeping_right_click_bed", npc.getFullName()));
                event.setCanceled(true);
                return;
            }

            if (event.getSide().isClient()) {
                playGenderSound(npc);
                // 潜行时右键永远打开NPC详细信息界面
                if (player.isShiftKeyDown()) {
                    openNPCDetailScreen(npc);
                } else {
                    openNPCScreen(player, npc);
                }
            }
            event.setCanceled(true);
        }
    }

    /**
     * 唤醒正在睡觉的NPC（玩家右键唤醒）
     * @param npc 正在睡觉的NPC
     * @param player 唤醒NPC的玩家
     */
    private static void wakeUpNPC(CustomEntity npc, Player player) {
        if (!npc.isSleeping()) return;

        // 在服务端执行唤醒
        if (npc.level() instanceof ServerLevel serverLevel) {
            com.xiaoliang.simukraft.utils.NPCRestHandler.wakeUpNPC(npc, serverLevel);

            // 发送消息给玩家
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "message.simukraft.npc_woken_up", npc.getFullName()));

            // 播放唤醒音效
            serverLevel.playSound(null, npc.blockPosition(),
                net.minecraft.sounds.SoundEvents.VILLAGER_YES,
                net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
    }

    /**
     * 处理右键物品事件，防止手持食物等物品右键NPC时被消耗
     * 在客户端和服务端都执行，确保右键状态正确重置
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        Player player = event.getEntity();

        // 检查玩家是否看向NPC（客户端和服务端都需要检查）
        net.minecraft.world.phys.EntityHitResult hitResult = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
            player.level(),
            player,
            player.getEyePosition(1.0F),
            player.getEyePosition(1.0F).add(player.getViewVector(1.0F).scale(5.0)),
            player.getBoundingBox().expandTowards(player.getViewVector(1.0F).scale(5.0)).inflate(1.0),
            entity -> entity instanceof CustomEntity && entity.isPickable()
        );

        if (hitResult != null && hitResult.getEntity() instanceof CustomEntity) {
            // 玩家看向NPC，取消物品使用
            // 在客户端和服务端都取消，防止右键长按状态保持
            event.setCanceled(true);

            // 在客户端额外重置右键状态
            if (player.level().isClientSide()) {
                resetPlayerUseItem(player);
            }
        }
    }

    /**
     * 重置玩家的物品使用状态，防止右键长按保持
     */
    @OnlyIn(Dist.CLIENT)
    private static void resetPlayerUseItem(Player player) {
        // 停止客户端的物品使用动画
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.equals(player)) {
            // 发送停止使用的包到服务端
            minecraft.gameMode.releaseUsingItem(player);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void openNPCDetailScreen(CustomEntity npc) {
        Minecraft.getInstance().setScreen(new com.xiaoliang.simukraft.client.gui.NPCCardScreen(npc));
    }

    @OnlyIn(Dist.CLIENT)
    private static void playGenderSound(CustomEntity npc) {
        Minecraft minecraft = Minecraft.getInstance();
        SoundEvent sound = npc.getGender() == Gender.FEMALE
                ? ModSoundEvents.FEMALE_HELLO.get()
                : ModSoundEvents.MALE_HELLO.get();

        minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(
                        sound,
                        1.0F,
                        1.0F
                )
        );
    }

    @OnlyIn(Dist.CLIENT)
    private static void openNPCScreen(Player player, CustomEntity npc) {
        if (npc.getWorkStatus() == WorkStatus.WORKING) {
            WorkUiContext workUiContext = resolveWorkUiContext(npc);
            if (workUiContext != null) {
                if (workUiContext.workBlockType == WorkBlockType.COMMERCIAL_CONTROL_BOX) {
                    openCommercialTradeSelectScreen(workUiContext.workplacePos, workUiContext.buildingFileName);
                    return;
                }
                if (workUiContext.workBlockType == WorkBlockType.INDUSTRIAL_CONTROL_BOX) {
                    openIndustrialControlBoxScreen(workUiContext.workplacePos, workUiContext.buildingFileName);
                    return;
                }
            }
        }

        // 打开普通NPC属性界面
        openScreenWithReflection(false, npc);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openCommercialTradeSelectScreen(BlockPos workPos, String buildingFileName) {
        // 打开交易选择界面
        Minecraft.getInstance().setScreen(
            new com.xiaoliang.simukraft.client.gui.CommercialTradeSelectScreen(workPos, buildingFileName)
        );

        // 播放打开界面音效
        Minecraft.getInstance().getSoundManager().play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK,
                1.0F
            )
        );
    }

    @OnlyIn(Dist.CLIENT)
    private static String readCommercialBuildingFileNameFromClient(BlockPos pos) {
        try {
            var minecraft = Minecraft.getInstance();
            if (minecraft.getSingleplayerServer() == null) {
                return null;
            }
            
            return com.xiaoliang.simukraft.client.utils.ClientFileUtils.readCommercialBuildingFileNameClient(
                minecraft, pos);
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCInteractionHandler] 读取建筑文件名失败: {}", e.getMessage());
        }
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    private static String readIndustrialBuildingFileNameFromClient(BlockPos pos) {
        try {
            var minecraft = Minecraft.getInstance();
            if (minecraft.getSingleplayerServer() == null) {
                return null;
            }
            return com.xiaoliang.simukraft.utils.FileUtils.readIndustrialBuildingFileNameCached(
                    minecraft.getSingleplayerServer(), pos);
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCInteractionHandler] 读取工业建筑文件名失败: {}", e.getMessage());
        }
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    private static void openIndustrialControlBoxScreen(BlockPos workPos, String buildingFileName) {
        Minecraft.getInstance().setScreen(
                new com.xiaoliang.simukraft.client.gui.IndustrialControlBoxLDLibScreen(workPos, buildingFileName)
        );
        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK,
                        1.0F
                )
        );
    }

    @OnlyIn(Dist.CLIENT)
    private static WorkUiContext resolveWorkUiContext(CustomEntity npc) {
        EmploymentAssignment assignment = WorkBlockHireClientCache.findByNpc(npc.getUUID()).orElse(null);
        if (assignment != null) {
            String buildingFileName = switch (assignment.workBlockType()) {
                case COMMERCIAL_CONTROL_BOX -> resolveCommercialBuildingFileName(assignment.workplacePos());
                case INDUSTRIAL_CONTROL_BOX -> resolveIndustrialBuildingFileName(assignment.workplacePos());
                default -> "";
            };
            if (assignment.workBlockType() == WorkBlockType.COMMERCIAL_CONTROL_BOX
                    || assignment.workBlockType() == WorkBlockType.INDUSTRIAL_CONTROL_BOX) {
                return new WorkUiContext(assignment.workBlockType(), assignment.workplacePos(), buildingFileName);
            }
        }

        WorkUiContext commercialFallback = findCommercialWorkUiContext(npc);
        if (commercialFallback != null) {
            return commercialFallback;
        }

        return findIndustrialWorkUiContext(npc);
    }

    @OnlyIn(Dist.CLIENT)
    private static WorkUiContext findCommercialWorkUiContext(CustomEntity npc) {
        UUID npcUuid = npc.getUUID();
        Map<BlockPos, CommercialClientData.HireInfo> allHires = CommercialClientData.getAllHiredEmployeeUuids();
        for (Map.Entry<BlockPos, CommercialClientData.HireInfo> entry : allHires.entrySet()) {
            CommercialClientData.HireInfo hireInfo = entry.getValue();
            if (hireInfo.getNpcUuid().equals(npcUuid)) {
                String buildingFileName = hireInfo.getBuildingFileName();
                if (buildingFileName == null || buildingFileName.isEmpty()) {
                    buildingFileName = readCommercialBuildingFileNameFromClient(entry.getKey());
                }
                return new WorkUiContext(WorkBlockType.COMMERCIAL_CONTROL_BOX, entry.getKey(), buildingFileName);
            }
        }
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    private static WorkUiContext findIndustrialWorkUiContext(CustomEntity npc) {
        UUID npcUuid = npc.getUUID();
        Map<BlockPos, IndustrialClientData.HireInfo> allHires = IndustrialClientData.getAllHiredEmployeeUuids();
        for (Map.Entry<BlockPos, IndustrialClientData.HireInfo> entry : allHires.entrySet()) {
            IndustrialClientData.HireInfo hireInfo = entry.getValue();
            if (hireInfo.getNpcUuid().equals(npcUuid)) {
                String buildingFileName = hireInfo.getBuildingFileName();
                if (buildingFileName == null || buildingFileName.isEmpty()) {
                    buildingFileName = readIndustrialBuildingFileNameFromClient(entry.getKey());
                }
                return new WorkUiContext(WorkBlockType.INDUSTRIAL_CONTROL_BOX, entry.getKey(), buildingFileName);
            }
        }
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    private static String resolveCommercialBuildingFileName(BlockPos pos) {
        if (pos == null) {
            return "";
        }
        String buildingFileName = CommercialClientData.getAllHiredEmployeeUuids()
                .getOrDefault(pos, new CommercialClientData.HireInfo(null, "", ""))
                .getBuildingFileName();
        return (buildingFileName == null || buildingFileName.isEmpty())
                ? readCommercialBuildingFileNameFromClient(pos)
                : buildingFileName;
    }

    @OnlyIn(Dist.CLIENT)
    private static String resolveIndustrialBuildingFileName(BlockPos pos) {
        if (pos == null) {
            return "";
        }
        String buildingFileName = IndustrialClientData.getAllHiredEmployeeUuids()
                .getOrDefault(pos, new IndustrialClientData.HireInfo(null, "", ""))
                .getBuildingFileName();
        return (buildingFileName == null || buildingFileName.isEmpty())
                ? readIndustrialBuildingFileNameFromClient(pos)
                : buildingFileName;
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void openCommercialBuyScreen(CustomEntity npc, String buildingFileName) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Class<?> screenClass = Class.forName("com.xiaoliang.simukraft.client.gui.CommercialBuyScreen");
            
            // 获取NPC的工作位置
            BlockPos workPos = getNPCWorkPosition(npc);
            if (workPos == null) {
                workPos = npc.blockPosition();
            }
            
            Object screen = screenClass.getConstructor(BlockPos.class, String.class)
                .newInstance(workPos, buildingFileName);
            minecraft.setScreen((net.minecraft.client.gui.screens.Screen) screen);
        } catch (Exception e) {
            Simukraft.LOGGER.error("[NPCInteractionHandler] 打开购买界面失败: {}", e.getMessage());
            // 如果打开购买界面失败，回退到普通界面
            openScreenWithReflection(false, npc);
        }
    }
    
    @OnlyIn(Dist.CLIENT)
    private static BlockPos getNPCWorkPosition(CustomEntity npc) {
        UUID npcUuid = npc.getUUID();
        
        Map<BlockPos, CommercialClientData.HireInfo> allHires = CommercialClientData.getAllHiredEmployeeUuids();
        
        for (Map.Entry<BlockPos, CommercialClientData.HireInfo> entry : allHires.entrySet()) {
            if (entry.getValue().getNpcUuid().equals(npcUuid)) {
                return entry.getKey();
            }
        }
        
        return null;
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void openScreenWithReflection(boolean isBuildingMaterialStoreEmployee, CustomEntity npc) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            
            if (isBuildingMaterialStoreEmployee) {
                Class<?> screenClass = Class.forName("com.xiaoliang.simukraft.client.gui.BuildingMaterialStoreNPCScreen");
                Object screen = screenClass.getConstructor(CustomEntity.class).newInstance(npc);
                minecraft.setScreen((net.minecraft.client.gui.screens.Screen) screen);
            } else {
                minecraft.setScreen(new com.xiaoliang.simukraft.client.gui.NPCCardScreen(npc));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private record WorkUiContext(WorkBlockType workBlockType, BlockPos workplacePos, String buildingFileName) {
    }
}
