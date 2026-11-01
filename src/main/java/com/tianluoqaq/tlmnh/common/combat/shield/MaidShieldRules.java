package com.tianluoqaq.tlmnh.common.combat.shield;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/** native 角度与耗损规则、原 TLM 的计时边界，不接触世界和装备 */
public final class MaidShieldRules {

    public static final int PASSIVE_TICKS = 100;
    public static final int ACTIVE_DURATION = 60;
    public static final int DISABLE_TICKS = 100;

    private MaidShieldRules() {}

    /** 同XZ时沿用BG的随机分离，不能把无法确定的方向当作正前方 */
    public static float relativeYaw(double sourceX, double sourceZ, double targetX, double targetZ, float targetYaw,
        DoubleSupplier random) {
        Objects.requireNonNull(random, "random");
        if (!Double.isFinite(sourceX) || !Double.isFinite(sourceZ)
            || !Double.isFinite(targetX)
            || !Double.isFinite(targetZ)
            || !Float.isFinite(targetYaw)) return Float.NaN;
        double dx = sourceX - targetX;
        double dz = sourceZ - targetZ;
        while (dx * dx + dz * dz < 1.0E-4D) {
            dx = (random.getAsDouble() - random.getAsDouble()) * .01D;
            dz = (random.getAsDouble() - random.getAsDouble()) * .01D;
        }
        float yaw = (float) (Math.atan2(dz, dx) * 180D / Math.PI) - targetYaw - 90;
        while (yaw < -180) yaw += 360;
        while (yaw >= 180) yaw -= 360;
        return yaw;
    }

    public static boolean withinAngle(float relativeYaw, float blockAngle) {
        return Float.isFinite(relativeYaw) && Float.isFinite(blockAngle)
            && blockAngle > 0
            && blockAngle <= 180
            && relativeYaw < blockAngle
            && relativeYaw > -blockAngle;
    }

    /** -1表示BG不会调用damageItem；0表示确实调用，但请求值四舍五入为0 */
    public static int requestedWear(float amount, float reduction) {
        if (!Float.isFinite(amount) || !Float.isFinite(reduction) || amount < 0)
            throw new IllegalArgumentException("Non-finite or negative shield damage input");
        return reduction < amount ? Math.round(amount - reduction) : -1;
    }

    public static int maintainPassive(int previous, boolean holdingShield) {
        if (previous <= 0) return 0;
        return holdingShield ? previous - 1 : 1;
    }

    public static boolean activeTimedOut(long now, long end) {
        return now > end;
    }
}
