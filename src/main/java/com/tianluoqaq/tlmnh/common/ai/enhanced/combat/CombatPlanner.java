package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan.Macro;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Weapon;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;

/** 有限动作组合的纯预演；不构造实体、不查询物品、不调用世界或随机源 */
public final class CombatPlanner {

    private static final double SWITCH_MARGIN = .06, SELF_WEIGHT = 1.45, OWNER_WEIGHT = .55, PROGRESS_WEIGHT = .95;
    private static final double RESOURCE_WEIGHT = .10, CONTINUITY_WEIGHT = .16;
    private static final int JUMP_PREPARATION_TICKS = 8;
    private long nextDecision, heldSince = Long.MIN_VALUE;
    private CombatPlan previous;

    public CombatPlan decide(CombatSnapshot snapshot, CombatWorkQuota quota) {
        List<Candidate> candidates = candidates(snapshot, quota);
        if (candidates.isEmpty()) return plan(snapshot, null, quota, false);
        boolean full = quota.full && snapshot.complete
            && snapshot.geometry != null
            && snapshot.geometry.fresh(snapshot.tick);
        Candidate best = null, current = null;
        boolean completeForecast = full;
        for (Candidate candidate : candidates) {
            if (full) {
                forecast(snapshot, candidate, quota);
                completeForecast &= candidate.evaluated;
            } else fallback(snapshot, candidate, quota);
            if (snapshot.self.drawing && previous != null && !same(candidate, previous)) candidate.utility -= .12;
            if (same(candidate, previous)) current = candidate;
            if (best == null || candidate.utility > best.utility) best = candidate;
        }
        double immediateRisk = ThreatEstimator
            .damage(snapshot, snapshot.self.position, snapshot.self.position, 2, 0, false, quota);
        if (current != null && current.utility > -100
            && immediateRisk < snapshot.self.health * .15
            && snapshot.tick >= heldSince
            && snapshot.tick - heldSince < 8
            && best.utility < current.utility + SWITCH_MARGIN) best = current;
        return plan(snapshot, best, quota, completeForecast);
    }

    public void finishDecision() {
        previous = null;
    }

    private List<Candidate> candidates(CombatSnapshot snapshot, CombatWorkQuota quota) {
        List<Candidate> result = new ArrayList<>(), attacks = new ArrayList<>();
        Target target = snapshot.target(snapshot.selectedTarget);
        if (target == null && !snapshot.targets.isEmpty()) target = snapshot.targets.get(0);
        Weapon current = snapshot.weapon(-1);
        // 当前动作和防御先占名额，静态输出排序不能将其挤掉
        if (previous != null && target != null
            && current != null
            && current.profile.supported()
            && (previous.macro != Macro.JUMP_STRIKE || !snapshot.self.grounded
                || remainingCooldown(snapshot) <= JUMP_PREPARATION_TICKS))
            add(
                result,
                new Candidate(
                    previous.macro == Macro.PREPARE_WEAPON ? Macro.HOLD_ATTACK : previous.macro,
                    target,
                    current,
                    destination(snapshot, target, previous.macro),
                    .1),
                quota);
        else if (target != null && usable(snapshot, current)) add(result, attack(snapshot, target, current), quota);
        if (snapshot.self.shieldAvailable)
            add(result, new Candidate(Macro.SHIELD, target, current, snapshot.self.position, .12), quota);
        if (!snapshot.self.stationary && target != null) add(
            result,
            new Candidate(Macro.RETREAT, target, current, destination(snapshot, target, Macro.RETREAT), .06),
            quota);
        for (Weapon weapon : snapshot.weapons) {
            if (!usable(snapshot, weapon)) continue;
            Candidate best = null;
            for (Target other : snapshot.targets) {
                if (!quota.spend(Work.FALLBACK) || !other.fresh(snapshot.tick)) break;
                Candidate candidate = attack(snapshot, other, weapon);
                if (best == null || candidate.staticValue > best.staticValue) best = candidate;
            }
            if (best != null) attacks.add(best);
        }
        attacks.sort(
            Comparator.comparingDouble((Candidate value) -> -value.staticValue)
                .thenComparingInt(value -> value.weapon.slot));
        for (int i = 0; i < Math.min(5, attacks.size()); i++) add(result, attacks.get(i), quota);
        Candidate strongest = attacks.isEmpty() ? null : attacks.get(0);
        if (strongest != null && !snapshot.self.stationary) {
            add(
                result,
                new Candidate(
                    Macro.STRAFE_LEFT,
                    strongest.target,
                    strongest.weapon,
                    destination(snapshot, strongest.target, Macro.STRAFE_LEFT),
                    strongest.staticValue - .04),
                quota);
            add(
                result,
                new Candidate(
                    Macro.STRAFE_RIGHT,
                    strongest.target,
                    strongest.weapon,
                    destination(snapshot, strongest.target, Macro.STRAFE_RIGHT),
                    strongest.staticValue - .04),
                quota);
            add(
                result,
                new Candidate(
                    Macro.COVER_EDGE,
                    strongest.target,
                    strongest.weapon,
                    destination(snapshot, strongest.target, Macro.COVER_EDGE),
                    strongest.staticValue - .08),
                quota);
        }
        if (strongest != null && jumpUseful(snapshot, strongest)) add(
            result,
            new Candidate(
                Macro.JUMP_STRIKE,
                strongest.target,
                strongest.weapon,
                snapshot.self.position,
                strongest.staticValue + .03),
            quota);
        return result;
    }

    private static boolean usable(CombatSnapshot snapshot, Weapon weapon) {
        return weapon != null && weapon.profile.supported()
            && (weapon.profile.remainingDurability < 0
                || weapon.profile.remainingDurability >= weapon.profile.durabilityCost)
            && (!weapon.profile.bow() || snapshot.self.ammunition > 0);
    }

    private static Candidate attack(CombatSnapshot snapshot, Target target, Weapon weapon) {
        double distance = snapshot.self.position.distance(target.position), damage = physical(weapon);
        double delay = weapon.profile.bow() ? Math.max(1, 20 - (weapon.slot == -1 ? snapshot.self.drawTicks : 0))
            : snapshot.self.attackDelay + Math.max(0, distance - snapshot.self.reach) / .2;
        double value = Math.min(1, damage / target.health) / (1 + delay / 20);
        if (target.attackingOwner) value += .22;
        if (!target.visible) value -= .4;
        if (weapon.slot >= 0) value -= .10;
        if (snapshot.self.drawing && !weapon.profile.bow()) value -= snapshot.self.drawTicks / 80.0;
        if (snapshot.self.preferencePending && weapon.profile.bow() == snapshot.self.preferBow) value += .025;
        Macro macro = !target.visible || !weapon.profile.bow() && distance > snapshot.self.reach ? Macro.APPROACH
            : Macro.HOLD_ATTACK;
        return new Candidate(macro, target, weapon, destination(snapshot, target, macro), value);
    }

    private static Vec destination(CombatSnapshot snapshot, Target target, Macro macro) {
        if (target == null || snapshot.self.stationary) return snapshot.self.position;
        Vec toward = target.position.subtract(snapshot.self.position)
            .horizontalUnit();
        return switch (macro) {
            case RETREAT -> snapshot.self.position.add(toward.scale(-2.5));
            case STRAFE_LEFT, COVER_EDGE -> snapshot.self.position.add(new Vec(-toward.z, 0, toward.x).scale(2));
            case STRAFE_RIGHT -> snapshot.self.position.add(new Vec(toward.z, 0, -toward.x).scale(2));
            case APPROACH -> snapshot.self.position.add(
                toward.scale(
                    Math.min(
                        3,
                        Math.max(0, snapshot.self.position.distance(target.position) - snapshot.self.reach * .8))));
            default -> snapshot.self.position;
        };
    }

    private static void add(List<Candidate> result, Candidate candidate, CombatWorkQuota quota) {
        for (Candidate existing : result) if (existing.macro == candidate.macro && existing.target == candidate.target
            && existing.weapon == candidate.weapon) return;
        if (result.size() < CombatWorkQuota.MAX_CANDIDATES && quota.spend(Work.CANDIDATE)) result.add(candidate);
    }

    private static boolean jumpUseful(CombatSnapshot snapshot, Candidate candidate) {
        Weapon weapon = candidate.weapon;
        Target target = candidate.target;
        if (weapon == null || !weapon.profile.melee()
            || weapon.profile.criticalMultiplier <= 1
            || !snapshot.self.jumpAllowed
            || !snapshot.self.grounded
            || remainingCooldown(snapshot) > JUMP_PREPARATION_TICKS
            || snapshot.self.stationary
            || !target.visible
            || snapshot.geometry == null
            || !snapshot.geometry.complete
            || !snapshot.geometry.fresh(snapshot.tick)
            || snapshot.self.position.distance(target.position) > snapshot.self.reach
            || target.lastOwnHitAt != Long.MIN_VALUE && snapshot.tick >= target.lastOwnHitAt
                && snapshot.tick - target.lastOwnHitAt < 10)
            return false;
        double normal = physical(weapon),
            critical = weapon.profile.estimatedMeleePhysical * weapon.profile.criticalMultiplier
                + weapon.profile.sharpness * 1.25;
        return Math.min(target.health, critical) - Math.min(target.health, normal) > Math.max(.5, normal * .12);
    }

    private static void fallback(CombatSnapshot snapshot, Candidate candidate, CombatWorkQuota quota) {
        if (!quota.spend(Work.FALLBACK)) {
            candidate.utility = -1000;
            return;
        }
        candidate.damage = candidate.risk = 0;
        if (candidate.macro == Macro.JUMP_STRIKE || candidate.macro == Macro.COVER_EDGE) {
            candidate.utility = -1000;
            return;
        }
        if (candidate.destination.distance(snapshot.self.position) > .05
            && !ThreatEstimator.safeMove(snapshot, snapshot.self.position, candidate.destination, true, quota)) {
            candidate.utility = -1000;
            return;
        }
        candidate.risk = ThreatEstimator.damage(
            snapshot,
            snapshot.self.position,
            candidate.destination,
            2,
            0,
            candidate.macro == Macro.SHIELD,
            quota);
        // 观测/几何缺口下只允许当前可见攻击、合法举盾或已知安全移动；不按静态高输出贴脸换血
        double progress = candidate.target != null && candidate.target.visible ? candidate.staticValue : -.1;
        if (candidate.weapon != null && candidate.weapon.profile.bow()
            && !ThreatEstimator.friendlyLine(
                snapshot,
                snapshot.self.position.add(new Vec(0, 1.3, 0)),
                candidate.target.position.add(new Vec(0, candidate.target.height * .5, 0)),
                quota))
            progress = -.5;
        candidate.utility = progress - SELF_WEIGHT * candidate.risk / snapshot.self.health;
        if (candidate.macro == Macro.SHIELD) candidate.utility += .05;
        if (candidate.weapon != null && candidate.weapon.slot >= 0) candidate.utility -= .1;
    }

    private static void forecast(CombatSnapshot snapshot, Candidate candidate, CombatWorkQuota quota) {
        double worst = Double.POSITIVE_INFINITY, risk = 0, damage = 0;
        for (int hypothesis = 0; hypothesis < CombatWorkQuota.HYPOTHESES; hypothesis++) {
            Vec position = snapshot.self.position;
            double received = 0, dealt = 0, ownerRisk = 0,
                verticalSpeed = snapshot.self.grounded ? .42 : snapshot.self.velocity.y;
            int swap = candidate.weapon != null && candidate.weapon.slot >= 0 ? 3 : 0;
            // 自有真实截止时刻与换装阶段可以同时经过，不能把false误当成又等整周期
            int nextAttack = Math.max(swap, remainingCooldown(snapshot));
            if (candidate.weapon != null && candidate.weapon.profile.bow())
                nextAttack = Math.max(nextAttack, swap + Math.max(1, 20 - (swap == 0 ? snapshot.self.drawTicks : 0)));
            int jumpAt = snapshot.self.grounded ? nextAttack : 0;
            boolean jumpPending = candidate.macro == Macro.JUMP_STRIKE && snapshot.self.grounded;
            boolean airborne = candidate.macro == Macro.JUMP_STRIKE && !snapshot.self.grounded;
            boolean landed = !airborne, hit = false, invalid = false;
            for (int elapsed = 2; elapsed <= CombatWorkQuota.HORIZON; elapsed += CombatWorkQuota.STEP) {
                if (!quota.spend(Work.ADVANCE)) {
                    candidate.utility = -1000;
                    return;
                }
                Vec next = position;
                if (!snapshot.self.stationary && elapsed > swap) {
                    Vec delta = candidate.destination.subtract(position)
                        .horizontalUnit()
                        .scale(
                            Math.min(
                                snapshot.self.movementPerTick * 2,
                                candidate.destination.subtract(position)
                                    .length()));
                    next = position.add(delta);
                }
                boolean descending = false;
                if (jumpPending && elapsed > jumpAt) {
                    jumpPending = false;
                    airborne = true;
                    landed = false;
                }
                if (airborne) {
                    double y = position.y;
                    // 准备期间站立受险照常计入；只有冷却到期后的区间才推进原生跳跃物理
                    int verticalSteps = Math.min(CombatWorkQuota.STEP, elapsed - jumpAt);
                    for (int part = 0; part < verticalSteps; part++) {
                        y += verticalSpeed;
                        verticalSpeed = (verticalSpeed - .08) * .98;
                    }
                    descending = verticalSpeed < 0;
                    if (y <= snapshot.self.position.y) {
                        y = snapshot.self.position.y;
                        airborne = false;
                        landed = true;
                    }
                    next = new Vec(next.x, y, next.z);
                }
                if (position.distance(next) > .001
                    && !ThreatEstimator.safeMove(snapshot, position, next, !airborne, quota)) {
                    invalid = true;
                    break;
                }
                received += ThreatEstimator
                    .damage(snapshot, position, next, elapsed, hypothesis, candidate.macro == Macro.SHIELD, quota);
                if (candidate.macro == Macro.COVER_EDGE && candidate.target != null
                    && ThreatEstimator.localCover(snapshot, next, candidate.target.position, quota)) received *= .92;
                position = next;
                if (candidate.target != null && candidate.target.attackingOwner
                    && snapshot.self.ownerPosition != null) {
                    Vec enemy = ThreatEstimator
                        .enemyPosition(candidate.target, snapshot.self.ownerPosition, elapsed, hypothesis);
                    if (enemy.distance(snapshot.self.ownerPosition) < 2.7)
                        ownerRisk += candidate.target.estimatedDamage * .1;
                }
                if (candidate.target == null || candidate.weapon == null
                    || !candidate.target.visible
                    || candidate.macro == Macro.SHIELD
                    || candidate.macro == Macro.RETREAT
                    || elapsed < nextAttack) continue;
                Vec enemy = ThreatEstimator
                    .enemyPosition(candidate.target, snapshot.self.position, elapsed, hypothesis);
                boolean bow = candidate.weapon.profile.bow();
                if (!bow && position.distance(enemy) > snapshot.self.reach + candidate.target.halfWidth) continue;
                if (candidate.macro == Macro.JUMP_STRIKE && !descending && !hit) continue;
                if (ThreatEstimator.localCover(snapshot, position, enemy, quota)) continue;
                if (bow && !ThreatEstimator.friendlyLine(
                    snapshot,
                    position.add(new Vec(0, 1.3, 0)),
                    enemy.add(new Vec(0, candidate.target.height * .5, 0)),
                    quota)) continue;
                // 只估计有机会命中的收益，当前完整弹道仍由真实放箭入口重验
                double amount = physical(candidate.weapon);
                if (candidate.macro == Macro.JUMP_STRIKE && descending && !hit)
                    amount += candidate.weapon.profile.estimatedMeleePhysical
                        * (candidate.weapon.profile.criticalMultiplier - 1);
                double uncertainty = Math.max(.35, 1 - elapsed * .013);
                dealt += amount * uncertainty;
                hit = true;
                nextAttack = elapsed + (bow ? Math.max(20, snapshot.self.attackDelay) : snapshot.self.attackDelay);
            }
            if (invalid || !Double.isFinite(received)) {
                candidate.utility = -1000;
                candidate.evaluated = quota.remaining(Work.COLLISION) > 0;
                return;
            }
            double terminal = !landed ? .45 : 0;
            if (!hit && candidate.weapon != null) {
                int remaining = Math.max(0, nextAttack - CombatWorkQuota.HORIZON);
                terminal += .08 + Math.min(.25, remaining / 80.0);
            }
            if (candidate.macro == Macro.COVER_EDGE && candidate.target != null
                && !ThreatEstimator.localCover(snapshot, position, candidate.target.position, quota)) terminal += .12;
            double progress = candidate.target == null ? 0 : Math.min(1, dealt / candidate.target.health);
            if (candidate.target != null && !snapshot.self.stationary) {
                double improvement = snapshot.self.position.distance(candidate.target.position)
                    - position.distance(candidate.target.position);
                progress += Math.max(-.15, Math.min(.2, improvement / 15));
            }
            double resource = candidate.weapon == null || candidate.weapon.profile.remainingDurability <= 0 ? 0
                : Math.min(
                    1,
                    candidate.weapon.profile.durabilityCost / (double) candidate.weapon.profile.remainingDurability);
            double continuity = candidate.weapon != null && candidate.weapon.slot >= 0 ? .6 : 0;
            if (snapshot.self.drawing
                && (candidate.weapon == null || !candidate.weapon.profile.bow() || candidate.macro == Macro.SHIELD))
                continuity += snapshot.self.drawTicks / 20.0;
            double utility = PROGRESS_WEIGHT * progress - SELF_WEIGHT * received / snapshot.self.health
                - OWNER_WEIGHT * Math.max(0, ownerRisk - dealt * .2) / snapshot.self.ownerHealth
                - RESOURCE_WEIGHT * resource
                - CONTINUITY_WEIGHT * continuity
                - terminal;
            if (candidate.target != null && candidate.target.attackingOwner) utility += Math.min(.2, progress * .2);
            worst = Math.min(worst, utility);
            risk = Math.max(risk, received);
            damage = hypothesis == 0 ? dealt : Math.min(damage, dealt);
        }
        candidate.evaluated = true;
        candidate.utility = worst;
        candidate.risk = risk;
        candidate.damage = damage;
    }

    private CombatPlan plan(CombatSnapshot snapshot, Candidate candidate, CombatWorkQuota quota, boolean simulated) {
        if (candidate == null || candidate.utility <= -100) candidate = new Candidate(
            Macro.WAIT,
            snapshot.target(snapshot.selectedTarget),
            snapshot.weapon(-1),
            snapshot.self.position,
            0);
        boolean same = same(candidate, previous);
        if (!same) heldSince = snapshot.tick;
        long id = same ? previous.decisionId : ++nextDecision;
        Macro macro = candidate.weapon != null && candidate.weapon.slot >= 0
            && candidate.macro != Macro.SHIELD
            && candidate.macro != Macro.WAIT ? Macro.PREPARE_WEAPON : candidate.macro;
        previous = new CombatPlan(
            id,
            snapshot,
            candidate.target,
            candidate.weapon,
            macro,
            candidate.destination,
            candidate.target == null ? snapshot.self.position : candidate.target.position,
            simulated ? "bounded_forecast" : "bounded_reaction",
            candidate.utility,
            candidate.damage,
            candidate.risk,
            simulated,
            quota);
        return previous;
    }

    private static boolean same(Candidate candidate, CombatPlan plan) {
        return plan != null && candidate.macro == plan.macro
            && candidate.weapon != null
            && candidate.weapon.fingerprint.equals(plan.weaponFingerprint)
            && Objects.equals(candidate.target == null ? null : candidate.target.id, plan.targetId);
    }

    private static int remainingCooldown(CombatSnapshot snapshot) {
        long remaining = snapshot.self.cadenceRemainingTicks;
        return remaining < 0 ? CombatWorkQuota.HORIZON + 1 : (int) Math.min(CombatWorkQuota.HORIZON + 1L, remaining);
    }

    private static double physical(Weapon weapon) {
        return weapon.profile.bow() ? 6 + weapon.profile.power * 1.5
            : weapon.profile.estimatedMeleePhysical + weapon.profile.sharpness * 1.25;
    }

    private static final class Candidate {

        final Macro macro;
        final Target target;
        final Weapon weapon;
        final Vec destination;
        final double staticValue;
        double utility, damage, risk;
        boolean evaluated;

        Candidate(Macro macro, Target target, Weapon weapon, Vec destination, double staticValue) {
            this.macro = macro;
            this.target = target;
            this.weapon = weapon;
            this.destination = destination;
            this.staticValue = staticValue;
        }
    }
}
