package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowOwnerActor.Owner;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 原床/乞食任务使用的工作记忆与当前帧实体边界 */
public interface MaidLifeActor {

    boolean isCurrent();

    int nextLifeInt(int bound);

    boolean canBrainMoving();

    boolean hasLifeWalkTarget();

    Point workTarget();

    void setWorkTarget(Point point);

    void eraseTargetPos();

    Point findNearestBed();

    boolean withinLifeRestriction(Point point);

    double distanceToBlockCenterSquared(Point point);

    void setPositionWalkAndLook(Point point, float speed, int closeDistance);

    void sleepAt(Point point);

    void eraseWalkTarget();

    void eraseLookTarget();

    Owner temptingVisibleOwner();

    Owner getOwner();

    boolean closerThan(Owner owner, double distance);

    void setWalkAndLookTargetMemories(Owner owner, float speed, int closeDistance);

    void lookAtOwner(Owner owner);

    void setBegging(boolean value);
}
