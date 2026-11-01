/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/PickType.java, MIT
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

public enum PickType {

    ONLY_ITEM(true, false),
    ONLY_XP(false, true),
    ALL(true, true);

    private final boolean pickItem, pickXp;

    PickType(boolean pickItem, boolean pickXp) {
        this.pickItem = pickItem;
        this.pickXp = pickXp;
    }

    public boolean canPickItem() {
        return pickItem;
    }

    public boolean canPickXp() {
        return pickXp;
    }

    public static String getTransKey(PickType type) {
        return "gui.touhou_little_maid.maid_config.value." + switch (type) {
            case ONLY_ITEM -> "item";
            case ONLY_XP -> "xp";
            case ALL -> "all";
        };
    }

    public static PickType getNextPickType(PickType type) {
        return values()[(type.ordinal() + 1) % values().length];
    }

    public static PickType getPreviousPickType(PickType type) {
        return values()[(type.ordinal() + values().length - 1) % values().length];
    }
}
