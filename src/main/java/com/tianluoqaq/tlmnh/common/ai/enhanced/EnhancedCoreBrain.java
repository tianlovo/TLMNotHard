package com.tianluoqaq.tlmnh.common.ai.enhanced;

import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirStopTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentArbiter;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidMealConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile;

/** 独立增强决策的开发切片；复用执行适配，不调用F或旧原型的决策入口 */
public final class EnhancedCoreBrain implements MaidPolicyRuntime.EnhancedCoreActions {

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final MaidIntentArbiter arbiter = new MaidIntentArbiter();
    private final EnhancedNavigationController navigation;
    private final EnhancedLifeController life;
    private final EnhancedCombatController combat;
    private final MaidBreathAirTask breath = new MaidBreathAirTask();
    private final MaidBreathAirStopTask breathStop = new MaidBreathAirStopTask();
    private Kind selectedKind;
    private String selectedTarget;
    private boolean ticking;
    private long decisions, transitions;

    public EnhancedCoreBrain(EntityMaid maid) {
        this.maid = java.util.Objects.requireNonNull(maid, "maid");
        // 新增强实例不继承其他策略的攻击执行记忆或冷却
        maid.getModernCombatMemory()
            .clear();
        actor = new ModernFollowCoreActor(
            maid,
            com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile.NATIVE_MOTION_COMPENSATION);
        perception = new MaidModernPerception(maid, actor::isCurrent);
        actor.bindPerception(perception);
        var budget = com.tianluoqaq.tlmnh.common.ai.MaidWorldNavigationBudget.forWorld(maid.worldObj);
        var identity = maid.getUniqueID();
        navigation = new EnhancedNavigationController(actor, actor::requestEnhancedPath, () -> budget.cancel(identity));
        life = new EnhancedLifeController(maid, actor);
        combat = new EnhancedCombatController(maid, actor, perception);
    }

    @Override
    public void tick(Token token) {
        if (ticking) throw new IllegalStateException("Reentrant enhanced core decision");
        ticking = true;
        try {
            var runtime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            actor.beginFrame(runtime, token);
            perception.tick();
            actor.maintainSleep();
            decisions++;
            // 骑乘尚未接入；本组件持有的睡眠继续接受生活合法性/日程检查
            var home = maid.getHomeArea();
            boolean sleeping = selectedKind == Kind.SLEEP && actor.isSleeping();
            if (!maid.isTamed() || !actor.canBrainMoving() && !sleeping
                || home.enabled() && !home.sameDimension()
                || maid.getTask() != MaidTask.IDLE && maid.getTask() != MaidTask.ATTACK
                    && maid.getTask() != MaidTask.RANGED_ATTACK
                || maid.getTask() == MaidTask.IDLE && perception.nearestHostile() != null) {
                stopSelected();
                return;
            }
            if (breathStop.checkExtraStartConditions(actor)) breathStop.start(actor);
            var frame = new MaidIntentFrame(runtime.getContext(), token, actor.worldGameTime());
            boolean lowAir = actor.airSupply() < 100 && !actor.hasBreathingEffect() && !actor.hasDrownBauble();
            if (lowAir || actor.isGoingToBreath() || actor.isEatBreatheItem())
                frame.offer(Group.EMERGENCY, Kind.BREATHE, "air", 1, null);
            if (home.enabled() && !home.containsMaid()) {
                var center = home.center();
                frame.offer(
                    Group.COMMAND,
                    Kind.HOME,
                    "home:" + home.settings().dimension + ":" + center,
                    1,
                    new com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point(
                        center.x,
                        center.y,
                        center.z));
            }
            var owner = actor.getOwner();
            if (!home.enabled() && owner != null
                && !owner.isSpectator()
                && !owner.isDeadOrDying()
                && owner.getWorldIdentity() == actor.getWorldIdentity()) {
                double start = selectedKind == Kind.FOLLOW ? 2 : 6;
                if (!actor.closerThan(owner, start) && actor.closerThan(owner, 64))
                    frame.offer(Group.COMMAND, Kind.FOLLOW, "owner", 1, owner);
            }
            if (maid.getMealController()
                .isEating() || actor.missingHealth() >= 2 && hasHealingFood())
                frame.offer(
                    Group.CARE,
                    Kind.HEAL,
                    "meal",
                    Math.min(1, actor.missingHealth() / maid.getMaxHealth()),
                    null);
            combat.offer(frame);
            life.offer(frame, runtime.getContext());
            if (maid.isPickup() && !actor.isSleeping()) {
                int count = 0;
                for (var entity : perception.pickupEntities()) {
                    if (++count > 64) break;
                    if (legalPickup(entity)) frame.offer(
                        Group.AUTONOMOUS,
                        Kind.PICKUP,
                        entity.getUniqueID()
                            .toString(),
                        1 / (1 + Math.sqrt(maid.getDistanceSqToEntity(entity))),
                        entity);
                }
            }
            var selected = arbiter.choose(frame);
            Kind nextKind = selected == null ? null : selected.kind;
            String nextTarget = selected == null ? null : selected.target;
            if (nextKind != selectedKind || !java.util.Objects.equals(nextTarget, selectedTarget)) {
                stopSelected();
                actor.beginEntityFrame(runtime, token);
                selectedKind = nextKind;
                selectedTarget = nextTarget;
                transitions++;
            }
            if (selected == null || !frame.accepts(selected)) return;
            switch (selected.kind) {
                case BREATHE -> {
                    if (breath.checkExtraStartConditions(actor)) breath.start(actor);
                }
                case FOLLOW -> actor.setWalkAndLookTargetMemories(
                    (com.tianluoqaq.tlmnh.common.ai.faithful.FollowOwnerActor.Owner) selected.payload,
                    .5F,
                    2);
                case HOME -> actor.setPositionWalkAndLook(
                    (com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point) selected.payload,
                    .7F,
                    1);
                case SLEEP, SNACK -> life.execute(
                    (com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent) selected.payload,
                    runtime.getContext());
                case MELEE, RANGED -> combat
                    .execute((net.minecraft.entity.EntityLivingBase) selected.payload, selected.kind == Kind.RANGED);
                case HEAL -> {
                    // 活跃进食不重复调用开始效果，紧急组仍可取消当前物品租约
                    if (!maid.getMealController()
                        .isEating()) actor.eatMeal(true);
                }
                case PICKUP -> {
                    var entity = (Entity) selected.payload;
                    if (legalPickup(entity)) actor.setEntityWalkAndLook(entity, .6F, 0);
                }
                default -> throw new IllegalStateException("Unsupported enhanced core intent");
            }
            if (!actor.isCurrent()) return;
            if (actor.hasLookTarget()) actor.lookAtCurrentTargetIfPresent();
            navigation.tick();
        } finally {
            ticking = false;
        }
    }

    private boolean hasHealingFood() {
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && !MaidMealConfig.blocked(stack, true) && MaidFoodProfile.inspect(stack) != null)
                return true;
        }
        return false;
    }

    private boolean legalPickup(Entity entity) {
        return entity != null && entity.worldObj == maid.worldObj
            && entity.isEntityAlive()
            && maid.canPickup(entity, true)
            && maid.canEntityBeSeen(entity)
            && maid.getDistanceSqToEntity(entity) <= Math.pow(
                maid.getHomeArea()
                    .restrictionRadius() + 1,
                2)
            && maid.getHomeArea()
                .withinRestriction(
                    MathHelper.floor_double(entity.posX),
                    MathHelper.floor_double(entity.posY),
                    MathHelper.floor_double(entity.posZ));
    }

    private void stopSelected() {
        selectedKind = null;
        selectedTarget = null;
        navigation.cancel();
        try {
            try {
                life.cancel();
            } finally {
                combat.cancel();
            }
        } finally {
            actor.cancelOwnedResources();
        }
    }

    @Override
    public void cancel() {
        try {
            stopSelected();
        } finally {
            arbiter.clear();
            perception.clear();
        }
    }

    @Override
    public void afterLivingUpdate(Token token) {
        // 无选择/指令限制时本tick已经清理actor，不应恢复其移动后授权
        if (actor.isCurrent()) actor.afterLivingFrame(token);
    }

    public Kind selectedKind() {
        return selectedKind;
    }

    public long decisions() {
        return decisions;
    }

    public long transitions() {
        return transitions;
    }

    public EnhancedCombatController combat() {
        return combat;
    }

    public EnhancedLifeController life() {
        return life;
    }

    public EnhancedNavigationController navigation() {
        return navigation;
    }

    public ModernFollowCoreActor actor() {
        return actor;
    }
}
