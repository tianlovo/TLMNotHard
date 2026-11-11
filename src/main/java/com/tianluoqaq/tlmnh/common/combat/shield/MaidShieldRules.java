package com.tianluoqaq.tlmnh.common.combat.shield;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/** native 角度与耗损规则、原 TLM 的计时边界，不接触世界和装备 */
public final class MaidShieldRules {

    private MaidShieldRules() {}

    /** 同XZ时沿用BG的随机分离，不能把无法确定的方向当作正前方 */
    public static float relativeYaw(double sourceX, double sourceZ, double targetX, double targetZ, float targetYaw,
        DoubleSupplier random) {
        Objects.requireNonNull(random, ShieldCombatIds.ID_RANDOM);
        if (!Double.isFinite(sourceX) || !Double.isFinite(sourceZ)
            || !Double.isFinite(targetX)
            || !Double.isFinite(targetZ)
            || !Float.isFinite(targetYaw)) return Float.NaN;
        double dx = sourceX - targetX;
        double dz = sourceZ - targetZ;
        while (dx * dx + dz * dz < ShieldCombatTuning.MIN_DZ_THRESHOLD) {
            dx = (random.getAsDouble() - random.getAsDouble()) * ShieldCombatTuning.SHIELD_RULES_LAYOUT_DX_OFFSET;
            dz = (random.getAsDouble() - random.getAsDouble()) * ShieldCombatTuning.SHIELD_RULES_LAYOUT_DX_OFFSET;
        }
        float yaw = (float) (Math.atan2(dz, dx) * ShieldCombatTuning.SHIELD_RULES_YAW / Math.PI) - targetYaw
            - ShieldCombatTuning.SHIELD_RULES_YAW_2;
        while (yaw < ShieldCombatTuning.MIN_YAW_THRESHOLD) yaw += ShieldCombatTuning.FULL_TURN_DEGREES;
        while (yaw >= ShieldCombatTuning.HALF_TURN_DEGREES) yaw -= ShieldCombatTuning.FULL_TURN_DEGREES;
        return yaw;
    }

    public static boolean withinAngle(float relativeYaw, float blockAngle) {
        return Float.isFinite(relativeYaw) && Float.isFinite(blockAngle)
            && blockAngle > 0
            && blockAngle <= ShieldCombatTuning.HALF_TURN_DEGREES
            && relativeYaw < blockAngle
            && relativeYaw > -blockAngle;
    }

    /** -1表示BG不会调用damageItem；0表示确实调用，但请求值四舍五入为0 */
    public static int requestedWear(float amount, float reduction) {
        if (!Float.isFinite(amount) || !Float.isFinite(reduction) || amount < 0)
            throw new IllegalArgumentException(ShieldCombatDiagnostics.MSG_NON_FINITE_OR_NEGATIVE_SHIELD);
        return reduction < amount ? Math.round(amount - reduction) : ShieldCombatTuning.UNSET_OR_NOT_FOUND;
    }

    public static int maintainPassive(int previous, boolean holdingShield) {
        if (previous <= 0) return ShieldCombatTuning.SHIELD_CONTROLLER_SNAPSHOT_ZERO_BASE;
        return holdingShield ? previous - 1 : ShieldCombatTuning.SHIELD_CONTROLLER_DEPTH;
    }

    public static boolean activeTimedOut(long now, long end) {
        return now > end;
    }
}
