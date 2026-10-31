package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 唯一策略执行壳，当前仅运行旧原型，所有调用须来自所属世界的服务端线程 */
public final class MaidPolicyRuntime {

    public enum TickResult {
        COMPLETED,
        UNAVAILABLE,
        FAULTED,
        REFUSED_REENTRANT,
        INVALIDATED,
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
        REENTRANT_CALL,
        CALLBACK_FAILURE
    }

    public interface LegacyActions {

        boolean tickCombat();

        boolean tickLife(boolean permitted);

        void tickFollow();

        /** 取消各动作持有的资源；一项失败时仍应尝试其余清理，再汇总异常 */
        void cancelAll();
    }

    private final LegacyActions actions;
    private final MaidPolicyContext context;
    private MaidBrainProfile profile = MaidBrainProfile.LEGACY_PROTOTYPE;
    private InvalidationReason lastInvalidationReason = InvalidationReason.INITIALIZED;
    private boolean dispatching, changing, faulted, retired;

    public MaidPolicyRuntime(UUID maidId, LegacyActions actions) {
        context = new MaidPolicyContext(maidId);
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /** lifePermission 保持惰性；战斗占用时仍推进一次不被许可的生活清理 */
    public TickResult tick(BooleanSupplier lifePermission) {
        if (retired) return TickResult.RETIRED;
        Objects.requireNonNull(lifePermission, "lifePermission");
        if (dispatching || changing) {
            refuseReentry();
            return retired ? TickResult.RETIRED : TickResult.REFUSED_REENTRANT;
        }
        if (faulted) return TickResult.FAULTED;
        if (!hasImplementation(profile)) return TickResult.UNAVAILABLE;
        dispatching = true;
        var token = context.getToken();
        try {
            boolean fighting = actions.tickCombat();
            if (!context.isCurrent(token)) return invalidatedTickResult();
            boolean permitted = !fighting && lifePermission.getAsBoolean();
            if (!context.isCurrent(token)) return invalidatedTickResult();
            boolean living = actions.tickLife(permitted);
            if (!context.isCurrent(token)) return invalidatedTickResult();
            if (!fighting && !living) {
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
            actions.cancelAll();
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

    private static boolean hasImplementation(MaidBrainProfile profile) {
        return profile == MaidBrainProfile.LEGACY_PROTOTYPE && profile.isAvailable();
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
