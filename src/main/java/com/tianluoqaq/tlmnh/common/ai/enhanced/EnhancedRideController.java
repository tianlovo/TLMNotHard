package com.tianluoqaq.tlmnh.common.ai.enhanced;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidVehicleActor.Vehicle;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 增强乘坐决策不驱动载具，登座仍使用受保护的平台容量与实际座位接口 */
public final class EnhancedRideController {

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;

    public EnhancedRideController(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
    }

    public static boolean stationary(EntityMaid maid) {
        return maid.isSitting() || maid.ridingEntity != null;
    }

    public boolean offerVehicle(MaidIntentFrame frame) {
        if (!actor.canFollowOwnerVehicle() || !actor.vehicleOwnerPresent()) return false;
        var controlled = actor.ownerControlledVehicle();
        if (controlled == null) {
            if (!actor.maidIsPassenger() || !actor.ownerInDismountCooldown() || !actor.maidWithinOwnerRange(3))
                return false;
            frame.offer(Group.COMMAND, Kind.VEHICLE, "leave", 1, null);
            return true;
        }
        if (actor.sameMaidVehicle(controlled) || !actor.canAddPassenger(controlled)) return false;
        if (actor.maidIsPassenger() && !actor.maidWithinVehicleRange(controlled, 3)) return false;
        frame.offer(Group.COMMAND, Kind.VEHICLE, "board", 1, controlled);
        return true;
    }

    public void executeVehicle(Vehicle proposed) {
        if (!actor.canFollowOwnerVehicle() || !actor.vehicleOwnerPresent()) return;
        if (proposed == null) {
            if (actor.ownerControlledVehicle() == null && actor.ownerInDismountCooldown()
                && actor.maidWithinOwnerRange(3)
                && actor.maidIsPassenger()) actor.stopRiding();
        } else if (!actor.sameMaidVehicle(proposed) && actor.canAddPassenger(proposed)
            && actor.isOwnerControlledVehicle(proposed)) {
                if (actor.maidWithinVehicleRange(proposed, 3)) actor.startRiding(proposed);
                else if (actor.canBrainMoving()) actor.walkToVehicle(proposed, .5F, 2);
            }
        if (actor.isCurrent() && stationary(maid)) actor.eraseWalkTarget();
    }

    public void offerStationary(MaidIntentFrame frame) {
        if (stationary(maid) && !actor.isSleeping() && !maid.getLeashed())
            frame.offer(Group.AUTONOMOUS, Kind.RIDE, "stationary", 0, null);
    }

    public void look() {
        if (!stationary(maid) || actor.isSleeping()) return;
        actor.eraseWalkTarget();
        var owner = actor.visibleOwnerNow(16);
        if (owner == null) actor.eraseLookTarget();
        else actor.lookAtOwner(owner);
        actor.setBegging(
            owner != null && actor.closerThan(owner, 6)
                && maid.getHomeArea()
                    .activity() != Activity.REST
                && actor.ownerHoldsTemptation(owner));
    }
}
