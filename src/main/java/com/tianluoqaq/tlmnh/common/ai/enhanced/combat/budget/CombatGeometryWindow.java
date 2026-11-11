package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget;

/** 只重分配原9×6×9几何窗；保留49站立节点的身体余量，不读取世界 */
public final class CombatGeometryWindow {

    private CombatGeometryWindow() {}

    public static int horizontalOrigin(double self, double observedTarget, boolean currentVisible, double halfWidth) {
        int center = (int) Math.floor(self), origin = center - CombatBudgetTuning.RADIUS;
        if (!currentVisible || !Double.isFinite(observedTarget)
            || !Double.isFinite(halfWidth)
            || halfWidth < 0
            || halfWidth >= CombatBudgetTuning.MAX_HALF_WIDTH_THRESHOLD) return origin;
        int target = (int) Math.floor(observedTarget);
        // 一格偏移仍包含center±3每个节点中心及半宽，不给窗外射线提供CLEAR
        return target < origin ? origin - 1 : target >= origin + CombatBudgetTuning.WIDTH ? origin + 1 : origin;
    }
}
