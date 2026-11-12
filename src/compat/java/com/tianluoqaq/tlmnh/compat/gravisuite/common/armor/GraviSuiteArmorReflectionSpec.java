package com.tianluoqaq.tlmnh.compat.gravisuite.common.armor;

/** GraviSuiteNeo 1.3.16 的胸甲灭火规则与依赖 API 描述 */
public final class GraviSuiteArmorReflectionSpec {

    public static final String NEO_MOD_ID = "gravisuiteneo";
    public static final String ADVANCED_NANO = "gravisuite.ItemAdvancedNanoChestPlate";
    public static final String GRAVITY_CHEST = "gravisuite.ItemGraviChestPlate";
    public static final String ELECTRIC_ITEM = "ic2.api.item.ElectricItem";
    public static final String MANAGER_INTERFACE = "ic2.api.item.IElectricItemManager";
    public static final String MANAGER_FIELD = "manager";
    public static final String DISCHARGE = "discharge";
    public static final String CAN_USE = "canUse";
    public static final String GUARD = "gravisuite_armor";
    public static final double NANO_EXTINGUISH_COST = 50000.0D;
    public static final double GRAVITY_EXTINGUISH_COST = 1000.0D;
    public static final int NANO_PERIOD = 20;

    private GraviSuiteArmorReflectionSpec() {}
}
