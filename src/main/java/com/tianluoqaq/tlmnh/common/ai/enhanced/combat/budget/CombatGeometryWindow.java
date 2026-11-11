package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget;

/** 只重分配原9×6×9几何窗；保留49站立节点的身体余量，不读取世界 */
public final class CombatGeometryWindow {

    public static final int WIDTH = 9, HEIGHT = 6, FLOOR_DEPTH = 2;
    private static final int RADIUS = WIDTH / 2;

    private CombatGeometryWindow() {}

    public static int horizontalOrigin(double self, double observedTarget, boolean currentVisible, double halfWidth) {
        int center = (int) Math.floor(self), origin = center - RADIUS;
        if (!currentVisible || !Double.isFinite(observedTarget)
            || !Double.isFinite(halfWidth)
            || halfWidth < 0
            || halfWidth >= .5) return origin;
        int target = (int) Math.floor(observedTarget);
        // 一格偏移仍包含center±3每个节点中心及半宽，不给窗外射线提供CLEAR
        return target < origin ? origin - 1 : target >= origin + WIDTH ? origin + 1 : origin;
    }
}
