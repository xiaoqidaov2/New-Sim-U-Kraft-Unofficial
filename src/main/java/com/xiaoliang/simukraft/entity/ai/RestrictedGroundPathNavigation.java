package com.xiaoliang.simukraft.entity.ai;

import com.xiaoliang.simukraft.building.PlacedBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 带边界限制的地面寻路导航（menglannnn: 在寻路层面阻止NPC走向边界外）
 */
@SuppressWarnings("null")
public class RestrictedGroundPathNavigation extends net.minecraft.world.entity.ai.navigation.GroundPathNavigation {
    private UUID restrictedBuildingId = null;
    private boolean restrictionEnabled = false;

    public RestrictedGroundPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    /**
     * 启用边界限制（menglannnn: 通常在NPC开始休息时调用）
     */
    public void enableRestriction(BlockPos controlBoxPos) {
        PlacedBuildingManager.PlacedBuildingData building = PlacedBuildingManager.getBuildingByControlBox(controlBoxPos);
        if (building != null) {
            this.restrictedBuildingId = building.buildingId;
            this.restrictionEnabled = true;
        }
    }

    /**
     * 禁用边界限制（menglannnn: 通常在NPC结束休息时调用）
     */
    public void disableRestriction() {
        this.restrictionEnabled = false;
        this.restrictedBuildingId = null;
    }

    /**
     * 检查位置是否在限制范围内
     */
    private boolean isPosAllowed(BlockPos pos) {
        // simukraft: 如果没有启用限制或没有有效的建筑ID，允许所有位置（menglannnn: 避免placed_buildings.dat为空时NPC无法移动）
        if (!restrictionEnabled || restrictedBuildingId == null) {
            return true;
        }
        return PlacedBuildingManager.isPosInBuilding(restrictedBuildingId, pos);
    }

    @Override
    @Nullable
    public Path createPath(BlockPos pos, int accuracy) {
        // simukraft: 如果目标位置超出边界，拒绝创建路径
        if (!isPosAllowed(pos)) {
            return null;
        }
        return super.createPath(pos, accuracy);
    }

    @Override
    @Nullable
    public Path createPath(net.minecraft.world.entity.Entity entity, int accuracy) {
        // simukraft: 如果目标实体位置超出边界，拒绝创建路径
        if (!isPosAllowed(entity.blockPosition())) {
            return null;
        }
        return super.createPath(entity, accuracy);
    }

    @Override
    public boolean moveTo(double x, double y, double z, double speed) {
        // simukraft: 检查目标位置是否在边界内
        if (!isPosAllowed(new BlockPos((int)x, (int)y, (int)z))) {
            return false;
        }
        return super.moveTo(x, y, z, speed);
    }

    @Override
    public boolean moveTo(net.minecraft.world.entity.Entity entity, double speed) {
        // simukraft: 检查目标实体位置是否在边界内
        if (!isPosAllowed(entity.blockPosition())) {
            return false;
        }
        return super.moveTo(entity, speed);
    }

    @Override
    public boolean moveTo(@Nullable Path path, double speed) {
        // simukraft: 检查路径的终点是否在边界内
        if (path != null && !path.isDone()) {
            Node endNode = path.getEndNode();
            if (endNode != null && !isPosAllowed(new BlockPos(endNode.x, endNode.y, endNode.z))) {
                return false;
            }
        }
        return super.moveTo(path, speed);
    }

    @Override
    public void tick() {
        // simukraft: 检查当前路径的下一个节点是否超出边界
        if (restrictionEnabled && this.path != null && !this.path.isDone()) {
            Node nextNode = this.path.getNextNode();
            if (nextNode != null) {
                BlockPos nextPos = new BlockPos(nextNode.x, nextNode.y, nextNode.z);
                if (!isPosAllowed(nextPos)) {
                    // 下一个节点超出边界，停止寻路
                    this.stop();
                    return;
                }
            }
        }

        // simukraft: 检查CustomEntity的工作状态
        if (this.mob instanceof CustomEntity) {
            CustomEntity npc = (CustomEntity) this.mob;
            if (!npc.isWorking() && this.path != null) {
                super.tick();
            } else if (npc.isWorking()) {
                super.tick();
            }
        } else {
            super.tick();
        }
    }

    /**
     * 检查是否启用了限制
     */
    public boolean isRestrictionEnabled() {
        return restrictionEnabled;
    }
}
