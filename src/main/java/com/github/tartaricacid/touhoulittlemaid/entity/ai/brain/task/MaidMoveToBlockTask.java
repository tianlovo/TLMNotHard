/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidMoveToBlockTask.java, MIT
 * Adaptation: guarded integer positions and shared modern arrival-map query
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidStealActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

public abstract class MaidMoveToBlockTask extends MaidCheckRateTask {

    private final float movementSpeed;
    private final int verticalSearchRange;
    protected int verticalSearchStart;
    private Point currentWorkPos;

    protected MaidMoveToBlockTask(float movementSpeed, int verticalSearchRange) {
        this.movementSpeed = movementSpeed;
        this.verticalSearchRange = verticalSearchRange;
        setMaxCheckRate(120);
    }

    public boolean checkExtraStartConditions(MaidStealActor maid) {
        return super.checkExtraStartConditions(maid::nextLifeInt);
    }

    protected final void searchForDestination(MaidStealActor maid) {
        try (var arrival = maid.openWorkSearch()) {
            var center = getWorkSearchPos(maid);
            int range = getHorizontalSearchRange(maid);
            for (int y = verticalSearchStart; y <= verticalSearchRange; y = y > 0 ? -y : 1 - y)
                for (int i = 0; i < range; i++) for (int x = 0; x <= i; x = x > 0 ? -x : 1 - x)
                    for (int z = x < i && x > -i ? i : 0; z <= i; z = z > 0 ? -z : 1 - z) {
                        var point = new Point(center.x + x, center.y + y - 1, center.z + z);
                        if (maid.withinLifeRestriction(point) && shouldMoveTo(maid, point)
                            && checkPathReach(maid, arrival, point)
                            && maid.workPointNearOwner(point)) {
                            maid.setPositionWalkAndLook(point, movementSpeed, 0);
                            maid.setWorkTarget(point);
                            currentWorkPos = point;
                            setNextCheckTickCount(5);
                            return;
                        }
                    }
            currentWorkPos = null;
        }
    }

    protected int getHorizontalSearchRange(MaidStealActor maid) {
        return (int) maid.getRestrictRadius();
    }

    private Point getWorkSearchPos(MaidStealActor maid) {
        if (!maid.isHomeModeEnable()) return maid.scheduleFeet();
        return currentWorkPos != null && maid.withinLifeRestriction(currentWorkPos) ? currentWorkPos
            : maid.restrictionCenter();
    }

    protected abstract boolean shouldMoveTo(MaidStealActor maid, Point point);

    protected boolean checkPathReach(MaidStealActor maid, MaidPathFindingBFS arrival, Point point) {
        return arrival.canPathReach(point);
    }

    @Override
    public void reset() {
        super.reset();
        currentWorkPos = null;
    }
}
