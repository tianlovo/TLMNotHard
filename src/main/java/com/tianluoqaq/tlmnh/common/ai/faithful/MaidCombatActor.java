package com.tianluoqaq.tlmnh.common.ai.faithful;

/** 原攻击OneShot与举盾Behavior的当前帧平台边界，Target是带租约的引用 */
public interface MaidCombatActor {

    interface Target {
    }

    boolean isCurrent();

    Target attackTarget();

    Target findFirstValidAttackTarget();

    boolean setAttackTarget(Target target);

    void eraseAttackTarget();

    boolean canAttack(Target target);

    boolean targetAlive(Target target);

    boolean targetInSameWorld(Target target);

    boolean hasAssaultWeapon();

    boolean targetTooFar(Target target);

    Long getCantReachSince();

    void eraseCantReachSince();

    long worldGameTime();

    boolean hasVisibleLivingMemory();

    boolean targetVisible(Target target);

    boolean withinMeleeRange(Target target);

    boolean holdingUsableProjectileWeapon();

    void lookAtAttackTarget(Target target);

    void walkToAttackTarget(Target target, float speed);

    void eraseWalkTarget();

    boolean attackCoolingDown();

    double attackSpeed();

    void setAttackCooldown(long ticks);

    void swingMainHand();

    void hurtTarget(Target target);

    boolean canUseShield();

    float distanceToTarget(Target target);

    void startUsingShield(Target target);

    void stopUsingShield();

    boolean shieldBehaviorRunning();

    long shieldBehaviorDeadline();
}
