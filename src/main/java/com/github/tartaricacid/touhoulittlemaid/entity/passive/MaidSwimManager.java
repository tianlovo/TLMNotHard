/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/MaidSwimManager.java
 * Copyright (c) 2019-2025 tartaric_acid, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: platform-neutral actor and immutable dimensions/target instead of modern engine types
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSwimmingActor;

public final class MaidSwimManager {

    private static final Dimensions SWIMMING_DIMENSIONS = new Dimensions(.6F, .6F);
    private final MaidSwimmingActor maid;
    private boolean isEatBreatheItem, wantToSwim, readyToLand, isGoingToBreath;
    private Target swimTarget;

    public MaidSwimManager(MaidSwimmingActor maid) {
        this.maid = maid;
        maid.configureWaterPath();
    }

    public void resetEatBreatheItem() {
        if (isEatBreatheItem) isEatBreatheItem = false;
    }

    public void updateSwimming() {
        if (!maid.isClient()) updatePose();
    }

    private void updatePose() {
        if (wantToSwim() && !maid.onGround() && !maid.isSitting() && !maid.isPassenger()) {
            maid.setSwimming(true);
            maid.setSwimmingPose();
        } else {
            maid.setSwimming(false);
            if (!maid.isSleeping()) maid.setStandingPose();
        }
    }

    public void setWantToSwim(boolean value) {
        wantToSwim = value;
    }

    public boolean wantToSwim() {
        return wantToSwim;
    }

    public boolean isEatBreatheItem() {
        return isEatBreatheItem;
    }

    public void setEatBreatheItem(boolean value) {
        isEatBreatheItem = value;
    }

    public Dimensions getSwimmingDimensions() {
        return SWIMMING_DIMENSIONS;
    }

    public void setSwimTarget(Target target) {
        swimTarget = target;
    }

    public Target getSwimTarget() {
        return wantToSwim() ? swimTarget : null;
    }

    public void setReadyToLand(boolean value) {
        readyToLand = value;
    }

    public boolean isReadyToLand() {
        return readyToLand;
    }

    public void setGoingToBreath(boolean value) {
        isGoingToBreath = value;
    }

    public boolean isGoingToBreath() {
        return isGoingToBreath;
    }

    public static final class Dimensions {

        public final float width, height;

        public Dimensions(float width, float height) {
            this.width = width;
            this.height = height;
        }
    }

    public static final class Target {

        public final int x, y, z;

        public Target(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
