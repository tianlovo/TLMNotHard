package com.tianluoqaq.tlmnh.compat.backpack.common.inventory;

/** Eydamos 背包反射类名与方法名定义 */
public final class EydamosBackpackReflectionSpec {

    public static final String ITEM_BASE_CLASS = "de.eydamos.backpack.item.ItemBackpackBase";
    public static final String UTIL_CLASS = "de.eydamos.backpack.util.BackpackUtil";
    public static final String SAVE_CLASS = "de.eydamos.backpack.saves.BackpackSave";
    public static final String INVENTORY_CLASS = "de.eydamos.backpack.inventory.InventoryBackpack";
    public static final String SLOT_CLASS = "de.eydamos.backpack.inventory.slot.SlotBackpack";
    public static final String GUI_HELPER_CLASS = "de.eydamos.backpack.helper.GuiHelper";

    public static final String METHOD_IS_ENDER = "isEnderBackpack";
    public static final String METHOD_GET_UUID = "getUUID";
    public static final String METHOD_GET_SIZE = "getSize";
    public static final String METHOD_SAVE = "save";
    public static final String METHOD_READ_FROM_NBT = "readFromNBT";
    public static final String METHOD_WRITE_TO_NBT = "writeToNBT";
    public static final String METHOD_IS_OPENED_BY_OTHER = "isOpenedByOtherPlayer";

    private EydamosBackpackReflectionSpec() {}
}
