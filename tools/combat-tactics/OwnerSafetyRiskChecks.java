import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.DamageSource;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Projectile;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Weapon;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrainTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.ThreatEstimator;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;

/** 纯快照回归，分别覆盖潜在射线、LIGHT/FULL主人风险计分和UNKNOWN边界 */
public final class OwnerSafetyRiskChecks {

    private static final UUID SELF = new UUID(0, 701), ENEMY = new UUID(0, 702);

    public static void main(String[] args) {
        String selected = args.length == 0 ? "all" : args[0];
        check(
            Arrays
                .asList(
                    "all",
                    "lane",
                    "light",
                    "full",
                    "pressure",
                    "healing",
                    "lane-pressure",
                    "projectile-pressure",
                    "service-pressure",
                    "self-safety",
                    "melee-hold")
                .contains(selected),
            "Unknown check selector");
        if (selected.equals("all") || selected.equals("lane")) laneGeometryAndUnknown();
        if (selected.equals("all") || selected.equals("light")) projectileOwnerScoring(false);
        if (selected.equals("all") || selected.equals("full")) projectileOwnerScoring(true);
        if (selected.equals("all") || selected.equals("pressure")) ownerRangedPressure();
        if (selected.equals("all") || selected.equals("healing")) healingAxePressure();
        if (selected.equals("all") || selected.equals("lane-pressure")) ownerFireLanePressure();
        if (selected.equals("all") || selected.equals("projectile-pressure")) ownerPressureSeparatesProjectileHits();
        if (selected.equals("all") || selected.equals("service-pressure")) ownerPressureSurvivesServiceGap();
        if (selected.equals("all") || selected.equals("self-safety")) selfProjectileDefenseSuspendsBow();
        if (selected.equals("all") || selected.equals("melee-hold")) reachedMeleeDoesNotRetainApproach();
        System.out.println("OwnerSafetyRiskChecks: " + selected + " passed; no native game");
    }

    private static void ownerRangedPressure() {
        for (boolean full : new boolean[] { false, true }) {
            CombatPlan protectedOwner = new CombatPlanner().decide(
                pressureSnapshot(true, true, false, 20, .2, DamageSource.GENERIC_PRIOR),
                CombatWorkQuota.isolated(100, full));
            check(
                protectedOwner.weaponSlot == 0 && protectedOwner.family == MaidWeaponProfile.Family.VANILLA_SWORD
                    && (protectedOwner.macro == CombatPlan.Macro.APPROACH
                        || protectedOwner.macro == CombatPlan.Macro.PREPARE_WEAPON
                        || protectedOwner.macro == CombatPlan.Macro.HOLD_ATTACK
                        || protectedOwner.macro == CombatPlan.Macro.JUMP_STRIKE),
                "Visible owner attacker did not receive safe melee pressure");
        }
        for (CombatSnapshot boundary : Arrays.asList(
            pressureSnapshot(false, true, false, 20, .2, DamageSource.GENERIC_PRIOR),
            pressureSnapshot(true, false, false, 20, .2, DamageSource.GENERIC_PRIOR),
            pressureSnapshot(true, true, true, 20, .2, DamageSource.GENERIC_PRIOR),
            pressureSnapshot(true, true, false, 20, .1, DamageSource.GENERIC_PRIOR),
            pressureSnapshot(true, true, false, 1, .2, DamageSource.GENERIC_PRIOR),
            pressureSnapshot(true, true, false, 20, .2, DamageSource.UNKNOWN))) {
            CombatPlan plan = new CombatPlanner().decide(boundary, CombatWorkQuota.isolated(100, false));
            check(plan.weaponSlot != 0, "Owner pressure bypassed its observed, reachable or survivable boundary");
        }
    }

    private static void healingAxePressure() {
        CombatSnapshot base = pressureSnapshot(true, true, false, 20, .2, DamageSource.GENERIC_PRIOR);
        var axe = new MaidWeaponProfile(
            MaidWeaponProfile.Kind.MELEE,
            MaidWeaponProfile.Family.HEALING_AXE,
            "",
            "",
            0,
            1,
            6,
            0,
            -1,
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
        for (boolean vulnerable : new boolean[] { false, true }) {
            var t = base.targets.get(0);
            var target = new Target(
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
                t.visible,
                t.swinging,
                t.ranged,
                t.explosive,
                t.attackingOwner,
                t.damageSource,
                vulnerable);
            var snapshot = new CombatSnapshot(
                base.tick,
                1,
                1,
                1,
                SELF,
                base.self,
                Arrays.asList(target),
                base.projectiles,
                base.friends,
                Arrays.asList(base.weapon(-1), new Weapon(0, "healing_axe", axe)),
                base.geometry,
                ENEMY,
                true);
            CombatPlan plan = new CombatPlanner().decide(snapshot, CombatWorkQuota.isolated(100, false));
            check(vulnerable || plan.weaponSlot != 0, "Owner pressure selected a weapon that cannot damage its target");
            check(!vulnerable || plan.weaponSlot == 0, "Healing axe lost pressure against its supported damage target");
        }
    }

    private static void ownerFireLanePressure() {
        for (boolean full : new boolean[] { false, true }) {
            for (boolean complete : new boolean[] { false, true }) {
                CombatSnapshot base = pressureSnapshot(
                    new Vec(-3, 0, 0),
                    false,
                    false,
                    20,
                    .2,
                    DamageSource.GENERIC_PRIOR);
                CombatSnapshot snapshot = new CombatSnapshot(
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
                    base.geometry,
                    base.selectedTarget,
                    complete);
                CombatPlan plan = new CombatPlanner().decide(snapshot, CombatWorkQuota.isolated(100, full));
                check(
                    plan.weaponSlot == 0 && plan.family == MaidWeaponProfile.Family.VANILLA_SWORD,
                    "Visible ranged fire lane did not protect the owner before the first injury");
                check(
                    plan.macro == CombatPlan.Macro.APPROACH || plan.macro == CombatPlan.Macro.PREPARE_WEAPON
                        || plan.macro == CombatPlan.Macro.HOLD_ATTACK
                        || plan.macro == CombatPlan.Macro.JUMP_STRIKE,
                    "Fire-lane protection admitted a defense-only action as pressure");
            }
        }
    }

    private static void ownerPressureSeparatesProjectileHits() {
        CombatSnapshot base = pressureSnapshot(true, true, false, 20, .2, DamageSource.GENERIC_PRIOR);
        Projectile harmless = new Projectile(new UUID(0, 704), new Vec(-3, 3, -3), new Vec(0, 0, 0), 100, 0, 1, .1, 6);
        for (boolean full : new boolean[] { false, true }) {
            CombatSnapshot clear = pressureProjectiles(base, Arrays.asList(harmless));
            CombatPlan pressure = new CombatPlanner().decide(clear, CombatWorkQuota.isolated(100, full));
            check(pressure.weaponSlot == 0, "Non-intersecting fresh arrow disabled safe owner pressure");
            Projectile lethal = new Projectile(
                new UUID(0, 705),
                new Vec(0, .7, 0),
                new Vec(0, 0, 0),
                100,
                0,
                1,
                .1,
                100);
            CombatPlan danger = new CombatPlanner()
                .decide(pressureProjectiles(base, Arrays.asList(lethal)), CombatWorkQuota.isolated(100, full));
            check(danger.weaponSlot != 0, "Owner pressure bypassed an actual lethal arrow body hit");
            Projectile unknown = new Projectile(
                new UUID(0, 706),
                new Vec(-3, 3, -3),
                new Vec(0, 0, 0),
                100,
                0,
                1,
                .1,
                6,
                DamageSource.UNKNOWN);
            CombatPlan refused = new CombatPlanner()
                .decide(pressureProjectiles(base, Arrays.asList(unknown)), CombatWorkQuota.isolated(100, full));
            check(refused.weaponSlot != 0, "Unknown arrow acquired safe owner pressure admission");
        }
    }

    /** 普通接近达到当前真实近战距离后重算持位，不能继续消费旧推进 */
    private static void reachedMeleeDoesNotRetainApproach() {
        boolean previous = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        try {
            for (double distance : new double[] { 2.5, 2.8 }) {
                var planner = new CombatPlanner();
                CombatPlan approaching = planner.decide(meleeHoldFrame(100, 3.5, 100),
                    CombatWorkQuota.isolated(100, false));
                check(approaching.macro == CombatPlan.Macro.APPROACH && approaching.stageId == 0,
                    "Melee-hold fixture failed to establish a real ordinary approach");
                CombatPlan reached = planner.decide(meleeHoldFrame(101, distance, 101),
                    CombatWorkQuota.isolated(101, false));
                check(reached.trace.candidates.stream().noneMatch(candidate ->
                    candidate.macro == CombatPlan.Macro.APPROACH && candidate.strategy == CombatPlan.Strategy.REACTION),
                    "Old melee approach continued after current target entered actual reach");
                check(reached.macro == CombatPlan.Macro.HOLD_ATTACK && reached.stageId == 0,
                    "Reached ordinary melee failed to hold its current native attack position");
            }
            for (CombatSnapshot boundary : Arrays.asList(meleeHoldFrame(101, 3.2, 101),
                meleeHoldFrame(101, 2.5, 100))) {
                var planner = new CombatPlanner();
                planner.decide(meleeHoldFrame(100, 3.5, 100), CombatWorkQuota.isolated(100, false));
                CombatPlan retained = planner.decide(boundary, CombatWorkQuota.isolated(101, false));
                check(retained.trace.candidates.stream().anyMatch(candidate ->
                    candidate.macro == CombatPlan.Macro.APPROACH && candidate.strategy == CombatPlan.Strategy.REACTION),
                    "Outside-reach or non-current target fabricated melee arrival");
            }
        } finally {
            CombatPlanner.setTraceEnabled(previous);
        }
    }

    private static CombatSnapshot meleeHoldFrame(long tick, double distance, long targetObservedAt) {
        CombatSnapshot base = pressureSnapshot(true, true, false, 20, .2, DamageSource.GENERIC_PRIOR);
        var target = new Target(ENEMY, 702, new Vec(distance, 0, 0), new Vec(0, 0, 0), targetObservedAt,
            Long.MIN_VALUE, 40, 4, .3, 1.8, true, false, true, false, true, DamageSource.GENERIC_PRIOR);
        return new CombatSnapshot(tick, 1, 1, 1, SELF, base.self, Arrays.asList(target),
            Collections.emptyList(), base.friends, Arrays.asList(new Weapon(-1, "sword", base.weapon(0).profile)),
            base.geometry, ENEMY, false);
    }

    /** 原即时扫掠确认自身来箭后，移动防御不再虚计射击，且不占用自有MAIN弓 */
    private static void selfProjectileDefenseSuspendsBow() {
        boolean previous = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        try {
            for (boolean full : new boolean[] { true, false }) {
                Projectile incoming = new Projectile(new UUID(0, 720), new Vec(2.5, .7, 0),
                    new Vec(-1.5, 0, 0), 100, 0, .99, .3, 6);
                var quota = CombatWorkQuota.isolated(100, full);
                CombatPlan defense = new CombatPlanner().decide(selfSafetyFrame(Arrays.asList(incoming), null), quota);
                check(defense.trace != null && defense.trace.full == full, "Wrong self-safety scoring path");
                int evaluatedMoves = 0, evaluatedLaterals = 0, taskReturns = 0;
                for (var candidate : defense.trace.candidates) {
                    if (candidate.weaponSlot != -1 || candidate.family != MaidWeaponProfile.Family.VANILLA_BOW
                        || !defensiveMovement(candidate.macro) || !candidate.evaluated && full
                        || candidate.utility <= -100) continue;
                    if (commits(candidate.strategy)) {
                        if (full && candidate.strategy == CombatPlan.Strategy.DEFENSIVE_RETURN && candidate.damage > 0)
                            taskReturns++;
                        continue;
                    }
                    evaluatedMoves++;
                    if (candidate.macro == CombatPlan.Macro.STRAFE_LEFT
                        || candidate.macro == CombatPlan.Macro.STRAFE_RIGHT) evaluatedLaterals++;
                    // 先核旧API的行为，再读取新增只读标志。旧实际类须在此命中行为断言
                    check(candidate.damage == 0,
                        "Self-arrow defense still credited shots while its owned bow must yield");
                }
                check(evaluatedMoves > 0 && evaluatedLaterals > 0,
                    "Self-safety fixture had no legal evaluated lateral bow movement");
                for (var candidate : defense.trace.candidates) {
                    if (candidate.weaponSlot != -1 || candidate.family != MaidWeaponProfile.Family.VANILLA_BOW
                        || !defensiveMovement(candidate.macro) || full && !candidate.evaluated
                        || candidate.utility <= -100) continue;
                    if (commits(candidate.strategy))
                        check(!rangedInterrupted(candidate), "Task defense borrowed reaction interruption authority");
                    else check(rangedInterrupted(candidate), "Self-arrow defense lost its shared ranged-use decision");
                }
                if (full) check(taskReturns > 0, "Legitimate defensive-return bow payoff disappeared");
                check(quota.used(CombatWorkQuota.Work.COLLISION) <= (full ? 40000 : 2400),
                    "Self-safety expanded its original collision quota");
                check(quota.used(CombatWorkQuota.Work.ADVANCE) <= CombatBudgetTuning.MAX_ADVANCES,
                    "Self-safety expanded its original forecast quota");
            }
            // 已知但不扫过自身的箭、仅主人遇险、过期箭均不得凭存在本身暂停拉弓
            for (List<Projectile> arrows : Arrays.asList(Collections.<Projectile>emptyList(),
                Arrays.asList(new Projectile(new UUID(0, 721), new Vec(2.5, .7, 3),
                    new Vec(-1.5, 0, 0), 100, 0, .99, .3, 6)),
                Arrays.asList(new Projectile(new UUID(0, 722), new Vec(2.5, .7, 0),
                    new Vec(-1.5, 0, 0), 90, 0, .99, .3, 6)))) {
                var plan = new CombatPlanner().decide(selfSafetyFrame(arrows, new Vec(0, 0, 3)),
                    CombatWorkQuota.isolated(100, true));
                boolean bowMoveStillScores = false;
                for (var candidate : plan.trace.candidates) {
                    check(!rangedInterrupted(candidate), "Non-self or stale arrow suppressed owned ranged use");
                    bowMoveStillScores |= candidate.family == MaidWeaponProfile.Family.VANILLA_BOW
                        && defensiveMovement(candidate.macro) && candidate.damage > 0;
                }
                check(bowMoveStillScores, "Untouched lateral bow benefit disappeared without a self-arrow threat");
            }
        } finally {
            CombatPlanner.setTraceEnabled(previous);
        }
    }

    private static boolean rangedInterrupted(Object value) {
        try {
            return value.getClass().getField("interruptRangedUse").getBoolean(value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Ranged-use decision metadata unavailable after behavior check", failure);
        }
    }

    private static boolean commits(CombatPlan.Strategy strategy) {
        return strategy == CombatPlan.Strategy.TASK || strategy == CombatPlan.Strategy.DEFENSIVE_RETURN;
    }

    private static boolean defensiveMovement(CombatPlan.Macro macro) {
        return macro == CombatPlan.Macro.RETREAT || macro == CombatPlan.Macro.STRAFE_LEFT
            || macro == CombatPlan.Macro.STRAFE_RIGHT || macro == CombatPlan.Macro.COVER_EDGE;
    }

    private static CombatSnapshot selfSafetyFrame(List<Projectile> arrows, Vec ownerPosition) {
        CombatSnapshot base = pressureSnapshot(ownerPosition, false, false, 20, .2, DamageSource.GENERIC_PRIOR);
        var target = new Target(ENEMY, 702, new Vec(3.5, 0, 0), new Vec(0, 0, 0), 100, Long.MIN_VALUE,
            40, 4, .3, 1.8, true, false, true, false, false, DamageSource.GENERIC_PRIOR);
        return new CombatSnapshot(100, 1, 1, 1, SELF, base.self, Arrays.asList(target), arrows,
            base.friends, Arrays.asList(base.weapon(-1)), base.geometry, ENEMY, true);
    }

    private static CombatSnapshot pressureProjectiles(CombatSnapshot base, List<Projectile> projectiles) {
        return new CombatSnapshot(
            base.tick,
            base.entityEpoch,
            base.observationEpoch,
            base.equipmentEpoch,
            base.selfId,
            base.self,
            base.targets,
            projectiles,
            base.friends,
            base.weapons,
            base.geometry,
            base.selectedTarget,
            base.complete);
    }

    private static void ownerPressureSurvivesServiceGap() {
        var base = pressureSnapshot(true, true, false, 20, .2, DamageSource.GENERIC_PRIOR);
        byte[] cells = new byte[9 * 6 * 9];
        for (int y = 0; y < 2; y++) for (int z = 0; z < 9; z++)
            for (int x = 0; x < 9; x++) cells[(y * 9 + z) * 9 + x] = CombatTerrainTuning.SOLID;
        var geometry = new CombatSnapshot.Geometry(-4, -2, -4, 9, 6, 9, 100, true, cells);
        var sword = new Weapon(-1, "service-sword", base.weapon(0).profile);
        var prepared = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            base.self,
            base.targets,
            Collections.emptyList(),
            base.friends,
            Arrays.asList(sword),
            geometry,
            ENEMY,
            true);
        var planner = new CombatPlanner();
        CombatPlan task = planner.decide(prepared, CombatWorkQuota.isolated(100, true));
        check(
            task.strategy == CombatPlan.Strategy.TASK && task.macro == CombatPlan.Macro.APPROACH
                && task.stageId > 0
                && task.normalProgressAdmitted,
            "Service-pressure fixture did not admit a real task");
        var gap = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            base.self,
            base.targets,
            Collections.emptyList(),
            base.friends,
            Arrays.asList(sword),
            geometry,
            ENEMY,
            false);
        CombatPlan reaction = planner.decide(gap, CombatWorkQuota.isolated(100, false));
        check(
            reaction.macro == CombatPlan.Macro.APPROACH && reaction.strategy == CombatPlan.Strategy.REACTION,
            "Owner pressure disappeared while its ordinary task waited for complete coverage");
        check(
            !reaction.simulated && !reaction.normalProgressAdmitted
                && reaction.stageId == task.stageId
                && reaction.stagePhase == task.stagePhase
                && reaction.route == null
                && reaction.movementNode == null,
            "Ordinary service reaction advanced or borrowed the task authority");
        byte[] unknownCells = new byte[cells.length];
        Arrays.fill(unknownCells, CombatTerrainTuning.UNKNOWN);
        var unknownGeometry = new CombatSnapshot.Geometry(-4, -2, -4, 9, 6, 9, 100, false, unknownCells);
        var unknownGap = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            base.self,
            base.targets,
            Collections.emptyList(),
            base.friends,
            Arrays.asList(sword),
            unknownGeometry,
            ENEMY,
            false);
        CombatPlan unknown = planner.decide(unknownGap, CombatWorkQuota.isolated(100, false));
        check(
            unknown.macro != CombatPlan.Macro.APPROACH && !unknown.normalProgressAdmitted,
            "Service reaction treated unknown body/floor as a proven node");
        var staleGeometry = new CombatSnapshot.Geometry(-4, -2, -4, 9, 6, 9, 0, true, cells);
        var staleGap = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            base.self,
            base.targets,
            Collections.emptyList(),
            base.friends,
            Arrays.asList(sword),
            staleGeometry,
            ENEMY,
            false);
        CombatPlan stale = planner.decide(staleGap, CombatWorkQuota.isolated(100, false));
        check(
            stale.macro != CombatPlan.Macro.APPROACH && !stale.normalProgressAdmitted,
            "Service reaction reused stale body/floor geometry");
        var profile = sword.profile;
        var exhausted = new MaidWeaponProfile(
            profile.kind,
            profile.family,
            profile.reason,
            profile.dependency,
            profile.physicalBonus,
            profile.criticalMultiplier,
            profile.estimatedMeleePhysical,
            profile.durabilityCost,
            0,
            profile.hurtResistanceMultiplier,
            profile.sharpness,
            profile.smite,
            profile.baneOfArthropods,
            profile.knockback,
            profile.fireAspect,
            profile.power,
            profile.punch,
            profile.infinity,
            profile.flame);
        // 背包中仍有阶段同指纹的可用能力，使阶段身份保留；当前手中档案已不可支付耐久
        var unavailableGap = new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            base.self,
            base.targets,
            Collections.emptyList(),
            base.friends,
            Arrays.asList(new Weapon(-1, sword.fingerprint, exhausted), new Weapon(0, sword.fingerprint, profile)),
            geometry,
            ENEMY,
            false);
        CombatPlan unavailable = planner.decide(unavailableGap, CombatWorkQuota.isolated(100, false));
        check(
            unavailable.stageId == task.stageId && unavailable.macro != CombatPlan.Macro.APPROACH
                && !unavailable.normalProgressAdmitted,
            "Service reaction admitted an unusable current weapon under the same stage identity");
    }

    private static CombatSnapshot pressureSnapshot(boolean ownerPresent, boolean attackingOwner, boolean stationary,
        float health, double speed, DamageSource source) {
        return pressureSnapshot(
            ownerPresent ? new Vec(-3, 0, attackingOwner ? 0 : 3) : null,
            attackingOwner,
            stationary,
            health,
            speed,
            source);
    }

    private static CombatSnapshot pressureSnapshot(Vec ownerPosition, boolean attackingOwner, boolean stationary,
        float health, double speed, DamageSource source) {
        var self = new CombatSnapshot.Self(
            new Vec(0, 0, 0),
            new Vec(0, 0, 0),
            ownerPosition,
            health,
            20,
            .3,
            1.35,
            2.8,
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
            32,
            false,
            false);
        var target = new Target(
            ENEMY,
            702,
            new Vec(10, 0, 0),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            40,
            4,
            .3,
            1.8,
            true,
            false,
            true,
            false,
            attackingOwner,
            source);
        var sword = new MaidWeaponProfile(
            MaidWeaponProfile.Kind.MELEE,
            MaidWeaponProfile.Family.VANILLA_SWORD,
            "",
            "",
            0,
            1.5F,
            6,
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
        byte[] cells = new byte[9 * 6 * 9];
        for (int y = 0; y < 2; y++) for (int z = 0; z < 9; z++)
            for (int x = 0; x < 9; x++) cells[(y * 9 + z) * 9 + x] = CombatTerrainTuning.SOLID;
        var geometry = new CombatSnapshot.Geometry(-4, -2, -4, 9, 6, 9, 100, true, cells);
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
            Arrays.asList(new Weapon(-1, "bow", bow), new Weapon(0, "sword", sword)),
            geometry,
            ENEMY,
            true);
    }

    private static void laneGeometryAndUnknown() {
        Target foe = ranged(false, DamageSource.GENERIC_PRIOR);
        ThreatEstimator.Risk inLane = risk(snapshot(new Vec(-2.5, 0, 0), Arrays.asList(foe), Collections.emptyList()));
        check(inLane.ownerReceived > 0 && inLane.ownerSingle > 0, "Ranged fire lane omitted owner exposure");
        check(
            risk(snapshot(new Vec(-2.5, 0, 3), Arrays.asList(foe), Collections.emptyList())).ownerReceived == 0,
            "Off-lane owner acquired invented directional exposure");
        check(
            risk(snapshot(new Vec(4, 0, 0), Arrays.asList(foe), Collections.emptyList())).ownerReceived == 0,
            "Owner behind the shooter acquired a forward fire lane");
        check(
            risk(snapshot(null, Arrays.asList(foe), Collections.emptyList())).ownerReceived == 0,
            "Missing owner acquired an exposure score");
        check(
            risk(
                snapshot(
                    new Vec(-2.5, 0, 3),
                    Arrays.asList(ranged(true, DamageSource.GENERIC_PRIOR)),
                    Collections.emptyList())).ownerReceived
                > 0,
            "Observed owner target lost direct ranged exposure");
        ThreatEstimator.Risk unknown = risk(
            snapshot(new Vec(-2.5, 0, 0), Arrays.asList(ranged(false, DamageSource.UNKNOWN)), Collections.emptyList()));
        check(!unknown.known, "UNKNOWN damage source became a known total risk");
    }

    private static void projectileOwnerScoring(boolean full) {
        Vec owner = new Vec(2.5, 0, 0);
        Projectile arrow = new Projectile(
            new UUID(0, 703),
            owner.add(new Vec(0, .7, 0)),
            new Vec(.05, 0, 0),
            100,
            0,
            1,
            .3,
            6);
        CombatSnapshot present = snapshot(owner, Collections.emptyList(), Arrays.asList(arrow));
        CombatSnapshot absent = snapshot(null, Collections.emptyList(), Arrays.asList(arrow));
        boolean previous = CombatPlanner.traceEnabled();
        CombatPlanner.setTraceEnabled(true);
        try {
            CombatPlan scored = new CombatPlanner().decide(present, CombatWorkQuota.isolated(100, full));
            CombatPlan baseline = new CombatPlanner().decide(absent, CombatWorkQuota.isolated(100, full));
            CombatPlanner.CandidateTrace withOwner = shield(scored), withoutOwner = shield(baseline);
            check(scored.trace.full == full && baseline.trace.full == full, "Wrong scoring path exercised");
            check(withOwner.ownerRisk == 6, "Known owner arrow was omitted or charged repeatedly");
            check(withOwner.risk == withoutOwner.risk, "Owner-only arrow changed the maid body risk");
            check(withOwner.utility < withoutOwner.utility, "Owner-only arrow did not reduce action utility");
        } finally {
            CombatPlanner.setTraceEnabled(previous);
        }
    }

    private static CombatPlanner.CandidateTrace shield(CombatPlan plan) {
        check(plan.trace != null, "Candidate trace was not captured");
        for (var candidate : plan.trace.candidates) if (candidate.macro == CombatPlan.Macro.SHIELD) return candidate;
        throw new AssertionError("Isolated shield candidate missing");
    }

    private static ThreatEstimator.Risk risk(CombatSnapshot snapshot) {
        var result = new ThreatEstimator.Risk();
        double self = ThreatEstimator.damage(
            snapshot,
            snapshot.self.position,
            snapshot.self.position,
            CombatBudgetTuning.STEP,
            0,
            false,
            CombatWorkQuota.isolated(100, true),
            null,
            result);
        check(Double.isFinite(self), "Geometry fixture exhausted its bounded calculation");
        return result;
    }

    private static Target ranged(boolean owner, DamageSource source) {
        return new Target(
            ENEMY,
            702,
            new Vec(3.5, 0, 0),
            new Vec(0, 0, 0),
            100,
            Long.MIN_VALUE,
            40,
            4,
            .3,
            1.8,
            true,
            false,
            true,
            false,
            owner,
            source);
    }

    private static CombatSnapshot snapshot(Vec owner, List<Target> targets, List<Projectile> arrows) {
        var self = new CombatSnapshot.Self(
            new Vec(0, 0, 0),
            new Vec(0, 0, 0),
            owner,
            100,
            20,
            .3,
            1.35,
            2.8,
            0,
            true,
            false,
            true,
            true,
            true,
            true,
            false,
            20,
            0,
            0,
            0,
            false,
            false);
        byte[] cells = new byte[9 * 6 * 9];
        for (int y = 0; y < 2; y++) for (int z = 0; z < 9; z++)
            for (int x = 0; x < 9; x++) cells[(y * 9 + z) * 9 + x] = CombatTerrainTuning.SOLID;
        var geometry = new CombatSnapshot.Geometry(-4, -2, -4, 9, 6, 9, 100, true, cells);
        return new CombatSnapshot(
            100,
            1,
            1,
            1,
            SELF,
            self,
            targets,
            arrows,
            Collections.emptyList(),
            Collections.emptyList(),
            geometry,
            targets.isEmpty() ? null : ENEMY,
            true);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
