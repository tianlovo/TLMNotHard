package com.tianluoqaq.tlmnh.compat.ic2.common.armor;

/** IC2 护甲、电量管理器、辐射药水与锡罐反射定义 */
public final class Ic2ArmorReflectionSpec {

    public static final String ELECTRIC_ITEM_CLASS = "ic2.api.item.ElectricItem";
    public static final String MANAGER_INTERFACE = "ic2.api.item.IElectricItemManager";
    public static final String FLD_MANAGER = "manager";
    public static final String MTD_CAN_USE = "canUse";
    public static final String MTD_USE = "use";
    public static final String MTD_GET_CHARGE = "getCharge";

    public static final String NANO_SUIT_CLASS = "ic2.core.item.armor.ItemArmorNanoSuit";
    public static final String QUANTUM_SUIT_CLASS = "ic2.core.item.armor.ItemArmorQuantumSuit";
    public static final String HAZMAT_CLASS = "ic2.core.item.armor.ItemArmorHazmat";
    public static final String MTD_HAS_COMPLETE_HAZMAT = "hasCompleteHazmat";

    public static final String IC2_POTION_CLASS = "ic2.core.IC2Potion";
    public static final String FLD_RADIATION = "radiation";

    public static final String TIN_CAN_CLASS = "ic2.core.item.ItemTinCan";
    public static final String IC2_ITEMS_CLASS = "ic2.core.Ic2Items";
    public static final String FLD_EMPTY_TIN_CAN = "tinCan";

    public static final String NBT_NIGHTVISION = "Nightvision";

    private Ic2ArmorReflectionSpec() {}
}
