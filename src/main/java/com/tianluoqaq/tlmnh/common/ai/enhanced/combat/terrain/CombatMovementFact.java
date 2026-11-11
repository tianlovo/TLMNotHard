package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import java.util.Objects;
import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;

/** 本战斗会话的原生导航事实；只传值，不向规划器泄露路径或世界对象 */
public final class CombatMovementFact {

    public enum Role {
        ORDINARY,
        SAME_CELL_ALIGNMENT,
        CONDITIONAL_ENTRY
    }

    public enum Outcome {
        JUNCTION_READY,
        INSTALLED,
        ARRIVED,
        WAIT_SERVICE,
        NATIVE_FAILURE
    }

    public static final class Node {

        public final long stage, index, entityEpoch, observationEpoch, submittedAt, expiresAt;
        public final UUID self, target;
        public final int targetEntityId;
        public final Vec foot;
        public final Role role;
        public final long parentStage, parentCursor;
        public final Vec anchor, parentNext;
        public final CombatConditionalEntryProof.DoorEdge doorEdge;

        public Node(long stage, long index, CombatSnapshot snapshot, Vec foot) {
            this(
                stage,
                index,
                snapshot,
                snapshot.selectedTarget,
                snapshot.target(snapshot.selectedTarget) == null ? CombatTerrainTuning.UNSET_OR_NOT_FOUND
                    : snapshot.target(snapshot.selectedTarget).entityId,
                foot);
        }

        public Node(long stage, long index, CombatSnapshot snapshot, UUID target, int targetEntityId, Vec foot) {
            this(
                stage,
                index,
                snapshot.entityEpoch,
                snapshot.observationEpoch,
                snapshot.tick,
                snapshot.tick + CombatBudgetTuning.OBSERVATION_TTL,
                snapshot.selfId,
                target,
                targetEntityId,
                foot);
        }

        private Node(long stage, long index, long entityEpoch, long observationEpoch, long submittedAt, long expiresAt,
            UUID self, UUID target, int targetEntityId, Vec foot) {
            this(
                stage,
                index,
                entityEpoch,
                observationEpoch,
                submittedAt,
                expiresAt,
                self,
                target,
                targetEntityId,
                foot,
                Role.ORDINARY,
                CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                null,
                null);
        }

        public static Node alignment(long auxiliaryId, long parentStage, long parentCursor, CombatSnapshot snapshot,
            UUID target, int targetEntityId, Vec anchor, Vec parentNext) {
            return new Node(
                auxiliaryId * CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM,
                CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP,
                snapshot.entityEpoch,
                snapshot.observationEpoch,
                snapshot.tick,
                snapshot.tick + CombatBudgetTuning.OBSERVATION_TTL,
                snapshot.selfId,
                target,
                targetEntityId,
                anchor,
                Role.SAME_CELL_ALIGNMENT,
                parentStage,
                parentCursor,
                anchor,
                parentNext);
        }

        private Node(long stage, long index, long entityEpoch, long observationEpoch, long submittedAt, long expiresAt,
            UUID self, UUID target, int targetEntityId, Vec foot, Role role, long parentStage, long parentCursor,
            Vec anchor, Vec parentNext) {
            this(
                stage,
                index,
                entityEpoch,
                observationEpoch,
                submittedAt,
                expiresAt,
                self,
                target,
                targetEntityId,
                foot,
                role,
                parentStage,
                parentCursor,
                anchor,
                parentNext,
                null);
        }

        public static Node conditionalEntry(long id, long parent, long cursor, CombatSnapshot s, UUID target,
            int targetEntityId, Vec k, Vec b, CombatConditionalEntryProof.DoorEdge edge) {
            return new Node(
                id * CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM,
                CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP,
                s.entityEpoch,
                s.observationEpoch,
                s.tick,
                s.tick + CombatBudgetTuning.OBSERVATION_TTL,
                s.selfId,
                target,
                targetEntityId,
                k,
                Role.CONDITIONAL_ENTRY,
                parent,
                cursor,
                k,
                b,
                edge);
        }

        private Node(long stage, long index, long entityEpoch, long observationEpoch, long submittedAt, long expiresAt,
            UUID self, UUID target, int targetEntityId, Vec foot, Role role, long parentStage, long parentCursor,
            Vec anchor, Vec parentNext, CombatConditionalEntryProof.DoorEdge edge) {
            doorEdge = edge;
            this.role = role;
            this.parentStage = parentStage;
            this.parentCursor = parentCursor;
            this.anchor = anchor;
            this.parentNext = parentNext;
            this.stage = stage;
            this.index = index;
            this.entityEpoch = entityEpoch;
            this.observationEpoch = observationEpoch;
            this.submittedAt = submittedAt;
            this.expiresAt = expiresAt;
            this.self = self;
            this.target = target;
            this.targetEntityId = targetEntityId;
            this.foot = foot;
        }

        public boolean same(Node other) {
            return other != null && role == other.role
                && (doorEdge == null ? other.doorEdge == null : doorEdge.same(other.doorEdge))
                && parentStage == other.parentStage
                && parentCursor == other.parentCursor
                && samePoint(anchor, other.anchor)
                && samePoint(parentNext, other.parentNext)
                && stage == other.stage
                && index == other.index
                && entityEpoch == other.entityEpoch
                && observationEpoch == other.observationEpoch
                && Objects.equals(self, other.self)
                && Objects.equals(target, other.target)
                && targetEntityId == other.targetEntityId
                && foot != null
                && other.foot != null
                && foot.distance(other.foot) < CombatTerrainTuning.NODE_IDENTITY_EPSILON;
        }

        private static boolean samePoint(Vec a, Vec b) {
            return a == null ? b == null : b != null && a.distance(b) < CombatTerrainTuning.NODE_IDENTITY_EPSILON;
        }

        public boolean current(long now) {
            return stage > 0 && index > 0
                && targetEntityId >= 0
                && self != null
                && target != null
                && foot != null
                && (role == Role.ORDINARY || parentStage > 0
                    && (role == Role.SAME_CELL_ALIGNMENT
                        && parentCursor > CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
                        || role == Role.CONDITIONAL_ENTRY
                            && parentCursor >= CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
                            && doorEdge != null
                            && doorEdge.entry(anchor, parentNext))
                    && anchor != null
                    && parentNext != null
                    && foot.distance(anchor) < CombatTerrainTuning.NODE_IDENTITY_EPSILON
                    && Double.isFinite(anchor.x + anchor.y + anchor.z + parentNext.x + parentNext.y + parentNext.z))
                && Double.isFinite(foot.x + foot.y + foot.z)
                && submittedAt <= now
                && now <= expiresAt;
        }
    }

    public final Node node;
    public final Outcome outcome;
    public final long tick, pathIdentity, retryAt;
    public final Vec actualFoot;
    public final boolean grounded, inArea, nativeTailMatches;
    public final String reason;
    public final int nativePathIndex, nativePathLength;
    public final Vec nativeTail;
    public final CombatAlignmentProof.Junction junction;
    public final CombatConditionalEntryProof.Arrival entryArrival;
    public final CombatPassiveDoorExitProof.Arrival passiveDoorExitArrival;

    public CombatMovementFact(Node node, Outcome outcome, long tick, long pathIdentity, long retryAt, Vec actualFoot,
        boolean grounded, boolean inArea, boolean nativeTailMatches) {
        this(
            node,
            outcome,
            tick,
            pathIdentity,
            retryAt,
            actualFoot,
            grounded,
            inArea,
            nativeTailMatches,
            outcome.name(),
            CombatTerrainTuning.UNSET_OR_NOT_FOUND,
            CombatTerrainTuning.UNSET_OR_NOT_FOUND,
            null);
    }

    public CombatMovementFact(Node node, Outcome outcome, long tick, long pathIdentity, long retryAt, Vec actualFoot,
        boolean grounded, boolean inArea, boolean nativeTailMatches, String reason, int nativePathIndex,
        int nativePathLength, Vec nativeTail) {
        this(
            node,
            outcome,
            tick,
            pathIdentity,
            retryAt,
            actualFoot,
            grounded,
            inArea,
            nativeTailMatches,
            reason,
            nativePathIndex,
            nativePathLength,
            nativeTail,
            null);
    }

    public CombatMovementFact(Node node, Outcome outcome, long tick, long pathIdentity, long retryAt, Vec actualFoot,
        boolean grounded, boolean inArea, boolean nativeTailMatches, String reason, int nativePathIndex,
        int nativePathLength, Vec nativeTail, CombatAlignmentProof.Junction junction) {
        this(
            node,
            outcome,
            tick,
            pathIdentity,
            retryAt,
            actualFoot,
            grounded,
            inArea,
            nativeTailMatches,
            reason,
            nativePathIndex,
            nativePathLength,
            nativeTail,
            junction,
            null);
    }

    public CombatMovementFact(Node node, Outcome outcome, long tick, long pathIdentity, long retryAt, Vec actualFoot,
        boolean grounded, boolean inArea, boolean nativeTailMatches, String reason, int nativePathIndex,
        int nativePathLength, Vec nativeTail, CombatAlignmentProof.Junction junction,
        CombatConditionalEntryProof.Arrival entryArrival) {
        this(
            node,
            outcome,
            tick,
            pathIdentity,
            retryAt,
            actualFoot,
            grounded,
            inArea,
            nativeTailMatches,
            reason,
            nativePathIndex,
            nativePathLength,
            nativeTail,
            junction,
            entryArrival,
            null);
    }

    public CombatMovementFact(Node node, Outcome outcome, long tick, long pathIdentity, long retryAt, Vec actualFoot,
        boolean grounded, boolean inArea, boolean nativeTailMatches, String reason, int nativePathIndex,
        int nativePathLength, Vec nativeTail, CombatAlignmentProof.Junction junction,
        CombatConditionalEntryProof.Arrival entryArrival, CombatPassiveDoorExitProof.Arrival passiveDoorExitArrival) {
        this.passiveDoorExitArrival = passiveDoorExitArrival;
        this.entryArrival = entryArrival;
        this.junction = junction;
        this.reason = reason;
        this.nativePathIndex = nativePathIndex;
        this.nativePathLength = nativePathLength;
        this.nativeTail = nativeTail;
        this.node = node;
        this.outcome = outcome;
        this.tick = tick;
        this.pathIdentity = pathIdentity;
        this.retryAt = retryAt;
        this.actualFoot = actualFoot;
        this.grounded = grounded;
        this.inArea = inArea;
        this.nativeTailMatches = nativeTailMatches;
    }

    public boolean belongs(Node expected, long now) {
        return node != null && node.same(expected)
            && tick >= node.submittedAt
            && tick <= now
            && now - tick <= CombatTerrainTuning.FEEDBACK_MAX_AGE
            && node.current(tick);
    }

    public boolean completed(Node expected, long now) {
        return belongs(expected, now) && node.role == Role.ORDINARY
            && outcome == Outcome.ARRIVED
            && pathIdentity > 0
            && nativeTailMatches
            && grounded
            && inArea
            && actualFoot != null
            && sameStandingCell(actualFoot, node.foot);
    }

    public boolean junctionReady(Node expected, long now) {
        return belongs(expected, now) && node.role == Role.SAME_CELL_ALIGNMENT
            && outcome == Outcome.JUNCTION_READY
            && pathIdentity > 0
            && grounded
            && inArea
            && nativeTailMatches
            && junction != null
            && junction.geometryAt <= now
            && now - junction.geometryAt <= CombatTerrainTuning.GEOMETRY_TTL
            && junction.matches(node, actualFoot, tick)
            && sameStandingCell(actualFoot, node.anchor);
    }

    public boolean entryArrived(Node expected, long now) {
        return belongs(expected, now) && node.role == Role.CONDITIONAL_ENTRY
            && outcome == Outcome.ARRIVED
            && pathIdentity > 0
            && nativeTailMatches
            && grounded
            && inArea
            && entryArrival != null
            && nativePathLength >= CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
            && nativePathLength <= CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM
            && nativePathIndex == nativePathLength - 1
            && entryArrival.matches(node, actualFoot, tick);
    }

    public boolean passiveExited(Node expected, long now, long serial) {
        return belongs(expected, now) && outcome == Outcome.ARRIVED
            && pathIdentity > 0
            && nativeTailMatches
            && grounded
            && inArea
            && nativePathLength >= CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
            && nativePathLength <= CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM
            && nativePathIndex == nativePathLength - 1
            && passiveDoorExitArrival != null
            && passiveDoorExitArrival.matches(expected, actualFoot, tick, serial);
    }

    /** 导航先记录的真实完成/失败在随后撤路径时不能被服务等待覆盖 */
    public boolean terminalFor(Node expected, long now) {
        return belongs(expected, now) && (outcome == Outcome.NATIVE_FAILURE || completed(expected, now)
            || junctionReady(expected, now)
            || entryArrived(expected, now));
    }

    /** 原生同格到达与原已证支撑足点共同成立；半砖不能靠round(y)冒充完成 */
    public static boolean sameStandingCell(Vec actual, Vec desired) {
        return actual != null && desired != null
            && Double.isFinite(actual.x + actual.y + actual.z)
            && Math.floor(actual.x) == Math.floor(desired.x)
            && Math.floor(actual.z) == Math.floor(desired.z)
            && Math.abs(actual.y - desired.y) <= CombatTerrainTuning.STANDING_EPSILON;
    }
}
