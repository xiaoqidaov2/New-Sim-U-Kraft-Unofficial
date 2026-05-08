package com.xiaoliang.simukraft.entity.ai;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.config.ServerConfig;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.utils.NPCFoodMarket;
import com.xiaoliang.simukraft.utils.SelfFeedingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

@SuppressWarnings("null")
public class BuyFoodGoal extends Goal {
    private static final int START_THRESHOLD = SelfFeedingManager.START_HUNGER_THRESHOLD;
    private static final int STOP_THRESHOLD = SelfFeedingManager.FULL_HUNGER;

    private final CustomEntity npc;
    private int nextStartTick = 0;

    private NPCFoodMarket.PurchasePlan plan;
    private BlockPos targetPos;

    public BuyFoodGoal(CustomEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!npc.canStartAutonomousGoal()) return false;
        if (npc.tickCount < nextStartTick) return false;
        if (npc.getWorkSubState() == WorkSubState.RESTING) return false;
        if (npc.isSleeping()) return false; // simukraft: 睡觉时不能去买食物
        if (npc.getHunger() > START_THRESHOLD) return false;
        if (!(npc.level() instanceof ServerLevel level)) return false;

        if (npc.getWorkSubState() != WorkSubState.BUYING_FOOD) {
            SelfFeedingManager.startSelfFeeding(npc);
        }

        plan = NPCFoodMarket.findPurchasePlan(level, npc);
        if (plan == null) {
            SelfFeedingManager.onFoodSearchFailed(level, npc);
            nextStartTick = npc.tickCount + 200;
            return false;
        }

        targetPos = plan.shopPos();
        return true;
    }

    /**
     * menglannnn: 完全使用新的自定义寻路系统，不再使用原版寻路
     */
    @Override
    public void start() {
        if (targetPos == null) return;
        npc.setStatusLabel(NPCFoodMarket.getTravelStatusLabel(plan));
        npc.setWorkNeedDetail(NPCFoodMarket.getFoodDetailKey(plan));

        if (ServerConfig.isDebugLogEnabled()) {
            Simukraft.LOGGER.info("[BuyFoodGoal] NPC {} 开始前往买食物，当前位置: {}，目标商店: {}，商品: {}",
                    npc.getFullName(), npc.blockPosition(), targetPos, plan != null ? plan.itemId() : "unknown");
        }

        if (npc.moveToWithNewPathfinder(targetPos, 1.0D)) {
            return; // 新寻路系统成功
        }

        Simukraft.LOGGER.warn("[BuyFoodGoal] NPC {} 前往买食物寻路失败，当前位置: {}，目标商店: {}，改为直接传送",
                npc.getFullName(), npc.blockPosition(), targetPos);

        npc.teleportTo(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
        npc.stopNewPathfinder();
    }

    @Override
    public boolean canContinueToUse() {
        if (npc.level().isClientSide) return false;
        if (targetPos == null || plan == null) return false;
        if (npc.getHunger() >= STOP_THRESHOLD) return false;
        if (npc.getWorkSubState() != WorkSubState.BUYING_FOOD) return false;
        if (npc.isSleeping()) return false; // simukraft: 睡觉时停止购买

        if (isAtTarget()) {
            return true;
        }

        // simukraft: 检查新寻路系统是否完成
        com.xiaoliang.simukraft.entity.ai.path.NPCPathNavigator navigator = npc.getNPCPathNavigator();
        if (navigator != null) {
            return navigator.isPathfinding();
        }
        return false;
    }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) {
            stop();
            return;
        }
        if (targetPos == null || plan == null) {
            stop();
            return;
        }

        if (npc.getWorkSubState() != WorkSubState.BUYING_FOOD) {
            stop();
            return;
        }

        if (isAtTarget()) {
            npc.setStatusLabel(NPCFoodMarket.getBuyingStatusLabel(plan));
            // 与肯打鸡等现有食物店保持一致：NPC 到达可买饭店铺后即视为完成进食，
            // 库存/税收扣减仍尽力执行，但不再阻塞恢复饥饿值。
            ItemStack purchasedFood = NPCFoodMarket.tryPurchaseFood(level, npc, plan);
            if (purchasedFood.isEmpty() && ServerConfig.isDebugLogEnabled()) {
                Simukraft.LOGGER.info("[BuyFoodGoal] NPC {} 到达店铺 {} 后未生成食物物品，按店内进食逻辑恢复饥饿值",
                        npc.getFullName(), targetPos);
            }
            NPCFoodMarket.finishPurchasedMeal(level, npc, plan);
            nextStartTick = npc.tickCount + 600;
            SelfFeedingManager.finishSelfFeeding(npc, level);
            stop();
        }
    }

    private boolean isAtTarget() {
        if (targetPos == null) {
            return false;
        }
        double distance = npc.blockPosition().distSqr(targetPos);
        if (distance <= 16.0D) {
            return true;
        }

        // 自定义寻路有时会停在控制盒附近而不是精确中心点，这里放宽到店判定。
        com.xiaoliang.simukraft.entity.ai.path.NPCPathNavigator navigator = npc.getNPCPathNavigator();
        return distance <= 36.0D && (navigator == null || !navigator.isPathfinding());
    }

    @Override
    public void stop() {
        // simukraft: 停止新寻路系统
        npc.stopNewPathfinder();
        if (NPCFoodMarket.isFoodStatusLabel(npc.getStatusLabel())) {
            npc.setStatusLabel(null);
        }
        npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        plan = null;
        targetPos = null;
    }
}
