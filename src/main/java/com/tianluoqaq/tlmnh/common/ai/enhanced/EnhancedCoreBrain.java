package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathEntity;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidInteractWithDoor;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorProvider;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorRegistry;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorStage;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedBreathingController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedBuoyancyController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedClimbController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedFollowController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedNavigationController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedRideController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidExternalBehaviorSlot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidPlatformBehaviorContext;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidPlatformHandLease;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidSessionCallbackScope;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.EnhancedMealController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidBehaviorExtensionData;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.perception.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidSensingSession;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentArbiter;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Intent;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkModeCoordinator;

/** 增强策略单活跃会话协调根；按固定五阶段收集类型化提案并隔离外部扩展模块 */
public final class EnhancedCoreBrain implements MaidPolicyRuntime.EnhancedActions {

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final MaidSensingSession sensing;
    private final MaidPlatformHandLease handLeaseIssuer;
    private final MaidSessionCallbackScope callbackScope = new MaidSessionCallbackScope();
    private final MaidIntentArbiter arbiter = new MaidIntentArbiter();
    private final EnhancedNavigationController navigation;
    private final EnhancedLifeController life;
    private final EnhancedCombatController combat;
    private final EnhancedMealController meal;
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
    private final List<MaidExternalBehaviorSlot> externalSlots;
    private final Map<MaidBehaviorId, MaidExternalBehaviorSlot> externalById;
    private final EnumMap<MaidBehaviorStage, List<MaidExternalBehaviorSlot>> externalByStage;
    private MaidInteractWithDoor doors = new MaidInteractWithDoor();
    private MaidBehaviorId activeModuleId;
    private Kind selectedKind;
    private String selectedTarget;
    private String selectedRawTarget;
    private Group selectedGroup;
    private boolean ticking;
    private long decisions, transitions;
    private Entity observedMount;
    private boolean observedSitting;
    private final ModernFlightProfile flightProfile;

    public EnhancedCoreBrain(EntityMaid maid) {
        this(maid, ModernFlightProfile.NATIVE_MOTION_COMPENSATION, null, MaidBehaviorRegistry.global());
    }

    public EnhancedCoreBrain(EntityMaid maid, ModernFlightProfile flightProfile) {
        this(maid, flightProfile, null, MaidBehaviorRegistry.global());
    }

    public EnhancedCoreBrain(EntityMaid maid, ModernFlightProfile flightProfile, NBTTagCompound initialTlmnhExtension) {
        this(maid, flightProfile, initialTlmnhExtension, MaidBehaviorRegistry.global());
    }

    public EnhancedCoreBrain(EntityMaid maid, ModernFlightProfile flightProfile, NBTTagCompound initialTlmnhExtension,
        MaidBehaviorRegistry registry) {
        this.maid = Objects.requireNonNull(maid, EnhancedAiIds.ID_MAID);
        Objects.requireNonNull(registry, EnhancedAiIds.ID_REGISTRY);
        if (!supportsFlightProfile(flightProfile))
            throw new IllegalArgumentException(EnhancedAiDiagnostics.MSG_UNSUPPORTED_ENHANCED_FLIGHT_PROFILE);
        this.flightProfile = flightProfile;
        // 新增强实例不继承其他策略的攻击执行记忆或冷却
        maid.getModernCombatMemory()
            .clear();
        actor = new ModernFollowCoreActor(maid, flightProfile);
        var identity = maid.getUniqueID();
        sensing = new MaidSensingSession(maid.worldObj, identity, actor::isCurrent);
        perception = new MaidModernPerception(maid, actor::isCurrent, sensing::acquire);
        actor.bindPerception(perception);
        actor.bindSensing(sensing);
        handLeaseIssuer = new MaidPlatformHandLease(
            maid,
            actor,
            () -> maid.getBrainCoordinator()
                .peekPolicyRuntime(),
            () -> !maid.getBrainCoordinator()
                .isSuspended());
        var budget = MaidWorldNavigationBudget.forWorld(maid.worldObj);
        navigation = new EnhancedNavigationController(actor, actor::requestEnhancedPath, () -> budget.cancel(identity));
        life = new EnhancedLifeController(maid, actor, sensing);
        combat = new EnhancedCombatController(maid, actor, perception);
        meal = new EnhancedMealController(maid, actor, () -> selectedKind);
        pickup = new EnhancedPickupController(maid, actor, perception, navigation);
        panic = new EnhancedPanicController(maid, actor, perception);
        ride = new EnhancedRideController(maid, actor);
        follow = new EnhancedFollowController(maid, actor);
        homeReturn = new EnhancedHomeController(maid, actor);
        breathing = new EnhancedBreathingController(maid, actor, sensing, navigation, follow::recoverForAir);
        climb = new EnhancedClimbController(actor);
        ambient = new EnhancedAmbientController(maid, actor, perception, sensing, navigation);
        snowball = new EnhancedSnowballController(maid, actor, perception, handLeaseIssuer);
        buoyancy = new EnhancedBuoyancyController(maid, actor);

        List<MaidBehaviorProvider> providers = registry.snapshotAndFreeze();
        if (providers.isEmpty()) {
            externalSlots = Collections.emptyList();
            externalById = Collections.emptyMap();
            externalByStage = new EnumMap<>(MaidBehaviorStage.class);
        } else {
            var slots = new ArrayList<MaidExternalBehaviorSlot>(providers.size());
            var byId = new HashMap<MaidBehaviorId, MaidExternalBehaviorSlot>(providers.size());
            var byStage = new EnumMap<MaidBehaviorStage, List<MaidExternalBehaviorSlot>>(MaidBehaviorStage.class);
            for (var provider : providers) {
                var slotRef = new MaidExternalBehaviorSlot[EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP];
                var context = new MaidPlatformBehaviorContext(
                    provider.id(),
                    maid,
                    actor,
                    perception,
                    navigation,
                    combat,
                    handLeaseIssuer,
                    callbackScope,
                    () -> slotRef[EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE] != null
                        && slotRef[EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE].isHealthy(),
                    failure -> {
                        if (slotRef[EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE] != null) {
                            slotRef[EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE]
                                .onAsyncCallbackFault(EnhancedAiIds.ID_HANDLEASEKEEPALIVE, failure);
                        }
                    });
                var slot = new MaidExternalBehaviorSlot(
                    identity,
                    maid.dimension,
                    provider,
                    callbackScope,
                    context,
                    context::releaseOwnedResources);
                slotRef[EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE] = slot;
                slots.add(slot);
                byId.put(slot.id(), slot);
                byStage.computeIfAbsent(slot.stage(), ignored -> new ArrayList<>())
                    .add(slot);
            }
            externalSlots = Collections.unmodifiableList(slots);
            externalById = Collections.unmodifiableMap(byId);
            externalByStage = byStage;
            loadPersistentBehaviorData(initialTlmnhExtension);
        }
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

    public void loadPersistentBehaviorData(NBTTagCompound tlmnhExtension) {
        for (var slot : externalSlots) {
            slot.loadPersistentData(tlmnhExtension);
        }
    }

    @Override
    public NBTTagCompound flushPersistentBehaviorData(NBTTagCompound baseTlmnhExtension) {
        if (externalSlots.isEmpty()) {
            return baseTlmnhExtension == null ? new NBTTagCompound() : (NBTTagCompound) baseTlmnhExtension.copy();
        }
        return MaidBehaviorExtensionData.flush(baseTlmnhExtension, externalSlots);
    }

    @Override
    public void onAcceptedHurt() {
        actor.wakeOwnedSleepOnHurt();
    }

    @Override
    public PathEntity peekActivePath() {
        return actor.peekOwnedPath();
    }

    @Override
    public boolean refreshModernBrain(MaidPolicyRuntime runtime, Token token) {
        if (runtime == null || !runtime.getProfile()
            .isEnhanced()) return false;
        runtime.invalidate(MaidPolicyRuntime.InvalidationReason.RESET);
        return true;
    }

    @Override
    public void tick(Token token) {
        if (ticking) throw new IllegalStateException(EnhancedAiDiagnostics.MSG_REENTRANT_ENHANCED_CORE_DECISION);
        ticking = true;
        try {
            var runtime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            actor.beginFrame(runtime, token);
            if (observedMount != maid.ridingEntity || observedSitting != maid.isSitting()) {
                stopAllActiveSessions(TransitionReason.POSTURE_CHANGED, runtime, token);
                if (!isSessionLive(runtime, token)) return;
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
                || maid.getTask() != MaidTask.IDLE && !maid.getTask()
                    .isCombatTask()
                    && !maid.getTask()
                        .isWorkTask()) {
                stopAllActiveSessions(TransitionReason.POSTURE_CHANGED, runtime, token);
                return;
            }
            var frame = new MaidIntentFrame(runtime.getContext(), token, actor.worldGameTime());
            boolean danger = panic.offer(frame);
            boolean stationary = EnhancedRideController.stationary(maid);
            boolean lowAir = breathing.lowAir();
            if (!actor.canBrainMoving() && !sleeping && !danger && !lowAir && (!stationary || maid.getLeashed())) {
                stopAllActiveSessions(TransitionReason.POSTURE_CHANGED, runtime, token);
                return;
            }
            breathing.offer(frame, lowAir);
            if (!offerExternalStage(MaidBehaviorStage.SURVIVAL_AND_DANGER, frame, runtime, token)) return;

            boolean vehicle = ride.offer(frame);
            var workModes = maid.getBrainCoordinator()
                .getWorkModeCoordinator();
            if (!workModes.shouldSuppressOwnerFollowAndHome(maid)) {
                homeReturn.offer(frame, vehicle);
                follow.offer(frame, vehicle, selectedKind);
            }
            if (!offerExternalStage(MaidBehaviorStage.COMMAND_AND_POSTURE, frame, runtime, token)) return;

            combat.offer(frame);
            meal.offer(frame);
            if (!offerExternalStage(MaidBehaviorStage.COMBAT_AND_RECOVERY, frame, runtime, token)) return;

            workModes.offerEnhanced(maid, frame);
            life.offer(frame, runtime.getContext());
            pickup.offer(frame);
            if (!offerExternalStage(MaidBehaviorStage.CARE_AND_AUTONOMOUS, frame, runtime, token)) return;

            boolean foodRequest = ambient.offerBeg(frame);
            if (!foodRequest) snowball.offer(frame);
            ambient.offer(frame);
            if (!offerExternalStage(MaidBehaviorStage.AMBIENT_IDLE, frame, runtime, token)) return;

            var selected = arbiter.choose(frame);
            MaidBehaviorId nextModuleId = selected == null ? null : selected.moduleId();
            Kind nextKind = selected == null ? null : selected.kind;
            String nextTarget = selected == null ? null : selected.target;
            String nextRawTarget = selected == null ? null : selected.rawTarget();
            Group nextGroup = selected == null ? null : selected.group;
            if (!Objects.equals(nextModuleId, activeModuleId) || nextKind != selectedKind
                || !Objects.equals(nextTarget, selectedTarget)) {
                if (activeModuleId != null) {
                    if (Objects.equals(nextModuleId, activeModuleId) && nextKind == selectedKind) {
                        notifyTargetChanged(activeModuleId, selectedRawTarget, nextRawTarget, runtime, token);
                    } else {
                        TransitionReason reason = resolveDeselectReason(activeModuleId, frame, selected);
                        deselectModule(activeModuleId, reason, runtime, token);
                    }
                }
                resetSharedLocomotion(false);
                if (!isSessionLive(runtime, token)) {
                    clearSelectedSession();
                    return;
                }
                actor.beginEntityFrame(runtime, token);
                activeModuleId = nextModuleId;
                selectedKind = nextKind;
                selectedTarget = nextTarget;
                selectedRawTarget = nextRawTarget;
                selectedGroup = nextGroup;
                transitions++;
            }
            if (selected == null) {
                if (actor.isCurrent()) buoyancy.tick();
                return;
            }
            if (!frame.accepts(selected)) return;
            selected.execute();
            if (!isSessionLive(runtime, token)) return;
            if (activeModuleId != null) {
                var extSlot = externalById.get(activeModuleId);
                if (extSlot != null && extSlot.isFaulted()) {
                    resetSharedLocomotion(false);
                    clearSelectedSession();
                    if (isSessionLive(runtime, token)) {
                        actor.beginEntityFrame(runtime, token);
                        buoyancy.tick();
                    }
                    return;
                }
            }
            if (EnhancedRideController.stationary(maid)) {
                actor.eraseWalkTarget();
                if (selected.kind == Kind.HEAL || selected.kind == Kind.MEAL) ride.look();
            }
            if (actor.hasLookTarget()) actor.lookAtCurrentTargetIfPresent();
            boolean builtInWork = MaidWorkModeCoordinator.MAINTENANCE_MODULE_ID.equals(activeModuleId)
                || MaidWorkModeCoordinator.BREEDING_MODULE_ID.equals(activeModuleId);
            if (!builtInWork) navigation.tick();
            if (actor.isCurrent()) climb.tick();
            if (actor.isCurrent()) doors.trigger(actor);
            if (actor.isCurrent()) buoyancy.tick();
        } finally {
            callbackScope.invalidateAll();
            ticking = false;
        }
    }

    private boolean offerExternalStage(MaidBehaviorStage stage, MaidIntentFrame frame, MaidPolicyRuntime runtime,
        Token token) {
        var stageSlots = externalByStage.get(stage);
        if (stageSlots == null || stageSlots.isEmpty()) return true;
        BooleanSupplier liveGuard = () -> isSessionLive(runtime, token) && frame.isCurrent();
        for (var slot : stageSlots) {
            slot.offer(frame, liveGuard);
            if (!isSessionLive(runtime, token) || !frame.isCurrent()) return false;
        }
        return true;
    }

    private TransitionReason resolveDeselectReason(MaidBehaviorId outgoingId, MaidIntentFrame frame, Intent selected) {
        var extSlot = externalById.get(outgoingId);
        if (extSlot != null && extSlot.isFaulted()) return TransitionReason.MODULE_FAULTED;
        if (selected != null && frame.hasModuleOffer(outgoingId)) return TransitionReason.PREEMPTED;
        if (selected != null && selectedGroup != null && selected.group.compareTo(selectedGroup) < 0)
            return TransitionReason.PREEMPTED;
        return TransitionReason.COMPLETED_OR_IDLE;
    }

    private void notifyTargetChanged(MaidBehaviorId moduleId, String prevTarget, String nextTarget,
        MaidPolicyRuntime runtime, Token token) {
        if (EnhancedCombatController.ID.equals(moduleId)) {
            combat.onTargetChanged();
        } else if (EnhancedLifeController.ID.equals(moduleId)) {
            life.onTargetChanged();
        } else if (EnhancedPickupController.ID.equals(moduleId)) {
            pickup.onTargetChanged();
        } else if (EnhancedSnowballController.ID.equals(moduleId)) {
            snowball.onTargetChanged();
        } else if (EnhancedAmbientController.ID.equals(moduleId)) {
            ambient.onTargetChanged();
        } else {
            var extSlot = externalById.get(moduleId);
            if (extSlot != null) {
                extSlot.onTargetChanged(prevTarget, nextTarget, () -> isSessionLive(runtime, token));
            }
        }
    }

    private void deselectModule(MaidBehaviorId moduleId, TransitionReason reason, MaidPolicyRuntime runtime,
        Token token) {
        if (EnhancedBreathingController.ID.equals(moduleId)) {
            breathing.onDeselected(reason);
        } else if (EnhancedPanicController.ID.equals(moduleId)) {
            panic.onDeselected(reason);
        } else if (EnhancedCombatController.ID.equals(moduleId)) {
            combat.onDeselected(reason);
        } else if (EnhancedLifeController.ID.equals(moduleId)) {
            life.onDeselected(reason);
        } else if (EnhancedPickupController.ID.equals(moduleId)) {
            pickup.onDeselected(reason);
        } else if (EnhancedSnowballController.ID.equals(moduleId)) {
            snowball.onDeselected(reason);
        } else if (EnhancedAmbientController.ID.equals(moduleId)) {
            ambient.onDeselected(reason);
        } else if (MaidWorkModeCoordinator.MAINTENANCE_MODULE_ID.equals(moduleId)
            || MaidWorkModeCoordinator.BREEDING_MODULE_ID.equals(moduleId)) {
                maid.getBrainCoordinator()
                    .getWorkModeCoordinator()
                    .reset();
        } else {
            var extSlot = externalById.get(moduleId);
            if (extSlot != null) {
                BooleanSupplier liveGuard = runtime != null && token != null ? () -> isSessionLive(runtime, token)
                    : () -> false;
                extSlot.onDeselected(reason, liveGuard);
            }
        }
    }

    private void stopAllActiveSessions(TransitionReason reason, MaidPolicyRuntime runtime, Token token) {
        var previousModule = activeModuleId;
        clearSelectedSession();
        if (previousModule != null && runtime != null && token != null) {
            deselectModule(previousModule, reason, runtime, token);
        }
        panic.cancel();
        ambient.cancel();
        snowball.cancel();
        pickup.cancel();
        breathing.cancel();
        meal.cancel();
        maid.getBrainCoordinator()
            .getWorkModeCoordinator()
            .reset();
        sensing.cancel(MaidSensingBudget.Kind.AIR);
        try {
            try {
                life.cancel();
            } finally {
                combat.cancel();
            }
        } finally {
            resetSharedLocomotion(true);
        }
    }

    private void resetSharedLocomotion(boolean clearCombatObservation) {
        climb.cancel();
        doors = new MaidInteractWithDoor();
        navigation.cancel();
        actor.cancelOwnedResources(clearCombatObservation);
    }

    private void clearSelectedSession() {
        activeModuleId = null;
        selectedKind = null;
        selectedTarget = null;
        selectedRawTarget = null;
        selectedGroup = null;
    }

    private boolean isSessionLive(MaidPolicyRuntime runtime, Token token) {
        return runtime != null && token != null
            && maid.getBrainCoordinator()
                .peekPolicyRuntime() == runtime
            && runtime.isReady()
            && runtime.getContext()
                .isCurrent(token)
            && !maid.getBrainCoordinator()
                .isSuspended()
            && maid.isEntityAlive()
            && !maid.isDead;
    }

    @Override
    public void cancel() {
        callbackScope.invalidateAll();
        try {
            stopAllActiveSessions(TransitionReason.BRAIN_INVALIDATED, null, null);
        } finally {
            try {
                for (var slot : externalSlots) {
                    slot.cancel();
                }
            } finally {
                try {
                    arbiter.clear();
                    perception.clear();
                } finally {
                    sensing.cancelAll();
                }
            }
        }
    }

    @Override
    public void afterLivingUpdate(Token token) {
        // 仅沿用本tick仍有效的actor相位，不能复活已取消动作
        if (actor.isCurrent()) actor.afterLivingFrame(token);
    }

    public MaidBehaviorId activeModuleId() {
        return activeModuleId;
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

    public EnhancedMealController meal() {
        return meal;
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

    public MaidSensingSession sensing() {
        return sensing;
    }
}
