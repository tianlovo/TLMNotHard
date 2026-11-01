package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;

/**
 * 按固定 MC1.20.1 DefaultRandomPos/RandomPos/GoalUtils 的实际字节码适配候选生成
 * 只生成坐标，不查询或安装路径，不运行导航，不提供完整自然 F/N 准入
 */
public final class FollowFallbackSampler {

    private static final int ATTEMPTS = 10;
    private static final double HALF_PI_FLOAT = 1.5707963705062866D;
    private static final float SQRT_OF_TWO = (float) Math.sqrt(2.0F);
    private static final double FRAC_BIAS = Double.longBitsToDouble(4805340802404319232L);
    private static final double[] ASIN_TABLE = new double[257];
    private static final double[] COS_TABLE = new double[257];

    static {
        for (int index = 0; index < 257; index++) {
            double angle = Math.asin((double) index / 256.0D);
            COS_TABLE[index] = Math.cos(angle);
            ASIN_TABLE[index] = angle;
        }
    }

    /** 事实和随机数必须来自当前实体的有效执行上下文，未知世界事实由适配器明确拒绝 */
    public interface Actor {

        Vec3 position();

        float nextEntityFloat();

        double nextEntityDouble();

        int nextEntityInt(int bound);

        boolean hasRestriction();

        BlockPos restrictionCenter();

        float restrictionRadius();

        boolean isWithinRestriction(BlockPos position);

        int minBuildHeight();

        int maxBuildHeight();

        boolean isStableDestination(BlockPos position);

        float pathfindingMalus(BlockPos position);

        /** 原 getWalkTargetValue 为 float，之后由选择过程拓宽为 double */
        float walkTargetValue(BlockPos position);
    }

    private FollowFallbackSampler() {}

    public static Vec3 getPosTowards(Actor actor, int horizontalRange, int verticalRange, Vec3 target, double angle) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        if (horizontalRange < 0 || horizontalRange > (Integer.MAX_VALUE - 1) / 2
            || verticalRange < 0
            || verticalRange > (Integer.MAX_VALUE - 1) / 2
            || !Double.isFinite(angle)
            || angle < 0) throw new IllegalArgumentException("Outside bounded fallback arguments");
        Vec3 position = Objects.requireNonNull(actor.position(), "actor position");
        double directionX = target.x - position.x, directionZ = target.z - position.z;
        if (!Double.isFinite(directionX) || !Double.isFinite(directionZ))
            throw new IllegalArgumentException("Non-finite fallback direction");
        boolean restricted = mobRestricted(actor, horizontalRange);
        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos best = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            BlockPos offset = direction(actor, horizontalRange, verticalRange, directionX, directionZ, angle);
            if (offset == null) continue;
            BlockPos candidate = absolutePosition(actor, horizontalRange, offset);
            // GoalUtils 按此顺序短路，原上界检查是 > max，不是 >= max
            if (candidate.y < actor.minBuildHeight() || candidate.y > actor.maxBuildHeight()
                || restricted && !actor.isWithinRestriction(candidate)
                || !actor.isStableDestination(candidate)
                || actor.pathfindingMalus(candidate) != 0.0F) continue;
            double score = actor.walkTargetValue(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? null : new Vec3(best.x + 0.5D, best.y, best.z + 0.5D);
    }

    static boolean mobRestricted(Actor actor, int range) {
        if (!actor.hasRestriction()) return false;
        BlockPos center = Objects.requireNonNull(actor.restrictionCenter(), "restriction center");
        Vec3 position = Objects.requireNonNull(actor.position(), "actor position");
        double distance = (double) (actor.restrictionRadius() + (float) range) + 1.0D;
        double x = center.x + 0.5D - position.x;
        double y = center.y + 0.5D - position.y;
        double z = center.z + 0.5D - position.z;
        return x * x + y * y + z * z < distance * distance;
    }

    static BlockPos direction(Actor actor, int horizontalRange, int verticalRange, double x, double z, double angle) {
        double base = mappedAtan2(z, x) - HALF_PI_FLOAT;
        // 两个乘减先在 float 完成，随后才拓宽为 double
        double heading = base + (double) (2.0F * entityFloat(actor) - 1.0F) * angle;
        double radius = Math.sqrt(entityDouble(actor)) * (double) SQRT_OF_TWO * (double) horizontalRange;
        double dx = -radius * Math.sin(heading), dz = radius * Math.cos(heading);
        // 方形范围外只消费 float/double，不抽 y，也不处理 restriction
        if (Math.abs(dx) > horizontalRange || Math.abs(dz) > horizontalRange) return null;
        int dy = entityInt(actor, 2 * verticalRange + 1) - verticalRange;
        return new BlockPos(mappedFloor(dx), dy, mappedFloor(dz));
    }

    static BlockPos absolutePosition(Actor actor, int range, BlockPos offset) {
        int x = offset.x, z = offset.z;
        if (actor.hasRestriction() && range > 1) {
            BlockPos center = Objects.requireNonNull(actor.restrictionCenter(), "restriction center");
            x += Objects.requireNonNull(actor.position(), "actor position").x > center.x ? -entityInt(actor, range / 2)
                : entityInt(actor, range / 2);
            z += Objects.requireNonNull(actor.position(), "actor position").z > center.z ? -entityInt(actor, range / 2)
                : entityInt(actor, range / 2);
        }
        Vec3 position = Objects.requireNonNull(actor.position(), "actor position");
        return new BlockPos(
            mappedFloor((double) x + position.x),
            mappedFloor((double) offset.y + position.y),
            mappedFloor((double) z + position.z));
    }

    /** LevelReader 的光照路径评分；调用方仍须核 grass、真实 raw light 与维度 ambient 来源 */
    public static float lightPathfindingCost(int rawBrightness, float ambientLight) {
        if (rawBrightness < 0 || rawBrightness > 15
            || !Float.isFinite(ambientLight)
            || ambientLight < 0
            || ambientLight > 1) throw new IllegalArgumentException("Outside verified light inputs");
        float level = (float) rawBrightness / 15.0F;
        float brightness = level / (4.0F - 3.0F * level);
        return brightness + ambientLight * (1.0F - brightness) - 0.5F;
    }

    private static float entityFloat(Actor actor) {
        float result = actor.nextEntityFloat();
        if (!(result >= 0.0F && result < 1.0F)) throw new IllegalArgumentException("Invalid entity random float");
        return result;
    }

    private static double entityDouble(Actor actor) {
        double result = actor.nextEntityDouble();
        if (!(result >= 0.0D && result < 1.0D)) throw new IllegalArgumentException("Invalid entity random double");
        return result;
    }

    private static int entityInt(Actor actor, int bound) {
        int result = actor.nextEntityInt(bound);
        if (result < 0 || result >= bound) throw new IllegalArgumentException("Invalid bounded entity random integer");
        return result;
    }

    private static int mappedFloor(double value) {
        int integer = (int) value;
        return value < (double) integer ? integer - 1 : integer;
    }

    /** Mth.atan2 的查表/单次逆平方根校正，不能用 Math.atan2 替代 */
    static double mappedAtan2(double y, double x) {
        double squared = x * x + y * y;
        if (Double.isNaN(squared)) return Double.NaN;
        boolean negativeY = y < 0.0D;
        if (negativeY) y = -y;
        boolean negativeX = x < 0.0D;
        if (negativeX) x = -x;
        boolean swap = y > x;
        if (swap) {
            double previousX = x;
            x = y;
            y = previousX;
        }
        double inverse = fastInvSqrt(squared);
        x *= inverse;
        y *= inverse;
        double biased = FRAC_BIAS + y;
        int index = (int) Double.doubleToRawLongBits(biased);
        double angle = ASIN_TABLE[index], cosine = COS_TABLE[index];
        double rounded = biased - FRAC_BIAS;
        double error = y * cosine - x * rounded;
        double correction = (6.0D + error * error) * error * 0.16666666666666666D;
        double result = angle + correction;
        if (swap) result = 1.5707963267948966D - result;
        if (negativeX) result = 3.141592653589793D - result;
        if (negativeY) result = -result;
        return result;
    }

    private static double fastInvSqrt(double value) {
        double half = 0.5D * value;
        long bits = Double.doubleToRawLongBits(value);
        bits = 6910469410427058090L - (bits >> 1);
        value = Double.longBitsToDouble(bits);
        value *= 1.5D - half * value * value;
        return value;
    }
}
