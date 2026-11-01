/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidAwaitTask.java, MIT
 * Adaptation: guarded modern target and restriction boundary
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;

public final class MaidAwaitTask {

    public void start(ModernFollowCoreActor maid) {
        var walk = maid.getWalkTarget();
        if (walk != null && !maid.isWithinHome(walk.currentBlockPosition())) {
            maid.setPathMemory(null);
            maid.eraseWalkTarget();
        }
    }
}
