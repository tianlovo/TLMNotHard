package com.tianluoqaq.tlmnh.common.ai.navigation.model;

/** 现代节点类型及默认代价；枚举次序参与占据范围内的同代价选择 */
public enum MaidPathType {

    BLOCKED(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    OPEN(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE),
    WALKABLE(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE),
    WALKABLE_DOOR(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE),
    TRAPDOOR(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE),
    POWDER_SNOW(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    DANGER_POWDER_SNOW(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE),
    FENCE(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    LAVA(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    WATER(NavigationModelTuning.SECOND_BYTE_SHIFT),
    WATER_BORDER(NavigationModelTuning.SECOND_BYTE_SHIFT),
    RAIL(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE),
    UNPASSABLE_RAIL(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    DANGER_FIRE(NavigationModelTuning.SECOND_BYTE_SHIFT),
    DAMAGE_FIRE(NavigationModelTuning.PATH_TYPE_DAMAGE_FIRE_FIRST_PARAM),
    DANGER_OTHER(NavigationModelTuning.SECOND_BYTE_SHIFT),
    DAMAGE_OTHER(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    DOOR_OPEN(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE),
    DOOR_WOOD_CLOSED(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    DOOR_IRON_CLOSED(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    BREACH(NavigationModelTuning.PATH_TYPE_BREACH_FIRST_PARAM),
    LEAVES(NavigationModelTuning.UNSET_OR_NOT_FOUND),
    STICKY_HONEY(NavigationModelTuning.SECOND_BYTE_SHIFT),
    COCOA(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE),
    DAMAGE_CAUTIOUS(NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE);

    public final float defaultCost;

    MaidPathType(float cost) {
        defaultCost = cost;
    }

    public static float[] maidCosts() {
        float[] costs = new float[values().length];
        for (var type : values()) costs[type.ordinal()] = type.defaultCost;
        costs[COCOA.ordinal()] = NavigationModelTuning.UNSET_OR_NOT_FOUND;
        return costs;
    }
}
