package com.xiaoliang.simukraft.entity.ai;

import com.xiaoliang.simukraft.building.PlacedBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 带边界限制的随机漫步Goal（menglannnn: 休息时只在建筑边界内随机移动）
 */
public class RestrictedRandomStrollGoal extends RandomStrollGoal {
    private final CustomEntity npc;
    private UUID restrictedBuildingId = null;
    private boolean restrictionEnabled = false;

    public RestrictedRandomStrollGoal(CustomEntity npc, double speedModifier) {
        super(npc, speedModifier);
        this.npc = npc;
    }

    /**
     * 启用边界限制
     */
    public void enableRestriction(BlockPos controlBoxPos) {
        PlacedBuildingManager.PlacedBuildingData building = PlacedBuildingManager.getBuildingByControlBox(controlBoxPos);
        if (building != null) {
            this.restrictedBuildingId = building.buildingId;
            this.restrictionEnabled = true;
        }
    }

    /**
     * 禁用边界限制
     */
    public void disableRestriction() {
        this.restrictionEnabled = false;
        this.restrictedBuildingId = null;
    }

    /**
     * 检查位置是否在限制范围内
     */
    private boolean isPosAllowed(BlockPos pos) {
        if (!restrictionEnabled || restrictedBuildingId == null) {
            return true;
        }
        return PlacedBuildingManager.isPosInBuilding(restrictedBuildingId, pos);
    }

    @Override
    public boolean canUse() {
        // simukraft: 如果启用了限制且有有效的建筑ID，检查是否能找到边界内的目标
        if (restrictionEnabled && restrictedBuildingId != null) {
            Vec3 target = getPosition();
            if (target == null) {
                return false;
            }
            // 检查目标是否在边界内
            if (!isPosAllowed(new BlockPos((int)target.x, (int)target.y, (int)target.z))) {
                return false;
            }
        }
        return super.canUse();
    }

    @Override
    @Nullable
    protected Vec3 getPosition() {
        // simukraft: 如果启用了限制，在边界内寻找随机位置
        if (restrictionEnabled && restrictedBuildingId != null) {
            return getPositionInBounds();
        }
        return super.getPosition();
    }

    /**
     * 在建筑边界内获取随机位置（menglannnn: 最多尝试10次）
     */
    @Nullable
    private Vec3 getPositionInBounds() {
        PlacedBuildingManager.PlacedBuildingData building = 
            PlacedBuildingManager.getBuilding(restrictedBuildingId);
        if (building == null) {
            return super.getPosition();
        }

        // 在建筑边界内尝试找到可行走的随机位置
        for (int i = 0; i < 10; i++) {
            Vec3 randomPos = DefaultRandomPos.getPos(this.npc, 10, 7);
            if (randomPos != null) {
                BlockPos pos = new BlockPos((int)randomPos.x, (int)randomPos.y, (int)randomPos.z);
                // 检查是否在建筑边界内
                if (PlacedBuildingManager.isPosInBuilding(restrictedBuildingId, pos)) {
                    return randomPos;
                }
            }
        }
        
        // 如果找不到边界内的随机位置，返回null（停止随机漫步）
        return null;
    }

    /**
     * 检查是否启用了限制
     */
    public boolean isRestrictionEnabled() {
        return restrictionEnabled;
    }
}
