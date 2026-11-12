package com.tianluoqaq.tlmnh.compat.galacticraftcore.common.space;

/** Galacticraft 3.4.37-GTNH 维度大气、供氧、隔热、缺氧事件与伤害源反射定义 */
public final class GalacticraftSpaceReflectionSpec {

    public static final String WORLD_PROVIDER_IFC = "micdoodle8.mods.galacticraft.api.world.IGalacticraftWorldProvider";
    public static final String MTD_HAS_BREATHABLE_ATMOSPHERE = "hasBreathableAtmosphere";
    public static final String MTD_GET_THERMAL_MODIFIER = "getThermalLevelModifier";

    public static final String OXYGEN_UTIL_CLASS = "micdoodle8.mods.galacticraft.core.util.OxygenUtil";
    public static final String MTD_IN_BREATHABLE_AIR = "isAABBInBreathableAirBlock";
    public static final String MTD_GET_DRAIN_SPACING = "getDrainSpacing";
    public static final String MTD_NO_ATMOSPHERIC_COMBUSTION = "noAtmosphericCombustion";

    public static final String ITEM_OXYGEN_MASK = "micdoodle8.mods.galacticraft.core.items.ItemOxygenMask";
    public static final String ITEM_OXYGEN_GEAR = "micdoodle8.mods.galacticraft.core.items.ItemOxygenGear";
    public static final String ITEM_OXYGEN_TANK = "micdoodle8.mods.galacticraft.core.items.ItemOxygenTank";
    public static final String ITEM_INFINITE_OXYGEN = "micdoodle8.mods.galacticraft.core.items.ItemCanisterOxygenInfinite";
    public static final String OXYGEN_SUPPLY_IFC = "micdoodle8.mods.galacticraft.api.item.IItemOxygenSupply";
    public static final String MTD_DISCHARGE = "discharge";
    public static final String MTD_GET_OXYGEN_STORED = "getOxygenStored";

    public static final String THERMAL_IFC = "micdoodle8.mods.galacticraft.api.item.IItemThermal";
    public static final String MTD_GET_THERMAL_STRENGTH = "getThermalStrength";
    public static final String MTD_IS_VALID_FOR_SLOT = "isValidForSlot";

    public static final String BREATHABLE_ARMOR_IFC = "micdoodle8.mods.galacticraft.api.item.IBreathableArmor";
    public static final String GEAR_TYPE_ENUM = "micdoodle8.mods.galacticraft.api.item.IBreathableArmor$EnumGearType";
    public static final String MTD_HANDLE_GEAR_TYPE = "handleGearType";

    public static final String SUFFOCATION_PRE_CLASS = "micdoodle8.mods.galacticraft.api.event.oxygen.GCCoreOxygenSuffocationEvent$Pre";
    public static final String SUFFOCATION_POST_CLASS = "micdoodle8.mods.galacticraft.api.event.oxygen.GCCoreOxygenSuffocationEvent$Post";

    public static final String DAMAGE_SOURCE_GC_CLASS = "micdoodle8.mods.galacticraft.core.util.DamageSourceGC";
    public static final String FLD_OXYGEN_SUFFOCATION = "oxygenSuffocation";
    public static final String FLD_THERMAL_DAMAGE = "thermal";

    public static final String CONFIG_CORE_CLASS = "micdoodle8.mods.galacticraft.core.util.ConfigManagerCore";
    public static final String FLD_SUFFOCATION_COOLDOWN = "suffocationCooldown";
    public static final String FLD_HARD_MODE = "hardMode";
    public static final String FLD_SUFFOCATION_DAMAGE = "suffocationDamage";

    private GalacticraftSpaceReflectionSpec() {}
}
