package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan.Macro;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan.StageState;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan.Strategy;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatAlignmentProof;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatConditionalEntryProof;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatMovementFact;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Weapon;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.ThreatEstimator;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;

/** 有限动作组合的纯预演；不构造实体、不查询物品、不调用世界或随机源 */
public final class CombatPlanner {

    private long nextDecision, heldSince = Long.MIN_VALUE;
    private long arrowDefenseStarted = Long.MIN_VALUE;
    private CombatPlan previous;
    private CombatPlan routeProposal;
    private UUID proposalSelfId;
    private CombatTerrain.Route followedRoute;
    private int followedNode;
    private static boolean traceEnabled;
    private int terrainVisited, terrainWork, alignmentWork, entryWork;
    private boolean intentEligible, comparisonReady, normalProgressAdmitted;
    private long nextStage;
    private Stage stage;
    private CombatReobservation reobservation;
    private long reobservationRetryAt;

    /** 仅显式原生QA启用；默认不构造逐候选诊断对象，不增加规划或查询 */
    public static boolean traceEnabled() {
        return traceEnabled;
    }

    public static void setTraceEnabled(boolean enabled) {
        traceEnabled = enabled;
    }

    public static final class Trace {

        public final int terrainVisited, terrainWork, alignmentWork, entryWork;
        public final boolean full, necessaryComparisonComplete;
        public final Vec proposalGoal;
        public final long proposalProvedAt;
        public final boolean intentEligible;
        public final List<CandidateTrace> candidates;

        private Trace(int visited, int work, boolean full, List<Candidate> source, CombatPlan proposal,
            boolean executable, boolean comparisonComplete, int alignmentWork, int entryWork) {
            this.entryWork = entryWork;
            this.alignmentWork = alignmentWork;
            terrainVisited = visited;
            terrainWork = work;
            this.full = full;
            necessaryComparisonComplete = comparisonComplete;
            proposalGoal = proposal == null ? null : proposal.destination;
            proposalProvedAt = proposal == null || proposal.route == null ? Long.MIN_VALUE : proposal.route.provedAt;
            intentEligible = executable;
            List<CandidateTrace> values = new ArrayList<>(source.size());
            for (Candidate candidate : source) values.add(new CandidateTrace(candidate));
            candidates = Collections.unmodifiableList(values);
        }
    }

    static Trace reobservationTrace(int visited, int work) {
        return traceEnabled
            ? new Trace(
                visited,
                work,
                false,
                Collections.emptyList(),
                null,
                false,
                false,
                CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)
            : null;
    }

    public static final class CandidateTrace {

        public final Macro macro;
        public final Strategy strategy;
        public final boolean progressFeasible, escapeProved, riskKnown;
        public final CombatSnapshot.DamageSource damageSource;
        public final double singleSelf, ownerRisk, singleOwner;
        public final int weaponSlot;
        public final MaidWeaponProfile.Family family;
        public final Vec destination;
        public final CombatTerrain.Route route;
        public final double utility, damage, risk;
        public final boolean evaluated, continuing, retained;

        private CandidateTrace(Candidate source) {
            macro = source.macro;
            strategy = source.strategy;
            progressFeasible = source.task();
            escapeProved = source.escapeProved;
            riskKnown = source.riskKnown;
            damageSource = riskKnown ? CombatSnapshot.DamageSource.GENERIC_PRIOR : CombatSnapshot.DamageSource.UNKNOWN;
            singleSelf = source.singleSelf;
            ownerRisk = source.ownerRisk;
            singleOwner = source.singleOwner;
            weaponSlot = source.weapon == null ? CombatPlanTuning.COMBAT_PLANNER_CANDIDATE_TRACE_PARAM
                : source.weapon.slot;
            family = source.weapon == null ? MaidWeaponProfile.Family.UNKNOWN : source.weapon.profile.family;
            destination = source.destination;
            route = source.route;
            utility = source.utility;
            damage = source.damage;
            risk = source.risk;
            evaluated = source.evaluated;
            continuing = source.continuing;
            retained = source.retained;
        }
    }

    public CombatPlan decide(CombatSnapshot snapshot, CombatWorkQuota quota) {
        terrainVisited = terrainWork = alignmentWork = entryWork = CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
        normalProgressAdmitted = false;
        synchronizeStage(snapshot);
        Target selected = snapshot.target(snapshot.selectedTarget);
        if (reobservation != null) reobservationRetryAt = Math.max(reobservationRetryAt, reobservation.retryAt);
        if (reobservation != null && !reobservation.matches(snapshot, selected)) {
            if (selected == null || !Objects.equals(reobservation.target, selected.id)
                || reobservation.targetEntityId != selected.entityId
                || reobservation.entityEpoch != snapshot.entityEpoch
                || reobservation.observationEpoch != snapshot.observationEpoch)
                reobservationRetryAt = CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
            reobservation = null;
        }
        if (selected != null && !selected.visible && selected.fresh(snapshot.tick)) {
            if (stage != null) {
                stage.admitted = false;
                if (stage.state != StageState.NATIVE_FAILURE) {
                    stage.state = StageState.WAIT_SERVICE;
                    stage.reason = CombatPlanIds.KEY_PARENT_WAITING_FOR_CURRENT_TARGET_OBSERVATION;
                }
            }
            if (reobservation == null && quota.full && snapshot.bodyComplete) reobservation = new CombatReobservation(
                ++nextStage,
                stage == null ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE : stage.id,
                snapshot,
                selected);
            if (reobservation != null && snapshot.tick >= reobservationRetryAt
                && (stage == null || stage.state != StageState.NATIVE_FAILURE)) {
                CombatPlan observing = reobservation.decide(snapshot, quota, ++nextDecision);
                if (observing != null) {
                    previous = observing;
                    return observing;
                }
            }
        }
        List<Candidate> candidates = candidates(snapshot, quota);
        if (candidates.isEmpty()) return plan(snapshot, null, quota, false, null);
        boolean full = quota.full && snapshot.complete
            && snapshot.geometry != null
            && snapshot.geometry.fresh(snapshot.tick);
        ThreatEstimator.ProjectileOcclusion occlusion = ThreatEstimator.occlusion(snapshot, quota);
        Candidate best = null, current = null;
        boolean completeForecast = full;
        for (Candidate candidate : candidates) {
            if (full && candidate.route != null && candidate.route.escape != null && !candidate.commits())
                candidate.escapeProved = CombatTerrain.reverseEscape(snapshot, candidate.route, quota);
            if (full) {
                forecast(snapshot, candidate, quota, occlusion);
                completeForecast &= candidate.evaluated && Double.isFinite(candidate.utility);
            } else fallback(snapshot, candidate, quota, occlusion);
            if (Double.isFinite(candidate.utility) && same(candidate, previous)) current = candidate;
            if (Double.isFinite(candidate.utility) && (best == null || candidate.utility > best.utility))
                best = candidate;
        }
        var immediate = new ThreatEstimator.Risk();
        double immediateRisk = full ? ThreatEstimator.damage(
            snapshot,
            snapshot.self.position,
            snapshot.self.position,
            CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM,
            CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
            false,
            quota,
            occlusion,
            immediate) : fallbackRisk(snapshot, snapshot.self.position, false, quota, occlusion, immediate);
        boolean acute = snapshot.self.recentlyHurt || immediate.acute
            || !full && hasFreshProjectile(snapshot)
                && immediateRisk >= snapshot.self.health * CombatPlanTuning.COMBAT_PLANNER_DECIDE_FACTOR;
        boolean immediateComplete = Double.isFinite(immediateRisk) && immediate.known;
        if (!immediateComplete || full && (!completeForecast || !comparisonReady)) intentEligible = false;
        if (acute && stage != null) stage = null;
        boolean comparable = full && completeForecast && comparisonReady && !acute && immediateComplete;
        if (comparable) for (Candidate candidate : candidates) {
            if (candidate.task() && (stage == null || candidate.stage == stage) && survivable(snapshot, candidate)) {
                normalProgressAdmitted = true;
                break;
            }
        }
        if (full && stage != null) {
            stage.admitted = normalProgressAdmitted;
            for (Candidate candidate : candidates) if (candidate.stage == stage && candidate.evaluated) {
                stage.risk = candidate.risk;
                stage.single = candidate.singleSelf;
                stage.ownerRisk = candidate.ownerRisk;
                stage.ownerSingle = candidate.singleOwner;
                stage.escape = candidate.escapeProved;
            }
        }
        boolean continueStage = !full && stage != null
            && stage.admitted
            && !acute
            && immediateComplete
            && intentEligible
            && stage.survivable(snapshot);
        boolean taskOnly = normalProgressAdmitted || continueStage;
        if (taskOnly) {
            best = current = null;
            for (Candidate candidate : candidates) {
                if (!candidate.task() || stage != null && candidate.stage != stage
                    || candidate.utility <= CombatPlanTuning.MIN_UTILITY_THRESHOLD
                    || normalProgressAdmitted && !survivable(snapshot, candidate)) continue;
                if (Double.isFinite(candidate.utility) && (best == null || candidate.utility > best.utility))
                    best = candidate;
                if (Double.isFinite(candidate.utility) && same(candidate, previous)) current = candidate;
            }
        }
        if (!taskOnly || best == null) {
            normalProgressAdmitted = false;
            if (taskOnly) intentEligible = false;
            // 空任务筛选也必须重新比较现有合法反应/纯防御，不能无故吞成WAIT
            // 新任务试探未获准时只比较原反应/纯防御，不能借组合首段名为RETREAT绕过急性安全门
            best = current = null;
            for (Candidate candidate : candidates) {
                if (candidate.commits()) continue;
                if (Double.isFinite(candidate.utility) && (best == null || candidate.utility > best.utility))
                    best = candidate;
                if (Double.isFinite(candidate.utility) && same(candidate, previous)) current = candidate;
            }
            // 服务等待期间若当前站位已确认零即时风险，原地等待而非后退抵消阶段推进
            if (stage != null && stage.state == StageState.WAIT_SERVICE
                && best != null
                && best.macro == Macro.RETREAT
                && !acute
                && immediateComplete
                && immediateRisk <= 0
                && !hasFreshProjectile(snapshot)) {
                best = current = null;
            }
        }
        // A newly installed arrow defense must not lose its safe startup interval merely because
        // FULL service becomes available. Every retained candidate still pays for current geometry/risk.
        if (best != null && previous != null
            && arrowDefenseStarted != Long.MIN_VALUE
            && snapshot.tick >= arrowDefenseStarted
            && snapshot.tick - arrowDefenseStarted
                < CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM * CombatBudgetTuning.STEP) {
            for (Candidate candidate : candidates) {
                if (candidate.strategy == Strategy.PURE_DEFENSE && same(candidate, previous)
                    && candidate.utility > CombatPlanTuning.MIN_UTILITY_THRESHOLD
                    && candidate.risk <= best.risk) {
                    best = current = candidate;
                    normalProgressAdmitted = false;
                    break;
                }
            }
        }
        if (current != null && best != null
            && current.utility > CombatPlanTuning.MIN_UTILITY_THRESHOLD
            && snapshot.tick >= heldSince
            && snapshot.tick - heldSince < CombatPlanTuning.MIN_HELD_SINCE_THRESHOLD
            && (immediateRisk < snapshot.self.health * CombatPlanTuning.COMBAT_PLANNER_DECIDE_FACTOR
                && best.utility < current.utility + CombatPlanTuning.SWITCH_MARGIN
                || !full && acute
                    && hasFreshProjectile(snapshot)
                    && current.strategy == Strategy.PURE_DEFENSE
                    && current.risk <= best.risk))
            best = current;
        return plan(
            snapshot,
            best,
            quota,
            completeForecast,
            traceEnabled
                ? new Trace(
                    terrainVisited,
                    terrainWork,
                    full,
                    candidates,
                    routeProposal,
                    intentEligible,
                    completeForecast && comparisonReady,
                    alignmentWork,
                    entryWork)
                : null);
    }

    public void finishDecision() {
        previous = null;
        arrowDefenseStarted = Long.MIN_VALUE;
        stage = null;
        reobservation = null;
        reobservationRetryAt = CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
        clearRouteProposal();
    }

    /** 原生攻击状态已由执行器结束，尚未到达的路线仍是当前动作 */
    public void finishAttack(CombatPlan plan, Vec position, long now) {
        if (stage != null) return;
        boolean pending = routeProposal != null && previous == plan
            && plan != null
            && position != null
            && Double.isFinite(position.x + position.y + position.z)
            && followedRoute != null
            && followedRoute.fresh(now)
            && Objects.equals(plan.targetId, routeProposal.targetId)
            && plan.targetEntityId == routeProposal.targetEntityId
            && plan.entityEpoch == routeProposal.entityEpoch
            && plan.observationEpoch == routeProposal.observationEpoch
            && position.distance(routeProposal.destination) >= CombatPlanTuning.ROUTE_ARRIVAL_DISTANCE;
        if (!pending) finishDecision();
    }

    private List<Candidate> candidates(CombatSnapshot snapshot, CombatWorkQuota quota) {
        List<Candidate> result = new ArrayList<>(), attacks = new ArrayList<>();
        Target target = snapshot.target(snapshot.selectedTarget);
        if (target == null && !snapshot.targets.isEmpty())
            target = snapshot.targets.get(CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE);
        Weapon current = snapshot.weapon(CombatPlanTuning.UNSET_OR_NOT_FOUND);
        comparisonReady = true;
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
        Candidate strongest = attacks.isEmpty() ? null : attacks.get(CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE);
        CombatTerrain.Choices terrain = strongest == null ? new CombatTerrain.Choices()
            : CombatTerrain.find(snapshot, strongest.target, quota);
        terrainVisited = terrain.visited;
        terrainWork = terrain.work;
        if (stage != null) {
            Candidate committed = stageCandidate(snapshot, target, current, quota, terrain);
            if (committed != null && !add(result, committed, quota)) {
                intentEligible = false;
                comparisonReady = false;
                if (stage.state != StageState.NATIVE_FAILURE) {
                    stage.state = StageState.WAIT_SERVICE;
                    stage.reason = CombatPlanIds.KEY_CURRENT_CANDIDATE_NOT_ADMITTED;
                }
            }
        } else addLegacyCurrent(result, snapshot, target, current, quota);
        // 一续持、至多两纯防御、一任务、一组合优先占位；其余名额仍由原候选使用
        int defenses = 0;
        if (snapshot.self.shieldAvailable) {
            comparisonReady &= add(
                result,
                new Candidate(
                    Macro.SHIELD,
                    target,
                    current,
                    snapshot.self.position,
                    CombatPlanTuning.COMBAT_PLANNER_CANDIDATE_FIFTH_FACTOR),
                quota);
            defenses++;
        }
        if (!snapshot.self.stationary && target != null) {
            Candidate retreat = new Candidate(
                Macro.RETREAT,
                target,
                current,
                destination(snapshot, target, Macro.RETREAT),
                CombatPlanTuning.COMBAT_PLANNER_CANDIDATES_FACTOR);
            if (terrain.retreat != null) retreat.route(terrain.retreat);
            comparisonReady &= add(result, retreat, quota);
            defenses++;
        }
        CombatTerrain.Route pureCover = terrain.cover != null ? terrain.cover : terrain.firingCover;
        if (defenses < CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM && strongest != null && pureCover != null) {
            comparisonReady &= addTerrain(result, Macro.COVER_EDGE, strongest, pureCover, quota);
            defenses++;
        }
        // LIGHT has no full terrain route service; add only bounded, geometry-checked lateral reactions.
        if (!quota.full && hasFreshProjectile(snapshot)
            && strongest != null
            && target != null
            && target.visible
            && !snapshot.self.stationary) {
            if (terrain.left == null) add(
                result,
                new Candidate(
                    Macro.STRAFE_LEFT,
                    target,
                    current,
                    destination(snapshot, target, Macro.STRAFE_LEFT),
                    CombatPlanTuning.COMBAT_PLANNER_CANDIDATES_FACTOR,
                    Strategy.PURE_DEFENSE),
                quota);
            if (terrain.right == null) add(
                result,
                new Candidate(
                    Macro.STRAFE_RIGHT,
                    target,
                    current,
                    destination(snapshot, target, Macro.STRAFE_RIGHT),
                    CombatPlanTuning.COMBAT_PLANNER_CANDIDATES_FACTOR,
                    Strategy.PURE_DEFENSE),
                quota);
        }
        CombatTerrain.Route taskRoute = terrain.approach;
        CombatTerrain.Route defenseRoute = terrain.cover != null ? terrain.cover : terrain.retreat;
        boolean currentEscape = defenseRoute != null && CombatTerrain.reverseEscape(snapshot, defenseRoute, quota);
        if (stage == null && strongest != null && taskRoute != null) {
            boolean escape = defenseRoute != null && CombatTerrain.reverseEscape(snapshot, taskRoute, quota)
                && CombatTerrain.reverseEscape(snapshot, defenseRoute, quota);
            Candidate task = new Candidate(
                Macro.APPROACH,
                strongest.target,
                strongest.weapon,
                taskRoute.destination,
                strongest.staticValue,
                Strategy.TASK);
            task.route(taskRoute);
            task.escapeProved = escape;
            comparisonReady &= add(result, task, quota);
            if (defenseRoute != null) {
                CombatTerrain.Route combined = CombatTerrain.defensiveReturn(snapshot, defenseRoute, taskRoute, quota);
                if (combined == null) comparisonReady = false;
                else {
                    Candidate returning = new Candidate(
                        defenseRoute == terrain.cover ? Macro.COVER_EDGE : Macro.RETREAT,
                        strongest.target,
                        strongest.weapon,
                        combined.destination,
                        strongest.staticValue,
                        Strategy.DEFENSIVE_RETURN);
                    returning.route(combined);
                    returning.escapeProved = escape;
                    comparisonReady &= add(result, returning, quota);
                }
            }
        }
        Candidate jump = null;
        for (int i = 0; i < Math.min(CombatPlanTuning.COMBAT_PLANNER_MIN_FIRST_PARAM, attacks.size()); i++) {
            Candidate attack = attacks.get(i);
            // 服务等待不能以普通轻量重选倒退已提交的任务；可在当前点攻击仍走原链
            if (stage != null && attack.macro == Macro.APPROACH) continue;
            CombatTerrain.Route route = terrainRoute(terrain, attack.macro);
            if (route != null) attack.route(route);
            add(result, attack, quota);
            if (jump == null && jumpUseful(snapshot, attack)) jump = attack;
        }
        if (jump != null) add(
            result,
            new Candidate(
                Macro.JUMP_STRIKE,
                jump.target,
                jump.weapon,
                snapshot.self.position,
                jump.staticValue + CombatPlanTuning.COMBAT_PLANNER_CANDIDATE_FIFTH_FACTOR_2),
            quota);
        if (strongest != null && !snapshot.self.stationary) {
            if (terrain.firingCover != null && terrain.firingCover != pureCover) {
                Candidate firing = new Candidate(
                    Macro.COVER_EDGE,
                    strongest.target,
                    strongest.weapon,
                    terrain.firingCover.destination,
                    strongest.staticValue,
                    Strategy.REACTION);
                firing.route(terrain.firingCover);
                add(result, firing, quota);
            }
            addTerrain(result, Macro.STRAFE_LEFT, strongest, terrain.left, quota);
            addTerrain(result, Macro.STRAFE_RIGHT, strongest, terrain.right, quota);
        }
        // 隐藏时普通接近不再借反应分支绕开复视政策，原合法防御仍保留
        if (target != null && !target.visible) result
            .removeIf(candidate -> candidate.strategy != Strategy.PURE_DEFENSE || candidate.macro == Macro.SHIELD);
        for (Candidate candidate : result) if (candidate.route == null && candidate.strategy == Strategy.REACTION)
            candidate.escapeProved = currentEscape;
        return result;
    }

    private void addLegacyCurrent(List<Candidate> result, CombatSnapshot snapshot, Target target, Weapon current,
        CombatWorkQuota quota) {
        if (routeProposal != null && (!proposalMatches(snapshot, target)
            || snapshot.self.position.distance(routeProposal.destination) < CombatPlanTuning.ROUTE_ARRIVAL_DISTANCE))
            clearRouteProposal();
        intentEligible = canContinue(snapshot, target, current);
        boolean repropose = quota.full && routeProposal != null
            && routeProposal.macro == Macro.COVER_EDGE
            && currentObservation(snapshot, target)
            && usable(snapshot, current);
        CombatPlan heldPlan = repropose || !quota.full && intentEligible ? routeProposal : previous;
        if (heldPlan != null && heldPlan.strategy == Strategy.REOBSERVE) heldPlan = null;
        // 当前动作和防御先占名额，静态输出排序不能将其挤掉
        // WAIT只表示无合法动作的兜底，不形成可保留的攻击意图
        if (heldPlan != null && heldPlan.macro != Macro.WAIT
            && target != null
            && current != null
            && (usable(snapshot, current) || heldPlan.macro == Macro.SHIELD || heldPlan.macro == Macro.RETREAT)
            && (heldPlan.macro != Macro.JUMP_STRIKE || !snapshot.self.grounded
                || remainingCooldown(snapshot) <= CombatPlanTuning.JUMP_PREPARATION_TICKS)) {
            Candidate held = heldPlan.macro == Macro.HOLD_ATTACK || heldPlan.macro == Macro.PREPARE_WEAPON
                ? attack(snapshot, target, current)
                : new Candidate(
                    heldPlan.macro,
                    target,
                    current,
                    heldPlan.macro == Macro.COVER_EDGE ? heldPlan.destination
                        : destination(snapshot, target, heldPlan.macro),
                    CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR,
                    heldPlan.strategy);
            if (heldPlan == routeProposal && (repropose || !quota.full && intentEligible)) {
                Vec step = continuationStep(snapshot, quota);
                if (step != null && quota.full) {
                    CombatTerrain.Route retained = CombatTerrain
                        .retainCover(snapshot, target, followedRoute, followedNode, quota);
                    if (retained != null) {
                        held.route(retained);
                        held.retained = true;
                    }
                } else if (step != null) {
                    held.route(followedRoute);
                    held.continuing = true;
                    held.nextStep = step;
                }
            }
            if (held.macro != Macro.COVER_EDGE || held.route != null) {
                boolean admitted = add(result, held, quota);
                comparisonReady &= admitted;
                if (!admitted) intentEligible = false;
            } else intentEligible = false;
        } else if (target != null && usable(snapshot, current))
            comparisonReady &= add(result, attack(snapshot, target, current), quota);
    }

    private static boolean survivable(CombatSnapshot snapshot, Candidate candidate) {
        return candidate.evaluated && candidate.escapeProved
            && candidate.riskKnown
            && Double.isFinite(candidate.risk + candidate.singleSelf + candidate.ownerRisk + candidate.singleOwner)
            && candidate.risk + candidate.singleSelf < snapshot.self.health
            && candidate.ownerRisk + candidate.singleOwner < snapshot.self.ownerHealth;
    }

    private void synchronizeStage(CombatSnapshot snapshot) {
        if (stage == null) return;
        Target target = snapshot.target(snapshot.selectedTarget);
        if (!stage.matches(snapshot, target)) {
            stage = null;
            return;
        }
        boolean ownsCapability = false;
        for (Weapon weapon : snapshot.weapons) if (usable(snapshot, weapon) && (stage.weapon.equals(weapon.fingerprint)
            || weapon.slot == CombatPlanTuning.UNSET_OR_NOT_FOUND && weapon.profile.family == stage.family))
            ownsCapability = true;
        if (!ownsCapability) {
            stage = null;
            return;
        }
        if (stage.entry != null
            && (stage.entry.equipmentEpoch != snapshot.equipmentEpoch || !stage.entry.weapon.equals(stage.weapon)))
            stage.entry = null;
        CombatMovementFact fact = snapshot.movementFact;
        CombatMovementFact.Node node = stage.node(snapshot);
        if (stage.entry != null) {
            ConditionalEntry entry = stage.entry;
            var auxiliary = entry.node(snapshot, stage);
            if (fact != null && fact.entryArrived(auxiliary, snapshot.tick)
                && fact.entryArrival.admissionSerial == entry.admissionSerial) {
                entry.state = EntryState.READY_WAIT_FULL;
                entry.arrival = fact.entryArrival;
                entry.proof = null;
                stage.admitted = false;
                stage.state = StageState.WAIT_SERVICE;
                stage.reason = CombatPlanIds.KEY_ENTRY_ARRIVED_WAIT_FULL;
            } else if (fact != null && fact.belongs(auxiliary, snapshot.tick)) {
                if (fact.outcome == CombatMovementFact.Outcome.INSTALLED && fact.pathIdentity > 0 && fact.inArea) {
                    entry.state = EntryState.ACTIVE;
                    if (entry.firstAcceptedAt == Long.MIN_VALUE) entry.firstAcceptedAt = fact.tick;
                } else if (fact.outcome == CombatMovementFact.Outcome.NATIVE_FAILURE) {
                    entry.state = EntryState.NATIVE_FAILURE;
                    entry.retryAt = fact.retryAt;
                    entry.proof = null;
                    stage.state = StageState.NATIVE_FAILURE;
                    stage.retryAt = fact.retryAt;
                    stage.reason = fact.reason;
                } else if (fact.outcome == CombatMovementFact.Outcome.WAIT_SERVICE) {
                    entry.suspend();
                    stage.admitted = false;
                }
            }
            if (entry.state == EntryState.READY_WAIT_FULL
                && !CombatConditionalEntryProof.handoff(auxiliary, snapshot.self.position)) entry.suspend();
        }
        if (stage.alignment != null && fact != null) {
            Alignment alignment = stage.alignment;
            CombatMovementFact.Node auxiliary = alignment.node(snapshot, stage);
            if (fact.junctionReady(auxiliary, snapshot.tick)) {
                alignment.state = AlignmentState.READY;
                stage.admitted = false;
                stage.state = StageState.WAIT_SERVICE;
                stage.reason = CombatPlanIds.KEY_ALIGNMENT_JUNCTION_READY_WAIT_FULL;
            } else if (fact.belongs(auxiliary, snapshot.tick)) {
                if (fact.outcome == CombatMovementFact.Outcome.INSTALLED && fact.pathIdentity > 0 && fact.inArea) {
                    alignment.state = AlignmentState.ACTIVE;
                    if (alignment.firstAcceptedAt == Long.MIN_VALUE) alignment.firstAcceptedAt = fact.tick;
                } else if (fact.outcome == CombatMovementFact.Outcome.NATIVE_FAILURE) {
                    alignment.state = AlignmentState.NATIVE_FAILURE;
                    alignment.retryAt = Math.max(alignment.retryAt, fact.retryAt);
                    stage.state = StageState.NATIVE_FAILURE;
                    stage.retryAt = Math.max(stage.retryAt, fact.retryAt);
                    stage.reason = fact.reason;
                } else if (fact.outcome == CombatMovementFact.Outcome.WAIT_SERVICE) {
                    alignment.state = AlignmentState.WAIT_SERVICE;
                    alignment.proof = null;
                    stage.admitted = false;
                }
            }
        }
        if (stage.connector != null && fact != null) {
            CombatMovementFact.Node connectorNode = stage.connector.node(snapshot, stage);
            if (fact.completed(connectorNode, snapshot.tick)) stage.connector.cursor++;
            else if (fact.belongs(connectorNode, snapshot.tick)
                && fact.outcome == CombatMovementFact.Outcome.NATIVE_FAILURE) {
                    stage.state = StageState.NATIVE_FAILURE;
                    stage.retryAt = fact.retryAt;
                    stage.reason = fact.reason;
                }
            // 最后一条连接提交父节点，connector事实永不推进父cursor
            if (stage.connector.cursor >= stage.connector.route.nodes.size() - 1) stage.connector = null;
        }
        if (fact != null && fact.belongs(node, snapshot.tick)) {
            if (fact.completed(node, snapshot.tick)) {
                if (stage.firstAcceptedAt == Long.MIN_VALUE) stage.firstAcceptedAt = fact.tick;
                stage.lastCompleted = fact;
                stage.cursor++;
                stage.entry = null;
                stage.alignment = null;
                stage.reason = CombatPlanIds.KEY_NATIVE_NODE_COMPLETE;
                stage.state = stage.cursor >= stage.route.nodes.size() ? StageState.COMPLETE : StageState.ACTIVE;
            } else if (fact.outcome == CombatMovementFact.Outcome.INSTALLED && fact.pathIdentity > 0 && fact.inArea) {
                if (stage.firstAcceptedAt == Long.MIN_VALUE) stage.firstAcceptedAt = fact.tick;
                stage.reason = fact.reason;
                stage.state = StageState.ACTIVE;
            } else if (fact.outcome == CombatMovementFact.Outcome.NATIVE_FAILURE) {
                stage.state = StageState.NATIVE_FAILURE;
                stage.retryAt = fact.retryAt;
                stage.reason = fact.reason;
            } else if (fact.outcome == CombatMovementFact.Outcome.WAIT_SERVICE && stage.state != StageState.ACTIVE)
                stage.state = StageState.WAIT_SERVICE;
        }
        if (stage.state == StageState.COMPLETE) stage = null;
        else if (stage.state == StageState.NATIVE_FAILURE && snapshot.tick >= stage.retryAt) {
            stage.state = StageState.PROPOSED;
            stage.reason = CombatPlanIds.KEY_ORIGINAL_BACKOFF_ELAPSED;
        }
    }

    private Candidate stageCandidate(CombatSnapshot snapshot, Target target, Weapon weapon, CombatWorkQuota quota,
        CombatTerrain.Choices terrain) {
        Weapon selected = null;
        for (Weapon available : snapshot.weapons)
            if (usable(snapshot, available) && stage.weapon.equals(available.fingerprint)) {
                selected = available;
                break;
            }
        if (selected == null && quota.full && usable(snapshot, weapon) && weapon.profile.family == stage.family) {
            // 真实命中磨损后的装备内容由本次FULL重新估计；已完成的几何子阶段不倒退
            selected = weapon;
            stage.weapon = weapon.fingerprint;
            stage.admitted = false;
        }
        weapon = selected;
        intentEligible = false;
        boolean observationReady = currentObservation(snapshot, target)
            || !quota.full && stage.admitted && meleeContinuationObservation(snapshot, target, weapon);
        if (!observationReady || !usable(snapshot, weapon) || stage.state == StageState.NATIVE_FAILURE) {
            if (stage.entry != null && stage.entry.state != EntryState.NATIVE_FAILURE) stage.entry.suspend();
            if (stage.state != StageState.NATIVE_FAILURE) {
                stage.state = StageState.WAIT_SERVICE;
                stage.reason = CombatPlanIds.KEY_CURRENT_OBSERVATION_OR_CAPABILITY_UNREADY;
            }
            return null;
        }
        if (stage.entry != null) {
            ConditionalEntry entry = stage.entry;
            if (!entry.weapon.equals(weapon.fingerprint) || entry.equipmentEpoch != snapshot.equipmentEpoch) {
                stage.entry = null;
            } else if (entry.state != EntryState.READY_WAIT_FULL) {
                if (entry.state == EntryState.NATIVE_FAILURE && snapshot.tick < entry.retryAt) return null;
                return entryCandidate(snapshot, target, weapon, quota, entry, null);
            } else if (!quota.full) return null;
        }
        CombatTerrain.Route route = quota.full ? CombatTerrain.reprove(snapshot, stage.route, stage.cursor, quota)
            : stage.lastProof;
        Vec parentNext = stage.route.next(stage.cursor);
        Alignment auxiliary = null;
        if (quota.full && route != null) {
            stage.connector = null;
            stage.alignment = null;
        }
        if (quota.full && route == null) {
            if (stage.connector == null) {
                CombatTerrain.Route prefix = CombatTerrain.connector(snapshot, terrain, parentNext, quota);
                if (prefix != null && prefix.nodes.size() > CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM)
                    stage.connector = new Connector(++nextStage, prefix);
            }
            if (stage.connector != null) {
                CombatTerrain.Route prefix = CombatTerrain
                    .reproveBody(snapshot, stage.connector.route, stage.connector.cursor, quota);
                route = CombatTerrain.prepend(snapshot, prefix, stage.route, stage.cursor, quota);
                if (route != null) stage.alignment = null;
            }
            if (route == null && stage.completedSource()) {
                int before = quota.used(Work.COLLISION);
                Vec center = stage.route.next(stage.cursor - 1);
                CombatTerrain.Route prefix = CombatTerrain.alignmentPrefix(snapshot, center, parentNext, quota);
                route = CombatTerrain.prepend(snapshot, prefix, stage.route, stage.cursor, quota);
                alignmentWork += quota.used(Work.COLLISION) - before;
                if (route != null) {
                    if (stage.alignment == null || !stage.alignment.matches(stage, center, parentNext))
                        stage.alignment = new Alignment(++nextStage, stage, center, parentNext);
                    auxiliary = stage.alignment;
                    auxiliary.route = route;
                    stage.connector = null;
                }
            }
            if (route == null && stage.entry == null) {
                int before = quota.used(Work.COLLISION);
                var source = CombatTerrain.conditionalEntry(snapshot, terrain, stage.route, stage.cursor, quota);
                entryWork += quota.used(Work.COLLISION) - before;
                if (source != null) return entryCandidate(
                    snapshot,
                    target,
                    weapon,
                    quota,
                    new ConditionalEntry(++nextStage, stage, snapshot, source),
                    source.route);
            }
        } else if (!quota.full && stage.alignment != null) {
            auxiliary = stage.alignment;
            if (auxiliary.state != AlignmentState.ACTIVE || !stage.admitted
                || auxiliary.proof == null
                || !auxiliary.proof.fresh(snapshot.tick)) route = null;
        }
        Vec next = auxiliary != null ? auxiliary.center
            : stage.connector == null ? parentNext : stage.connector.route.next(stage.connector.cursor);
        boolean connection = false;
        if (route != null && route.fresh(snapshot.tick)) {
            if (auxiliary != null) {
                int before = quota.used(Work.COLLISION);
                connection = quota.full
                    || auxiliary.proof.movement(snapshot.self.position, snapshot.tick, quota) != null;
                alignmentWork += quota.used(Work.COLLISION) - before;
            } else connection = CombatTerrain.safeStep(snapshot, snapshot.self.position, next, quota)
                || CombatTerrain.safeRequestStep(snapshot, snapshot.self.position, next, quota);
        }
        if (!connection) {
            stage.state = StageState.WAIT_SERVICE;
            stage.reason = CombatPlanIds.KEY_CURRENT_CONNECTION_OR_SERVICE_UNPROVED;
            return null;
        }
        stage.lastProof = route;
        Candidate held = new Candidate(
            stage.strategy == Strategy.DEFENSIVE_RETURN && stage.cursor <= stage.route.defenseNode ? stage.firstMacro
                : Macro.APPROACH,
            target,
            weapon,
            route.destination,
            CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR,
            stage.strategy);
        held.route(route);
        held.escapeProved = quota.full ? CombatTerrain.reverseEscape(snapshot, route, quota) : stage.escape;
        held.stage = stage;
        held.alignment = auxiliary;
        intentEligible = held.escapeProved;
        if (!quota.full) {
            held.continuing = true;
            held.nextStep = next;
        }
        return held;
    }

    private Candidate entryCandidate(CombatSnapshot s, Target target, Weapon weapon, CombatWorkQuota quota,
        ConditionalEntry entry, CombatTerrain.Route source) {
        int before = quota.used(Work.COLLISION);
        CombatTerrain.Route route;
        if (quota.full) route = source != null ? source
            : CombatTerrain.conditionalEntryRoute(s, stage.route, stage.cursor, entry.k, entry.edge, quota);
        else {
            route = entry.route;
            if (entry.state != EntryState.ACTIVE || !stage.admitted
                || entry.proof == null
                || !entry.proof.fresh(s.tick)
                || !(s.bodyComplete || weapon != null && weapon.profile.melee() && s.bodyContinuationComplete)
                || entry.proof.movement(s.self.position, null, s.tick, quota) == null) route = null;
        }
        entryWork += quota.used(Work.COLLISION) - before;
        if (route == null) {
            if (entry.state != EntryState.NATIVE_FAILURE) entry.suspend();
            stage.state = StageState.WAIT_SERVICE;
            stage.reason = CombatPlanIds.KEY_CONDITIONAL_ENTRY_BODY_OR_SERVICE_UNPROVED;
            return null;
        }
        Candidate held = new Candidate(
            Macro.APPROACH,
            target,
            weapon,
            route.destination,
            CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR,
            stage.strategy);
        held.route(route);
        held.stage = stage;
        held.entry = entry;
        int escapeBefore = quota.used(Work.COLLISION);
        held.escapeProved = quota.full ? CombatTerrain.reverseEscape(s, route, quota) : stage.escape;
        entryWork += quota.used(Work.COLLISION) - escapeBefore;
        intentEligible = held.escapeProved;
        if (!quota.full) {
            held.continuing = true;
            held.nextStep = entry.k;
        }
        return held;
    }

    /** 几何提案不携带执行许可；任何装备内容变化仍会使下方旧stamp匹配失败 */
    private boolean proposalMatches(CombatSnapshot snapshot, Target target) {
        return routeProposal != null && followedRoute != null
            && routeProposal.route == followedRoute
            && Objects.equals(proposalSelfId, snapshot.selfId)
            && followedRoute.fresh(snapshot.tick)
            && target != null
            && Objects.equals(routeProposal.targetId, target.id)
            && routeProposal.targetEntityId == target.entityId
            && Objects.equals(snapshot.selectedTarget, target.id)
            && routeProposal.entityEpoch == snapshot.entityEpoch
            && routeProposal.observationEpoch == snapshot.observationEpoch;
    }

    private boolean intentMatches(CombatSnapshot snapshot, Target target, Weapon weapon) {
        return proposalMatches(snapshot, target) && routeProposal.equipmentEpoch == snapshot.equipmentEpoch
            && usable(snapshot, weapon)
            && routeProposal.weaponFingerprint.equals(weapon.fingerprint)
            && routeProposal.family == weapon.profile.family;
    }

    private static boolean currentObservation(CombatSnapshot snapshot, Target target) {
        return snapshot.complete && snapshot.friendsComplete
            && snapshot.self.grounded
            && !snapshot.self.stationary
            && target != null
            && target.visible
            && target.observedAt == snapshot.tick;
    }

    private static boolean meleeContinuationObservation(CombatSnapshot snapshot, Target target, Weapon weapon) {
        return snapshot.continuationComplete && weapon != null
            && weapon.profile.melee()
            && snapshot.self.grounded
            && !snapshot.self.stationary
            && target != null
            && target.visible
            && target.observedAt == snapshot.tick;
    }

    private boolean canContinue(CombatSnapshot snapshot, Target target, Weapon weapon) {
        return intentMatches(snapshot, target, weapon) && currentObservation(snapshot, target);
    }

    private void clearRouteProposal() {
        routeProposal = null;
        proposalSelfId = null;
        followedRoute = null;
        followedNode = CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE;
    }

    private Vec continuationStep(CombatSnapshot snapshot, CombatWorkQuota quota) {
        if (!quota.spend(Work.COLLISION)) return null;
        CombatMovementFact fact = snapshot.movementFact;
        if (previous != null && previous.route == followedRoute
            && fact != null
            && fact.completed(previous.movementNode, snapshot.tick)
            && previous.movementNode.index == followedNode
            && followedNode + 1 < followedRoute.nodes.size()) followedNode++;
        return followedRoute.next(followedNode);
    }

    private static CombatTerrain.Route terrainRoute(CombatTerrain.Choices choices, Macro macro) {
        return switch (macro) {
            case APPROACH -> choices.approach;
            case RETREAT -> choices.retreat;
            case STRAFE_LEFT -> choices.left;
            case STRAFE_RIGHT -> choices.right;
            case COVER_EDGE -> choices.cover;
            default -> null;
        };
    }

    private static boolean addTerrain(List<Candidate> result, Macro macro, Candidate attack, CombatTerrain.Route route,
        CombatWorkQuota quota) {
        if (route == null) return true;
        if (macro == Macro.STRAFE_LEFT || macro == Macro.STRAFE_RIGHT)
            for (Candidate other : result) if (other.macro == Macro.COVER_EDGE
                && other.destination.distance(route.destination) < CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_2)
                return true;
        Candidate candidate = new Candidate(macro, attack.target, attack.weapon, route.destination, attack.staticValue);
        candidate.route(route);
        return add(result, candidate, quota);
    }

    private static boolean usable(CombatSnapshot snapshot, Weapon weapon) {
        return weapon != null && weapon.profile.supported()
            && (weapon.profile.remainingDurability < 0
                || weapon.profile.remainingDurability >= weapon.profile.durabilityCost)
            && (!weapon.profile.bow() || snapshot.self.ammunition > 0);
    }

    private static Candidate attack(CombatSnapshot snapshot, Target target, Weapon weapon) {
        double distance = snapshot.self.position.distance(target.position), damage = physical(weapon);
        int cooldown = remainingCooldown(snapshot);
        double delay = weapon.profile.bow()
            ? Math.max(
                cooldown,
                Math.max(
                    CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE,
                    CombatPlanTuning.COMBAT_PLANNER_MAX_SECOND_PARAM
                        - (weapon.slot == CombatPlanTuning.UNSET_OR_NOT_FOUND ? snapshot.self.drawTicks
                            : CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)))
            : Math.max(cooldown, snapshot.self.attackDelay)
                + Math.max(CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE, distance - snapshot.self.reach)
                    / CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR;
        double value = Math.min(CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE, damage / target.health)
            / (CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE
                + delay / CombatPlanTuning.COMBAT_PLANNER_MAX_SECOND_PARAM);
        if (target.attackingOwner) value += CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR_2;
        if (!target.visible) value -= CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR_3;
        if (weapon.slot >= 0) value -= CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR_4;
        if (snapshot.self.drawing && !weapon.profile.bow())
            value -= snapshot.self.drawTicks / CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR_5;
        if (snapshot.self.preferencePending && weapon.profile.bow() == snapshot.self.preferBow)
            value += CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR_6;
        Macro macro = !target.visible || !weapon.profile.bow() && distance > snapshot.self.reach ? Macro.APPROACH
            : Macro.HOLD_ATTACK;
        return new Candidate(macro, target, weapon, destination(snapshot, target, macro), value);
    }

    private static Vec destination(CombatSnapshot snapshot, Target target, Macro macro) {
        if (target == null || snapshot.self.stationary) return snapshot.self.position;
        Vec toward = target.position.subtract(snapshot.self.position)
            .horizontalUnit();
        return switch (macro) {
            case RETREAT -> snapshot.self.position
                .add(toward.scale(CombatPlanTuning.COMBAT_PLANNER_DESTINATION_SCALE_X));
            case STRAFE_LEFT, COVER_EDGE -> snapshot.self.position.add(
                new Vec(-toward.z, CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE, toward.x)
                    .scale(CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM));
            case STRAFE_RIGHT -> snapshot.self.position.add(
                new Vec(toward.z, CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE, -toward.x)
                    .scale(CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM));
            case APPROACH -> snapshot.self.position.add(
                toward.scale(
                    Math.min(
                        CombatPlanTuning.COMBAT_PLANNER_DESTINATION_PARAM,
                        Math.max(
                            CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                            snapshot.self.position.distance(target.position)
                                - snapshot.self.reach * CombatPlanTuning.COMBAT_PLANNER_MAX_SECOND_FACTOR))));
            default -> snapshot.self.position;
        };
    }

    private static boolean add(List<Candidate> result, Candidate candidate, CombatWorkQuota quota) {
        for (Candidate existing : result) if (equivalent(existing, candidate)) return true;
        if (result.size() >= CombatBudgetTuning.MAX_CANDIDATES || !quota.spend(Work.CANDIDATE)) return false;
        result.add(candidate);
        return true;
    }

    /** 只有同策略、同当前连接才能复用必要名额，不把同macro的另一条路线当成已经比较 */
    private static boolean equivalent(Candidate a, Candidate b) {
        if (a.strategy != b.strategy || a.macro != b.macro
            || a.target != b.target
            || a.weapon != b.weapon
            || a.destination.distance(b.destination) >= CombatPlanTuning.COMBAT_PLANNER_EQUIVALENT_FACTOR) return false;
        if (a.route == b.route) return true;
        if (a.route == null || b.route == null
            || a.route.nodes.size() != b.route.nodes.size()
            || a.route.defenseNode != b.route.defenseNode
            || a.route.conditionalDoor != b.route.conditionalDoor) return false;
        if (a.route.conditionalDoor && Math.abs(a.route.stopBeforeDoor - b.route.stopBeforeDoor)
            >= CombatPlanTuning.COMBAT_PLANNER_EQUIVALENT_FACTOR) return false;
        for (int i = 0; i < a.route.nodes.size(); i++) if (a.route.nodes.get(i)
            .distance(b.route.nodes.get(i)) >= CombatPlanTuning.COMBAT_PLANNER_EQUIVALENT_FACTOR) return false;
        return true;
    }

    private static boolean jumpUseful(CombatSnapshot snapshot, Candidate candidate) {
        Weapon weapon = candidate.weapon;
        Target target = candidate.target;
        if (weapon == null || !weapon.profile.melee()
            || weapon.profile.criticalMultiplier <= CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE
            || !snapshot.self.jumpAllowed
            || !snapshot.self.grounded
            || remainingCooldown(snapshot) > CombatPlanTuning.JUMP_PREPARATION_TICKS
            || snapshot.self.stationary
            || !target.visible
            || snapshot.geometry == null
            || !snapshot.geometry.complete
            || !snapshot.geometry.fresh(snapshot.tick)
            || snapshot.self.position.distance(target.position) > snapshot.self.reach
            || target.lastOwnHitAt != Long.MIN_VALUE && snapshot.tick >= target.lastOwnHitAt
                && snapshot.tick - target.lastOwnHitAt < CombatPlanTuning.MIN_LAST_OWN_HIT_AT_THRESHOLD)
            return false;
        double normal = physical(weapon),
            critical = weapon.profile.estimatedMeleePhysical * weapon.profile.criticalMultiplier
                + weapon.profile.sharpness * CombatPlanTuning.COMBAT_PLANNER_JUMP_USEFUL_FACTOR;
        return Math.min(target.health, critical) - Math.min(target.health, normal) > Math.max(
            CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR,
            normal * CombatPlanTuning.COMBAT_PLANNER_CANDIDATE_FIFTH_FACTOR);
    }

    private static void fallback(CombatSnapshot snapshot, Candidate candidate, CombatWorkQuota quota,
        ThreatEstimator.ProjectileOcclusion occlusion) {
        if (!quota.spend(Work.FALLBACK)) {
            candidate.utility = CombatPlanTuning.COMBAT_PLANNER_UTILITY;
            return;
        }
        candidate.damage = candidate.risk = CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
        if (candidate.macro == Macro.JUMP_STRIKE || candidate.macro == Macro.COVER_EDGE && !candidate.continuing) {
            candidate.utility = CombatPlanTuning.COMBAT_PLANNER_UTILITY;
            return;
        }
        Vec next = candidate.continuing ? candidate.nextStep : candidate.destination;
        if (next.distance(snapshot.self.position) > CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_2
            && (candidate.continuing
                ? !CombatTerrain.safeStep(snapshot, snapshot.self.position, next, quota)
                    && !(candidate.stage != null && candidate.stage.admitted
                        && candidate.stage.connector == null
                        && candidate.commits()
                        && candidate.alignment == null
                        && candidate.entry == null
                        && candidate.route != null
                        && candidate.route.conditionalDoor
                        && candidate.route.fresh(snapshot.tick)
                        && CombatTerrain.safeRequestStep(snapshot, snapshot.self.position, next, quota))
                : !ThreatEstimator.safeMove(snapshot, snapshot.self.position, next, true, quota))) {
            candidate.utility = CombatPlanTuning.COMBAT_PLANNER_UTILITY;
            return;
        }
        candidate.risk = fallbackRisk(snapshot, next, candidate.macro == Macro.SHIELD, quota, occlusion, null);
        if (!Double.isFinite(candidate.risk)) {
            candidate.utility = CombatPlanTuning.COMBAT_PLANNER_UTILITY;
            candidate.riskKnown = false;
            return;
        }
        // 观测/几何缺口下只允许当前可见攻击、合法举盾或已知安全移动；不按静态高输出贴脸换血
        double progress = candidate.target != null && candidate.target.visible ? candidate.staticValue
            : CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_3;
        if (candidate.weapon != null && candidate.weapon.profile.bow()
            && !ThreatEstimator.friendlyLine(
                snapshot,
                snapshot.self.position.add(
                    new Vec(
                        CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                        CombatPlanTuning.COMBAT_PLANNER_VEC_SECOND_FACTOR,
                        CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)),
                candidate.target.position.add(
                    new Vec(
                        CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                        candidate.target.height * CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR,
                        CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)),
                quota))
            progress = CombatPlanTuning.COMBAT_PLANNER_PROGRESS;
        candidate.utility = progress - CombatPlanTuning.SELF_WEIGHT * candidate.risk / snapshot.self.health;
        if (candidate.macro == Macro.SHIELD) candidate.utility += CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_2;
        if (candidate.weapon != null && candidate.weapon.slot >= 0)
            candidate.utility -= CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR;
    }

    /** Short reaction horizon, limited by the same per-tick movement model as the full forecast. */
    private static double fallbackRisk(CombatSnapshot snapshot, Vec destination, boolean shield, CombatWorkQuota quota,
        ThreatEstimator.ProjectileOcclusion occlusion, ThreatEstimator.Risk risk) {
        Vec position = snapshot.self.position;
        double total = 0;
        int horizon = hasFreshProjectile(snapshot)
            ? CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM * CombatBudgetTuning.STEP
            : CombatBudgetTuning.STEP;
        boolean[] projectileHits = new boolean[snapshot.projectiles.size()];
        for (int elapsed = CombatBudgetTuning.STEP; elapsed <= horizon; elapsed += CombatBudgetTuning.STEP) {
            Vec delta = destination.subtract(position);
            double step = snapshot.self.stationary ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE
                : Math.max(CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE, snapshot.self.movementPerTick)
                    * CombatBudgetTuning.STEP;
            Vec next = position.add(
                delta.scale(
                    Math.min(
                        CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE,
                        step / Math.max(CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR_2, delta.length()))));
            double value = ThreatEstimator.damage(
                snapshot,
                position,
                next,
                elapsed,
                CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                shield,
                quota,
                occlusion,
                risk,
                projectileHits);
            if (!Double.isFinite(value)) return Double.POSITIVE_INFINITY;
            total += value;
            position = next;
        }
        return total;
    }

    private static boolean hasFreshProjectile(CombatSnapshot snapshot) {
        for (var projectile : snapshot.projectiles) if (projectile.observedAt <= snapshot.tick
            && snapshot.tick - projectile.observedAt <= CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM)
            return true;
        return false;
    }

    private static void forecast(CombatSnapshot snapshot, Candidate candidate, CombatWorkQuota quota,
        ThreatEstimator.ProjectileOcclusion occlusion) {
        if (candidate.macro == Macro.COVER_EDGE && (candidate.route == null || candidate.route.escape == null)) {
            candidate.utility = CombatPlanTuning.COMBAT_PLANNER_UTILITY;
            candidate.evaluated = true;
            return;
        }
        double worst = Double.POSITIVE_INFINITY, risk = CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
            damage = CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
        candidate.riskKnown = true;
        for (int hypothesis = 0; hypothesis < CombatBudgetTuning.HYPOTHESES; hypothesis++) {
            Vec position = snapshot.self.position;
            var reserve = new ThreatEstimator.Risk();
            double received = 0, dealt = CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                ownerRisk = CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                verticalSpeed = snapshot.self.grounded ? CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_4
                    : snapshot.self.velocity.y;
            int ammunition = snapshot.self.ammunition;
            long durability = candidate.weapon == null ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE
                : candidate.weapon.profile.remainingDurability;
            int swap = candidate.weapon != null && candidate.weapon.slot >= 0
                ? CombatPlanTuning.COMBAT_PLANNER_DESTINATION_PARAM
                : CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
            // 自有真实截止时刻与换装阶段可以同时经过，不能把false误当成又等整周期
            int nextAttack = Math.max(swap, remainingCooldown(snapshot));
            if (candidate.weapon != null && candidate.weapon.profile.bow()) nextAttack = Math.max(
                nextAttack,
                swap + Math.max(
                    CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE,
                    CombatPlanTuning.COMBAT_PLANNER_MAX_SECOND_PARAM
                        - (swap == 0 ? snapshot.self.drawTicks : CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)));
            int jumpAt = snapshot.self.grounded ? nextAttack : CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
            boolean jumpPending = candidate.macro == Macro.JUMP_STRIKE && snapshot.self.grounded;
            boolean airborne = candidate.macro == Macro.JUMP_STRIKE && !snapshot.self.grounded;
            boolean landed = !airborne, hit = false, invalid = false;
            for (int elapsed = CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM; elapsed
                <= CombatBudgetTuning.HORIZON; elapsed += CombatBudgetTuning.STEP) {
                if (!quota.spend(Work.ADVANCE)) {
                    candidate.utility = CombatPlanTuning.COMBAT_PLANNER_UTILITY;
                    return;
                }
                Vec next = position;
                if (!snapshot.self.stationary && elapsed > swap) {
                    Vec delta = candidate.destination.subtract(position)
                        .horizontalUnit()
                        .scale(
                            Math.min(
                                snapshot.self.movementPerTick * CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM,
                                candidate.destination.subtract(position)
                                    .length()));
                    next = candidate.route == null ? position.add(delta)
                        : candidate.route.at(snapshot.self.movementPerTick * (elapsed - swap));
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
                    int verticalSteps = Math.min(CombatBudgetTuning.STEP, elapsed - jumpAt);
                    for (int part = 0; part < verticalSteps; part++) {
                        y += verticalSpeed;
                        verticalSpeed = (verticalSpeed - CombatPlanTuning.COMBAT_PLANNER_VERTICAL_SPEED)
                            * CombatPlanTuning.COMBAT_PLANNER_VERTICAL_SPEED_2;
                    }
                    descending = verticalSpeed < 0;
                    if (y <= snapshot.self.position.y) {
                        y = snapshot.self.position.y;
                        airborne = false;
                        landed = true;
                    }
                    next = new Vec(next.x, y, next.z);
                }
                if (position.distance(next) > CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR_2
                    && (candidate.route == null ? !ThreatEstimator.safeMove(snapshot, position, next, !airborne, quota)
                        : !quota.spend(Work.COLLISION))) {
                    invalid = true;
                    break;
                }
                received += ThreatEstimator.damage(
                    snapshot,
                    position,
                    next,
                    elapsed,
                    hypothesis,
                    candidate.macro == Macro.SHIELD,
                    quota,
                    occlusion,
                    reserve);
                position = next;
                if (candidate.target != null && candidate.target.attackingOwner
                    && snapshot.self.ownerPosition != null) {
                    Vec enemy = ThreatEstimator
                        .enemyPosition(candidate.target, snapshot.self.ownerPosition, elapsed, hypothesis);
                    if (enemy.distance(snapshot.self.ownerPosition) < CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_5)
                        ownerRisk += candidate.target.estimatedDamage * CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR;
                }
                if (candidate.target == null || candidate.weapon == null
                    || !candidate.target.visible
                    || candidate.macro == Macro.SHIELD
                    || candidate.macro == Macro.RETREAT
                        && (candidate.strategy != Strategy.DEFENSIVE_RETURN || candidate.route == null
                            || snapshot.self.movementPerTick * (elapsed - swap) <= candidate.route.defenseDistance)
                    || elapsed < nextAttack) continue;
                Vec enemy = ThreatEstimator
                    .enemyPosition(candidate.target, snapshot.self.position, elapsed, hypothesis);
                boolean bow = candidate.weapon.profile.bow();
                if (bow && ammunition <= 0 || durability >= 0 && durability < candidate.weapon.profile.durabilityCost)
                    continue;
                if (!bow && position.distance(enemy) > snapshot.self.reach + candidate.target.halfWidth) continue;
                if (candidate.macro == Macro.JUMP_STRIKE && !descending && !hit) continue;
                if (ThreatEstimator.localCover(snapshot, position, enemy, quota)) continue;
                if (candidate.route != null
                    && position.distance(snapshot.self.position) > CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_2
                    && CombatTerrain.sight(
                        snapshot,
                        position.add(
                            new Vec(
                                CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                                snapshot.self.height * CombatPlanTuning.COMBAT_PLANNER_VEC_SECOND_FACTOR_2,
                                CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)),
                        enemy.add(
                            new Vec(
                                CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                                candidate.target.height * CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR,
                                CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)),
                        quota) != CombatTerrain.Sight.CLEAR)
                    continue;
                if (bow && !ThreatEstimator.friendlyLine(
                    snapshot,
                    position.add(
                        new Vec(
                            CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                            CombatPlanTuning.COMBAT_PLANNER_VEC_SECOND_FACTOR,
                            CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)),
                    enemy.add(
                        new Vec(
                            CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                            candidate.target.height * CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR,
                            CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE)),
                    quota)) continue;
                // 只估计有机会命中的收益，当前完整弹道仍由真实放箭入口重验
                double amount = physical(candidate.weapon);
                if (candidate.macro == Macro.JUMP_STRIKE && descending && !hit)
                    amount += candidate.weapon.profile.estimatedMeleePhysical
                        * (candidate.weapon.profile.criticalMultiplier - 1);
                double uncertainty = Math.max(
                    CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR_3,
                    CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE
                        - elapsed * CombatPlanTuning.COMBAT_PLANNER_UNCERTAINTY);
                dealt += amount * uncertainty;
                if (bow && !candidate.weapon.profile.infinity) ammunition--;
                if (durability >= 0) durability -= candidate.weapon.profile.durabilityCost;
                hit = true;
                nextAttack = elapsed
                    + (bow ? Math.max(CombatPlanTuning.COMBAT_PLANNER_MAX_SECOND_PARAM, snapshot.self.attackDelay)
                        : snapshot.self.attackDelay);
            }
            if (invalid || !Double.isFinite(received)) {
                candidate.utility = CombatPlanTuning.COMBAT_PLANNER_UTILITY;
                candidate.evaluated = quota.remaining(Work.COLLISION) > 0;
                return;
            }
            double terminal = !landed ? CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_6
                : CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
            if (!hit && candidate.weapon != null) {
                int remaining = Math
                    .max(CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE, nextAttack - CombatBudgetTuning.HORIZON);
                terminal += CombatPlanTuning.COMBAT_PLANNER_VERTICAL_SPEED + Math.min(
                    CombatPlanTuning.COMBAT_PLANNER_MIN_FIRST_FACTOR,
                    remaining / CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR_5);
            }
            if (candidate.route != null) {
                double available = Math.min(
                    snapshot.self.movementPerTick
                        * Math.max(CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE, CombatBudgetTuning.HORIZON - swap),
                    candidate.route.stopBeforeDoor);
                terminal += Math.min(
                    CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR_3,
                    Math.max(
                        CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                        candidate.route.currentLength() - available) * CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_2);
                if (candidate.route.conditionalDoor) terminal += CombatPlanTuning.COMBAT_PLANNER_VERTICAL_SPEED;
            }
            double progress = candidate.target == null ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE
                : Math.min(CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE, dealt / candidate.target.health);
            if (candidate.target != null && !snapshot.self.stationary) {
                double improvement = snapshot.self.position.distance(candidate.target.position)
                    - position.distance(candidate.target.position);
                // 横移用于规避威胁或改善射界，不借栅格取整伪造接近收益引发无收益绕圈
                if (candidate.macro == Macro.STRAFE_LEFT || candidate.macro == Macro.STRAFE_RIGHT) {
                    improvement = Math.min(CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE, improvement);
                    terminal += CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_7;
                }
                progress += Math.max(
                    CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR_4,
                    Math.min(
                        CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR,
                        improvement / CombatPlanTuning.COMBAT_PLANNER_MIN_SECOND_PARAM));
            }
            double resource = candidate.weapon == null || candidate.weapon.profile.remainingDurability <= 0
                ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE
                : Math.min(
                    CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE,
                    candidate.weapon.profile.durabilityCost / (double) candidate.weapon.profile.remainingDurability);
            double continuity = candidate.weapon != null && candidate.weapon.slot >= 0
                ? CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_8
                : CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE;
            if (snapshot.self.drawing
                && (candidate.weapon == null || !candidate.weapon.profile.bow() || candidate.macro == Macro.SHIELD))
                continuity += snapshot.self.drawTicks / CombatPlanTuning.COMBAT_PLANNER_DOMAIN_FACTOR_9;
            double utility = CombatPlanTuning.PROGRESS_WEIGHT * progress
                - CombatPlanTuning.SELF_WEIGHT * received / snapshot.self.health
                - CombatPlanTuning.OWNER_WEIGHT * Math.max(
                    CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE,
                    ownerRisk - dealt * CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR) / snapshot.self.ownerHealth
                - CombatPlanTuning.RESOURCE_WEIGHT * resource
                - CombatPlanTuning.CONTINUITY_WEIGHT * continuity
                - terminal;
            if (candidate.target != null && candidate.target.attackingOwner) utility += Math.min(
                CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR,
                progress * CombatPlanTuning.COMBAT_PLANNER_ATTACK_FACTOR);
            worst = Math.min(worst, utility);
            risk = Math.max(risk, received);
            candidate.singleSelf = Math.max(candidate.singleSelf, reserve.selfSingle);
            candidate.singleOwner = Math.max(candidate.singleOwner, reserve.ownerSingle);
            candidate.ownerRisk = Math.max(candidate.ownerRisk, reserve.ownerReceived);
            candidate.riskKnown &= reserve.known;
            damage = hypothesis == 0 ? dealt : Math.min(damage, dealt);
        }
        candidate.evaluated = true;
        candidate.utility = worst;
        candidate.risk = risk;
        candidate.damage = damage;
    }

    private CombatPlan plan(CombatSnapshot snapshot, Candidate candidate, CombatWorkQuota quota, boolean simulated,
        Trace trace) {
        if (candidate == null || !Double.isFinite(candidate.utility)
            || candidate.utility <= CombatPlanTuning.MIN_UTILITY_THRESHOLD)
            candidate = new Candidate(
                Macro.WAIT,
                snapshot.target(snapshot.selectedTarget),
                snapshot.weapon(CombatPlanTuning.UNSET_OR_NOT_FOUND),
                snapshot.self.position,
                CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE);
        boolean same = same(candidate, previous);
        if (!same) heldSince = snapshot.tick;
        if (candidate.strategy != Strategy.PURE_DEFENSE) arrowDefenseStarted = Long.MIN_VALUE;
        else if (!same) arrowDefenseStarted = hasFreshProjectile(snapshot) ? snapshot.tick : Long.MIN_VALUE;
        long id = same ? previous.decisionId : ++nextDecision;
        Macro macro = candidate.weapon != null && candidate.weapon.slot >= 0
            && candidate.macro != Macro.SHIELD
            && candidate.macro != Macro.WAIT ? Macro.PREPARE_WEAPON : candidate.macro;
        if (candidate.commits() && normalProgressAdmitted && stage == null && candidate.route != null)
            stage = new Stage(++nextStage, snapshot, candidate);
        Stage committed = candidate.commits() ? stage : null;
        if (stage != null && committed == null && stage.state != StageState.NATIVE_FAILURE) {
            stage.state = StageState.WAIT_SERVICE;
            stage.reason = CombatPlanIds.KEY_SAFE_REACTION_OR_COMPARISON_WAIT;
        } else if (committed != null && normalProgressAdmitted && committed.state == StageState.WAIT_SERVICE) {
            committed.state = StageState.PROPOSED;
            committed.reason = CombatPlanIds.KEY_FULL_SERVICE_RESUMED;
        }
        if (stage != null && stage.entry != null && (committed == null || candidate.entry != stage.entry)) {
            if (committed != null && normalProgressAdmitted
                && simulated
                && stage.entry.state == EntryState.READY_WAIT_FULL) stage.entry = null;
            else if (stage.entry.state != EntryState.READY_WAIT_FULL && stage.entry.state != EntryState.NATIVE_FAILURE)
                stage.entry.suspend();
        }
        CombatConditionalEntryProof entryProof = null;
        if (committed != null && candidate.entry != null) {
            ConditionalEntry entry = candidate.entry;
            if (simulated && normalProgressAdmitted && comparisonReady) {
                committed.entry = entry;
                committed.connector = null;
                committed.alignment = null;
                entry.route = candidate.route;
                entry.proof = new CombatConditionalEntryProof(
                    entry.node(snapshot, committed),
                    snapshot,
                    candidate.weapon.fingerprint,
                    ++entry.admissionSerial);
                if (entry.state != EntryState.ACTIVE) entry.state = EntryState.PROPOSED;
            }
            entryProof = entry.proof;
        }
        CombatMovementFact.Node node = committed == null ? null
            : candidate.entry != null ? candidate.entry.node(snapshot, committed)
                : candidate.alignment != null ? candidate.alignment.node(snapshot, committed)
                    : committed.connector == null ? committed.node(snapshot)
                        : committed.connector.node(snapshot, committed);
        CombatAlignmentProof alignmentProof = null;
        if (committed != null && candidate.alignment != null) {
            Alignment alignment = candidate.alignment;
            if (simulated && normalProgressAdmitted && comparisonReady) {
                alignment.proof = new CombatAlignmentProof(node, snapshot, candidate.weapon.fingerprint);
                if (alignment.state != AlignmentState.ACTIVE) alignment.state = AlignmentState.PROPOSED;
            }
            alignmentProof = alignment.proof;
        }
        if (node == null && candidate.route != null) {
            int index = candidate.route == followedRoute ? followedNode : CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE;
            node = new CombatMovementFact.Node(
                id * CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM + 1,
                index,
                snapshot,
                candidate.target.id,
                candidate.target.entityId,
                candidate.route.next(index));
        }
        previous = new CombatPlan(
            id,
            snapshot,
            candidate.target,
            candidate.weapon,
            macro,
            candidate.destination,
            candidate.target == null ? snapshot.self.position : candidate.target.position,
            simulated ? CombatPlanIds.ID_BOUNDED_FORECAST : CombatPlanIds.ID_BOUNDED_REACTION,
            candidate.utility,
            candidate.damage,
            candidate.risk,
            simulated,
            quota,
            candidate.route,
            trace,
            candidate.strategy,
            stage == null ? StageState.NONE : stage.state,
            stage == null ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE : stage.id,
            stage == null ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE
                : stage.strategy == Strategy.DEFENSIVE_RETURN && stage.cursor <= stage.route.defenseNode
                    ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE
                    : CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE,
            stage == null ? null : stage.route.destination,
            node,
            normalProgressAdmitted,
            simulated && comparisonReady,
            stage == null ? Long.MIN_VALUE : stage.firstAcceptedAt,
            stage == null ? CombatPlanTuning.COMBAT_PLAN_DEFAULT_ZERO_BASE : stage.retryAt,
            stage == null ? "" : stage.reason,
            null,
            null,
            alignmentProof,
            entryProof);
        if (simulated) {
            if (candidate.route == null) clearRouteProposal();
            else {
                if (followedRoute != candidate.route) followedNode = CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE;
                followedRoute = candidate.route;
                routeProposal = previous;
                proposalSelfId = snapshot.selfId;
            }
        }
        return previous;
    }

    private static boolean same(Candidate candidate, CombatPlan plan) {
        return plan != null && candidate.strategy == plan.strategy
            && candidate.macro == plan.macro
            && candidate.weapon != null
            && candidate.weapon.fingerprint.equals(plan.weaponFingerprint)
            && Objects.equals(candidate.target == null ? null : candidate.target.id, plan.targetId)
            && (candidate.route == null || plan.route == null
                || candidate.destination.distance(plan.destination) < CombatPlanTuning.COMBAT_PLANNER_MAX_FIRST_FACTOR);
    }

    private static int remainingCooldown(CombatSnapshot snapshot) {
        long remaining = snapshot.self.cadenceRemainingTicks;
        return remaining < 0 ? Integer.MAX_VALUE / CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM
            : (int) Math.min(Integer.MAX_VALUE / CombatPlanTuning.COMBAT_PLANNER_MIN_FIRST_LIMIT, remaining);
    }

    private static double physical(Weapon weapon) {
        return weapon.profile.bow()
            ? CombatPlanTuning.COMBAT_PLANNER_PHYSICAL_PARAM
                + weapon.profile.power * CombatPlanTuning.COMBAT_PLANNER_PHYSICAL_FACTOR
            : weapon.profile.estimatedMeleePhysical
                + weapon.profile.sharpness * CombatPlanTuning.COMBAT_PLANNER_JUMP_USEFUL_FACTOR;
    }

    private static final class Stage {

        final long id, entityEpoch, observationEpoch;
        final UUID self, target;
        final int targetEntityId;
        final Strategy strategy;
        final Macro firstMacro;
        final CombatTerrain.Route route;
        String weapon;
        final MaidWeaponProfile.Family family;
        boolean escape, admitted;
        double risk, single, ownerRisk, ownerSingle;
        int cursor = CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE;
        long retryAt, firstAcceptedAt = Long.MIN_VALUE;
        String reason = CombatPlanIds.KEY_NOT_INSTALLED;
        StageState state = StageState.PROPOSED;
        CombatTerrain.Route lastProof;
        Connector connector;
        CombatMovementFact lastCompleted;
        Alignment alignment;
        ConditionalEntry entry;

        boolean completedSource() {
            return cursor > CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE && lastCompleted != null
                && lastCompleted.node != null
                && lastCompleted.node.role == CombatMovementFact.Role.ORDINARY
                && lastCompleted.node.stage == id * CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM
                && lastCompleted.node.index == cursor - 1
                && lastCompleted.completed(lastCompleted.node, lastCompleted.tick)
                && lastCompleted.node.foot.distance(route.next(cursor - 1))
                    < CombatPlanTuning.COMBAT_PLANNER_EQUIVALENT_FACTOR;
        }

        Stage(long id, CombatSnapshot snapshot, Candidate candidate) {
            this.id = id;
            self = snapshot.selfId;
            target = candidate.target.id;
            targetEntityId = candidate.target.entityId;
            entityEpoch = snapshot.entityEpoch;
            observationEpoch = snapshot.observationEpoch;
            strategy = candidate.strategy;
            firstMacro = candidate.macro;
            route = lastProof = candidate.route;
            weapon = candidate.weapon.fingerprint;
            family = candidate.weapon.profile.family;
            escape = candidate.escapeProved;
            admitted = true;
            risk = candidate.risk;
            single = candidate.singleSelf;
            ownerRisk = candidate.ownerRisk;
            ownerSingle = candidate.singleOwner;
        }

        boolean matches(CombatSnapshot snapshot, Target other) {
            return Objects.equals(self, snapshot.selfId) && other != null
                && Objects.equals(target, other.id)
                && targetEntityId == other.entityId
                && entityEpoch == snapshot.entityEpoch
                && observationEpoch == snapshot.observationEpoch;
        }

        CombatMovementFact.Node node(CombatSnapshot snapshot) {
            return new CombatMovementFact.Node(
                id * CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM,
                cursor,
                snapshot,
                target,
                targetEntityId,
                route.next(cursor));
        }

        boolean survivable(CombatSnapshot snapshot) {
            return risk + single < snapshot.self.health && ownerRisk + ownerSingle < snapshot.self.ownerHealth;
        }
    }

    private enum EntryState {
        PROPOSED,
        ACTIVE,
        WAIT_SERVICE,
        READY_WAIT_FULL,
        NATIVE_FAILURE
    }

    private static final class ConditionalEntry {

        final long id, parentId, parentCursor, equipmentEpoch;
        final Vec sourceA, k, b;
        final String weapon;
        final CombatConditionalEntryProof.DoorEdge edge;
        CombatTerrain.Route route;
        CombatConditionalEntryProof proof;
        CombatConditionalEntryProof.Arrival arrival;
        EntryState state = EntryState.PROPOSED;
        long admissionSerial, firstAcceptedAt = Long.MIN_VALUE, retryAt;

        ConditionalEntry(long id, Stage parent, CombatSnapshot s, CombatTerrain.EntrySource source) {
            this.id = id;
            parentId = parent.id;
            parentCursor = parent.cursor;
            equipmentEpoch = s.equipmentEpoch;
            sourceA = source.sourceA;
            k = source.entryK;
            b = source.exactB;
            edge = source.edge;
            weapon = parent.weapon;
            route = source.route;
        }

        void suspend() {
            state = EntryState.WAIT_SERVICE;
            proof = null;
            arrival = null;
        }

        CombatMovementFact.Node node(CombatSnapshot s, Stage parent) {
            return CombatMovementFact.Node.conditionalEntry(
                id,
                parentId * CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM,
                parentCursor,
                s,
                parent.target,
                parent.targetEntityId,
                k,
                b,
                edge);
        }
    }

    private enum AlignmentState {
        PROPOSED,
        ACTIVE,
        WAIT_SERVICE,
        READY,
        NATIVE_FAILURE
    }

    private static final class Alignment {

        final long id, parentId;
        final int parentCursor;
        final Vec center, next;
        CombatTerrain.Route route;
        CombatAlignmentProof proof;
        AlignmentState state = AlignmentState.PROPOSED;
        long firstAcceptedAt = Long.MIN_VALUE, retryAt;

        Alignment(long id, Stage parent, Vec center, Vec next) {
            this.id = id;
            parentId = parent.id;
            parentCursor = parent.cursor;
            this.center = center;
            this.next = next;
        }

        boolean matches(Stage parent, Vec c, Vec b) {
            return parentId == parent.id && parentCursor == parent.cursor
                && center.distance(c) < CombatPlanTuning.COMBAT_PLANNER_EQUIVALENT_FACTOR
                && next.distance(b) < CombatPlanTuning.COMBAT_PLANNER_EQUIVALENT_FACTOR;
        }

        CombatMovementFact.Node node(CombatSnapshot s, Stage parent) {
            return CombatMovementFact.Node.alignment(
                id,
                parent.id * CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM,
                parent.cursor,
                s,
                parent.target,
                parent.targetEntityId,
                center,
                next);
        }
    }

    private static final class Connector {

        final long id;
        final CombatTerrain.Route route;
        int cursor = CombatPlanTuning.COMBAT_PLANNER_FOLLOWED_NODE;

        Connector(long id, CombatTerrain.Route route) {
            this.id = id;
            this.route = route;
        }

        CombatMovementFact.Node node(CombatSnapshot s, Stage parent) {
            return new CombatMovementFact.Node(
                id * CombatPlanTuning.COMBAT_PLANNER_DAMAGE_FOURTH_PARAM,
                cursor,
                s,
                parent.target,
                parent.targetEntityId,
                route.next(cursor));
        }
    }

    private static final class Candidate {

        final Macro macro;
        final Strategy strategy;
        final Target target;
        final Weapon weapon;
        Vec destination;
        CombatTerrain.Route route;
        final double staticValue;
        double utility, damage, risk;
        boolean evaluated, continuing, retained, escapeProved, riskKnown;
        double singleSelf, singleOwner, ownerRisk;
        Stage stage;
        Alignment alignment;
        ConditionalEntry entry;
        Vec nextStep;

        Candidate(Macro macro, Target target, Weapon weapon, Vec destination, double staticValue) {
            this(
                macro,
                target,
                weapon,
                destination,
                staticValue,
                macro == Macro.RETREAT || macro == Macro.SHIELD || macro == Macro.COVER_EDGE ? Strategy.PURE_DEFENSE
                    : Strategy.REACTION);
        }

        Candidate(Macro macro, Target target, Weapon weapon, Vec destination, double staticValue, Strategy strategy) {
            this.macro = macro;
            this.strategy = strategy;
            this.target = target;
            this.weapon = weapon;
            this.destination = destination;
            this.staticValue = staticValue;
        }

        boolean commits() {
            return strategy == Strategy.TASK || strategy == Strategy.DEFENSIVE_RETURN;
        }

        boolean task() {
            return commits() || damage > 0 && macro != Macro.RETREAT && macro != Macro.SHIELD;
        }

        void route(CombatTerrain.Route value) {
            route = value;
            destination = value.destination;
        }
    }
}
