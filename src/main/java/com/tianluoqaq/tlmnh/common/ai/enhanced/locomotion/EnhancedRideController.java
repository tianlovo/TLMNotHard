package com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.locomotion.MaidVehicleActor.Vehicle;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 增强乘坐决策不驱动载具，登座仍使用受保护的平台容量与实际座位接口 */
public final class EnhancedRideController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin(EnhancedLocomotionIds.ID_RIDE);
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;

    public EnhancedRideController(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
    }

    public static boolean stationary(EntityMaid maid) {
        return maid.isSitting() || maid.ridingEntity != null;
    }

    public boolean offer(MaidIntentFrame frame) {
        boolean vehicle = offerVehicle(frame);
        offerStationary(frame);
        return vehicle;
    }

    public boolean offerVehicle(MaidIntentFrame frame) {
        if (!actor.canFollowOwnerVehicle() || !actor.vehicleOwnerPresent()) return false;
        var controlled = actor.ownerControlledVehicle();
        if (controlled == null) {
            if (!actor.maidIsPassenger() || !actor.ownerInDismountCooldown()
                || !actor
                    .maidWithinOwnerRange(EnhancedLocomotionTuning.ENHANCED_RIDE_MAID_WITHIN_OWNER_RANGE_FIRST_PARAM))
                return false;
            frame.offerTyped(
                ID,
                Group.COMMAND,
                Kind.VEHICLE,
                EnhancedLocomotionIds.ID_LEAVE,
                EnhancedLocomotionTuning.OFFER_TYPED_SINGLE_UNIT,
                (Vehicle) null,
                this::executeVehicle);
            return true;
        }
        if (actor.sameMaidVehicle(controlled) || !actor.canAddPassenger(controlled)) return false;
        if (actor.maidIsPassenger() && !actor.maidWithinVehicleRange(
            controlled,
            EnhancedLocomotionTuning.ENHANCED_RIDE_MAID_WITHIN_OWNER_RANGE_FIRST_PARAM)) return false;
        frame.offerTyped(
            ID,
            Group.COMMAND,
            Kind.VEHICLE,
            EnhancedLocomotionIds.ID_BOARD,
            EnhancedLocomotionTuning.OFFER_TYPED_SINGLE_UNIT,
            controlled,
            this::executeVehicle);
        return true;
    }

    public void executeVehicle(Vehicle proposed) {
        if (!actor.canFollowOwnerVehicle() || !actor.vehicleOwnerPresent()) return;
        if (proposed == null) {
            if (actor.ownerControlledVehicle() == null && actor.ownerInDismountCooldown()
                && actor
                    .maidWithinOwnerRange(EnhancedLocomotionTuning.ENHANCED_RIDE_MAID_WITHIN_OWNER_RANGE_FIRST_PARAM)
                && actor.maidIsPassenger()) actor.stopRiding();
        } else if (!actor.sameMaidVehicle(proposed) && actor.canAddPassenger(proposed)
            && actor.isOwnerControlledVehicle(proposed)) {
                if (actor.maidWithinVehicleRange(
                    proposed,
                    EnhancedLocomotionTuning.ENHANCED_RIDE_MAID_WITHIN_OWNER_RANGE_FIRST_PARAM))
                    actor.startRiding(proposed);
                else if (actor.canBrainMoving()) actor.walkToVehicle(
                    proposed,
                    EnhancedLocomotionTuning.ENHANCED_BREATHING_SET_AIR_WALK_AND_LOOK_SECOND_FACT,
                    EnhancedLocomotionTuning.ENHANCED_BREATHING_EXECUTE_Z_OFFSET);
            }
        if (actor.isCurrent() && stationary(maid)) actor.eraseWalkTarget();
    }

    public void offerStationary(MaidIntentFrame frame) {
        if (stationary(maid) && !actor.isSleeping() && !maid.getLeashed()) frame.offerTyped(
            ID,
            Group.AUTONOMOUS,
            Kind.RIDE,
            EnhancedLocomotionIds.ID_STATIONARY,
            EnhancedLocomotionTuning.ENHANCED_BREATHING_CANDIDATE_INDEX,
            null,
            ignored -> look());
    }

    public void look() {
        if (!stationary(maid) || actor.isSleeping()) return;
        actor.eraseWalkTarget();
        var owner = actor.visibleOwnerNow(EnhancedLocomotionTuning.ENHANCED_BREATHING_FIND_AIR_WITH_BFS_FIRST_PARAM);
        if (owner == null) actor.eraseLookTarget();
        else actor.lookAtOwner(owner);
        actor.setBegging(
            owner != null && actor.closerThan(owner, EnhancedLocomotionTuning.ENHANCED_FOLLOW_OFFER_PARAM)
                && maid.getHomeArea()
                    .activity() != Activity.REST
                && actor.ownerHoldsTemptation(owner));
    }
}
