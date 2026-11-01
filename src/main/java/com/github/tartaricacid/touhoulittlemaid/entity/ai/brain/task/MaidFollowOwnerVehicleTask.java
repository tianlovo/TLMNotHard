/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidFollowOwnerVehicleTask.java, MIT
 * Adaptation: guarded actor and installed vehicle capacity/seat API; excluded SWarfare integration
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidVehicleActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidVehicleActor.Vehicle;

public final class MaidFollowOwnerVehicleTask {

    private final float speedModifier;
    private final int stopDistance;
    private Vehicle ownerControlledVehicle;
    private Type type = Type.NONE;

    public MaidFollowOwnerVehicleTask(float speedModifier, int stopDistance) {
        this.speedModifier = speedModifier;
        this.stopDistance = stopDistance;
    }

    public boolean checkExtraStartConditions(MaidVehicleActor maid) {
        if (!maid.canFollowOwnerVehicle() || !maid.vehicleOwnerPresent()) return false;
        var controlled = maid.ownerControlledVehicle();
        if (controlled == null) {
            if (maid.ownerInDismountCooldown() && maid.maidWithinOwnerRange(3) && maid.maidIsPassenger()) {
                type = Type.STOP;
                return true;
            }
            return false;
        }
        if (maid.sameMaidVehicle(controlled) || !maid.canAddPassenger(controlled)) return false;
        if (maid.maidWithinVehicleRange(controlled, 3)) {
            ownerControlledVehicle = controlled;
            type = Type.RIDE;
            return true;
        }
        if (!maid.hasVehicleWalkTarget()) maid.walkToVehicle(controlled, speedModifier, stopDistance);
        return false;
    }

    public void start(MaidVehicleActor maid) {
        if (type == Type.RIDE && ownerControlledVehicle != null) maid.startRiding(ownerControlledVehicle);
        else if (type == Type.STOP) maid.stopRiding();
    }

    public void stop() {
        ownerControlledVehicle = null;
        type = Type.NONE;
    }

    private enum Type {
        RIDE,
        STOP,
        NONE
    }
}
