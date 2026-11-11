package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;

/** 已付费普通身体首段的值证明；不持有原生path或世界 */
public final class CombatMovementSegment {

    public final CombatMovementFact.Node node;
    public final Vec from, to;
    public final long geometryAt;

    public CombatMovementSegment(CombatMovementFact.Node node, Vec from, long geometryAt) {
        this.node = node;
        this.from = from;
        this.to = node.foot;
        this.geometryAt = geometryAt;
    }

    public boolean ordinaryAdjacent() {
        return Math.abs(Math.floor(from.x) - Math.floor(to.x)) + Math.abs(Math.floor(from.z) - Math.floor(to.z)) <= 1
            && Math.abs(from.y - to.y) <= .500001;
    }

    public boolean fresh(long now) {
        return ordinaryAdjacent() && node.current(now)
            && now >= geometryAt
            && now - geometryAt <= CombatSnapshot.GEOMETRY_TTL;
    }

    /** 原生节点Y是既有四舍五入坐标；真正半砖足点另由sameStandingCell证明 */
    public boolean nativePoint(int x, int y, int z, boolean end) {
        Vec point = end ? to : from;
        return x == (int) Math.floor(point.x) && y == (int) Math.floor(point.y + .5) && z == (int) Math.floor(point.z);
    }

    /** 仅接原生适配器已先限长提取的点；未认证raw必须传index0，已认证raw只重证尚未消费的后缀 */
    public static final class NativeCheck {

        public String result = "NOT_EVALUATED";
        public int failedAt = -1, spent;
    }

    public boolean acceptsNative(int[] coordinates, int count, int index, CombatWorkQuota quota) {
        return acceptsNative(coordinates, count, index, quota, null);
    }

    public boolean acceptsNative(int[] coordinates, int count, int index, CombatWorkQuota quota,
        NativeCheck diagnostic) {
        if (count < 1 || count > 2 || index < 0 || index > count || coordinates.length != count * 3) {
            if (diagnostic != null) diagnostic.result = "BOUNDS";
            return false;
        }
        for (int i = Math.min(index, count - 1); i < count; i++) {
            if (!quota.spend(CombatWorkQuota.Work.COLLISION)) {
                if (diagnostic != null) {
                    diagnostic.result = "COLLISION_BUDGET";
                    diagnostic.failedAt = i;
                }
                return false;
            }
            if (diagnostic != null) diagnostic.spent++;
            if (!nativePoint(coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2], i == count - 1)) {
                if (diagnostic != null) {
                    diagnostic.result = "POINT_MISMATCH";
                    diagnostic.failedAt = i;
                }
                return false;
            }
        }
        if (diagnostic != null) diagnostic.result = "ACCEPTED";
        return true;
    }

    public boolean currentPosition(Vec actual) {
        if (!Double.isFinite(actual.x + actual.y + actual.z)) return false;
        if (node.role == CombatMovementFact.Role.SAME_CELL_ALIGNMENT)
            return CombatMovementFact.sameStandingCell(actual, node.anchor);
        double minX = Math.min(from.x, to.x) - .5, maxX = Math.max(from.x, to.x) + .5;
        double minZ = Math.min(from.z, to.z) - .5, maxZ = Math.max(from.z, to.z) + .5;
        return actual.x >= minX && actual.x <= maxX
            && actual.z >= minZ
            && actual.z <= maxZ
            && actual.y >= Math.min(from.y, to.y) - .0001
            && actual.y <= Math.max(from.y, to.y) + .0001;
    }
}
