package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;

/** 已完整比较的单普通边门前恢复；几何门格不携带任何开门许可 */
public final class CombatConditionalEntryProof {

    public final CombatMovementFact.Node node;
    public final Geometry geometry;
    public final double halfWidth, height;
    public final long admittedAt, admissionSerial, equipmentEpoch;
    public final String weapon;
    public final CombatMovementSegment segment;

    public CombatConditionalEntryProof(CombatMovementFact.Node node, CombatSnapshot s, String weapon, long serial) {
        var target = s.target(node.target);
        if (node.role != CombatMovementFact.Role.CONDITIONAL_ENTRY || !node.current(s.tick)
            || !s.complete
            || !s.bodyComplete
            || !s.friendsComplete
            || !s.self.grounded
            || s.self.stationary
            || s.geometry == null
            || !s.geometry.complete
            || !s.geometry.fresh(s.tick)
            || !Objects.equals(node.self, s.selfId)
            || node.entityEpoch != s.entityEpoch
            || node.observationEpoch != s.observationEpoch
            || target == null
            || !target.visible
            || target.observedAt != s.tick
            || target.entityId != node.targetEntityId
            || !Objects.equals(s.selectedTarget, node.target)
            || serial <= 0) throw new IllegalArgumentException("Complete admitted conditional entry required");
        this.node = node;
        geometry = s.geometry;
        halfWidth = s.self.halfWidth;
        height = s.self.height;
        admittedAt = s.tick;
        admissionSerial = serial;
        equipmentEpoch = s.equipmentEpoch;
        this.weapon = weapon;
        segment = new CombatMovementSegment(node, s.self.position, geometry.observedAt);
        if (!segment.ordinaryAdjacent()) throw new IllegalArgumentException("Single ordinary entry edge required");
    }

    public boolean fresh(long now) {
        return node.current(now) && geometry != null
            && geometry.complete
            && geometry.fresh(now)
            && admittedAt <= now
            && now - admittedAt <= CombatBudgetTuning.OBSERVATION_TTL;
    }

    public boolean sameEvidence(CombatConditionalEntryProof other) {
        return other != null && node.same(other.node)
            && geometry == other.geometry
            && halfWidth == other.halfWidth
            && height == other.height
            && admittedAt == other.admittedAt
            && admissionSerial == other.admissionSerial
            && equipmentEpoch == other.equipmentEpoch
            && Objects.equals(weapon, other.weapon)
            && same(segment.from, other.segment.from);
    }

    /** dispatch=null仅核当前普通首段；真实native目的点必须另行精确匹配并付费 */
    public Movement movement(Vec actual, Vec dispatch, long now, CombatWorkQuota quota) {
        if (!fresh(now) || !segment.currentPosition(actual) || dispatch != null && !dispatchPoint(dispatch))
            return null;
        int before = quota.used(Work.COLLISION);
        if (!body(actual, node.anchor, now, quota) || !body(node.anchor, actual, now, quota)) return null;
        if (dispatch != null && !same(dispatch, node.anchor)
            && (!body(actual, dispatch, now, quota) || !body(dispatch, actual, now, quota))) return null;
        return new Movement(this, actual, dispatch, now, quota.used(Work.COLLISION) - before);
    }

    private boolean dispatchPoint(Vec p) {
        return same(p, node.anchor) || Math.abs(p.x - (Math.floor(segment.from.x) + .5)) < .0001
            && Math.abs(p.z - (Math.floor(segment.from.z) + .5)) < .0001
            && Math.abs(p.y - segment.from.y) < .0001;
    }

    private boolean body(Vec a, Vec b, long now, CombatWorkQuota quota) {
        return CombatTerrain.alignmentBodyStep(geometry, halfWidth, height, a, b, now, quota);
    }

    public Arrival arrival(Movement movement, Vec actual, long now) {
        return movement != null && movement.proof == this
            && movement.matches(actual, movement.dispatch, now)
            && fresh(now)
            && handoff(node, actual) ? new Arrival(this, actual, now, movement.work) : null;
    }

    public static boolean handoff(CombatMovementFact.Node node, Vec actual) {
        return node != null && node.role == CombatMovementFact.Role.CONDITIONAL_ENTRY
            && node.doorEdge != null
            && CombatMovementFact.sameStandingCell(actual, node.anchor)
            && node.doorEdge.handoff(actual, node.parentNext);
    }

    private static boolean same(Vec a, Vec b) {
        return a == null ? b == null : b != null && a.x == b.x && a.y == b.y && a.z == b.z;
    }

    public static final class DoorEdge {

        public final int x, y, z, entranceX, entranceZ, exitX, exitZ, dx, dz;

        private DoorEdge(Vec a, Vec b) {
            entranceX = (int) Math.floor(a.x);
            entranceZ = (int) Math.floor(a.z);
            exitX = (int) Math.floor(b.x);
            exitZ = (int) Math.floor(b.z);
            dx = Integer.signum(exitX - entranceX);
            dz = Integer.signum(exitZ - entranceZ);
            x = (int) Math.floor((a.x + b.x) * .5);
            y = (int) Math.floor(a.y);
            z = (int) Math.floor((a.z + b.z) * .5);
        }

        public static DoorEdge from(Vec a, Vec b) {
            if (a == null || b == null
                || !Double.isFinite(a.x + a.y + a.z + b.x + b.y + b.z)
                || Math.abs(a.y - b.y) > .500001) return null;
            int dx = (int) Math.floor(b.x) - (int) Math.floor(a.x), dz = (int) Math.floor(b.z) - (int) Math.floor(a.z);
            if (!(Math.abs(dx) == 2 && dz == 0 || dx == 0 && Math.abs(dz) == 2)) return null;
            var edge = new DoorEdge(a, b);
            return edge.x == edge.entranceX + edge.dx && edge.z == edge.entranceZ + edge.dz ? edge : null;
        }

        public boolean same(DoorEdge other) {
            return other != null && x == other.x
                && y == other.y
                && z == other.z
                && entranceX == other.entranceX
                && entranceZ == other.entranceZ
                && exitX == other.exitX
                && exitZ == other.exitZ
                && dx == other.dx
                && dz == other.dz;
        }

        public boolean entry(Vec k, Vec b) {
            return k != null && b != null
                && (int) Math.floor(k.x) == entranceX
                && (int) Math.floor(k.z) == entranceZ
                && (int) Math.floor(k.y) == y
                && (int) Math.floor(b.x) == exitX
                && (int) Math.floor(b.z) == exitZ
                && same(from(k, b));
        }

        public boolean handoff(Vec actual, Vec b) {
            if (actual == null || b == null) return false;
            double distance = actual.distance(b);
            return Double.isFinite(distance) && distance >= 1.5
                && distance <= CombatTerrain.continuationDistance()
                && (int) Math.floor((actual.x + b.x) * .5) == x
                && (int) Math.floor(actual.y) == y
                && (int) Math.floor((actual.z + b.z) * .5) == z;
        }
    }

    public static final class Movement {

        private final CombatConditionalEntryProof proof;
        public final Vec actual, dispatch;
        public final long tick;
        public final int work;

        private Movement(CombatConditionalEntryProof proof, Vec actual, Vec dispatch, long tick, int work) {
            this.proof = proof;
            this.actual = actual;
            this.dispatch = dispatch;
            this.tick = tick;
            this.work = work;
        }

        public boolean matches(Vec point, Vec destination, long now) {
            return tick == now && same(actual, point) && same(dispatch, destination);
        }
    }

    public static final class Arrival {

        public final CombatMovementFact.Node node;
        public final Vec actual;
        public final long tick, geometryAt, admissionSerial;
        public final double halfWidth, height;
        public final int work;

        private Arrival(CombatConditionalEntryProof proof, Vec actual, long tick, int work) {
            node = proof.node;
            this.actual = actual;
            this.tick = tick;
            geometryAt = proof.geometry.observedAt;
            admissionSerial = proof.admissionSerial;
            halfWidth = proof.halfWidth;
            height = proof.height;
            this.work = work;
        }

        public boolean matches(CombatMovementFact.Node expected, Vec point, long now) {
            return node.same(expected) && tick == now
                && same(actual, point)
                && geometryAt <= now
                && now - geometryAt <= CombatSnapshot.GEOMETRY_TTL
                && admissionSerial > 0
                && work > 0
                && handoff(node, point);
        }
    }
}
