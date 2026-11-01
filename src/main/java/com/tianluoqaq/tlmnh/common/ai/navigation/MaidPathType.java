package com.tianluoqaq.tlmnh.common.ai.navigation;

/** 现代节点类型及默认代价；枚举次序参与占据范围内的同代价选择 */
public enum MaidPathType {

    BLOCKED(-1),
    OPEN(0),
    WALKABLE(0),
    WALKABLE_DOOR(0),
    TRAPDOOR(0),
    POWDER_SNOW(-1),
    DANGER_POWDER_SNOW(0),
    FENCE(-1),
    LAVA(-1),
    WATER(8),
    WATER_BORDER(8),
    RAIL(0),
    UNPASSABLE_RAIL(-1),
    DANGER_FIRE(8),
    DAMAGE_FIRE(16),
    DANGER_OTHER(8),
    DAMAGE_OTHER(-1),
    DOOR_OPEN(0),
    DOOR_WOOD_CLOSED(-1),
    DOOR_IRON_CLOSED(-1),
    BREACH(4),
    LEAVES(-1),
    STICKY_HONEY(8),
    COCOA(0),
    DAMAGE_CAUTIOUS(0);

    public final float defaultCost;

    MaidPathType(float cost) {
        defaultCost = cost;
    }

    public static float[] maidCosts() {
        float[] costs = new float[values().length];
        for (var type : values()) costs[type.ordinal()] = type.defaultCost;
        costs[COCOA.ordinal()] = -1;
        return costs;
    }
}
