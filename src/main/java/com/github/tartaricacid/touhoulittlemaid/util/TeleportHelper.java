/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: util/TeleportHelper.java, MIT
 * Adaptation: guarded target-world teleport boundary; preserve entity RNG order and integer candidates
 */
package com.github.tartaricacid.touhoulittlemaid.util;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidScheduleActor;

public final class TeleportHelper {

    private TeleportHelper() {}

    public static boolean teleportToRestrictCenter(MaidScheduleActor maid) {
        var center = maid.restrictionCenter();
        int x = center.x + maid.nextTeleportInt(7) - 3;
        int y = center.y + maid.nextTeleportInt(4);
        int z = center.z + maid.nextTeleportInt(7) - 3;
        return maid.teleportAt(x, y, z);
    }
}
