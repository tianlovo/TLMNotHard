/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidUpdateActivityFromSchedule.java, MIT
 * Adaptation: guarded activity state, existing native home anchors and first-release retained task set
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState;

public final class MaidUpdateActivityFromSchedule {

    public void start(MaidActivityState brain) {
        brain.startScheduleTask();
    }
}
