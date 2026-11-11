package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;

/** 一个已完整比较准入的同格辅助段；只有不可变几何与身体值，不持有世界或原生路径 */
public final class CombatAlignmentProof {

    public final CombatMovementFact.Node node;
    public final Geometry geometry;
    public final double halfWidth, height;
    public final long admittedAt, equipmentEpoch;
    public final String weapon;
    public final CombatMovementSegment segment;

    public CombatAlignmentProof(CombatMovementFact.Node node, CombatSnapshot s, String weapon) {
        var target = s.target(node.target);
        if (node.role != CombatMovementFact.Role.SAME_CELL_ALIGNMENT || !s.complete
            || !s.bodyComplete
            || target == null
            || !target.visible
            || target.observedAt != s.tick
            || !node.target.equals(s.selectedTarget))
            throw new IllegalArgumentException("Complete visible parent alignment required");
        this.node = node;
        geometry = s.geometry;
        halfWidth = s.self.halfWidth;
        height = s.self.height;
        admittedAt = s.tick;
        equipmentEpoch = s.equipmentEpoch;
        this.weapon = weapon;
        segment = new CombatMovementSegment(node, s.self.position, geometry.observedAt);
    }

    public boolean fresh(long now) {
        return node.current(now) && geometry != null
            && geometry.complete
            && geometry.fresh(now)
            && admittedAt <= now
            && now - admittedAt <= CombatBudgetTuning.OBSERVATION_TTL;
    }

    public boolean sameEvidence(CombatAlignmentProof other) {
        return other != null && node.same(other.node)
            && geometry == other.geometry
            && geometry.observedAt == other.geometry.observedAt
            && halfWidth == other.halfWidth
            && height == other.height
            && admittedAt == other.admittedAt
            && equipmentEpoch == other.equipmentEpoch
            && Objects.equals(weapon, other.weapon);
    }

    public Movement movement(Vec actual, long now, CombatWorkQuota quota) {
        if (!fresh(now) || !CombatMovementFact.sameStandingCell(actual, node.anchor)) return null;
        int before = quota.used(Work.COLLISION);
        if (!body(actual, node.anchor, now, quota) || !body(node.anchor, actual, now, quota)) return null;
        return new Movement(this, actual, now, quota.used(Work.COLLISION) - before);
    }

    private boolean body(Vec from, Vec to, long now, CombatWorkQuota quota) {
        return CombatTerrain.alignmentBodyStep(geometry, halfWidth, height, from, to, now, quota);
    }

    public Junction junction(Movement movement, Vec actual, long now, CombatWorkQuota quota) {
        if (movement == null || movement.proof != this || !movement.matches(actual, now) || !fresh(now)) return null;
        int before = quota.used(Work.COLLISION);
        if (!body(actual, node.parentNext, now, quota) || !body(node.parentNext, actual, now, quota)) return null;
        return new Junction(this, actual, now, movement.work + quota.used(Work.COLLISION) - before);
    }

    public static final class Movement {

        private final CombatAlignmentProof proof;
        public final Vec actual;
        public final long tick;
        public final int work;

        private Movement(CombatAlignmentProof proof, Vec actual, long tick, int work) {
            this.proof = proof;
            this.actual = actual;
            this.tick = tick;
            this.work = work;
        }

        public boolean matches(Vec point, long now) {
            return tick == now && point != null && actual.x == point.x && actual.y == point.y && actual.z == point.z;
        }
    }

    public static final class Junction {

        public final CombatMovementFact.Node node;
        public final Vec actual;
        public final long tick, geometryAt;
        public final double halfWidth, height;
        public final int work;

        private Junction(CombatAlignmentProof proof, Vec actual, long tick, int work) {
            node = proof.node;
            this.actual = actual;
            this.tick = tick;
            geometryAt = proof.geometry.observedAt;
            halfWidth = proof.halfWidth;
            height = proof.height;
            this.work = work;
        }

        public boolean matches(CombatMovementFact.Node expected, Vec point, long now) {
            return node.same(expected) && tick == now
                && geometryAt <= now
                && now - geometryAt <= CombatSnapshot.GEOMETRY_TTL
                && point != null
                && actual.x == point.x
                && actual.y == point.y
                && actual.z == point.z
                && work > 0;
        }
    }
}
