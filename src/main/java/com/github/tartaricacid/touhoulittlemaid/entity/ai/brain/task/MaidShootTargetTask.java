/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidShootTargetTask.java, MIT
 * Adaptation: guarded current-frame target, shared native item-use lease and target-platform arrow release
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidRangedActor;

public final class MaidShootTargetTask {

    private int attackTime = -1, seeTime;

    public MaidShootTargetTask() {}

    @Deprecated
    public MaidShootTargetTask(int attackCooldown) {
        this();
    }

    public boolean checkExtraStartConditions(MaidRangedActor maid) {
        var target = maid.attackTarget();
        return target != null && maid.holdingProjectileWeapon() && maid.canSeeRanged(target);
    }

    public boolean canStillUse(MaidRangedActor maid) {
        return maid.attackTarget() != null && checkExtraStartConditions(maid);
    }

    public void start(MaidRangedActor maid) {
        maid.setSwingingArms(true);
    }

    public void tick(MaidRangedActor maid) {
        var target = maid.attackTarget();
        if (target == null) return;
        maid.lookDirectlyAtRangedTarget(target);
        boolean visible = maid.canSeeRanged(target);
        if (visible != (seeTime > 0)) seeTime = 0;
        if (visible) seeTime++;
        else seeTime--;
        boolean using = maid.rangedUsingItem();
        if (!maid.isCurrent()) return;
        if (using) {
            if (!visible && seeTime < -60) maid.stopRangedItemUse();
            else if (visible) {
                int used = maid.rangedUsedTicks();
                int level = maid.quickChargeLevel();
                if (!maid.isCurrent()) return;
                if (level > 4 || used >= 20 - level * 5) {
                    maid.stopRangedItemUse();
                    if (!maid.isCurrent()) return;
                    int powerTime = Math.max(used, 20);
                    float charge = powerTime / 20.0F;
                    charge = (charge * charge + charge * 2.0F) / 3.0F;
                    maid.performRangedAttack(target, Math.min(charge, 1));
                    if (maid.isCurrent()) attackTime = maid.shootCooldown();
                }
            }
        } else if (--attackTime <= 0 && seeTime >= -60) maid.startRangedMainUse();
    }

    public void stop(MaidRangedActor maid) {
        reset();
        maid.stopRangedItemUse();
    }

    public void reset() {
        seeTime = 0;
        attackTime = -1;
    }
}
