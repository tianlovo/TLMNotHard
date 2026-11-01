package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidAttackStrafingTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidRangedWalkToTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidShootTargetTask;

/** 原TaskBowAttack优先级5任务；WORK与RIDE_WORK各自拥有独立射击Behavior */
public final class MaidModernRanged {

    private final MaidAttackStrafingTask strafe = new MaidAttackStrafingTask();
    private final MaidShootTargetTask shoot = new MaidShootTargetTask();
    private boolean strafeRunning, shootRunning;
    private long strafeDeadline, shootDeadline;
    private final boolean riding;

    public MaidModernRanged(boolean riding) {
        this.riding = riding;
    }

    public void startTasks(MaidRangedActor maid, boolean visibleMemoryPresent) {
        if (maid.attackTarget() == null && maid.hasBowAndArrow()) {
            var selected = maid.findFirstValidRangedTarget();
            if (selected != null && maid.canAttack(selected) && maid.setAttackTarget(selected))
                maid.eraseCantReachSince();
            if (!maid.isCurrent()) return;
        }
        var target = maid.attackTarget();
        if (target != null) {
            Long since = maid.getCantReachSince();
            if (!maid.canAttack(target) || since != null && maid.worldGameTime() - since > 200
                || !maid.targetAlive(target)
                || !maid.targetInSameWorld(target)
                || !maid.hasBowAndArrow()
                || maid.rangedTargetTooFar(target)) maid.eraseAttackTarget();
        }
        if (!riding) {
            MaidRangedWalkToTarget.trigger(maid, .6F);
            // WALK_TARGET准入必须读取上面OneShot写完后的实际记忆
            if (!strafeRunning && !maid.hasRangedWalkTarget()
                && visibleMemoryPresent
                && strafe.checkExtraStartConditions(maid)) {
                strafeRunning = true;
                strafeDeadline = maid.worldGameTime() + 1200 + maid.nextRangedWorldInt(1);
                strafe.start(maid);
            }
        }
        if (!shootRunning && visibleMemoryPresent && shoot.checkExtraStartConditions(maid)) {
            shootRunning = true;
            shootDeadline = maid.worldGameTime() + 1200 + maid.nextRangedWorldInt(1);
            shoot.start(maid);
        }
    }

    public void runTasks(MaidRangedActor maid) {
        if (strafeRunning) {
            if (maid.worldGameTime() > strafeDeadline || !strafe.canStillUse(maid)) {
                strafeRunning = false;
                strafe.stop(maid);
            } else strafe.tick(maid);
        }
        if (!maid.isCurrent()) return;
        if (shootRunning) {
            if (maid.worldGameTime() > shootDeadline || !shoot.canStillUse(maid)) {
                shootRunning = false;
                shoot.stop(maid);
            } else shoot.tick(maid);
        }
    }

    public void stop(MaidRangedActor maid) {
        if (strafeRunning) strafe.stop(maid);
        if (shootRunning) shoot.stop(maid);
        reset();
    }

    public void reset() {
        strafeRunning = shootRunning = false;
        shoot.reset();
    }
}
