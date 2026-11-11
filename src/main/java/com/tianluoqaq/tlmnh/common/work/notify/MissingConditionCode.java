package com.tianluoqaq.tlmnh.common.work.notify;

/** 维护仓与育种模式全部缺件条件枚举码及对应翻译键 */
public enum MissingConditionCode {

    MISSING_MAINTENANCE_BELL(MissingConditionIds.KEY_MISSING_MAINT_BELL),
    UNBOUND_MAINTENANCE_BELL(MissingConditionIds.KEY_UNBOUND_MAINT_BELL),
    WRONG_DIMENSION_MAINTENANCE_BELL(MissingConditionIds.KEY_WRONG_DIM_MAINT_BELL),
    MISSING_GT_WRENCH_OFFHAND(MissingConditionIds.KEY_MISSING_GT_WRENCH),

    MISSING_BREEDING_BELL(MissingConditionIds.KEY_MISSING_BREED_BELL),
    UNBOUND_BREEDING_BELL(MissingConditionIds.KEY_UNBOUND_BREED_BELL),
    WRONG_DIMENSION_BREEDING_BELL(MissingConditionIds.KEY_WRONG_DIM_BREED_BELL),
    INVALID_PLOT_OBSTRUCTED(MissingConditionIds.KEY_PLOT_OBSTRUCTED),
    MISSING_TARGET_CONFIG(MissingConditionIds.KEY_MISSING_TARGET_CFG),
    UNSUPPORTED_IN_WORLD_CROP(MissingConditionIds.KEY_UNSUPPORTED_CROP),
    MISSING_CROPSNH_SPADE(MissingConditionIds.KEY_MISSING_SPADE),
    MISSING_CROP_STICKS(MissingConditionIds.KEY_MISSING_STICKS),
    MISSING_PARENT_SEEDS(MissingConditionIds.KEY_MISSING_PARENTS),
    MISSING_SOIL_BLOCK(MissingConditionIds.KEY_MISSING_SOIL),
    MISSING_SUBSOIL_BLOCK(MissingConditionIds.KEY_MISSING_SUBSOIL),
    MISSING_HARVEST_TOOL(MissingConditionIds.KEY_MISSING_HARVEST_TOOL);

    private final String translationKey;

    MissingConditionCode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }
}
