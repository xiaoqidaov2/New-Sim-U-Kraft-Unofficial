package com.xiaoliang.simukraft.entity.ai;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.entity.WorkSubState;
import com.xiaoliang.simukraft.utils.NPCFoodMarket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

@SuppressWarnings("null")
public class BuyFoodGoal extends Goal {
    private static final int START_THRESHOLD = 12;
    private static final int STOP_THRESHOLD = 20;
    private static final String LABEL_EATING = "gui.npc.status.eating";

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
        if (npc.level().isClientSide) return false;
        if (npc.tickCount < nextStartTick) return false;
        // simukraft: 工作中不能买食物，但午休时可以
        if (npc.getWorkStatus() == WorkStatus.WORKING && npc.getWorkSubState() != WorkSubState.LUNCH_BREAK) return false;
        if (npc.getWorkSubState() == WorkSubState.RESTING) return false;
        if (npc.isSleeping()) return false; // simukraft: 睡觉时不能去买食物
        if (npc.getHunger() > START_THRESHOLD) return false;
        if (!(npc.level() instanceof ServerLevel level)) return false;

        plan = NPCFoodMarket.findPurchasePlan(level, npc);
        if (plan == null) {
            nextStartTick = npc.tickCount + 200;
            return false;
        }

        targetPos = plan.shopPos();
        return true;
    }

    @Override
    public void start() {
        if (targetPos == null) return;
        npc.setStatusLabel(NPCFoodMarket.getTravelStatusLabel(plan));
        npc.setWorkNeedDetail(NPCFoodMarket.getFoodDetailKey(plan));
        npc.getNavigation().moveTo(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5, 1.0);
    }

    @Override
    public boolean canContinueToUse() {
        if (npc.level().isClientSide) return false;
        if (targetPos == null || plan == null) return false;
        if (npc.getHunger() >= STOP_THRESHOLD) return false;
        // simukraft: 工作中停止购买，但午休时可以继续
        if (npc.getWorkStatus() == WorkStatus.WORKING && npc.getWorkSubState() != WorkSubState.LUNCH_BREAK) return false;
        if (npc.isSleeping()) return false; // simukraft: 睡觉时停止购买
        return !npc.getNavigation().isDone();
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

        if (npc.blockPosition().distSqr(targetPos) <= 4.0) {
            npc.setStatusLabel(NPCFoodMarket.getBuyingStatusLabel(plan));
            boolean ok = NPCFoodMarket.tryPurchaseAndEat(level, npc, plan);
            
            if (ok) {
                npc.setStatusLabelForTicks(LABEL_EATING, 60);
                npc.setWorkNeedDetail("");
                
                // 如果还没吃饱，继续购买
                if (npc.getHunger() < STOP_THRESHOLD) {
                    // 重新查找购买计划（可能库存或资金有变化）
                    plan = NPCFoodMarket.findPurchasePlan(level, npc);
                    if (plan != null) {
                        targetPos = plan.shopPos();
                        npc.setStatusLabel(NPCFoodMarket.getTravelStatusLabel(plan));
                        npc.setWorkNeedDetail(NPCFoodMarket.getFoodDetailKey(plan));
                        npc.getNavigation().moveTo(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5, 1.0);
                        return; // 继续购买，不停止
                    }
                }
                
                nextStartTick = npc.tickCount + 600;
            } else {
                nextStartTick = npc.tickCount + 200;
            }
            stop();
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        if (NPCFoodMarket.isFoodStatusLabel(npc.getStatusLabel())) {
            npc.setStatusLabel(null);
        }
        plan = null;
        targetPos = null;
    }
}
