/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidClearSleepTask.java, MIT
 * Adaptation: guarded current entity sleep/bed-lease boundary
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;

public final class MaidClearSleepTask {

    public void start(MaidActivityState brain, ModernFollowCoreActor maid) {
        if (brain.active() != MaidActivityState.Activity.REST) maid.clearSleepingIfPresent();
    }
}
