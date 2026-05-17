package com.xiaoliang.simukraft.item;

import com.xiaoliang.simukraft.building.CommercialBuildingConfig;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.building.ConstructionTask;
import com.xiaoliang.simukraft.building.ControlBoxDataManager;
import com.xiaoliang.simukraft.building.IndustrialBuildingConfig;
import com.xiaoliang.simukraft.building.IndustrialBuildingManager;
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
    private static final String TAG_SOURCE_TYPE = "SourceType";
    private static final String TAG_RECIPE_NAME = "RecipeName";
    private static final String TAG_RECIPE_ID = "RecipeId";
    private static final String TAG_PRODUCT_GROUPS = "ProductGroups";
    private static final String TAG_PRODUCT_ITEM = "ProductItem";

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

        if (player.isShiftKeyDown() && isCommercialControlBox(state)) {
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                fillManifestFromCommercialControlBox(stack, serverLevel, pos);
                player.sendSystemMessage(Component.translatable("message.simukraft.manifest.filled"));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        if (player.isShiftKeyDown() && isIndustrialControlBox(state)) {
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                fillManifestFromIndustrialControlBox(stack, serverLevel, pos);
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

    private boolean isCommercialControlBox(@Nonnull BlockState state) {
        return state.is(com.xiaoliang.simukraft.init.ModBlocks.COMMERCIAL_CONTROL_BOX.get());
    }

    private boolean isIndustrialControlBox(@Nonnull BlockState state) {
        return state.is(com.xiaoliang.simukraft.init.ModBlocks.INDUSTRIAL_CONTROL_BOX.get());
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
                    tag.putString(TAG_BUILDING_NAME, task.getDisplayName());
                    tag.putLong(TAG_BUILD_BOX_POS, buildBoxPos.asLong());
                    tag.putString(TAG_SOURCE_TYPE, "build");
                    Map<String, Integer> materials = task.getRequiredMaterials();
                    writeMaterials(tag, materials);
                }
            }
        }
        
        stack.setTag(tag);
    }

    private void fillManifestFromCommercialControlBox(@Nonnull ItemStack stack, @Nonnull ServerLevel level, @Nonnull BlockPos controlBoxPos) {
        ControlBoxDataManager.ControlBoxData boxData = ControlBoxDataManager.readControlBox(level.getServer(), controlBoxPos, "commercial");
        if (boxData == null || boxData.buildingFileName == null || boxData.buildingFileName.isBlank()) {
            return;
        }

        CommercialBuildingConfig config = CommercialBuildingManager.getConfig(boxData.buildingFileName);
        if (config == null) {
            return;
        }

        List<ProductGroupEntry> productGroups = new ArrayList<>();
        Map<String, Integer> globalMaterials = new LinkedHashMap<>();
        for (CommercialBuildingConfig.MaterialRequirement material : config.getMaterials()) {
            addMaterial(globalMaterials, material.getItemId(), material.getCount());
        }
        for (CommercialBuildingConfig.TradeItem trade : config.getTrades()) {
            Map<String, Integer> materials = new LinkedHashMap<>();
            for (CommercialBuildingConfig.MaterialRequirement material : trade.getRequiredMaterials()) {
                addMaterial(materials, material.getItemId(), material.getCount());
            }
            if (!materials.isEmpty()) {
                productGroups.add(new ProductGroupEntry(trade.getItemId(), materials));
            }
        }

        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_BUILDING_NAME, getDisplayName(config.getBuildingName(), boxData.buildingFileName));
        tag.putLong(TAG_BUILD_BOX_POS, controlBoxPos.asLong());
        tag.putString(TAG_SOURCE_TYPE, "commercial");
        writeProductGroups(tag, productGroups, globalMaterials);
        stack.setTag(tag);
    }

    private void fillManifestFromIndustrialControlBox(@Nonnull ItemStack stack, @Nonnull ServerLevel level, @Nonnull BlockPos controlBoxPos) {
        ControlBoxDataManager.ControlBoxData boxData = ControlBoxDataManager.readControlBox(level.getServer(), controlBoxPos, "industrial");
        if (boxData == null || boxData.buildingFileName == null || boxData.buildingFileName.isBlank()) {
            return;
        }

        IndustrialBuildingConfig config = IndustrialBuildingManager.getConfig(boxData.buildingFileName);
        if (config == null) {
            return;
        }

        List<ProductGroupEntry> recipeGroups = new ArrayList<>();
        if (config.isMultiRecipe() && config.getRecipes() != null && !config.getRecipes().isEmpty()) {
            for (var recipe : config.getRecipes()) {
                Map<String, Integer> recipeMaterials = new LinkedHashMap<>();
                for (IndustrialBuildingConfig.MaterialRequirement material : recipe.getMaterials()) {
                    addMaterial(recipeMaterials, material.getItemId(), material.getCount());
                }
                String recipeName = getDisplayName(recipe.getRecipeName(), recipe.getRecipeId());
                recipeGroups.add(new ProductGroupEntry("$" + recipeName, recipeMaterials));
            }
        } else {
            Map<String, Integer> materials = new LinkedHashMap<>();
            for (IndustrialBuildingConfig.MaterialRequirement material : config.getEffectiveMaterials(null)) {
                addMaterial(materials, material.getItemId(), material.getCount());
            }
            recipeGroups.add(new ProductGroupEntry("#gui.manifest.default_recipe", materials));
        }

        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_BUILDING_NAME, getDisplayName(config.getBuildingName(), boxData.buildingFileName));
        tag.putLong(TAG_BUILD_BOX_POS, controlBoxPos.asLong());
        tag.putString(TAG_SOURCE_TYPE, "industrial");
        tag.remove(TAG_RECIPE_ID);
        tag.remove(TAG_RECIPE_NAME);
        writeProductGroups(tag, recipeGroups, Collections.emptyMap());
        stack.setTag(tag);
    }

    private static void writeMaterials(@Nonnull CompoundTag tag, @Nonnull Map<String, Integer> materials) {
        ListTag materialsList = new ListTag();
        ListTag checkedList = new ListTag();

        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() <= 0) {
                continue;
            }
            CompoundTag materialTag = new CompoundTag();
            materialTag.putString("Item", entry.getKey());
            materialTag.putInt("Count", entry.getValue());
            materialsList.add(materialTag);
            checkedList.add(StringTag.valueOf("false"));
        }

        tag.put(TAG_MATERIALS, materialsList);
        tag.put(TAG_CHECKED, checkedList);
        tag.remove(TAG_PRODUCT_GROUPS);
    }

    private static void writeProductGroups(@Nonnull CompoundTag tag, @Nonnull List<ProductGroupEntry> productGroups, @Nonnull Map<String, Integer> globalMaterials) {
        ListTag groupsList = new ListTag();
        ListTag flatMaterialsList = new ListTag();
        ListTag checkedList = new ListTag();
        int index = 0;

        for (ProductGroupEntry group : productGroups) {
            if (group.productItemId().isBlank() || group.materials().isEmpty()) {
                continue;
            }

            CompoundTag groupTag = new CompoundTag();
            groupTag.putString(TAG_PRODUCT_ITEM, group.productItemId());
            ListTag groupMaterialsList = new ListTag();

            for (Map.Entry<String, Integer> entry : group.materials().entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() <= 0) {
                    continue;
                }

                CompoundTag materialTag = new CompoundTag();
                materialTag.putString("Item", entry.getKey());
                materialTag.putInt("Count", entry.getValue());
                materialTag.putInt("Index", index);
                groupMaterialsList.add(materialTag.copy());
                flatMaterialsList.add(materialTag);
                checkedList.add(StringTag.valueOf("false"));
                index++;
            }

            if (!groupMaterialsList.isEmpty()) {
                groupTag.put(TAG_MATERIALS, groupMaterialsList);
                groupsList.add(groupTag);
            }
        }

        for (Map.Entry<String, Integer> entry : globalMaterials.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() <= 0) {
                continue;
            }
            CompoundTag materialTag = new CompoundTag();
            materialTag.putString("Item", entry.getKey());
            materialTag.putInt("Count", entry.getValue());
            flatMaterialsList.add(materialTag);
            checkedList.add(StringTag.valueOf("false"));
        }

        tag.put(TAG_PRODUCT_GROUPS, groupsList);
        tag.put(TAG_MATERIALS, flatMaterialsList);
        tag.put(TAG_CHECKED, checkedList);
    }

    private static void addMaterial(@Nonnull Map<String, Integer> materials, @Nullable String itemId, int count) {
        if (itemId == null || itemId.isBlank() || count <= 0) {
            return;
        }
        materials.merge(itemId, count, Integer::sum);
    }

    @Nonnull
    private static String getDisplayName(@Nullable String displayName, @Nonnull String fallback) {
        return displayName != null && !displayName.isBlank() ? displayName : fallback;
    }

    /**
     * 打开清单界面
     */
    private void openManifestScreen(@Nonnull ItemStack stack) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            com.xiaoliang.simukraft.client.ManifestClientHooks.openManifestScreen(stack);
        });
    }

    @Override
    public void appendHoverText(@Nonnull ItemStack stack, @Nullable Level level, @Nonnull List<Component> tooltipComponents, @Nonnull TooltipFlag isAdvanced) {
        super.appendHoverText(stack, level, tooltipComponents, isAdvanced);
        
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_BUILDING_NAME)) {
            String buildingName = tag.getString(TAG_BUILDING_NAME);
            tooltipComponents.add(Component.translatable("tooltip.simukraft.manifest.building", buildingName));
            if (tag.contains(TAG_RECIPE_NAME)) {
                tooltipComponents.add(Component.translatable("tooltip.simukraft.manifest.recipe", tag.getString(TAG_RECIPE_NAME)));
            }
            
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

    @Nonnull
    public static List<ProductGroup> getProductGroups(@Nonnull ItemStack stack) {
        List<ProductGroup> result = new ArrayList<>();
        CompoundTag tag = stack.getTag();

        if (tag == null || !tag.contains(TAG_PRODUCT_GROUPS)) {
            return result;
        }

        ListTag groupsList = tag.getList(TAG_PRODUCT_GROUPS, Tag.TAG_COMPOUND);
        ListTag checkedList = tag.getList(TAG_CHECKED, Tag.TAG_STRING);

        for (int groupIndex = 0; groupIndex < groupsList.size(); groupIndex++) {
            CompoundTag groupTag = groupsList.getCompound(groupIndex);
            String productItemId = groupTag.getString(TAG_PRODUCT_ITEM);
            ListTag materialsList = groupTag.getList(TAG_MATERIALS, Tag.TAG_COMPOUND);
            List<MaterialEntry> groupMaterials = new ArrayList<>();

            for (int i = 0; i < materialsList.size(); i++) {
                CompoundTag materialTag = materialsList.getCompound(i);
                String itemId = materialTag.getString("Item");
                int count = materialTag.getInt("Count");
                int index = materialTag.getInt("Index");
                boolean checked = index >= 0 && index < checkedList.size() && Boolean.parseBoolean(checkedList.getString(index));
                groupMaterials.add(new MaterialEntry(itemId, count, checked, index));
            }

            if (!groupMaterials.isEmpty()) {
                result.add(new ProductGroup(productItemId, groupMaterials));
            }
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

    @Nonnull
    public static String getRecipeName(@Nonnull ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_RECIPE_NAME)) {
            return tag.getString(TAG_RECIPE_NAME);
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

    public record ProductGroup(@Nonnull String productItemId, @Nonnull List<MaterialEntry> materials) {
    }

    private record ProductGroupEntry(@Nonnull String productItemId, @Nonnull Map<String, Integer> materials) {
    }
}
