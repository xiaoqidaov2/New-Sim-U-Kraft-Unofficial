package com.xiaoliang.simukraft.entity.ai;

import com.xiaoliang.simukraft.building.PlacedBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * 限制NPC活动范围的AI目标（menglannnn: 将边界视为物理障碍，像原版生物遇到墙一样自动避开）
 */
@SuppressWarnings("null")
public class RestrictedAreaGoal extends Goal {
    private final CustomEntity npc;
    private BlockPos centerPos;
    private int radiusX;
    private int radiusZ;
    private boolean enabled = false;
    private UUID buildingId = null;

    public RestrictedAreaGoal(CustomEntity npc) {
        this.npc = npc;
        // 设置目标标志，允许在移动时执行
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
        this.centerPos = center;
        this.radiusX = Math.max(radiusX, 3); // 最小半径3格
        this.radiusZ = Math.max(radiusZ, 3);
        this.enabled = true;

        // simukraft: 使用控制盒位置从PlacedBuildingManager获取建筑ID
        PlacedBuildingManager.PlacedBuildingData building = PlacedBuildingManager.getBuildingByControlBox(controlBoxPos);
        if (building != null) {
            this.buildingId = building.buildingId;
        }
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
     * 检查是否接近边界（在缓冲区内）
     */
    public boolean isNearBoundary() {
        if (!enabled) return false;

        // simukraft: 如果有关联的建筑ID，使用精确的建筑边界
        if (buildingId != null) {
            BlockPos pos = npc.blockPosition();
            // 检查当前位置是否在建筑内，以及周围是否在建筑内
            if (!PlacedBuildingManager.isPosInBuilding(buildingId, pos)) {
                return true; // 已经在边界外
            }
            // 检查前方是否在边界内
            Vec3 look = npc.getLookAngle();
            BlockPos frontPos = pos.offset((int) Math.signum(look.x), 0, (int) Math.signum(look.z));
            return !PlacedBuildingManager.isPosInBuilding(buildingId, frontPos);
        }

        // 回退到椭圆边界检查
        if (centerPos == null) return false;
        double dx = npc.getX() - centerPos.getX();
        double dz = npc.getZ() - centerPos.getZ();
        double normalizedDist = (dx * dx) / (radiusX * radiusX) + (dz * dz) / (radiusZ * radiusZ);

        // 如果在0.85倍半径外，认为接近边界（提前阻止）
        return normalizedDist > 0.85;
    }

    /**
     * 检查是否超出边界
     */
    public boolean isOutOfBounds() {
        if (!enabled) return false;

        // simukraft: 如果有关联的建筑ID，使用精确的建筑边界
        if (buildingId != null) {
            return !PlacedBuildingManager.isPosInBuilding(buildingId, npc.blockPosition());
        }

        // 回退到椭圆边界检查
        if (centerPos == null) return false;
        double dx = npc.getX() - centerPos.getX();
        double dz = npc.getZ() - centerPos.getZ();
        double normalizedDist = (dx * dx) / (radiusX * radiusX) + (dz * dz) / (radiusZ * radiusZ);
        return normalizedDist > 1.0;
    }

    /**
     * 检查位置是否在边界内（优先使用PlacedBuildingManager的精确边界）
     */
    public boolean isPosInBounds(BlockPos pos) {
        if (!enabled) return true;

        // simukraft: 如果有关联的建筑ID，使用精确的建筑边界
        if (buildingId != null) {
            return PlacedBuildingManager.isPosInBuilding(buildingId, pos);
        }

        // 回退到椭圆边界检查
        if (centerPos == null) return true;
        double dx = pos.getX() - centerPos.getX();
        double dz = pos.getZ() - centerPos.getZ();
        double normalizedDist = (dx * dx) / (radiusX * radiusX) + (dz * dz) / (radiusZ * radiusZ);
        return normalizedDist <= 1.0;
    }

    @Override
    public boolean canUse() {
        // simukraft: 只要启用了限制就持续检查（预防性阻止）
        return enabled;
    }

    @Override
    public boolean canContinueToUse() {
        // simukraft: 只要启用了限制就持续检查
        return enabled;
    }

    @Override
    public void tick() {
        if (!enabled || centerPos == null) return;

        // simukraft: 如果没有建筑数据且没有有效的中心点，不阻止移动（menglannnn: 避免placed_buildings.dat为空时NPC无法移动）
        if (buildingId == null && (radiusX <= 0 || radiusZ <= 0)) {
            return;
        }

        // simukraft: 检查NPC当前寻路目标是否超出边界
        if (npc.getNavigation().getPath() != null && npc.getNavigation().getPath().getEndNode() != null) {
            net.minecraft.world.level.pathfinder.Node endNode = npc.getNavigation().getPath().getEndNode();
            BlockPos targetPos = new BlockPos(endNode.x, endNode.y, endNode.z);

            // 如果目标超出边界，停止寻路
            if (!isPosInBounds(targetPos)) {
                npc.getNavigation().stop();
                return;
            }
        }

        // simukraft: 检查NPC是否接近边界
        if (isNearBoundary()) {
            // 停止当前移动，防止走出边界
            npc.getNavigation().stop();

            // 如果已经走出边界，强制返回
            if (isOutOfBounds()) {
                forceReturnToBounds();
            }
        }
    }

    @Override
    public void stop() {
        // 停止时清除寻路
        npc.getNavigation().stop();
    }

    /**
     * 强制返回边界内（menglannnn: 用于NPC已经越界时的紧急处理）
     */
    private void forceReturnToBounds() {
        if (centerPos == null) return;

        // 计算朝向中心的方向
        Vec3 toCenter = Vec3.atCenterOf(centerPos).subtract(npc.position());
        double dist = toCenter.length();

        if (dist > 0.1) {
            toCenter = toCenter.normalize();

            // 计算边界内的安全位置
            double safeDist = Math.min(radiusX, radiusZ) * 0.7;
            double ratio = safeDist / dist;

            double safeX = centerPos.getX() + (npc.getX() - centerPos.getX()) * ratio;
            double safeZ = centerPos.getZ() + (npc.getZ() - centerPos.getZ()) * ratio;

            // 传送到安全位置
            npc.teleportTo(safeX, npc.getY(), safeZ);
            npc.getNavigation().stop();
        }
    }

    /**
     * 获取限制中心
     */
    public BlockPos getCenterPos() {
        return centerPos;
    }

    /**
     * 检查是否启用了限制
     */
    public boolean isEnabled() {
        return enabled;
    }
}
