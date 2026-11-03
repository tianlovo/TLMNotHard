package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.Objects;

import net.minecraft.entity.Entity;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidInteractWithDoor;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentArbiter;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.config.MaidMealConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile;

/** 首版保留活动的独立增强根；复用执行适配，不调用F或旧原型的决策入口 */
public final class EnhancedCoreBrain implements MaidPolicyRuntime.EnhancedActions {

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingSession sensing;
    private final MaidIntentArbiter arbiter = new MaidIntentArbiter();
    private final EnhancedNavigationController navigation;
    private final EnhancedLifeController life;
    private final EnhancedCombatController combat;
    private final EnhancedPickupController pickup;
    private final EnhancedPanicController panic;
    private final EnhancedRideController ride;
    private final EnhancedBreathingController breathing;
    private final EnhancedClimbController climb;
    private final EnhancedAmbientController ambient;
    private final EnhancedSnowballController snowball;
    private final EnhancedBuoyancyController buoyancy;
    private final EnhancedFollowController follow;
    private final EnhancedHomeController homeReturn;
    private MaidInteractWithDoor doors = new MaidInteractWithDoor();
    private Kind selectedKind;
    private String selectedTarget;
    private boolean ticking;
    private long decisions, transitions;
    private Entity observedMount;
    private boolean observedSitting;
    private long nextMeal, lastCareTick = Long.MIN_VALUE;
    private final ModernFlightProfile flightProfile;

    public EnhancedCoreBrain(EntityMaid maid) {
        this(maid, ModernFlightProfile.NATIVE_MOTION_COMPENSATION);
    }

    public EnhancedCoreBrain(EntityMaid maid, ModernFlightProfile flightProfile) {
        this.maid = Objects.requireNonNull(maid, "maid");
        if (!supportsFlightProfile(flightProfile))
            throw new IllegalArgumentException("Unsupported enhanced flight profile");
        this.flightProfile = flightProfile;
        // 新增强实例不继承其他策略的攻击执行记忆或冷却
        maid.getModernCombatMemory()
            .clear();
        actor = new ModernFollowCoreActor(maid, flightProfile);
        sensing = new com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingSession(
            maid.worldObj,
            maid.getUniqueID(),
            actor::isCurrent);
        perception = new MaidModernPerception(maid, actor::isCurrent, sensing::acquire);
        actor.bindPerception(perception);
        actor.bindSensing(sensing);
        var budget = com.tianluoqaq.tlmnh.common.ai.MaidWorldNavigationBudget.forWorld(maid.worldObj);
        var identity = maid.getUniqueID();
        navigation = new EnhancedNavigationController(actor, actor::requestEnhancedPath, () -> budget.cancel(identity));
        life = new EnhancedLifeController(maid, actor, sensing);
        combat = new EnhancedCombatController(maid, actor, perception);
        pickup = new EnhancedPickupController(maid, actor, perception, navigation);
        panic = new EnhancedPanicController(maid, actor, perception);
        ride = new EnhancedRideController(maid, actor);
        follow = new EnhancedFollowController(maid, actor);
        homeReturn = new EnhancedHomeController(maid, actor);
        breathing = new EnhancedBreathingController(maid, actor, navigation, follow);
        climb = new EnhancedClimbController(actor);
        ambient = new EnhancedAmbientController(maid, actor, perception, sensing, navigation);
        snowball = new EnhancedSnowballController(maid, actor, perception);
        buoyancy = new EnhancedBuoyancyController(maid, actor);
    }

    public static boolean supportsFlightProfile(ModernFlightProfile profile) {
        return profile == ModernFlightProfile.NATIVE_STATIC_COMPENSATION
            || profile == ModernFlightProfile.NATIVE_LAST_SAMPLE_COMPENSATION
            || profile == ModernFlightProfile.NATIVE_MOTION_COMPENSATION;
    }

    public ModernFlightProfile getFlightProfile() {
        return flightProfile;
    }

    public boolean hasFlightContract() {
        return actor.rangedBow()
            .flightProfile() == flightProfile;
    }

    @Override
    public void tick(Token token) {
        if (ticking) throw new IllegalStateException("Reentrant enhanced core decision");
        ticking = true;
        try {
            var runtime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            actor.beginFrame(runtime, token);
            if (observedMount != maid.ridingEntity || observedSitting != maid.isSitting()) {
                stopSelected();
                actor.beginEntityFrame(runtime, token);
                observedMount = maid.ridingEntity;
                observedSitting = maid.isSitting();
            }
            perception.tick();
            actor.maintainSleep();
            decisions++;
            var home = maid.getHomeArea();
            boolean sleeping = selectedKind == Kind.SLEEP && actor.isSleeping();
            if (home.enabled() && !home.sameDimension()
                || maid.getTask() != MaidTask.IDLE && maid.getTask() != MaidTask.ATTACK
                    && maid.getTask() != MaidTask.RANGED_ATTACK) {
                stopSelected();
                return;
            }
            var frame = new MaidIntentFrame(runtime.getContext(), token, actor.worldGameTime());
            boolean danger = panic.offer(frame);
            boolean stationary = EnhancedRideController.stationary(maid);
            boolean lowAir = actor.airSupply() < 100 && !actor.hasBreathingEffect() && !actor.hasDrownBauble();
            if (!actor.canBrainMoving() && !sleeping && !danger && !lowAir && (!stationary || maid.getLeashed())) {
                stopSelected();
                return;
            }
            breathing.stopIfRecovered();
            if (lowAir || actor.isGoingToBreath() || actor.isEatBreatheItem())
                frame.offer(Group.EMERGENCY, Kind.BREATHE, "air", 1, null);
            boolean vehicle = ride.offerVehicle(frame);
            ride.offerStationary(frame);
            if (!stationary && !vehicle && home.enabled() && !home.containsMaid()) {
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
            if (!stationary && !vehicle
                && !home.enabled()
                && owner != null
                && !owner.isSpectator()
                && !owner.isDeadOrDying()
                && owner.getWorldIdentity() == actor.getWorldIdentity()) {
                double start = selectedKind == Kind.FOLLOW ? 2 : 6;
                if (!actor.closerThan(owner, start)) frame.offer(Group.COMMAND, Kind.FOLLOW, "owner", 1, owner);
            }
            boolean eating = maid.getMealController()
                .isEating();
            long now = actor.worldGameTime();
            if (now < lastCareTick) nextMeal = 0;
            lastCareTick = now;
            combat.offer(frame);
            // 新受击仍立即还手；伤害窗口结束后，持续战斗不能永久阻止自疗
            boolean reactingToHit = maid.hurtTime > 0
                && (frame.kindMask() & ((1 << Kind.MELEE.ordinal()) | (1 << Kind.RANGED.ordinal()))) != 0;
            if (maid.isTamed() && !actor.isSleeping()
                && !reactingToHit
                && (eating && selectedKind != Kind.MEAL || actor.missingHealth() >= 2 && hasFood(true)))
                frame.offer(
                    Group.RECOVERY,
                    Kind.HEAL,
                    "meal",
                    Math.min(1, actor.missingHealth() / maid.getMaxHealth()),
                    null);
            else if (maid.isTamed() && (eating && selectedKind == Kind.MEAL || !eating && now >= nextMeal
                && home.activity() == com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity.WORK
                && actor.canAddWorkMeal()
                && maid.getItemUseState()
                    .peek() == null
                && hasFood(false))) frame.offer(Group.CARE, Kind.MEAL, "workmeal", .1, null);
            life.offer(frame, runtime.getContext());
            pickup.offer(frame);
            boolean foodRequest = ambient.offerBeg(frame);
            if (!foodRequest) snowball.offer(frame);
            ambient.offer(frame);
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
            if (selected == null) {
                if (actor.isCurrent()) buoyancy.tick();
                return;
            }
            if (!frame.accepts(selected)) return;
            switch (selected.kind) {
                case BREATHE -> breathing.execute();
                case FOLLOW -> follow
                    .execute((com.tianluoqaq.tlmnh.common.ai.faithful.FollowOwnerActor.Owner) selected.payload);
                case HOME -> homeReturn
                    .execute((com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point) selected.payload);
                case SLEEP, SNACK -> life.execute(
                    (com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent) selected.payload,
                    runtime.getContext());
                case MELEE, RANGED -> combat
                    .execute((net.minecraft.entity.EntityLivingBase) selected.payload, selected.kind == Kind.RANGED);
                case PANIC -> panic.execute((net.minecraft.entity.EntityLivingBase) selected.payload);
                case VEHICLE -> ride.executeVehicle(
                    (com.tianluoqaq.tlmnh.common.ai.faithful.MaidVehicleActor.Vehicle) selected.payload);
                case RIDE -> ride.look();
                case MEAL -> {
                    if (!maid.getMealController()
                        .isEating()) {
                        actor.eatMeal(false);
                        nextMeal = actor.worldGameTime() + 50;
                    }
                }
                case HEAL -> {
                    // 活跃进食不重复调用开始效果，紧急组仍可取消当前物品租约
                    if (!maid.getMealController()
                        .isEating()) actor.eatMeal(true);
                }
                case PICKUP -> pickup.execute((Entity) selected.payload);
                case BEG, OBSERVE, WANDER -> ambient.execute(selected);
                case SNOWBALL -> snowball.execute((net.minecraft.entity.EntityLivingBase) selected.payload);
                default -> throw new IllegalStateException("Unsupported enhanced core intent");
            }
            if (!actor.isCurrent()) return;
            if (EnhancedRideController.stationary(maid)) {
                actor.eraseWalkTarget();
                if (selected.kind == Kind.HEAL || selected.kind == Kind.MEAL) ride.look();
            }
            if (actor.hasLookTarget()) actor.lookAtCurrentTargetIfPresent();
            navigation.tick();
            if (actor.isCurrent()) climb.tick();
            if (actor.isCurrent()) doors.trigger(actor);
            if (actor.isCurrent()) buoyancy.tick();
        } finally {
            ticking = false;
        }
    }

    private boolean hasFood(boolean healing) {
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && !MaidMealConfig.blocked(stack, healing) && MaidFoodProfile.inspect(stack) != null)
                return true;
        }
        return false;
    }

    private void stopSelected() {
        selectedKind = null;
        selectedTarget = null;
        panic.cancel();
        ambient.cancel();
        snowball.cancel();
        pickup.cancel();
        breathing.cancel();
        sensing.cancel(com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget.Kind.AIR);
        climb.cancel();
        doors = new MaidInteractWithDoor();
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
            try {
                arbiter.clear();
                perception.clear();
            } finally {
                sensing.cancelAll();
            }
        }
    }

    @Override
    public void afterLivingUpdate(Token token) {
        // 仅沿用本tick仍有效的actor相位，不能复活已取消动作
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

    public EnhancedPickupController pickup() {
        return pickup;
    }

    public EnhancedPanicController panic() {
        return panic;
    }

    public EnhancedLifeController life() {
        return life;
    }

    public EnhancedNavigationController navigation() {
        return navigation;
    }

    public EnhancedBreathingController breathing() {
        return breathing;
    }

    public EnhancedAmbientController ambient() {
        return ambient;
    }

    public EnhancedSnowballController snowball() {
        return snowball;
    }

    public EnhancedBuoyancyController buoyancy() {
        return buoyancy;
    }

    public EnhancedFollowController follow() {
        return follow;
    }

    public EnhancedHomeController homeReturn() {
        return homeReturn;
    }

    public ModernFollowCoreActor actor() {
        return actor;
    }

    public MaidModernPerception perception() {
        return perception;
    }

    public com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingSession sensing() {
        return sensing;
    }
}
