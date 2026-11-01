package com.tianluoqaq.tlmnh.common.ai;

import java.util.function.DoubleSupplier;

/** 固定上游 MaidAttackStrafingTask 的方向状态，移动执行由旧引擎适配器负责 */
public final class MaidStrafingState {

    public static final float PROJECTILE_RANGE = 15;
    private boolean clockwise, backwards;
    private int time = -1;
    private float forward, strafe;

    public void tick(double distance, double searchRadius, boolean ownerBoundary, DoubleSupplier random) {
        forward = strafe = 0;
        if (!Double.isFinite(distance) || distance < 0 || distance >= searchRadius) {
            time = -1;
            return;
        }
        if (++time >= 20) {
            if (random.getAsDouble() < .3) clockwise = !clockwise;
            if (random.getAsDouble() < .3) backwards = !backwards;
            time = 0;
        }
        if (distance > PROJECTILE_RANGE * .5) backwards = false;
        else if (distance < PROJECTILE_RANGE * .2) backwards = true;
        if (!ownerBoundary) {
            forward = backwards ? -.5F : .5F;
            strafe = clockwise ? .5F : -.5F;
        }
    }

    public void stop() {
        // 上游 stop 不清方向与随机相位，恢复时延续同一任务实例的状态
        forward = strafe = 0;
    }

    public float forward() {
        return forward;
    }

    public float strafe() {
        return strafe;
    }
}
