package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;

/** 只提交首段意图，实际许可、物品及弹道由actor在世界边界重新检查 */
public final class CombatPlan {

    public enum Macro {
        HOLD_ATTACK,
        PREPARE_WEAPON,
        SHIELD,
        STRAFE_LEFT,
        STRAFE_RIGHT,
        RETREAT,
        APPROACH,
        COVER_EDGE,
        JUMP_STRIKE,
        WAIT
    }

    public enum Result {
        RUNNING,
        FINISHED,
        WAIT_EQUIPMENT,
        WAIT_BUDGET,
        STALE,
        REJECTED,
        HELD
    }

    public final long decisionId, entityEpoch, observationEpoch, equipmentEpoch, observedAt, expiresAt;
    public final UUID targetId;
    public final int targetEntityId, weaponSlot;
    public final String weaponFingerprint, reason;
    public final MaidWeaponProfile.Family family;
    public final Macro macro;
    public final Vec destination, face;
    public final double utility, expectedDamage, expectedRisk;
    public final boolean simulated;
    public final CombatWorkQuota quota;

    public CombatPlan(long decisionId, CombatSnapshot snapshot, CombatSnapshot.Target target,
        CombatSnapshot.Weapon weapon, Macro macro, Vec destination, Vec face, String reason, double utility,
        double expectedDamage, double expectedRisk, boolean simulated, CombatWorkQuota quota) {
        this.decisionId = decisionId;
        entityEpoch = snapshot.entityEpoch;
        observationEpoch = snapshot.observationEpoch;
        equipmentEpoch = snapshot.equipmentEpoch;
        observedAt = snapshot.tick;
        expiresAt = snapshot.tick + CombatSnapshot.OBSERVATION_TTL;
        targetId = target == null ? null : target.id;
        targetEntityId = target == null ? -1 : target.entityId;
        weaponSlot = weapon == null ? -1 : weapon.slot;
        weaponFingerprint = weapon == null ? "" : weapon.fingerprint;
        family = weapon == null ? MaidWeaponProfile.Family.UNKNOWN : weapon.profile.family;
        this.macro = macro;
        this.destination = destination;
        this.face = face;
        this.reason = reason;
        this.utility = utility;
        this.expectedDamage = expectedDamage;
        this.expectedRisk = expectedRisk;
        this.simulated = simulated;
        this.quota = quota;
    }
}
