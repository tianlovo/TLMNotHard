/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidClearHurtTask.java, MIT
 * Adaptation: guarded modern activity/typed memory state, outer Behavior lifecycle
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState;

public final class MaidClearHurtTask {

    public void start(MaidActivityState brain) {
        if (!brain.canPanic() || !brain.hurtOrHostile()) {
            brain.clearHurtMemories();
            brain.updateFromSchedule();
        }
    }
}
