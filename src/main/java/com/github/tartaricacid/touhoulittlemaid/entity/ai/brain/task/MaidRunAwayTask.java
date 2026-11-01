/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidRunAwayTask.java, MIT
 * Adaptation: guarded native sitting/mount boundary and validated avoid position
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;

public final class MaidRunAwayTask extends SetWalkTargetAwayFrom {

    public MaidRunAwayTask(float speed, boolean ignoreOtherWalkTarget) {
        super(speed, ignoreOtherWalkTarget);
    }

    @Override
    public void start(ModernFollowCoreActor maid, Vec3 avoid) {
        maid.leaveMountOrSitForDanger();
        super.start(maid, avoid);
    }
}
