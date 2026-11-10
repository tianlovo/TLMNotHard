import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatGeometryWindow;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Weapon;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatTerrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.ThreatEstimator;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;

/** 无Minecraft类加载的实际纯规划检查；输出交给主构建批次运行 */
public final class CombatTacticsChecks {

    private static final UUID SELF = new UUID(0, 1), TARGET = new UUID(0, 2);

    public static void main(String[] args) {
        sweptBodyCannotCrossWall();
        sweptProjectileCannotSkipBody();
        lightProjectileResponseRespectsReachableMovement();
        unknownHandCanSelectOwnedSword();
        unavailableGeometryRejectsJump();
        finiteHorizonChargesUnfinishedDraw();
        worldRoundRobinPreservesWaiters();
        sameTickPromotionSharesCharges();
        preparationExpiryCancelAndReadyBypass();
        snapshotFriendFactsCannotBecomeCurrentByFlag();
        staleReadyHeadCannotBlockAffordableTail();
        boundedCandidatesAndAdvances();
        jumpPreparationUsesOwnedDeadlineAndRejectsBadTradeoffs();
        terrainRouteTurnsAndUsesOnlyRealHalfSteps();
        coverHasExitActualOcclusionAndFullForecast();
        conditionalGateAndUnknownNeverBecomeFreeSpace();
        arrowOcclusionKeepsHitOrderingAndPrefix();
        nativeSizedCoverSurvivesLightOnlyWithinItsProof();
        fullCoverRetainsItsGoalAndRejectsTheObservedCorner();
        coverageGapAndNativeAttackPulseDoNotRenewOrDropTheProposal();
        resourceBalancesCannotCreateUnavailableAttacks();
        visibleTargetWindowRestoresNativeExitWithoutChangingTheQuota();
        fallbackWaitCannotKeepInventedAttackProgress();
        System.out.println("CombatTacticsChecks: original22 mechanisms passed (one historical ROI ranking versioned)");
        genericProgressRetainsDefensiveSafetyExits();
        defensiveReturnRemainsOneBoundedStrategy();
        nativeFactsActivateAndAdvanceWithoutReplayingDefense();
        waitingAndOriginalBackoffCannotBecomeFabricatedFailure();
        nativeNodeFactsRejectWrongFootIdentityAndPhase();
        navigationCompletionPrecedesCleanupAndServiceWaitIsNotFailure();
        continuationServiceAndRequiredComparisonKeepTheirContracts();
        System.out.println("CombatTaskProgressChecks: original6 plus r1 service/comparison causal group passed");
        boundedReobservationMechanisms();
        readyExpiryKeepsFirstPreparationRotation();
        System.out.println(
            "CombatReobservationChecks: seven mechanism groups passed; reconstructed controls, not native replay");
    }

    private static void sweptBodyCannotCrossWall() {
        Geometry geometry = floorGeometry(true);
        CombatSnapshot snapshot = snapshot(
            geometry,
            false,
            20,
            0,
            Arrays.asList(new Weapon(-1, "sword", sword())),
            target(new Vec(2, 0, 0), 30));
        check(
            !ThreatEstimator
                .safeMove(snapshot, new Vec(0, 0, 0), new Vec(3, 0, 0), true, CombatWorkQuota.isolated(100, true)),
            "Two-tick body sweep crossed a one-block wall");
    }

    private static void sweptProjectileCannotSkipBody() {
        CombatSnapshot base = snapshot(
            floorGeometry(false),
            false,
            20,
            0,
            Arrays.asList(new Weapon(-1, "sword", sword())),
            target(new Vec(10, 0, 0), 30));
        CombatSnapshot snapshot = new CombatSnapshot(
            base.tick,
            1,
            1,
            1,
            SELF,
            base.self,
            Collections.emptyList(),
            Arrays.asList(
                new CombatSnapshot.Projectile(
                    new UUID(0, 9),
                    new Vec(-3, .7, 0),
                    new Vec(3, 0, 0),
                    base.tick,
                    0,
                    1,
                    .1,
                    6)),
            Collections.emptyList(),
            base.weapons,
            base.geometry,
            null,
            true);
        check(
            ThreatEstimator
                .damage(snapshot, new Vec(0, 0, 0), new Vec(0, 0, 0), 2, 0, false, CombatWorkQuota.isolated(100, true))
                >= 6,
            "Projectile crossed body between sparse endpoints");
    }

    private static void lightProjectileResponseRespectsReachableMovement() {
        var arrow = new CombatSnapshot.Projectile(
            new UUID(0, 91),
            new Vec(4.9, .7, .5),
            new Vec(-1.56, 0, 0),
            100,
            0,
            .99,
            .3,
            6);
        var frame = progressScene(20, CombatSnapshot.DamageSource.GENERIC_PRIOR, Arrays.asList(arrow));
        boolean prior = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        try {
            var quota = CombatWorkQuota.isolated(100, false);
            var plan = new CombatPlanner().decide(frame, quota);
            check(plan.trace != null && !plan.trace.full && !plan.simulated, "LIGHT unexpectedly became FULL");
            CombatPlanner.CandidateTrace retreat = null, lateral = null;
            for (var candidate : plan.trace.candidates) {
                if (candidate.macro == CombatPlan.Macro.RETREAT) retreat = candidate;
                if (candidate.macro == CombatPlan.Macro.STRAFE_LEFT || candidate.macro == CombatPlan.Macro.STRAFE_RIGHT)
                    if (candidate.utility > -100) lateral = candidate;
            }
            check(retreat != null && retreat.risk >= 6, "Two tick destination teleport hid a chasing arrow");
            check(lateral != null, "LIGHT omitted an available lateral evasive candidate");
            check(quota.used(Work.COLLISION) <= 2400, "LIGHT exceeded its unchanged collision budget");
            var earlierArrow = new CombatSnapshot.Projectile(
                new UUID(0, 92),
                new Vec(6.3, .7, .5),
                new Vec(-1.6, 0, 0),
                100,
                0,
                .99,
                .3,
                6);
            var earlierFrame = progressScene(
                20,
                CombatSnapshot.DamageSource.GENERIC_PRIOR,
                Arrays.asList(earlierArrow));
            var earlierPlanner = new CombatPlanner();
            var earlier = earlierPlanner.decide(earlierFrame, CombatWorkQuota.isolated(100, false));
            check(
                earlier.macro == CombatPlan.Macro.STRAFE_LEFT || earlier.macro == CombatPlan.Macro.STRAFE_RIGHT,
                "Reachable sideways dodge was not selected for an imminent arrow: " + earlier.macro);
            // The native path can need a tick to begin moving. Do not cancel it for equal-risk static output.
            var afterPathInstall = earlierPlanner.decide(
                proposalFrame(
                    earlierFrame,
                    earlierFrame.self,
                    101,
                    1,
                    earlierFrame.geometry,
                    true,
                    earlierFrame.weapons),
                CombatWorkQuota.isolated(101, false));
            check(
                afterPathInstall.macro == earlier.macro,
                "An equally risky attack canceled the newly installed arrow defense");
            var heldSide = candidate(afterPathInstall, earlier.macro);
            check(
                heldSide != null && heldSide.risk >= 6 && heldSide.risk < 7,
                "One arrow was counted twice across adjacent reaction sweeps");
            var fullServiceReturns = earlierPlanner.decide(
                proposalFrame(
                    earlierFrame,
                    earlierFrame.self,
                    103,
                    1,
                    earlierFrame.geometry,
                    true,
                    earlierFrame.weapons),
                CombatWorkQuota.isolated(103, true));
            check(
                fullServiceReturns.macro == earlier.macro,
                "FULL task admission interrupted the safe startup of a just-installed arrow defense");
            var afterStartup = earlierPlanner.decide(
                proposalFrame(
                    earlierFrame,
                    earlierFrame.self,
                    104,
                    1,
                    earlierFrame.geometry,
                    true,
                    earlierFrame.weapons),
                CombatWorkQuota.isolated(104, true));
            check(afterStartup.macro != earlier.macro, "The short arrow startup hold became an indefinite intent");
            var zeroSpeed = new CombatPlanner().decide(
                progressScene(20, CombatSnapshot.DamageSource.GENERIC_PRIOR, Arrays.asList(earlierArrow), 0, false),
                CombatWorkQuota.isolated(100, false));
            for (var c : zeroSpeed.trace.candidates) check(
                c.utility <= -100 || c.risk >= 6,
                "Zero-speed candidate escaped an arrow without reachable movement");
            var seated = new CombatPlanner().decide(
                progressScene(20, CombatSnapshot.DamageSource.GENERIC_PRIOR, Arrays.asList(earlierArrow), .2, true),
                CombatWorkQuota.isolated(100, false));
            check(
                candidate(seated, CombatPlan.Macro.STRAFE_LEFT) == null
                    && candidate(seated, CombatPlan.Macro.STRAFE_RIGHT) == null,
                "Seated maid gained a lateral candidate");
            for (var c : seated.trace.candidates)
                check(c.utility <= -100 || c.risk >= 6, "Seated candidate escaped an arrow without moving");
            var staleArrow = new CombatSnapshot.Projectile(
                new UUID(0, 93),
                earlierArrow.position,
                earlierArrow.velocity,
                97,
                0,
                .99,
                .3,
                6);
            var stale = new CombatPlanner().decide(
                progressScene(20, CombatSnapshot.DamageSource.GENERIC_PRIOR, Arrays.asList(staleArrow)),
                CombatWorkQuota.isolated(100, false));
            check(
                candidate(stale, CombatPlan.Macro.STRAFE_LEFT) == null
                    && candidate(stale, CombatPlan.Macro.STRAFE_RIGHT) == null,
                "Stale projectile opened a fresh lateral reaction window");
            var blockedTerrain = new TerrainFixture();
            blockedTerrain.box(3, 0, 0, 1, Geometry.SOLID);
            var protectedFrame = replaceGeometry(
                frame,
                new Geometry(-4, -2, -4, 9, 6, 9, 100, true, blockedTerrain.cells, blockedTerrain.bounds));
            var blocked = new CombatPlanner().decide(protectedFrame, CombatWorkQuota.isolated(100, false));
            var blockedRetreat = candidate(blocked, CombatPlan.Macro.RETREAT);
            check(
                blockedRetreat != null && blockedRetreat.risk < retreat.risk,
                "Known intervening wall did not reduce arrow risk");
            var unknownTerrain = new TerrainFixture();
            unknownTerrain.box(3, 0, 0, 1, Geometry.UNKNOWN);
            var unknownFrame = replaceGeometry(
                frame,
                new Geometry(-4, -2, -4, 9, 6, 9, 100, false, unknownTerrain.cells, unknownTerrain.bounds));
            var unknown = new CombatPlanner().decide(unknownFrame, CombatWorkQuota.isolated(100, false));
            var unknownRetreat = candidate(unknown, CombatPlan.Macro.RETREAT);
            check(
                unknownRetreat == null || unknownRetreat.utility <= -100 || unknownRetreat.risk >= 6,
                "Unknown space falsely shielded the arrow");
            var exhausted = CombatWorkQuota.isolated(100, false);
            check(exhausted.spend(Work.COLLISION, 2400), "Collision exhaustion setup failed");
            var denied = new CombatPlanner().decide(frame, exhausted);
            check(
                denied.macro != CombatPlan.Macro.STRAFE_LEFT && denied.macro != CombatPlan.Macro.STRAFE_RIGHT
                    && denied.macro != CombatPlan.Macro.RETREAT,
                "Exhausted collision budget allowed evasion");
            System.out.println(
                "LIGHT_ARROW_TEST selected=" + plan.macro
                    + " retreatRisk="
                    + retreat.risk
                    + " sideRisk="
                    + lateral.risk
                    + " candidates="
                    + plan.trace.candidates.size());
        } finally {
            CombatPlanner.setTraceEnabled(prior);
        }
    }

    private static CombatPlanner.CandidateTrace candidate(CombatPlan plan, CombatPlan.Macro macro) {
        for (var c : plan.trace.candidates) if (c.macro == macro) return c;
        return null;
    }

    private static CombatSnapshot replaceGeometry(CombatSnapshot base, Geometry geometry) {
        return new CombatSnapshot(
            base.tick,
            base.entityEpoch,
            base.observationEpoch,
            base.equipmentEpoch,
            base.selfId,
            base.self,
            base.targets,
            base.projectiles,
            base.friends,
            base.weapons,
            geometry,
            base.selectedTarget,
            true);
    }

    private static void unknownHandCanSelectOwnedSword() {
        CombatSnapshot snapshot = snapshot(
            floorGeometry(false),
            false,
            20,
            0,
            Arrays.asList(
                new Weapon(-1, "unknown", MaidWeaponProfile.unknown("unsupported")),
                new Weapon(3, "owned_sword", sword())),
            target(new Vec(2, 0, 0), 30));
        CombatPlan plan = new CombatPlanner().decide(snapshot, CombatWorkQuota.isolated(100, true));
        check(
            plan.weaponSlot == 3 && plan.macro == CombatPlan.Macro.PREPARE_WEAPON,
            "Unsupported hand prevented selection of supported owned backpack weapon");
    }

    private static void unavailableGeometryRejectsJump() {
        CombatSnapshot snapshot = snapshot(
            null,
            false,
            20,
            0,
            Arrays.asList(new Weapon(-1, "sword", sword())),
            target(new Vec(2, 0, 0), 30));
        CombatPlan plan = new CombatPlanner().decide(snapshot, CombatWorkQuota.isolated(100, false));
        check(plan.macro != CombatPlan.Macro.JUMP_STRIKE, "Jump used an unobserved landing");
    }

    private static void finiteHorizonChargesUnfinishedDraw() {
        MaidWeaponProfile bow = new MaidWeaponProfile(
            MaidWeaponProfile.Kind.BOW,
            MaidWeaponProfile.Family.VANILLA_BOW,
            "",
            "",
            0,
            1,
            0,
            1,
            100,
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            false);
        CombatSnapshot snapshot = snapshot(
            floorGeometry(false),
            false,
            100,
            0,
            Arrays.asList(new Weapon(-1, "bow", bow)),
            target(new Vec(8, 0, 0), 30));
        CombatPlan plan = new CombatPlanner().decide(snapshot, CombatWorkQuota.isolated(100, true));
        check(
            plan.expectedDamage == 0 && plan.utility < 0,
            "Unfinished cooldown/draw received a completed-shot reward: " + plan.macro
                + "/"
                + plan.utility
                + "/damage="
                + plan.expectedDamage
                + "/full="
                + plan.simulated
                + "/work="
                + Arrays.toString(plan.quota.counts()));
    }

    private static void worldRoundRobinPreservesWaiters() {
        Object world = new Object();
        int[] served = new int[32], prep = new int[32];
        for (int tick = 0; tick < 96; tick++) {
            for (int i = 0; i < 32; i++) {
                UUID id = new UUID(19, i);
                CombatWorkQuota quota = CombatWorldBudget.heartbeat(world, id, tick, true);
                if (quota.preparing) {
                    prep[i]++;
                    CombatWorldBudget.sampled(world, id, tick, quota.preparationRound);
                    if (prep[i] % 4 == 0) CombatWorldBudget.prepared(world, id, tick, true, tick - 3);
                }
                quota = CombatWorldBudget.heartbeat(world, id, tick, true);
                if (quota.full) {
                    served[i]++;
                    CombatWorldBudget.completed(world, id, tick);
                }
            }
            CombatWorldBudget.Diagnostics d = CombatWorldBudget.diagnostics(world, tick);
            check(d.fullGrants <= 2 && d.preparations.length <= 8, "Preparation/full world slots overflowed");
            if (tick == 31) for (int i = 0; i < 32; i++)
                check(served[i] > 0, "A continuously renewed preparation waiter starved: " + i);
        }
        for (int n : served) check(n >= 2, "Repeated preparation service starved");
        CombatWorldBudget.unload(world);
    }

    /** 固定异质准备速度，保留真实请求直到completed；旧实现的第二次轮转在tick204产生wait33 */
    private static void readyExpiryKeepsFirstPreparationRotation() {
        Object world = new Object();
        int[] need = { 5, 3, 4, 4, 3, 3, 4, 5, 6, 5, 3, 3, 6, 3, 6, 5, 6, 6, 6, 4, 4, 6, 4, 3, 4, 4, 5, 6, 3, 5, 6, 5 };
        long[] last = new long[32], since = new long[32], coverage = new long[32], round = new long[32],
            started = new long[32];
        int[] samples = new int[32];
        Arrays.fill(last, -1000);
        Arrays.fill(since, -1);
        Arrays.fill(coverage, -1000);
        for (int tick = 0; tick < 240; tick++) {
            for (int i = 0; i < 32; i++) {
                UUID id = new UUID(17, i);
                boolean due = tick - last[i] >= 4;
                CombatWorkQuota q = CombatWorldBudget.heartbeat(world, id, tick, false);
                if (q.preparing) {
                    if (round[i] != q.preparationRound) {
                        round[i] = q.preparationRound;
                        started[i] = tick;
                        samples[i] = 0;
                    }
                    if (++samples[i] >= need[i]) coverage[i] = started[i];
                    CombatWorldBudget.sampled(world, id, tick, q.preparationRound);
                }
                boolean ready = tick - coverage[i] <= CombatSnapshot.OBSERVATION_TTL;
                CombatWorldBudget.prepared(world, id, tick, ready, coverage[i]);
                q = CombatWorldBudget.heartbeat(world, id, tick, due);
                CombatWorldBudget.prepared(world, id, tick, ready, coverage[i]);
                q = CombatWorldBudget.heartbeat(world, id, tick, due);
                if (q.full) {
                    last[i] = tick;
                    CombatWorldBudget.completed(world, id, tick);
                    since[i] = -1;
                } else if (due) {
                    if (since[i] < 0) since[i] = tick;
                    check(
                        tick - since[i] <= 32,
                        "Expired READY request was demoted twice beyond32: actor=" + i + " tick=" + tick);
                }
            }
            var diagnostic = CombatWorldBudget.diagnostics(world, tick);
            check(
                diagnostic.fullGrants <= 2 && diagnostic.preparations.length <= 8,
                "Expiry repair expanded world slots");
            for (var row : diagnostic.entries) check(row.sampledTicks <= 6, "Expiry repair extended preparation lease");
        }
        CombatWorldBudget.unload(world);
        System.out.println("Main19 closeout: heterogeneous preparation expiry retained real request fairness");
    }

    private static void sameTickPromotionSharesCharges() {
        Object world = new Object();
        UUID id = new UUID(44, 1);
        CombatWorkQuota light = CombatWorldBudget.heartbeat(world, id, 100, false);
        check(light.spend(Work.ITEM, 3) && light.spendObservation(Work.RAY, 4), "Light acquisition failed");
        CombatWorkQuota prep = CombatWorldBudget.heartbeat(world, id, 100, true);
        check(
            prep == light && prep.preparing && !prep.full && prep.used(Work.ITEM) == 3,
            "Preparation discarded charged work or granted forecast authority");
        check(!prep.spend(Work.ADVANCE), "Preparation advanced forecast");
        check(prep.spendObservation(Work.RAY, 13), "Preparation did not provide its bounded observation tier");
        CombatWorldBudget.prepared(world, id, 100, true, 100, 1, 1);
        check(
            !CombatWorldBudget.heartbeat(world, id, 100, true).full,
            "Exhausted same-tick preparation borrowed final-check reserve for friends");
        CombatWorkQuota next = CombatWorldBudget.heartbeat(world, id, 101, false);
        check(next.spendObservation(Work.RAY, 5), "Next LIGHT collection failed");
        CombatWorkQuota full = CombatWorldBudget.heartbeat(world, id, 101, true);
        check(
            full == next && full.full && full.used(Work.RAY) == 5,
            "Fresh tick did not permit FULL or reset its existing charges");
        check(full.spend(Work.ADVANCE, 720) && !full.spend(Work.ADVANCE), "Forecast exceeded720 advances");
        UUID second = new UUID(44, 2);
        CombatWorldBudget.heartbeat(world, second, 101, true);
        CombatWorldBudget.prepared(world, second, 101, true, 101);
        CombatWorkQuota q = CombatWorldBudget.heartbeat(world, second, 101, true);
        check(q.full && q.spend(Work.ADVANCE, 720) && !q.spend(Work.ADVANCE), "World exceeded1440 advances");
        long oldRound = prep.preparationRound;
        CombatWorldBudget.cancel(world, id);
        CombatWorkQuota renewed = CombatWorldBudget.heartbeat(world, id, 102, true);
        check(
            renewed.preparing && renewed.preparationRound != oldRound,
            "A fresh request reused an old preparation generation");
        CombatWorldBudget.unload(world);
    }

    private static void preparationExpiryCancelAndReadyBypass() {
        Object world = new Object();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (int tick = 0; tick < 24; tick++) {
            for (int i = 0; i < 32; i++) {
                UUID id = new UUID(52, i);
                CombatWorkQuota q = CombatWorldBudget.heartbeat(world, id, tick, true);
                if (q.preparing) {
                    seen.add(id);
                    CombatWorldBudget.sampled(world, id, tick, q.preparationRound);
                }
            }
            CombatWorldBudget.Diagnostics d = CombatWorldBudget.diagnostics(world, tick);
            check(d.preparations.length <= 8 && d.fullGrants == 0, "Unready actors acquired FULL");
            for (CombatWorldBudget.Entry e : d.entries) check(
                (e.id.getLeastSignificantBits() < 8 ? e.since <= 1 : e.since == 0) && e.sampledTicks <= 6,
                "Expiry renewed first waiting age or exceeded six actual samples");
            if (tick == 0) {
                for (UUID id : d.preparations) CombatWorldBudget.cancel(world, id);
                CombatWorldBudget.heartbeat(world, new UUID(52, 99), tick, true);
                check(
                    CombatWorldBudget.diagnostics(world, tick).preparations.length == 8,
                    "Cancellation recycled a preparation slot in the same tick");
            }
        }
        for (int i = 0; i < 32; i++)
            check(seen.contains(new UUID(52, i)), "Failed preparations reacquired the head and starved another waiter");
        CombatWorldBudget.unload(world);
        world = new Object();
        UUID old = new UUID(53, 1), young = new UUID(53, 2);
        CombatWorldBudget.heartbeat(world, old, 100, true);
        CombatWorldBudget.heartbeat(world, young, 100, true);
        CombatWorldBudget.prepared(world, young, 100, true, 100);
        check(
            CombatWorldBudget.heartbeat(world, young, 100, true).full,
            "An unready older request blocked a ready request");
        CombatWorldBudget.prepared(world, old, 100, true, 91);
        check(!CombatWorldBudget.heartbeat(world, old, 100, true).full, "Expired coverage acquired FULL");
        CombatWorkQuota reset = CombatWorldBudget.heartbeat(world, old, 99, false);
        check(
            !reset.full && !reset.preparing && CombatWorldBudget.diagnostics(world, 99).ready.length == 0,
            "Clock rollback retained a preparation/ready qualification");
        CombatWorldBudget.cancel(world, old);
        check(CombatWorldBudget.diagnostics(world, 99).requests == 0, "Lifecycle cancellation retained its request");
        CombatWorldBudget.unload(world);
        check(CombatWorldBudget.diagnostics(world, 100).requests == 0, "World unload retained requests");

        // 直接检查真实Request生命周期，不以UUID、phase或prep round推断对象是否相同
        Object diagnosticWorld = new Object();
        UUID id = new UUID(54, 1);
        CombatWorldBudget.heartbeat(diagnosticWorld, id, 100, false);
        CombatWorldBudget.Entry placeholder = CombatWorldBudget.diagnostics(diagnosticWorld, 100).entries[0];
        check(!placeholder.pending && placeholder.completedAt == Long.MIN_VALUE, "False heartbeat became pending");
        long serial = placeholder.requestSerial;
        for (int tick = 101; tick <= 114; tick++) {
            CombatWorkQuota quota = CombatWorldBudget.heartbeat(diagnosticWorld, id, tick, true);
            if (quota.preparing) CombatWorldBudget.sampled(diagnosticWorld, id, tick, quota.preparationRound);
            if (tick == 108) CombatWorldBudget.heartbeat(diagnosticWorld, id, tick, false);
            CombatWorldBudget.Entry pending = CombatWorldBudget.diagnostics(diagnosticWorld, tick).entries[0];
            check(
                pending.requestSerial == serial && pending.pending
                    && pending.since == 101
                    && pending.completedAt == Long.MIN_VALUE,
                "Preparation expiry/false renewal replaced pending Request or reset its first age");
        }
        CombatWorldBudget.completed(diagnosticWorld, id, 114);
        check(
            CombatWorldBudget.diagnostics(diagnosticWorld, 114).entries[0].pending,
            "An ungranted completion retired the real pending request");
        CombatWorldBudget.prepared(diagnosticWorld, id, 114, true, 114);
        check(
            CombatWorldBudget.heartbeat(diagnosticWorld, id, 114, true).full,
            "Lifecycle test could not acquire FULL");
        check(
            CombatWorldBudget.diagnostics(diagnosticWorld, 114).entries[0].completedAt == Long.MIN_VALUE,
            "FULL grant masqueraded as actual completion");
        CombatWorldBudget.completed(diagnosticWorld, id, 114);
        CombatWorldBudget.Entry completed = CombatWorldBudget.diagnostics(diagnosticWorld, 114).entries[0];
        check(
            !completed.pending && completed.completedAt == 114 && completed.requestSerial == serial,
            "Actual completion did not retain its Request identity and timestamp");
        CombatWorldBudget.heartbeat(diagnosticWorld, id, 115, true);
        CombatWorldBudget.Entry anotherDemand = CombatWorldBudget.diagnostics(diagnosticWorld, 115).entries[0];
        check(
            anotherDemand.requestSerial == serial && anotherDemand.pending
                && anotherDemand.since == 115
                && anotherDemand.completedAt == 114,
            "Completed Request could not start a new explicit demand");
        CombatWorldBudget.cancel(diagnosticWorld, id);
        CombatWorldBudget.heartbeat(diagnosticWorld, id, 115, false);
        CombatWorldBudget.Entry recreated = CombatWorldBudget.diagnostics(diagnosticWorld, 115).entries[0];
        check(
            recreated.requestSerial != serial && !recreated.pending && recreated.completedAt == Long.MIN_VALUE,
            "True cancellation reused the old Request serial/completion");
        CombatWorldBudget.heartbeat(diagnosticWorld, id, 116, true);
        CombatWorldBudget.Entry restarted = CombatWorldBudget.diagnostics(diagnosticWorld, 116).entries[0];
        check(
            restarted.requestSerial == recreated.requestSerial && restarted.pending && restarted.since == 116,
            "False placeholder to first true request changed identity or kept the wrong first age");
        CombatWorldBudget.unload(diagnosticWorld);
    }

    private static void snapshotFriendFactsCannotBecomeCurrentByFlag() {
        CombatSnapshot base = snapshot(
            floorGeometry(false),
            false,
            20,
            0,
            Arrays.asList(new Weapon(-1, "sword", sword())),
            target(new Vec(2, 0, 0), 30));
        CombatSnapshot.Friend old = new CombatSnapshot.Friend(
            new UUID(6, 1),
            17,
            0,
            base.tick - 1,
            1,
            false,
            new Vec(1, 0, 0),
            .3,
            1.5);
        CombatSnapshot guarded = new CombatSnapshot(
            base.tick,
            1,
            1,
            1,
            SELF,
            base.self,
            base.targets,
            base.projectiles,
            Arrays.asList(old),
            base.weapons,
            base.geometry,
            base.selectedTarget,
            true,
            true,
            base.tick);
        check(
            !guarded.complete && !guarded.friendsComplete && guarded.friends.get(0).observedAt == base.tick - 1,
            "A complete flag promoted historical friend facts to current safety");
        guarded = new CombatSnapshot(
            base.tick,
            1,
            1,
            1,
            SELF,
            base.self,
            base.targets,
            base.projectiles,
            Collections.emptyList(),
            base.weapons,
            base.geometry,
            base.selectedTarget,
            true,
            true,
            base.tick - 9);
        check(!guarded.complete, "Snapshot counted scan time and ready waiting as separate TTLs");
    }

    private static void staleReadyHeadCannotBlockAffordableTail() {
        Object world = new Object();
        UUID old = new UUID(68, 1), first = new UUID(68, 2), tail = new UUID(68, 99);
        CombatWorldBudget.heartbeat(world, old, 100, true);
        CombatWorldBudget.prepared(world, old, 100, true, 100, 0, 12);
        CombatWorldBudget.heartbeat(world, first, 101, true);
        CombatWorldBudget.prepared(world, first, 101, true, 101);
        check(CombatWorldBudget.heartbeat(world, first, 101, true).full, "First actual FULL slot was not used");
        for (int i = 0; i < 42; i++) check(
            CombatWorldBudget.heartbeat(world, new UUID(69, i), 101, false)
                .spend(Work.RAY, 12),
            "Could not establish the real shared-ledger counterexample");
        check(
            CombatWorldBudget.diagnostics(world, 101).used[Work.RAY.ordinal()] == 504,
            "Counterexample did not leave exactly8 world RAY");
        CombatWorldBudget.heartbeat(world, tail, 101, true);
        CombatWorldBudget.prepared(world, tail, 101, true, 101, 0, 0);
        check(
            CombatWorldBudget.heartbeat(world, tail, 101, true).full,
            "Old READY head needing12 friend rays blocked affordable debt0 tail with8 world rays remaining");
        check(
            CombatWorldBudget.diagnostics(world, 101).fullGrants == 2,
            "Counterexample failed to fill the remaining actual FULL slot");
        CombatWorldBudget.unload(world);
    }

    private static void boundedCandidatesAndAdvances() {
        List<Weapon> weapons = new ArrayList<>();
        for (int i = -1; i < 6; i++) weapons.add(new Weapon(i, "sword_" + i, sword()));
        CombatWorkQuota quota = CombatWorkQuota.isolated(100, true);
        CombatPlan plan = new CombatPlanner()
            .decide(snapshot(floorGeometry(false), true, 20, 0, weapons, target(new Vec(2, 0, 0), 40)), quota);
        check(
            quota.used(Work.CANDIDATE) <= 12 && quota.used(Work.ADVANCE) <= 720,
            "Portfolio exceeded the approved operation budget");
        check(plan != null && Double.isFinite(plan.utility), "Planner returned a non-finite choice");
    }

    private static void jumpPreparationUsesOwnedDeadlineAndRejectsBadTradeoffs() {
        CombatPlan ready = new CombatPlanner().decide(jumpSnapshot(0, 20, false), CombatWorkQuota.isolated(100, true));
        check(
            ready.simulated && ready.macro == CombatPlan.Macro.JUMP_STRIKE && ready.expectedDamage > 0,
            "Safe nearby supported sword lost its beneficial jump candidate: " + ready.macro
                + "/"
                + ready.utility
                + "/"
                + ready.expectedDamage
                + "/"
                + Arrays.toString(ready.quota.counts()));
        CombatPlan pending = new CombatPlanner()
            .decide(jumpSnapshot(2, 20, false), CombatWorkQuota.isolated(100, true));
        check(
            pending.simulated && pending.macro == CombatPlan.Macro.JUMP_STRIKE && pending.expectedDamage > 0,
            "Two remaining owned cooldown ticks were treated as a new full attack period");
        CombatPlan lowCeiling = new CombatPlanner()
            .decide(jumpSnapshot(0, 20, true), CombatWorkQuota.isolated(100, true));
        check(
            lowCeiling.macro != CombatPlan.Macro.JUMP_STRIKE,
            "A beneficial damage multiplier bypassed the low ceiling");
        CombatPlan fast = new CombatPlanner().decide(jumpSnapshot(0, 5, false), CombatWorkQuota.isolated(100, true));
        check(
            fast.simulated && fast.macro == CombatPlan.Macro.HOLD_ATTACK && fast.expectedDamage > 0,
            "Jump was forced when ordinary fast attacks were the better continuation");
        for (long remaining : new long[] { 9, Long.MAX_VALUE }) {
            CombatPlan outsideWindow = new CombatPlanner()
                .decide(jumpSnapshot(remaining, 20, false), CombatWorkQuota.isolated(100, true));
            check(
                outsideWindow.macro != CombatPlan.Macro.JUMP_STRIKE,
                "Unknown or out-of-window cooldown started a jump preparation: " + remaining);
        }
    }

    /** 平地支撑、实体脚底坐标及自有剩余冷却均按生产快照语义布置 */
    private static CombatSnapshot jumpSnapshot(long remaining, int delay, boolean lowCeiling) {
        CombatSnapshot.Self self = new CombatSnapshot.Self(
            new Vec(0, 0, 0),
            new Vec(0, -.0784, 0),
            new Vec(0, 0, -2),
            20,
            20,
            .3,
            1.35,
            2,
            .2,
            true,
            true,
            false,
            false,
            true,
            remaining == 0,
            false,
            delay,
            remaining,
            0,
            0,
            false,
            false);
        long lastHit = remaining > 0 && remaining <= delay ? 100 - delay + remaining : Long.MIN_VALUE;
        Target nearby = new Target(
            TARGET,
            2,
            new Vec(1.7, 0, 0),
            new Vec(0, 0, 0),
            100,
            lastHit,
            200,
            4,
            .45,
            .9,
            true,
            false,
            false,
            false,
            false);
        List<Weapon> weapons = new ArrayList<>();
        weapons.add(new Weapon(-1, "iron_sword", sword()));
        for (int slot = 0; slot < 6; slot++)
            weapons.add(new Weapon(slot, "empty_" + slot, MaidWeaponProfile.unknown("empty")));
        return new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            self,
            Arrays.asList(nearby),
            Collections.emptyList(),
            Collections.emptyList(),
            weapons,
            floorGeometry(false, lowCeiling),
            TARGET,
            true);
    }

    private static CombatSnapshot snapshot(Geometry geometry, boolean shield, int delay, int drawTicks,
        List<Weapon> weapons, Target target) {
        CombatSnapshot.Self self = new CombatSnapshot.Self(
            new Vec(0, 0, 0),
            new Vec(0, 0, 0),
            null,
            100,
            20,
            .3,
            1.35,
            2.8,
            .2,
            true,
            true,
            false,
            shield,
            true,
            delay <= 20,
            drawTicks > 0,
            delay,
            delay <= 20 ? 0 : delay,
            drawTicks,
            64,
            false,
            false);
        return new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            self,
            Arrays.asList(target),
            Collections.emptyList(),
            Collections.emptyList(),
            weapons,
            geometry,
            TARGET,
            geometry != null && geometry.complete);
    }

    private static Target target(Vec position, float health) {
        return new Target(
            TARGET,
            2,
            position,
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            health,
            1,
            .3,
            1.8,
            true,
            false,
            false,
            false,
            false);
    }

    private static MaidWeaponProfile sword() {
        return new MaidWeaponProfile(
            MaidWeaponProfile.Kind.MELEE,
            MaidWeaponProfile.Family.VANILLA_SWORD,
            "",
            "",
            0,
            1.5F,
            8,
            1,
            100,
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            false);
    }

    private static Geometry floorGeometry(boolean wall) {
        return floorGeometry(wall, false);
    }

    private static Geometry floorGeometry(boolean wall, boolean lowCeiling) {
        byte[] cells = new byte[9 * 6 * 9];
        for (int y = 0; y < 2; y++)
            for (int z = 0; z < 9; z++) for (int x = 0; x < 9; x++) cells[(y * 9 + z) * 9 + x] = Geometry.SOLID;
        if (wall) for (int y = 2; y <= 4; y++) for (int z = 0; z < 9; z++) cells[(y * 9 + z) * 9 + 5] = Geometry.SOLID;
        if (lowCeiling)
            for (int z = 0; z < 9; z++) for (int x = 0; x < 9; x++) cells[(4 * 9 + z) * 9 + x] = Geometry.SOLID;
        return new Geometry(-4, -2, -4, 9, 6, 9, 100, true, cells);
    }

    private static void terrainRouteTurnsAndUsesOnlyRealHalfSteps() {
        TerrainFixture wall = new TerrainFixture();
        wall.box(1, 0, 0, 1, Geometry.SOLID);
        wall.box(1, 1, 0, 2, Geometry.SOLID);
        var route = CombatTerrain.find(wall.snapshot(), wall.target, CombatWorkQuota.isolated(100, true)).approach;
        check(
            route != null && route.nodes.stream()
                .anyMatch(p -> Math.abs(p.z - .5) > .5),
            "Known reachable route did not turn around the blocking wall");
        TerrainFixture steps = new TerrainFixture();
        steps.box(1, 0, 0, .5, Geometry.SOLID);
        steps.box(2, 0, 0, 1, Geometry.SOLID);
        steps.target = target(new Vec(3.5, 1, .5), 200);
        var elevated = CombatTerrain.find(steps.snapshot(), steps.target, CombatWorkQuota.isolated(100, true)).approach;
        check(
            elevated != null && elevated.nodes.stream()
                .anyMatch(p -> p.y > .4),
            "Supported native half-step route stayed artificially level");
        for (int i = 1; i < elevated.nodes.size(); i++) check(
            Math.abs(elevated.nodes.get(i).y - elevated.nodes.get(i - 1).y) <= .501,
            "Route fabricated an unsupported full-block ascent");
        check(elevated.next(2) != elevated.nodes.get(0), "Route navigation returned to a passed node");
    }

    private static void coverHasExitActualOcclusionAndFullForecast() {
        TerrainFixture fixture = new TerrainFixture();
        fixture.box(1, 0, 1, 1, Geometry.SOLID);
        fixture.box(1, 0, 2, 1, Geometry.SOLID);
        fixture.target = new Target(
            TARGET,
            2,
            new Vec(3.5, 0, .5),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            20,
            8,
            .3,
            1.8,
            true,
            false,
            true,
            false,
            false);
        var snapshot = fixture.snapshot();
        var quota = CombatWorkQuota.isolated(100, true);
        var choices = CombatTerrain.find(snapshot, fixture.target, quota);
        check(
            choices.cover != null && choices.cover.escape != null && choices.cover.protection > .2,
            "Cover was not derived from geometry plus a separately proven exit: visited=" + choices.visited
                + "/"
                + choices.work);
        TerrainFixture deadEnd = new TerrainFixture();
        for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++)
            if (!(x == 0 && (z == 0 || z == 1)) && !(x == 1 && z == 0)) deadEnd.box(x, 0, z, 1, Geometry.SOLID);
        deadEnd.target = new Target(
            TARGET,
            2,
            new Vec(3.5, 1, .5),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            20,
            8,
            .3,
            1.8,
            true,
            false,
            true,
            false,
            false);
        var trapped = CombatTerrain.find(deadEnd.snapshot(), deadEnd.target, CombatWorkQuota.isolated(100, true));
        check(
            trapped.visited == 3 && trapped.cover == null,
            "One-entry shielded dead end was accepted without a separately walkable exit");
        double exposed = ThreatEstimator.damage(
            snapshot,
            snapshot.self.position,
            snapshot.self.position,
            2,
            0,
            false,
            CombatWorkQuota.isolated(100, true));
        double sheltered = ThreatEstimator.damage(
            snapshot,
            choices.cover.destination,
            choices.cover.destination,
            2,
            0,
            false,
            CombatWorkQuota.isolated(100, true));
        check(sheltered < exposed, "Real known block occlusion did not reduce ranged exposure");
        var fullQuota = CombatWorkQuota.isolated(100, true);
        var plan = new CombatPlanner().decide(snapshot, fullQuota);
        check(
            plan.simulated && fullQuota.used(Work.ADVANCE) > 0
                && fullQuota.used(Work.ADVANCE) <= 720
                && fullQuota.used(Work.COLLISION) > choices.work
                && fullQuota.used(Work.COLLISION) <= 40000
                && plan.quota == fullQuota,
            "Spatial selection displaced every actual complete forecast: " + Arrays.toString(fullQuota.counts()));
        check(
            plan.route != null && plan.macro == CombatPlan.Macro.COVER_EDGE,
            "Actual full decision did not use the proven environmental route: " + plan.macro + "/" + plan.utility);
        System.out.println(
            "CombatTerrain full=" + plan.simulated
                + " macro="
                + plan.macro
                + " graphWork="
                + choices.work
                + " combinedCollision="
                + fullQuota.used(Work.COLLISION)
                + " advances="
                + fullQuota.used(Work.ADVANCE));
    }

    private static void conditionalGateAndUnknownNeverBecomeFreeSpace() {
        TerrainFixture gate = new TerrainFixture();
        for (int z = -4; z <= 4; z++) for (int y = 0; y <= 1; y++) gate.box(1, y, z, y + 1, Geometry.SOLID);
        for (int y = 0; y <= 1; y++) gate.box(1, y, 0, y + 1, Geometry.SOLID | Geometry.OPENABLE);
        var choices = CombatTerrain.find(gate.snapshot(), gate.target, CombatWorkQuota.isolated(100, true));
        check(
            choices.approach != null && choices.approach.conditionalDoor,
            "Permitted known gate did not remain a conditional native route");
        check(
            choices.approach.at(100)
                .distance(gate.snapshot().self.position) < .01,
            "Forecast walked through a gate which has not actually opened");
        check(choices.approach.next(1).x > 2, "Native route was not allowed to request the existing door actor");
        for (int y = 0; y <= 1; y++) gate.box(1, y, 0, y + 1, Geometry.SOLID);
        var denied = CombatTerrain.find(gate.snapshot(), gate.target, CombatWorkQuota.isolated(100, true));
        check(
            denied.approach == null || denied.approach.destination.x < 1,
            "Unpermitted gate became a traversable route");
        gate.box(1, 0, 0, 1, Geometry.OPENABLE | Geometry.RAY_UNKNOWN);
        check(
            CombatTerrain
                .sight(gate.snapshot(), new Vec(.5, .5, .5), new Vec(3.5, .5, .5), CombatWorkQuota.isolated(100, true))
                == CombatTerrain.Sight.UNKNOWN,
            "Empty animated gate body was mistaken for a proven clear ray");
        var exhausted = CombatWorkQuota.isolated(100, true);
        check(exhausted.spend(Work.COLLISION, 40000), "Could not establish an exhausted real local collision quota");
        check(
            CombatTerrain.sight(gate.snapshot(), new Vec(.5, .5, .5), new Vec(3.5, .5, .5), exhausted)
                == CombatTerrain.Sight.BUDGET,
            "No collision budget was treated as a clear shot");
        gate.box(1, 0, 0, 1, Geometry.UNKNOWN);
        check(
            CombatTerrain
                .sight(gate.snapshot(), new Vec(.5, .5, .5), new Vec(3.5, .5, .5), CombatWorkQuota.isolated(100, true))
                == CombatTerrain.Sight.UNKNOWN,
            "Unknown cell was treated as a clear shot");
    }

    private static void arrowOcclusionKeepsHitOrderingAndPrefix() {
        TerrainFixture fixture = new TerrainFixture();
        fixture.box(-1, 0, 0, 1, Geometry.SOLID);
        var base = fixture.snapshot();
        var snapshot = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            base.self,
            Collections.emptyList(),
            Arrays.asList(
                new CombatSnapshot.Projectile(
                    new UUID(0, 98),
                    new Vec(-2.5, .7, .5),
                    new Vec(.5, 0, 0),
                    100,
                    0,
                    1,
                    .05,
                    6)),
            Collections.emptyList(),
            base.weapons,
            base.geometry,
            null,
            true);
        var quota = CombatWorkQuota.isolated(100, true);
        var occlusion = ThreatEstimator.occlusion(snapshot, quota);
        check(
            ThreatEstimator.damage(snapshot, base.self.position, base.self.position, 6, 0, false, quota, occlusion)
                == 0,
            "Arrow reappeared behind the block in a later forecast interval");
        // 多片碰撞外包不能冒充填满空隙的实体掩体
        fixture.cells[((2 * 9 + 4) * 9 + 3)] &= ~Geometry.RAY_BLOCKING;
        base = fixture.snapshot();
        snapshot = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            base.self,
            Collections.emptyList(),
            snapshot.projectiles,
            Collections.emptyList(),
            base.weapons,
            base.geometry,
            null,
            true);
        quota = CombatWorkQuota.isolated(100, true);
        check(
            ThreatEstimator.damage(
                snapshot,
                base.self.position,
                base.self.position,
                6,
                0,
                false,
                quota,
                ThreatEstimator.occlusion(snapshot, quota)) >= 6,
            "Uncertified multi-shape envelope provided phantom cover");
        fixture.box(-1, 0, 0, 1, 0);
        fixture.box(1, 0, 0, 1, Geometry.SOLID);
        base = fixture.snapshot();
        snapshot = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            base.self,
            Collections.emptyList(),
            Arrays.asList(
                new CombatSnapshot.Projectile(
                    new UUID(0, 98),
                    new Vec(-.5, .7, .5),
                    new Vec(2, 0, 0),
                    100,
                    0,
                    1,
                    .05,
                    6)),
            Collections.emptyList(),
            base.weapons,
            base.geometry,
            null,
            true);
        quota = CombatWorkQuota.isolated(100, true);
        check(
            ThreatEstimator.damage(
                snapshot,
                base.self.position,
                base.self.position,
                2,
                0,
                false,
                quota,
                ThreatEstimator.occlusion(snapshot, quota)) >= 6,
            "Wall behind the body incorrectly erased the earlier impact");
    }

    private static void nativeSizedCoverSurvivesLightOnlyWithinItsProof() {
        TerrainFixture fixture = new TerrainFixture();
        for (int y = -2; y <= 3; y++) for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) fixture.box(x, y, z, y + 1, y < 0 || z == -2 ? Geometry.SOLID : 0);
        fixture.box(-1, 0, 0, 1, Geometry.SOLID);
        fixture.box(-1, 1, 0, 2, Geometry.SOLID);
        fixture.box(1, 0, 1, 1, Geometry.SOLID);
        fixture.box(1, 0, 2, 1, Geometry.SOLID);
        fixture.box(3, 0, 0, .5, Geometry.SOLID);
        fixture.target = new Target(
            TARGET,
            2,
            new Vec(3.5, .5, .5),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            30,
            5,
            .45,
            .9,
            true,
            false,
            true,
            false,
            false);
        var base = fixture.snapshot();
        var self = new CombatSnapshot.Self(
            base.self.position,
            base.self.velocity,
            null,
            20,
            20,
            .3,
            1.5,
            2,
            .2,
            true,
            true,
            false,
            false,
            true,
            true,
            false,
            20,
            0,
            0,
            32,
            false,
            false);
        var snapshot = continuationFrame(base, self, 100, 1, base.geometry, true);
        var planner = new CombatPlanner();
        var fullQuota = CombatWorkQuota.isolated(100, true);
        var full = planner.decide(snapshot, fullQuota);
        check(
            full.macro == CombatPlan.Macro.COVER_EDGE && full.route != null && full.simulated,
            "Native pig/slab dimensions failed to produce an actual complete cover forecast");
        check(!CombatPlanner.traceEnabled() && full.trace == null, "QA candidate tracing was enabled by default");
        var lightQuota = CombatWorkQuota.isolated(101, false);
        var light = planner.decide(continuationFrame(base, self, 101, 1, base.geometry, true), lightQuota);
        check(
            light.macro == CombatPlan.Macro.COVER_EDGE && light.route == full.route
                && !light.simulated
                && light.route.provedAt == 100
                && lightQuota.used(Work.ADVANCE) == 0
                && lightQuota.used(Work.COLLISION) > 0
                && lightQuota.used(Work.COLLISION) <= 2400,
            "A fresh committed cover route was interrupted by LIGHT or continued without charged local proof");
        var currentGeometry = new Geometry(-4, -2, -4, 9, 6, 9, 109, true, fixture.cells, fixture.bounds);
        var expired = planner
            .decide(continuationFrame(base, self, 109, 1, currentGeometry, true), CombatWorkQuota.isolated(109, false));
        check(
            expired.macro != CombatPlan.Macro.COVER_EDGE && expired.route == null,
            "A new geometry stamp silently extended the old route proof beyond its eight ticks");
        planner = new CombatPlanner();
        planner.decide(snapshot, CombatWorkQuota.isolated(100, true));
        var newEpoch = planner
            .decide(continuationFrame(base, self, 101, 2, base.geometry, true), CombatWorkQuota.isolated(101, false));
        check(
            newEpoch.route == null && newEpoch.macro != CombatPlan.Macro.COVER_EDGE,
            "Equipment/entity epoch change retained a previous route commitment");
        planner = new CombatPlanner();
        planner.decide(snapshot, CombatWorkQuota.isolated(100, true));
        fixture.box(0, 0, 1, 1, Geometry.SOLID);
        var blockedGeometry = new Geometry(-4, -2, -4, 9, 6, 9, 101, true, fixture.cells, fixture.bounds);
        var blocked = planner
            .decide(continuationFrame(base, self, 101, 1, blockedGeometry, true), CombatWorkQuota.isolated(101, false));
        check(
            blocked.route == null && blocked.macro != CombatPlan.Macro.COVER_EDGE,
            "A newly observed obstruction was ignored on the retained route's next segment");
        planner = new CombatPlanner();
        planner.decide(snapshot, CombatWorkQuota.isolated(100, true));
        var exhausted = CombatWorkQuota.isolated(101, false);
        check(exhausted.spend(Work.COLLISION, 2400), "Could not establish the real LIGHT collision limit");
        var rejected = planner.decide(continuationFrame(base, self, 101, 1, base.geometry, true), exhausted);
        check(
            rejected.route == null && rejected.macro != CombatPlan.Macro.COVER_EDGE,
            "A retained cover route consumed future collision allowance or treated BUDGET as safety");
        planner = new CombatPlanner();
        planner.decide(snapshot, CombatWorkQuota.isolated(100, true));
        planner.finishDecision();
        var cancelled = planner
            .decide(continuationFrame(base, self, 101, 1, base.geometry, true), CombatWorkQuota.isolated(101, false));
        check(
            cancelled.route == null && cancelled.macro != CombatPlan.Macro.COVER_EDGE,
            "Cancelled route ownership leaked into a later reaction");
        System.out.println(
            "CombatTerrain continuation=LIGHT chargedCollision=" + lightQuota.used(Work.COLLISION)
                + " fullAdvances="
                + fullQuota.used(Work.ADVANCE)
                + " proofTick="
                + full.route.provedAt);
    }

    private static CombatSnapshot continuationFrame(CombatSnapshot base, CombatSnapshot.Self self, long tick,
        long epoch, Geometry geometry, boolean complete) {
        Target old = base.target(TARGET);
        Target target = new Target(
            old.id,
            old.entityId,
            old.position,
            old.velocity,
            tick,
            old.lastOwnHitAt,
            old.health,
            old.estimatedDamage,
            old.halfWidth,
            old.height,
            true,
            old.swinging,
            old.ranged,
            old.explosive,
            old.attackingOwner);
        return new CombatSnapshot(
            tick,
            epoch,
            epoch,
            epoch,
            SELF,
            self,
            Arrays.asList(target),
            base.projectiles,
            base.friends,
            base.weapons,
            geometry,
            TARGET,
            complete);
    }

    private static void fullCoverRetainsItsGoalAndRejectsTheObservedCorner() {
        TerrainFixture fixture = new TerrainFixture();
        for (int y = -2; y <= 3; y++) for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) fixture.box(x, y, z, y + 1, y < 0 || z == -2 ? Geometry.SOLID : 0);
        fixture.box(-1, 0, 0, 1, Geometry.SOLID);
        fixture.box(-1, 1, 0, 2, Geometry.SOLID);
        fixture.box(1, 0, 1, 1, Geometry.SOLID);
        fixture.box(1, 0, 2, 1, Geometry.SOLID);
        fixture.box(3, 0, 0, .5, Geometry.SOLID);
        fixture.target = new Target(
            TARGET,
            2,
            new Vec(3.5, .5, .5),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            30,
            5,
            .45,
            .9,
            true,
            false,
            true,
            false,
            false);
        var base = fixture.snapshot();
        var initial = continuationFrame(
            base,
            nativeEnvironmentSelf(new Vec(.6194445118, 0, .4879581742), 1),
            100,
            1,
            base.geometry,
            true);
        var cornerQuota = CombatWorkQuota.isolated(100, false);
        check(
            !CombatTerrain.safeStep(initial, new Vec(.7191425345, 0, .6542516331), new Vec(.5, 0, 1.5), cornerQuota)
                && cornerQuota.used(Work.COLLISION) > 0
                && cornerQuota.denials()[Work.COLLISION.ordinal()] == 0,
            "The real native body corner was waived as safe or mislabeled budget failure");
        check(
            CombatTerrain.safeStep(
                initial,
                new Vec(.6812241401, 0, 1.0588918054),
                new Vec(.5, 0, 1.5),
                CombatWorkQuota.isolated(100, false)),
            "A genuinely clear later native segment was rejected");
        boolean traced = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        try {
            var planner = new CombatPlanner();
            var first = planner.decide(initial, CombatWorkQuota.isolated(100, true));
            check(
                first.simulated && first.macro == CombatPlan.Macro.COVER_EDGE && first.route != null,
                "Native initial input did not select an actual cover forecast");
            var moving = continuationFrame(
                base,
                nativeEnvironmentSelf(new Vec(.5240469396, 0, 1.5353138513), 10),
                102,
                1,
                new Geometry(-4, -2, -4, 9, 6, 9, 102, true, fixture.cells, fixture.bounds),
                true);
            var quota = CombatWorkQuota.isolated(102, true);
            var next = planner.decide(moving, quota);
            boolean kept = false;
            for (var candidate : next.trace.candidates)
                if (candidate.macro == CombatPlan.Macro.COVER_EDGE && candidate.route != null
                    && candidate.destination.distance(first.destination) < .01) kept = true;
            check(
                kept && next.simulated
                    && quota.used(Work.ADVANCE) > 0
                    && quota.used(Work.ADVANCE) <= CombatWorkQuota.MAX_ADVANCES
                    && quota.used(Work.COLLISION) <= 40000,
                "Full scoring replaced the committed goal merely because the actor had already gained partial cover");
            check(
                next.macro == CombatPlan.Macro.COVER_EDGE && next.destination.distance(first.destination) < .01
                    && next.route.provedAt == 102
                    && next.route != first.route,
                "The current cover goal was not genuinely re-proved in the full decision");
            Vec oldEntrance = first.route.nodes.get(first.route.nodes.size() - 2);
            var arrived = continuationFrame(
                base,
                nativeEnvironmentSelf(first.destination, 10),
                102,
                1,
                moving.geometry,
                true);
            var arrivedProof = CombatTerrain.retainCover(
                arrived,
                arrived.target(TARGET),
                first.route,
                first.route.nodes.size() - 1,
                CombatWorkQuota.isolated(102, true));
            check(
                arrivedProof != null && arrivedProof.nodes.size() == 1,
                "Could not establish the actual arrived-at-cover tail before its entrance changed");
            fixture.box(
                (int) Math.floor(oldEntrance.x),
                (int) Math.floor(oldEntrance.y),
                (int) Math.floor(oldEntrance.z),
                oldEntrance.y + 1,
                Geometry.SOLID);
            var closedEntrance = continuationFrame(
                base,
                nativeEnvironmentSelf(first.destination, 10),
                104,
                1,
                new Geometry(-4, -2, -4, 9, 6, 9, 104, true, fixture.cells, fixture.bounds),
                true);
            check(
                CombatTerrain.safeStep(
                    closedEntrance,
                    first.destination,
                    first.route.escape,
                    CombatWorkQuota.isolated(104, false)),
                "The independent exit was accidentally blocked by the entrance fixture");
            check(
                CombatTerrain.retainCover(
                    closedEntrance,
                    closedEntrance.target(TARGET),
                    arrivedProof,
                    arrivedProof.nodes.size() - 1,
                    CombatWorkQuota.isolated(104, true)) == null,
                "A shortened arrived tail renewed a dead-end cover after its old entrance was blocked");
            fixture.box(
                (int) Math.floor(oldEntrance.x),
                (int) Math.floor(oldEntrance.y),
                (int) Math.floor(oldEntrance.z),
                oldEntrance.y + 1,
                Geometry.UNKNOWN);
            var unknownEntrance = continuationFrame(
                base,
                nativeEnvironmentSelf(first.destination, 10),
                104,
                1,
                new Geometry(-4, -2, -4, 9, 6, 9, 104, true, fixture.cells, fixture.bounds),
                true);
            check(
                CombatTerrain.retainCover(
                    unknownEntrance,
                    unknownEntrance.target(TARGET),
                    arrivedProof,
                    arrivedProof.nodes.size() - 1,
                    CombatWorkQuota.isolated(104, true)) == null,
                "An unknown old entrance was silently treated as a second proven cover connection");
            System.out.println(
                "CombatTerrain retainedGoal=" + next.destination.x
                    + ","
                    + next.destination.z
                    + " collision="
                    + quota.used(Work.COLLISION)
                    + " advances="
                    + quota.used(Work.ADVANCE));
        } finally {
            CombatPlanner.setTraceEnabled(traced);
        }
    }

    private static CombatSnapshot.Self nativeEnvironmentSelf(Vec position, int drawTicks) {
        return new CombatSnapshot.Self(
            position,
            new Vec(0, 0, 0),
            null,
            20,
            20,
            .3,
            1.5,
            2,
            .2,
            true,
            true,
            false,
            false,
            true,
            true,
            drawTicks > 0,
            20,
            0,
            drawTicks,
            32,
            false,
            false);
    }

    private static void coverageGapAndNativeAttackPulseDoNotRenewOrDropTheProposal() {
        TerrainFixture fixture = new TerrainFixture();
        for (int y = -2; y <= 3; y++) for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) fixture.box(x, y, z, y + 1, y < 0 || z == -2 ? Geometry.SOLID : 0);
        fixture.box(-1, 0, 0, 1, Geometry.SOLID);
        fixture.box(-1, 1, 0, 2, Geometry.SOLID);
        fixture.box(1, 0, 1, 1, Geometry.SOLID);
        fixture.box(1, 0, 2, 1, Geometry.SOLID);
        fixture.box(3, 0, 0, .5, Geometry.SOLID);
        fixture.target = new Target(
            TARGET,
            2,
            new Vec(3.5, .5, .5),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            30,
            5,
            .45,
            .9,
            true,
            false,
            true,
            false,
            false);
        var base = fixture.snapshot();
        var start = nativeEnvironmentSelf(new Vec(.6189187691, 0, .4835585114), 1);
        var mid = nativeEnvironmentSelf(new Vec(.5259463527, 0, 1.5301652288), 10);
        var current = new Geometry(-4, -2, -4, 9, 6, 9, 105, true, fixture.cells, fixture.bounds);
        var planner = new CombatPlanner();
        var first = planner.decide(
            proposalFrame(base, start, 100, 1, base.geometry, true, base.weapons),
            CombatWorkQuota.isolated(100, true));
        check(
            first.simulated && first.macro == CombatPlan.Macro.COVER_EDGE,
            "Native input did not initially select cover");
        var lightQuota = CombatWorkQuota.isolated(102, false);
        var gap = planner.decide(proposalFrame(base, mid, 102, 1, base.geometry, false, base.weapons), lightQuota);
        check(
            gap.route == null && gap.macro != CombatPlan.Macro.COVER_EDGE
                && !gap.simulated
                && lightQuota.used(Work.ADVANCE) == 0,
            "Incomplete coverage executed or simulated the historical route");
        var fullQuota = CombatWorkQuota.isolated(105, true);
        var resumed = planner.decide(proposalFrame(base, mid, 105, 1, current, true, base.weapons), fullQuota);
        check(
            resumed.simulated && resumed.macro == CombatPlan.Macro.COVER_EDGE
                && resumed.destination.distance(first.destination) < .01
                && resumed.route.provedAt == 105,
            "A temporary real coverage gap dropped the still-bounded geometric proposal before FULL re-proof");
        // Controller reports actual shot completion separately from a route reaching its destination.
        planner.finishAttack(resumed, mid.position, 105);
        var afterPulse = planner.decide(
            proposalFrame(base, mid, 106, 1, current, true, base.weapons),
            CombatWorkQuota.isolated(106, false));
        check(
            afterPulse.route == resumed.route && afterPulse.macro == CombatPlan.Macro.COVER_EDGE
                && !afterPulse.simulated
                && afterPulse.route.provedAt == 105,
            "A native attack pulse completed an unfinished route or renewed its geometry on LIGHT");
        var changed = Arrays.asList(new Weapon(-1, "native-wear-or-nbt-change", base.weapon(-1).profile));
        var changedLightQuota = CombatWorkQuota.isolated(107, false);
        var changedLight = planner.decide(proposalFrame(base, mid, 107, 2, current, true, changed), changedLightQuota);
        check(
            changedLight.route == null && changedLight.macro != CombatPlan.Macro.COVER_EDGE
                && changedLightQuota.used(Work.ADVANCE) == 0,
            "Equipment content/epoch mutation reused old execution intent on LIGHT");
        var newGeometry = new Geometry(-4, -2, -4, 9, 6, 9, 108, true, fixture.cells, fixture.bounds);
        var rebound = planner
            .decide(proposalFrame(base, mid, 108, 2, newGeometry, true, changed), CombatWorkQuota.isolated(108, true));
        check(
            rebound.simulated && rebound.macro == CombatPlan.Macro.COVER_EDGE
                && rebound.destination.distance(resumed.destination) < .01
                && rebound.route != resumed.route
                && rebound.route.provedAt == 108
                && rebound.equipmentEpoch == 2
                && rebound.weaponFingerprint.equals("native-wear-or-nbt-change"),
            "A geometric proposal either lost its valid goal or inherited stale equipment/proof instead of new FULL re-proof");
        planner.finishAttack(rebound, rebound.destination, 108);
        boolean traced = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        try {
            var arrived = planner.decide(
                proposalFrame(base, mid, 109, 2, newGeometry, false, changed),
                CombatWorkQuota.isolated(109, false));
            check(
                arrived.trace.proposalGoal == null && arrived.route == null,
                "An actual completed route retained its historical proposal");
            planner = new CombatPlanner();
            planner.decide(
                proposalFrame(base, start, 100, 1, base.geometry, true, base.weapons),
                CombatWorkQuota.isolated(100, true));
            planner.decide(
                proposalFrame(base, mid, 102, 1, base.geometry, false, base.weapons),
                CombatWorkQuota.isolated(102, false));
            var late = planner.decide(
                proposalFrame(
                    base,
                    mid,
                    109,
                    2,
                    new Geometry(-4, -2, -4, 9, 6, 9, 109, true, fixture.cells, fixture.bounds),
                    true,
                    changed),
                CombatWorkQuota.isolated(109, true));
            check(
                late.trace.proposalGoal == null,
                "Unknown LIGHT frames or new equipment silently extended proposal TTL");
            planner = new CombatPlanner();
            planner.decide(
                proposalFrame(base, start, 100, 1, base.geometry, true, base.weapons),
                CombatWorkQuota.isolated(100, true));
            planner.finishDecision();
            var cancelled = planner.decide(
                proposalFrame(base, mid, 105, 1, current, true, base.weapons),
                CombatWorkQuota.isolated(105, true));
            check(cancelled.trace.proposalGoal == null, "Real cancellation leaked the pure geometric proposal");
            planner = new CombatPlanner();
            var original = planner.decide(
                proposalFrame(base, start, 100, 1, base.geometry, true, base.weapons),
                CombatWorkQuota.isolated(100, true));
            var arrivedWithoutPulse = planner.decide(
                proposalFrame(
                    base,
                    nativeEnvironmentSelf(original.destination, 2),
                    101,
                    1,
                    base.geometry,
                    true,
                    base.weapons),
                CombatWorkQuota.isolated(101, false));
            check(
                arrivedWithoutPulse.trace.proposalGoal == null && arrivedWithoutPulse.route == null,
                "Physical route arrival waited for an unrelated attack pulse before retiring its proposal");
            planner = new CombatPlanner();
            planner.decide(
                proposalFrame(base, start, 100, 1, base.geometry, true, base.weapons),
                CombatWorkQuota.isolated(100, true));
            var sameStamp = proposalFrame(base, mid, 105, 1, current, true, base.weapons);
            var newIdentity = new CombatSnapshot(
                105,
                1,
                1,
                1,
                new UUID(0, 99),
                sameStamp.self,
                sameStamp.targets,
                sameStamp.projectiles,
                sameStamp.friends,
                sameStamp.weapons,
                sameStamp.geometry,
                TARGET,
                true);
            var retired = planner.decide(newIdentity, CombatWorkQuota.isolated(105, true));
            check(retired.trace.proposalGoal == null, "A changed maid UUID retained another identity's proposal");
            planner = new CombatPlanner();
            planner.decide(
                proposalFrame(base, start, 100, 1, base.geometry, true, base.weapons),
                CombatWorkQuota.isolated(100, true));
            var stationary = new CombatSnapshot.Self(
                mid.position,
                mid.velocity,
                mid.ownerPosition,
                mid.health,
                mid.ownerHealth,
                mid.halfWidth,
                mid.height,
                mid.reach,
                mid.movementPerTick,
                true,
                false,
                true,
                mid.shieldAvailable,
                mid.equipped,
                mid.cadenceReady,
                mid.drawing,
                mid.attackDelay,
                mid.cadenceRemainingTicks,
                mid.drawTicks,
                mid.ammunition,
                false,
                false);
            var held = planner.decide(
                proposalFrame(base, stationary, 105, 1, current, true, base.weapons),
                CombatWorkQuota.isolated(105, true));
            check(
                held.simulated && held.route == null && held.macro == CombatPlan.Macro.HOLD_ATTACK,
                "Historical geometry forced a cover movement over a legal stationary FULL decision");
            var afterFullHold = planner.decide(
                proposalFrame(base, mid, 106, 1, current, false, base.weapons),
                CombatWorkQuota.isolated(106, false));
            check(
                afterFullHold.trace.proposalGoal == null,
                "A legitimate new FULL choice failed to retire the previous geometric proposal");
        } finally {
            CombatPlanner.setTraceEnabled(traced);
        }
        check(
            fullQuota.used(Work.ADVANCE) > 0 && fullQuota.used(Work.ADVANCE) <= CombatWorkQuota.MAX_ADVANCES
                && fullQuota.used(Work.COLLISION) <= 40000,
            "Proposal resumption bypassed the real FULL quota");
        System.out.println(
            "CombatTerrain gapResumed=" + resumed.destination.x
                + ","
                + resumed.destination.z
                + " fullCollision="
                + fullQuota.used(Work.COLLISION)
                + " fullAdvances="
                + fullQuota.used(Work.ADVANCE)
                + " gapAdvances="
                + lightQuota.used(Work.ADVANCE));
    }

    private static CombatSnapshot proposalFrame(CombatSnapshot base, CombatSnapshot.Self self, long tick,
        long equipmentEpoch, Geometry geometry, boolean complete, List<Weapon> weapons) {
        Target old = base.target(TARGET);
        Target target = new Target(
            old.id,
            old.entityId,
            old.position,
            old.velocity,
            tick,
            old.lastOwnHitAt,
            old.health,
            old.estimatedDamage,
            old.halfWidth,
            old.height,
            true,
            old.swinging,
            old.ranged,
            old.explosive,
            old.attackingOwner);
        return new CombatSnapshot(
            tick,
            1,
            1,
            equipmentEpoch,
            SELF,
            self,
            Arrays.asList(target),
            base.projectiles,
            base.friends,
            weapons,
            geometry,
            TARGET,
            complete);
    }

    private static void resourceBalancesCannotCreateUnavailableAttacks() {
        var bow = resourceWeapon(true, 100, 1, false);
        var single = new CombatPlanner().decide(resourceFrame(bow, 1), CombatWorkQuota.isolated(100, true));
        var two = new CombatPlanner().decide(resourceFrame(bow, 2), CombatWorkQuota.isolated(100, true));
        check(
            single.simulated && single.expectedDamage > 0
                && single.expectedDamage <= 6
                && two.expectedDamage > single.expectedDamage
                && two.expectedDamage <= 12,
            "The same single observed arrow was credited for more than one launch");
        var lastUse = new CombatPlanner()
            .decide(resourceFrame(resourceWeapon(true, 1, 1, false), 64), CombatWorkQuota.isolated(100, true));
        check(
            lastUse.expectedDamage > 0 && lastUse.expectedDamage <= 6,
            "A bow with one remaining native wear request was credited with later shots or lucky Unbreaking");
        var infinite = new CombatPlanner()
            .decide(resourceFrame(resourceWeapon(true, 100, 1, true), 1), CombatWorkQuota.isolated(100, true));
        var noArrow = new CombatPlanner()
            .decide(resourceFrame(resourceWeapon(true, 100, 1, true), 0), CombatWorkQuota.isolated(100, true));
        check(
            infinite.expectedDamage > single.expectedDamage && noArrow.expectedDamage == 0,
            "Infinity either consumed the sole required arrow or fabricated the required initial arrow");
        var lastMelee = new CombatPlanner()
            .decide(resourceFrame(resourceWeapon(false, 25, 25, false), 0), CombatWorkQuota.isolated(100, true));
        var insufficient = new CombatPlanner()
            .decide(resourceFrame(resourceWeapon(false, 24, 25, false), 0), CombatWorkQuota.isolated(100, true));
        check(
            lastMelee.expectedDamage > 0 && lastMelee.expectedDamage <= 8 && insufficient.expectedDamage == 0,
            "Finite melee wear requests were ignored or an unaffordable GT-style hit was admitted");
    }

    private static MaidWeaponProfile resourceWeapon(boolean bow, long remaining, long cost, boolean infinity) {
        return new MaidWeaponProfile(
            bow ? MaidWeaponProfile.Kind.BOW : MaidWeaponProfile.Kind.MELEE,
            bow ? MaidWeaponProfile.Family.VANILLA_BOW : MaidWeaponProfile.Family.GREGTECH_KNIFE,
            "",
            "",
            0,
            1,
            bow ? 0 : 8,
            cost,
            remaining,
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            infinity,
            false);
    }

    private static CombatSnapshot resourceFrame(MaidWeaponProfile profile, int ammunition) {
        var self = new CombatSnapshot.Self(
            new Vec(0, 0, 0),
            new Vec(0, 0, 0),
            null,
            20,
            20,
            .3,
            1.5,
            2,
            .2,
            true,
            true,
            true,
            false,
            true,
            true,
            true,
            5,
            0,
            20,
            ammunition,
            false,
            false);
        var target = new Target(
            TARGET,
            2,
            new Vec(1.5, 0, 0),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            30,
            0,
            .45,
            .9,
            true,
            false,
            false,
            false,
            false);
        return new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            self,
            Arrays.asList(target),
            Collections.emptyList(),
            Collections.emptyList(),
            Arrays.asList(new Weapon(-1, "resource", profile)),
            floorGeometry(false),
            TARGET,
            true);
    }

    private static void visibleTargetWindowRestoresNativeExitWithoutChangingTheQuota() {
        int origin = CombatGeometryWindow.horizontalOrigin(79.4835082455, 74.5, true, .3);
        check(
            origin == 74
                && CombatGeometryWindow.WIDTH * CombatGeometryWindow.HEIGHT * CombatGeometryWindow.WIDTH == 486,
            "Visible-target reallocation changed the fixed observation volume");
        check(
            CombatGeometryWindow.horizontalOrigin(79.4835082455, 74.5, false, .3) == 75
                && CombatGeometryWindow.horizontalOrigin(79.4835082455, 74.5, true, .5) == 75,
            "Hidden target coordinates or an oversized body changed the observation window");
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            double x = 79 + dx + .5, z = 5 + dz + .5;
            check(
                x - .3 >= origin && x + .3 < origin + 9 && z - .3 >= 1 && z + .3 < 10,
                "Biased observation dropped a required standing node's body");
        }
        var oldScene = nativeExitFrame(75, 101, true, 10);
        var scene = nativeExitFrame(origin, 101, true, 10);
        var from = new Vec(76.5, 102.275, 3.5);
        var aim = new Vec(74.5, 101.45, 2.5);
        check(
            CombatTerrain.sight(oldScene, from, aim, CombatWorkQuota.isolated(101, true))
                == CombatTerrain.Sight.UNKNOWN,
            "Original ROI unexpectedly certified its unobserved endpoint");
        check(
            CombatTerrain.sight(scene, from, aim, CombatWorkQuota.isolated(101, true)) == CombatTerrain.Sight.CLEAR,
            "New ROI failed to prove the genuinely clear loaded-template segment");
        var oldWindowPlanner = new CombatPlanner();
        oldWindowPlanner.decide(nativeExitFrame(75, 100, false, 10), CombatWorkQuota.isolated(100, false));
        boolean traceBeforePolicyCheck = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        var oldWindow = oldWindowPlanner.decide(oldScene, CombatWorkQuota.isolated(101, true));
        CombatPlanner.setTraceEnabled(traceBeforePolicyCheck);
        // 旧RETREAT排名由冻结main17的原22检查单独保留；这里核新任务合同而非强保留旧限制
        check(oldWindow.expectedDamage == 0, "Old ROI fabricated an unproved ranged benefit");
        if (oldWindow.normalProgressAdmitted) check(
            oldWindow.simulated && oldWindow.route != null
                && oldWindow.movementNode != null
                && oldWindow.stageId > 0
                && oldWindow.boundary != null
                && oldWindow.route.fresh(oldScene.tick)
                && oldScene.self.ammunition == 0
                && oldWindow.necessaryComparisonComplete
                && oldWindow.trace.candidates.stream()
                    .anyMatch(
                        c -> c.strategy == oldWindow.strategy && c.weaponSlot == oldWindow.weaponSlot
                            && c.evaluated
                            && c.escapeProved
                            && c.riskKnown
                            && c.progressFeasible
                            && c.destination.distance(oldWindow.destination) < .001
                            && c.risk + c.singleSelf < oldScene.self.health
                            && c.ownerRisk + c.singleOwner < oldScene.self.ownerHealth),
            "New task filtering lacked a current proved route/native submission boundary");
        System.out.println(
            "CombatExit oldWindowPolicy=" + oldWindow.macro
                + "/"
                + oldWindow.strategy
                + "/damage="
                + oldWindow.expectedDamage
                + "/admitted="
                + oldWindow.normalProgressAdmitted);
        var planner = new CombatPlanner();
        planner.decide(nativeExitFrame(75, 100, false, 10), CombatWorkQuota.isolated(100, false));
        var quota = CombatWorkQuota.isolated(101, true);
        var plan = planner.decide(scene, quota);
        check(
            plan.simulated && plan.macro == CombatPlan.Macro.PREPARE_WEAPON
                && plan.weaponSlot == 0
                && plan.family == MaidWeaponProfile.Family.VANILLA_SWORD
                && plan.route != null
                && plan.expectedDamage > 0
                && quota.used(Work.ADVANCE) <= CombatWorkQuota.MAX_ADVANCES
                && quota.used(Work.COLLISION) <= 40000,
            "Actual zero-arrow native input failed to choose the owned sword and proved exit route");
        System.out.println(
            "CombatExit selected=" + plan.macro
                + " slot="
                + plan.weaponSlot
                + " damage="
                + plan.expectedDamage
                + " collision="
                + quota.used(Work.COLLISION)
                + " advances="
                + quota.used(Work.ADVANCE));
    }

    private static CombatSnapshot nativeExitFrame(int origin, long tick, boolean exit, int draw) {
        var bow = resourceWeapon(true, 385, 1, false);
        var self = new CombatSnapshot.Self(
            new Vec(79.4835082455, 101, 5.0653077876),
            new Vec(0, 0, 0),
            new Vec(73.5, 101, 1.5),
            20,
            20,
            .3,
            1.5,
            2,
            .2,
            true,
            true,
            false,
            false,
            false,
            true,
            draw > 0,
            5,
            0,
            draw,
            exit ? 0 : 32,
            false,
            false);
        var target = new Target(
            TARGET,
            2,
            new Vec(exit ? 74.5 : 82.5, exit ? 101 : 101.5, 2.5),
            new Vec(0, 0, 0),
            tick,
            Long.MIN_VALUE,
            30,
            5,
            .45,
            .9,
            true,
            false,
            true,
            false,
            false);
        var weapons = new ArrayList<Weapon>();
        weapons.add(new Weapon(-1, "bow", bow));
        for (int slot = 0; slot < 6; slot++) weapons.add(
            new Weapon(
                slot,
                "slot" + slot,
                exit && slot == 0 ? new MaidWeaponProfile(
                    MaidWeaponProfile.Kind.MELEE,
                    MaidWeaponProfile.Family.VANILLA_SWORD,
                    "",
                    "",
                    0,
                    1.5F,
                    8,
                    1,
                    251,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false) : MaidWeaponProfile.unknown("empty")));
        var owner = new CombatSnapshot.Friend(new UUID(0, 3), 3, 0, tick, 1, true, new Vec(73.5, 101, 1.5), .3, 1.8);
        return new CombatSnapshot(
            tick,
            1,
            1,
            exit ? 2 : 1,
            SELF,
            self,
            Arrays.asList(target),
            Collections.emptyList(),
            Arrays.asList(owner),
            weapons,
            nativeExitGeometry(origin, tick),
            TARGET,
            true,
            true,
            tick);
    }

    private static Geometry nativeExitGeometry(int origin, long tick) {
        byte[] cells = new byte[486];
        double[] bounds = new double[cells.length * 6];
        for (int y = 0; y < 6; y++) for (int z = 0; z < 9; z++) for (int x = 0; x < 9; x++) {
            int px = origin + x, py = 99 + y, pz = 1 + z, index = (y * 9 + z) * 9 + x;
            double top = px == 82 && pz == 2 && py == 101 ? 101.5 : py + 1;
            boolean solid = py == 100 && px >= 72 && px <= 92 && pz >= 0 && pz <= 8 || pz == 8 && py >= 100
                || px == 78 && pz == 2 && (py == 101 || py == 102)
                || px == 80 && (pz == 3 || pz == 4) && py == 101
                || px == 82 && pz == 2 && py == 101;
            if (solid) cells[index] = (byte) (Geometry.SOLID | Geometry.RAY_BLOCKING);
            double[] box = { px, py, pz, px + 1, top, pz + 1 };
            System.arraycopy(box, 0, bounds, index * 6, 6);
        }
        return new Geometry(origin, 99, 1, 9, 6, 9, tick, true, cells, bounds);
    }

    private static void fallbackWaitCannotKeepInventedAttackProgress() {
        boolean recovered = true;
        StringBuilder results = new StringBuilder();
        for (boolean full : new boolean[] { false, true }) {
            var planner = new CombatPlanner();
            var waiting = planner.decide(waitRecoveryFrame(100, false), CombatWorkQuota.isolated(100, full));
            check(
                waiting.macro == CombatPlan.Macro.WAIT && waiting.expectedDamage == 0 && waiting.utility == 0,
                "No-target fallback either attacked or fabricated progress");
            var frame = waitRecoveryFrame(101, true);
            var direct = new CombatPlanner().decide(frame, CombatWorkQuota.isolated(101, full));
            var afterWait = planner.decide(frame, CombatWorkQuota.isolated(101, full));
            var continued = planner.decide(waitRecoveryFrame(102, true), CombatWorkQuota.isolated(102, full));
            check(
                direct.macro == CombatPlan.Macro.HOLD_ATTACK && (!full || direct.expectedDamage > 0),
                "Control input did not permit the currently held sword's ordinary attack");
            recovered &= afterWait.macro == direct.macro && continued.macro == direct.macro
                && Math.abs(afterWait.utility - direct.utility) < 1.0E-9
                && Math.abs(afterWait.expectedDamage - direct.expectedDamage) < 1.0E-9;
            String result = (full ? "FULL" : "LIGHT") + " afterWait="
                + afterWait.macro
                + " utility="
                + afterWait.utility
                + " damage="
                + afterWait.expectedDamage
                + " control="
                + direct.macro
                + " controlUtility="
                + direct.utility
                + " controlDamage="
                + direct.expectedDamage
                + " continued="
                + continued.macro;
            results.append(result)
                .append(';');
            System.out.println("CombatWaitRecovery " + result);
        }
        check(recovered, "Fallback WAIT displaced a legal attack or invented progress: " + results);
    }

    private static CombatSnapshot waitRecoveryFrame(long tick, boolean targetPresent) {
        var self = new CombatSnapshot.Self(
            new Vec(0, 0, 0),
            new Vec(0, 0, 0),
            null,
            20,
            20,
            .3,
            1.5,
            2,
            .2,
            true,
            false,
            true,
            false,
            true,
            true,
            false,
            5,
            0,
            0,
            0,
            false,
            false);
        var target = new Target(
            TARGET,
            2,
            new Vec(1.5, 0, 0),
            new Vec(0, 0, 0),
            tick,
            Long.MIN_VALUE,
            200,
            1,
            .3,
            1.8,
            true,
            false,
            false,
            false,
            false);
        return new CombatSnapshot(
            tick,
            1,
            1,
            1,
            SELF,
            self,
            targetPresent ? Arrays.asList(target) : Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Arrays.asList(new Weapon(-1, "sword", sword())),
            floorGeometry(false),
            targetPresent ? TARGET : null,
            true);
    }

    /** 代表性局部图重建，不是缺失完整snapshot的main17原生精确重放 */
    private static CombatSnapshot progressScene(float health, CombatSnapshot.DamageSource source,
        List<CombatSnapshot.Projectile> arrows) {
        return progressScene(health, source, arrows, .2, false);
    }

    private static CombatSnapshot progressScene(float health, CombatSnapshot.DamageSource source,
        List<CombatSnapshot.Projectile> arrows, double speed, boolean stationary) {
        TerrainFixture fixture = new TerrainFixture();
        var self = new CombatSnapshot.Self(
            new Vec(.5, 0, .5),
            new Vec(0, 0, 0),
            null,
            health,
            20,
            .3,
            1.5,
            2,
            speed,
            true,
            false,
            stationary,
            false,
            true,
            true,
            false,
            20,
            0,
            0,
            0,
            false,
            false);
        Target target = new Target(
            TARGET,
            2,
            new Vec(6.5, 1.5, .5),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            30,
            5,
            .45,
            .9,
            true,
            false,
            true,
            false,
            false,
            source);
        return new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            self,
            Arrays.asList(target),
            arrows,
            Collections.emptyList(),
            Arrays.asList(new Weapon(-1, "task_sword", resourceWeapon(false, 251, 1, false))),
            new Geometry(-4, -2, -4, 9, 6, 9, 100, true, fixture.cells, fixture.bounds),
            TARGET,
            true);
    }

    private static CombatSnapshot progressFrame(CombatSnapshot base, Vec position, long tick, float health,
        com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact fact, boolean complete) {
        var old = base.self;
        var self = new CombatSnapshot.Self(
            position,
            old.velocity,
            old.ownerPosition,
            health,
            old.ownerHealth,
            old.halfWidth,
            old.height,
            old.reach,
            old.movementPerTick,
            true,
            old.jumpAllowed,
            old.stationary,
            old.shieldAvailable,
            old.equipped,
            old.cadenceReady,
            old.drawing,
            old.attackDelay,
            old.cadenceRemainingTicks,
            old.drawTicks,
            old.ammunition,
            old.preferBow,
            old.preferencePending);
        var targets = new ArrayList<Target>();
        for (Target t : base.targets) targets.add(
            new Target(
                t.id,
                t.entityId,
                t.position,
                t.velocity,
                tick,
                t.lastOwnHitAt,
                t.health,
                t.estimatedDamage,
                t.halfWidth,
                t.height,
                t.visible,
                t.swinging,
                t.ranged,
                t.explosive,
                t.attackingOwner,
                t.damageSource));
        Geometry g = base.geometry;
        byte[] cells = new byte[g.sizeX * g.sizeY * g.sizeZ];
        double[] bounds = new double[cells.length * 6];
        for (int y = 0; y < g.sizeY; y++) for (int z = 0; z < g.sizeZ; z++) for (int x = 0; x < g.sizeX; x++) {
            int i = (y * g.sizeZ + z) * g.sizeX + x;
            cells[i] = (byte) g.at(g.x + x, g.y + y, g.z + z);
            for (int b = 0; b < 6; b++) bounds[i * 6 + b] = g.bound(g.x + x, g.y + y, g.z + z, b);
        }
        return new CombatSnapshot(
            tick,
            base.entityEpoch,
            base.observationEpoch,
            base.equipmentEpoch,
            base.selfId,
            self,
            targets,
            Collections.emptyList(),
            Collections.emptyList(),
            base.weapons,
            new Geometry(g.x, g.y, g.z, g.sizeX, g.sizeY, g.sizeZ, tick, g.complete, cells, bounds),
            base.selectedTarget,
            complete,
            true,
            complete ? tick : Long.MIN_VALUE,
            fact);
    }

    private static void genericProgressRetainsDefensiveSafetyExits() {
        CombatPlanner.setTraceEnabled(true);
        try {
            var normal = progressScene(20, CombatSnapshot.DamageSource.GENERIC_PRIOR, Collections.emptyList());
            var plan = new CombatPlanner().decide(normal, CombatWorkQuota.isolated(100, true));
            check(
                plan.normalProgressAdmitted && plan.stageId > 0 && plan.movementNode != null,
                "Normal generic-prior trial lacked current progress/escape admission: " + plan.macro);
            check(
                plan.trace.candidates.stream()
                    .anyMatch(c -> c.strategy == CombatPlan.Strategy.PURE_DEFENSE),
                "Task filtering erased the independent pure defense candidate");
            check(plan.trace.necessaryComparisonComplete, "Task filtering used an incomplete comparison");
            var low = new CombatPlanner().decide(
                progressScene(8, CombatSnapshot.DamageSource.GENERIC_PRIOR, Collections.emptyList()),
                CombatWorkQuota.isolated(100, true));
            check(
                !low.normalProgressAdmitted && low.strategy == CombatPlan.Strategy.PURE_DEFENSE,
                "Low-health estimate margin forced a task-prefix trial: " + low.macro);
            check(
                low.trace.candidates.stream()
                    .filter(c -> c.progressFeasible)
                    .allMatch(c -> c.risk + c.singleSelf >= 8)
                    && low.trace.candidates.stream()
                        .anyMatch(c -> c.strategy == CombatPlan.Strategy.PURE_DEFENSE && c.risk + c.singleSelf < 8),
                "Low-health witness did not separate unsafe task trials from a surviving pure-defense estimate");
            var unknown = new CombatPlanner().decide(
                progressScene(20, CombatSnapshot.DamageSource.UNKNOWN, Collections.emptyList()),
                CombatWorkQuota.isolated(100, true));
            check(!unknown.normalProgressAdmitted, "UNKNOWN damage source filtered pure defense");
            var arrow = new CombatSnapshot.Projectile(
                new UUID(0, 90),
                new Vec(-1, .7, .5),
                new Vec(2, 0, 0),
                100,
                0,
                1,
                .1,
                6);
            var acute = new CombatPlanner().decide(
                progressScene(20, CombatSnapshot.DamageSource.GENERIC_PRIOR, Arrays.asList(arrow)),
                CombatWorkQuota.isolated(100, true));
            check(
                !acute.normalProgressAdmitted && acute.strategy == CombatPlan.Strategy.PURE_DEFENSE,
                "Current native-sized arrow sweep was hidden by task progress: " + acute.macro);
            var pressured = new CombatPlanner();
            for (long tick = 100; tick <= 102; tick++) {
                var at = progressFrame(normal, normal.self.position, tick, 20, null, true);
                var currentArrow = new CombatSnapshot.Projectile(
                    new UUID(0, 90),
                    new Vec(-1, .7, .5),
                    new Vec(2, 0, 0),
                    tick,
                    0,
                    1,
                    .1,
                    6);
                var input = new CombatSnapshot(
                    at.tick,
                    at.entityEpoch,
                    at.observationEpoch,
                    at.equipmentEpoch,
                    at.selfId,
                    at.self,
                    at.targets,
                    Arrays.asList(currentArrow),
                    at.friends,
                    at.weapons,
                    at.geometry,
                    at.selectedTarget,
                    true);
                var defense = pressured.decide(input, CombatWorkQuota.isolated(tick, true));
                check(
                    !defense.normalProgressAdmitted && defense.strategy == CombatPlan.Strategy.PURE_DEFENSE,
                    "Sustained current arrows forced the interrupted task stage to resume");
            }
            var exhausted = CombatWorkQuota.isolated(100, true);
            exhausted.spend(Work.ADVANCE, CombatWorkQuota.MAX_ADVANCES - 20);
            var partial = new CombatPlanner().decide(normal, exhausted);
            check(
                !partial.normalProgressAdmitted && !partial.simulated,
                "Incomplete necessary forecasts filtered the pure defense exit");
            System.out.println(
                "CombatProgress normal=" + plan.strategy
                    + "/"
                    + plan.macro
                    + " low="
                    + low.macro
                    + " unknownFiltered="
                    + unknown.normalProgressAdmitted
                    + " acuteFiltered="
                    + acute.normalProgressAdmitted);
        } finally {
            CombatPlanner.setTraceEnabled(false);
        }
    }

    private static CombatSnapshot returnScene() {
        TerrainFixture fixture = new TerrainFixture();
        fixture.box(1, 0, 1, 1, Geometry.SOLID);
        fixture.box(1, 0, 2, 1, Geometry.SOLID);
        fixture.target = new Target(
            TARGET,
            2,
            new Vec(3.5, 0, .5),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            20,
            8,
            .3,
            1.8,
            true,
            false,
            true,
            false,
            false);
        return fixture.snapshot();
    }

    private static void defensiveReturnRemainsOneBoundedStrategy() {
        CombatPlanner.setTraceEnabled(true);
        try {
            var quota = CombatWorkQuota.isolated(100, true);
            var plan = new CombatPlanner().decide(returnScene(), quota);
            check(
                plan.normalProgressAdmitted && plan.strategy == CombatPlan.Strategy.DEFENSIVE_RETURN
                    && plan.route.defenseNode > 0
                    && plan.stagePhase == 0,
                "Proven cover could not participate as a returning task strategy");
            check(
                plan.trace.candidates.stream()
                    .anyMatch(
                        c -> c.strategy == CombatPlan.Strategy.PURE_DEFENSE && c.macro == CombatPlan.Macro.COVER_EDGE)
                    && plan.trace.candidates.stream()
                        .anyMatch(c -> c.strategy == CombatPlan.Strategy.DEFENSIVE_RETURN),
                "Strategy dedup merged pure cover with defensive return");
            check(
                quota.used(Work.ADVANCE) == plan.trace.candidates.size() * 60 && quota.used(Work.ADVANCE) <= 720
                    && quota.used(Work.CANDIDATE) <= 12,
                "Defensive return secretly ran a second full horizon");
            System.out.println(
                "CombatProgress returnNodes=" + plan.route.nodes.size()
                    + " turn="
                    + plan.route.defenseNode
                    + " candidates="
                    + quota.used(Work.CANDIDATE)
                    + " advances="
                    + quota.used(Work.ADVANCE));
        } finally {
            CombatPlanner.setTraceEnabled(false);
        }
    }

    private static void nativeFactsActivateAndAdvanceWithoutReplayingDefense() {
        var original = returnScene();
        var base = progressFrame(original, original.self.position, 100, 40, null, true);
        var planner = new CombatPlanner();
        CombatPlan plan = planner.decide(base, CombatWorkQuota.isolated(100, true));
        check(
            plan.stageState == CombatPlan.StageState.PROPOSED && plan.firstNativeAcceptanceAt == Long.MIN_VALUE,
            "RUNNING-free proposal fabricated native activation");
        long stage = plan.stageId, tick = 100;
        int defenseEnd = plan.route.defenseNode;
        for (int n = 1; n <= defenseEnd; n++) {
            var node = plan.movementNode;
            var installed = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
                node,
                com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.INSTALLED,
                tick,
                n,
                0,
                base.self.position,
                true,
                true,
                true);
            plan = planner.decide(
                progressFrame(base, base.self.position, ++tick, 40, installed, true),
                CombatWorkQuota.isolated(tick, false));
            check(
                plan.stageId == stage && plan.stageState == CombatPlan.StageState.ACTIVE
                    && plan.firstNativeAcceptanceAt == 100,
                "Real path installation failed to activate its exact stage");
            node = plan.movementNode;
            var arrived = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
                node,
                com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
                tick,
                n,
                0,
                node.foot,
                true,
                true,
                true);
            base = progressFrame(base, node.foot, ++tick, 40, arrived, true);
            plan = planner.decide(base, CombatWorkQuota.isolated(tick, true));
            check(
                plan.stageId == stage && plan.movementNode != null && plan.movementNode.index > node.index,
                "FULL/new route replayed a genuinely completed native node: n=" + n
                    + " old="
                    + node.stage
                    + "/"
                    + node.index
                    + " now="
                    + plan.stageId
                    + "/"
                    + plan.stageState
                    + "/"
                    + plan.stagePhase
                    + " macro="
                    + plan.macro
                    + " movement="
                    + (plan.movementNode == null ? null : plan.movementNode.stage + "/" + plan.movementNode.index)
                    + " admitted="
                    + plan.normalProgressAdmitted);
        }
        check(
            plan.stagePhase == 1 && plan.macro == CombatPlan.Macro.APPROACH,
            "Completed cover prefix was selected again instead of its proved return");
        int completedPhase = plan.stagePhase;
        plan = planner.decide(
            progressFrame(base, base.self.position, ++tick, 40, null, true),
            CombatWorkQuota.isolated(tick, false));
        check(
            plan.stageId == stage && plan.stagePhase >= completedPhase,
            "Ordinary LIGHT reset the completed defensive substage");
        System.out.println(
            "CombatProgress stage=" + stage
                + " returnPhase="
                + plan.stagePhase
                + " nextNode="
                + plan.movementNode.index);
    }

    private static void waitingAndOriginalBackoffCannotBecomeFabricatedFailure() {
        var base = progressScene(20, CombatSnapshot.DamageSource.GENERIC_PRIOR, Collections.emptyList());
        var planner = new CombatPlanner();
        var initial = planner.decide(base, CombatWorkQuota.isolated(100, true));
        CombatPlan plan = initial;
        for (long tick : new long[] { 101, 140, 180 }) {
            plan = planner.decide(
                progressFrame(base, base.self.position, tick, 20, null, false),
                CombatWorkQuota.isolated(tick, false));
            check(
                plan.stageId == initial.stageId && plan.stageState == CombatPlan.StageState.WAIT_SERVICE
                    && plan.firstNativeAcceptanceAt == Long.MIN_VALUE,
                "Uninstalled/scan wait became ACTIVE, timeout or native failure");
        }
        var retryFrame = progressFrame(base, base.self.position, 181, 20, null, true);
        plan = planner.decide(retryFrame, CombatWorkQuota.isolated(181, true));
        check(
            plan.stageId == initial.stageId && plan.movementNode != null,
            "Service recovery lost the proposed prefix");
        var failure = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            plan.movementNode,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.NATIVE_FAILURE,
            181,
            0,
            201,
            base.self.position,
            true,
            true,
            false);
        var failed = planner.decide(
            progressFrame(base, base.self.position, 182, 20, failure, true),
            CombatWorkQuota.isolated(182, true));
        check(
            failed.stageState == CombatPlan.StageState.NATIVE_FAILURE && !failed.normalProgressAdmitted
                && failed.stageRetryAt == 201
                && failed.stageReason.equals("NATIVE_FAILURE"),
            "New FULL bypassed an observed native backoff");
        var retried = planner
            .decide(progressFrame(base, base.self.position, 201, 20, null, true), CombatWorkQuota.isolated(201, true));
        check(
            retried.stageId == initial.stageId && retried.stageState != CombatPlan.StageState.NATIVE_FAILURE
                && retried.movementNode != null,
            "Original native retry time permanently poisoned an unchanged boundary");
        planner.finishDecision();
        var cancelled = planner.decide(
            progressFrame(base, base.self.position, 202, 20, null, false),
            CombatWorkQuota.isolated(202, false));
        check(cancelled.stageId == 0, "Root cancellation retained an old task stage");
    }

    private static void nativeNodeFactsRejectWrongFootIdentityAndPhase() {
        var base = progressScene(20, CombatSnapshot.DamageSource.GENERIC_PRIOR, Collections.emptyList());
        var node = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Node(
            2,
            1,
            base,
            new Vec(.5, .5, .5));
        var correct = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            node,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
            100,
            3,
            0,
            new Vec(.98, .5, .98),
            true,
            true,
            true);
        check(correct.completed(node, 101), "Native same-cell completion incorrectly required center distance .35");
        check(!correct.completed(node, 102), "Expired completion from an older frame advanced a stage");
        var wrongNode = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Node(
            4,
            1,
            base,
            node.foot);
        check(!correct.completed(wrongNode, 101), "Other stage's ARRIVED advanced this route");
        for (double foot : new double[] { 0, 1 }) {
            var wrong = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
                node,
                com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
                100,
                3,
                0,
                new Vec(.5, foot, .5),
                true,
                true,
                true);
            check(!wrong.completed(node, 101), "Rounded half-slab Y fabricated a correct support foot");
        }
        var uninstalled = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            node,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
            100,
            0,
            0,
            node.foot,
            true,
            true,
            false);
        check(!uninstalled.completed(node, 101), "Pending request without raw-path/tail evidence became COMPLETE");
        var low = returnScene();
        var t = low.targets.get(0);
        var diagonal = new Target(
            t.id,
            t.entityId,
            t.position,
            t.velocity,
            100,
            t.lastOwnHitAt,
            t.health,
            t.estimatedDamage,
            t.halfWidth,
            t.height,
            true,
            false,
            true,
            false,
            false,
            CombatSnapshot.DamageSource.UNKNOWN);
        low = new CombatSnapshot(
            low.tick,
            low.entityEpoch,
            low.observationEpoch,
            low.equipmentEpoch,
            low.selfId,
            low.self,
            Arrays.asList(diagonal),
            low.projectiles,
            low.friends,
            low.weapons,
            low.geometry,
            TARGET,
            true);
        var planner = new CombatPlanner();
        CombatPlan defense = planner.decide(low, CombatWorkQuota.isolated(100, true));
        check(
            defense.strategy == CombatPlan.Strategy.PURE_DEFENSE && defense.route.nodes.size() >= 4,
            "Could not establish a multi-node pure defense continuation: " + defense.macro
                + "/"
                + defense.strategy
                + "/nodes="
                + (defense.route == null ? 0 : defense.route.nodes.size()));
        for (int index = 1; index <= 2; index++) {
            var submitted = defense.movementNode;
            var done = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
                submitted,
                com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
                99 + index,
                7,
                0,
                submitted.foot,
                true,
                true,
                true);
            var frame = progressFrame(low, submitted.foot, 100 + index, low.self.health, done, true);
            defense = planner.decide(frame, CombatWorkQuota.isolated(frame.tick, false));
            check(
                defense.movementNode != null && defense.movementNode.index > submitted.index,
                "Pure defense kept comparing every completion to its original FULL first node");
        }
    }

    private static void navigationCompletionPrecedesCleanupAndServiceWaitIsNotFailure() {
        var actor = new ProgressNavigationActor();
        var navigation = new com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedNavigationController(
            actor,
            pos -> new com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathCreation(
                com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathCreation.Result.WAITING_BUDGET,
                null),
            () -> {});
        for (long tick : new long[] { 100, 160 }) {
            actor.tick = tick;
            check(
                navigation.tick()
                    == com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedNavigationController.Outcome.WAITING_BUDGET
                    && navigation.failureCount() == 0
                    && actor.cantReach == null,
                "Path service starvation fabricated a native unreachable attempt or H40 timeout");
        }
        actor.path = actor.installed = new ProgressNavigationActor.TestPath();
        actor.position = new Vec(2.98, 0, .98);
        actor.desired = new Vec(2.5, .5, .5);
        check(
            navigation.tick() != com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedNavigationController.Outcome.ARRIVED,
            "Half-slab node completed at the wrong actual footY");
        actor.position = new Vec(2.98, .5, .98);
        check(
            navigation.tick() == com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedNavigationController.Outcome.ARRIVED
                && actor.arrivedBeforeCleanup
                && actor.path == null
                && actor.walk == null,
            "Native node/path identity was lost before the combat ARRIVED snapshot");
    }

    /** 只验证真实增强导航类的结果顺序，不代替Minecraft物理或实际路径安装 */
    private static final class ProgressNavigationActor
        implements com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor,
        com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatNavigationObserver {

        long tick = 100;
        Long cantReach;
        Vec position = new Vec(.5, 0, .5), desired = new Vec(2.5, .5, .5);
        TestPath path, installed;
        WalkTarget walk = new WalkTarget() {

            public BlockPos currentBlockPosition() {
                return new BlockPos(2, 1, 0);
            }

            public float getSpeedModifier() {
                return .6F;
            }

            public int getCloseEnoughDistance() {
                return 0;
            }

            public boolean isEntityTrackerSpectator() {
                return false;
            }
        };
        boolean arrivedBeforeCleanup;

        public boolean isWalkTargetRegistered() {
            return true;
        }

        public boolean isPathRegistered() {
            return true;
        }

        public boolean isCantReachSinceRegistered() {
            return true;
        }

        public WalkTarget getWalkTarget() {
            return walk;
        }

        public Path getPathMemory() {
            return path;
        }

        public Long getCantReachSince() {
            return cantReach;
        }

        public void eraseWalkTarget() {
            walk = null;
        }

        public void setPathMemory(Path value) {
            path = (TestPath) value;
        }

        public void eraseCantReachSince() {
            cantReach = null;
        }

        public void setCantReachSince(long value) {
            cantReach = value;
        }

        public BlockPos blockPosition() {
            return new BlockPos(
                (int) Math.floor(position.x),
                (int) Math.floor(position.y),
                (int) Math.floor(position.z));
        }

        public long worldGameTime() {
            return tick;
        }

        public int nextWorldInt(int bound) {
            throw new AssertionError("Unexpected RNG");
        }

        public Path createPath(BlockPos target, int accuracy) {
            throw new AssertionError("Unexpected direct search");
        }

        public Path createPath(Vec3 target, int accuracy) {
            throw new AssertionError("Unexpected direct search");
        }

        public Vec3 findFallbackTowards(int h, int v, Vec3 p, double angle) {
            throw new AssertionError("Unexpected fallback");
        }

        public Path navigationPath() {
            return path;
        }

        public boolean navigationIsDone() {
            return path == null;
        }

        public boolean navigationIsStuck() {
            return false;
        }

        public boolean moveTo(Path value, double speed) {
            path = (TestPath) value;
            return true;
        }

        public void stopNavigation() {
            path = null;
        }

        public boolean isCombatNode(WalkTarget target) {
            return target != null && target == walk;
        }

        public boolean combatNodeArrived(WalkTarget target, Path current) {
            return current != null && current == installed
                && isCombatNode(target)
                && com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact
                    .sameStandingCell(position, desired);
        }

        public void combatNavigationOutcome(WalkTarget target, Path current,
            com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedNavigationController.Outcome outcome, long retryAt) {
            if (outcome == com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedNavigationController.Outcome.ARRIVED)
                arrivedBeforeCleanup = target == walk && current == path && current == installed;
        }

        private static final class TestPath implements Path {

            public Object getIdentity() {
                return this;
            }

            public boolean canReach() {
                return true;
            }
        }
    }

    private static void continuationServiceAndRequiredComparisonKeepTheirContracts() {
        boolean before = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        try {
            r1BlockedCurrentStepKeepsFallback();
            r1NativeBackoffKeepsFallback();
            r1UnavailableRiskKeepsCompletedPrefix();
            r1MissingRequiredCandidateClosesAdmission();
        } finally {
            CombatPlanner.setTraceEnabled(before);
        }
    }

    private static final class R1Prepared {

        final CombatPlanner planner = new CombatPlanner();
        CombatSnapshot base;
        CombatPlan plan;
        long tick = 100;
    }

    private static R1Prepared r1Prepared(boolean completeDefense) {
        var value = new R1Prepared();
        var scene = returnScene();
        value.base = progressFrame(scene, scene.self.position, 100, 40, null, true);
        value.plan = value.planner.decide(value.base, CombatWorkQuota.isolated(100, true));
        check(
            value.plan.normalProgressAdmitted && value.plan.stageId > 0,
            "R1 witness did not start with an admitted defensive-return stage");
        int count = completeDefense ? value.plan.route.defenseNode : 0;
        for (int node = 0; node <= count; node++) {
            var submitted = value.plan.movementNode;
            var installed = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
                submitted,
                com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.INSTALLED,
                value.tick,
                node + 1,
                0,
                value.base.self.position,
                true,
                true,
                true);
            value.base = progressFrame(value.base, value.base.self.position, ++value.tick, 40, installed, true);
            value.plan = value.planner.decide(value.base, CombatWorkQuota.isolated(value.tick, false));
            if (!completeDefense || node == count) break;
            submitted = value.plan.movementNode;
            var arrived = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
                submitted,
                com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
                value.tick,
                node + 1,
                0,
                submitted.foot,
                true,
                true,
                true);
            value.base = progressFrame(value.base, submitted.foot, ++value.tick, 40, arrived, true);
            value.plan = value.planner.decide(value.base, CombatWorkQuota.isolated(value.tick, true));
        }
        check(
            value.plan.stageState == CombatPlan.StageState.ACTIVE && value.plan.firstNativeAcceptanceAt == 100,
            "R1 fixture lacked a real value-level installation fact");
        if (completeDefense) check(
            value.plan.stagePhase == 1 && value.plan.movementNode.index > 1,
            "R1 risk-service witness did not consume its defensive prefix");
        return value;
    }

    private static void r1BlockedCurrentStepKeepsFallback() {
        var value = r1Prepared(false);
        var node = value.plan.movementNode;
        var current = progressFrame(value.base, value.base.self.position, ++value.tick, 40, null, true);
        Geometry g = current.geometry;
        byte[] cells = new byte[g.sizeX * g.sizeY * g.sizeZ];
        double[] bounds = new double[cells.length * 6];
        for (int y = 0; y < g.sizeY; y++) for (int z = 0; z < g.sizeZ; z++) for (int x = 0; x < g.sizeX; x++) {
            int i = (y * g.sizeZ + z) * g.sizeX + x, px = g.x + x, py = g.y + y, pz = g.z + z;
            cells[i] = (byte) g.at(px, py, pz);
            for (int b = 0; b < 6; b++) bounds[i * 6 + b] = g.bound(px, py, pz, b);
            if (px == (int) Math.floor(node.foot.x) && py == (int) Math.floor(node.foot.y)
                && pz == (int) Math.floor(node.foot.z)) {
                cells[i] = (byte) (Geometry.SOLID | Geometry.RAY_BLOCKING);
                double[] box = { px, py, pz, px + 1, py + 1, pz + 1 };
                System.arraycopy(box, 0, bounds, i * 6, 6);
            }
        }
        var blocked = new CombatSnapshot(
            current.tick,
            current.entityEpoch,
            current.observationEpoch,
            current.equipmentEpoch,
            current.selfId,
            current.self,
            current.targets,
            current.projectiles,
            current.friends,
            current.weapons,
            new Geometry(g.x, g.y, g.z, g.sizeX, g.sizeY, g.sizeZ, current.tick, true, cells, bounds),
            current.selectedTarget,
            true);
        check(
            !CombatTerrain
                .safeStep(blocked, blocked.self.position, node.foot, CombatWorkQuota.isolated(blocked.tick, false))
                && !CombatTerrain.safeRequestStep(
                    blocked,
                    blocked.self.position,
                    node.foot,
                    CombatWorkQuota.isolated(blocked.tick, false)),
            "R1 blocked-step premise did not fail both proofs");
        var result = value.planner.decide(blocked, CombatWorkQuota.isolated(blocked.tick, false));
        check(
            result.trace.candidates.stream()
                .anyMatch(c -> c.strategy == CombatPlan.Strategy.PURE_DEFENSE && c.utility > -100),
            "R1 blocked-step witness had no legal pure defense");
        check(
            !result.trace.intentEligible && result.stageId == value.plan.stageId
                && result.stageState == CombatPlan.StageState.WAIT_SERVICE
                && result.macro != CombatPlan.Macro.WAIT,
            "R1 blocked continuation swallowed legal fallback: " + result.macro
                + "/eligible="
                + result.trace.intentEligible
                + "/stage="
                + result.stageId);
        System.out.println(
            "R1 blockedStep=" + result.macro + "/eligible=" + result.trace.intentEligible + "/stage=" + result.stageId);
    }

    private static void r1NativeBackoffKeepsFallback() {
        var value = r1Prepared(false);
        var failed = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            value.plan.movementNode,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.NATIVE_FAILURE,
            value.tick,
            1,
            value.tick + 20,
            value.base.self.position,
            true,
            true,
            false);
        var frame = progressFrame(value.base, value.base.self.position, ++value.tick, 40, failed, true);
        var result = value.planner.decide(frame, CombatWorkQuota.isolated(frame.tick, false));
        check(
            result.trace.candidates.stream()
                .anyMatch(c -> c.strategy == CombatPlan.Strategy.PURE_DEFENSE && c.utility > -100),
            "R1 native-backoff witness had no legal pure defense");
        check(
            !result.trace.intentEligible && result.stageId == value.plan.stageId
                && result.stageState == CombatPlan.StageState.NATIVE_FAILURE
                && result.stageRetryAt == failed.retryAt
                && result.macro != CombatPlan.Macro.WAIT,
            "R1 native backoff swallowed legal fallback: " + result.macro
                + "/eligible="
                + result.trace.intentEligible
                + "/retry="
                + result.stageRetryAt);
        System.out.println(
            "R1 nativeBackoff=" + result.macro
                + "/eligible="
                + result.trace.intentEligible
                + "/retry="
                + result.stageRetryAt);
    }

    private static void r1UnavailableRiskKeepsCompletedPrefix() {
        r1RiskKeepsCompletedPrefix(false);
        r1RiskKeepsCompletedPrefix(true);
    }

    private static void r1RiskKeepsCompletedPrefix(boolean nonfinite) {
        var value = r1Prepared(true);
        var node = value.plan.movementNode;
        long stage = value.plan.stageId, first = value.plan.firstNativeAcceptanceAt;
        var frame = progressFrame(value.base, value.base.self.position, ++value.tick, 40, null, true);
        var quota = CombatWorkQuota.isolated(frame.tick, false);
        if (nonfinite) {
            var t = frame.targets.get(0);
            var unknown = new Target(
                t.id,
                t.entityId,
                t.position,
                t.velocity,
                t.observedAt,
                t.lastOwnHitAt,
                t.health,
                Float.NaN,
                t.halfWidth,
                t.height,
                t.visible,
                t.swinging,
                t.ranged,
                t.explosive,
                t.attackingOwner,
                CombatSnapshot.DamageSource.UNKNOWN);
            frame = new CombatSnapshot(
                frame.tick,
                frame.entityEpoch,
                frame.observationEpoch,
                frame.equipmentEpoch,
                frame.selfId,
                frame.self,
                Arrays.asList(unknown),
                frame.projectiles,
                frame.friends,
                frame.weapons,
                frame.geometry,
                frame.selectedTarget,
                true);
        } else check(
            quota.spend(Work.COLLISION, quota.remaining(Work.COLLISION)),
            "Could not establish exact instantaneous-query budget exhaustion");
        var waiting = value.planner.decide(frame, quota);
        check(
            !waiting.normalProgressAdmitted && !waiting.trace.intentEligible
                && waiting.stageId == stage
                && waiting.firstNativeAcceptanceAt == first
                && waiting.stagePhase == 1
                && waiting.stageState == CombatPlan.StageState.WAIT_SERVICE
                && waiting.movementNode == null,
            "R1 unavailable immediate risk erased/replayed the accepted prefix: nonfinite=" + nonfinite
                + "/stage="
                + waiting.stageId
                + "/phase="
                + waiting.stagePhase
                + "/first="
                + waiting.firstNativeAcceptanceAt
                + "/macro="
                + waiting.macro);
        var recovered = value.planner.decide(
            progressFrame(value.base, value.base.self.position, ++value.tick, 40, null, true),
            CombatWorkQuota.isolated(value.tick, true));
        check(
            recovered.stageId == stage && recovered.firstNativeAcceptanceAt == first
                && recovered.stagePhase == 1
                && recovered.movementNode != null
                && recovered.movementNode.index == node.index,
            "R1 service recovery replayed an already completed defense segment");
        System.out.println(
            "R1 riskUnavailable=" + nonfinite
                + "/stage="
                + stage
                + "/phase="
                + recovered.stagePhase
                + "/node="
                + recovered.movementNode.index
                + "/first="
                + first);
    }

    private static void r1MissingRequiredCandidateClosesAdmission() {
        for (int remaining : new int[] { 1, 2 }) {
            Object world = new Object();
            try {
                int fill = 256 - remaining;
                for (int n = 0; fill > 0; n++) {
                    var spent = CombatWorldBudget.heartbeat(world, new UUID(700, n), 100, false);
                    int amount = Math.min(12, fill);
                    check(spent.spend(Work.CANDIDATE, amount), "Could not establish shared CANDIDATE residue");
                    fill -= amount;
                }
                UUID requester = new UUID(701, remaining);
                CombatWorldBudget.heartbeat(world, requester, 100, true);
                CombatWorldBudget.prepared(world, requester, 100, true, 100);
                var quota = CombatWorldBudget.heartbeat(world, requester, 100, true);
                check(
                    quota.full && quota.remaining(Work.CANDIDATE) == remaining,
                    "R1 required-candidate witness did not obtain a real FULL with the exact residue");
                var scene = returnScene();
                var frame = progressFrame(scene, scene.self.position, 100, 40, null, true);
                var result = new CombatPlanner().decide(frame, quota);
                check(
                    result.trace.candidates.stream()
                        .anyMatch(c -> c.damage > 0 && c.escapeProved && c.riskKnown && c.risk + c.singleSelf < 40),
                    "R1 residue witness lacked an otherwise viable old attack");
                check(
                    !result.normalProgressAdmitted && !result.necessaryComparisonComplete
                        && !result.trace.necessaryComparisonComplete,
                    "R1 missing required candidates were counted as a completed comparison: residue=" + remaining
                        + "/admitted="
                        + result.normalProgressAdmitted
                        + "/actual="
                        + result.trace.candidates.size());
                System.out.println(
                    "R1 candidateResidue=" + remaining
                        + "/actual="
                        + result.trace.candidates.size()
                        + "/comparison="
                        + result.necessaryComparisonComplete
                        + "/admitted="
                        + result.normalProgressAdmitted);
            } finally {
                CombatWorldBudget.unload(world);
            }
        }
    }

    private static CombatSnapshot reobsFrame(CombatSnapshot base, Vec at, long tick, float hp,
        com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact fact, boolean body, boolean refresh) {
        var current = progressFrame(base, at, tick, hp, fact, false);
        var targets = new ArrayList<Target>();
        for (Target t : base.targets) targets.add(
            new Target(
                t.id,
                t.entityId,
                t.position,
                new Vec(0, 0, 0),
                t.observedAt,
                t.lastOwnHitAt,
                t.health,
                t.estimatedDamage,
                t.halfWidth,
                t.height,
                false,
                t.swinging,
                t.ranged,
                t.explosive,
                t.attackingOwner,
                t.damageSource));
        return new CombatSnapshot(
            tick,
            base.entityEpoch,
            base.observationEpoch,
            base.equipmentEpoch,
            base.selfId,
            current.self,
            targets,
            base.projectiles,
            Collections.emptyList(),
            base.weapons,
            refresh ? current.geometry : base.geometry,
            base.selectedTarget,
            false,
            true,
            tick,
            fact,
            body);
    }

    private static CombatSnapshot reobsScene() {
        return reobsFrame(new TerrainFixture().snapshot(), new Vec(.5, 0, .5), 100, 20, null, true, true);
    }

    private static boolean reobserving(CombatPlan plan) {
        return plan.strategy == CombatPlan.Strategy.REOBSERVE;
    }

    private static void boundedReobservationMechanisms() {
        boolean before = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        try {
            reobsBodyIsNotCombatAndHasAnActualMovement();
            reobsNoFreshHiddenFactsOrRenewedExpiry();
            reobsRiskAndNecessaryComparisonRejectUnsafe();
            reobsUnknownWorldAndDoorCannotBecomeOrdinary();
            reobsNativeFactsAndExpectedDoorContextStaySeparate();
            reobsServicesSettleOnlyRealCaptureOnce();
            reobsVisibleConnectorUsesOneGraphAndExactNext();
        } finally {
            CombatPlanner.setTraceEnabled(before);
        }
    }

    private static void reobsBodyIsNotCombatAndHasAnActualMovement() {
        var scene = reobsScene();
        var q = CombatWorkQuota.isolated(100, true);
        var plan = new CombatPlanner().decide(scene, q);
        check(
            scene.bodyComplete && !scene.complete && reobserving(plan),
            "Body-only service did not yield the distinct REOBSERVE movement: " + plan.macro + "/" + plan.strategy);
        check(
            plan.movementNode != null && plan.noDoorSegment != null
                && plan.expectedDamage == 0
                && !plan.simulated
                && !plan.normalProgressAdmitted
                && !plan.necessaryComparisonComplete
                && plan.reobservationComparisonComplete,
            "Reobservation leaked attack/normal progress or omitted the native no-door segment");
        check(
            Double.isNaN(plan.expectedRisk) && plan.diagnosticTotalRisk() == null
                && plan.diagnosticCombatUtility() == null
                && plan.riskScope == CombatPlan.RiskScope.OBSERVED_ONLY_TOTAL_UNKNOWN,
            "Observed-only forecast pretended to know total risk or serialized NaN as an ordinary total");
        check(
            q.used(Work.ADVANCE) > 0 && q.used(Work.ADVANCE) <= 240 && plan.trace.terrainVisited <= 49,
            "Body comparison escaped its single bounded graph/horizon allowance");
        var missing = reobsFrame(scene, scene.self.position, 100, 20, null, false, true);
        check(
            !reobserving(new CombatPlanner().decide(missing, CombatWorkQuota.isolated(100, true))),
            "REOBSERVE bypassed missing body service");
        var old = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            scene.self,
            scene.targets,
            scene.projectiles,
            scene.friends,
            scene.weapons,
            scene.geometry,
            TARGET,
            false,
            true,
            100);
        check(!old.bodyComplete, "Legacy incomplete constructor granted body authority");
        System.out
            .println("REOBS body=true combat=false strategy=" + plan.strategy + " advances=" + q.used(Work.ADVANCE));
    }

    private static void reobsNoFreshHiddenFactsOrRenewedExpiry() {
        var scene = reobsScene();
        var planner = new CombatPlanner();
        var plan = planner.decide(scene, CombatWorkQuota.isolated(100, true));
        var light = reobsFrame(scene, scene.self.position, 101, 20, null, true, false);
        var continued = planner.decide(light, CombatWorkQuota.isolated(101, false));
        check(
            reobserving(continued) && continued.stageId == plan.stageId
                && continued.movementNode.index == plan.movementNode.index
                && continued.route.provedAt == 100
                && light.targets.get(0).observedAt == 100,
            "LIGHT recreated identity, moved cursor, or renewed geometry/hidden observation");
        var expired = reobsFrame(scene, scene.self.position, 109, 20, null, true, false);
        check(
            !reobserving(planner.decide(expired, CombatWorkQuota.isolated(109, false))),
            "Expired body proof still moved");
        var targetExpired = reobsFrame(scene, scene.self.position, 201, 20, null, true, true);
        check(
            !reobserving(planner.decide(targetExpired, CombatWorkQuota.isolated(201, true))),
            "Hidden100 was renewed by body refresh");
        var copy = new CombatPlanner().decide(reobsScene(), CombatWorkQuota.isolated(100, true));
        check(copy.destination.distance(plan.destination) < .0001, "Equal last-seen pure input changed the proposal");
    }

    private static void reobsRiskAndNecessaryComparisonRejectUnsafe() {
        var scene = reobsScene();
        check(
            !reobserving(
                new CombatPlanner().decide(
                    reobsFrame(scene, scene.self.position, 100, 1, null, true, true),
                    CombatWorkQuota.isolated(100, true))),
            "Hidden generic single-impact reserve vanished at low health");
        Target t = scene.targets.get(0);
        var unknown = new Target(
            t.id,
            t.entityId,
            t.position,
            t.velocity,
            t.observedAt,
            t.lastOwnHitAt,
            t.health,
            t.estimatedDamage,
            t.halfWidth,
            t.height,
            false,
            false,
            false,
            false,
            false,
            CombatSnapshot.DamageSource.UNKNOWN);
        var source = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            scene.self,
            Arrays.asList(unknown),
            scene.projectiles,
            scene.friends,
            scene.weapons,
            scene.geometry,
            TARGET,
            false,
            true,
            100,
            null,
            true);
        check(
            !reobserving(new CombatPlanner().decide(source, CombatWorkQuota.isolated(100, true))),
            "UNKNOWN source became a finite hidden reserve");
        var q = CombatWorkQuota.isolated(100, true);
        check(q.spend(Work.CANDIDATE, 11), "Candidate residue setup failed");
        check(
            !reobserving(new CombatPlanner().decide(scene, q)),
            "Missing WAIT/defense comparison admitted the remaining REOBSERVE candidate");
        var arrow = new CombatSnapshot.Projectile(
            new UUID(99, 1),
            new Vec(.5, .6, -.5),
            new Vec(0, 0, 1),
            100,
            0,
            1,
            .1,
            6);
        var acute = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            scene.self,
            scene.targets,
            Arrays.asList(arrow),
            scene.friends,
            scene.weapons,
            scene.geometry,
            TARGET,
            false,
            true,
            100,
            null,
            true);
        check(
            !reobserving(new CombatPlanner().decide(acute, CombatWorkQuota.isolated(100, true))),
            "Acute real sweep was ignored for body recovery");
        var a = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatObservedRisk(
            new double[] { 1, 0, 0 },
            new double[] { 0, 0, 0 },
            4,
            0,
            4,
            true,
            false);
        var b = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatObservedRisk(
            new double[] { 0, 0, 0 },
            new double[] { 0, 0, 0 },
            4,
            0,
            4,
            true,
            false);
        check(!a.nonWorse(b), "Observed componentwise loss became an unknown-total exemption");
    }

    private static void reobsUnknownWorldAndDoorCannotBecomeOrdinary() {
        for (int flags : new int[] { Geometry.UNKNOWN, Geometry.HAZARD, Geometry.OPENABLE | Geometry.SOLID }) {
            var fixture = new TerrainFixture();
            for (int y = 0; y <= 2; y++)
                for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) fixture.box(x, y, z, y + 1, flags);
            var scene = reobsFrame(fixture.snapshot(), new Vec(.5, 0, .5), 100, 20, null, true, true);
            check(
                !reobserving(new CombatPlanner().decide(scene, CombatWorkQuota.isolated(100, true))),
                "Unknown/hazard/closed-door body cells acquired an ordinary reobservation route");
        }
    }

    private static void reobsNativeFactsAndExpectedDoorContextStaySeparate() {
        var scene = reobsScene();
        var planner = new CombatPlanner();
        var plan = planner.decide(scene, CombatWorkQuota.isolated(100, true));
        var node = plan.movementNode;
        var fact = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            node,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
            100,
            4,
            0,
            node.foot,
            true,
            true,
            true);
        var next = planner
            .decide(reobsFrame(scene, node.foot, 101, 20, fact, true, false), CombatWorkQuota.isolated(101, false));
        check(
            reobserving(next) && next.stageId == plan.stageId && next.movementNode.index == node.index + 1,
            "Real REOBSERVE arrival did not advance its own cursor exactly once");
        check(fact.terminalFor(node, 101), "True arrival was not retained across subsequent native path cleanup");
        var revoked = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            next.movementNode,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.WAIT_SERVICE,
            101,
            4,
            0,
            node.foot,
            true,
            true,
            false,
            "REOBSERVATION_DOOR_OBSERVED_REPROOF_REQUIRED",
            -1,
            -1,
            null);
        var stopped = planner
            .decide(reobsFrame(scene, node.foot, 102, 20, revoked, true, false), CombatWorkQuota.isolated(102, false));
        check(!reobserving(stopped), "Lease revocation reused the old comparison on LIGHT before FULL reproof");
        var refreshed = reobsFrame(scene, node.foot, 103, 20, null, true, true);
        var reproved = planner.decide(refreshed, CombatWorkQuota.isolated(103, true));
        check(
            reobserving(reproved) && reproved.stageId == plan.stageId
                && reproved.movementNode.index == next.movementNode.index
                && reproved.firstNativeAcceptanceAt == 100,
            "FULL lease reproof reset the existing cursor/native acceptance");
        var budgetWait = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            reproved.movementNode,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.WAIT_SERVICE,
            103,
            4,
            0,
            node.foot,
            true,
            true,
            true,
            "WAITING_BUDGET",
            -1,
            -1,
            null);
        var ordinary = planner.decide(
            reobsFrame(refreshed, node.foot, 104, 20, budgetWait, true, false),
            CombatWorkQuota.isolated(104, false));
        check(reobserving(ordinary), "Ordinary budget wait unnecessarily invalidated an installed fresh body proof");
        var localDone = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            ordinary.movementNode,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
            104,
            5,
            0,
            ordinary.movementNode.foot,
            true,
            true,
            true);
        check(
            !reobserving(
                planner.decide(
                    reobsFrame(refreshed, ordinary.movementNode.foot, 105, 20, localDone, true, false),
                    CombatWorkQuota.isolated(105, false))),
            "Completed local endpoint fabricated a new LIGHT route/node");
        var parent = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Node(
            node.stage + 2,
            node.index,
            scene,
            node.foot);
        check(
            !fact.completed(parent, 101) && !fact.completed(node, 102),
            "Cross-owner or stale body fact completed a different node");
        var segment = plan.noDoorSegment;
        int[] direct = { (int) Math.floor(segment.from.x), (int) Math.floor(segment.from.y + .5),
            (int) Math.floor(segment.from.z), (int) Math.floor(segment.to.x), (int) Math.floor(segment.to.y + .5),
            (int) Math.floor(segment.to.z) };
        check(
            segment.acceptsNative(direct, 2, 0, CombatWorkQuota.isolated(100, false)),
            "Exact paid native segment was rejected");
        check(
            !segment.acceptsNative(new int[9], 3, 0, CombatWorkQuota.isolated(100, false)),
            "Native detour escaped the proved node sequence");
        var continued = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementSegment(
            segment.node,
            segment.to,
            segment.geometryAt);
        check(
            continued.acceptsNative(direct, 2, 1, CombatWorkQuota.isolated(100, false)),
            "Consumed paid native prefix was compared with the moved current from cell");
        check(
            !continued.acceptsNative(direct, 2, 0, CombatWorkQuota.isolated(100, false)),
            "New unbound native path borrowed consumed-prefix permission");
        int[] changedTail = direct.clone();
        changedTail[3] += 1;
        check(
            !continued.acceptsNative(changedTail, 2, 1, CombatWorkQuota.isolated(100, false))
                && !continued.acceptsNative(changedTail, 2, 2, CombatWorkQuota.isolated(100, false)),
            "Consumed prefix skipped the actual current or finished native tail");
        int[] wrong = direct.clone();
        wrong[0] += 1;
        check(
            !segment.acceptsNative(wrong, 2, 0, CombatWorkQuota.isolated(100, false)),
            "Same tail disguised a native unproved prefix");
        Object token = new Object(), behavior = new Object(), path = new Object();
        var context = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatDoorContext(
            token,
            behavior,
            4,
            node,
            path,
            1,
            100,
            5);
        check(context.matches(token, behavior, 4, node, path, path, 1, 100, 5), "Current exact door context failed");
        check(
            !context.matches(token, behavior, 5, node, path, path, 1, 100, 5)
                && !context.matches(new Object(), behavior, 4, node, path, path, 1, 100, 5)
                && !context.matches(token, behavior, 4, parent, path, path, 1, 100, 5)
                && !context.matches(token, behavior, 4, node, new Object(), path, 1, 100, 5),
            "Late open request borrowed a successor lease/node/raw-path");
    }

    private static void reobsServicesSettleOnlyRealCaptureOnce() {
        var receipt = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatFullServiceReceipt();
        check(!receipt.settle(100), "Ungrant service was settled");
        receipt.granted(100);
        check(!receipt.settle(100), "Granted but uncaptured service was settled");
        check(receipt.beginCapture(100) && !receipt.beginCapture(100), "One FULL attempted duplicate capture");
        receipt.captured(100, true, false);
        check(
            receipt.status(100)
                == com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatFullServiceReceipt.Status.BODY_READY
                && receipt.settle(100)
                && !receipt.settle(100)
                && !receipt.settle(101),
            "Body-only service did not settle exactly its real attempt once");
        Object world = new Object();
        int full = 0;
        try {
            for (int i = 0; i < 32; i++) {
                UUID id = new UUID(818, i);
                CombatWorldBudget.heartbeat(world, id, 100, true);
                CombatWorldBudget.prepared(world, id, 100, true, 100);
            }
            for (int i = 0; i < 32; i++)
                if (CombatWorldBudget.heartbeat(world, new UUID(818, i), 100, true).full) full++;
            check(full == 2, "Body eligibility enlarged world FULL cap");
        } finally {
            CombatWorldBudget.unload(world);
        }
    }

    private static void reobsVisibleConnectorUsesOneGraphAndExactNext() {
        var open = new TerrainFixture().snapshot();
        var old = CombatTerrain.find(open, open.targets.get(0), CombatWorkQuota.isolated(100, true)).approach;
        check(old != null, "Connector old task route witness missing");
        var fixture = new TerrainFixture();
        fixture.box(1, 0, 0, 1, Geometry.SOLID);
        var changed = fixture.snapshot();
        var q = CombatWorkQuota.isolated(100, true);
        Vec exact = old.destination;
        check(
            !CombatTerrain.safeStep(changed, changed.self.position, exact, q),
            "Connector witness direct edge was not blocked");
        var choices = CombatTerrain.find(changed, changed.targets.get(0), q);
        int visited = choices.visited;
        var connector = CombatTerrain.connector(changed, choices, exact, q);
        check(
            connector != null && !connector.conditionalDoor
                && connector.destination.distance(exact) < .0001
                && choices.visited == visited
                && visited <= 49,
            "Same bounded graph failed exact ordinary parent-next reconnect");
        check(
            CombatTerrain.connector(changed, choices, new Vec(30.5, 0, .5), q) == null,
            "Connector searched outside original graph");
        System.out.println(
            "REOBS connector nodes=" + connector.nodes
                .size() + " exact=" + connector.destination.x + "," + connector.destination.z + " visited=" + visited);
        var prepared = r1Prepared(true);
        long parentId = prepared.plan.stageId, first = prepared.plan.firstNativeAcceptanceAt;
        long parentIndex = prepared.plan.movementNode.index;
        Vec parentNext = prepared.plan.movementNode.foot;
        var wall = new TerrainFixture();
        wall.box(1, 0, 1, 1, Geometry.SOLID);
        wall.box(1, 0, 2, 1, Geometry.SOLID);
        wall.box(1, 0, 3, 1, Geometry.SOLID);
        var displaced = progressFrame(prepared.base, new Vec(2.5, 0, 3.5), ++prepared.tick, 40, null, true);
        var wallGeometry = progressFrame(
            wall.snapshot(),
            displaced.self.position,
            prepared.tick,
            40,
            null,
            true).geometry;
        displaced = new CombatSnapshot(
            displaced.tick,
            displaced.entityEpoch,
            displaced.observationEpoch,
            displaced.equipmentEpoch,
            displaced.selfId,
            displaced.self,
            displaced.targets,
            displaced.projectiles,
            displaced.friends,
            displaced.weapons,
            wallGeometry,
            displaced.selectedTarget,
            true,
            true,
            displaced.tick);
        check(
            !CombatTerrain.safeStep(
                displaced,
                displaced.self.position,
                parentNext,
                CombatWorkQuota.isolated(prepared.tick, true)),
            "Actual planner connector witness direct parent-next edge was not blocked");
        var linked = prepared.planner.decide(displaced, CombatWorkQuota.isolated(prepared.tick, true));
        check(
            linked.normalProgressAdmitted && linked.stageId == parentId
                && linked.movementNode != null
                && linked.movementNode.stage != parentId * 2
                && linked.firstNativeAcceptanceAt == first,
            "Visible displaced stage did not install a separately identified same-graph connector: " + linked.macro
                + "/"
                + linked.stageReason);
        int consumed = 0;
        while (linked.movementNode.stage != parentId * 2 && consumed++ < 8) {
            var submitted = linked.movementNode;
            var arrived = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
                submitted,
                com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
                prepared.tick,
                12,
                0,
                submitted.foot,
                true,
                true,
                true);
            displaced = progressFrame(displaced, submitted.foot, ++prepared.tick, 40, arrived, true);
            linked = prepared.planner.decide(displaced, CombatWorkQuota.isolated(prepared.tick, false));
        }
        check(
            linked.stageId == parentId && linked.movementNode.stage == parentId * 2
                && linked.movementNode.index == parentIndex
                && linked.movementNode.foot.distance(parentNext) < .0001,
            "Connector completion skipped or fabricated the original unfinished parent node");
        var parentArrived = new com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact(
            linked.movementNode,
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatMovementFact.Outcome.ARRIVED,
            prepared.tick,
            13,
            0,
            parentNext,
            true,
            true,
            true);
        displaced = progressFrame(displaced, parentNext, ++prepared.tick, 40, parentArrived, true);
        linked = prepared.planner.decide(displaced, CombatWorkQuota.isolated(prepared.tick, true));
        check(
            linked.stageId == parentId && linked.stagePhase == 1
                && linked.movementNode.index == parentIndex + 1
                && linked.firstNativeAcceptanceAt == first,
            "Real parent arrival replayed the defense or lost its original acceptance");
        System.out.println(
            "REOBS connector parent=" + parentId
                + " originalNext="
                + parentIndex
                + " afterParent="
                + linked.movementNode.index
                + " first="
                + first);
    }

    private static final class TerrainFixture {

        final byte[] cells = new byte[9 * 6 * 9];
        final double[] bounds = new double[cells.length * 6];
        Target target = target(new Vec(3.5, 0, .5), 200);

        TerrainFixture() {
            for (int y = -2; y <= 3; y++) for (int z = -4; z <= 4; z++)
                for (int x = -4; x <= 4; x++) box(x, y, z, y + 1, y < 0 ? Geometry.SOLID : 0);
        }

        void box(int x, int y, int z, double top, int flags) {
            int i = ((y + 2) * 9 + z + 4) * 9 + x + 4;
            cells[i] = (byte) ((flags & Geometry.SOLID) != 0 && (flags & Geometry.OPENABLE) == 0
                ? flags | Geometry.RAY_BLOCKING
                : flags);
            double[] value = { x, y, z, x + 1, top, z + 1 };
            System.arraycopy(value, 0, bounds, i * 6, 6);
        }

        CombatSnapshot snapshot() {
            var bow = new MaidWeaponProfile(
                MaidWeaponProfile.Kind.BOW,
                MaidWeaponProfile.Family.VANILLA_BOW,
                "",
                "",
                0,
                1,
                0,
                1,
                100,
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false);
            var self = new CombatSnapshot.Self(
                new Vec(.5, 0, .5),
                new Vec(0, 0, 0),
                null,
                20,
                20,
                .3,
                1.35,
                1.2,
                .2,
                true,
                true,
                false,
                false,
                true,
                true,
                false,
                20,
                0,
                0,
                64,
                false,
                false);
            return new CombatSnapshot(
                100,
                1,
                1,
                1,
                SELF,
                self,
                Arrays.asList(target),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList(new Weapon(-1, "bow", bow)),
                new Geometry(-4, -2, -4, 9, 6, 9, 100, true, cells, bounds),
                TARGET,
                true);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
