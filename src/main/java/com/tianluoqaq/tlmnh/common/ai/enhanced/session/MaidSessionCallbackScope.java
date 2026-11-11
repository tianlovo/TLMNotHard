package com.tianluoqaq.tlmnh.common.ai.enhanced.session;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;

/** 外部行为回调相位与帧有效性守卫；杜绝跨帧缓存视图读写或非 execute 阶段提交动作 */
public final class MaidSessionCallbackScope {

    public enum Phase {
        INACTIVE,
        OFFER,
        TRANSITION,
        EXECUTE
    }

    private Phase phase = Phase.INACTIVE;
    private MaidBehaviorId activeModule;
    private BooleanSupplier liveFrameGuard = () -> false;
    private long generation;

    public long enter(Phase nextPhase, MaidBehaviorId moduleId, BooleanSupplier frameGuard) {
        Objects.requireNonNull(nextPhase, "nextPhase");
        if (nextPhase == Phase.INACTIVE) throw new IllegalArgumentException("Cannot enter INACTIVE phase");
        this.phase = nextPhase;
        this.activeModule = Objects.requireNonNull(moduleId, "moduleId");
        this.liveFrameGuard = Objects.requireNonNull(frameGuard, "frameGuard");
        return ++generation;
    }

    public void exit(long expectedGeneration) {
        if (generation == expectedGeneration) {
            phase = Phase.INACTIVE;
            activeModule = null;
            liveFrameGuard = () -> false;
            generation++;
        }
    }

    public void invalidateAll() {
        phase = Phase.INACTIVE;
        activeModule = null;
        liveFrameGuard = () -> false;
        generation++;
    }

    public void invalidateModule(MaidBehaviorId moduleId) {
        if (Objects.equals(activeModule, moduleId)) {
            invalidateAll();
        }
    }

    public boolean isObservationCurrent(MaidBehaviorId moduleId) {
        return phase != Phase.INACTIVE && Objects.equals(activeModule, moduleId) && liveFrameGuard.getAsBoolean();
    }

    public void requireObservation(MaidBehaviorId moduleId) {
        if (!isObservationCurrent(moduleId))
            throw new IllegalStateException("Observation view is not active for module " + moduleId);
    }

    public void requireOffer(MaidBehaviorId moduleId) {
        if (phase != Phase.OFFER || !Objects.equals(activeModule, moduleId) || !liveFrameGuard.getAsBoolean())
            throw new IllegalStateException("Offer sink is only valid during active offer callback for " + moduleId);
    }

    public void requireExecute(MaidBehaviorId moduleId) {
        if (phase != Phase.EXECUTE || !Objects.equals(activeModule, moduleId) || !liveFrameGuard.getAsBoolean())
            throw new IllegalStateException(
                "Action and hand access are only permitted during active execute callback for " + moduleId);
    }
}
