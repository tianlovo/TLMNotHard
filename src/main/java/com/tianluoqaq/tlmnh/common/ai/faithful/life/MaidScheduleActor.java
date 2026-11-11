package com.tianluoqaq.tlmnh.common.ai.faithful.life;

import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;

/** 原SchedulePos的移动后相位边界，限制区属于实体并跨策略取消保留 */
public interface MaidScheduleActor {

    int entityAge();

    void applyScheduleRestriction();

    boolean withinCurrentRestriction();

    boolean canBrainMoving();

    Point restrictionCenter();

    Point scheduleFeet();

    float getRestrictRadius();

    boolean walkTargetsRestrictionCenter();

    void walkToRestrictionCenter();

    int nextTeleportInt(int bound);

    boolean teleportAt(double x, double y, double z);
}
