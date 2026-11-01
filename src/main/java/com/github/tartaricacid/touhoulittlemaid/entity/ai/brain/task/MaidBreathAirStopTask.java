/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidBreathAirStopTask.java, MIT
 * Adaptation: guarded platform actor; existing item lifecycle and query-local modern BFS
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidAirActor;

public final class MaidBreathAirStopTask {

    public boolean checkExtraStartConditions(MaidAirActor maid) {
        return maid.isGoingToBreath()
            && (maid.airSupply() >= 290 || maid.hasBreathingEffect() || maid.hasDrownBauble());
    }

    public void start(MaidAirActor maid) {
        maid.setGoingToBreath(false);
        maid.eraseWalkTarget();
        maid.eraseTargetPos();
    }
}
