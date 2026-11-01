package com.tianluoqaq.tlmnh.common.ai.faithful;

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
     * 自然适配须另实现并验证现代采样/过滤/评分，不得用legacy近似替代
     */
    Vec3 findFallbackTowards(int horizontalRange, int verticalRange, Vec3 target, double angle);

    Path navigationPath();

    boolean navigationIsDone();

    boolean navigationIsStuck();

    boolean moveTo(Path path, double speedModifier);

    /** 原sink合法运行域内无条件stop；不是A1跨策略取消接口 */
    void stopNavigation();

    interface WalkTarget {

        BlockPos currentBlockPosition();

        default Vec3 currentPosition() {
            var pos = currentBlockPosition();
            return new Vec3(pos.x + .5, pos.y + .5, pos.z + .5);
        }

        float getSpeedModifier();

        int getCloseEnoughDistance();

        /** 仅EntityTracker实际目标是旁观者时为true，普通整格目标不是旁观目标 */
        boolean isEntityTrackerSpectator();
    }

    interface Path {

        /** 底层实际路径对象身份，非null且不能每次读取创建新身份 */
        Object getIdentity();

        /** 现代路径的实际canReach，不以RC2的noPath或二维终点判据猜测 */
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
                throw new IllegalArgumentException("Non-finite fallback position");
            }
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
