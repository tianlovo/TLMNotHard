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
        return Math.abs(Math.floor(from.x) - Math.floor(to.x)) + Math.abs(Math.floor(from.z) - Math.floor(to.z))
            <= CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
            && Math.abs(from.y - to.y) <= CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_FACTOR;
    }

    public boolean fresh(long now) {
        return ordinaryAdjacent() && node.current(now)
            && now >= geometryAt
            && now - geometryAt <= CombatTerrainTuning.GEOMETRY_TTL;
    }

    /** 原生节点Y是既有四舍五入坐标；真正半砖足点另由sameStandingCell证明 */
    public boolean nativePoint(int x, int y, int z, boolean end) {
        Vec point = end ? to : from;
        return x == (int) Math.floor(point.x)
            && y == (int) Math.floor(point.y + CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR)
            && z == (int) Math.floor(point.z);
    }

    /** 仅接原生适配器已先限长提取的点；未认证raw必须传index0，已认证raw只重证尚未消费的后缀 */
    public static final class NativeCheck {

        public String result = CombatTerrainIds.KEY_NOT_EVALUATED;
        public int failedAt = CombatTerrainTuning.UNSET_OR_NOT_FOUND, spent;
    }

    public boolean acceptsNative(int[] coordinates, int count, int index, CombatWorkQuota quota) {
        return acceptsNative(coordinates, count, index, quota, null);
    }

    public boolean acceptsNative(int[] coordinates, int count, int index, CombatWorkQuota quota,
        NativeCheck diagnostic) {
        if (count < CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
            || count > CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM
            || index < 0
            || index > count
            || coordinates.length != count * CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM) {
            if (diagnostic != null) diagnostic.result = CombatTerrainIds.KEY_BOUNDS;
            return false;
        }
        for (int i = Math.min(index, count - 1); i < count; i++) {
            if (!quota.spend(CombatWorkQuota.Work.COLLISION)) {
                if (diagnostic != null) {
                    diagnostic.result = CombatTerrainIds.KEY_COLLISION_BUDGET;
                    diagnostic.failedAt = i;
                }
                return false;
            }
            if (diagnostic != null) diagnostic.spent++;
            if (!nativePoint(
                coordinates[i * CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM],
                coordinates[i * CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM + 1],
                coordinates[i * CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM
                    + CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM],
                i == count - 1)) {
                if (diagnostic != null) {
                    diagnostic.result = CombatTerrainIds.KEY_POINT_MISMATCH;
                    diagnostic.failedAt = i;
                }
                return false;
            }
        }
        if (diagnostic != null) diagnostic.result = CombatTerrainIds.KEY_ACCEPTED;
        return true;
    }

    public boolean currentPosition(Vec actual) {
        if (!Double.isFinite(actual.x + actual.y + actual.z)) return false;
        if (node.role == CombatMovementFact.Role.SAME_CELL_ALIGNMENT)
            return CombatMovementFact.sameStandingCell(actual, node.anchor);
        double minX = Math.min(from.x, to.x) - CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR,
            maxX = Math.max(from.x, to.x) + CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR;
        double minZ = Math.min(from.z, to.z) - CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR,
            maxZ = Math.max(from.z, to.z) + CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR;
        return actual.x >= minX && actual.x <= maxX
            && actual.z >= minZ
            && actual.z <= maxZ
            && actual.y >= Math.min(from.y, to.y) - CombatTerrainTuning.COMBAT_CONDITIONAL_SAME_FACTOR
            && actual.y <= Math.max(from.y, to.y) + CombatTerrainTuning.COMBAT_CONDITIONAL_SAME_FACTOR;
    }
}
