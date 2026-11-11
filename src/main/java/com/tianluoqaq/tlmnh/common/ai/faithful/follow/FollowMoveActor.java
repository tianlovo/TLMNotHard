package com.tianluoqaq.tlmnh.common.ai.faithful.follow;

/**
 * 现代 MoveToTargetSink 的受控依赖边界，不包含原生导航或随机候选算法
 * 调用者须持有效运行授权并独占本合同的记忆/导航；跨策略所有权拒绝由外层处理
 */
public interface FollowMoveActor {

    boolean isWalkTargetRegistered();

    boolean isPathRegistered();

    boolean isCantReachSinceRegistered();

    WalkTarget getWalkTarget();

    Path getPathMemory();

    Long getCantReachSince();

    void eraseWalkTarget();

    /** null与原版Brain.setMemory(PATH,null)一样表示擦除 */
    void setPathMemory(Path path);

    void eraseCantReachSince();

    void setCantReachSince(long gameTime);

    BlockPos blockPosition();

    /** 实际世界时钟，独立于Behavior方法传入的gameTime */
    long worldGameTime();

    /** 只消费实际世界RNG，不混用实体导航/候选RNG */
    int nextWorldInt(int bound);

    Path createPath(BlockPos target, int accuracy);

    Path createPath(Vec3 target, int accuracy);

    /**
     * 对应现代DefaultRandomPos.getPosTowards；受控重放消费原版实际结果
     * 自然适配采用独立的现代采样、过滤与评分实现
     */
    Vec3 findFallbackTowards(int horizontalRange, int verticalRange, Vec3 target, double angle);

    Path navigationPath();

    boolean navigationIsDone();

    boolean navigationIsStuck();

    boolean moveTo(Path path, double speedModifier);

    /** 专属原 sink 运行域内的无条件停步接口，保持策略内部边界清晰 */
    void stopNavigation();

    interface WalkTarget {

        BlockPos currentBlockPosition();

        default Vec3 currentPosition() {
            var pos = currentBlockPosition();
            return new Vec3(
                pos.x + FaithfulFollowTuning.FOLLOW_MOVE_VEC3_FIRST_FACTOR,
                pos.y + FaithfulFollowTuning.FOLLOW_MOVE_VEC3_FIRST_FACTOR,
                pos.z + FaithfulFollowTuning.FOLLOW_MOVE_VEC3_FIRST_FACTOR);
        }

        float getSpeedModifier();

        int getCloseEnoughDistance();

        /** 仅当 EntityTracker 跟踪的实体目标处于旁观模式时判定为 true */
        boolean isEntityTrackerSpectator();
    }

    interface Path {

        /** 底层实际路径对象实例，保持持久化单例引用 */
        Object getIdentity();

        /** 现代路径的实际canReach，不以原生平台的noPath或二维终点判据猜测 */
        boolean canReach();
    }

    final class BlockPos {

        public final int x, y, z;

        public BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int manhattanDistance(BlockPos other) {
            // 现代Vec3i先将各轴绝对差转float，再相加并转int，保留原转换次序
            float dx = Math.abs(other.x - x);
            float dy = Math.abs(other.y - y);
            float dz = Math.abs(other.z - z);
            return (int) (dx + dy + dz);
        }

        public double distanceSquared(BlockPos other) {
            double dx = (double) x - other.x;
            double dy = (double) y - other.y;
            double dz = (double) z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    final class Vec3 {

        public final double x, y, z;

        public Vec3(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_NON_FINITE_FALLBACK_POSITION);
            }
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
