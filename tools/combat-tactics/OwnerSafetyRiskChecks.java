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
            Arrays.asList("all", "lane", "light", "full", "pressure", "healing", "lane-pressure")
                .contains(selected),
            "Unknown check selector");
        if (selected.equals("all") || selected.equals("lane")) laneGeometryAndUnknown();
        if (selected.equals("all") || selected.equals("light")) projectileOwnerScoring(false);
        if (selected.equals("all") || selected.equals("full")) projectileOwnerScoring(true);
        if (selected.equals("all") || selected.equals("pressure")) ownerRangedPressure();
        if (selected.equals("all") || selected.equals("healing")) healingAxePressure();
        if (selected.equals("all") || selected.equals("lane-pressure")) ownerFireLanePressure();
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
