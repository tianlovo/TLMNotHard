package com.tianluoqaq.tlmnh.common.ai;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidDangerSensor;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidHurtMemory;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidOwnerSensor;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidTargetMemory;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidAfterNavigationGate;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentArbiter;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidLifeSelector;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.InvalidationReason;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeController;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 唯一服务端任务入口，战斗优先打断生活动作，路径和目标由当前动作独占 */
public final class MaidBrainCoordinator extends EntityAIBase {

    private final EntityMaid maid;
    private final MaidFollowController follow;
    private final MaidCombatController combat;
    private final CombatActor combatActor;
    private final MaidBowAttack bow;
    private final MaidLifeController life;
    private final MaidTelemetryChannel telemetry;
    private final MaidActionRunner actionRunner = new MaidActionRunner();
    private final MaidHomeController home;
    private final MaidTraversalController traversal;
    private final MaidPickupController pickup;
    private final MaidPanicController panic;
    private final MaidBreathController breath;
    private final MaidOwnerSensor ownerSensor;
    private final MaidDangerSensor dangerSensor;
    private final MaidTargetMemory targetMemory = new MaidTargetMemory(MaidCombatController.HIDDEN_TIMEOUT);
    private final MaidHurtMemory hurtMemory = new MaidHurtMemory();
    private MaidPolicyRuntime policy;
    private final MaidAfterNavigationGate afterNavigationGate = new MaidAfterNavigationGate();
    private final MaidAfterNavigationGate afterLivingGate = new MaidAfterNavigationGate();
    private MaidPolicyRuntime.AfterNavigationActions afterNavigationActions;
    private World observedWorld, policyWorld;
    private MaidBrainProfile requestedBrainProfile;
    private String observedOwner;
    private String lastPolicyState;
    private boolean transitioningPolicy, lifecycleFailed, unloaded, persistentReadFailed, behaviorProtected;
    private int persistentReadDepth;
    private final BooleanSupplier lifePermission;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner(true);
    private final MaidBoundedPathPlanner combatPlanner = new MaidBoundedPathPlanner(true);
    private boolean suspended;
    private long decisionTick;
    private final MaidIntentArbiter ordinaryArbiter = new MaidIntentArbiter();
    private Kind ordinaryIntent;
    private int ordinaryOffers;
    private String lastIntentState;

    public MaidBrainCoordinator(EntityMaid maid) {
        this.maid = maid;
        telemetry = new MaidTelemetryChannel(maid);
        ownerSensor = new MaidOwnerSensor(maid, telemetry);
        dangerSensor = new MaidDangerSensor(maid, telemetry);
        observedWorld = maid.worldObj;
        observedOwner = maid.func_152113_b();
        follow = new MaidFollowController(new EntityActor(), maid.getEntityId());
        home = new MaidHomeController(maid, actionRunner);
        traversal = new MaidTraversalController(maid);
        panic = new MaidPanicController(maid, actionRunner, () -> suspended, this::hasRecentHurt);
        breath = new MaidBreathController(maid, actionRunner);
        pickup = new MaidPickupController(maid, actionRunner, ownerSensor::findOwner);
        bow = new MaidBowAttack(maid);
        life = new MaidLifeController(
            maid,
            this::claimLifeControl,
            actionRunner::release,
            ownerSensor::findOwner,
            MaidLifeSelector::nearest);
        combatActor = new CombatActor();
        combat = new MaidCombatController(combatActor, maid.getEntityId());
        lifePermission = () -> !suspended && !dangerSensor.hasKnownDanger();
        setMutexBits(7);
    }

    @Override
    public boolean shouldExecute() {
        return !maid.worldObj.isRemote && maid.isEntityAlive();
    }

    @Override
    public boolean continueExecuting() {
        return shouldExecute();
    }

    @Override
    public void updateTask() {
        if (behaviorProtected) return;
        if (MaidBrainLifecycle.isWorldUnloading(maid.worldObj)) {
            onUnload();
            return;
        }
        if (unloaded && !onRegistered()) return;
        traversal.configureNavigator();
        decisionTick++;
        var runtime = getPolicyRuntime();
        if (runtime == null) {
            afterNavigationGate.clear();
            return;
        }
        if (observedWorld != maid.worldObj) runtime.invalidate(InvalidationReason.WORLD_CHANGED);
        else if (!Objects.equals(observedOwner, maid.func_152113_b()))
            runtime.invalidate(InvalidationReason.OWNER_CHANGED);
        observedWorld = maid.worldObj;
        observedOwner = maid.func_152113_b();
        boolean enhanced = runtime.getProfile() == MaidBrainProfile.ENHANCED_CORE_COMPONENT;
        if ((!runtime.requiresAfterNativeNavigation() || enhanced) && maid.getHomeArea()
            .refreshActivity()) {
            runtime.invalidate(InvalidationReason.HOME_ACTIVITY_CHANGED);
            if (enhanced) maid.getHomeArea()
                .restrictToCurrentSchedule();
            else home.requestReturn();
        }
        if (runtime.requiresAfterNativeNavigation()) {
            if (suspended) {
                afterNavigationGate.clear();
                afterLivingGate.clear();
            } else {
                // 实体日程独立于Brain；窗口在导航前打开也不能丢掉本tick的实体阶段
                afterLivingGate.book(maid.worldObj, maid.worldObj.getTotalWorldTime(), maid.ticksExisted, runtime);
                if (maid.guiOpening) afterNavigationGate.clear();
                else afterNavigationGate
                    .book(maid.worldObj, maid.worldObj.getTotalWorldTime(), maid.ticksExisted, runtime);
            }
            return;
        }
        afterNavigationGate.clear();
        if (!maid.guiOpening) executePolicy(runtime, false);
    }

    /** 原生navigator更新后、move/look/jump之前，只消费唯一协调器本tick的预约 */
    public void afterNativeNavigationTick() {
        var runtime = policy;
        if (maid.worldObj == null || maid.worldObj.isRemote
            || runtime == null
            || suspended
            || behaviorProtected
            || maid.guiOpening
            || unloaded
            || persistentReadDepth > 0
            || lifecycleFailed
            || transitioningPolicy
            || !maid.isEntityAlive()
            || MaidBrainLifecycle.isWorldUnloading(maid.worldObj)) {
            afterNavigationGate.clear();
            return;
        }
        if (!runtime.getContext()
            .getMaidId()
            .equals(maid.getUniqueID())) {
            retireCurrent(InvalidationReason.IDENTITY_CHANGED);
            policy = null;
            afterNavigationActions = null;
            refreshLifecycleObservations();
            return;
        }
        if (observedWorld != maid.worldObj || !Objects.equals(observedOwner, maid.func_152113_b())) {
            afterNavigationGate.clear();
            runtime.invalidate(
                observedWorld != maid.worldObj ? InvalidationReason.WORLD_CHANGED : InvalidationReason.OWNER_CHANGED);
            refreshLifecycleObservations();
            return;
        }
        if (afterNavigationGate.consume(maid.worldObj, maid.worldObj.getTotalWorldTime(), maid.ticksExisted, runtime))
            executePolicy(runtime, true);
    }

    /** 仅消费本tick成功现代Brain阶段预约；失效或换策略不能执行旧实体移动后动作 */
    public void afterLivingUpdate() {
        var runtime = policy;
        if (runtime == null || maid.worldObj.isRemote
            || suspended
            || behaviorProtected
            || unloaded
            || persistentReadDepth > 0
            || lifecycleFailed
            || transitioningPolicy
            || !maid.isEntityAlive()
            || MaidBrainLifecycle.isWorldUnloading(maid.worldObj)) {
            afterLivingGate.clear();
            return;
        }
        if (!runtime.getContext()
            .getMaidId()
            .equals(maid.getUniqueID())) {
            retireCurrent(InvalidationReason.IDENTITY_CHANGED);
            policy = null;
            afterNavigationActions = null;
            refreshLifecycleObservations();
            return;
        }
        if (observedWorld != maid.worldObj || !Objects.equals(observedOwner, maid.func_152113_b())) {
            afterLivingGate.clear();
            runtime.invalidate(
                observedWorld != maid.worldObj ? InvalidationReason.WORLD_CHANGED : InvalidationReason.OWNER_CHANGED);
            refreshLifecycleObservations();
            return;
        }
        if (afterLivingGate.consume(maid.worldObj, maid.worldObj.getTotalWorldTime(), maid.ticksExisted, runtime))
            runtime.tickAfterLivingUpdate();
    }

    private void executePolicy(MaidPolicyRuntime runtime, boolean afterNativeNavigation) {
        if (runtime.getProfile() == MaidBrainProfile.LEGACY_PROTOTYPE) actionRunner.beginFrame(
            runtime.getContext(),
            runtime.getContext()
                .getToken(),
            maid.worldObj,
            maid.getTask());
        else actionRunner.clear();
        ownerSensor.beginFrame(runtime.getContext(), decisionTick);
        dangerSensor.beginFrame(runtime.getContext(), decisionTick);
        targetMemory.beginFrame(maid.worldObj, runtime.getContext(), maid.worldObj.getTotalWorldTime());
        boolean recording = telemetry.isEnabled();
        long started = recording ? System.nanoTime() : 0;
        try {
            var result = afterNativeNavigation ? runtime.tickAfterNativeNavigation() : runtime.tick(lifePermission);
            if (afterNativeNavigation && result == MaidPolicyRuntime.TickResult.COMPLETED)
                afterLivingGate.book(maid.worldObj, maid.worldObj.getTotalWorldTime(), maid.ticksExisted, runtime);
            traversal.tick(!afterNativeNavigation);
            if (recording) {
                String state = runtime.getProfile()
                    .getId() + ":"
                    + runtime.isReady()
                    + ":"
                    + runtime.getContext()
                        .getEpoch()
                    + ":"
                    + runtime.getLastInvalidationReason()
                    + ":"
                    + result;
                if (!state.equals(lastPolicyState)) {
                    telemetry.state("policy", "", state);
                    lastPolicyState = state;
                }
            }
        } finally {
            if (recording) telemetry.work("brain", WorkComponent.BRAIN, System.nanoTime() - started);
        }
    }

    @Override
    public void resetTask() {
        afterNavigationGate.clear();
        afterLivingGate.clear();
        var runtime = getPolicyRuntime();
        if (runtime != null) runtime.invalidate(InvalidationReason.RESET);
    }

    /** 延迟绑定持久UUID；原生NBT读取会在实体构造之后恢复UUID */
    public MaidPolicyRuntime getPolicyRuntime() {
        if (maid.worldObj == null || maid.worldObj.isRemote
            || transitioningPolicy
            || lifecycleFailed
            || behaviorProtected
            || unloaded
            || persistentReadDepth > 0
            || MaidBrainLifecycle.isWorldUnloading(maid.worldObj)) return policy;
        if (policy != null && (!policy.getContext()
            .getMaidId()
            .equals(maid.getUniqueID()) || requestedBrainProfile != null && policyWorld != maid.worldObj)) {
            retireCurrent(
                policy.getContext()
                    .getMaidId()
                    .equals(maid.getUniqueID()) ? InvalidationReason.WORLD_CHANGED
                        : InvalidationReason.IDENTITY_CHANGED);
            policy = null;
            afterNavigationActions = null;
        }
        if (policy == null) {
            if (requestedBrainProfile == MaidBrainProfile.FAITHFUL_TLM)
                afterNavigationActions = new com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain(maid);
            else if (requestedBrainProfile == MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                afterNavigationActions = new com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain(maid);
            policy = new MaidPolicyRuntime(maid.getUniqueID(), new MaidPolicyRuntime.LegacyActions() {

                @Override
                public boolean tickSurvival() {
                    boolean breathing = breath.tick();
                    if (breathing) {
                        ordinaryArbiter.clear();
                        ordinaryIntent = null;
                        panic.cancel();
                        combat.cancel();
                        bow.cancel();
                        pickup.cancel();
                    }
                    return breathing;
                }

                @Override
                public boolean tickPanic() {
                    boolean panicking = panic.tick();
                    if (panicking) {
                        ordinaryArbiter.clear();
                        ordinaryIntent = null;
                        combat.cancel();
                        bow.cancel();
                        pickup.cancel();
                    }
                    return panicking;
                }

                @Override
                public boolean tickCombat() {
                    boolean fighting = combat.tick();
                    if (fighting) {
                        ordinaryArbiter.clear();
                        ordinaryIntent = null;
                    }
                    maid.getShieldController()
                        .updateActiveTarget(maid.getAttackTarget());
                    return fighting;
                }

                @Override
                public boolean tickLife(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
                    boolean living = life.tick(context, token, permitted);
                    if (!permitted || living) pickup.cancel();
                    return living;
                }

                @Override
                public void tickFollow() {
                    if (maid.getHomeArea()
                        .enabled()) {
                        follow.cancel();
                        home.tick();
                        if (actionRunner.getOwner() == Owner.HOME) pickup.cancel();
                        else pickup.tick(!dangerSensor.hasKnownDanger());
                    } else {
                        home.cancel();
                        if (actionRunner.getOwner() == Owner.FOLLOW) {
                            follow.tick();
                            if (actionRunner.getOwner() == Owner.FOLLOW) {
                                pickup.cancel();
                                return;
                            }
                        }
                        if (pickup.tick(!dangerSensor.hasKnownDanger())) follow.cancel();
                        else follow.tick();
                    }
                }

                @Override
                public boolean usesOrdinaryIntents() {
                    return true;
                }

                @Override
                public void tickOrdinaryIntents(MaidPolicyContext context, MaidPolicyContext.Token token,
                    boolean permitted) {
                    executeOrdinaryIntents(context, token, permitted);
                }

                @Override
                public void cancelAll() {
                    cancelControllers();
                }
            }, afterNavigationActions);
            policyWorld = maid.worldObj;
            if (requestedBrainProfile != null
                && policy.select(requestedBrainProfile) != MaidPolicyRuntime.SelectionResult.SELECTED)
                throw new IllegalStateException("Requested Brain binding failed");
        }
        return policy;
    }

    /** 开发组件显式安装，不保存到NBT，不把窄组件注册成完整F或N */
    public void setAfterNavigationActionsForDevelopment(MaidPolicyRuntime.AfterNavigationActions actions) {
        if (!Boolean.getBoolean("tlmnh.devCommands") || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || MaidBrainLifecycle.isWorldUnloading(maid.worldObj)
            || persistentReadDepth > 0
            || unloaded
            || lifecycleFailed
            || behaviorProtected
            || transitioningPolicy)
            throw new IllegalStateException("Development component cannot be installed at this lifecycle boundary");
        retireCurrent(InvalidationReason.PROFILE_CHANGED);
        policy = null;
        requestedBrainProfile = null;
        afterNavigationActions = actions;
        var runtime = getPolicyRuntime();
        if (runtime == null) throw new IllegalStateException("No development policy runtime");
        if (actions != null
            && runtime.select(MaidBrainProfile.FOLLOW_CORE_COMPONENT) != MaidPolicyRuntime.SelectionResult.SELECTED)
            throw new IllegalStateException("Development component selection failed");
    }

    /** 显式开发选择；F由本协调器创建完整保留Brain，普通回调不作为替代实现 */
    public MaidPolicyRuntime.SelectionResult selectProfileForDevelopment(MaidBrainProfile requested) {
        Objects.requireNonNull(requested, "requested");
        if (!Boolean.getBoolean("tlmnh.devCommands") || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || MaidBrainLifecycle.isWorldUnloading(maid.worldObj)
            || persistentReadDepth > 0
            || unloaded
            || lifecycleFailed
            || behaviorProtected
            || transitioningPolicy) return MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE;
        if (policy != null && policy.getProfile() == requested
            && policy.isReady()
            && (!(requested == MaidBrainProfile.FAITHFUL_TLM || requested == MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                || requestedBrainProfile == requested))
            return MaidPolicyRuntime.SelectionResult.UNCHANGED;
        retireCurrent(InvalidationReason.PROFILE_CHANGED);
        policy = null;
        afterNavigationActions = null;
        requestedBrainProfile = requested == MaidBrainProfile.FAITHFUL_TLM
            || requested == MaidBrainProfile.ENHANCED_CORE_COMPONENT ? requested : null;
        var runtime = getPolicyRuntime();
        if (requestedBrainProfile != null) return runtime.isReady() ? MaidPolicyRuntime.SelectionResult.SELECTED
            : MaidPolicyRuntime.SelectionResult.UNAVAILABLE;
        return requested == MaidBrainProfile.LEGACY_PROTOTYPE ? MaidPolicyRuntime.SelectionResult.SELECTED
            : runtime.select(requested);
    }

    /** 观察当前已绑定的原版保留Brain，不创建状态或恢复失效策略 */
    public com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain peekFaithfulBrain() {
        return policy != null && policy.isReady()
            && (requestedBrainProfile == null || policyWorld == maid.worldObj)
            && policy.requiresAfterNativeNavigation()
            && afterNavigationActions instanceof com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain brain ? brain
                : null;
    }

    /** 只观察独立增强切片，不创建或恢复实现 */
    public com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain peekEnhancedCore() {
        return policy != null && policy.isReady()
            && policyWorld == maid.worldObj
            && policy.getProfile() == MaidBrainProfile.ENHANCED_CORE_COMPONENT
            && afterNavigationActions instanceof com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain brain ? brain
                : null;
    }

    /** 元数据观察不创建或恢复策略，尚未创建时返回 null */
    public MaidPolicyRuntime peekPolicyRuntime() {
        return policy;
    }

    /** 仅已真正登记回世界的对象可以结束卸载阻断，清理失败不会自动恢复 */
    public boolean onRegistered() {
        var world = maid.worldObj;
        if (world == null || world.isRemote
            || !maid.isEntityAlive()
            || lifecycleFailed
            || transitioningPolicy
            || persistentReadDepth > 0
            || MaidBrainLifecycle.isWorldUnloading(world)
            || world.getEntityByID(maid.getEntityId()) != maid
            || !listedByIdentity(world)
            || !maid.addedToChunk
            || !world.getChunkProvider()
                .chunkExists(maid.chunkCoordX, maid.chunkCoordZ)
            || !world.getChunkFromChunkCoords(maid.chunkCoordX, maid.chunkCoordZ).isChunkLoaded) return false;
        if (unloaded) {
            unloaded = false;
            policy = null;
            refreshLifecycleObservations();
        }
        return true;
    }

    private boolean listedByIdentity(World world) {
        // 原生Entity.equals只比较entityId，不能据此确认本对象仍在列表中
        for (Object value : world.loadedEntityList) if (value == maid) return true;
        return false;
    }

    /** 外层 native NBT 入口先调用，child 入口嵌套时只增加深度 */
    public void beforePersistentRead() {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        persistentReadDepth++;
        if (persistentReadDepth > 1) return;
        persistentReadFailed = false;
        try {
            if (transitioningPolicy) throw new IllegalStateException("Persistent read during policy cleanup");
            // 旧runtime必须先失效，之后任何新清理失败也不能保留旧授权
            Throwable failure = cancel(() -> retireCurrent(InvalidationReason.IDENTITY_CHANGED), null);
            failure = cancel(
                () -> maid.getShieldController()
                    .onEntityBoundary("persistent_read"),
                failure);
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        } catch (RuntimeException | Error failure) {
            lifecycleFailed = persistentReadFailed = true;
            throw failure;
        } finally {
            afterNavigationActions = null;
            if (telemetry.isEnabled()) telemetry.invalidate("actor_persistent_read_during_recording");
        }
    }

    public void afterPersistentRead(boolean successful) {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        if (persistentReadDepth <= 0) throw new IllegalStateException("Unbalanced persistent read boundary");
        if (!successful) lifecycleFailed = persistentReadFailed = true;
        persistentReadDepth--;
        if (persistentReadDepth == 0 && !persistentReadFailed && !lifecycleFailed) {
            if (!unloaded) policy = null;
            refreshLifecycleObservations();
        }
    }

    public boolean isLifecycleFailed() {
        return lifecycleFailed;
    }

    /** 被动物品能力不要求主人在线，也不因开发场景暂停主动AI而失效 */
    public boolean allowsReactiveItemUse() {
        return maid.worldObj != null && !maid.worldObj.isRemote
            && !unloaded
            && !lifecycleFailed
            && !transitioningPolicy
            && persistentReadDepth == 0
            && !MaidBrainLifecycle.isWorldUnloading(maid.worldObj);
    }

    private void refreshLifecycleObservations() {
        observedWorld = maid.worldObj;
        observedOwner = maid.func_152113_b();
        dangerSensor.clear();
        lastPolicyState = null;
    }

    public MaidTelemetryChannel getTelemetry() {
        return telemetry;
    }

    public void onUnload() {
        if (maid.worldObj == null || maid.worldObj.isRemote || unloaded) return;
        unloaded = true;
        try {
            Throwable failure = cancel(() -> retireCurrent(InvalidationReason.UNLOADED), null);
            failure = cancel(
                () -> maid.getShieldController()
                    .onEntityBoundary("entity_unloaded"),
                failure);
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        } catch (RuntimeException | Error failure) {
            lifecycleFailed = true;
            throw failure;
        } finally {
            maid.getModernCombatMemory()
                .clear();
            afterNavigationActions = null;
            if (telemetry.isEnabled()) telemetry.invalidate("actor_unloaded_during_recording");
        }
    }

    /** 仅停行为与其旧预约，不清库存，也不把故障当作可自动恢复的暂停 */
    public void stopBehaviorForProtection() {
        if (behaviorProtected) return;
        behaviorProtected = true;
        afterNavigationGate.clear();
        afterLivingGate.clear();
        try {
            if (policy == null) cancelControllers();
            else policy.retireAfterFailure();
        } catch (RuntimeException | Error failure) {
            lifecycleFailed = true;
            throw failure;
        } finally {
            maid.clearBehaviorInputs();
        }
    }

    private void retireCurrent(InvalidationReason reason) {
        afterNavigationGate.clear();
        afterLivingGate.clear();
        if (transitioningPolicy) {
            if (policy != null) policy.retire(reason);
            return;
        }
        transitioningPolicy = true;
        try {
            if (policy == null) cancelControllers();
            else policy.retire(reason);
        } catch (RuntimeException | Error failure) {
            lifecycleFailed = true;
            throw failure;
        } finally {
            transitioningPolicy = false;
        }
    }

    private void cancelControllers() {
        ordinaryArbiter.clear();
        ordinaryIntent = null;
        MaidWorldNavigationBudget.cancel(maid);
        traversal.clear();
        ownerSensor.clear();
        dangerSensor.clear();
        combatActor.forgetOwner();
        targetMemory.clear();
        hurtMemory.clear();
        Throwable failure = cancel(breath::cancel, null);
        failure = cancel(panic::cancel, failure);
        failure = cancel(combat::cancel, failure);
        failure = cancel(pickup::cancel, failure);
        failure = cancel(life::cancel, failure);
        failure = cancel(bow::cancel, failure);
        failure = cancel(
            () -> maid.getMealController()
                .cancel(),
            failure);
        failure = cancel(follow::cancel, failure);
        failure = cancel(home::cancel, failure);
        failure = cancel(actionRunner::clear, failure);
        failure = cancel(
            () -> maid.getShieldController()
                .cancelUse("policy_cancel"),
            failure);
        failure = cancel(
            () -> maid.getItemUseState()
                .invalidate(),
            failure);
        failure = cancel(
            () -> {
                if (maid.getAquaticController() != null) maid.getAquaticController()
                    .clear();
            },
            failure);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    /** 只在原生伤害入口确实接受攻击后记录；取消/免疫的攻击不刷新记忆 */
    public void recordAcceptedHurt() {
        if (maid.worldObj != null && !maid.worldObj.isRemote && maid.isEntityAlive())
            hurtMemory.record(maid.worldObj, maid.worldObj.getTotalWorldTime());
    }

    public boolean hasRecentHurt() {
        return maid.worldObj != null && !maid.worldObj.isRemote
            && hurtMemory.isRecent(maid.worldObj, maid.worldObj.getTotalWorldTime());
    }

    public MaidBreathController getBreathController() {
        return breath;
    }

    private static Throwable cancel(Runnable action, Throwable first) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (first == null) return failure;
            if (first != failure) first.addSuppressed(failure);
        }
        return first;
    }

    public MaidFollowController getFollowController() {
        return follow;
    }

    public MaidBoundedPathPlanner.Attempt getLastPathAttempt() {
        return planner.getLastAttempt();
    }

    public MaidCombatController getCombatController() {
        return combat;
    }

    public MaidBowAttack getBowAttack() {
        return bow;
    }

    public MaidLifeController getLifeController() {
        return life;
    }

    public MaidHomeController getHomeController() {
        return home;
    }

    public MaidPanicController getPanicController() {
        return panic;
    }

    public MaidPickupController getPickupController() {
        return pickup;
    }

    public void onHomeCommandChanged() {
        if (policy != null) policy.invalidate(InvalidationReason.HOME_COMMAND_CHANGED);
    }

    private boolean claimLifeControl(MaidActionRun run) {
        return actionRunner.claim(Owner.LIFE, run, life::cancel);
    }

    public MaidActionRunner getActionRunner() {
        return actionRunner;
    }

    public MaidOwnerSensor getOwnerSensor() {
        return ownerSensor;
    }

    public MaidBoundedPathPlanner.Attempt getLastCombatPathAttempt() {
        return combatPlanner.getLastAttempt();
    }

    /** 仅窥视原PATH记忆，供同类女仆让门判断，不启动或推进对方策略 */
    public PathEntity peekModernBrainPath() {
        var core = peekFaithfulBrain();
        if (core != null) {
            var path = core.getActor()
                .peekPathMemory();
            return path == null ? null : (PathEntity) path.getIdentity();
        }
        var enhanced = peekEnhancedCore();
        if (enhanced != null) {
            var path = enhanced.actor()
                .peekPathMemory();
            return path == null ? null : (PathEntity) path.getIdentity();
        }
        return null;
    }

    /** 原refresh只停运行行为、重建Behavior/Sensor，不能用策略取消擦掉工作记忆 */
    public boolean refreshModernBrain() {
        var runtime = policy;
        if (peekEnhancedCore() != null && !suspended && !transitioningPolicy && persistentReadDepth == 0) {
            runtime.invalidate(InvalidationReason.RESET);
            return true;
        }
        if (maid.worldObj == null || maid.worldObj.isRemote
            || persistentReadDepth > 0
            || suspended
            || unloaded
            || lifecycleFailed
            || behaviorProtected
            || transitioningPolicy
            || runtime == null
            || !runtime.isReady()
            || !runtime.requiresAfterNativeNavigation()
            || requestedBrainProfile != null && policyWorld != maid.worldObj
            || !(afterNavigationActions instanceof com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain core))
            return false;
        core.refreshBrain(
            runtime,
            runtime.getContext()
                .getToken());
        return true;
    }

    /** 供受控场景暂时让出行为，恢复后重新感知，不持久化为玩家玩法状态 */
    public void setSuspended(boolean suspended) {
        if (maid.worldObj.isRemote) return;
        if (this.suspended != suspended) {
            afterNavigationGate.clear();
            afterLivingGate.clear();
            this.suspended = suspended;
            var runtime = getPolicyRuntime();
            if (runtime != null) runtime.invalidate(InvalidationReason.SUSPENSION_CHANGED);
        }
    }

    public boolean isSuspended() {
        return suspended;
    }

    public Kind getOrdinaryIntent() {
        return ordinaryIntent;
    }

    public boolean offeredOrdinaryIntent(Kind kind) {
        return (ordinaryOffers & 1 << kind.ordinal()) != 0;
    }

    private void executeOrdinaryIntents(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
        var frame = new MaidIntentFrame(context, token, maid.worldObj.getTotalWorldTime());
        if (home.wantsReturn()) frame.offer(Group.COMMAND, Kind.HOME, "home", 1, null);
        var owner = ownerSensor.findOwner();
        var lifeOptions = permitted ? life.prepareIntents(context, token)
            : java.util.Collections.<MaidLifeIntent>emptyList();
        // 合法生活保留现有委托范围，不用5格跟随起距截断8格食物活动
        if (lifeOptions.isEmpty() && !maid.getHomeArea()
            .enabled()
            && MaidOwnerSensor.isValid(maid, owner)
            && !suspended
            && maid.isTamed()
            && maid.isEntityAlive()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && !maid.getLeashed()
            && !maid.hasPose(MaidPose.SLEEPING)
            && maid.getAttackTarget() == null) {
            double distance = maid.getDistanceSqToEntity(owner);
            double threshold = actionRunner.getOwner() == Owner.FOLLOW ? MaidFollowController.STOP_DISTANCE_SQ
                : MaidFollowController.START_DISTANCE_SQ;
            if (distance > threshold && distance <= MaidFollowController.MAX_DISTANCE_SQ) frame.offer(
                Group.COMMAND,
                Kind.FOLLOW,
                owner.getUniqueID()
                    .toString(),
                1,
                null);
        }
        if (permitted) {
            for (var intent : lifeOptions) {
                var candidate = intent.getCandidate();
                boolean bed = intent.getAction() == MaidLifeIntent.Action.BED;
                frame.offer(
                    bed ? Group.CARE : Group.AUTONOMOUS,
                    bed ? Kind.SLEEP : Kind.SNACK,
                    candidate.getDimension() + ":"
                        + candidate.getX()
                        + ":"
                        + candidate.getY()
                        + ":"
                        + candidate.getZ()
                        + ":"
                        + intent.getAction(),
                    proximity(intent.getDistanceSq()),
                    intent);
            }
            for (var entity : pickup.prepareIntents()) frame.offer(
                Group.AUTONOMOUS,
                Kind.PICKUP,
                entity.getUniqueID()
                    .toString(),
                proximity(maid.getDistanceSqToEntity(entity)),
                entity);
        }
        ordinaryOffers = frame.kindMask();
        var selected = ordinaryArbiter.choose(frame);
        ordinaryIntent = selected == null ? null : selected.kind;
        String state = selected == null ? "none"
            : selected.kind + ":" + selected.target + ":" + ordinaryArbiter.reason();
        if (!state.equals(lastIntentState)) {
            telemetry.state("ordinary_intent", selected == null ? "" : selected.target, state);
            lastIntentState = state;
        }
        boolean living = selected != null && (selected.kind == Kind.SLEEP || selected.kind == Kind.SNACK);
        if (!living) life.tick(context, token, false);
        if (selected == null || selected.kind != Kind.FOLLOW) follow.cancel();
        if (selected == null || selected.kind != Kind.HOME) home.cancel();
        if (selected == null || selected.kind != Kind.PICKUP) {
            if (pickup.getTarget() != null) pickup.cancel();
        }
        if (!frame.accepts(selected)) return;
        switch (selected.kind) {
            case SLEEP, SNACK -> life.tickSelected(context, token, (MaidLifeIntent) selected.payload);
            case PICKUP -> pickup.tickSelected((net.minecraft.entity.Entity) selected.payload);
            case FOLLOW -> follow.tick();
            case HOME -> home.tick();
        }
    }

    private static double proximity(double distanceSq) {
        return 1 / (1 + Math.sqrt(Math.max(0, distanceSq)));
    }

    /** 在原生 move helper 之后投递当 tick 的走位输入，仍由原生 travel/collision 执行 */
    public boolean applyCombatMovement() {
        return combatActor.applyMovement();
    }

    private final class CombatActor implements MaidCombatController.Actor {

        private EntityPlayer owner;
        private EntityLivingBase target;
        private PathEntity ownedPath;
        private UUID ignoredTarget;
        private long ignoreUntil, nextOwnerScan;
        private MaidTask selectedTask;
        private MaidActionRun controlRun;
        private final MaidStrafingState strafing = new MaidStrafingState();
        private long movementWorldTick = Long.MIN_VALUE;

        @Override
        public void positionInRange() {
            if (maid.getTask() != MaidTask.RANGED_ATTACK || !controlValid()) return;
            var area = maid.getHomeArea();
            boolean boundary = !area.enabled() && owner != null
                && maid.getDistanceSqToEntity(owner) >= area.radius() * area.radius();
            strafing.tick(
                Math.sqrt(distanceSq()),
                16,
                boundary,
                () -> maid.getRNG()
                    .nextFloat());
            movementWorldTick = maid.worldObj.getTotalWorldTime();
        }

        @Override
        public void stopPositioning() {
            strafing.stop();
            movementWorldTick = Long.MIN_VALUE;
        }

        @Override
        public boolean prepareWhileChasing() {
            return maid.getTask() == MaidTask.RANGED_ATTACK;
        }

        private boolean applyMovement() {
            if (maid.worldObj == null || maid.worldObj.isRemote
                || movementWorldTick != maid.worldObj.getTotalWorldTime()) return false;
            movementWorldTick = Long.MIN_VALUE;
            if (suspended || unloaded || !controlValid() || !targetValid() || blockedReason() != null) return false;
            maid.rotationYaw = maid.rotationYawHead;
            float forward = strafing.forward(), strafe = strafing.strafe();
            if (!safeStep(forward, strafe)) {
                // 现代 MoveControl 对不可走侧向退回正前方；前方也不可走时保留原地
                forward = safeStep(1, 0) ? 1 : 0;
                strafe = 0;
            }
            maid.setAIMoveSpeed(
                (float) maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue() * .25F);
            maid.moveForward = forward;
            maid.moveStrafing = strafe;
            return true;
        }

        private boolean safeStep(float forward, float strafe) {
            if (forward == 0 && strafe == 0) return true;
            double yaw = maid.rotationYaw * Math.PI / 180;
            double dx = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * .5;
            double dz = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * .5;
            var box = maid.boundingBox.copy()
                .offset(dx, 0, dz);
            if (!maid.getHomeArea()
                .contains(maid.posX + dx, maid.posY, maid.posZ + dz)) return false;
            for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX); x++)
                for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ); z++)
                    for (int y = MathHelper.floor_double(box.minY) - 1; y <= MathHelper.floor_double(box.maxY); y++) {
                        if (!maid.worldObj.blockExists(x, y, z)) return false;
                        var block = maid.worldObj.getBlock(x, y, z);
                        if (block.getMaterial()
                            .isLiquid() || block == Blocks.fire
                            || block == Blocks.cactus) return false;
                    }
            return maid.worldObj.getCollidingBoundingBoxes(maid, box)
                .isEmpty()
                && !maid.worldObj.getCollidingBoundingBoxes(
                    maid,
                    box.copy()
                        .offset(0, -.6, 0))
                    .isEmpty();
        }

        private void forgetOwner() {
            owner = null;
            nextOwnerScan = 0;
        }

        @Override
        public MaidCombatController.Status blockedReason() {
            if (maid.worldObj.isRemote || !maid.isEntityAlive()
                || !maid.isTamed()
                || suspended
                || maid.isSitting()
                || maid.ridingEntity != null
                || maid.getLeashed()) return MaidCombatController.Status.HARD_STOP;
            if (selectedTask != null && selectedTask != maid.getTask()) return MaidCombatController.Status.DISABLED;
            if (maid.getTask() != MaidTask.ATTACK && maid.getTask() != MaidTask.RANGED_ATTACK)
                return MaidCombatController.Status.DISABLED;
            var area = maid.getHomeArea();
            if (area.enabled() && (!area.sameDimension() || !area.containsMaid() || area.activity() != Activity.WORK))
                return MaidCombatController.Status.HARD_STOP;
            if (maid.getTask() == MaidTask.RANGED_ATTACK ? !MaidBowAttack.hasBow(maid) || !bow.hasAmmunition()
                : !MaidCombatTargeting.hasMeleeWeapon(maid)) return MaidCombatController.Status.NO_WEAPON;
            if (decisionTick >= nextOwnerScan) {
                nextOwnerScan = decisionTick + MaidCombatController.SCAN_INTERVAL;
                owner = ownerSensor.findOwner();
            }
            if (!area.enabled() && !MaidCombatTargeting.validOwner(maid, owner))
                return MaidCombatController.Status.NO_OWNER;
            if (!area.enabled() && maid.getDistanceSqToEntity(owner) > MaidCombatTargeting.OWNER_RADIUS_SQ)
                return MaidCombatController.Status.HARD_STOP;
            return null;
        }

        @Override
        public boolean hasTarget() {
            return target != null;
        }

        @Override
        public boolean acquireTarget() {
            if (adopt(maid.getAttackTarget()) || adopt(maid.getAITarget())
                || owner != null && (adopt(owner.getAITarget()) || adopt(owner.getLastAttacker()))) return true;
            EntityLivingBase closest = null;
            double distance = Double.MAX_VALUE;
            boolean recording = telemetry.isEnabled();
            long started = recording ? System.nanoTime() : 0;
            var candidates = maid.worldObj
                .getEntitiesWithinAABB(EntityLivingBase.class, maid.boundingBox.expand(16, 8, 16));
            int inspected = 0;
            for (Object value : candidates) {
                if (++inspected > 64) break;
                if (value instanceof EntityLivingBase candidate && usable(candidate)) {
                    double candidateDistance = maid.getDistanceSqToEntity(candidate);
                    if (candidateDistance < distance) {
                        closest = candidate;
                        distance = candidateDistance;
                    }
                }
            }
            telemetry.scan(
                "combat_targets",
                candidates.size(),
                Math.min(inspected, 64),
                "native_aabb_returned_list;partial=" + (candidates.size() > Math.min(inspected, 64)));
            if (recording) telemetry.work("combat_targets", WorkComponent.SENSOR, System.nanoTime() - started);
            return closest != null && adopt(closest);
        }

        private boolean usable(EntityLivingBase candidate) {
            if (candidate == null) return false;
            if (candidate.getUniqueID()
                .equals(ignoredTarget) && decisionTick < ignoreUntil) {
                telemetry.candidate("combat", candidate, false, "temporarily_ignored");
                return false;
            }
            if (!MaidCombatTargeting.canAttack(maid, owner, candidate)) {
                telemetry.candidate("combat", candidate, false, "target_filter");
                return false;
            }
            boolean visible = maid.getEntitySenses()
                .canSee(candidate);
            telemetry.candidate("combat", candidate, visible, visible ? "visible" : "unseen");
            return visible;
        }

        private boolean adopt(EntityLivingBase candidate) {
            if (!usable(candidate) || !rememberVisible(candidate)) return false;
            target = candidate;
            selectedTask = maid.getTask();
            maid.setAttackTarget(candidate);
            telemetry
                .state("combat_target_selected", MaidTelemetryChannel.id(candidate), "task=" + selectedTask.getId());
            return true;
        }

        private boolean rememberVisible(EntityLivingBase candidate) {
            return targetMemory.observeVisible(
                candidate.getUniqueID(),
                candidate,
                candidate.posX,
                candidate.boundingBox.minY,
                candidate.posZ,
                candidate.boundingBox.minY + candidate.getEyeHeight());
        }

        private MaidTargetMemory.Observation lastSeen() {
            return target == null ? null : targetMemory.recall(target.getUniqueID(), target);
        }

        @Override
        public boolean targetValid() {
            return target != null && maid.getAttackTarget() == target
                && MaidCombatTargeting.canAttack(maid, owner, target);
        }

        @Override
        public boolean targetVisible() {
            boolean visible = maid.getEntitySenses()
                .canSee(target);
            if (visible) visible = rememberVisible(target);
            if (maid.getTask() == MaidTask.RANGED_ATTACK) bow.observe(target, visible, decisionTick);
            return visible;
        }

        @Override
        public double distanceSq() {
            var observation = lastSeen();
            return observation == null ? Double.NaN
                : maid.getDistanceSq(observation.getX(), observation.getFootY(), observation.getZ());
        }

        @Override
        public double reachSq() {
            return maid.getTask() == MaidTask.RANGED_ATTACK ? MaidBowAttack.RANGE_SQ
                : MaidCombatTargeting.meleeReachSq(maid, target);
        }

        @Override
        public boolean claimControl() {
            var proposed = actionRunner.newRun(maid.worldObj, maid.getTask());
            if (!actionRunner.claim(Owner.COMBAT, proposed, combat::cancel)) return false;
            controlRun = proposed;
            return controlValid();
        }

        @Override
        public boolean controlValid() {
            return actionRunner.isOwned(controlRun, maid.worldObj, maid.getTask());
        }

        @Override
        public void releaseControl() {
            var released = controlRun;
            controlRun = null;
            actionRunner.release(released);
        }

        @Override
        public void revokeControl() {
            if (controlRun != null) controlRun.cancel();
        }

        @Override
        public void lookAtTarget() {
            if (!controlValid()) return;
            var observation = lastSeen();
            if (observation == null) return;
            maid.getLookHelper()
                .setLookPosition(
                    observation.getX(),
                    observation.getEyeY(),
                    observation.getZ(),
                    30,
                    maid.getVerticalFaceSpeed());
        }

        @Override
        public boolean hasPath() {
            return ownedPath != null
                && com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership.owns(
                    maid.getNavigator()
                        .getPath(),
                    ownedPath)
                && !maid.getNavigator()
                    .noPath();
        }

        @Override
        public PathResult approachTarget() {
            if (!controlValid() || !targetValid()) return PathResult.UNREACHABLE;
            var observation = lastSeen();
            if (observation == null) return PathResult.UNREACHABLE;
            double dx = observation.getX() - maid.posX, dy = observation.getFootY() - maid.posY,
                dz = observation.getZ() - maid.posZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double fraction = distance > 16 ? 16 / distance : 1;
            var result = combatPlanner
                .request(maid, maid.posX + dx * fraction, maid.posY + dy * fraction, maid.posZ + dz * fraction, 1.0D);
            if (result == PathResult.STARTED) ownedPath = maid.getNavigator()
                .getPath();
            return result;
        }

        @Override
        public void stopNavigation() {
            if (ownedPath != null && com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership.owns(
                maid.getNavigator()
                    .getPath(),
                ownedPath))
                maid.getNavigator()
                    .clearPathEntity();
            ownedPath = null;
        }

        @Override
        public boolean attack() {
            if (!controlValid() || !targetValid()) return false;
            if (maid.getTask() == MaidTask.RANGED_ATTACK) return bow.fire(target);
            maid.swingItem();
            return maid.attackEntityAsMob(target);
        }

        @Override
        public boolean prepareAttack() {
            return maid.getTask() != MaidTask.RANGED_ATTACK || bow.prepare();
        }

        @Override
        public void interruptAttack() {
            bow.interrupt();
        }

        @Override
        public int attackInterval() {
            return maid.getTask() == MaidTask.RANGED_ATTACK ? 2 : MaidCombatController.ATTACK_INTERVAL;
        }

        @Override
        public void release(boolean suppressTarget) {
            targetMemory.forgetTarget();
            if (target != null) {
                if (suppressTarget) {
                    ignoredTarget = target.getUniqueID();
                    ignoreUntil = decisionTick + 100;
                }
                if (maid.getAttackTarget() == target) maid.setAttackTarget(null);
            }
            stopNavigation();
            bow.cancel();
            target = null;
            selectedTask = null;
        }

        @Override
        public double x() {
            return maid.posX;
        }

        @Override
        public double y() {
            return maid.posY;
        }

        @Override
        public double z() {
            return maid.posZ;
        }
    }

    private final class EntityActor implements MaidFollowController.Actor {

        private EntityPlayer owner;
        private PathEntity ownedPath;
        private MaidActionRun controlRun;

        @Override
        public boolean claimControl() {
            var proposed = actionRunner.newRun(maid.worldObj, maid.getTask());
            if (!actionRunner.claim(Owner.FOLLOW, proposed, follow::cancel)) return false;
            controlRun = proposed;
            return controlValid();
        }

        @Override
        public boolean controlValid() {
            return actionRunner.isOwned(controlRun, maid.worldObj, maid.getTask());
        }

        @Override
        public void releaseControl() {
            var released = controlRun;
            controlRun = null;
            actionRunner.release(released);
        }

        @Override
        public void revokeControl() {
            if (controlRun != null) controlRun.cancel();
        }

        @Override
        public Status stopReason() {
            if (maid.worldObj.isRemote) return Status.CLIENT;
            if (!maid.isEntityAlive()) return Status.DEAD;
            if (suspended) return Status.SUSPENDED;
            if (maid.ridingEntity != null) return Status.RIDING;
            if (maid.isSitting()) return Status.SITTING;
            if (maid.hasPose(MaidPose.SLEEPING)) return Status.SLEEPING;
            if (maid.getLeashed()) return Status.LEASHED;
            if (!maid.isTamed()) return Status.UNTAMED;
            if (maid.getAttackTarget() != null) return Status.BUSY;
            if (maid.getHomeArea()
                .enabled()) return Status.BUSY;
            return null;
        }

        @Override
        public boolean refreshOwner() {
            owner = ownerSensor.findOwner();
            return owner != null;
        }

        @Override
        public void forgetOwner() {
            owner = null;
        }

        @Override
        public boolean ownerValid() {
            return MaidOwnerSensor.isValid(maid, owner);
        }

        @Override
        public double distanceToOwnerSq() {
            return maid.getDistanceSqToEntity(owner);
        }

        @Override
        public double ownerX() {
            return owner.posX;
        }

        @Override
        public double ownerY() {
            return owner.boundingBox.minY;
        }

        @Override
        public double ownerZ() {
            return owner.posZ;
        }

        @Override
        public double x() {
            return maid.posX;
        }

        @Override
        public double y() {
            return maid.posY;
        }

        @Override
        public double z() {
            return maid.posZ;
        }

        @Override
        public void lookAtOwner() {
            if (!controlValid()) return;
            maid.getLookHelper()
                .setLookPositionWithEntity(owner, 10.0F, maid.getVerticalFaceSpeed());
        }

        @Override
        public boolean hasPath() {
            return !maid.getNavigator()
                .noPath();
        }

        @Override
        public PathResult requestPath() {
            if (!controlValid() || !ownerValid()) return PathResult.UNREACHABLE;
            double dx = ownerX() - maid.posX, dy = ownerY() - maid.posY, dz = ownerZ() - maid.posZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double fraction = distance > 16 ? 16 / distance : 1;
            double x = maid.posX + dx * fraction, y = maid.posY + dy * fraction, z = maid.posZ + dz * fraction;
            var result = planner.request(maid, x, y, z, 1.0D);
            if (result != PathResult.STARTED) return result;
            ownedPath = maid.getNavigator()
                .getPath();
            return PathResult.STARTED;
        }

        @Override
        public void stopNavigation() {
            var current = maid.getNavigator()
                .getPath();
            if (com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership.owns(current, ownedPath)) {
                maid.getNavigator()
                    .clearPathEntity();
            }
            // 原版移动和注视请求每tick消费；取消后不再提交，也不覆盖其他动作的新请求
            ownedPath = null;
        }
    }
}
