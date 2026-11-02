package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 唯一策略执行壳，开发组件与旧原型互斥，所有调用须来自所属世界的服务端线程 */
public final class MaidPolicyRuntime {

    public enum TickResult {
        COMPLETED,
        UNAVAILABLE,
        FAULTED,
        REFUSED_REENTRANT,
        INVALIDATED,
        WRONG_PHASE,
        RETIRED
    }

    public enum SelectionResult {
        SELECTED,
        UNCHANGED,
        UNAVAILABLE,
        REFUSED_REENTRANT,
        INVALIDATED_DURING_CANCELLATION,
        RETIRED,
        REFUSED_LIFECYCLE
    }

    public enum InvalidationReason {
        INITIALIZED,
        RESET,
        SUSPENSION_CHANGED,
        OWNER_CHANGED,
        IDENTITY_CHANGED,
        WORLD_CHANGED,
        UNLOADED,
        PROFILE_CHANGED,
        HOME_COMMAND_CHANGED,
        HOME_ACTIVITY_CHANGED,
        REENTRANT_CALL,
        CALLBACK_FAILURE
    }

    public interface LegacyActions {

        default boolean tickSurvival() {
            return false;
        }

        default boolean tickPanic() {
            return false;
        }

        boolean tickCombat();

        boolean tickLife(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted);

        void tickFollow();

        default boolean usesOrdinaryIntents() {
            return false;
        }

        default void tickOrdinaryIntents(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
            throw new IllegalStateException("Ordinary intent execution is not installed");
        }

        /** 取消各动作持有的资源；一项失败时仍应尝试其余清理，再汇总异常 */
        void cancelAll();
    }

    /** 现代行为的世界阶段合同，窄开发组件和已绑定的原版保留Brain共用相位 */
    public interface AfterNavigationActions {

        void tick(MaidPolicyContext.Token token);

        default void afterLivingUpdate(MaidPolicyContext.Token token) {}

        /** 旧 token 已先失效；清理只依据本动作实际持有的资源身份 */
        void cancel();
    }

    /** 标明已绑定完整首版保留活动实现，普通开发回调不能据此变成F */
    public interface FaithfulActions extends AfterNavigationActions {
    }

    /** 独立增强核心切片，不授予完整ENHANCED执行资格 */
    public interface EnhancedCoreActions extends AfterNavigationActions {
    }

    /** 首版增强活动的独立根；窄组件回调本身不取得完整模式的执行资格 */
    public interface EnhancedActions extends EnhancedCoreActions {
    }

    private final LegacyActions actions;
    private final MaidPolicyContext context;
    private final AfterNavigationActions afterNavigation;
    private MaidBrainProfile profile = MaidBrainProfile.LEGACY_PROTOTYPE;
    private InvalidationReason lastInvalidationReason = InvalidationReason.INITIALIZED;
    private boolean dispatching, changing, faulted, retired;

    public MaidPolicyRuntime(UUID maidId, LegacyActions actions) {
        this(maidId, actions, null);
    }

    public MaidPolicyRuntime(UUID maidId, LegacyActions actions, AfterNavigationActions afterNavigation) {
        context = new MaidPolicyContext(maidId);
        this.actions = Objects.requireNonNull(actions, "actions");
        this.afterNavigation = afterNavigation;
    }

    /** lifePermission 保持惰性；战斗占用时仍推进一次不被许可的生活清理 */
    public TickResult tick(BooleanSupplier lifePermission) {
        return tick(false, false, lifePermission);
    }

    public TickResult tickAfterNativeNavigation() {
        return tick(true, false, () -> false);
    }

    public TickResult tickAfterLivingUpdate() {
        return tick(true, true, () -> false);
    }

    private TickResult tick(boolean afterNativeNavigation, boolean afterLiving, BooleanSupplier lifePermission) {
        if (retired) return TickResult.RETIRED;
        Objects.requireNonNull(lifePermission, "lifePermission");
        if (dispatching || changing) {
            refuseReentry();
            return retired ? TickResult.RETIRED : TickResult.REFUSED_REENTRANT;
        }
        if (faulted) return TickResult.FAULTED;
        if (!hasImplementation(profile)) return TickResult.UNAVAILABLE;
        if (requiresAfterNativeNavigation() != afterNativeNavigation) return TickResult.WRONG_PHASE;
        dispatching = true;
        var token = context.getToken();
        try {
            if (afterNativeNavigation) {
                if (afterLiving) afterNavigation.afterLivingUpdate(token);
                else afterNavigation.tick(token);
                return context.isCurrent(token) ? TickResult.COMPLETED : invalidatedTickResult();
            }
            boolean surviving = actions.tickSurvival();
            if (!context.isCurrent(token)) return invalidatedTickResult();
            boolean panicking = !surviving && actions.tickPanic();
            if (!context.isCurrent(token)) return invalidatedTickResult();
            boolean fighting = !surviving && !panicking && actions.tickCombat();
            if (!context.isCurrent(token)) return invalidatedTickResult();
            boolean permitted = !surviving && !panicking && !fighting && lifePermission.getAsBoolean();
            if (!context.isCurrent(token)) return invalidatedTickResult();
            if (!surviving && !panicking && !fighting && actions.usesOrdinaryIntents()) {
                actions.tickOrdinaryIntents(context, token, permitted);
                return context.isCurrent(token) ? TickResult.COMPLETED : invalidatedTickResult();
            }
            boolean living = actions.tickLife(context, token, permitted);
            if (!context.isCurrent(token)) return invalidatedTickResult();
            if (!surviving && !panicking && !fighting && !living) {
                actions.tickFollow();
                if (!context.isCurrent(token)) return invalidatedTickResult();
            }
            return TickResult.COMPLETED;
        } catch (RuntimeException | Error failure) {
            failCallback(failure);
            throw failure;
        } finally {
            dispatching = false;
        }
    }

    /** 不可用模式停止执行并明确报告，不回落到旧原型 */
    public SelectionResult select(MaidBrainProfile requested) {
        if (retired) return SelectionResult.RETIRED;
        Objects.requireNonNull(requested, "requested");
        if (dispatching || changing) {
            refuseReentry();
            return retired ? SelectionResult.RETIRED : SelectionResult.REFUSED_REENTRANT;
        }
        if (profile == requested && !faulted)
            return hasImplementation(profile) ? SelectionResult.UNCHANGED : SelectionResult.UNAVAILABLE;
        boolean cancelled = cancelAndInvalidate(InvalidationReason.PROFILE_CHANGED);
        if (retired) return SelectionResult.RETIRED;
        if (!cancelled) return SelectionResult.INVALIDATED_DURING_CANCELLATION;
        profile = requested;
        faulted = false;
        context.setActive(hasImplementation(profile));
        return hasImplementation(profile) ? SelectionResult.SELECTED : SelectionResult.UNAVAILABLE;
    }

    /** 生命周期失效先取消旧 token；根协调器的状态决定下一自然 tick 是否允许动作 */
    public void invalidate(InvalidationReason reason) {
        if (retired) return;
        Objects.requireNonNull(reason, "reason");
        if (cancelAndInvalidate(reason) && !retired) {
            faulted = false;
            context.setActive(hasImplementation(profile));
        }
    }

    /** 身份替换或卸载时永久停止旧壳，外部持有的旧引用不能再次驱动同一组执行器 */
    public void retire(InvalidationReason reason) {
        if (retired) return;
        Objects.requireNonNull(reason, "reason");
        retired = true;
        context.retire();
        cancelAndInvalidate(reason);
    }

    /** 世界回退永久关闭已失败的壳；回调边界已尝试过清理时不重试未知接口 */
    public void retireAfterFailure() {
        if (retired) return;
        if (faulted && lastInvalidationReason == InvalidationReason.CALLBACK_FAILURE) {
            retired = true;
            context.retire();
        } else retire(InvalidationReason.CALLBACK_FAILURE);
    }

    private TickResult invalidatedTickResult() {
        return retired ? TickResult.RETIRED : TickResult.INVALIDATED;
    }

    private boolean cancelAndInvalidate(InvalidationReason reason) {
        lastInvalidationReason = reason;
        context.invalidate();
        faulted = true;
        if (changing) return false;
        var cancelledToken = context.getToken();
        changing = true;
        try {
            cancelActions();
        } catch (RuntimeException | Error failure) {
            lastInvalidationReason = InvalidationReason.CALLBACK_FAILURE;
            context.invalidate();
            throw failure;
        } finally {
            changing = false;
        }
        return context.getToken() == cancelledToken;
    }

    private void refuseReentry() {
        cancelAndInvalidate(InvalidationReason.REENTRANT_CALL);
        // 即使清理成功也不在重入栈上重新启用旧策略，须显式恢复
        faulted = true;
        context.setActive(false);
    }

    private void failCallback(Throwable primary) {
        try {
            if (!retired) cancelAndInvalidate(InvalidationReason.CALLBACK_FAILURE);
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primary) primary.addSuppressed(cleanupFailure);
        } finally {
            faulted = true;
            context.setActive(false);
            lastInvalidationReason = InvalidationReason.CALLBACK_FAILURE;
        }
    }

    private void cancelActions() {
        Throwable first = null;
        try {
            actions.cancelAll();
        } catch (RuntimeException | Error failure) {
            first = failure;
        }
        try {
            if (afterNavigation != null) afterNavigation.cancel();
        } catch (RuntimeException | Error failure) {
            if (first == null) first = failure;
            else if (first != failure) first.addSuppressed(failure);
        }
        if (first instanceof RuntimeException failure) throw failure;
        if (first instanceof Error failure) throw failure;
    }

    private boolean hasImplementation(MaidBrainProfile profile) {
        return profile.isAvailable() && (profile == MaidBrainProfile.LEGACY_PROTOTYPE
            || profile == MaidBrainProfile.FOLLOW_CORE_COMPONENT && afterNavigation != null
            || profile == MaidBrainProfile.FAITHFUL_TLM && afterNavigation instanceof FaithfulActions
            || profile == MaidBrainProfile.ENHANCED_CORE_COMPONENT && afterNavigation instanceof EnhancedCoreActions
            || profile == MaidBrainProfile.ENHANCED && afterNavigation instanceof EnhancedActions);
    }

    public boolean requiresAfterNativeNavigation() {
        return profile == MaidBrainProfile.FOLLOW_CORE_COMPONENT || profile == MaidBrainProfile.FAITHFUL_TLM
            || profile.isEnhanced();
    }

    public MaidBrainProfile getProfile() {
        return profile;
    }

    public MaidPolicyContext getContext() {
        return context;
    }

    public boolean isReady() {
        return !retired && !faulted && !changing && hasImplementation(profile);
    }

    public boolean isRetired() {
        return retired;
    }

    public InvalidationReason getLastInvalidationReason() {
        return lastInvalidationReason;
    }
}
