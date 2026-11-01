/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidRangedWalkToTarget.java, MIT
 * Adaptation: original OneShot memory/distance contract through guarded native facts
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidRangedActor;

public final class MaidRangedWalkToTarget {

    private MaidRangedWalkToTarget() {}

    public static boolean trigger(MaidRangedActor maid, float speed) {
        var target = maid.attackTarget();
        if (target == null) return false;
        if (maid.canSeeRanged(target) && shouldEraseWalkTarget(maid, target)) maid.eraseWalkTarget();
        else {
            maid.lookAtAttackTarget(target);
            maid.walkToAttackTarget(target, speed);
        }
        return true;
    }

    private static boolean shouldEraseWalkTarget(MaidRangedActor maid, MaidRangedActor.Target target) {
        double radius = 8;
        if (maid.isHomeModeEnable()) radius = maid.targetDistanceToRestrictionCenter(target);
        else {
            var ownerDistance = maid.targetDistanceToOwner(target);
            if (ownerDistance != null) radius = ownerDistance;
        }
        return radius <= 4 || radius >= maid.getRestrictRadius() - 2;
    }
}
