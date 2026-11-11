package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan;

import java.util.Objects;
import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatMovementFact;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatMovementSegment;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatObservedRisk;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatPassiveDoorExitProof;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.ThreatEstimator;

/** 战斗会话内的可中断身体复视，不承诺隐藏总风险、攻击或任务完成 */
final class CombatReobservation {

    final long id, parentId, entityEpoch, observationEpoch, lastSeenAt;
    final UUID self, target;
    final int targetEntityId;
    final Vec lastSeen;
    CombatTerrain.Route route, proof;
    int cursor = 1;
    long firstAccepted = Long.MIN_VALUE, retryAt;
    CombatPlan.StageState state = CombatPlan.StageState.PROPOSED;
    String reason = "NOT_INSTALLED", threats;
    CombatObservedRisk comparison;
    int graphVisited, graphWork;
    CombatPassiveDoorExitProof passiveExitProof;
    long passiveSerial;

    CombatReobservation(long id, long parentId, CombatSnapshot snapshot, Target selected) {
        this.id = id;
        this.parentId = parentId;
        self = snapshot.selfId;
        target = selected.id;
        targetEntityId = selected.entityId;
        entityEpoch = snapshot.entityEpoch;
        observationEpoch = snapshot.observationEpoch;
        lastSeenAt = selected.observedAt;
        lastSeen = selected.position;
    }

    boolean matches(CombatSnapshot s, Target t) {
        return t != null && !t.visible
            && t.fresh(s.tick)
            && Objects.equals(self, s.selfId)
            && Objects.equals(target, t.id)
            && targetEntityId == t.entityId
            && entityEpoch == s.entityEpoch
            && observationEpoch == s.observationEpoch
            && lastSeenAt == t.observedAt
            && lastSeen.distance(t.position) < .0001;
    }

    CombatMovementFact.Node node(CombatSnapshot s) {
        return route == null ? null
            : new CombatMovementFact.Node(id * 2, cursor, s, target, targetEntityId, route.next(cursor));
    }

    void synchronize(CombatSnapshot s) {
        CombatMovementFact.Node n = node(s);
        CombatMovementFact f = s.movementFact;
        if (f != null && n != null && f.belongs(n, s.tick)) {
            if (route.passiveExit != null ? f.passiveExited(n, s.tick, passiveSerial) : f.completed(n, s.tick)) {
                passiveExitProof = null;
                if (firstAccepted == Long.MIN_VALUE) firstAccepted = f.tick;
                cursor++;
                reason = "REOBSERVATION_NATIVE_NODE_COMPLETE";
                state = cursor >= route.nodes.size() ? CombatPlan.StageState.COMPLETE : CombatPlan.StageState.ACTIVE;
            } else if (f.outcome == CombatMovementFact.Outcome.INSTALLED && f.pathIdentity > 0 && f.inArea) {
                if (firstAccepted == Long.MIN_VALUE) firstAccepted = f.tick;
                state = CombatPlan.StageState.ACTIVE;
            } else if (f.outcome == CombatMovementFact.Outcome.NATIVE_FAILURE) {
                state = CombatPlan.StageState.NATIVE_FAILURE;
                retryAt = f.retryAt;
                reason = f.reason;
            } else if (f.outcome == CombatMovementFact.Outcome.WAIT_SERVICE
                && state != CombatPlan.StageState.NATIVE_FAILURE) {
                    state = CombatPlan.StageState.WAIT_SERVICE;
                    reason = f.reason;
                    if (f.reason != null && f.reason.startsWith("REOBSERVATION_")) {
                        proof = null;
                        comparison = null;
                    }
                }
        }
        if (state == CombatPlan.StageState.NATIVE_FAILURE && s.tick >= retryAt) {
            state = CombatPlan.StageState.PROPOSED;
            reason = "ORIGINAL_BACKOFF_ELAPSED";
        }
    }

    void waitService(String why) {
        if (state != CombatPlan.StageState.NATIVE_FAILURE) {
            state = CombatPlan.StageState.WAIT_SERVICE;
            reason = why;
        }
    }

    CombatPlan decide(CombatSnapshot s, CombatWorkQuota q, long decision) {
        graphVisited = graphWork = 0;
        Target t = s.target(s.selectedTarget);
        if (!matches(s, t)) return null;
        synchronize(s);
        if (route != null && cursor >= route.nodes.size() && !q.full) {
            reason = "LOCAL_BODY_ROUTE_COMPLETE_WAIT_FULL";
            return null;
        }
        if (!s.bodyComplete || !s.self.grounded
            || s.self.stationary
            || s.self.recentlyHurt
            || state == CombatPlan.StageState.NATIVE_FAILURE) {
            waitService("BODY_OR_BACKOFF_UNREADY");
            return null;
        }
        CombatSnapshot.Weapon hand = s.weapon(-1);
        if (hand == null) return null;
        ThreatEstimator.ProjectileOcclusion occlusion = ThreatEstimator.occlusion(s, q);
        ThreatEstimator.Risk immediate = new ThreatEstimator.Risk(true);
        double immediateDamage = ThreatEstimator
            .damage(s, s.self.position, s.self.position, 2, 0, false, q, occlusion, immediate);
        if (!Double.isFinite(immediateDamage) || !immediate.known || immediate.acute) {
            waitService("IMMEDIATE_OBSERVATION_UNREADY_OR_ACUTE");
            return null;
        }
        String currentThreats = threats(s, q);
        if (currentThreats == null) {
            waitService("THREAT_IDENTITY_OR_SERVICE_UNREADY");
            return null;
        }
        if (q.full) {
            if (route != null && route.passiveExit != null) {
                passiveExitProof = null;
                comparison = null;
            }
            CombatTerrain.Choices terrain = null;
            if (route == null || cursor >= route.nodes.size()) {
                terrain = CombatTerrain.findBody(s, t, q);
                if (terrain.approach == null) {
                    waitService("NO_CLOSER_ORDINARY_BODY_ROUTE");
                    return null;
                }
                route = terrain.approach;
                cursor = 1;
                state = CombatPlan.StageState.PROPOSED;
            }
            proof = reprove(s, route, cursor, q);
            if (proof == null) {
                waitService("BODY_FORWARD_OR_RETURN_UNPROVED");
                return null;
            }
            if (terrain == null) terrain = CombatTerrain.findBody(s, t, q);
            // 唯一一次已建图；续持也只用这一个图给必要可执行防御参照
            graphVisited = terrain.visited;
            graphWork = terrain.work;
            CombatObservedRisk moving = assess(s, proof, q, occlusion);
            CombatObservedRisk waiting = assess(s, null, q, occlusion);
            CombatObservedRisk defense = null;
            if (terrain.retreat != null) {
                CombatTerrain.Route retreat = reprove(s, terrain.retreat, 1, q);
                if (retreat == null) {
                    waitService("DEFENSE_BODY_COMPARISON_UNPROVED");
                    return null;
                }
                defense = assess(s, retreat, q, occlusion);
            }
            if (!moving.reserves(s) || !moving.nonWorse(waiting) || defense != null && !moving.nonWorse(defense)) {
                waitService("OBSERVED_COMPARISON_OR_RESERVES_REJECTED");
                return null;
            }
            comparison = moving;
            threats = currentThreats;
        } else if (comparison == null || !comparison.reserves(s)
            || proof == null
            || !proof.fresh(s.tick)
            || !Objects.equals(threats, currentThreats)) {
                waitService("COMPARISON_STALE_OR_THREAT_CHANGED");
                return null;
            }
        var node = node(s);
        CombatMovementSegment segment;
        if (route.passiveExit != null) {
            var candidate = q.full
                ? new CombatPassiveDoorExitProof(node, s, route.passiveExit, hand.fingerprint, ++passiveSerial)
                : passiveExitProof;
            if (candidate == null || candidate.movement(s.self.position, null, s.tick, q) == null) {
                passiveExitProof = null;
                waitService("CURRENT_PASSIVE_EXIT_UNPROVED");
                return null;
            }
            passiveExitProof = candidate;
            segment = candidate.segment;
        } else {
            if (!CombatTerrain.bodyStep(s, s.self.position, route.next(cursor), q)
                || !CombatTerrain.bodyStep(s, route.next(cursor), s.self.position, q)) {
                waitService("CURRENT_ORDINARY_SEGMENT_UNPROVED");
                return null;
            }
            segment = new CombatMovementSegment(node, s.self.position, proof.provedAt);
        }
        if (!segment.ordinaryAdjacent()) {
            waitService("CURRENT_BODY_NODE_NOT_ADJACENT");
            return null;
        }
        reason = "OBSERVED_ONLY_TOTAL_UNKNOWN";
        return new CombatPlan(
            decision,
            s,
            t,
            hand,
            CombatPlan.Macro.APPROACH,
            route.destination,
            lastSeen,
            "bounded_reobservation",
            -Double.MAX_VALUE,
            0,
            Double.NaN,
            false,
            q,
            proof,
            CombatPlanner.reobservationTrace(graphVisited, graphWork),
            CombatPlan.Strategy.REOBSERVE,
            state,
            id,
            cursor,
            route.destination,
            node,
            false,
            false,
            firstAccepted,
            retryAt,
            reason,
            comparison,
            segment,
            null,
            null,
            passiveExitProof);
    }

    private static CombatTerrain.Route reprove(CombatSnapshot s, CombatTerrain.Route route, int cursor,
        CombatWorkQuota q) {
        return route.passiveExit == null ? CombatTerrain.reproveBody(s, route, cursor, q)
            : CombatTerrain.reprovePassiveExit(s, route, cursor, q);
    }

    static CombatObservedRisk assess(CombatSnapshot s, CombatTerrain.Route route, CombatWorkQuota q,
        ThreatEstimator.ProjectileOcclusion occlusion) {
        double[] self = new double[CombatWorkQuota.HYPOTHESES], owner = new double[self.length];
        double single = 0, ownerSingle = 0, hidden = 0;
        boolean complete = q.spend(Work.CANDIDATE), acute = false;
        for (int h = 0; h < self.length && complete; h++) {
            Vec p = s.self.position;
            var risk = new ThreatEstimator.Risk(true);
            for (int elapsed = 2; elapsed <= CombatWorkQuota.HORIZON; elapsed += CombatWorkQuota.STEP) {
                if (!q.spend(Work.ADVANCE)) {
                    complete = false;
                    break;
                }
                Vec next = route == null ? s.self.position : route.at(s.self.movementPerTick * elapsed);
                double damage = ThreatEstimator.damage(s, p, next, elapsed, h, false, q, occlusion, risk);
                if (!Double.isFinite(damage) || !risk.known) {
                    complete = false;
                    break;
                }
                self[h] += damage;
                p = next;
            }
            owner[h] = risk.ownerReceived;
            single = Math.max(single, risk.selfSingle);
            ownerSingle = Math.max(ownerSingle, risk.ownerSingle);
            hidden = Math.max(hidden, risk.hiddenSingle);
            acute |= risk.acute;
        }
        return new CombatObservedRisk(self, owner, single, ownerSingle, hidden, complete, acute);
    }

    static String threats(CombatSnapshot s, CombatWorkQuota quota) {
        StringBuilder key = new StringBuilder();
        for (Target t : s.targets) {
            if (!quota.spend(Work.COLLISION) || !t.fresh(s.tick) || t.visible && t.observedAt != s.tick) return null;
            key.append(t.id)
                .append('/')
                .append(t.entityId)
                .append('/')
                .append(t.visible)
                .append('/')
                .append(t.damageSource)
                .append('/')
                .append(t.estimatedDamage)
                .append(';');
        }
        for (CombatSnapshot.Projectile p : s.projectiles) {
            if (!quota.spend(Work.COLLISION) || p.observedAt > s.tick || s.tick - p.observedAt > 2) return null;
            key.append(p.id)
                .append('/')
                .append(p.damageSource)
                .append('/')
                .append(p.estimatedDamage)
                .append(';');
        }
        return key.toString();
    }
}
