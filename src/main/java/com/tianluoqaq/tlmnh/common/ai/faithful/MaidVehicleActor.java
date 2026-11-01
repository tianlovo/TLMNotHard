package com.tianluoqaq.tlmnh.common.ai.faithful;

/** 原载具行为边界，所有引用只在当前策略帧有效 */
public interface MaidVehicleActor {

    interface Vehicle {
    }

    boolean canFollowOwnerVehicle();

    boolean vehicleOwnerPresent();

    Vehicle ownerControlledVehicle();

    boolean sameMaidVehicle(Vehicle vehicle);

    boolean canAddPassenger(Vehicle vehicle);

    boolean maidIsPassenger();

    boolean ownerInDismountCooldown();

    boolean maidWithinOwnerRange(float range);

    boolean maidWithinVehicleRange(Vehicle vehicle, float range);

    boolean hasVehicleWalkTarget();

    void walkToVehicle(Vehicle vehicle, float speed, int closeDistance);

    void startRiding(Vehicle vehicle);

    void stopRiding();
}
