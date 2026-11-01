/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidBreathAirTask.java, MIT
 * Adaptation: guarded platform actor; existing item lifecycle and query-local modern BFS
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidAirActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 低氧物品优先，再找空气；外层保留默认Behavior开始/运行相位与RNG */
public final class MaidBreathAirTask {

    private static final int AIR_SEARCH_RANGE = 16;

    public boolean checkExtraStartConditions(MaidAirActor maid) {
        if (maid.isEatBreatheItem()) return false;
        var target = maid.isGoingToBreath() ? maid.walkTargetPosition() : null;
        if (target != null && maid.givesAir(target)) {
            if (!maid.navigationIsDone() || maid.distanceManhattan(target) <= 1) return false;
        }
        if (maid.airSupply() >= 100) return false;
        if (maid.hasBreathingEffect()) return false;
        if (maid.hasDrownBauble()) return false;
        return !maid.isUsingItem();
    }

    public void start(MaidAirActor maid) {
        if (maid.tryEatBreatheItem()) return;
        if (!maid.isCurrent()) return;
        findAirPosition(maid);
    }

    private void findAirPosition(MaidAirActor maid) {
        if (!maid.canBrainMoving()) return;
        var match = maid.findAirWithBfs(AIR_SEARCH_RANGE);
        // 原BFS可能找到A*范围内不能完全到达的点，仍须实际canReach确认
        if (match.isPresent() && maid.canPathReach(match.get())) {
            move(maid, match.get());
            return;
        }
        var current = maid.airSearchPosition();
        var sea = new Point(current.x, maid.seaLevel() + 1, current.z);
        if (maid.givesAir(sea) && maid.canPathReach(sea)) {
            move(maid, sea);
            return;
        }
        // BlockPos.betweenClosed的原迭代顺序：x先变化，随后z
        for (int z = sea.z - 2; z <= sea.z + 2; z++) for (int x = sea.x - 2; x <= sea.x + 2; x++) {
            var pos = new Point(x, sea.y, z);
            if (maid.givesAir(pos) && maid.canPathReach(pos)) {
                move(maid, pos);
                return;
            }
        }
    }

    private void move(MaidAirActor maid, Point pos) {
        maid.setGoingToBreath(true);
        maid.setAirWalkAndLook(pos, .5F, 1);
    }
}
