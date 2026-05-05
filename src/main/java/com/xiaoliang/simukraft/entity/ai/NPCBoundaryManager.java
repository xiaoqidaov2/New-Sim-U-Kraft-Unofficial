package com.xiaoliang.simukraft.entity.ai;

import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.UUID;

/**
 * NPC边界管理器（menglannnn: 合并原RestrictedAreaGoal、RestrictedGroundPathNavigation、RestrictedRandomStrollGoal功能）
 * 统一管理NPC的边界限制，包括AI层面的阻止、寻路层面的限制和随机漫步的限制
 */
@SuppressWarnings("null")
public class NPCBoundaryManager extends Goal {
    private final CustomEntity npc;
    private BlockPos centerPos;
    private int radiusX;
    private int radiusZ;
    private boolean enabled = false;
    private UUID buildingId = null;

    // 界限解锁时间，23900 tick（约早上5:58），让NPC可以提前出发去工作
    private static final long BOUNDS_UNLOCK_TIME = 23900L;

    public NPCBoundaryManager(CustomEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /**
     * 设置限制区域（menglannnn: 通常在NPC开始休息时调用）
     * @param center 边界中心位置
     * @param radiusX X方向半径
     * @param radiusZ Z方向半径
     * @param controlBoxPos 控制盒位置（用于查找建筑ID）
     */
    public void setRestrictedArea(BlockPos center, int radiusX, int radiusZ, BlockPos controlBoxPos) {
        // simukraft: 暂时彻底停用边界限制
        this.enabled = false;
        this.centerPos = null;
        this.buildingId = null;
        this.radiusX = 0;
        this.radiusZ = 0;
    }

    /**
     * 设置限制区域（重载方法，向后兼容）
     */
    public void setRestrictedArea(BlockPos center, int radiusX, int radiusZ) {
        setRestrictedArea(center, radiusX, radiusZ, center);
    }

    /**
     * 清除限制区域（menglannnn: 通常在NPC结束休息时调用）
     */
    public void clearRestrictedArea() {
        this.enabled = false;
        this.centerPos = null;
        this.buildingId = null;
    }

    /**
     * 检查是否应该解锁界限（menglannnn: 23900 tick后允许NPC离开家去工作）
     */
    private boolean shouldUnlockBounds() {
        if (npc.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            long dayTime = serverLevel.getDayTime() % 24000L;
            return dayTime >= BOUNDS_UNLOCK_TIME || dayTime < 1000L;
        }
        return false;
    }

    /**
     * 检查位置是否在限制范围内
     */
    public boolean isPosAllowed(BlockPos pos) {
        return true;
    }

    /**
     * 检查是否接近边界
     */
    public boolean isNearBoundary() {
        return false;
    }

    /**
     * 检查是否超出边界
     */
    public boolean isOutOfBounds() {
        return false;
    }

    @Override
    public boolean canUse() {
        // simukraft: 暂时禁用边界限制
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        // simukraft: 暂时禁用边界限制
        return false;
    }

    @Override
    public void tick() {
    }

    @Override
    public void stop() {
    }

    /**
     * 强制返回边界内
     */
    private void forceReturnToBounds() {
    }

    /**
     * 获取边界内的随机位置（用于随机漫步）
     */
    @Nullable
    public Vec3 getRandomPositionInBounds() {
        return DefaultRandomPos.getPos(npc, 10, 7);
    }

    /**
     * 检查是否启用了限制
     */
    public boolean isEnabled() {
        return false;
    }

    /**
     * 获取限制中心
     */
    public BlockPos getCenterPos() {
        return centerPos;
    }
}
