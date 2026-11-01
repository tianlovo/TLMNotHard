/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidAttackStrafingTask.java, MIT
 * Adaptation: existing modern move-control strafe operation and guarded native distances/target references
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidRangedActor;

public final class MaidAttackStrafingTask {

    private boolean strafingClockwise, strafingBackwards;
    private int strafingTime = -1;

    public boolean checkExtraStartConditions(MaidRangedActor maid) {
        var target = maid.attackTarget();
        return maid.mainHandProjectileWeapon() && target != null && maid.targetAlive(target);
    }

    public boolean canStillUse(MaidRangedActor maid) {
        return checkExtraStartConditions(maid);
    }

    public void start(MaidRangedActor maid) {
        maid.setSwingingArms(true);
    }

    public void tick(MaidRangedActor maid) {
        if (!maid.mainHandProjectileWeapon()) return;
        var target = maid.attackTarget();
        if (target == null) return;
        int weaponRange = maid.defaultProjectileRange();
        double distance = maid.distanceToTarget(target);
        if (distance < maid.rangedSearchRadius()) strafingTime++;
        else strafingTime = -1;
        if (strafingTime >= 20) {
            if (maid.nextRangedFloat() < .3) strafingClockwise = !strafingClockwise;
            if (maid.nextRangedFloat() < .3) strafingBackwards = !strafingBackwards;
            strafingTime = 0;
        }
        if (strafingTime > -1) {
            if (distance > weaponRange * .5) strafingBackwards = false;
            else if (distance < weaponRange * .2) strafingBackwards = true;
            if (maid.ownerOutsideStrafeRestriction()) maid.stopRangedInPlace();
            else maid.strafe(strafingBackwards ? -.5F : .5F, strafingClockwise ? .5F : -.5F);
            maid.alignRangedBodyToHead();
        }
        maid.lookAtAttackTarget(target);
    }

    public void stop(MaidRangedActor maid) {
        maid.setSwingingArms(false);
        maid.strafe(0, 0);
    }
}
