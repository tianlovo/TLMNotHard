package com.tianluoqaq.tlmnh.common.development.combat;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan.Macro;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Weapon;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;

/** 试点A/B的纯值决策，不访问实体、物品、世界或威胁/预演实现 */
final class CombatEffectPilotDecisions {

    private long sequence, heldSince = Long.MIN_VALUE;
    private CombatPlan previous;

    CombatPlan decide(CombatSnapshot snapshot, CombatWorkQuota quota, boolean selectWeapon) {
        Target target = snapshot.target(snapshot.selectedTarget);
        Weapon current = snapshot.weapon(-1), chosen = usable(snapshot, current) ? current : null;
        if (target == null || !target.fresh(snapshot.tick)) return waitPlan(snapshot, quota, "pilot_no_fresh_target");
        double best = chosen == null ? Double.NEGATIVE_INFINITY : value(snapshot, target, chosen);
        for (Weapon weapon : snapshot.weapons) {
            if (!quota.spend(Work.FALLBACK)) break;
            if (!usable(snapshot, weapon)) continue;
            if (!selectWeapon) {
                if (chosen == null) chosen = weapon;
                break;
            }
            double score = value(snapshot, target, weapon);
            if (chosen == null || score > best || score == best && weapon.slot < chosen.slot) {
                chosen = weapon;
                best = score;
            }
        }
        if (chosen == null) return waitPlan(snapshot, quota, "pilot_no_usable_weapon");
        if (selectWeapon && usable(snapshot, current)
            && previous != null
            && Objects.equals(previous.targetId, target.id)
            && snapshot.tick >= heldSince
            && snapshot.tick - heldSince < 8
            && best < value(snapshot, target, current) + .06) chosen = current;
        Macro macro = chosen.slot >= 0 ? Macro.PREPARE_WEAPON
            : !target.visible
                || chosen.profile.melee() && snapshot.self.position.distance(target.position) > snapshot.self.reach
                    ? Macro.APPROACH
                    : Macro.HOLD_ATTACK;
        Vec destination = snapshot.self.position;
        if (macro == Macro.APPROACH && !snapshot.self.stationary) {
            double distance = snapshot.self.position.distance(target.position);
            destination = destination.add(
                target.position.subtract(destination)
                    .horizontalUnit()
                    .scale(Math.min(3, Math.max(0, distance - snapshot.self.reach * .8))));
        }
        if (!quota.spend(Work.CANDIDATE)) return waitPlan(snapshot, quota, "pilot_candidate_budget");
        if (previous == null || !chosen.fingerprint.equals(previous.weaponFingerprint)
            || !Objects.equals(previous.targetId, target.id)) heldSince = snapshot.tick;
        previous = new CombatPlan(
            ++sequence,
            snapshot,
            target,
            chosen,
            macro,
            destination,
            target.position,
            selectWeapon ? "pilot_weapon_static" : "pilot_reactive",
            value(snapshot, target, chosen),
            0,
            0,
            false,
            quota);
        return previous;
    }

    CombatPlan waitPlan(CombatSnapshot snapshot, CombatWorkQuota quota, String reason) {
        return new CombatPlan(
            ++sequence,
            snapshot,
            snapshot.target(snapshot.selectedTarget),
            snapshot.weapon(-1),
            Macro.WAIT,
            snapshot.self.position,
            snapshot.self.position,
            reason,
            0,
            0,
            0,
            false,
            quota);
    }

    private static boolean usable(CombatSnapshot snapshot, Weapon weapon) {
        return weapon != null && weapon.profile.supported()
            && (weapon.profile.remainingDurability < 0
                || weapon.profile.remainingDurability >= weapon.profile.durabilityCost)
            && (!weapon.profile.bow() || snapshot.self.ammunition > 0);
    }

    /** 与当前生产attack的静态分量相同，风险与预演没有参与此分数 */
    private static double value(CombatSnapshot snapshot, Target target, Weapon weapon) {
        double damage = weapon.profile.bow() ? 6 + weapon.profile.power * 1.5
            : weapon.profile.estimatedMeleePhysical + weapon.profile.sharpness * 1.25;
        double distance = snapshot.self.position.distance(target.position);
        double delay = weapon.profile.bow() ? Math.max(1, 20 - (weapon.slot == -1 ? snapshot.self.drawTicks : 0))
            : snapshot.self.attackDelay + Math.max(0, distance - snapshot.self.reach) / .2;
        double score = Math.min(1, damage / target.health) / (1 + delay / 20);
        if (target.attackingOwner) score += .22;
        if (!target.visible) score -= .4;
        if (weapon.slot >= 0) score -= .10;
        if (snapshot.self.drawing && !weapon.profile.bow()) score -= snapshot.self.drawTicks / 80.0;
        if (snapshot.self.preferencePending && weapon.profile.bow() == snapshot.self.preferBow) score += .025;
        return score;
    }
}
