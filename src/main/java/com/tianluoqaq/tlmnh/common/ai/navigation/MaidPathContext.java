package com.tianluoqaq.tlmnh.common.ai.navigation;

/** 查询期间固定的实际身体与能力；代价表属于实体导航而非全局静态状态 */
public final class MaidPathContext {

    public final MaidPathRegion region;
    public final double x, y, z;
    public final float width, height, stepHeight;
    public final int maxFall;
    public final float[] costs;
    public final boolean onGround;

    public MaidPathContext(MaidPathRegion region, double x, double y, double z, float width, float height,
        float stepHeight, int maxFall, boolean onGround, float[] costs) {
        this.region = region;
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = width;
        this.height = height;
        this.stepHeight = stepHeight;
        this.maxFall = maxFall;
        this.onGround = onGround;
        this.costs = costs;
    }

    public float cost(MaidPathType type) {
        return costs[type.ordinal()];
    }

    public void cost(MaidPathType type, float cost) {
        costs[type.ordinal()] = cost;
    }

    public static int floor(double value) {
        return (int) Math.floor(value);
    }
}
