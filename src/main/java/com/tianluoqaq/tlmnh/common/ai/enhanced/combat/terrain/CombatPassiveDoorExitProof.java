package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.DoorCell;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;

/** 当前已占稳定已开单门的被动退出；不含World或任何开门许可 */
public final class CombatPassiveDoorExitProof {

    public final CombatMovementFact.Node node;
    public final Geometry geometry;
    public final Source source;
    public final double halfWidth, height;
    public final long admittedAt, serial, equipmentEpoch, lastSeenAt;
    public final String weapon;
    public final CombatMovementSegment segment;

    public CombatPassiveDoorExitProof(CombatMovementFact.Node node, CombatSnapshot s, Source source, String weapon,
        long serial) {
        var target = s.target(node.target);
        if (source == null || serial <= 0
            || !node.current(s.tick)
            || !s.bodyComplete
            || !s.self.grounded
            || s.self.stationary
            || s.geometry == null
            || !s.geometry.complete
            || !s.geometry.fresh(s.tick)
            || !Objects.equals(node.self, s.selfId)
            || node.entityEpoch != s.entityEpoch
            || node.observationEpoch != s.observationEpoch
            || target == null
            || target.visible
            || !target.fresh(s.tick)
            || !Objects.equals(node.target, s.selectedTarget)
            || node.targetEntityId != target.entityId
            || node.foot.distance(source.k) > CombatTerrainTuning.COMBAT_CONDITIONAL_SAME_FACTOR
            || node.role != CombatMovementFact.Role.ORDINARY)
            throw new IllegalArgumentException(CombatTerrainDiagnostics.MSG_CURRENT_ADMITTED_PASSIVE_DOOR_EXIT);
        this.node = node;
        geometry = s.geometry;
        this.source = source;
        halfWidth = s.self.halfWidth;
        height = s.self.height;
        admittedAt = s.tick;
        this.serial = serial;
        equipmentEpoch = s.equipmentEpoch;
        lastSeenAt = target.observedAt;
        this.weapon = weapon;
        segment = new CombatMovementSegment(node, s.self.position, geometry.observedAt);
        if (!segment.ordinaryAdjacent())
            throw new IllegalArgumentException(CombatTerrainDiagnostics.MSG_ADJACENT_PASSIVE_EXIT_REQUIRED);
    }

    public boolean fresh(long now) {
        return node.current(now) && geometry.complete
            && geometry.fresh(now)
            && admittedAt <= now
            && now - admittedAt <= CombatBudgetTuning.OBSERVATION_TTL
            && lastSeenAt <= now
            && now - lastSeenAt <= CombatTerrainTuning.HIDDEN_TTL;
    }

    public boolean sameEvidence(CombatPassiveDoorExitProof other) {
        return other != null && node.same(other.node)
            && geometry == other.geometry
            && source.same(other.source)
            && halfWidth == other.halfWidth
            && height == other.height
            && admittedAt == other.admittedAt
            && serial == other.serial
            && equipmentEpoch == other.equipmentEpoch
            && lastSeenAt == other.lastSeenAt
            && Objects.equals(weapon, other.weapon)
            && same(segment.from, other.segment.from);
    }

    public Movement movement(Vec actual, Vec dispatch, long now, CombatWorkQuota quota) {
        if (!fresh(now) || !segment.currentPosition(actual)
            || !source.contains(actual)
            || dispatch != null && !dispatchPoint(dispatch)) return null;
        int before = quota.used(Work.COLLISION);
        if (!body(actual, source.k, now, quota) || !body(source.k, actual, now, quota)) return null;
        if (dispatch != null && !same(dispatch, source.k)
            && (!body(actual, dispatch, now, quota) || !body(dispatch, actual, now, quota))) return null;
        return new Movement(this, actual, dispatch, now, quota.used(Work.COLLISION) - before);
    }

    private boolean dispatchPoint(Vec p) {
        return same(p, source.k) || Math
            .abs(p.x - (Math.floor(segment.from.x) + CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR))
            < CombatTerrainTuning.COMBAT_CONDITIONAL_SAME_FACTOR
            && Math.abs(p.z - (Math.floor(segment.from.z) + CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR))
                < CombatTerrainTuning.COMBAT_CONDITIONAL_SAME_FACTOR
            && Math.abs(p.y - segment.from.y) < CombatTerrainTuning.COMBAT_CONDITIONAL_SAME_FACTOR;
    }

    private boolean body(Vec a, Vec b, long now, CombatWorkQuota quota) {
        return CombatTerrain.passiveDoorStep(geometry, halfWidth, height, a, b, source, now, quota);
    }

    public Arrival arrival(Movement movement, Vec actual, long now) {
        return movement != null && movement.proof == this
            && movement.matches(actual, movement.dispatch, now)
            && fresh(now)
            && CombatMovementFact.sameStandingCell(actual, source.k)
            && source.outside(actual, halfWidth, height) ? new Arrival(this, actual, now, movement.work) : null;
    }

    public boolean doorPosition(int x, int y, int z) {
        return x == source.door.x && z == source.door.z && (y == source.door.y || y == source.door.y + 1);
    }

    private static boolean same(Vec a, Vec b) {
        return a == null ? b == null : b != null && a.x == b.x && a.y == b.y && a.z == b.z;
    }

    /** 只由本轮图的初始已占门格和其一个相邻全身退出节点产生 */
    public static final class Source {

        public final DoorCell door;
        public final Vec occupiedOrigin, k;

        public Source(DoorCell door, Vec occupiedOrigin, Vec k) {
            this.door = door;
            this.occupiedOrigin = occupiedOrigin;
            this.k = k;
        }

        public boolean same(Source other) {
            return other != null && door.sameDoor(other.door)
                && CombatPassiveDoorExitProof.same(occupiedOrigin, other.occupiedOrigin)
                && CombatPassiveDoorExitProof.same(k, other.k);
        }

        public boolean contains(Vec p) {
            return p != null && Double.isFinite(p.x + p.y + p.z)
                && p.x >= Math.min(occupiedOrigin.x, k.x) - CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR
                && p.x <= Math.max(occupiedOrigin.x, k.x) + CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR
                && p.z >= Math.min(occupiedOrigin.z, k.z) - CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR
                && p.z <= Math.max(occupiedOrigin.z, k.z) + CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR
                && p.y >= Math.min(occupiedOrigin.y, k.y) - CombatTerrainTuning.COMBAT_CONDITIONAL_SAME_FACTOR
                && p.y <= Math.max(occupiedOrigin.y, k.y) + CombatTerrainTuning.COMBAT_CONDITIONAL_SAME_FACTOR;
        }

        public boolean outside(Vec p, double width, double height) {
            return !occupies(door, p, width, height);
        }

        public static boolean occupies(DoorCell d, Vec p, double width, double height) {
            return d != null && p != null
                && Double.isFinite(p.x + p.y + p.z + width + height)
                && width > 0
                && height > 0
                && p.x + width > d.x
                && p.x - width < d.x + 1
                && p.z + width > d.z
                && p.z - width < d.z + 1
                && p.y + height > d.y
                && p.y < d.y + CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM;
        }
    }

    public static final class Movement {

        private final CombatPassiveDoorExitProof proof;
        public final Vec actual, dispatch;
        public final long tick;
        public final int work;

        private Movement(CombatPassiveDoorExitProof proof, Vec actual, Vec dispatch, long tick, int work) {
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
        public final Source source;
        public final Vec actual;
        public final long tick, geometryAt, serial;
        public final double halfWidth, height;
        public final int work;

        private Arrival(CombatPassiveDoorExitProof proof, Vec actual, long tick, int work) {
            node = proof.node;
            source = proof.source;
            this.actual = actual;
            this.tick = tick;
            geometryAt = proof.geometry.observedAt;
            serial = proof.serial;
            halfWidth = proof.halfWidth;
            height = proof.height;
            this.work = work;
        }

        public boolean matches(CombatMovementFact.Node expected, Vec point, long now, long expectedSerial) {
            return node.same(expected) && tick == now
                && same(actual, point)
                && serial == expectedSerial
                && serial > 0
                && geometryAt <= now
                && now - geometryAt <= CombatTerrainTuning.GEOMETRY_TTL
                && work > 0
                && CombatMovementFact.sameStandingCell(point, source.k)
                && source.outside(point, halfWidth, height);
        }
    }
}
