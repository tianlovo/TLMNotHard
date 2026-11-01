/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidMeleeAttack.java, MIT
 * Adaptation: current-frame target capability and native hurt service; original expirable Brain cooldown
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor;

public final class MaidMeleeAttack {

    private MaidMeleeAttack() {}

    public static boolean trigger(MaidCombatActor maid, int cooldownBetweenAttacks) {
        var target = maid.attackTarget();
        if (target == null || maid.attackCoolingDown() || !maid.hasVisibleLivingMemory()) return false;
        if (!maid.holdingUsableProjectileWeapon() && maid.withinMeleeRange(target) && maid.targetVisible(target)) {
            maid.lookAtAttackTarget(target);
            maid.swingMainHand();
            maid.hurtTarget(target);
            if (!maid.isCurrent()) return true;
            double speed = maid.attackSpeed();
            maid.setAttackCooldown(speed > 0 ? (long) (cooldownBetweenAttacks / speed) : cooldownBetweenAttacks);
            return true;
        }
        return false;
    }
}
