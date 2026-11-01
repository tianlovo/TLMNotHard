/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/SchedulePos.java, MIT
 * Adaptation: existing home storage/metadata and guarded entity-after-living actor
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import com.github.tartaricacid.touhoulittlemaid.util.TeleportHelper;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidScheduleActor;

public final class SchedulePos {

    public void restrictTo(MaidScheduleActor maid) {
        maid.applyScheduleRestriction();
    }

    public void tick(MaidScheduleActor maid) {
        if (maid.entityAge() % 40 != 0) return;
        restrictTo(maid);
        if (maid.withinCurrentRestriction() || !maid.canBrainMoving()) return;
        var center = maid.restrictionCenter();
        var feet = maid.scheduleFeet();
        double dx = (double) center.x - feet.x, dy = (double) center.y - feet.y, dz = (double) center.z - feet.z;
        int minimumTeleportDistance = (int) maid.getRestrictRadius() + 4;
        if (dx * dx + dy * dy + dz * dz > minimumTeleportDistance * minimumTeleportDistance
            && !maid.walkTargetsRestrictionCenter()) {
            for (int attempt = 0; attempt < 10; attempt++) if (TeleportHelper.teleportToRestrictCenter(maid)) return;
        } else maid.walkToRestrictionCenter();
    }
}
