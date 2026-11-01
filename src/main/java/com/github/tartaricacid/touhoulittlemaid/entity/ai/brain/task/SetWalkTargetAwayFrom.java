/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/SetWalkTargetAwayFrom.java, MIT
 * Adaptation: validated source-memory position instead of generic vanilla MemoryModuleType
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;

public class SetWalkTargetAwayFrom {

    private final float speed;
    private final boolean ignoreOtherWalkTarget;

    public SetWalkTargetAwayFrom(float speed, boolean ignoreOtherWalkTarget) {
        this.speed = speed;
        this.ignoreOtherWalkTarget = ignoreOtherWalkTarget;
    }

    public boolean checkExtraStartConditions(ModernFollowCoreActor maid, Vec3 avoid) {
        var walk = maid.getWalkTarget();
        if (avoid == null || walk != null && !ignoreOtherWalkTarget) return false;
        var position = maid.entityPosition();
        if (walk != null && walk.getSpeedModifier() == speed) {
            var target = walk.currentPosition();
            if ((target.x - position.x) * (avoid.x - position.x) + (target.y - position.y) * (avoid.y - position.y)
                + (target.z - position.z) * (avoid.z - position.z) < 0) return false;
        }
        double radius = (int) maid.getRestrictRadius();
        double dx = position.x - avoid.x, dy = position.y - avoid.y, dz = position.z - avoid.z;
        return dx * dx + dy * dy + dz * dz < radius * radius;
    }

    public void start(ModernFollowCoreActor maid, Vec3 avoid) {
        for (int attempt = 0; attempt < 10; attempt++) {
            var pos = maid.findLandAway((int) maid.getRestrictRadius(), 7, avoid);
            if (pos != null) {
                maid.setVectorWalkTarget(pos, speed, 0);
                return;
            }
        }
    }
}
