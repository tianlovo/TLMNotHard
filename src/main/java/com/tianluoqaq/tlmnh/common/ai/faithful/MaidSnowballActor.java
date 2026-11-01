package com.tianluoqaq.tlmnh.common.ai.faithful;

/** 原TaskIdle雪仗共享ATTACK_TARGET，但不走战斗选敌事件或真实武器伤害 */
public interface MaidSnowballActor extends MaidCombatActor {

    boolean canSnowballFight();

    Target findFirstValidSnowballTarget();

    void rememberSnowballTarget(Target target);

    boolean holdingSnowballOrEmpty();

    boolean targetWithinRestriction(Target target);

    void prepareSnowballHand();

    float nextSnowballFloat();

    int nextSnowballInt(int bound);

    void swingSnowballHand();

    void throwSnowballAt(Target target);

    void playPickUpSnowball();

    boolean isPickingUpSnowball();

    long snowballAnimationStartedAt();

    long nowMillis();

    void expireSnowballAnimation();

    void stopNavigation();
}
