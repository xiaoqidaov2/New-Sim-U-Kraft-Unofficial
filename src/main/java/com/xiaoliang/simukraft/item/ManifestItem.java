package com.xiaoliang.simukraft.item;

import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.client.gui.ManifestScreen;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.world.BuildBoxHiredData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * 清单物品
 * 用于记录建筑盒建造任务所需材料，支持勾选追踪
 */
@SuppressWarnings("null")
public class ManifestItem extends Item {

    private static final String TAG_MATERIALS = "Materials";
    private static final String TAG_CHECKED = "Checked";
    private static final String TAG_BUILDING_NAME = "BuildingName";
    private static final String TAG_BUILD_BOX_POS = "BuildBoxPos";

    public ManifestItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public @Nonnull InteractionResultHolder<ItemStack> use(@Nonnull Level level, @Nonnull Player player, @Nonnull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        
        if (level.isClientSide) {
            openManifestScreen(stack);
        }
        
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public @Nonnull InteractionResult useOn(@Nonnull UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockState state = level.getBlockState(pos);

        if (player == null) {
            return InteractionResult.PASS;
        }

        // 潜行+右键点击建筑盒获取材料清单
        if (player.isShiftKeyDown() && isBuildBox(state)) {
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                fillManifestFromBuildBox(stack, serverLevel, pos);
                player.sendSystemMessage(Component.translatable("message.simukraft.manifest.filled"));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        // 普通右键打开界面
        if (level.isClientSide) {
            openManifestScreen(stack);
        }
        
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 检查是否为建筑盒方块
     */
    private boolean isBuildBox(@Nonnull BlockState state) {
        return state.is(com.xiaoliang.simukraft.init.ModBlocks.BUILD_BOX.get());
    }

    /**
     * 从建筑盒填充清单数据
     * menglannnn: 通过BuildBoxHiredData查找关联的NPC和建造任务
     */
    private void fillManifestFromBuildBox(@Nonnull ItemStack stack, @Nonnull ServerLevel level, @Nonnull BlockPos buildBoxPos) {
        CompoundTag tag = stack.getOrCreateTag();
        MinecraftServer server = level.getServer();
        
        // 获取建筑盒关联的NPC
        Map<BlockPos, UUID> hiredBuilders = BuildBoxHiredData.loadHiredBuilders(server);
        UUID npcUuid = hiredBuilders.get(buildBoxPos);
        
        if (npcUuid != null) {
            CustomEntity npc = BuildBoxHiredData.findNPCByUuid(server, npcUuid);
            if (npc != null) {
                ConstructionTask task = npc.getConstructionTask();
                
                if (task != null) {
                    // 保存建筑名称
                    tag.putString(TAG_BUILDING_NAME, task.getDisplayName());
                    tag.putLong(TAG_BUILD_BOX_POS, buildBoxPos.asLong());
                    
                    // 获取所需材料清单
                    Map<String, Integer> materials = task.getRequiredMaterials();
                    
                    // 保存材料列表
                    ListTag materialsList = new ListTag();
                    ListTag checkedList = new ListTag();
                    
                    for (Map.Entry<String, Integer> entry : materials.entrySet()) {
                        CompoundTag materialTag = new CompoundTag();
                        materialTag.putString("Item", entry.getKey());
                        materialTag.putInt("Count", entry.getValue());
                        materialsList.add(materialTag);
                        checkedList.add(StringTag.valueOf("false"));
                    }
                    
                    tag.put(TAG_MATERIALS, materialsList);
                    tag.put(TAG_CHECKED, checkedList);
                }
            }
        }
        
        stack.setTag(tag);
    }

    /**
     * 打开清单界面
     */
    private void openManifestScreen(@Nonnull ItemStack stack) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (net.minecraft.client.Minecraft.getInstance().level != null) {
                net.minecraft.client.Minecraft.getInstance().setScreen(
                    new ManifestScreen(stack)
                );
            }
        });
    }

    @Override
    public void appendHoverText(@Nonnull ItemStack stack, @Nullable Level level, @Nonnull List<Component> tooltipComponents, @Nonnull TooltipFlag isAdvanced) {
        super.appendHoverText(stack, level, tooltipComponents, isAdvanced);
        
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_BUILDING_NAME)) {
            String buildingName = tag.getString(TAG_BUILDING_NAME);
            tooltipComponents.add(Component.translatable("tooltip.simukraft.manifest.building", buildingName));
            
            int total = getTotalMaterials(stack);
            int checked = getCheckedCount(stack);
            int percentage = total > 0 ? (checked * 100 / total) : 0;
            tooltipComponents.add(Component.translatable("tooltip.simukraft.manifest.progress", checked, total, percentage));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.simukraft.manifest.empty"));
        }
    }

    // ========== 数据访问方法 ==========

    /**
     * 获取材料列表
     */
    @Nonnull
    public static List<MaterialEntry> getMaterials(@Nonnull ItemStack stack) {
        List<MaterialEntry> result = new ArrayList<>();
        CompoundTag tag = stack.getTag();
        
        if (tag == null || !tag.contains(TAG_MATERIALS)) {
            return result;
        }
        
        ListTag materialsList = tag.getList(TAG_MATERIALS, Tag.TAG_COMPOUND);
        ListTag checkedList = tag.getList(TAG_CHECKED, Tag.TAG_STRING);
        
        for (int i = 0; i < materialsList.size(); i++) {
            CompoundTag materialTag = materialsList.getCompound(i);
            String itemId = materialTag.getString("Item");
            int count = materialTag.getInt("Count");
            boolean checked = i < checkedList.size() && Boolean.parseBoolean(checkedList.getString(i));
            
            result.add(new MaterialEntry(itemId, count, checked, i));
        }
        
        return result;
    }

    /**
     * 设置勾选状态
     */
    public static void setChecked(@Nonnull ItemStack stack, int index, boolean checked) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_CHECKED)) {
            return;
        }
        
        ListTag checkedList = tag.getList(TAG_CHECKED, Tag.TAG_STRING);
        if (index >= 0 && index < checkedList.size()) {
            checkedList.set(index, StringTag.valueOf(String.valueOf(checked)));
            tag.put(TAG_CHECKED, checkedList);
            stack.setTag(tag);
        }
    }

    /**
     * 获取建筑名称
     */
    @Nonnull
    public static String getBuildingName(@Nonnull ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_BUILDING_NAME)) {
            return tag.getString(TAG_BUILDING_NAME);
        }
        return "";
    }

    /**
     * 获取总材料数
     */
    public static int getTotalMaterials(@Nonnull ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_MATERIALS)) {
            return tag.getList(TAG_MATERIALS, Tag.TAG_COMPOUND).size();
        }
        return 0;
    }

    /**
     * 获取已勾选数量
     */
    public static int getCheckedCount(@Nonnull ItemStack stack) {
        int count = 0;
        CompoundTag tag = stack.getTag();
        
        if (tag != null && tag.contains(TAG_CHECKED)) {
            ListTag checkedList = tag.getList(TAG_CHECKED, Tag.TAG_STRING);
            for (int i = 0; i < checkedList.size(); i++) {
                if (Boolean.parseBoolean(checkedList.getString(i))) {
                    count++;
                }
            }
        }
        
        return count;
    }

    /**
     * 材料条目数据类
     */
    public record MaterialEntry(@Nonnull String itemId, int count, boolean checked, int index) {
    }
}
