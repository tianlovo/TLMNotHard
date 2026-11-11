package com.tianluoqaq.tlmnh.common.ai.faithful.follow;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.Vec3;

/**
 * 按固定 MC1.20.1 DefaultRandomPos/RandomPos/GoalUtils 的实际字节码适配候选生成
 * 只生成坐标，不查询或安装路径，不运行导航，不提供完整自然 F/N 准入
 */
public final class FollowFallbackSampler {

    private static final double[] ASIN_TABLE = new double[FaithfulFollowTuning.FOLLOW_FALLBACK_DOMAIN_PARAM];
    private static final double[] COS_TABLE = new double[FaithfulFollowTuning.FOLLOW_FALLBACK_DOMAIN_PARAM];

    static {
        for (int index = 0; index < FaithfulFollowTuning.FOLLOW_FALLBACK_DOMAIN_PARAM; index++) {
            double angle = Math.asin((double) index / FaithfulFollowTuning.FOLLOW_FALLBACK_ANGLE);
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
        Objects.requireNonNull(actor, FaithfulFollowIds.ID_ACTOR);
        Objects.requireNonNull(target, FaithfulFollowIds.ID_TARGET);
        if (horizontalRange < 0
            || horizontalRange
                > (Integer.MAX_VALUE - 1) / FaithfulFollowTuning.FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM
            || verticalRange < 0
            || verticalRange
                > (Integer.MAX_VALUE - 1) / FaithfulFollowTuning.FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM
            || !Double.isFinite(angle)
            || angle < 0)
            throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_OUTSIDE_BOUNDED_FALLBACK_ARGUMENTS);
        Vec3 position = Objects.requireNonNull(actor.position(), FaithfulFollowDiagnostics.MSG_ACTOR_POSITION);
        double directionX = target.x - position.x, directionZ = target.z - position.z;
        if (!Double.isFinite(directionX) || !Double.isFinite(directionZ))
            throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_NON_FINITE_FALLBACK_DIRECTION);
        boolean restricted = mobRestricted(actor, horizontalRange);
        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos best = null;
        for (int attempt = 0; attempt < FaithfulFollowTuning.ATTEMPTS; attempt++) {
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
        return best == null ? null
            : new Vec3(
                best.x + FaithfulFollowTuning.CENTER_OFFSET_D,
                best.y,
                best.z + FaithfulFollowTuning.CENTER_OFFSET_D);
    }

    static boolean mobRestricted(Actor actor, int range) {
        if (!actor.hasRestriction()) return false;
        BlockPos center = Objects
            .requireNonNull(actor.restrictionCenter(), FaithfulFollowDiagnostics.MSG_RESTRICTION_CENTER);
        Vec3 position = Objects.requireNonNull(actor.position(), FaithfulFollowDiagnostics.MSG_ACTOR_POSITION);
        double distance = (double) (actor.restrictionRadius() + (float) range)
            + FaithfulFollowTuning.FOLLOW_FALLBACK_DISTANCE;
        double x = center.x + FaithfulFollowTuning.CENTER_OFFSET_D - position.x;
        double y = center.y + FaithfulFollowTuning.CENTER_OFFSET_D - position.y;
        double z = center.z + FaithfulFollowTuning.CENTER_OFFSET_D - position.z;
        return x * x + y * y + z * z < distance * distance;
    }

    static BlockPos direction(Actor actor, int horizontalRange, int verticalRange, double x, double z, double angle) {
        double base = mappedAtan2(z, x) - FaithfulFollowTuning.HALF_PI_FLOAT;
        // 两个乘减先在 float 完成，随后才拓宽为 double
        double heading = base + (double) (FaithfulFollowTuning.FOLLOW_FALLBACK_DIRECTION_FACTOR * entityFloat(actor)
            - FaithfulFollowTuning.DRY_PLATFORM_LIGHT_PATHFINDING_COST_SECOND_FACTOR) * angle;
        double radius = Math.sqrt(entityDouble(actor)) * (double) FaithfulFollowDerivedTuning.SQRT_OF_TWO
            * (double) horizontalRange;
        double dx = -radius * Math.sin(heading), dz = radius * Math.cos(heading);
        // 方形范围外只消费 float/double，不抽 y，也不处理 restriction
        if (Math.abs(dx) > horizontalRange || Math.abs(dz) > horizontalRange) return null;
        int dy = entityInt(
            actor,
            FaithfulFollowTuning.FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM * verticalRange + 1) - verticalRange;
        return new BlockPos(mappedFloor(dx), dy, mappedFloor(dz));
    }

    static BlockPos absolutePosition(Actor actor, int range, BlockPos offset) {
        int x = offset.x, z = offset.z;
        if (actor.hasRestriction() && range > FaithfulFollowTuning.NEXT_WORLD_INT_SINGLE_UNIT) {
            BlockPos center = Objects
                .requireNonNull(actor.restrictionCenter(), FaithfulFollowDiagnostics.MSG_RESTRICTION_CENTER);
            x += Objects.requireNonNull(actor.position(), FaithfulFollowDiagnostics.MSG_ACTOR_POSITION).x > center.x
                ? -entityInt(actor, range / FaithfulFollowTuning.FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM)
                : entityInt(actor, range / FaithfulFollowTuning.FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM);
            z += Objects.requireNonNull(actor.position(), FaithfulFollowDiagnostics.MSG_ACTOR_POSITION).z > center.z
                ? -entityInt(actor, range / FaithfulFollowTuning.FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM)
                : entityInt(actor, range / FaithfulFollowTuning.FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM);
        }
        Vec3 position = Objects.requireNonNull(actor.position(), FaithfulFollowDiagnostics.MSG_ACTOR_POSITION);
        return new BlockPos(
            mappedFloor((double) x + position.x),
            mappedFloor((double) offset.y + position.y),
            mappedFloor((double) z + position.z));
    }

    /** LevelReader 的光照路径评分；调用方仍须核 grass、真实 raw light 与维度 ambient 来源 */
    public static float lightPathfindingCost(int rawBrightness, float ambientLight) {
        if (rawBrightness < 0 || rawBrightness > FaithfulFollowTuning.TARGET_RAW_THRESHOLD
            || !Float.isFinite(ambientLight)
            || ambientLight < 0
            || ambientLight > FaithfulFollowTuning.NEXT_WORLD_INT_SINGLE_UNIT)
            throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_OUTSIDE_VERIFIED_LIGHT_INPUTS);
        float level = (float) rawBrightness / FaithfulFollowTuning.FOLLOW_FALLBACK_LEVEL;
        float brightness = level / (FaithfulFollowTuning.FOLLOW_FALLBACK_LIGHT_PATHFINDING_COST_FACTOR
            - FaithfulFollowTuning.FOLLOW_FALLBACK_BRIGHTNESS * level);
        return brightness
            + ambientLight * (FaithfulFollowTuning.DRY_PLATFORM_LIGHT_PATHFINDING_COST_SECOND_FACTOR - brightness)
            - FaithfulFollowTuning.HALF_FACTOR_F;
    }

    private static float entityFloat(Actor actor) {
        float result = actor.nextEntityFloat();
        if (!(result >= 0.0F && result < FaithfulFollowTuning.DRY_PLATFORM_LIGHT_PATHFINDING_COST_SECOND_FACTOR))
            throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_INVALID_ENTITY_RANDOM_FLOAT);
        return result;
    }

    private static double entityDouble(Actor actor) {
        double result = actor.nextEntityDouble();
        if (!(result >= 0.0D && result < FaithfulFollowTuning.FOLLOW_FALLBACK_DISTANCE))
            throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_INVALID_ENTITY_RANDOM_DOUBLE);
        return result;
    }

    private static int entityInt(Actor actor, int bound) {
        int result = actor.nextEntityInt(bound);
        if (result < 0 || result >= bound)
            throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_INVALID_BOUNDED_ENTITY_RANDOM_INTEGER);
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
        double biased = FaithfulFollowDerivedTuning.FRAC_BIAS + y;
        int index = (int) Double.doubleToRawLongBits(biased);
        double angle = ASIN_TABLE[index], cosine = COS_TABLE[index];
        double rounded = biased - FaithfulFollowDerivedTuning.FRAC_BIAS;
        double error = y * cosine - x * rounded;
        double correction = (FaithfulFollowTuning.FOLLOW_FALLBACK_MAPPED_ATAN2_FACTOR + error * error) * error
            * FaithfulFollowTuning.FOLLOW_FALLBACK_CORRECTION;
        double result = angle + correction;
        if (swap) result = FaithfulFollowTuning.FOLLOW_FALLBACK_RESULT - result;
        if (negativeX) result = FaithfulFollowTuning.FOLLOW_FALLBACK_RESULT_2 - result;
        if (negativeY) result = -result;
        return result;
    }

    private static double fastInvSqrt(double value) {
        double half = FaithfulFollowTuning.CENTER_OFFSET_D * value;
        long bits = Double.doubleToRawLongBits(value);
        bits = FaithfulFollowTuning.FOLLOW_FALLBACK_BITS - (bits >> FaithfulFollowTuning.NEXT_WORLD_INT_SINGLE_UNIT);
        value = Double.longBitsToDouble(bits);
        value *= FaithfulFollowTuning.FOLLOW_FALLBACK_FAST_INV_SQRT_FACTOR - half * value * value;
        return value;
    }
}
