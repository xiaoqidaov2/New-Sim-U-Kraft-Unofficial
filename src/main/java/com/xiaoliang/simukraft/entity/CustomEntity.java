package com.xiaoliang.simukraft.entity;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.ai.HoldItemGoal;
import com.xiaoliang.simukraft.entity.ai.IdleNearbyStrollGoal;
import com.xiaoliang.simukraft.entity.ai.NPCBoundaryManager;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.init.ModBlocks;
import com.xiaoliang.simukraft.utils.NPCDataManager;
import com.xiaoliang.simukraft.utils.NameManager;
import com.xiaoliang.simukraft.utils.ResidentManager;
import com.xiaoliang.simukraft.utils.SkinManager;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.PopulationData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.TagKey;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Random;
import java.util.List;
import java.util.UUID;

@SuppressWarnings({"null", "deprecation"})
public class CustomEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> DATA_NAME = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_GENDER = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SKIN_PATH = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_WORK_STATUS = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_JOB = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_CITY_ID = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_WORK_SUB_STATE = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_STATUS_LABEL = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
    // 活跃任务状态同步字段（用于客户端挥手动画）
    private static final EntityDataAccessor<Boolean> DATA_HAS_ACTIVE_TASK = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.BOOLEAN);
    
    // 年龄与疾病系统数据同步字段
    private static final EntityDataAccessor<Integer> DATA_AGE = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_IS_SICK = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.BOOLEAN);
    // 寿命数据同步字段
    private static final EntityDataAccessor<Integer> DATA_LIFESPAN = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HUNGER = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_IS_HOMELESS = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_WORK_NEED_DETAIL = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);

    private WorkStatus workStatus = WorkStatus.IDLE;
    private WorkSubState workSubState = WorkSubState.NONE;
    private String fullName;
    protected boolean nameInitialized = false;
    private boolean dataRecorded = false;
    private int npcId = -1;
    private Gender gender;
    private String skinPath;
    private long lastHurtSoundTime;
    private BlockPos targetPos;
    private int teleportCountdown = -1;
    private boolean hireArrivalTeleportActive = false;
    private int hireArrivalRevealDelay = -1;
    private BlockPos hireArrivalEffectPos;
    private boolean isWorking = false;
    private String job = "unemployed";
    private UUID cityId;
    private int teleportParticleTimer = -1;
    private com.xiaoliang.simukraft.building.ConstructionTask constructionTask;
    private int constructionProgress = 0;
    private String currentBuildingName = "";
    private boolean isHomeless = false;
    private com.xiaoliang.simukraft.job.jobs.planner.PlannerWorkHandler plannerWorkHandler;
    
    // menglan: 建造进度累积器，用于小数方块放置
    private double buildProgressAccumulator = 0.0;
    
    // 年龄与疾病系统本地字段
    private int age = -1; // -1表示未初始化，将在首次获取时生成18-25岁随机值
    private boolean isSick = false;
    // 寿命本地字段（60-90岁随机）
    private int lifespan = -1; // -1表示未初始化
    // 防止重复死亡标记
    private boolean isDying = false;
    private int hunger = 20;
    private int statusLabelExpireTick = -1;
    @Nullable
    private String statusLabelExpireKey = null;
    private static final int VISIT_STATUS_RADIUS = 4;


    public CustomEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.moveControl = new CustomMoveControl(this);
        this.setMaxUpStep(1.0F);
        
        if (level instanceof ServerLevel serverLevel) {
            this.npcPathNavigator = new com.xiaoliang.simukraft.entity.ai.path.NPCPathNavigator(this, serverLevel);
        }
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_NAME, "");
        this.entityData.define(DATA_GENDER, "");
        this.entityData.define(DATA_SKIN_PATH, "");
        this.entityData.define(DATA_WORK_STATUS, WorkStatus.IDLE.getDisplayName());
        this.entityData.define(DATA_JOB, "unemployed");
        this.entityData.define(DATA_CITY_ID, "");
        this.entityData.define(DATA_WORK_SUB_STATE, WorkSubState.NONE.getDisplayName());
        this.entityData.define(DATA_STATUS_LABEL, "");
        // 活跃任务状态同步字段（用于客户端挥手动画）
        this.entityData.define(DATA_HAS_ACTIVE_TASK, false);
        // 年龄与疾病系统数据同步字段
        this.entityData.define(DATA_AGE, -1); // -1表示未初始化
        this.entityData.define(DATA_IS_SICK, false);
        // 寿命数据同步字段（默认75岁）
        this.entityData.define(DATA_LIFESPAN, 75);
        this.entityData.define(DATA_HUNGER, 20);
        this.entityData.define(DATA_IS_HOMELESS, false);
        this.entityData.define(DATA_WORK_NEED_DETAIL, "");
    }

    /**
     * 获取实体尺寸（睡觉时扩大碰撞箱便于玩家右键唤醒）
     */
    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        // 如果正在睡觉，扩大碰撞箱
        if (this.isSleeping()) {
            // 扩大碰撞箱：宽度从0.6扩大到1.2，高度从1.8保持1.8
            return net.minecraft.world.entity.EntityDimensions.scalable(0.6F, 1.8F);
        }
        return super.getDimensions(pose);
    }

    // simukraft: NPC边界管理器（合并原RestrictedAreaGoal、RestrictedGroundPathNavigation、RestrictedRandomStrollGoal）
    private NPCBoundaryManager boundaryManager;
    
    // simukraft: 新的自定义寻路系统（menglannnn: 完全独立于原版寻路）
    private com.xiaoliang.simukraft.entity.ai.path.NPCPathNavigator npcPathNavigator;

    public boolean isUsingCustomPathfinder() {
        return npcPathNavigator != null && npcPathNavigator.getTargetPos() != null;
    }

    public boolean canStartAutonomousGoal() {
        return !isUsingCustomPathfinder() && !isSleeping();
    }

    @Override
    protected void registerGoals() {
        // simukraft: 添加边界管理器，优先级设为最高（0），确保在休息时优先执行
        this.boundaryManager = new NPCBoundaryManager(this);
        this.goalSelector.addGoal(0, this.boundaryManager);

        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new com.xiaoliang.simukraft.entity.ai.BuyFoodGoal(this));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new IdleNearbyStrollGoal(this));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        this.goalSelector.addGoal(6, new HoldItemGoal(this));
    }

    /**
     * 获取边界管理器（用于NPCRestHandler设置休息边界）
     */
    public NPCBoundaryManager getBoundaryManager() {
        return this.boundaryManager;
    }
    
    /**
     * 获取新的自定义寻路导航器（menglannnn: 完全独立于原版寻路）
     */
    public com.xiaoliang.simukraft.entity.ai.path.NPCPathNavigator getNPCPathNavigator() {
        return this.npcPathNavigator;
    }
    
    /**
     * 使用新的寻路系统移动到指定位置
     * @param pos 目标位置
     * @return 是否成功开始寻路
     */
    public boolean moveToWithNewPathfinder(BlockPos pos) {
        if (npcPathNavigator != null) {
            if (ServerConfig.isDebugLogEnabled()) {
                Simukraft.LOGGER.info("[CustomEntity] NPC {} 调用新寻路，当前位置: {}，目标位置: {}，目的: 通用路径请求", this.getFullName(), this.blockPosition(), pos);
            }
            return npcPathNavigator.moveTo(pos, 0.35);
        }
        return false;
    }
    
    /**
     * 使用新的寻路系统移动到指定位置
     * @param x 目标X坐标
     * @param y 目标Y坐标
     * @param z 目标Z坐标
     * @return 是否成功开始寻路
     */
    public boolean moveToWithNewPathfinder(double x, double y, double z) {
        if (npcPathNavigator != null) {
            if (ServerConfig.isDebugLogEnabled()) {
                Simukraft.LOGGER.info("[CustomEntity] NPC {} 调用新寻路，当前位置: {}，目标位置: ({}, {}, {})，目的: 通用路径请求", this.getFullName(), this.blockPosition(), x, y, z);
            }
            return npcPathNavigator.moveTo(x, y, z, 0.35);
        }
        return false;
    }

    public boolean moveToWithNewPathfinder(BlockPos pos, double reachDistance) {
        if (npcPathNavigator != null) {
            if (ServerConfig.isDebugLogEnabled()) {
                Simukraft.LOGGER.info("[CustomEntity] NPC {} 调用新寻路，当前位置: {}，目标位置: {}，到达阈值: {}，目的: 通用路径请求", this.getFullName(), this.blockPosition(), pos, reachDistance);
            }
            return npcPathNavigator.moveTo(pos, reachDistance);
        }
        return false;
    }

    public boolean moveToWithNewPathfinder(double x, double y, double z, double reachDistance) {
        if (npcPathNavigator != null) {
            if (ServerConfig.isDebugLogEnabled()) {
                Simukraft.LOGGER.info("[CustomEntity] NPC {} 调用新寻路，当前位置: {}，目标位置: ({}, {}, {})，到达阈值: {}，目的: 通用路径请求", this.getFullName(), this.blockPosition(), x, y, z, reachDistance);
            }
            return npcPathNavigator.moveTo(x, y, z, reachDistance);
        }
        return false;
    }

    public void stopAllMovement() {
        stopNewPathfinder();
        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
    }
    
    /**
     * 停止新的寻路系统
     */
    public void stopNewPathfinder() {
        if (npcPathNavigator != null) {
            npcPathNavigator.stop();
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0D) // 设置NPC生命值为30
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.JUMP_STRENGTH, 0.5D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0D); // simukraft: 降低击退抗性，让NPC可以被正常击退
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public void actuallyHurt(DamageSource damageSource, float amount) {
        // 禁用窒息伤害，并尝试传送到周围安全位置
        if (damageSource.is(DamageTypes.IN_WALL)) {
            tryTeleportToSafePosition();
            return;
        }

        // simukraft: 睡觉时不受伤害影响，防止"躺着跑"
        if (this.isSleeping()) {
            return;
        }

        // simukraft: 调用父类方法处理伤害和击退，不再手动覆盖setDeltaMovement
        if (!isWorking) {
            super.actuallyHurt(damageSource, amount);
            // 不再立即恢复满血，NPC现在会正常受到伤害
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastHurtSoundTime > 500) {
            this.playHurtSound(damageSource);
            lastHurtSoundTime = currentTime;
        }

        // simukraft: NPC受到攻击后获得发光效果并通知市长和官员
        handleNPCAttacked(damageSource);
    }

    /**
     * 处理NPC被攻击事件
     * menglannnn: 给予发光效果并通知市长和官员
     */
    private void handleNPCAttacked(DamageSource damageSource) {
        if (this.level().isClientSide) return;

        // 给予发光效果（10秒）
        this.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.GLOWING, 200, 0));

        // 获取攻击者信息
        Entity attacker = damageSource.getEntity();
        String attackerName = attacker instanceof Player player
            ? player.getName().getString()
            : (attacker != null ? attacker.getName().getString() : "未知");

        // 获取城市信息
        UUID cityId = this.getCityId();
        if (cityId == null) return;

        MinecraftServer server = this.level().getServer();
        if (server == null) return;

        CityData cityData = CityData.get((ServerLevel) this.level());
        CityData.CityInfo cityInfo = cityData.getCity(cityId);
        if (cityInfo == null) return;

        // 发送消息给市长和官员
        Component message = Component.translatable("message.simukraft.npc.attacked",
            this.getFullName(), attackerName,
            (int) this.getX(), (int) this.getY(), (int) this.getZ());

        // 发送给市长
        sendMessageToMayor(server, message);

        // 发送给官员
        sendMessageToOfficials(server, cityInfo, message);
    }

    /**
     * 发送消息给官员
     * menglannnn: 遍历城市官员列表发送消息
     */
    private void sendMessageToOfficials(MinecraftServer server, CityData.CityInfo cityInfo, Component message) {
        for (String officialName : cityInfo.getOfficials()) {
            ServerPlayer officialPlayer = server.getPlayerList().getPlayerByName(officialName);
            if (officialPlayer != null) {
                officialPlayer.sendSystemMessage(message);
            }
        }
    }

    @Override
    public void knockback(double strength, double x, double z) {
        // simukraft: 睡觉时不能被击退，防止"躺着跑"
        if (this.isSleeping()) {
            return;
        }
        super.knockback(strength, x, z);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return this.getGender() == Gender.FEMALE
                ? ModSoundEvents.FEMALE_HURT.get()
                : ModSoundEvents.MALE_HURT.get();
    }

    @Override
    protected void playHurtSound(DamageSource source) {
        if (!this.level().isClientSide) {
            SoundEvent sound = this.getHurtSound(source);
            this.level().playSound(
                    null,
                    this.getX(),
                    this.getY(),
                    this.getZ(),
                    sound,
                    SoundSource.NEUTRAL,
                    this.getSoundVolume(),
                    this.getVoicePitch()
            );
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("fullName", fullName != null ? fullName : "");
        tag.putString("gender", gender != null ? gender.getName() : "");
        tag.putString("skinPath", skinPath != null ? skinPath : "");
        tag.putInt("npcId", npcId);
        tag.putBoolean("nameInitialized", nameInitialized);
        tag.putBoolean("dataRecorded", dataRecorded);
        tag.putString("workStatus", workStatus != null ? workStatus.getDisplayName() : WorkStatus.IDLE.getDisplayName());
        tag.putString("workSubState", workSubState != null ? workSubState.getDisplayName() : WorkSubState.NONE.getDisplayName());
        tag.putBoolean("isWorking", isWorking);
        tag.putString("job", job != null ? job : "unemployed");
        tag.putInt("teleportCountdown", teleportCountdown);
        tag.putInt("aiRestoreDelay", aiRestoreDelay);
        tag.putInt("teleportParticleTimer", teleportParticleTimer);
        tag.putInt("constructionProgress", constructionProgress);
        tag.putString("currentBuildingName", currentBuildingName != null ? currentBuildingName : "");
        if (cityId != null) {
            tag.putString("city_id", cityId.toString());
        }
        // 保存建造任务数据
        if (constructionTask != null) {
            CompoundTag taskTag = new CompoundTag();
            taskTag.putInt("startPosX", constructionTask.getStartPos().getX());
            taskTag.putInt("startPosY", constructionTask.getStartPos().getY());
            taskTag.putInt("startPosZ", constructionTask.getStartPos().getZ());
            // 修复：保存建筑盒位置，解决退出重进后找不到箱子的问题
            taskTag.putInt("buildBoxPosX", constructionTask.getBuildBoxPos().getX());
            taskTag.putInt("buildBoxPosY", constructionTask.getBuildBoxPos().getY());
            taskTag.putInt("buildBoxPosZ", constructionTask.getBuildBoxPos().getZ());
            taskTag.putString("facing", constructionTask.getFacing().getName());
            taskTag.putString("displayName", constructionTask.getBuildingName());
            taskTag.putString("internalName", constructionTask.getInternalBuildingName());
            taskTag.putString("category", constructionTask.getCategory());
            // 修复：使用DoubleTag保存成本，保留小数
            taskTag.putDouble("cost", constructionTask.getCost());
            taskTag.putInt("currentBlockIndex", constructionTask.getCurrentBlockIndex());
            tag.put("constructionTask", taskTag);
        }
        // 保存年龄与疾病数据
        tag.putInt("age", this.age);
        tag.putBoolean("isSick", this.isSick);
        tag.putInt("hunger", this.hunger);
        // 保存寿命数据（如果已初始化）
        if (this.lifespan > 0) {
            tag.putInt("lifespan", this.lifespan);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setPersistenceRequired();
        this.fullName = tag.getString("fullName");
        this.gender = Gender.fromString(tag.getString("gender"));
        this.skinPath = tag.getString("skinPath");
        this.npcId = tag.getInt("npcId");
        this.nameInitialized = tag.getBoolean("nameInitialized");
        this.dataRecorded = tag.getBoolean("dataRecorded");
        this.isWorking = tag.getBoolean("isWorking");
        this.job = tag.getString("job");
        this.teleportCountdown = tag.getInt("teleportCountdown");
        this.aiRestoreDelay = tag.getInt("aiRestoreDelay");
        this.teleportParticleTimer = tag.getInt("teleportParticleTimer");
        this.hireArrivalTeleportActive = false;
        this.hireArrivalRevealDelay = -1;
        this.hireArrivalEffectPos = null;
        this.setInvisible(false);
        this.hunger = tag.contains("hunger") ? tag.getInt("hunger") : 20;
        // 加载建造任务相关数据
        this.constructionProgress = tag.getInt("constructionProgress");
        this.currentBuildingName = tag.getString("currentBuildingName");

        if (tag.contains("city_id")) {
            this.cityId = UUID.fromString(tag.getString("city_id"));
        }

        if (!this.level().isClientSide) {
            this.entityData.set(DATA_NAME, fullName);
            this.entityData.set(DATA_GENDER, gender != null ? gender.getName() : "");
            this.entityData.set(DATA_SKIN_PATH, skinPath != null ? skinPath : "");
            this.workStatus = WorkStatus.fromString(tag.getString("workStatus"));
            this.entityData.set(DATA_WORK_STATUS, workStatus.getDisplayName());
            this.workSubState = WorkSubState.fromString(tag.getString("workSubState"));
            this.entityData.set(DATA_WORK_SUB_STATE, workSubState.getDisplayName());
            this.entityData.set(DATA_JOB, job);
            this.entityData.set(DATA_CITY_ID, cityId != null ? cityId.toString() : "");
            this.entityData.set(DATA_HUNGER, this.hunger);
            this.entityData.set(DATA_IS_HOMELESS, this.isHomeless);

            if ("builder".equals(job)) {
                this.setItemInHand(this.getUsedItemHand(), new ItemStack(Items.COBBLESTONE));
            }
        }
        // 加载建造任务数据 - 优先使用JSON数据，NBT数据仅作为备份
        // 实际的恢复逻辑在BuilderWorkService.restoreConstructionTaskIfNeeded中处理
        // 这里不再直接从NBT恢复，以避免与JSON数据冲突
        if (tag.contains("constructionTask")) {
            // 标记有NBT建造任务数据，但让JSON数据优先恢复
            // 如果JSON中没有数据，BuilderWorkService会尝试从NBT备份恢复
            Simukraft.LOGGER.debug("[CustomEntity] NPC {} 从NBT读取到建造任务数据，将优先使用JSON数据恢复",
                this.getUUID().toString().substring(0, 8));
        }
        
        // 加载年龄与疾病数据
        // 优先从NPCDataManager加载最新年龄数据
        if (!this.level().isClientSide) {
            try {
                this.age = NPCDataManager.getNPCAge(((ServerLevel) this.level()).getServer(), this.getUUID());
            } catch (Exception e) {
                this.age = tag.contains("age") ? tag.getInt("age") : 18;
            }
            this.entityData.set(DATA_AGE, this.age);
            
            try {
                this.isSick = NPCDataManager.isNPCSick(((ServerLevel) this.level()).getServer(), this.getUUID());
            } catch (Exception e) {
                this.isSick = tag.contains("isSick") ? tag.getBoolean("isSick") : false;
            }
            this.entityData.set(DATA_IS_SICK, this.isSick);
        } else {
            // 客户端从NBT加载
            this.age = tag.contains("age") ? tag.getInt("age") : 18;
            this.entityData.set(DATA_AGE, this.age);
            this.isSick = tag.contains("isSick") ? tag.getBoolean("isSick") : false;
            this.entityData.set(DATA_IS_SICK, this.isSick);
        }
        
        // 加载寿命数据
        // 优先从NPCDataManager加载最新寿命数据
        if (!this.level().isClientSide) {
            try {
                int savedLifespan = NPCDataManager.getNPCLifespan(((ServerLevel) this.level()).getServer(), this.getUUID());
                if (savedLifespan > 0) {
                    this.lifespan = savedLifespan;
                } else if (tag.contains("lifespan")) {
                    this.lifespan = tag.getInt("lifespan");
                } else {
                    this.lifespan = -1; // 标记为未初始化
                }
            } catch (Exception e) {
                this.lifespan = tag.contains("lifespan") ? tag.getInt("lifespan") : -1;
            }
        } else {
            // 客户端从NBT加载
            this.lifespan = tag.contains("lifespan") ? tag.getInt("lifespan") : -1;
        }
        if (this.lifespan > 0) {
            this.entityData.set(DATA_LIFESPAN, this.lifespan);
        }
    }

    @Override
    public void die(DamageSource source) {
        // 防止重复死亡处理 - 只使用isDying标记
        // 注意：不能检查isDeadOrDying()，因为父类可能在调用此方法前已经设置了死亡状态
        if (isDying) {
            return;
        }
        isDying = true;

        if (!this.level().isClientSide) {
            if (npcId != -1) {
                    // 获取城市信息用于发送死亡消息
                    UUID cityId = this.getCityId();
                    ServerLevel serverLevel = (ServerLevel) this.level();

                    // 判断死亡类型并发送相应消息（只发送一次）
                    CityData cityData = CityData.get(serverLevel);
                    CityData.CityInfo cityInfo = cityId != null ? cityData.getCity(cityId) : null;
                    String cityName = cityInfo != null ? cityInfo.getCityName() : "No City";
                    
                    Component deathMessage = null;
                    String deathType = "";

                    // Debug log
                    Simukraft.LOGGER.info("[CustomEntity] NPC death debug: source.entity={}, isSick={}, isAtEndOfLife={}",
                            source.getEntity() != null ? source.getEntity().getName().getString() : "null",
                            this.isSick,
                            isAtEndOfLife());

                    if (source.getEntity() != null) {
                        // Killed by entity
                        String killerName = source.getEntity().getName().getString();
                        deathMessage = Component.translatable("message.npc.killed",
                                this.getFullName(),
                                killerName);
                        deathType = "killed";
                        Simukraft.LOGGER.info("[CustomEntity] NPC killed by {}", killerName);
                    } else if (this.isSick) {
                        // Died of sickness
                        deathMessage = Component.translatable("message.npc.died_of_sickness",
                                this.getFullName(),
                                getNpcAge(),
                                cityName);
                        deathType = "sick";
                    } else if (isAtEndOfLife()) {
                        // Natural death (old age)
                        deathMessage = Component.translatable("message.npc.natural_death",
                                this.getFullName(),
                                getNpcAge(),
                                cityName);
                        deathType = "natural death";
                    }

                    // Send death message to city group
                    Simukraft.LOGGER.debug("[CustomEntity] Preparing death message: deathMessage={}, cityId={}", deathMessage, cityId);
                    if (deathMessage != null && cityId != null) {
                        Simukraft.LOGGER.debug("[CustomEntity] Sending death message to city group: {}", deathMessage.getString());
                        com.xiaoliang.simukraft.utils.CityMessageUtils.sendToCityGroup(
                                serverLevel.getServer(),
                                cityId,
                                deathMessage
                        );
                        Simukraft.LOGGER.info("[CustomEntity] NPC {} death ({}), message sent to city group",
                                this.getFullName(), deathType);
                    } else {
                        Simukraft.LOGGER.debug("[CustomEntity] Death message not sent: deathMessage={}, cityId={}", deathMessage, cityId);
                    }

                    // 清除NPC的住宅数据
                    com.xiaoliang.simukraft.utils.ResidentManager.clearResidenceOnNPCDeath(
                            serverLevel.getServer(),
                            this.getUUID(),
                            this.getFullName()
                    );

                    // 使用UUID删除NPC数据
                    NPCDataManager.removeNPCDataByUUID(
                            serverLevel.getServer(),
                            this.getUUID()
                    );

                    // 从所属城市中移除该市民
                    if (cityId != null) {
                        cityData.removeCitizenFromCity(cityId, this.getUUID());
                    }

                    // NPC死亡时自动解雇，释放工作方块
                    try {
                        com.xiaoliang.simukraft.employment.service.EmploymentService employmentService =
                            com.xiaoliang.simukraft.employment.service.EmploymentServices.get(serverLevel.getServer());
                        com.xiaoliang.simukraft.employment.service.EmploymentCommands.FireByNpcCommand fireCommand =
                            new com.xiaoliang.simukraft.employment.service.EmploymentCommands.FireByNpcCommand(this.getUUID());
                        employmentService.fireByNpc(fireCommand);
                    } catch (Exception e) {
                        Simukraft.LOGGER.error("[CustomEntity] NPC {} 死亡时解雇失败", this.getFullName(), e);
                    }

                    PopulationData populationData = PopulationData.get(serverLevel);
                    populationData.removePopulation();
                    populationData.syncToAllPlayers(serverLevel);
                }
        }
        
        // 先调用父类的die方法，确保实体正确死亡
        super.die(source);
        
        // 强制设置死亡状态，确保实体被移除
        if (!this.level().isClientSide) {
            this.setHealth(0);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void tick() {
        super.tick();

        if (!nameInitialized && !this.level().isClientSide) {
            initializeName();
        }

        if (this.level().isClientSide && !nameInitialized) {
            String name = this.entityData.get(DATA_NAME);
            String genderStr = this.entityData.get(DATA_GENDER);
            String skin = this.entityData.get(DATA_SKIN_PATH);
            String job = this.entityData.get(DATA_JOB);
            String cityIdStr = this.entityData.get(DATA_CITY_ID);

            if (!name.isEmpty() && !genderStr.isEmpty()) {
                this.fullName = name;
                this.gender = Gender.fromString(genderStr);
                this.skinPath = skin;
                this.job = job;
                this.nameInitialized = true;
                if (!cityIdStr.isEmpty()) {
                    this.cityId = UUID.fromString(cityIdStr);
                }
            }
        }

        if (!this.level().isClientSide) {
            String currentJob = getJob();
            boolean aliveAndActive = !this.isDeadOrDying() && !isDying;
            // 计算是否有活跃任务（用于同步到客户端）
            boolean hasActiveWork = false;

            // simukraft: 午休状态下没有活跃任务（仿造休息中的写法）
            if (getWorkSubState() != WorkSubState.LUNCH_BREAK) {
                // 建筑师：检查是否有未完成的建造任务
                if ("builder".equals(currentJob) && constructionTask != null
                        && !constructionTask.isCompleted() && constructionTask.hasNextBlock()) {
                    hasActiveWork = true;
                }

                // 规划师：检查是否有进行中的规划任务
                if ("planner".equals(currentJob)) {
                    if (getOrCreatePlannerWorkHandler().hasActiveTask()) {
                        hasActiveWork = true;
                    }
                }

                // 牧羊人和屠夫：保持原有行为（工作时挥手）
                if (("shepherd".equals(currentJob) || "butcher".equals(currentJob))
                        && getWorkStatus() == WorkStatus.WORKING) {
                    hasActiveWork = true;
                }
            }
            
            // 同步活跃任务状态到客户端
            if (this.entityData.get(DATA_HAS_ACTIVE_TASK) != hasActiveWork) {
                this.entityData.set(DATA_HAS_ACTIVE_TASK, hasActiveWork);
            }
            
            // simukraft: 躺在床上时不允许有任何动作
            if (this.isSleeping()) {
                this.stopNewPathfinder();
                this.getNavigation().stop();
                this.setDeltaMovement(Vec3.ZERO);
                this.setWorking(false);
                return;
            }

            // 触发挥手动画（服务器端）
            if (hasActiveWork && this.tickCount % 20 == 0) {
                this.swing(InteractionHand.MAIN_HAND);
            }
            
            // 处理NPC数据记录
            if (!dataRecorded && nameInitialized && fullName != null && !fullName.isEmpty()) {
                recordNPCData();
                dataRecorded = true;
            }

            // 处理居民分配（每5秒检查一次）
            // 修复：死亡NPC不应该再分配住宅
            if (aliveAndActive && nameInitialized && fullName != null && !fullName.isEmpty()
                    && this.tickCount % ASSIGNMENT_INTERVAL == 0
            ) {
                assignResidenceToNPC();

                // 更新流浪状态
                updateHomelessStatus();
            }

            if (aliveAndActive && this.tickCount % 1200 == 0) {
                if (getHunger() > 0) {
                    setHunger(getHunger() - 1);
                }
            }

            if (aliveAndActive && this.tickCount % 20 == 0) {
                tryPickupDroppedFood();
            }

            if (aliveAndActive && this.tickCount % 40 == 0) {
                refreshWorkNeedDetail();
            }

            if (statusLabelExpireTick > 0 && this.tickCount >= statusLabelExpireTick) {
                String current = this.entityData.get(DATA_STATUS_LABEL);
                if (statusLabelExpireKey != null && statusLabelExpireKey.equals(current)) {
                    setStatusLabel(null);
                }
                statusLabelExpireTick = -1;
                statusLabelExpireKey = null;
            }

            if (aliveAndActive && this.tickCount % 20 == 0) {
                String current = this.entityData.get(DATA_STATUS_LABEL);
                if (current == null) current = "";
                boolean canOverride = canUseDynamicStatusLabel(current);

                // simukraft: 休息和午休状态下不更新动态标签
                if (canOverride && getWorkSubState() != WorkSubState.RESTING
                        && getWorkSubState() != WorkSubState.LUNCH_BREAK) {
                    String desired = resolveDynamicStatusLabel(hasActiveWork);
                    if (!desired.equals(current)) {
                        setStatusLabel(desired.isEmpty() ? null : desired);
                    }
                }
            }

            // 处理传送倒计时
            if (teleportCountdown > 0) {
                if (hireArrivalTeleportActive && targetPos != null && this.tickCount % 2 == 0 && this.level() instanceof ServerLevel serverLevel) {
                    spawnHireArrivalParticles(serverLevel, targetPos, false);
                }
                teleportCountdown--;
                if (teleportCountdown <= 0) {
                    performTeleport();
                }
            }

            if (hireArrivalRevealDelay > 0 && hireArrivalEffectPos != null && this.level() instanceof ServerLevel serverLevel) {
                if (this.tickCount % 2 == 0) {
                    spawnHireArrivalParticles(serverLevel, hireArrivalEffectPos, false);
                }
                hireArrivalRevealDelay--;
                if (hireArrivalRevealDelay <= 0) {
                    this.setInvisible(false);
                    spawnHireArrivalParticles(serverLevel, hireArrivalEffectPos, true);
                    hireArrivalEffectPos = null;
                }
            }

            // 处理AI恢复延迟
            if (aiRestoreDelay > 0) {
                aiRestoreDelay--;
                if (aiRestoreDelay <= 0 && !this.isSleeping()) {
                    this.setNoAi(false);
                    // simukraft: 不再强制停止移动，避免与击退效果冲突导致一停一顿
                    // this.getNavigation().stop();
                    // this.setDeltaMovement(Vec3.ZERO);
                }
            }

        }

        // 处理传送粒子效果
        if (teleportParticleTimer > 0) {
            teleportParticleTimer--;
            spawnTeleportParticles();
        }

        // 处理门交互 - NPC可以自动开关门
        if (!this.level().isClientSide && !this.isSleeping() && this.tickCount % 5 == 0) {
            handleDoorInteraction();
        }
        
        // simukraft: 更新新的自定义寻路系统
        if (!this.level().isClientSide && !this.isSleeping() && npcPathNavigator != null) {
            npcPathNavigator.tick();
        }

        if (!this.level().isClientSide && this.tickCount % 10 == 0) {
            syncPathDebugToNearbyPlayers();
        }

        if (this.isSleeping()) {
            return;
        }

        // 处理建造任务
        if (!this.level().isClientSide && workStatus == WorkStatus.WORKING && constructionTask != null) {
            // simukraft: 午休状态暂停建造（仿造休息中的写法）
            if (getWorkSubState() == WorkSubState.LUNCH_BREAK) {
                return; // 午休期间不建造
            }

            if (!constructionTask.isCompleted() && constructionTask.hasNextBlock()) {
                if (this.level() instanceof ServerLevel serverLevel) {
                    // menglan: 使用新的速度系统 - 每tick放置小数个方块
                    int npcLevel = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCLevel(serverLevel.getServer(), this.getUUID());
                    double blocksPerTick = com.xiaoliang.simukraft.config.ServerConfig.getBuilderBlocksPerTickDouble(npcLevel);
                    
                    // 累积小数部分
                    buildProgressAccumulator += blocksPerTick;
                    int blocksToPlace = (int) buildProgressAccumulator;
                    buildProgressAccumulator -= blocksToPlace;
                    
                    int scannedCandidates = 0;
                    int placedBlocksCount = 0;
                    // menglan: 最大扫描次数，确保高速时也能找到足够方块
                    int maxScanPerTick = Math.max(20, blocksToPlace * 5);
                    
                    // menglan: 循环放置多个方块，直到达到计算出的数量或没有更多方块
                    while (placedBlocksCount < blocksToPlace && constructionTask.hasNextBlock() && scannedCandidates < maxScanPerTick) {
                        scannedCandidates++;
                        // 修复：传入serverLevel以检查方块是否已存在，避免重复消耗材料
                        com.xiaoliang.simukraft.building.ConstructionTask.BlockInfo blockInfo = constructionTask.getNextBlock(serverLevel);
                        if (blockInfo == null) break;

                        BlockPos targetPos = blockInfo.pos();
                        BlockState targetState = blockInfo.state();

                        // menglannnn: 处理空气方块（用于拆除/替换已有方块）
                        if (targetState.isAir()) {
                            // 目标为空气，移除该位置的方块
                            BlockState currentState = serverLevel.getBlockState(targetPos);
                            if (!currentState.isAir()) {
                                // 检查是否在黑名单中（menglannnn: 黑名单方块不应被拆除）
                                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(currentState.getBlock());
                                if (blockId != null && ServerConfig.isBlockBlacklistedForConstruction(blockId.toString())) {
                                    if (ServerConfig.shouldLogSkippedBlocks()) {
                                        Simukraft.LOGGER.info("[CustomEntity] Blacklisted block encountered during air placement, keeping original and skipping pos {}: {}", targetPos, blockId);
                                    }
                                    completeBuildStepAfterSkip();
                                    placedBlocksCount++;
                                    constructionProgress = constructionTask.getProgress();
                                    continue;
                                }
                                serverLevel.destroyBlock(targetPos, false);
                            }
                            completeBuildStepAfterSkip();
                            placedBlocksCount++;
                            constructionProgress = constructionTask.getProgress();
                            continue;
                        }

                        // 检查当前位置是否有阻挡方块（非目标方块的其他方块）
                        BlockState currentState = serverLevel.getBlockState(targetPos);
                        if (!currentState.isAir() && currentState.getBlock() != targetState.getBlock()) {
                            // 检查阻挡方块是否在黑名单中
                            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(currentState.getBlock());
                            if (blockId != null && ServerConfig.isBlockBlacklistedForConstruction(blockId.toString())) {
                                // 黑名单方块，保留阻挡方块，将此位置标记为已完成（不放置目标方块）
                                if (ServerConfig.shouldLogSkippedBlocks()) {
                                    Simukraft.LOGGER.info("[CustomEntity] Blacklisted block encountered during construction, keeping original and skipping pos {}: {}", targetPos, blockId);
                                }
                                completeBuildStepAfterSkip();
                                placedBlocksCount++;
                                constructionProgress = constructionTask.getProgress();
                                continue;
                            }
                            // 清除阻挡方块
                            serverLevel.destroyBlock(targetPos, false);
                        }

                        try {
                            // 放置目标方块
                            serverLevel.setBlock(targetPos, targetState, 3);
                        } catch (Exception e) {
                            completeBuildStepAfterSkip();
                            placedBlocksCount++;
                            constructionProgress = constructionTask.getProgress();
                            continue;
                        }

                        // 修复：如果是双格方块（门或床），同时放置另一半
                        if (isDoubleBlock(targetState)) {
                            BlockPos otherHalfPos = getOtherHalfPos(targetState, targetPos);
                            if (otherHalfPos != null) {
                                BlockState otherHalfState = getOtherHalfState(targetState);
                                if (otherHalfState != null) {
                                    // 清除阻挡方块
                                    BlockState otherCurrentState = serverLevel.getBlockState(otherHalfPos);
                                    if (!otherCurrentState.isAir() && otherCurrentState.getBlock() != otherHalfState.getBlock()) {
                                        serverLevel.destroyBlock(otherHalfPos, false);
                                    }
                                    // 放置另一半
                                    try {
                                        serverLevel.setBlock(otherHalfPos, otherHalfState, 3);
                                    } catch (Exception e) {
                                        //Simukraft.LOGGER.error("[CustomEntity] 放置双格方块另一半失败 at {}: {}", otherHalfPos, otherHalfState, e);
                                    }
                                }
                            }
                        }

                        placedBlocksCount++;
                        
                        // 添加熟练度经验值 - 每放置一个方块+1xp
                        addBuilderXpForBlock(serverLevel.getServer());

                        // 添加白烟粒子效果
                        for(int i = 0; i < 5; i++) {
                            serverLevel.sendParticles(
                                    ParticleTypes.CLOUD,
                                    targetPos.getX() + 0.5,
                                    targetPos.getY() + 0.1 + i * 0.2,
                                    targetPos.getZ() + 0.5,
                                    1,
                                    0, 0.1, 0,
                                    0.02
                            );
                        }

                        // 更新进度
                        constructionProgress = constructionTask.getProgress();

                        // 同步到客户端 - 每放置5个方块或进度变化超过10%时才同步
                        if (serverLevel.getServer() != null &&
                            (constructionProgress % 10 < 2 || constructionTask.getCurrentBlockIndex() % 5 == 0)) {
                            serverLevel.getServer().getPlayerList().getPlayers().forEach(player -> {
                                if (player.distanceTo(this) < 50) {
                                    com.xiaoliang.simukraft.network.NetworkManager.sendToPlayer(
                                            new com.xiaoliang.simukraft.network.ConstructionProgressPacket(
                                                    blockPosition(),
                                                    currentBuildingName,
                                                    constructionProgress
                                            ),
                                            player
                                    );
                                }
                            });
                        }

                        // 每放置10个方块保存一次建造任务进度（用于局域网开放模式下恢复）
                        if (serverLevel.getServer() != null && constructionTask.getCurrentBlockIndex() % 10 == 0) {
                            com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.saveConstructionTask(
                                serverLevel.getServer(), this
                            );
                        }
                    }
                }
            } else if (constructionTask.isCompleted()) {
                // 建造完成，自动解雇
                completeConstruction();
            }
        }

        // 处理规划师任务
        if (!this.level().isClientSide && "planner".equals(getJob())) {
            getOrCreatePlannerWorkHandler().tick(this.level());
        }

        // 寿命检查 - 每20秒检查一次（400 ticks）
        if (!this.level().isClientSide && this.tickCount % 400 == 0) {
            checkLifespanAndDie();
        }
    }

    private void completeBuildStepAfterSkip() {
        // menglan: 新系统不再需要重置冷却计时器
    }

    private com.xiaoliang.simukraft.job.jobs.planner.PlannerWorkHandler getOrCreatePlannerWorkHandler() {
        if (plannerWorkHandler == null) {
            plannerWorkHandler = new com.xiaoliang.simukraft.job.jobs.planner.PlannerWorkHandler(this);
        }
        return plannerWorkHandler;
    }

    /**
     * 添加建筑师经验值
     */
    private void addBuilderXpForBlock(MinecraftServer server) {
        if (server == null) return;

        // 检查是否启用经验获取
        if (!com.xiaoliang.simukraft.config.ServerConfig.isBuilderXpGainEnabled()) {
            return;
        }

        // 添加经验值
        int xpAmount = com.xiaoliang.simukraft.config.ServerConfig.getBuilderXpPerBlock();
        boolean leveledUp = com.xiaoliang.simukraft.utils.NPCDataManager.addXp(server, this.getUUID(), xpAmount);

        // 如果升级了，发送消息给市长
        if (leveledUp) {
            int newLevel = com.xiaoliang.simukraft.utils.NPCDataManager.getNPCLevel(server, this.getUUID());
            Component message = Component.translatable("message.simukraft.npc.level_up.block_placed", this.getFullName(), newLevel);
            sendMessageToMayor(server, message);
        }
    }

    /**
     * 发送消息给市长（通过通知接口）
     */
    private void sendMessageToMayor(MinecraftServer server, Component message) {
        if (server == null) return;

        java.util.UUID cityId = this.getCityId();
        if (cityId == null) return;

        com.xiaoliang.simukraft.utils.CityMessageUtils.sendToMayorViaService(server, cityId,
                net.minecraft.network.chat.Component.translatable("notify.title.npc"),
                message,
                com.xiaoliang.simukraft.notification.MessageCategory.CITIZEN);
    }

    // 居民分配间隔（游戏刻），每5秒检查一次
    private static final long ASSIGNMENT_INTERVAL = 100; // 5秒 = 100游戏刻

    /**
     * 为NPC自动分配住宅
     */
    private void assignResidenceToNPC() {
        if (this.level().getServer() == null) {
            return;
        }

        // 修复：死亡NPC不应该再分配住宅
        if (this.isDeadOrDying() || isDying) {
            return;
        }

        // 检查NPC是否已有住宅分配
        boolean hasResidence = ResidentManager.hasResidenceAssigned(this.level().getServer(), fullName);

        if (!hasResidence) {
            // 尝试分配住宅
            boolean assigned = ResidentManager.assignResidenceToNPC(this.level().getServer(), fullName);
            if (assigned) {
                Simukraft.LOGGER.debug("[CustomEntity] NPC {} has been assigned a residence", fullName);
            }
        }
    }
    /**
     * 更新流浪状态
     */
    private void updateHomelessStatus() {
        if (this.level().getServer() == null) {
            return;
        }

        // 检查NPC是否在流浪状态
        boolean homeless = ResidentManager.isNPCHomeless(this.level().getServer(), fullName);
        this.isHomeless = homeless;
        this.entityData.set(DATA_IS_HOMELESS, homeless);

        // 可以在这里添加流浪状态的特殊行为（比如显示特殊粒子效果等）
        if (homeless && this.tickCount % 200 == 0) { // log homeless status every 10 seconds
            Simukraft.LOGGER.debug("[CustomEntity] NPC {} is currently homeless", fullName);
        }
    }

    /**
     * 获取NPC是否在流浪状态
     */
    public boolean isHomeless() {
        return this.entityData.get(DATA_IS_HOMELESS);
    }


    /**
     * 记录NPC数据
     */
    private void recordNPCData() {
        if (this.level().getServer() == null) {
            return;
        }

        // 使用稳定的 NPC 序号（持久化在 NBT 中），避免使用每次重新加载会变的运行时 entity id —
        // 否则 npc.json 中的 id 与 saveJobData 写入的 "npc"+npcId 不一致，UI 上看到"编号变成奇异数字"。
        if (this.npcId == -1 && this.level() instanceof ServerLevel serverLevel) {
            this.npcId = NameManager.getNextNPCId(serverLevel);
        }
        String npcId = "npc" + this.npcId;
        String npcName = fullName;
        String skinName = skinPath != null ? skinPath : "default";
        String genderStr = gender != null ? gender.getName() : "male";

        // 修复：传入城市ID，解决视距外NPC无法显示在雇佣列表的问题
        // 同时记录年龄、健康状态和寿命
        // 使用getNpcAge()确保生成随机年龄（如果是新NPC）
        int npcAge = getNpcAge();
        int npcLifespan = getLifespan();
        NPCDataManager.recordNPCData(this.level().getServer(), npcId, npcName, skinName, genderStr, this.getUUID(), this.cityId, npcAge, this.isSick, npcLifespan);
        //Simukraft.LOGGER.debug("[CustomEntity] Recorded NPC data: id={}, name={}, skin={}, gender={}, uuid={}, cityId={}, age={}, sick={}, lifespan={}",
                //npcId, npcName, skinName, genderStr, this.getUUID(), this.cityId, npcAge, this.isSick, npcLifespan);
    }

    private void spawnTeleportParticles() {
        if (this.level().isClientSide) return;

        ServerLevel serverLevel = (ServerLevel) this.level();
        Vec3 pos = this.position();
        net.minecraft.util.RandomSource random = this.random;

        // 在1格方块范围内生成粒子
        for (int i = 0; i < 15; i++) {
            double x = pos.x + (random.nextDouble() - 0.5) * 0.8;
            // 降低粒子高度：从NPC头顶上方1.5格到3.5格的高度生成粒子
            double y = pos.y + 0.0 + random.nextDouble() * 2.5;
            double z = pos.z + (random.nextDouble() - 0.5) * 0.8;

            // 加快下落速度，让粒子下落更迅速
            double motionY = -0.5 - random.nextDouble() * 0.10;

            serverLevel.sendParticles(
                    ParticleTypes.PORTAL,
                    x, y, z,
                    1,
                    0, motionY, 0,
                    0.1
            );
        }
    }

    private int aiRestoreDelay = -1; // AI恢复延迟计数器

    private void performTeleport() {
        if (targetPos != null) {
            boolean hireArrivalTeleport = this.hireArrivalTeleportActive;
            // 完全停止所有动作和行为
            this.setNoAi(true); // 禁用AI
            this.getNavigation().stop(); // 停止导航
            this.setDeltaMovement(Vec3.ZERO); // 清零速度
            this.setYRot(0); // 重置旋转
            this.setYHeadRot(0);
            this.setYBodyRot(0);

            // 普通传送保留原粒子；首次雇佣到岗使用工程方块上的显现特效。
            this.teleportParticleTimer = hireArrivalTeleport ? -1 : 120;

            // 执行传送
            Vec3 target = new Vec3(
                    targetPos.getX() + 0.5,
                    targetPos.getY() + 1.0,
                    targetPos.getZ() + 0.5
            );
            this.teleportTo(target.x, target.y, target.z);

            // 保存目标位置用于事件触发
            BlockPos savedTargetPos = targetPos;

            this.teleportCountdown = -1;
            this.targetPos = null;

            if (hireArrivalTeleport) {
                this.hireArrivalTeleportActive = false;
                this.hireArrivalEffectPos = savedTargetPos;
                this.hireArrivalRevealDelay = 10;
            }

            if ("builder".equals(this.job)) {
                if (hasActiveConstructionTask()) {
                    this.aiRestoreDelay = -1;
                    this.setWorkStatus(WorkStatus.WORKING);
                } else {
                    enterBuilderStandbyMode();
                }
            } else {
                this.aiRestoreDelay = 20;
            }
            // 触发传送完成事件
            if (!this.level().isClientSide) {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new com.xiaoliang.simukraft.event.NPCTeleportCompleteEvent(this, savedTargetPos, this.level())
                );
            }
        }
    }


    public void initializeName() {
        if (this.level() instanceof ServerLevel serverLevel && !nameInitialized) {
            NameManager nameManager = NameManager.get(serverLevel);
            fullName = nameManager.generateUniqueName(this.getUUID(), new Random());
            this.setCustomName(Component.literal(fullName));
            this.setCustomNameVisible(true);
            nameInitialized = true;

            if (!dataRecorded) {
                npcId = NameManager.getNextNPCId(serverLevel);
                String npcIdStr = "npc" + npcId;

                // 使用SkinManager生成性别和皮肤，确保50%男50%女，60个皮肤都能使用
                gender = Gender.getRandom();
                skinPath = SkinManager.getRandomSkinPath(gender, new Random(), this.getUUID());

                // 新生成的NPC总是设置为空闲状态
                this.workStatus = WorkStatus.IDLE;
                this.workSubState = WorkSubState.NONE;
                this.job = "unemployed";

                this.entityData.set(DATA_NAME, fullName);
                this.entityData.set(DATA_GENDER, gender.getName());
                this.entityData.set(DATA_SKIN_PATH, skinPath);
                this.entityData.set(DATA_WORK_STATUS, workStatus.getDisplayName());
                this.entityData.set(DATA_WORK_SUB_STATE, workSubState.getDisplayName());
                this.entityData.set(DATA_JOB, job);
                this.entityData.set(DATA_CITY_ID, cityId != null ? cityId.toString() : "");

                // 修复：传入城市ID，解决视距外NPC无法显示在雇佣列表的问题
                // 同时记录年龄、健康状态和寿命
                // 使用getNpcAge()确保生成随机年龄（如果是新NPC）
                int npcAge = getNpcAge();
                int npcLifespan = getLifespan();
                NPCDataManager.recordNPCData(
                        serverLevel.getServer(),
                        npcIdStr,
                        fullName,
                        skinPath,
                        gender.getName(),
                        this.getUUID(),
                        this.cityId,
                        npcAge,
                        this.isSick,
                        npcLifespan
                );

                // 保存初始的空闲状态到文件
                NPCDataManager.saveJobData(
                        serverLevel.getServer(),
                        npcIdStr,
                        "idle",
                        "unemployed"
                );

                dataRecorded = true;
                this.setNoAi(false);

                PopulationData populationData = PopulationData.get(serverLevel);
                populationData.addPopulation();
                populationData.syncToAllPlayers(serverLevel);

                // 新NPC生成后，尝试为其分配空置的住宅
                if (this.cityId != null) {
                    Simukraft.LOGGER.info("[CustomEntity] New NPC {} joined city {}, attempting residence assignment", fullName, cityId);
                    assignResidenceToNewNPC(serverLevel.getServer());
                }
            }
        }
    }

    /**
     * 为新NPC尝试分配空置的住宅
     */
    private void assignResidenceToNewNPC(MinecraftServer server) {
        try {
            // 检查该城市是否有空置的住宅
            java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path residenceDir = worldDir.resolve("simukraft").resolve("residence");

            if (!java.nio.file.Files.exists(residenceDir)) {
                Simukraft.LOGGER.debug("[CustomEntity] No residence directory found, cannot assign residence");
                return;
            }

            // 查找所有sk文件
            java.util.List<java.nio.file.Path> skFiles = new java.util.ArrayList<>();
            java.nio.file.Files.list(residenceDir)
                    .filter(path -> path.toString().toLowerCase().endsWith(".sk"))
                    .forEach(skFiles::add);

            Simukraft.LOGGER.debug("[CustomEntity] New NPC {} attempting residence assignment, found {} residence files", fullName, skFiles.size());

            // 查找空置的住宅（resident字段不存在或为空，且cityid匹配）
            for (java.nio.file.Path skFile : skFiles) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(skFile, java.nio.charset.StandardCharsets.UTF_8);
                String resident = null;
                String fileCityId = null;
                boolean hasResidentField = false;
                boolean isAvailable = true; // 默认为可用，除非找到resident字段且有值

                for (String line : lines) {
                    String trimmedLine = line.trim();
                    // 检查resident字段（但不是resident_uuid）
                    if (trimmedLine.startsWith("resident:") && !trimmedLine.startsWith("resident_uuid:")) {
                        hasResidentField = true;
                        resident = trimmedLine.substring("resident:".length()).trim();
                        isAvailable = resident.isEmpty(); // 如果resident为空，则可用
                    } else if (trimmedLine.startsWith("cityid:")) {
                        fileCityId = trimmedLine.substring("cityid:".length()).trim();
                    }
                }

                // 如果没有resident字段，也认为是空置的
                if (!hasResidentField) {
                    isAvailable = true;
                }

                Simukraft.LOGGER.debug("[CustomEntity] Checking residence {}: cityId={}, hasResidentField={}, resident={}, isAvailable={}",
                        skFile.getFileName(), fileCityId, hasResidentField, resident, isAvailable);

                // 如果住宅空置且属于同一城市，分配给这个新NPC
                if (isAvailable && fileCityId != null && fileCityId.equals(this.cityId.toString())) {
                    // 从文件名提取位置
                    String fileName = skFile.getFileName().toString().replace(".sk", "");
                    String[] parts = fileName.split("_");
                    if (parts.length == 3) {
                        try {
                            int x = Integer.parseInt(parts[0]);
                            int y = Integer.parseInt(parts[1]);
                            int z = Integer.parseInt(parts[2]);
                            BlockPos pos = new BlockPos(x, y, z);

                            Simukraft.LOGGER.info("[CustomEntity] Found vacant residence at {}, assigning to new NPC {}", pos, fullName);

                            // 直接分配住宅给当前NPC（不通过assignResidenceToCityNPCs，因为新NPC可能还没被添加到CityData）
                            boolean assigned = ResidentManager.assignResidenceToNPCDirectly(server, pos, this.getUUID(), this.getFullName(), cityId);
                            if (assigned) {
                                Simukraft.LOGGER.info("[CustomEntity] Successfully assigned residence {} to new NPC {}", pos, fullName);
                            } else {
                                Simukraft.LOGGER.warn("[CustomEntity] Failed to assign residence: {}", pos);
                            }
                            return; // 只分配一个住宅
                        } catch (NumberFormatException e) {
                            Simukraft.LOGGER.error("[CustomEntity] Failed to parse residence position: {}", fileName);
                        }
                    }
                }
            }

            Simukraft.LOGGER.debug("[CustomEntity] No vacant residences available in city {} for new NPC {}", cityId, fullName);

        } catch (Exception e) {
            Simukraft.LOGGER.error("[CustomEntity] Error assigning residence to new NPC: {}", e.getMessage(), e);
        }
    }

    public void scheduleTeleport(BlockPos pos) {
        clearHireArrivalTeleportState();
        this.targetPos = pos;
        this.teleportCountdown = 60; // 3秒 (60 ticks)
        this.isWorking = true;
        this.teleportParticleTimer = 120; // 6秒粒子效果

        // 立即开始停止动作
        if (!this.level().isClientSide) {
            this.setNoAi(true); // 禁用AI
            this.getNavigation().stop(); // 停止导航
            this.setDeltaMovement(Vec3.ZERO); // 清零速度

            // 清除所有目标
            this.setTarget(null);
            this.setLastHurtByMob(null);
            this.setLastHurtMob(null);
        }
    }

    public void scheduleHireArrivalTeleport(BlockPos pos) {
        // 幂等保护：如果已经在朝同一个目标传送，不要重置 30 tick 倒计时 —
        // 否则像 JobRuntimeService.correctWorkplaceDrift 这样的兜底路径每 100 tick 调一次，
        // 会让 NPC 长时间保持隐身，造成"NPC 消失"假象。
        if (this.hireArrivalTeleportActive && pos != null && pos.equals(this.targetPos)) {
            return;
        }
        clearHireArrivalTeleportState();
        this.targetPos = pos;
        this.teleportCountdown = 30; // 先在工作方块上方播放一段紫色粒子，再让NPC出现
        this.isWorking = true;
        this.teleportParticleTimer = -1;
        this.hireArrivalTeleportActive = true;
        this.hireArrivalRevealDelay = -1;
        this.hireArrivalEffectPos = pos;

        if (!this.level().isClientSide) {
            this.setInvisible(true);
            this.setNoAi(true);
            this.getNavigation().stop();
            this.setDeltaMovement(Vec3.ZERO);
            this.setTarget(null);
            this.setLastHurtByMob(null);
            this.setLastHurtMob(null);
        }
    }

    public void setWorkStatus(WorkStatus status) {
        // 如果状态没有变化，跳过更新
        if (this.workStatus == status) {
            return;
        }

        this.workStatus = status;

        // 无论是否是服务器端，都更新实体数据，确保客户端同步
        this.entityData.set(DATA_WORK_STATUS, status.getDisplayName());

        if (!this.level().isClientSide) {
            // 只在服务器端执行的逻辑 - 使用新方法确保所有NPC都能保存状态
            NPCDataManager.saveJobData(this);

            // 更新isWorking状态和其他逻辑
            if (status != WorkStatus.IDLE) {
                this.isWorking = true;
                // 如果切换到工作状态，默认设置子状态为工作中
                if (this.workSubState == WorkSubState.NONE || this.workSubState == WorkSubState.RESTING) {
                    this.workSubState = WorkSubState.WORKING;
                    this.entityData.set(DATA_WORK_SUB_STATE, WorkSubState.WORKING.getDisplayName());
                }
                // 停止导航和移动，但不完全禁用AI
                this.getNavigation().stop();
                this.setDeltaMovement(Vec3.ZERO);
                this.setTarget(null);
            } else {
                this.isWorking = false;
                this.workSubState = WorkSubState.NONE;
                this.entityData.set(DATA_WORK_SUB_STATE, WorkSubState.NONE.getDisplayName());
                this.targetPos = null;
                this.teleportCountdown = -1;
                this.teleportParticleTimer = -1;
                // 恢复AI
                this.setNoAi(false);
                this.getNavigation().stop();
                this.setDeltaMovement(Vec3.ZERO);
            }
        }
    }

    public void setConstructionTask(com.xiaoliang.simukraft.building.ConstructionTask task) {
        com.xiaoliang.simukraft.building.ConstructionTask previousTask = this.constructionTask;
        if (previousTask != null && previousTask != task) {
            previousTask.detachBuilder();
        }
        this.constructionTask = task;
        if (task != null) {
            task.attachBuilder(this);
        }
        if (task != null && !task.isCompleted() && task.hasNextBlock()) {
            this.currentBuildingName = task.getBuildingName();
            this.constructionProgress = task.getProgress();
            if (this.level() instanceof ServerLevel serverLevel) {
                com.xiaoliang.simukraft.utils.BuildBoxFloatingEntityManager.ensureSpawned(
                        serverLevel,
                        task.getBuildBoxPos(),
                        null
                );
                // 立即持久化建造任务：避免"刚下任务建筑师就下班，第二天起床时 JSON 没有最新任务"
                // 这种竞态导致的"睡一觉建造任务全丢失"。
                com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.saveConstructionTask(serverLevel.getServer(), this);
            }
        } else {
            this.currentBuildingName = "";
            this.constructionProgress = 0;
            if (this.level() instanceof ServerLevel serverLevel && previousTask != null) {
                com.xiaoliang.simukraft.utils.BuildBoxFloatingEntityManager.remove(
                        serverLevel,
                        previousTask.getBuildBoxPos()
                );
            }
        }
    }

    public com.xiaoliang.simukraft.building.ConstructionTask getConstructionTask() {
        return constructionTask;
    }

    public int getConstructionProgress() {
        return constructionProgress;
    }

    public void setConstructionProgress(int progress) {
        this.constructionProgress = progress;
    }

    public String getCurrentBuildingName() {
        return currentBuildingName;
    }

    public void completeConstruction() {
        if (constructionTask != null) {
            com.xiaoliang.simukraft.building.ConstructionTask completedTask = constructionTask;
            String buildingName = completedTask.getBuildingName();
            String category = completedTask.getCategory();
            BlockPos buildBoxPos = completedTask.getBuildBoxPos();

            completedTask.markCompleted();

            // simukraft: 注册建筑整体结构（支持一键拆除和NPC识别）
            if (this.level() instanceof ServerLevel serverLevel) {
            // 别jb乱弄这块，住宅控制盒必须在整栋建筑完工后统一激活，否则会提前入住。
                // simukraft: 获取已放置的方块列表（包含旋转后的正确坐标）
                java.util.List<com.xiaoliang.simukraft.building.ConstructionTask.BlockInfo> placedBlocks = completedTask.getBlocksToPlace();

                for (BlockPos controlBoxPos : completedTask.getControlBoxPositions()) {
                    // 激活住宅控制盒
                    com.xiaoliang.simukraft.block.ResidentialControlBoxBlock.activatePendingResidence(serverLevel, controlBoxPos);

                    // 注册建筑结构（使用实际放置的方块列表，包含旋转信息）
                    com.xiaoliang.simukraft.building.PlacedBuildingManager.registerPlacedBuildingFromTask(
                        controlBoxPos,
                        buildingName,
                        category,
                        serverLevel.dimension().location().toString(),
                        placedBlocks
                    );
                }
            }

            setConstructionTask(null);

            // 添加建筑完成提示
            com.xiaoliang.simukraft.utils.ConstructionCompletionNotifier.notifyConstructionCompletion(this, buildingName);

            if (this.level() instanceof ServerLevel serverLevel) {
                com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.autoDismissCompletedBuilder(
                        serverLevel,
                        this,
                        buildBoxPos
                );
            }
        }
    }

    private boolean hasActiveConstructionTask() {
        return this.constructionTask != null
                && !this.constructionTask.isCompleted()
                && this.constructionTask.hasNextBlock();
    }

    private void enterBuilderStandbyMode() {
        this.aiRestoreDelay = 20;
        // 建造完成后保持工作中状态，等待玩家手动解雇
        this.setWorkStatus(WorkStatus.WORKING);
        this.setWorking(true);
        this.setNoAi(false);
        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
    }

    private void clearHireArrivalTeleportState() {
        this.hireArrivalTeleportActive = false;
        this.hireArrivalRevealDelay = -1;
        this.hireArrivalEffectPos = null;
        this.setInvisible(false);
    }

    private void spawnHireArrivalParticles(ServerLevel level, BlockPos effectPos, boolean burst) {
        double x = effectPos.getX() + 0.5D;
        double y = effectPos.getY() + 1.15D;
        double z = effectPos.getZ() + 0.5D;

        level.sendParticles(
                ParticleTypes.DRAGON_BREATH,
                x, y, z,
                burst ? 30 : 8,
                burst ? 0.45D : 0.18D,
                burst ? 0.55D : 0.25D,
                burst ? 0.45D : 0.18D,
                burst ? 0.05D : 0.01D
        );
        level.sendParticles(
                ParticleTypes.PORTAL,
                x, y + 0.15D, z,
                burst ? 18 : 4,
                burst ? 0.30D : 0.10D,
                burst ? 0.30D : 0.08D,
                burst ? 0.30D : 0.10D,
                burst ? 0.08D : 0.02D
        );
    }

    public void setJob(String job) {
        // 如果职业没有变化，跳过更新
        if (this.job != null && this.job.equals(job)) {
            return;
        }

        this.job = job;

        // 无论是否是服务器端，都更新实体数据，确保客户端同步
        this.entityData.set(DATA_JOB, job);

        if (!this.level().isClientSide) {
            // 只在服务器端执行的逻辑 - 使用新方法确保所有NPC都能保存状态
            NPCDataManager.saveJobData(this);

            // 更新物品持有状态 - 优先从JSON配置读取
            ItemStack heldItem = resolveHeldItemFromConfig(job);
            if (!heldItem.isEmpty()) {
                this.setItemInHand(this.getUsedItemHand(), heldItem);
            } else {
                // 后备：使用默认手持物品
                switch (job) {
                    case "builder" -> this.setItemInHand(this.getUsedItemHand(), new ItemStack(Items.COBBLESTONE));
                    case "planner" -> this.setItemInHand(this.getUsedItemHand(), new ItemStack(Items.IRON_SHOVEL));
                    case "shepherd" -> this.setItemInHand(this.getUsedItemHand(), new ItemStack(Items.SHEARS));
                    case "butcher" -> this.setItemInHand(this.getUsedItemHand(), new ItemStack(Items.GOLDEN_AXE));
                    case "farmer" -> this.setItemInHand(this.getUsedItemHand(), new ItemStack(Items.STONE_HOE));
                    default -> this.setItemInHand(this.getUsedItemHand(), ItemStack.EMPTY);
                }
            }
        }
    }

    public void resetToIdle() {
        clearHireArrivalTeleportState();
        this.workStatus = WorkStatus.IDLE;
        this.workSubState = WorkSubState.NONE;
        this.job = "unemployed";
        this.isWorking = false;
        this.targetPos = null;
        this.teleportCountdown = -1;
        this.teleportParticleTimer = -1;
        this.setItemInHand(this.getUsedItemHand(), ItemStack.EMPTY);

        // 清除建造相关状态
        if (this.constructionTask != null) {
            this.constructionTask.cancel();
            setConstructionTask(null);
        }
        this.currentBuildingName = "";
        this.constructionProgress = 0;
        // 移除持久化存储中的建造任务
        if (this.level() instanceof ServerLevel serverLevel) {
            com.xiaoliang.simukraft.job.jobs.builder.BuilderWorkService.INSTANCE.removeConstructionTask(
                serverLevel.getServer(), this.getUUID()
            );
        }

        // 完全停止所有动作
        this.setNoAi(false); // 恢复AI
        this.getNavigation().stop(); // 停止导航
        this.setDeltaMovement(Vec3.ZERO); // 清零速度
        this.setTarget(null); // 清除目标
        this.setLastHurtByMob(null);
        this.setLastHurtMob(null);

        // 更新同步数据，无论是否是服务器端
        this.entityData.set(DATA_WORK_STATUS, WorkStatus.IDLE.getDisplayName());
        this.entityData.set(DATA_WORK_SUB_STATE, WorkSubState.NONE.getDisplayName());
        this.entityData.set(DATA_JOB, "unemployed");

        // 只在服务器端保存数据到文件
        if (!this.level().isClientSide) {
            if (npcId != -1) {
                String npcIdStr = "npc" + npcId;
                NPCDataManager.saveJobData(
                        ((ServerLevel) this.level()).getServer(),
                        npcIdStr,
                        "idle",
                        "unemployed"
                );
            }
        }
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        // simukraft: 使用原版地面寻路，边界限制由NPCBoundaryManager处理
        return new net.minecraft.world.entity.ai.navigation.GroundPathNavigation(this, level);
    }

    private static class CustomMoveControl extends MoveControl {
        private final CustomEntity npc;

        public CustomMoveControl(CustomEntity npc) {
            super(npc);
            this.npc = npc;
        }

        @Override
        public void tick() {
            if (npc.isSleeping()) {
                npc.setSpeed(0.0F);
                npc.setZza(0.0F);
                npc.setXxa(0.0F);
                return;
            }
            super.tick();
        }
    }

    public String getFullName() {
        String name = this.entityData.get(DATA_NAME);
        return name.isEmpty() && fullName != null ? fullName : name;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(getFullNameOrFallback());
    }

    public Component getStatusDisplayComponent() {
        WorkSubState currentSubState = getWorkSubState();
        WorkStatus currentWorkStatus = getWorkStatus();
        String syncedStatusLabel = getStatusLabel();

        if (currentSubState == WorkSubState.RESTING) {
            // 休息状态下，不显示饥饿标签
            return Component.translatable("work_sub_state.resting");
        }

        // simukraft: 午休状态下显示"午休中"
        if (currentSubState == WorkSubState.LUNCH_BREAK) {
            return Component.translatable("gui.npc.status.lunch_break");
        }

        // 工作中状态优先显示，不受饥饿状态标签影响（但午休状态除外）
        if (currentWorkStatus == WorkStatus.WORKING && currentSubState != WorkSubState.LUNCH_BREAK) {
            return Component.translatable("work_status.working");
        }

        // 不显示饥饿状态标签，饥饿状态单独显示
        if (syncedStatusLabel != null && !syncedStatusLabel.isEmpty()
                && !"gui.npc.status.hungry".equals(syncedStatusLabel)) {
            return Component.translatable(syncedStatusLabel);
        }

        return Component.translatable(currentWorkStatus.getDisplayName());
    }

    private String getFullNameOrFallback() {
        String name = this.entityData.get(DATA_NAME);
        if (name.isEmpty() && fullName != null) {
            name = fullName;
        }
        return name.isEmpty() ? "NPC" : name;
    }

    public boolean isNameInitialized() {
        return nameInitialized;
    }

    public boolean isDataRecorded() {
        return dataRecorded;
    }

    public int getNpcId() {
        return npcId;
    }

    public Gender getGender() {
        String genderStr = this.entityData.get(DATA_GENDER);
        if (!genderStr.isEmpty()) {
            return Gender.fromString(genderStr);
        }
        return gender != null ? gender : Gender.MALE;
    }

    public String getSkinPath() {
        String path = this.entityData.get(DATA_SKIN_PATH);
        return path.isEmpty() && skinPath != null ? skinPath : path;
    }

    public WorkStatus getWorkStatus() {
        String statusStr = this.entityData.get(DATA_WORK_STATUS);
        return WorkStatus.fromString(statusStr);
    }

    public String getJob() {
        String job = this.entityData.get(DATA_JOB);
        return job.isEmpty() ? this.job : job;
    }

    public boolean isWorking() {
        return isWorking;
    }

    /**
     * 设置NPC的工作状态（用于休息系统控制移动）
     */
    public void setWorking(boolean working) {
        this.isWorking = working;
    }

    public void setWorkSubState(WorkSubState subState) {
        // 如果子状态没有变化，跳过更新
        if (this.workSubState == subState) {
            return;
        }
        this.workSubState = subState;
        this.entityData.set(DATA_WORK_SUB_STATE, subState.getDisplayName());
    }

    public WorkSubState getWorkSubState() {
        String subStateStr = this.entityData.get(DATA_WORK_SUB_STATE);
        return WorkSubState.fromString(subStateStr);
    }

    public void setStatusLabel(String label) {
        // 始终设置数据，让SynchedEntityData自动同步到客户端
        this.entityData.set(DATA_STATUS_LABEL, label != null ? label : "");
        if (label == null || label.isEmpty()) {
            statusLabelExpireTick = -1;
            statusLabelExpireKey = null;
        }
    }

    public String getStatusLabel() {
        return this.entityData.get(DATA_STATUS_LABEL);
    }

    private boolean canUseDynamicStatusLabel(String current) {
        return current.isEmpty()
                || "gui.npc.status.hungry".equals(current)
                || "gui.npc.status.working_task".equals(current)
                || "gui.npc.status.selling".equals(current)
                || "gui.npc.status.sowing".equals(current)
                || "gui.npc.status.visiting_farm".equals(current)
                || "gui.npc.status.visiting_shop".equals(current)
                || "gui.npc.status.visiting_factory".equals(current)
                || "gui.npc.status.lunch_break".equals(current);  // simukraft: 午休状态不应被动态标签覆盖
    }

    private String resolveDynamicStatusLabel(boolean hasActiveWork) {
        if (getHunger() <= 12) {
            return "gui.npc.status.hungry";
        }

        if (hasActiveWork) {
            if ("farmer".equals(getJob())) {
                return "gui.npc.status.sowing";
            }
            if (com.xiaoliang.simukraft.building.CommercialBuildingManager.isCommercialJobType(getJob())) {
                return "gui.npc.status.selling";
            }
            return "gui.npc.status.working_task";
        }

        if (getWorkStatus() == WorkStatus.IDLE && "unemployed".equals(getJob())) {
            return resolveNearbyVisitStatus();
        }

        return "";
    }

    private String resolveNearbyVisitStatus() {
        BlockPos origin = this.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = -1; y <= 1; y++) {
            for (int x = -VISIT_STATUS_RADIUS; x <= VISIT_STATUS_RADIUS; x++) {
                for (int z = -VISIT_STATUS_RADIUS; z <= VISIT_STATUS_RADIUS; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    Block block = this.level().getBlockState(cursor).getBlock();
                    if (block == ModBlocks.NSUK_FARMLAND_BOX.get()) {
                        return "gui.npc.status.visiting_farm";
                    }
                    if (block == ModBlocks.COMMERCIAL_CONTROL_BOX.get()) {
                        return "gui.npc.status.visiting_shop";
                    }
                    if (block == ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
                        return "gui.npc.status.visiting_factory";
                    }
                }
            }
        }

        return "";
    }

    public void setStatusLabelForTicks(String label, int ticks) {
        if (label == null || label.isEmpty() || ticks <= 0) {
            setStatusLabel(null);
            return;
        }
        setStatusLabel(label);
        statusLabelExpireKey = label;
        statusLabelExpireTick = this.tickCount + ticks;
    }

    public void setCityId(UUID cityId) {
        this.cityId = cityId;
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_CITY_ID, cityId != null ? cityId.toString() : "");
        }
    }

    /**
     * Synchronizes the NPC name from a network packet (client-side).
     * Replaces reflection-based field access in SyncNPCNamePacket.
     */
    public void syncNameFromPacket(String newName) {
        this.fullName = newName;
        this.entityData.set(DATA_NAME, newName);
        this.setCustomName(Component.literal(newName));
        this.setCustomNameVisible(true);
    }

    public UUID getCityId() {
        return this.cityId;
    }

    public String getCityIdString() {
        return this.entityData.get(DATA_CITY_ID);
    }

    public int getHunger() {
        return this.entityData.get(DATA_HUNGER);
    }

    public void setHunger(int hunger) {
        int clamped = Mth.clamp(hunger, 0, 20);
        this.hunger = clamped;
        this.entityData.set(DATA_HUNGER, clamped);
    }

    public void addHunger(int amount) {
        if (amount <= 0) return;
        setHunger(getHunger() + amount);
    }

    public String getWorkNeedDetail() {
        return this.entityData.get(DATA_WORK_NEED_DETAIL);
    }

    public void setWorkNeedDetail(String detail) {
        this.entityData.set(DATA_WORK_NEED_DETAIL, detail == null ? "" : detail);
    }

    public String getHungerLevelKey() {
        int hungerValue = getHunger();
        if (hungerValue >= 18) {
            return "gui.npc.hunger.level.full";
        }
        if (hungerValue >= 13) {
            return "gui.npc.hunger.level.bit_hungry";
        }
        if (hungerValue >= 7) {
            return "gui.npc.hunger.level.very_hungry";
        }
        return "gui.npc.hunger.level.starving";
    }

    @Override
    public float getAttackAnim(float f) {
        // 只在有活跃任务时播放挥手动画，休息时不挥动手部
        if (getWorkSubState() == WorkSubState.RESTING) {
            return 0.0f;
        }

        // simukraft: 午休时不挥动手部
        if (getWorkSubState() == WorkSubState.LUNCH_BREAK) {
            return 0.0f;
        }

        // 使用服务器同步的活跃任务状态（客户端无法直接访问constructionTask和plannerWorkHandler）
        // 注意：这里只返回姿势值，不触发挥手动作。挥手动作由服务器端的 swing() 方法触发
        if (this.entityData.get(DATA_HAS_ACTIVE_TASK)) {
            // 降低幅度，避免与 swing() 触发的挥手动画冲突，但保持原有速度
            return (Mth.sin((tickCount + f) / 2f) / 20) + 0.05f;
        }
        return 0.0f; // 其他情况下不显示攻击动画
    }

    /**
     * 检查是否是双格方块（床或门）
     */
    private boolean isDoubleBlock(BlockState state) {
        if (state.getBlock() instanceof BedBlock) {
            return true;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return true;
        }
        return false;
    }

    /**
     * 获取双格方块的另一半位置
     */
    private BlockPos getOtherHalfPos(BlockState state, BlockPos pos) {
        // 处理床
        if (state.getBlock() instanceof BedBlock) {
            if (state.hasProperty(BedBlock.PART)) {
                BedPart part = state.getValue(BedBlock.PART);
                if (state.hasProperty(BedBlock.FACING)) {
                    Direction facing = state.getValue(BedBlock.FACING);
                    if (part == BedPart.FOOT) {
                        return pos.relative(facing);
                    } else {
                        return pos.relative(facing.getOpposite());
                    }
                }
            }
        }

        // 处理门
        if (state.getBlock() instanceof DoorBlock) {
            if (state.hasProperty(DoorBlock.HALF)) {
                DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
                if (half == DoubleBlockHalf.LOWER) {
                    return pos.above();
                } else {
                    return pos.below();
                }
            }
        }

        return null;
    }

    /**
     * 获取双格方块的另一半状态
     */
    private BlockState getOtherHalfState(BlockState state) {
        // 处理床
        if (state.getBlock() instanceof BedBlock) {
            if (state.hasProperty(BedBlock.PART)) {
                BedPart part = state.getValue(BedBlock.PART);
                // 返回另一半的状态
                if (part == BedPart.FOOT) {
                    return state.setValue(BedBlock.PART, BedPart.HEAD);
                } else {
                    return state.setValue(BedBlock.PART, BedPart.FOOT);
                }
            }
        }

        // 处理门
        if (state.getBlock() instanceof DoorBlock) {
            if (state.hasProperty(DoorBlock.HALF)) {
                DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
                // 返回另一半的状态
                if (half == DoubleBlockHalf.LOWER) {
                    return state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
                } else {
                    return state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
                }
            }
        }

        return null;
    }

    // ==================== 门交互功能 ====================

    private int doorCooldown = 0;

    private void syncPathDebugToNearbyPlayers() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        java.util.List<net.minecraft.world.phys.Vec3> nodePositions = new java.util.ArrayList<>();
        java.util.List<String> nodeTypes = new java.util.ArrayList<>();
        int currentIndex = 0;
        boolean clear = true;
        boolean blocked = false;

        if (npcPathNavigator != null) {
            blocked = npcPathNavigator.isBlockedByObstacle();
            net.minecraft.world.phys.Vec3 debugTargetPos = npcPathNavigator.getDebugDisplayTargetPos();
            if (debugTargetPos != null) {
                nodePositions.add(debugTargetPos);
                nodeTypes.add("TARGET");
            }
            com.xiaoliang.simukraft.entity.ai.path.NPCPath currentPath = npcPathNavigator.getCurrentPath();
            if (currentPath != null && !currentPath.isEmpty()) {
                currentIndex = currentPath.getCurrentIndex();
                for (com.xiaoliang.simukraft.entity.ai.path.NPCPathNode node : currentPath.getNodes()) {
                    nodePositions.add(new net.minecraft.world.phys.Vec3(node.standX, node.standY, node.standZ));
                    nodeTypes.add(node.type.name());
                }
                clear = false;
            } else if (!nodePositions.isEmpty()) {
                clear = false;
            }
        }

        com.xiaoliang.simukraft.network.SyncNPCPathDebugPacket packet =
                new com.xiaoliang.simukraft.network.SyncNPCPathDebugPacket(this.getUUID(), currentIndex, nodePositions, nodeTypes, clear, blocked);

        if (ServerConfig.isDebugLogEnabled()) {
            Simukraft.LOGGER.info("[CustomEntity] 同步NPC路径调试数据: npc={}, clear={}, blocked={}, nodes={}", this.getFullName(), clear, blocked, nodePositions.size());
        }

        serverLevel.getServer().getPlayerList().getPlayers().forEach(player -> {
            if (player.level() == serverLevel
                    && player.distanceTo(this) < 128.0F
                    && serverLevel.getServer().getPlayerList().isOp(player.getGameProfile())) {
                com.xiaoliang.simukraft.network.NetworkManager.sendToPlayer(packet, player);
            }
        });
    }

    /**
     * 处理门交互 - NPC可以自动开门（不会自动关闭）
     */
    private void handleDoorInteraction() {
        if (doorCooldown > 0) {
            doorCooldown--;
            return;
        }

        // 检查NPC是否在移动
        if (this.getNavigation().isDone()) {
            return; // 不在移动，不处理门
        }

        // 获取NPC前方的位置
        BlockPos frontPos = this.blockPosition().relative(this.getDirection());
        BlockState frontState = this.level().getBlockState(frontPos);

        // 检查前方是否是门
        if (frontState.getBlock() instanceof DoorBlock) {
            // 检查门是否关闭
            if (!frontState.getValue(DoorBlock.OPEN)) {
                // 打开门
                openDoor(frontPos, frontState);
                doorCooldown = 20; // 1秒冷却时间
            }
        }
    }

    /**
     * 打开门
     */
    private void openDoor(BlockPos doorPos, BlockState doorState) {
        // 设置门为打开状态
        this.level().setBlock(doorPos, doorState.setValue(DoorBlock.OPEN, true), 10);

        // 同时打开门的另一半
        BlockPos otherHalfPos = getOtherHalfPos(doorState, doorPos);
        if (otherHalfPos != null) {
            BlockState otherHalfState = this.level().getBlockState(otherHalfPos);
            if (otherHalfState.getBlock() instanceof DoorBlock) {
                this.level().setBlock(otherHalfPos, otherHalfState.setValue(DoorBlock.OPEN, true), 10);
            }
        }

        // 播放开门声音
        this.level().playSound(null, doorPos, doorState.getBlock().getSoundType(doorState).getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);

        if (ServerConfig.isDebugLogEnabled()) {
            Simukraft.LOGGER.debug("[CustomEntity] NPC {} opened door at {}", this.getFullName(), doorPos);
        }
    }

    private void tryPickupDroppedFood() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (getHunger() >= 20) {
            return;
        }

        AABB searchBox = this.getBoundingBox().inflate(1.8D, 0.8D, 1.8D);
        List<ItemEntity> items = serverLevel.getEntitiesOfClass(ItemEntity.class, searchBox, Entity::isAlive);
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            FoodProperties food = getCommonFoodProperties(stack);
            if (food == null || food.getNutrition() <= 0) {
                continue;
            }

            addHunger(food.getNutrition());
            this.swing(InteractionHand.MAIN_HAND);
            serverLevel.playSound(null, this.blockPosition(), net.minecraft.sounds.SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.8f, 1.0f);

            stack.shrink(1);
            if (stack.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(stack);
            }
            break;
        }
    }

    @Nullable
    public FoodProperties getCommonFoodProperties(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        FoodProperties food = stack.getFoodProperties(this);
        if (food != null && food.getNutrition() > 0) {
            return food;
        }
        Item item = stack.getItem();
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
        if (itemId == null) {
            return null;
        }
        TagKey<Item> forgeFoods = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", "foods"));
        if (stack.is(forgeFoods)) {
            return item.getFoodProperties();
        }
        return null;
    }

    private void refreshWorkNeedDetail() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!"builder".equals(getJob()) || getConstructionTask() == null || getConstructionTask().isCompleted()) {
            setWorkNeedDetail("");
            return;
        }

        try {
            List<com.xiaoliang.simukraft.network.MaterialRequirementsResponsePacket.MaterialInfo> materials =
                    com.xiaoliang.simukraft.network.MaterialRequirementsRequestPacket.collectMaterialsForTask(getConstructionTask(), serverLevel);
            if (materials.isEmpty()) {
                setWorkNeedDetail("");
                return;
            }
            com.xiaoliang.simukraft.network.MaterialRequirementsResponsePacket.MaterialInfo top = materials.get(0);
            setWorkNeedDetail(top.blockId + "|" + top.count);
        } catch (Exception e) {
            setWorkNeedDetail("");
        }
    }

    // ==================== 年龄与疾病系统 ====================

    /**
     * 获取NPC当前年龄
     * @return NPC的年龄（岁），默认18岁
     */
    public int getNpcAge() {
        // 优先从entityData获取
        int dataAge = this.entityData.get(DATA_AGE);
        if (dataAge > 0) {
            return dataAge;
        }
        // 如果entityData无效且本地字段有值，返回本地字段值
        if (this.age > 0) {
            return this.age;
        }
        // 如果年龄未初始化，生成18-25岁的随机值
        if (this.age <= 0) {
            Random random = new Random();
            this.age = 18 + random.nextInt(8); // 18-25岁（包含18和25）
            this.entityData.set(DATA_AGE, this.age);
        }
        return this.age;
    }

    /**
     * 设置NPC年龄
     * @param age 要设置的年龄值
     */
    public void setNpcAge(int age) {
        this.age = Math.max(0, age);
        this.entityData.set(DATA_AGE, this.age);
    }

    /**
     * 检查NPC是否生病
     * @return true表示生病，false表示健康
     */
    public boolean isSick() {
        return this.entityData.get(DATA_IS_SICK);
    }

    /**
     * 设置NPC疾病状态
     * @param sick true设置为生病，false设置为健康
     */
    public void setSick(boolean sick) {
        this.isSick = sick;
        this.entityData.set(DATA_IS_SICK, sick);
    }

    /**
     * 根据年龄计算生病概率
     * 公式：概率 = 0.01 + (年龄 - 18) × 0.00452
     * @return 生病概率（0.0 - 1.0）
     */
    public float getSicknessProbability() {
        int age = getNpcAge();
        if (age <= 18) {
            return 0.01f; // 18岁时基础概率1%
        }
        // 每增加1岁，概率增加约0.452%
        float probability = 0.01f + (age - 18) * 0.00452f;
        // 限制最大概率为20%
        return Math.min(probability, 0.20f);
    }

    /**
     * 每日疾病检查
     * 如果NPC已经生病，返回false
     * 根据年龄计算生病概率，使用随机数决定是否生病
     * @return true表示NPC新生病了，false表示保持健康或已经生病
     */
    public boolean dailySicknessCheck() {
        // 如果已经生病，返回false
        if (this.isSick) {
            return false;
        }

        // 计算生病概率
        float probability = getSicknessProbability();
        
        // 使用随机数决定是否生病
        Random random = new Random();
        boolean getsSick = random.nextFloat() < probability;
        
        if (getsSick) {
            this.setSick(true);
            Simukraft.LOGGER.info("[CustomEntity] NPC {} became sick! Age: {}, probability: {}%", this.getFullName(), getNpcAge(), String.format("%.2f", probability * 100));
            return true;
        }
        
        return false;
    }

    // ==================== 寿命系统 ====================

    /**
     * 获取NPC寿命
     * 如果寿命未初始化，则生成60-90岁的随机值
     * @return NPC的寿命（岁）
     */
    public int getLifespan() {
        // 如果寿命未初始化，生成随机值
        if (this.lifespan <= 0) {
            this.lifespan = generateRandomLifespan();
            this.entityData.set(DATA_LIFESPAN, this.lifespan);
        }
        return this.lifespan;
    }

    /**
     * 设置NPC寿命
     * @param lifespan 要设置的寿命值
     */
    public void setLifespan(int lifespan) {
        this.lifespan = Math.max(1, lifespan);
        this.entityData.set(DATA_LIFESPAN, this.lifespan);
    }

    /**
     * 生成随机寿命（60-90岁）
     * @return 随机寿命值
     */
    private int generateRandomLifespan() {
        Random random = new Random();
        return 60 + random.nextInt(31); // 60-90岁（包含60和90）
    }

    /**
     * 检查NPC是否已达到寿命上限
     * @return true表示NPC已达到寿命上限，false表示未达上限
     */
    public boolean isAtEndOfLife() {
        return getNpcAge() >= getLifespan();
    }

    /**
     * 获取NPC剩余寿命
     * @return 剩余寿命年数，如果已超出寿命则返回负数
     */
    public int getRemainingLifespan() {
        return getLifespan() - getNpcAge();
    }

    /**
     * 检查寿命并处理自然死亡
     * 如果NPC已达到寿命上限，则触发死亡
     */
    private void checkLifespanAndDie() {
        // 防止重复死亡
        if (isDying || !this.isAlive()) {
            return;
        }

        if (isAtEndOfLife()) {
            // 获取城市名称用于日志
            String cityName = "No City";
            UUID cityId = this.getCityId();

            if (cityId != null) {
                CityData cityData = CityData.get((ServerLevel) this.level());
                CityData.CityInfo cityInfo = cityData.getCity(cityId);
                if (cityInfo != null) {
                    cityName = cityInfo.getCityName();
                }
            }

            // 记录日志
            Simukraft.LOGGER.info("[CustomEntity] NPC {} died of old age at {}, city: {}",
                    this.getFullName(), getNpcAge(), cityName);

            // 触发自然死亡（die方法会处理消息发送）
            this.die(this.level().damageSources().generic());
        }
    }

    /**
     * 尝试传送到周围的安全位置（当NPC被墙卡住时调用）
     * 会搜索周围5x5x5范围内的完整方块表面
     */
    private void tryTeleportToSafePosition() {
        if (this.level().isClientSide) return;

        ServerLevel serverLevel = (ServerLevel) this.level();
        BlockPos currentPos = this.blockPosition();

        // 搜索周围5x5x5范围内的位置
        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos checkPos = currentPos.offset(x, y, z);

                    // 检查该位置是否是安全的（上方两格空气，下方是完整方块）
                    if (isSafeTeleportPosition(serverLevel, checkPos)) {
                        // 传送到该位置（中心）
                        double targetX = checkPos.getX() + 0.5;
                        double targetY = checkPos.getY();
                        double targetZ = checkPos.getZ() + 0.5;

                        this.teleportTo(targetX, targetY, targetZ);
                        Simukraft.LOGGER.debug("[CustomEntity] NPC {} was stuck, teleported to safe position: {}",
                                this.getFullName(), checkPos);
                        return;
                    }
                }
            }
        }

        // 如果没有找到安全位置，尝试传送到当前位置上方
        BlockPos upPos = currentPos.above(3);
        this.teleportTo(upPos.getX() + 0.5, upPos.getY(), upPos.getZ() + 0.5);
        Simukraft.LOGGER.warn("[CustomEntity] NPC {} was stuck, no safe position found, teleported above: {}",
                this.getFullName(), upPos);
    }

    /**
     * 检查指定位置是否是安全的传送位置
     * 要求：该位置和上方一格是空气，下方是完整方块
     * @param level 服务器世界
     * @param pos 要检查的位置
     * @return true表示安全，false表示不安全
     */
    private boolean isSafeTeleportPosition(ServerLevel level, BlockPos pos) {
        // 检查该位置是否是空气
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        // 检查上方一格是否是空气（确保NPC有站立空间）
        if (!level.getBlockState(pos.above()).isAir()) {
            return false;
        }

        // 检查下方是否是完整方块（有立足点）
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        return belowState.isSolid() && !belowState.isAir();
    }

    /**
     * 从JSON配置解析手持物品
     */
    private ItemStack resolveHeldItemFromConfig(String jobType) {
        if (jobType == null || jobType.isBlank()) {
            return ItemStack.EMPTY;
        }

        // 从商业建筑配置查找
        var commercialConfigs = com.xiaoliang.simukraft.building.CommercialBuildingManager.getConfigsByJobType(jobType);
        if (!commercialConfigs.isEmpty()) {
            String heldItemId = commercialConfigs.get(0).getHeldItem();
            if (heldItemId != null && !heldItemId.isBlank()) {
                try {
                    ResourceLocation itemId = parseHeldItemId(heldItemId);
                    var item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(itemId);
                    if (item != null && item != Items.AIR) {
                        return new ItemStack(item);
                    }
                } catch (Exception e) {
                    Simukraft.LOGGER.warn("[CustomEntity] 无法解析手持物品: {}", heldItemId);
                }
            }
        }

        // 从工业建筑配置查找
        var industrialConfigs = com.xiaoliang.simukraft.building.IndustrialBuildingManager.getConfigsByJobType(jobType);
        if (!industrialConfigs.isEmpty()) {
            String heldItemId = industrialConfigs.get(0).getHeldItem();
            if (heldItemId != null && !heldItemId.isBlank()) {
                try {
                    ResourceLocation itemId = parseHeldItemId(heldItemId);
                    var item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(itemId);
                    if (item != null && item != Items.AIR) {
                        return new ItemStack(item);
                    }
                } catch (Exception e) {
                    Simukraft.LOGGER.warn("[CustomEntity] 无法解析手持物品: {}", heldItemId);
                }
            }
        }

        return ItemStack.EMPTY;
    }

    private static @Nullable ResourceLocation parseHeldItemId(String heldItemId) {
        return heldItemId.contains(":")
                ? ResourceLocation.tryParse(heldItemId)
                : ResourceLocation.tryParse("minecraft:" + heldItemId);
    }

    /**
     * 执行跳跃（供新寻路系统调用）
     * menglannnn: 包装protected方法供外部使用
     */
    public void doJump() {
        this.jumpFromGround();
    }
}
