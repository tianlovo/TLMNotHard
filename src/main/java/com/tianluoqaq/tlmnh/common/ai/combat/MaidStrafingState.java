package com.tianluoqaq.tlmnh.common.ai.combat;

import java.util.function.DoubleSupplier;

/** 固定上游 MaidAttackStrafingTask 的方向状态，移动执行由旧引擎适配器负责 */
public final class MaidStrafingState {

    private boolean clockwise, backwards;
    private int time = AiCombatTuning.UNSET_OR_NOT_FOUND;
    private float forward, strafe;

    public void tick(double distance, double searchRadius, boolean ownerBoundary, DoubleSupplier random) {
        forward = strafe = AiCombatTuning.COMBAT_CONTROLLER_CONSECUTIVE_PATH_FAILURES;
        if (!Double.isFinite(distance) || distance < 0 || distance >= searchRadius) {
            time = AiCombatTuning.UNSET_OR_NOT_FOUND;
            return;
        }
        if (++time >= AiCombatTuning.MELEE_ATTACK_MAX_SECOND_PARAM) {
            if (random.getAsDouble() < AiCombatTuning.MIN_GET_AS_DOUBLE_THRESHOLD) clockwise = !clockwise;
            if (random.getAsDouble() < AiCombatTuning.MIN_GET_AS_DOUBLE_THRESHOLD) backwards = !backwards;
            time = AiCombatTuning.COMBAT_CONTROLLER_CONSECUTIVE_PATH_FAILURES;
        }
        if (distance > AiCombatTuning.PROJECTILE_RANGE * AiCombatTuning.STRAFING_STATE_TICK_FACTOR) backwards = false;
        else if (distance < AiCombatTuning.PROJECTILE_RANGE * AiCombatTuning.STRAFING_STATE_TICK_FACTOR_2)
            backwards = true;
        if (!ownerBoundary) {
            forward = backwards ? AiCombatTuning.STRAFING_STATE_TICK_FACTOR_3
                : AiCombatTuning.STRAFING_STATE_TICK_FACTOR_4;
            strafe = clockwise ? AiCombatTuning.STRAFING_STATE_TICK_FACTOR_4
                : AiCombatTuning.STRAFING_STATE_TICK_FACTOR_3;
        }
    }

    public void stop() {
        // 上游 stop 不清方向与随机相位，恢复时延续同一任务实例的状态
        forward = strafe = AiCombatTuning.COMBAT_CONTROLLER_CONSECUTIVE_PATH_FAILURES;
    }

    public float forward() {
        return forward;
    }

    public float strafe() {
        return strafe;
    }
}
