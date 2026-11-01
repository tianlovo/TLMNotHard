package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Optional;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 原呼吸任务的世界/物品/记忆边界，调用方须持当前策略帧 */
public interface MaidAirActor {

    default boolean isCurrent() {
        return true;
    }

    boolean isEatBreatheItem();

    boolean isGoingToBreath();

    Point walkTargetPosition();

    boolean givesAir(Point pos);

    boolean navigationIsDone();

    int distanceManhattan(Point pos);

    int airSupply();

    boolean hasBreathingEffect();

    boolean hasDrownBauble();

    boolean isUsingItem();

    boolean tryEatBreatheItem();

    boolean canBrainMoving();

    Optional<Point> findAirWithBfs(int range);

    boolean canPathReach(Point pos);

    Point airSearchPosition();

    int seaLevel();

    void setGoingToBreath(boolean value);

    void setAirWalkAndLook(Point pos, float speed, int closeDistance);

    void eraseWalkTarget();

    void eraseTargetPos();
}
