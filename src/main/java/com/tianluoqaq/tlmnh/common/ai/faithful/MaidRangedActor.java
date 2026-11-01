package com.tianluoqaq.tlmnh.common.ai.faithful;

/** 原弓箭行为的当前帧边界，物品使用读取共享手部状态，不沿用原型20帧准入 */
public interface MaidRangedActor extends MaidCombatActor {

    boolean hasRangedWalkTarget();

    int nextRangedWorldInt(int bound);

    boolean hasBowAndArrow();

    Target findFirstValidRangedTarget();

    boolean rangedTargetTooFar(Target target);

    boolean mainHandProjectileWeapon();

    boolean holdingProjectileWeapon();

    boolean canSeeRanged(Target target);

    void lookDirectlyAtRangedTarget(Target target);

    boolean rangedUsingItem();

    int rangedUsedTicks();

    int quickChargeLevel();

    void startRangedMainUse();

    void stopRangedItemUse();

    void performRangedAttack(Target target, float charge);

    int shootCooldown();

    void setSwingingArms(boolean value);

    float rangedSearchRadius();

    int defaultProjectileRange();

    float nextRangedFloat();

    boolean ownerOutsideStrafeRestriction();

    void strafe(float forwards, float sideways);

    void stopRangedInPlace();

    void alignRangedBodyToHead();

    boolean isHomeModeEnable();

    double targetDistanceToRestrictionCenter(Target target);

    Double targetDistanceToOwner(Target target);

    float getRestrictRadius();
}
