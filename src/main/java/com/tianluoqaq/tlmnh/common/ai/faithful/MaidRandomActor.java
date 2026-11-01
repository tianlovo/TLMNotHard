package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;

/** 原RunOne的随机张望/步行边界，洗牌使用各Gate自己的随机源 */
public interface MaidRandomActor {

    enum LookKind {
        PLAYER,
        MAID,
        WOLF,
        CAT,
        PARROT
    }

    boolean randomGateEligible();

    boolean tryRandomLook(LookKind kind, float distance);

    boolean hasRandomWalkTarget();

    Vec3 sampleRandomWalk(int radius, int vertical);

    void setVectorWalkTarget(Vec3 pos, float speed, int closeDistance);

    void eraseWalkTarget();

    int nextWorldInt(int bound);

    long worldGameTime();
}
