/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidStartSnowballAttacking.java, MIT
 * Adaptation: guarded ATTACK_TARGET capability; default Behavior start/stop is scheduled by modern component
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSnowballActor;

public final class MaidStartSnowballAttacking {

    public boolean checkExtraStartConditions(MaidSnowballActor maid) {
        if (!maid.canSnowballFight()) return false;
        var target = maid.findFirstValidSnowballTarget();
        return target != null && maid.targetAlive(target);
    }

    public void start(MaidSnowballActor maid) {
        var target = maid.findFirstValidSnowballTarget();
        if (target != null) {
            maid.rememberSnowballTarget(target);
            maid.eraseCantReachSince();
        }
    }
}
