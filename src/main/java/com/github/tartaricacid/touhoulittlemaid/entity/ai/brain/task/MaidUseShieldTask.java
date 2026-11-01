/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidUseShieldTask.java, MIT
 * Adaptation: existing item/lease service owns shield use; component separates start from running/stop phase
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor;

public final class MaidUseShieldTask {

    public boolean checkExtraStartConditions(MaidCombatActor maid) {
        if (!maid.canUseShield()) return false;
        var target = maid.attackTarget();
        return target != null && maid.targetAlive(target) && maid.distanceToTarget(target) < 8;
    }

    public boolean canStillUse(MaidCombatActor maid) {
        return checkExtraStartConditions(maid);
    }

    public void start(MaidCombatActor maid) {
        maid.startUsingShield(maid.attackTarget());
    }

    public void stop(MaidCombatActor maid) {
        maid.stopUsingShield();
    }
}
