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
        RETIRED
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

        /** 取消各动作持有的资源；一项失败时仍应尝试其余清理，再汇总异常 */
        void cancelAll();
    }

    /** 只供已显式安装的开发组件，不代表完整忠实策略 */
    public interface AfterNavigationActions {

        void tick(MaidPolicyContext.Token token);

        /** 旧 token 已先失效；清理只依据本动作实际持有的资源身份 */
        void cancel();
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
        return tick(false, lifePermission);
    }

    public TickResult tickAfterNativeNavigation() {
        return tick(true, () -> false);
    }

    private TickResult tick(boolean afterNativeNavigation, BooleanSupplier lifePermission) {
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
                afterNavigation.tick(token);
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
            || profile == MaidBrainProfile.FOLLOW_CORE_COMPONENT && afterNavigation != null);
    }

    public boolean requiresAfterNativeNavigation() {
        return profile == MaidBrainProfile.FOLLOW_CORE_COMPONENT;
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
