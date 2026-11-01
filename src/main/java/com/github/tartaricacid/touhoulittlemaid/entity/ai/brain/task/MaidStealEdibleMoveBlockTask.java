/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidStealEdibleMoveBlockTask.java, MIT
 * Adaptation: first-release native cake adapter; no external edible-block integrations
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockAction;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidStealActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

public final class MaidStealEdibleMoveBlockTask extends MaidMoveToBlockTask {

    private static final int NEXT_CHECK_TICK_COUNT = 45 * 20;
    private ItemStack placedStack;

    public MaidStealEdibleMoveBlockTask(float movementSpeed) {
        super(movementSpeed, 2);
        setMaxCheckRate(NEXT_CHECK_TICK_COUNT);
    }

    public void start(MaidStealActor maid) {
        if (maid.edibleAction() == MaidEdibleBlockAction.TRY_STEAL) {
            var stack = maid.firstCakeStack();
            if (stack != null) {
                placedStack = stack;
                maid.setEdibleAction(MaidEdibleBlockAction.TRY_PLACE);
                searchForDestination(maid);
                return;
            }
        }
        maid.setEdibleAction(MaidEdibleBlockAction.TRY_STEAL);
        searchForDestination(maid);
    }

    protected boolean shouldMoveTo(MaidStealActor maid, Point point) {
        return maid.edibleAction() == MaidEdibleBlockAction.TRY_PLACE
            ? placedStack != null && maid.shouldPlaceCake(point, placedStack)
            : maid.shouldStealCake(point);
    }

    protected boolean checkPathReach(MaidStealActor maid, MaidPathFindingBFS arrival, Point point) {
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            if (arrival.canPathReach(new Point(point.x + x, point.y + y, point.z + z))) return true;
        return false;
    }

    protected int getHorizontalSearchRange(MaidStealActor maid) {
        return Math.min(super.getHorizontalSearchRange(maid), 5);
    }

    protected void setNextCheckTickCount(int count) {
        super.setNextCheckTickCount(NEXT_CHECK_TICK_COUNT);
    }

    public void reset() {
        super.reset();
        placedStack = null;
    }
}
