/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidRunOne.java, MIT
 * Adaptation: fixed native Gate/OneShot lifecycle and guarded platform facts instead of modern BehaviorControl
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidRandomActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidRandomGate;

public final class MaidRunOne extends MaidRandomGate {

    public MaidRunOne(boolean walking) {
        super(walking);
    }

    public boolean tryStart(MaidRandomActor maid, boolean enabled) {
        return enabled && maid.randomGateEligible() && super.tryStart(maid);
    }
}
