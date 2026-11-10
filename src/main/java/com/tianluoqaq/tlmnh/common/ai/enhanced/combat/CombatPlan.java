package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;

/** 只提交首段意图，实际许可、物品及弹道由actor在世界边界重新检查 */
public final class CombatPlan {

    public enum Strategy {
        REACTION,
        PURE_DEFENSE,
        TASK,
        DEFENSIVE_RETURN,
        REOBSERVE
    }

    public enum StageState {
        NONE,
        PROPOSED,
        ACTIVE,
        WAIT_SERVICE,
        NATIVE_FAILURE,
        COMPLETE,
        INTERRUPTED
    }

    public final Strategy strategy;
    public final StageState stageState;
    public final long stageId, firstNativeAcceptanceAt, stageRetryAt;
    public final String stageReason;
    public final int stagePhase;
    public final Vec boundary;
    public final CombatMovementFact.Node movementNode;
    public final boolean normalProgressAdmitted, necessaryComparisonComplete;

    public enum RiskScope {
        COMPLETE_COMBAT,
        OBSERVED_ONLY_TOTAL_UNKNOWN
    }

    public final RiskScope riskScope;
    public final boolean reobservationComparisonComplete;
    public final CombatObservedRisk observedRisk;
    public final CombatMovementSegment noDoorSegment;
    public final CombatAlignmentProof alignmentProof;
    public final CombatConditionalEntryProof conditionalEntryProof;
    public final CombatPassiveDoorExitProof passiveDoorExitProof;

    public Double diagnosticTotalRisk() {
        return riskScope == RiskScope.COMPLETE_COMBAT && Double.isFinite(expectedRisk) ? expectedRisk : null;
    }

    public Double diagnosticCombatUtility() {
        return riskScope == RiskScope.COMPLETE_COMBAT && Double.isFinite(utility) ? utility : null;
    }

    public enum Macro {
        HOLD_ATTACK,
        PREPARE_WEAPON,
        SHIELD,
        STRAFE_LEFT,
        STRAFE_RIGHT,
        RETREAT,
        APPROACH,
        COVER_EDGE,
        JUMP_STRIKE,
        WAIT
    }

    public enum Result {
        RUNNING,
        FINISHED,
        WAIT_EQUIPMENT,
        WAIT_BUDGET,
        STALE,
        REJECTED,
        HELD
    }

    public final long decisionId, entityEpoch, observationEpoch, equipmentEpoch, observedAt, expiresAt;
    public final UUID targetId;
    public final int targetEntityId, weaponSlot;
    public final String weaponFingerprint, reason;
    public final MaidWeaponProfile.Family family;
    public final Macro macro;
    public final Vec destination, face;
    public final CombatTerrain.Route route;
    public final double utility, expectedDamage, expectedRisk;
    public final boolean simulated;
    public final CombatWorkQuota quota;
    public final CombatPlanner.Trace trace;

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota) {
        this(
            decisionId,
            snapshot,
            target,
            weapon,
            macro,
            destination,
            face,
            reason,
            utility,
            expectedDamage,
            expectedRisk,
            simulated,
            quota,
            null);
    }

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota,
        CombatTerrain.Route route) {
        this(
            decisionId,
            snapshot,
            target,
            weapon,
            macro,
            destination,
            face,
            reason,
            utility,
            expectedDamage,
            expectedRisk,
            simulated,
            quota,
            route,
            null);
    }

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota, CombatTerrain.Route route,
        CombatPlanner.Trace trace) {
        this(
            decisionId,
            snapshot,
            target,
            weapon,
            macro,
            destination,
            face,
            reason,
            utility,
            expectedDamage,
            expectedRisk,
            simulated,
            quota,
            route,
            trace,
            Strategy.REACTION,
            StageState.NONE,
            0,
            0,
            null,
            null,
            false,
            false,
            Long.MIN_VALUE,
            0,
            "");
    }

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota, CombatTerrain.Route route,
        CombatPlanner.Trace trace, Strategy strategy, StageState state, long stageId, int stagePhase, Vec boundary,
        CombatMovementFact.Node movementNode, boolean admitted, boolean comparisonComplete,
        long firstNativeAcceptanceAt, long stageRetryAt, String stageReason) {
        this(
            decisionId,
            snapshot,
            target,
            weapon,
            macro,
            destination,
            face,
            reason,
            utility,
            expectedDamage,
            expectedRisk,
            simulated,
            quota,
            route,
            trace,
            strategy,
            state,
            stageId,
            stagePhase,
            boundary,
            movementNode,
            admitted,
            comparisonComplete,
            firstNativeAcceptanceAt,
            stageRetryAt,
            stageReason,
            null,
            null);
    }

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota, CombatTerrain.Route route,
        CombatPlanner.Trace trace, Strategy strategy, StageState state, long stageId, int stagePhase, Vec boundary,
        CombatMovementFact.Node movementNode, boolean admitted, boolean comparisonComplete,
        long firstNativeAcceptanceAt, long stageRetryAt, String stageReason, CombatObservedRisk observedRisk,
        CombatMovementSegment noDoorSegment) {
        this(
            decisionId,
            snapshot,
            target,
            weapon,
            macro,
            destination,
            face,
            reason,
            utility,
            expectedDamage,
            expectedRisk,
            simulated,
            quota,
            route,
            trace,
            strategy,
            state,
            stageId,
            stagePhase,
            boundary,
            movementNode,
            admitted,
            comparisonComplete,
            firstNativeAcceptanceAt,
            stageRetryAt,
            stageReason,
            observedRisk,
            noDoorSegment,
            null);
    }

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota, CombatTerrain.Route route,
        CombatPlanner.Trace trace, Strategy strategy, StageState state, long stageId, int stagePhase, Vec boundary,
        CombatMovementFact.Node movementNode, boolean admitted, boolean comparisonComplete,
        long firstNativeAcceptanceAt, long stageRetryAt, String stageReason, CombatObservedRisk observedRisk,
        CombatMovementSegment noDoorSegment, CombatAlignmentProof alignmentProof) {
        this(
            decisionId,
            snapshot,
            target,
            weapon,
            macro,
            destination,
            face,
            reason,
            utility,
            expectedDamage,
            expectedRisk,
            simulated,
            quota,
            route,
            trace,
            strategy,
            state,
            stageId,
            stagePhase,
            boundary,
            movementNode,
            admitted,
            comparisonComplete,
            firstNativeAcceptanceAt,
            stageRetryAt,
            stageReason,
            observedRisk,
            noDoorSegment,
            alignmentProof,
            null);
    }

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota, CombatTerrain.Route route,
        CombatPlanner.Trace trace, Strategy strategy, StageState state, long stageId, int stagePhase, Vec boundary,
        CombatMovementFact.Node movementNode, boolean admitted, boolean comparisonComplete,
        long firstNativeAcceptanceAt, long stageRetryAt, String stageReason, CombatObservedRisk observedRisk,
        CombatMovementSegment noDoorSegment, CombatAlignmentProof alignmentProof,
        CombatConditionalEntryProof conditionalEntryProof) {
        this(
            decisionId,
            snapshot,
            target,
            weapon,
            macro,
            destination,
            face,
            reason,
            utility,
            expectedDamage,
            expectedRisk,
            simulated,
            quota,
            route,
            trace,
            strategy,
            state,
            stageId,
            stagePhase,
            boundary,
            movementNode,
            admitted,
            comparisonComplete,
            firstNativeAcceptanceAt,
            stageRetryAt,
            stageReason,
            observedRisk,
            noDoorSegment,
            alignmentProof,
            conditionalEntryProof,
            null);
    }

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota, CombatTerrain.Route route,
        CombatPlanner.Trace trace, Strategy strategy, StageState state, long stageId, int stagePhase, Vec boundary,
        CombatMovementFact.Node movementNode, boolean admitted, boolean comparisonComplete,
        long firstNativeAcceptanceAt, long stageRetryAt, String stageReason, CombatObservedRisk observedRisk,
        CombatMovementSegment noDoorSegment, CombatAlignmentProof alignmentProof,
        CombatConditionalEntryProof conditionalEntryProof, CombatPassiveDoorExitProof passiveDoorExitProof) {
        this.passiveDoorExitProof = passiveDoorExitProof;
        this.conditionalEntryProof = conditionalEntryProof;
        this.alignmentProof = alignmentProof;
        this.observedRisk = observedRisk;
        this.noDoorSegment = noDoorSegment;
        riskScope = strategy == Strategy.REOBSERVE ? RiskScope.OBSERVED_ONLY_TOTAL_UNKNOWN : RiskScope.COMPLETE_COMBAT;
        reobservationComparisonComplete = strategy == Strategy.REOBSERVE && observedRisk != null
            && observedRisk.complete;
        this.strategy = strategy;
        this.stageState = state;
        this.stageId = stageId;
        this.firstNativeAcceptanceAt = firstNativeAcceptanceAt;
        this.stageRetryAt = stageRetryAt;
        this.stageReason = stageReason;
        this.stagePhase = stagePhase;
        this.boundary = boundary;
        this.movementNode = movementNode;
        normalProgressAdmitted = admitted;
        necessaryComparisonComplete = comparisonComplete;
        this.trace = trace;
        this.route = route;
        this.decisionId = decisionId;
        entityEpoch = snapshot.entityEpoch;
        observationEpoch = snapshot.observationEpoch;
        equipmentEpoch = snapshot.equipmentEpoch;
        observedAt = snapshot.tick;
        expiresAt = snapshot.tick + CombatSnapshot.OBSERVATION_TTL;
        targetId = target == null ? null : target.id;
        targetEntityId = target == null ? -1 : target.entityId;
        weaponSlot = weapon == null ? -1 : weapon.slot;
        weaponFingerprint = weapon == null ? "" : weapon.fingerprint;
        family = weapon == null ? MaidWeaponProfile.Family.UNKNOWN : weapon.profile.family;
        this.macro = macro;
        this.destination = destination;
        this.face = face;
        this.reason = reason;
        this.utility = utility;
        this.expectedDamage = expectedDamage;
        this.expectedRisk = expectedRisk;
        this.simulated = simulated;
        this.quota = quota;
    }
}
