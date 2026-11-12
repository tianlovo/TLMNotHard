package com.tianluoqaq.tlmnh.common.ai.enhanced.support;

import java.util.function.Supplier;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 增强进食会话模块；每帧只评估一次，按原顺序提交自疗进食或工作餐提案 */
public final class EnhancedMealController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin(EnhancedSupportIds.ID_MEAL_CARE);

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final Supplier<Kind> selectedKindSupplier;
    private long nextMeal;
    private long lastCareTick = Long.MIN_VALUE;

    public EnhancedMealController(EntityMaid maid, ModernFollowCoreActor actor, Supplier<Kind> selectedKindSupplier) {
        this.maid = maid;
        this.actor = actor;
        this.selectedKindSupplier = selectedKindSupplier;
    }

    public void offer(MaidIntentFrame frame) {
        boolean eating = maid.getMealController()
            .isEating();
        long now = actor.worldGameTime();
        if (now < lastCareTick) nextMeal = EnhancedSupportTuning.ENHANCED_MEAL_NEXT_MEAL;
        lastCareTick = now;
        Kind selectedKind = selectedKindSupplier.get();
        // 新受击仍立即还手；伤害窗口结束后，持续战斗不能永久阻止自疗
        boolean reactingToHit = maid.hurtTime > 0
            && ((frame.kindMask() & ((EnhancedSupportTuning.ENHANCED_MEAL_OFFER_UNIT_STEP << Kind.MELEE.ordinal())
                | (EnhancedSupportTuning.ENHANCED_MEAL_OFFER_UNIT_STEP << Kind.RANGED.ordinal())
                | (EnhancedSupportTuning.ENHANCED_MEAL_OFFER_UNIT_STEP << Kind.COMBAT.ordinal()))) != 0
                || frame.hasGroupOffer(Group.COMBAT));
        if (maid.isTamed() && !actor.isSleeping()
            && !reactingToHit
            && (eating && selectedKind != Kind.MEAL
                || actor.missingHealth() >= EnhancedSupportTuning.MAX_MISSING_HEALTH_THRESHOLD && hasFood(true))) {
            frame.offerTyped(
                ID,
                Group.RECOVERY,
                Kind.HEAL,
                EnhancedSupportIds.ID_MEAL,
                Math.min(
                    EnhancedSupportTuning.ENHANCED_MEAL_OFFER_UNIT_STEP,
                    actor.missingHealth() / maid.getMaxHealth()),
                Boolean.TRUE,
                ignored -> executeHeal());
        } else if (maid.isTamed() && (eating && selectedKind == Kind.MEAL || !eating && now >= nextMeal
            && maid.getHomeArea()
                .activity() == Activity.WORK
            && actor.canAddWorkMeal()
            && maid.getItemUseState()
                .peek() == null
            && hasFood(false))) {
                frame.offerTyped(
                    ID,
                    Group.CARE,
                    Kind.MEAL,
                    EnhancedSupportIds.ID_WORKMEAL,
                    EnhancedSupportTuning.ENHANCED_MEAL_OFFER_TYPED_FIFTH_FACTOR,
                    Boolean.FALSE,
                    ignored -> executeWorkMeal());
            }
    }

    public void executeHeal() {
        // 活跃进食不重复调用开始效果，紧急组仍可取消当前物品租约
        if (!maid.getMealController()
            .isEating()) actor.eatMeal(true);
    }

    public void executeWorkMeal() {
        if (!maid.getMealController()
            .isEating()) {
            actor.eatMeal(false);
            nextMeal = actor.worldGameTime() + EnhancedSupportTuning.WORK_MEAL_COOLDOWN_TICKS;
        }
    }

    private boolean hasFood(boolean healing) {
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (maid.getMealController()
                .canEatMeal(stack, healing)) return true;
        }
        return false;
    }

    public void cancel() {
        // 策略级取消不清空已提交进食冷却，仅在世界时间回拨时由 offer 重置
    }
}
