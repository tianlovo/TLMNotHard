package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidMeleeAttack;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUseShieldTask;

/** TaskAttack的原优先级5序列，遵循1.20.1 Start/Stop/SetWalk OneShot的记忆合同 */
public final class MaidModernCombat {

    private final MaidUseShieldTask shield = new MaidUseShieldTask();

    /** OneShot没有Behavior的duration抽样；举盾由物品服务在实际start时抽样 */
    public void startTasks(MaidCombatActor maid, boolean riding) {
        if (maid.attackTarget() == null && maid.hasAssaultWeapon()) {
            var selected = maid.findFirstValidAttackTarget();
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
                || !maid.hasAssaultWeapon()
                || maid.targetTooFar(target)) maid.eraseAttackTarget();
        }
        target = maid.attackTarget();
        if (!riding && target != null) {
            if (maid.hasVisibleLivingMemory() && maid.targetVisible(target) && maid.withinMeleeRange(target))
                maid.eraseWalkTarget();
            else {
                maid.lookAtAttackTarget(target);
                maid.walkToAttackTarget(target, .6F);
            }
        }
        MaidMeleeAttack.trigger(maid, 20);
        if (!maid.isCurrent()) return;
        if (!maid.shieldBehaviorRunning() && shield.checkExtraStartConditions(maid)) shield.start(maid);
    }

    public void stop(MaidCombatActor maid) {
        if (maid.shieldBehaviorRunning()) shield.stop(maid);
    }

    public void runShield(MaidCombatActor maid) {
        if (maid.shieldBehaviorRunning()
            && (maid.worldGameTime() > maid.shieldBehaviorDeadline() || !shield.canStillUse(maid))) shield.stop(maid);
    }
}
