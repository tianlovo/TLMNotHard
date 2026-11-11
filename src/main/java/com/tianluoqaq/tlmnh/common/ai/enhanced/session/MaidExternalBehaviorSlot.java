package com.tianluoqaq.tlmnh.common.ai.enhanced.session;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.nbt.NBTTagCompound;

import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorInitContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorModule;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorProvider;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorStage;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidBehaviorExtensionData;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;

/** 单女仆外部扩展模块隔离槽；统一承接生命周期异常熔断、持久读写隔离与手部租约回收 */
public final class MaidExternalBehaviorSlot implements MaidBehaviorExtensionData.ModulePersistentAdapter {

    public interface ResourceCleanup {

        void releaseResources();
    }

    private final UUID maidId;
    private final MaidBehaviorProvider provider;
    private final MaidBehaviorId id;
    private final MaidBehaviorStage stage;
    private final MaidSessionCallbackScope scope;
    private final MaidExternalOfferSink offerSink;
    private final MaidBehaviorContext context;
    private final ResourceCleanup resourceCleanup;
    private MaidBehaviorModule instance;
    private boolean faulted;
    private boolean persistenceBlocked;
    private Throwable lastFault;

    public MaidExternalBehaviorSlot(UUID maidId, int dimensionId, MaidBehaviorProvider provider,
        MaidSessionCallbackScope scope, MaidBehaviorContext context, ResourceCleanup resourceCleanup) {
        this.maidId = Objects.requireNonNull(maidId, "maidId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.id = Objects.requireNonNull(provider.id(), "provider.id");
        this.stage = Objects.requireNonNull(provider.stage(), "provider.stage");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.offerSink = new MaidExternalOfferSink(id, scope);
        this.context = Objects.requireNonNull(context, "context");
        this.resourceCleanup = Objects.requireNonNull(resourceCleanup, "resourceCleanup");
        initializeInstance(dimensionId);
    }

    private void initializeInstance(int dimensionId) {
        try {
            var created = provider.create(new MaidBehaviorInitContext() {

                @Override
                public UUID maidId() {
                    return maidId;
                }

                @Override
                public int dimensionId() {
                    return dimensionId;
                }
            });
            if (created == null || !id.equals(created.id()) || stage != created.stage()) {
                fault("create", new IllegalStateException("External module metadata mismatch for provider " + id));
                return;
            }
            instance = created;
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            fault("create", failure);
        }
    }

    @Override
    public MaidBehaviorId id() {
        return id;
    }

    public MaidBehaviorStage stage() {
        return stage;
    }

    @Override
    public boolean isFaulted() {
        return faulted;
    }

    @Override
    public boolean isPersistencePaused() {
        return persistenceBlocked;
    }

    public boolean isHealthy() {
        return !faulted && instance != null;
    }

    public Throwable lastFault() {
        return lastFault;
    }

    public void loadPersistentData(NBTTagCompound tlmnhExtension) {
        if (!isHealthy()) return;
        var slice = MaidBehaviorExtensionData.extractModuleTag(tlmnhExtension, id);
        if (slice.blocked) {
            persistenceBlocked = true;
            TLMNotHard.LOG.warn("Behavior module {} persistent read paused on maid {}: {}", id, maidId, slice.reason);
            return;
        }
        persistenceBlocked = false;
        try {
            instance.readPersistentData(slice.tag);
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            fault("readPersistentData", failure);
        }
    }

    @Override
    public void writePersistentData(NBTTagCompound candidate) {
        if (!isHealthy() || persistenceBlocked) return;
        instance.writePersistentData(candidate);
    }

    @Override
    public void onPersistenceFault(Throwable cause) {
        fault("writePersistentData", cause);
    }

    public void onAsyncCallbackFault(String phaseName, Throwable cause) {
        fault(phaseName, cause);
    }

    public void offer(MaidIntentFrame frame, BooleanSupplier liveGuard) {
        if (!isHealthy()) return;
        long ticket = scope.enter(MaidSessionCallbackScope.Phase.OFFER, id, liveGuard);
        offerSink.bindFrame(frame, action -> executeSelected(action, liveGuard));
        try {
            instance.offer(context, offerSink);
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            frame.revokeModuleOffers(id);
            fault("offer", failure);
        } finally {
            offerSink.clearFrame();
            scope.exit(ticket);
        }
    }

    public void onTargetChanged(String previousTarget, String nextTarget, BooleanSupplier liveGuard) {
        if (!isHealthy()) return;
        long ticket = scope.enter(MaidSessionCallbackScope.Phase.TRANSITION, id, liveGuard);
        try {
            instance.onTargetChanged(context, previousTarget, nextTarget);
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            fault("onTargetChanged", failure);
        } finally {
            scope.exit(ticket);
        }
    }

    public void onDeselected(TransitionReason reason, BooleanSupplier liveGuard) {
        try {
            if (!isHealthy()) return;
            long ticket = scope.enter(MaidSessionCallbackScope.Phase.TRANSITION, id, liveGuard);
            try {
                instance.onDeselected(context, reason);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                fault("onDeselected", failure);
            } finally {
                scope.exit(ticket);
            }
        } finally {
            resourceCleanup.releaseResources();
        }
    }

    private void executeSelected(Runnable boundAction, BooleanSupplier liveGuard) {
        if (!isHealthy()) return;
        long ticket = scope.enter(MaidSessionCallbackScope.Phase.EXECUTE, id, liveGuard);
        try {
            boundAction.run();
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            fault("execute", failure);
        } finally {
            scope.exit(ticket);
        }
    }

    public void cancel() {
        try {
            if (!isHealthy()) return;
            try {
                instance.cancel();
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                fault("cancel", failure);
            }
        } finally {
            resourceCleanup.releaseResources();
        }
    }

    private void fault(String phaseName, Throwable cause) {
        boolean firstFault = !faulted;
        faulted = true;
        lastFault = cause;
        scope.invalidateModule(id);
        try {
            resourceCleanup.releaseResources();
        } catch (RuntimeException | LinkageError | AssertionError cleanupError) {
            if (cleanupError != cause) cause.addSuppressed(cleanupError);
        }
        if (firstFault) {
            TLMNotHard.LOG.error(
                "External maid behavior module {} faulted during {} on maid {} and was isolated",
                id,
                phaseName,
                maidId,
                cause);
        }
    }
}
