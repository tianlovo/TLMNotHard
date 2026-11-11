package com.tianluoqaq.tlmnh.common.ai;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.tianluoqaq.tlmnh.common.ai.aquatic.MaidBreathController;
import com.tianluoqaq.tlmnh.common.ai.aquatic.MaidTraversalController;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidTelemetryChannel;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.combat.MaidCombatController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.combat.MaidFaithfulFlightProfile;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidDangerSensor;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidHurtMemory;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidOwnerSensor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidAfterNavigationGate;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.InvalidationReason;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.life.runtime.MaidLifeController;

/** 唯一服务端任务入口，战斗优先打断生活动作，路径和目标由当前动作独占 */
public final class MaidBrainCoordinator extends EntityAIBase {

    private final EntityMaid maid;
    private MaidLegacyBrainHost legacy;
    private final int legacyControllerSeed;
    private final MaidTelemetryChannel telemetry;
    private final MaidActionRunner actionRunner = new MaidActionRunner();
    private final MaidTraversalController traversal;
    private final MaidOwnerSensor ownerSensor;
    private final MaidDangerSensor dangerSensor;
    private final MaidHurtMemory hurtMemory = new MaidHurtMemory();
    private MaidPolicyRuntime policy;
    private final MaidAfterNavigationGate afterNavigationGate = new MaidAfterNavigationGate();
    private final MaidAfterNavigationGate afterLivingGate = new MaidAfterNavigationGate();
    private MaidPolicyRuntime.AfterNavigationActions afterNavigationActions;
    private World observedWorld, policyWorld;
    private MaidBrainProfile requestedBrainProfile;
    private MaidBrainProfile configuredBrainProfile;
    private boolean developmentOverride, creatingPolicy;
    private MaidFaithfulFlightProfile requestedFaithfulFlight = MaidFaithfulFlightProfile.SOURCE_ORIGINAL;
    private ModernFlightProfile requestedEnhancedFlight = ModernFlightProfile.NATIVE_MOTION_COMPENSATION;
    private String observedOwner;
    private String lastPolicyState;
    private boolean transitioningPolicy, lifecycleFailed, unloaded, persistentReadFailed, behaviorProtected;
    private int persistentReadDepth;
    private boolean flushingBehaviorData;
    private final BooleanSupplier lifePermission;
    private boolean suspended;
    private long decisionTick;

    public MaidBrainCoordinator(EntityMaid maid) {
        this.maid = maid;
        telemetry = new MaidTelemetryChannel(maid);
        ownerSensor = new MaidOwnerSensor(maid, telemetry);
        dangerSensor = new MaidDangerSensor(maid, telemetry);
        observedWorld = maid.worldObj;
        observedOwner = maid.func_152113_b();
        legacyControllerSeed = maid.getEntityId();
        traversal = new MaidTraversalController(maid);
        lifePermission = () -> !suspended && !dangerSensor.hasKnownDanger();
        setMutexBits(MaidAiTuning.BRAIN_COORDINATOR_SET_MUTEX_BITS_FIRST_PARAM);
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
        boolean enhanced = runtime.getProfile()
            .isEnhanced();
        if ((!runtime.requiresAfterNativeNavigation() || enhanced) && maid.getHomeArea()
            .refreshActivity()) {
            runtime.invalidate(InvalidationReason.HOME_ACTIVITY_CHANGED);
            if (enhanced) maid.getHomeArea()
                .restrictToCurrentSchedule();
            else legacyHost().home.requestReturn();
        }
        if (runtime.requiresAfterNativeNavigation()) {
            if (suspended) {
                afterNavigationGate.clear();
                afterLivingGate.clear();
            } else {
                // 实体日程独立于 Brain 调度；窗口在导航前打开时保留本 tick 的实体阶段
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

    /** 仅消费本 tick 成功的现代 Brain 预约；策略失效或变更时清空移动后动作预约 */
    public void afterLivingUpdate() {
        var runtime = policy;
        if (runtime == null || maid.worldObj.isRemote
            || suspended
            || behaviorProtected
            || creatingPolicy
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
        if (runtime.getProfile() == MaidBrainProfile.LEGACY_PROTOTYPE) {
            legacyHost();
            actionRunner.beginFrame(
                runtime.getContext(),
                runtime.getContext()
                    .getToken(),
                maid.worldObj,
                maid.getTask());
        } else actionRunner.clear();
        ownerSensor.beginFrame(runtime.getContext(), decisionTick);
        dangerSensor.beginFrame(runtime.getContext(), decisionTick);
        if (legacy != null) legacy.beginFrame(runtime.getContext(), maid.worldObj.getTotalWorldTime());
        boolean recording = telemetry.isEnabled();
        long started = recording ? System.nanoTime() : MaidAiTuning.NAVIGATION_INITIAL_VALUE;
        try {
            var result = afterNativeNavigation ? runtime.tickAfterNativeNavigation() : runtime.tick(lifePermission);
            if (afterNativeNavigation && result == MaidPolicyRuntime.TickResult.COMPLETED)
                afterLivingGate.book(maid.worldObj, maid.worldObj.getTotalWorldTime(), maid.ticksExisted, runtime);
            traversal.tick(!afterNativeNavigation);
            if (recording) {
                String state = runtime.getProfile()
                    .getId() + MaidAiIds.COLON_DELIMITER
                    + runtime.isReady()
                    + MaidAiIds.COLON_DELIMITER
                    + runtime.getContext()
                        .getEpoch()
                    + MaidAiIds.COLON_DELIMITER
                    + runtime.getLastInvalidationReason()
                    + MaidAiIds.COLON_DELIMITER
                    + result;
                if (!state.equals(lastPolicyState)) {
                    telemetry.state(MaidAiIds.ID_POLICY, "", state);
                    lastPolicyState = state;
                }
            }
        } finally {
            if (recording) telemetry.work(MaidAiIds.ID_BRAIN, WorkComponent.BRAIN, System.nanoTime() - started);
        }
    }

    @Override
    public void resetTask() {
        afterNavigationGate.clear();
        afterLivingGate.clear();
        var runtime = getPolicyRuntime();
        if (runtime != null) runtime.invalidate(InvalidationReason.RESET);
    }

    /** 延迟绑定持久UUID；正常N在完整NBT读取及真实世界登记之后由相同完整工厂创建 */
    public MaidPolicyRuntime getPolicyRuntime() {
        if (maid.worldObj == null || maid.worldObj.isRemote
            || transitioningPolicy
            || creatingPolicy
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
            if (!developmentOverride) {
                var configured = configuredBrainProfile == null ? MaidBehaviorConfig.profile() : configuredBrainProfile;
                // A join/save observer can query before native registration is complete. Do not activate N there.
                if (configured == MaidBrainProfile.ENHANCED && !onRegistered()) return null;
                configuredBrainProfile = configured;
                requestedBrainProfile = configured == MaidBrainProfile.LEGACY_PROTOTYPE ? null : configured;
                requestedEnhancedFlight = ModernFlightProfile.NATIVE_MOTION_COMPENSATION;
            }
            creatingPolicy = true;
            try {
                if (requestedBrainProfile == MaidBrainProfile.FAITHFUL_TLM)
                    afterNavigationActions = new MaidFaithfulBrain(maid, requestedFaithfulFlight);
                else if (requestedBrainProfile != null && requestedBrainProfile.isEnhanced())
                    afterNavigationActions = new EnhancedCoreBrain(
                        maid,
                        requestedEnhancedFlight,
                        maid.copyBehaviorSaveData());
                policy = new MaidPolicyRuntime(maid.getUniqueID(), new MaidPolicyRuntime.LegacyActions() {

                    @Override
                    public boolean tickSurvival() {
                        return legacyHost().tickSurvival();
                    }

                    @Override
                    public boolean tickPanic() {
                        return legacyHost().tickPanic();
                    }

                    @Override
                    public boolean tickCombat() {
                        return legacyHost().tickCombat();
                    }

                    @Override
                    public boolean tickLife(MaidPolicyContext context, MaidPolicyContext.Token token,
                        boolean permitted) {
                        return legacyHost().tickLife(context, token, permitted);
                    }

                    @Override
                    public void tickFollow() {
                        legacyHost().tickFollow();
                    }

                    @Override
                    public boolean usesOrdinaryIntents() {
                        return true;
                    }

                    @Override
                    public void tickOrdinaryIntents(MaidPolicyContext context, MaidPolicyContext.Token token,
                        boolean permitted) {
                        legacyHost().tickOrdinaryIntents(context, token, permitted);
                    }

                    @Override
                    public void cancelAll() {
                        cancelControllers();
                    }
                }, afterNavigationActions);
                policyWorld = maid.worldObj;
                if (requestedBrainProfile != null
                    && policy.select(requestedBrainProfile) != MaidPolicyRuntime.SelectionResult.SELECTED)
                    throw new IllegalStateException(MaidAiDiagnostics.MSG_REQUESTED_BRAIN_BINDING_FAILED);
            } finally {
                creatingPolicy = false;
            }
        }
        return policy;
    }

    /** 开发组件显式安装，不保存到NBT，不把窄组件注册成完整F或N */
    public void setAfterNavigationActionsForDevelopment(MaidPolicyRuntime.AfterNavigationActions actions) {
        if (!DevelopmentFlags.isDevCommandsEnabled() || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || MaidBrainLifecycle.isWorldUnloading(maid.worldObj)
            || persistentReadDepth > 0
            || unloaded
            || lifecycleFailed
            || behaviorProtected
            || creatingPolicy
            || transitioningPolicy)
            throw new IllegalStateException(MaidAiDiagnostics.MSG_DEVELOPMENT_COMPONENT_CANNOT_BE_INSTALLED);
        retireCurrent(InvalidationReason.PROFILE_CHANGED);
        policy = null;
        developmentOverride = true;
        requestedBrainProfile = null;
        requestedFaithfulFlight = MaidFaithfulFlightProfile.SOURCE_ORIGINAL;
        requestedEnhancedFlight = ModernFlightProfile.NATIVE_MOTION_COMPENSATION;
        afterNavigationActions = actions;
        var runtime = getPolicyRuntime();
        if (runtime == null) throw new IllegalStateException(MaidAiDiagnostics.MSG_NO_DEVELOPMENT_POLICY_RUNTIME);
        if (actions != null
            && runtime.select(MaidBrainProfile.FOLLOW_CORE_COMPONENT) != MaidPolicyRuntime.SelectionResult.SELECTED)
            throw new IllegalStateException(MaidAiDiagnostics.MSG_DEVELOPMENT_COMPONENT_SELECTION_FAILED);
    }

    /** 显式开发选择；F/N 由本协调器统一创建并维护独立根 */
    public MaidPolicyRuntime.SelectionResult selectProfileForDevelopment(MaidBrainProfile requested) {
        return selectProfileForDevelopment(requested, MaidFaithfulFlightProfile.SOURCE_ORIGINAL);
    }

    /** 弹道合同在创建完整根之前选择，同对象NBT重建沿用原请求，不依赖观察器改写actor */
    public MaidPolicyRuntime.SelectionResult selectProfileForDevelopment(MaidBrainProfile requested,
        MaidFaithfulFlightProfile flightProfile) {
        Objects.requireNonNull(requested, MaidAiIds.ID_REQUESTED);
        Objects.requireNonNull(flightProfile, MaidAiIds.ID_FLIGHTPROFILE);
        if (requested != MaidBrainProfile.FAITHFUL_TLM && flightProfile != MaidFaithfulFlightProfile.SOURCE_ORIGINAL)
            return MaidPolicyRuntime.SelectionResult.UNAVAILABLE;
        return selectProfileForDevelopment(requested, flightProfile, ModernFlightProfile.NATIVE_MOTION_COMPENSATION);
    }

    /** 完整N的开发消融规格在根创建时绑定，原生NBT重建仍沿用同对象的请求 */
    public MaidPolicyRuntime.SelectionResult selectProfileForDevelopment(MaidBrainProfile requested,
        ModernFlightProfile flightProfile) {
        Objects.requireNonNull(requested, MaidAiIds.ID_REQUESTED);
        Objects.requireNonNull(flightProfile, MaidAiIds.ID_FLIGHTPROFILE);
        if (requested != MaidBrainProfile.ENHANCED || !EnhancedCoreBrain.supportsFlightProfile(flightProfile))
            return MaidPolicyRuntime.SelectionResult.UNAVAILABLE;
        return selectProfileForDevelopment(requested, MaidFaithfulFlightProfile.SOURCE_ORIGINAL, flightProfile);
    }

    private MaidPolicyRuntime.SelectionResult selectProfileForDevelopment(MaidBrainProfile requested,
        MaidFaithfulFlightProfile faithfulFlight, ModernFlightProfile enhancedFlight) {
        if (!DevelopmentFlags.isDevCommandsEnabled() || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || MaidBrainLifecycle.isWorldUnloading(maid.worldObj)
            || persistentReadDepth > 0
            || unloaded
            || lifecycleFailed
            || behaviorProtected
            || creatingPolicy
            || transitioningPolicy) return MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE;
        if (policy != null && policy.getProfile() == requested
            && policy.isReady()
            && (requested != MaidBrainProfile.FAITHFUL_TLM
                || requestedFaithfulFlight == faithfulFlight && peekFaithfulBrain() != null
                    && peekFaithfulBrain().hasFlightContract())
            && (!requested.isEnhanced() || requestedEnhancedFlight == enhancedFlight && peekEnhancedCore() != null
                && peekEnhancedCore().hasFlightContract())
            && (!(requested == MaidBrainProfile.FAITHFUL_TLM || requested.isEnhanced())
                || requestedBrainProfile == requested)) {
            developmentOverride = true;
            return MaidPolicyRuntime.SelectionResult.UNCHANGED;
        }
        retireCurrent(InvalidationReason.PROFILE_CHANGED);
        policy = null;
        afterNavigationActions = null;
        developmentOverride = true;
        requestedBrainProfile = requested == MaidBrainProfile.FAITHFUL_TLM || requested.isEnhanced() ? requested : null;
        requestedFaithfulFlight = faithfulFlight;
        requestedEnhancedFlight = enhancedFlight;
        var runtime = getPolicyRuntime();
        if (requestedBrainProfile != null) return runtime.isReady() ? MaidPolicyRuntime.SelectionResult.SELECTED
            : MaidPolicyRuntime.SelectionResult.UNAVAILABLE;
        return requested == MaidBrainProfile.LEGACY_PROTOTYPE ? MaidPolicyRuntime.SelectionResult.SELECTED
            : runtime.select(requested);
    }

    /** 观察当前已绑定的原版保留Brain，不创建状态或恢复失效策略 */
    public MaidFaithfulBrain peekFaithfulBrain() {
        return policy != null && policy.isReady()
            && (requestedBrainProfile == null || policyWorld == maid.worldObj)
            && policy.requiresAfterNativeNavigation()
            && afterNavigationActions instanceof MaidFaithfulBrain brain ? brain : null;
    }

    /** 只观察当前已绑定的独立增强根，不创建或恢复实现 */
    public EnhancedCoreBrain peekEnhancedCore() {
        return policy != null && policy.isReady()
            && policyWorld == maid.worldObj
            && policy.getProfile()
                .isEnhanced()
            && afterNavigationActions instanceof EnhancedCoreBrain brain ? brain : null;
    }

    /** 元数据观察不创建或恢复策略，尚未创建时返回 null */
    public MaidPolicyRuntime peekPolicyRuntime() {
        return policy;
    }

    /** 不创建策略，开发参照保留分离任务及旧物理合同 */
    public boolean usesUnifiedCombat() {
        var profile = requestedBrainProfile != null ? requestedBrainProfile
            : policy != null ? policy.getProfile()
                : configuredBrainProfile != null ? configuredBrainProfile : MaidBehaviorConfig.profile();
        return profile == MaidBrainProfile.LEGACY_PROTOTYPE || profile.isEnhanced();
    }

    /** 仅完成世界登记恢复的对象可解除卸载阻断；清理异常时保持保护状态 */
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
        // 原生 Entity.equals 仅比对 entityId，此处进一步核验对象引用一致性
        for (Object value : world.loadedEntityList) if (value == maid) return true;
        return false;
    }

    /** 外层 native NBT 入口先调用，child 入口嵌套时只增加深度 */
    public void beforePersistentRead() {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        persistentReadDepth++;
        if (persistentReadDepth > MaidAiTuning.MAX_PERSISTENT_READ_DEPTH_THRESHOLD) return;
        persistentReadFailed = false;
        try {
            if (transitioningPolicy || creatingPolicy)
                throw new IllegalStateException(MaidAiDiagnostics.MSG_PERSISTENT_READ_DURING_POLICY_CLEANUP);
            // 优先使旧 runtime 失效，清理异常时亦确保旧授权及时撤销
            Throwable failure = cancel(() -> retireCurrent(InvalidationReason.IDENTITY_CHANGED), null);
            failure = cancel(
                () -> maid.getShieldController()
                    .onEntityBoundary(MaidAiIds.ID_PERSISTENT_READ),
                failure);
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        } catch (RuntimeException | Error failure) {
            lifecycleFailed = persistentReadFailed = true;
            throw failure;
        } finally {
            afterNavigationActions = null;
            if (telemetry.isEnabled()) telemetry.invalidate(MaidAiIds.ID_ACTOR_PERSISTENT_READ_DURING_RECORDING);
        }
    }

    public void afterPersistentRead(boolean successful) {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        if (persistentReadDepth <= 0)
            throw new IllegalStateException(MaidAiDiagnostics.MSG_UNBALANCED_PERSISTENT_READ_BOUNDARY);
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
                    .onEntityBoundary(MaidAiIds.ID_ENTITY_UNLOADED),
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
            if (telemetry.isEnabled()) telemetry.invalidate(MaidAiIds.ID_ACTOR_UNLOADED_DURING_RECORDING);
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
            flushPersistentBehaviorData();
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
        if (legacy != null) legacy.clearOrdinaryIntents();
        MaidWorldNavigationBudget.cancel(maid);
        traversal.clear();
        ownerSensor.clear();
        dangerSensor.clear();
        if (legacy != null) legacy.forgetTargets();
        hurtMemory.clear();
        Throwable failure = legacy == null ? cancel(
            () -> maid.getMealController()
                .cancel(),
            null) : legacy.cancelControllers();
        failure = cancel(actionRunner::clear, failure);
        failure = cancel(
            () -> maid.getShieldController()
                .cancelUse(MaidAiIds.ID_POLICY_CANCEL),
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
        if (maid.worldObj == null || maid.worldObj.isRemote || !maid.isEntityAlive()) return;
        hurtMemory.record(maid.worldObj, maid.worldObj.getTotalWorldTime());
        // 受伤触发原生 stopSleeping 时同步释放床位，保障状态即时生效
        var actions = currentAfterNavigationActions();
        if (actions != null) actions.onAcceptedHurt();
    }

    public boolean hasRecentHurt() {
        return maid.worldObj != null && !maid.worldObj.isRemote
            && hurtMemory.isRecent(maid.worldObj, maid.worldObj.getTotalWorldTime());
    }

    public MaidBreathController getBreathController() {
        return legacyHost().breath;
    }

    static Throwable cancel(Runnable action, Throwable first) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (first == null) return failure;
            if (first != failure) first.addSuppressed(failure);
        }
        return first;
    }

    public MaidFollowController getFollowController() {
        return legacyHost().follow;
    }

    public MaidBoundedPathPlanner.Attempt getLastPathAttempt() {
        return legacy == null ? null : legacy.planner.getLastAttempt();
    }

    public MaidCombatController getCombatController() {
        return legacyHost().combat;
    }

    public MaidBowAttack getBowAttack() {
        return legacyHost().bow;
    }

    public MaidLifeController getLifeController() {
        return legacyHost().life;
    }

    public MaidHomeController getHomeController() {
        return legacyHost().home;
    }

    public MaidPanicController getPanicController() {
        return legacyHost().panic;
    }

    public MaidPickupController getPickupController() {
        return legacyHost().pickup;
    }

    public void onHomeCommandChanged() {
        if (policy != null) policy.invalidate(InvalidationReason.HOME_COMMAND_CHANGED);
    }

    public MaidActionRunner getActionRunner() {
        return actionRunner;
    }

    public MaidOwnerSensor getOwnerSensor() {
        return ownerSensor;
    }

    public MaidBoundedPathPlanner.Attempt getLastCombatPathAttempt() {
        return legacy == null ? null : legacy.combatPlanner.getLastAttempt();
    }

    /** 保存时转发已有策略的模块数据，数据隔离与单模块异常由存储层处理 */
    public void flushPersistentBehaviorData() {
        var actions = afterNavigationActions;
        if (maid.worldObj == null || maid.worldObj.isRemote
            || actions == null
            || persistentReadDepth > 0
            || flushingBehaviorData) return;
        flushingBehaviorData = true;
        try {
            var before = maid.copyBehaviorSaveData();
            var after = actions.flushPersistentBehaviorData((NBTTagCompound) before.copy());
            if (!maid.commitBehaviorExtensionData(before, after))
                ModContext.LOG.warn(MaidAiDiagnostics.MSG_BEHAVIOR_EXTENSION_SAVE_REJECTED_FOR, maid.getUniqueID());
        } finally {
            flushingBehaviorData = false;
        }
    }

    /** 仅窥视原PATH记忆，供同类女仆让门判断，不启动或推进对方策略 */
    public PathEntity peekModernBrainPath() {
        var actions = currentAfterNavigationActions();
        return actions == null ? null : actions.peekActivePath();
    }

    /** 行为刷新委托当前策略执行，Faithful 对照模式采用其记忆复制流程 */
    public boolean refreshModernBrain() {
        var runtime = policy;
        var actions = currentAfterNavigationActions();
        if (maid.worldObj == null || maid.worldObj.isRemote
            || persistentReadDepth > 0
            || suspended
            || unloaded
            || lifecycleFailed
            || behaviorProtected
            || transitioningPolicy
            || runtime == null
            || actions == null) return false;
        return actions.refreshModernBrain(
            runtime,
            runtime.getContext()
                .getToken());
    }

    private MaidPolicyRuntime.AfterNavigationActions currentAfterNavigationActions() {
        return policy != null && policy.isReady()
            && policy.requiresAfterNativeNavigation()
            && (requestedBrainProfile == null || policyWorld == maid.worldObj) ? afterNavigationActions : null;
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
        return legacy == null ? null : legacy.getOrdinaryIntent();
    }

    public boolean offeredOrdinaryIntent(Kind kind) {
        return legacy != null && legacy.offeredOrdinaryIntent(kind);
    }

    /** 兼容性 getter 按需提供完整旧原型控制器 */
    private MaidLegacyBrainHost legacyHost() {
        if (legacy == null) legacy = new MaidLegacyBrainHost(maid, this, dangerSensor, legacyControllerSeed);
        return legacy;
    }

    long decisionTick() {
        return decisionTick;
    }

    boolean isUnloaded() {
        return unloaded;
    }

    public boolean travelLegacyBreathWater() {
        return legacy != null && legacy.breath.travelWater();
    }

    public boolean hasLegacyBreathSwimming() {
        return legacy != null && legacy.breath.isSwimming();
    }

    public boolean hasLegacyBreathActive() {
        return legacy != null && legacy.breath.isActive();
    }

    public MaidSwimManager.Target peekLegacySwimTarget() {
        return legacy == null ? null : legacy.breath.nextSwimTarget();
    }

    public void cancelLegacySleepIfActive() {
        if (legacy != null && legacy.life.isSleeping()) legacy.life.cancel();
    }

    /** 在原生 move helper 推进后投递当 tick 的旧原型走位输入 */
    public boolean applyCombatMovement() {
        return legacy != null && legacy.applyCombatMovement();
    }
}
