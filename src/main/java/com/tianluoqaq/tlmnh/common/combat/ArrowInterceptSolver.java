package com.tianluoqaq.tlmnh.common.combat;

/** 在每段真实tick内解截击二次式，补偿固定阻力和下坠，不读取目标未来状态 */
public final class ArrowInterceptSolver {

    public static final int MAX_FLIGHT_TICKS = 160;
    private static final double MIN_DISTANCE = 0.25;
    private static final double SPEED_RESIDUAL = 1.0E-9;

    public enum Status {
        SOLVED,
        INVALID_INPUT,
        TOO_CLOSE,
        NO_INTERCEPT_WITHIN_BUDGET
    }

    private ArrowInterceptSolver() {}

    public static Solution solve(BallisticVector origin, BallisticVector target, BallisticVector targetVelocity,
        double speed, int maxTicks, ArrowPhysics physics) {
        if (origin == null || target == null
            || targetVelocity == null
            || physics == null
            || !Double.isFinite(speed)
            || speed <= 0
            || speed > 16
            || maxTicks < 1
            || maxTicks > MAX_FLIGHT_TICKS) return Solution.rejected(Status.INVALID_INPUT, 0);
        var relative = target.subtract(origin);
        if (relative.length() < MIN_DISTANCE) return Solution.rejected(Status.TOO_CLOSE, 0);
        double distanceScale = 0, gravityScale = 0, power = 1;
        for (int tick = 0; tick < maxTicks; tick++) {
            // 对分段时间t=n+f：A=A_n+f*d^n，B=B_n+f*A_n，位置=origin+A*v0-g*B
            var p = new BallisticVector(
                relative.x + targetVelocity.x * tick,
                relative.y + targetVelocity.y * tick + physics.gravity * gravityScale,
                relative.z + targetVelocity.z * tick);
            var q = new BallisticVector(
                targetVelocity.x,
                targetVelocity.y + physics.gravity * distanceScale,
                targetVelocity.z);
            double a = q.dot(q) - speed * speed * power * power;
            double b = 2 * (p.dot(q) - speed * speed * distanceScale * power);
            double c = p.dot(p) - speed * speed * distanceScale * distanceScale;
            double[] roots = roots(a, b, c);
            for (double fraction : roots) {
                if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1 || tick + fraction <= 0) continue;
                double scale = distanceScale + fraction * power;
                if (!(scale > 0)) continue;
                var velocity = p.add(q.scale(fraction))
                    .scale(1 / scale);
                if (Math.abs(velocity.length() - speed) > SPEED_RESIDUAL * Math.max(1, speed)) continue;
                double time = tick + fraction;
                return new Solution(Status.SOLVED, velocity, time, target.add(targetVelocity.scale(time)), tick + 1);
            }
            gravityScale += distanceScale;
            distanceScale += power;
            power *= physics.drag;
        }
        return Solution.rejected(Status.NO_INTERCEPT_WITHIN_BUDGET, maxTicks);
    }

    private static double[] roots(double a, double b, double c) {
        if (a == 0) return b == 0 ? new double[0] : new double[] { -c / b };
        double discriminant = b * b - 4 * a * c;
        if (!Double.isFinite(discriminant)) return new double[0];
        if (discriminant < 0) {
            double rounding = 8 * Math.ulp(Math.abs(b * b) + Math.abs(4 * a * c));
            if (discriminant < -rounding) return new double[0];
            discriminant = 0;
        }
        double q = -0.5 * (b + Math.copySign(Math.sqrt(discriminant), b));
        if (q == 0) return new double[] { -b / (2 * a) };
        double first = q / a, second = c / q;
        return first <= second ? new double[] { first, second } : new double[] { second, first };
    }

    public static final class Solution {

        public final Status status;
        public final BallisticVector initialVelocity, predictedTarget;
        public final double flightTicks;
        public final int evaluatedIntervals;

        private Solution(Status status, BallisticVector velocity, double ticks, BallisticVector target, int intervals) {
            this.status = status;
            initialVelocity = velocity;
            flightTicks = ticks;
            predictedTarget = target;
            evaluatedIntervals = intervals;
        }

        private static Solution rejected(Status status, int intervals) {
            return new Solution(status, null, Double.NaN, null, intervals);
        }

        public boolean isSolved() {
            return status == Status.SOLVED;
        }
    }
}
