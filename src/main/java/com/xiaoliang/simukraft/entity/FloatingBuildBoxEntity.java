package com.xiaoliang.simukraft.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings("null")
public class FloatingBuildBoxEntity extends PathfinderMob {
    private static final EntityDataAccessor<Float> DATA_FLOAT_HEIGHT = SynchedEntityData.defineId(FloatingBuildBoxEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_FLOAT_SPEED = SynchedEntityData.defineId(FloatingBuildBoxEntity.class, EntityDataSerializers.FLOAT);

    private int floatTimer = 0;
    private double baseY; // 基准Y坐标

    public FloatingBuildBoxEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.noPhysics = true; // 无物理碰撞
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_FLOAT_HEIGHT, 0.5f);
        this.entityData.define(DATA_FLOAT_SPEED, 0.02f);
    }

    @Override
    public void tick() {
        super.tick();
        
        // 只在首次tick时设置基准位置
        if (this.tickCount == 1) {
            this.baseY = this.getY();
        }
        
        if (this.level().isClientSide) {
            // 客户端悬浮动画
            handleFloatingAnimation();
        } else {
            // 服务器端：保持基准位置，只处理旋转
            this.setYRot(this.getYRot() + 1.0f);
        }
        
        // 防止实体移动
        this.setDeltaMovement(Vec3.ZERO);
    }

    private void handleFloatingAnimation() {
        floatTimer++;

        // 使用同步数据控制悬浮幅度与速度，保证命令设置和存档值生效。
        float animationSpeed = Math.max(0.01f, this.getFloatSpeed() * 5.0f);
        float floatOffset = (float) Math.sin(floatTimer * animationSpeed) * (this.getFloatHeight() * 0.1f);
        
        // 增加旋转速度
        this.setYRot(this.getYRot() + 2.0f); // 从0.5f增加到2.0f
        
        // 应用浮动效果，基于基准位置
        this.setPos(this.getX(), this.baseY + floatOffset, this.getZ());
    }

    @Override
    public boolean isPushable() {
        return false; // 不可被推动
    }

    @Override
    public boolean isPickable() {
        return false; // 不可被拾取
    }

    @Override
    public boolean canBeCollidedWith() {
        return false; // 无碰撞
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("FloatHeight", this.getFloatHeight());
        tag.putFloat("FloatSpeed", this.getFloatSpeed());
        tag.putDouble("BaseY", this.baseY);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("FloatHeight")) {
            this.setFloatHeight(tag.getFloat("FloatHeight"));
        }
        if (tag.contains("FloatSpeed")) {
            this.setFloatSpeed(tag.getFloat("FloatSpeed"));
        }
        if (tag.contains("BaseY")) {
            this.baseY = tag.getDouble("BaseY");
        }
    }

    // Getter 和 Setter 方法
    public float getFloatHeight() {
        return this.entityData.get(DATA_FLOAT_HEIGHT);
    }

    public void setFloatHeight(float height) {
        this.entityData.set(DATA_FLOAT_HEIGHT, height);
    }

    public float getFloatSpeed() {
        return this.entityData.get(DATA_FLOAT_SPEED);
    }

    public void setFloatSpeed(float speed) {
        this.entityData.set(DATA_FLOAT_SPEED, speed);
    }
}
