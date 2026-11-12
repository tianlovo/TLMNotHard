package com.tianluoqaq.tlmnh.compat.galacticraftcore.common.space;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.common.MinecraftForge;

import com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.CompatExactMethod;
import com.tianluoqaq.tlmnh.compat.galacticraftcore.GalacticraftCoreCompat;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.relauncher.Side;

/**
 * Galacticraft 与 GalaxySpace 太空生存联动实现：
 * 支持饰品栏穿戴氧气面罩、供氧器、双氧气罐及 1/2 阶隔热内衬，严格对齐真实玩家氧气消耗与外星热力结算
 */
public final class GalacticraftSpaceSurvivalCompat implements SpaceSurvivalCompatProvider {

    public static final GalacticraftSpaceSurvivalCompat INSTANCE = new GalacticraftSpaceSurvivalCompat();

    private static final ThreadLocal<EntityLivingBase> CONTROLLER_PRE = new ThreadLocal<>();
    private static volatile Api cachedApi;
    private static volatile boolean resolved;

    private GalacticraftSpaceSurvivalCompat() {}

    @Override
    public void registerNativeEvents() {
        GalacticraftMaidOxygenEvents.register();
    }

    @Override
    public boolean isAvailable() {
        return OptionalApi.isModLoaded(GalacticraftCoreCompat.MOD_ID)
            && IntegrationGuard.available(Side.SERVER, GalacticraftSpaceIds.GUARD)
            && api() != null;
    }

    @Override
    public boolean isSpaceBaubleItem(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(GalacticraftCoreCompat.MOD_ID)
            || !IntegrationGuard.available(Side.SERVER, GalacticraftSpaceIds.GUARD)) {
            return false;
        }
        Api a = api();
        if (a == null) return false;
        Item item = stack.getItem();
        return a.maskClass.isInstance(item) || a.gearClass.isInstance(item)
            || a.tankClass.isInstance(item)
            || a.oxygenSupplyIfc.isInstance(item)
            || a.thermalIfc.isInstance(item);
    }

    @Override
    public AtmosphereSample sampleAtmosphere(World world) {
        if (!isAvailable() || world == null || world.provider == null) {
            return AtmosphereSample.BREATHABLE_OVERWORLD;
        }
        try {
            Api a = api();
            WorldProvider provider = world.provider;
            if (!a.worldProviderIfc.isInstance(provider)) {
                return AtmosphereSample.BREATHABLE_OVERWORLD;
            }
            boolean breathable = (Boolean) a.hasBreathableAtmosphere.invoke(provider);
            boolean noCombustion = (Boolean) a.noAtmosphericCombustion.invoke(null, provider);
            float thermalMod = (Float) a.getThermalModifier.invoke(provider);
            return new AtmosphereSample(true, breathable, noCombustion, thermalMod);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return AtmosphereSample.BREATHABLE_OVERWORLD;
        }
    }

    @Override
    public boolean isInBreathableAir(EntityLivingBase maid, boolean checkThermal) {
        if (!isAvailable() || maid == null) return true;
        try {
            return (Boolean) api().inBreathableAir.invoke(null, maid, checkThermal);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return true;
        }
    }

    @Override
    public OxygenCheckResult checkAndDrainOxygen(EntityLivingBase maid, List<ItemStack> baubleStacks,
        int ticksExisted) {
        if (!isAvailable() || maid == null) return OxygenCheckResult.INVALID;
        try {
            Api a = api();
            boolean hasMask = false;
            boolean hasGear = false;
            ItemStack tank1 = null;
            ItemStack tank2 = null;

            if (baubleStacks != null) {
                for (ItemStack s : baubleStacks) {
                    if (s == null || s.stackSize <= 0 || s.getItem() == null) continue;
                    Item item = s.getItem();
                    if (!hasMask && a.maskClass.isInstance(item)) {
                        hasMask = true;
                    } else if (!hasGear && a.gearClass.isInstance(item)) {
                        hasGear = true;
                    } else if (isOxygenTankItem(a, item)) {
                        if (tank1 == null) tank1 = s;
                        else if (tank2 == null) tank2 = s;
                    }
                }
            }

            boolean inf1 = isInfiniteTank(a, tank1);
            boolean inf2 = isInfiniteTank(a, tank2);
            if (inf1 || inf2) {
                return new OxygenCheckResult(true, true, GalacticraftSpaceTuning.ZERO_SPACING);
            }
            boolean avail1 = hasStoredOxygen(a, tank1);
            boolean avail2 = hasStoredOxygen(a, tank2);
            if (!avail1 && !avail2) return OxygenCheckResult.INVALID;

            int spacing = (Integer) a.getDrainSpacing.invoke(null, tank1, tank2);
            if (spacing > 0 && ticksExisted > 0 && (ticksExisted - 1) % spacing == 0) {
                if (avail1) drainOneUnit(a, tank1);
                else drainOneUnit(a, tank2);
            }
            return new OxygenCheckResult(
                hasMask && hasGear && (hasStoredOxygen(a, tank1) || hasStoredOxygen(a, tank2)),
                false,
                spacing);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return OxygenCheckResult.INVALID;
        }
    }

    @Override
    public float computeThermalStrength(List<ItemStack> baubleStacks) {
        if (!isAvailable() || baubleStacks == null || baubleStacks.isEmpty()) {
            return GalacticraftSpaceTuning.ZERO_FLOAT;
        }
        try {
            Api a = api();
            int[] bestPerSlot = new int[GalacticraftSpaceTuning.THERMAL_SLOT_COUNT];
            for (ItemStack s : baubleStacks) {
                if (s == null || s.stackSize <= 0 || s.getItem() == null) continue;
                Item item = s.getItem();
                if (!a.thermalIfc.isInstance(item)) continue;
                int strength = (Integer) a.getThermalStrength.invoke(item);
                if (strength <= 0) continue;
                for (int slot = 0; slot < GalacticraftSpaceTuning.THERMAL_SLOT_COUNT; slot++) {
                    if (strength > bestPerSlot[slot] && (Boolean) a.isValidForSlot.invoke(item, s, slot)) {
                        bestPerSlot[slot] = strength;
                        break;
                    }
                }
            }
            int sum = 0;
            for (int strength : bestPerSlot) {
                if (strength <= 0) return GalacticraftSpaceTuning.ZERO_FLOAT;
                sum += strength;
            }
            return (float) sum / GalacticraftSpaceTuning.THERMAL_SLOT_COUNT;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return GalacticraftSpaceTuning.ZERO_FLOAT;
        }
    }

    @Override
    public int thermalPieceCount(List<ItemStack> stacks) {
        if (!isAvailable() || stacks == null) return GalacticraftSpaceTuning.ZERO_SPACING;
        boolean[] present = new boolean[GalacticraftSpaceTuning.THERMAL_SLOT_COUNT];
        try {
            Api a = api();
            for (ItemStack stack : stacks) {
                if (stack == null || stack.stackSize <= 0 || !a.thermalIfc.isInstance(stack.getItem())) continue;
                for (int slot = 0; slot < present.length; slot++) {
                    if ((Boolean) a.isValidForSlot.invoke(stack.getItem(), stack, slot)) present[slot] = true;
                }
            }
            int count = 0;
            for (boolean value : present) if (value) count++;
            return count;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return GalacticraftSpaceTuning.ZERO_SPACING;
        }
    }

    @Override
    public boolean hardModeSuffocation() {
        if (!isAvailable()) return false;
        try {
            Class<?> cfg = Class.forName(GalacticraftSpaceReflectionSpec.CONFIG_CORE_CLASS);
            return cfg.getField(GalacticraftSpaceReflectionSpec.FLD_HARD_MODE)
                .getBoolean(null);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean postSuffocationPre(EntityLivingBase maid) {
        if (!isAvailable() || maid == null) return false;
        try {
            Event pre = (Event) api().suffocationPreCtor.newInstance(maid);
            EntityLivingBase previous = CONTROLLER_PRE.get();
            CONTROLLER_PRE.set(maid);
            try {
                return MinecraftForge.EVENT_BUS.post(pre);
            } finally {
                if (previous == null) CONTROLLER_PRE.remove();
                else CONTROLLER_PRE.set(previous);
            }
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return false;
        }
    }

    static boolean isControllerPre(EntityLivingBase entity) {
        return CONTROLLER_PRE.get() == entity;
    }

    @Override
    public void postSuffocationPost(EntityLivingBase maid) {
        if (!isAvailable() || maid == null) return;
        try {
            Event post = (Event) api().suffocationPostCtor.newInstance(maid);
            MinecraftForge.EVENT_BUS.post(post);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
        }
    }

    @Override
    public int suffocationCooldown() {
        if (!isAvailable()) return GalacticraftSpaceTuning.DEFAULT_COOLDOWN;
        try {
            int val = api().suffocationCooldownField.getInt(null);
            return val > 0 ? val : GalacticraftSpaceTuning.DEFAULT_COOLDOWN;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return GalacticraftSpaceTuning.DEFAULT_COOLDOWN;
        }
    }

    @Override
    public int suffocationDamageBase() {
        if (!isAvailable()) return GalacticraftSpaceTuning.DEFAULT_DAMAGE;
        try {
            int val = api().suffocationDamageField.getInt(null);
            return val > 0 ? val : GalacticraftSpaceTuning.DEFAULT_DAMAGE;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return GalacticraftSpaceTuning.DEFAULT_DAMAGE;
        }
    }

    @Override
    public boolean applySuffocationDamage(EntityLivingBase maid, float damage) {
        if (!isAvailable() || maid == null || damage <= GalacticraftSpaceTuning.ZERO_FLOAT) return false;
        try {
            DamageSource src = (DamageSource) api().oxygenSuffocationSource.get(null);
            return src != null && maid.attackEntityFrom(src, damage);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean applyThermalDamage(EntityLivingBase maid, float damage) {
        if (!isAvailable() || maid == null || damage <= GalacticraftSpaceTuning.ZERO_FLOAT) return false;
        try {
            DamageSource src = (DamageSource) api().thermalSource.get(null);
            return src != null && maid.attackEntityFrom(src, damage);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
            return false;
        }
    }

    private static boolean isOxygenTankItem(Api a, Item item) {
        return a.tankClass.isInstance(item) || a.oxygenSupplyIfc.isInstance(item);
    }

    private static boolean isInfiniteTank(Api a, ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        return a.infiniteTankClass.isInstance(stack.getItem()) || stack.getMaxDamage() == Integer.MAX_VALUE;
    }

    private static boolean hasStoredOxygen(Api a, ItemStack stack) throws ReflectiveOperationException {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        if (isInfiniteTank(a, stack)) return true;
        Item item = stack.getItem();
        if (a.oxygenSupplyIfc.isInstance(item)) {
            return (Integer) a.getOxygenStored.invoke(item, stack) > 0;
        }
        return stack.getMaxDamage() - stack.getItemDamage() > 0;
    }

    private static void drainOneUnit(Api a, ItemStack stack) throws ReflectiveOperationException {
        if (stack == null || isInfiniteTank(a, stack)) return;
        Item item = stack.getItem();
        if (a.oxygenSupplyIfc.isInstance(item)) {
            a.discharge.invoke(item, stack, GalacticraftSpaceTuning.OXYGEN_DISCHARGE_UNIT);
            return;
        }
        int nextDamage = Math.min(stack.getMaxDamage(), stack.getItemDamage() + 1);
        stack.setItemDamage(nextDamage);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Api api() {
        if (resolved) return cachedApi;
        synchronized (GalacticraftSpaceSurvivalCompat.class) {
            if (resolved) return cachedApi;
            try {
                ClassLoader cl = GalacticraftSpaceSurvivalCompat.class.getClassLoader();
                Class<?> wpIfc = Class.forName(GalacticraftSpaceReflectionSpec.WORLD_PROVIDER_IFC, false, cl);
                CompatExactMethod hasBreathable = CompatExactMethod
                    .virtual(wpIfc, GalacticraftSpaceReflectionSpec.MTD_HAS_BREATHABLE_ATMOSPHERE, boolean.class);
                CompatExactMethod getThermalMod = CompatExactMethod
                    .virtual(wpIfc, GalacticraftSpaceReflectionSpec.MTD_GET_THERMAL_MODIFIER, float.class);

                Class<?> oxUtil = Class.forName(GalacticraftSpaceReflectionSpec.OXYGEN_UTIL_CLASS, false, cl);
                CompatExactMethod inBreathable = CompatExactMethod.staticMethod(
                    oxUtil,
                    GalacticraftSpaceReflectionSpec.MTD_IN_BREATHABLE_AIR,
                    boolean.class,
                    EntityLivingBase.class,
                    boolean.class);
                CompatExactMethod drainSpacing = CompatExactMethod.staticMethod(
                    oxUtil,
                    GalacticraftSpaceReflectionSpec.MTD_GET_DRAIN_SPACING,
                    int.class,
                    ItemStack.class,
                    ItemStack.class);
                CompatExactMethod noCombustion = CompatExactMethod.staticMethod(
                    oxUtil,
                    GalacticraftSpaceReflectionSpec.MTD_NO_ATMOSPHERIC_COMBUSTION,
                    boolean.class,
                    WorldProvider.class);

                Class<?> maskCls = Class.forName(GalacticraftSpaceReflectionSpec.ITEM_OXYGEN_MASK, false, cl);
                Class<?> gearCls = Class.forName(GalacticraftSpaceReflectionSpec.ITEM_OXYGEN_GEAR, false, cl);
                Class<?> tankCls = Class.forName(GalacticraftSpaceReflectionSpec.ITEM_OXYGEN_TANK, false, cl);
                Class<?> infCls = Class.forName(GalacticraftSpaceReflectionSpec.ITEM_INFINITE_OXYGEN, false, cl);
                Class<?> supplyIfc = Class.forName(GalacticraftSpaceReflectionSpec.OXYGEN_SUPPLY_IFC, false, cl);
                CompatExactMethod discharge = CompatExactMethod.virtual(
                    supplyIfc,
                    GalacticraftSpaceReflectionSpec.MTD_DISCHARGE,
                    float.class,
                    ItemStack.class,
                    float.class);
                CompatExactMethod getOxStored = CompatExactMethod.virtual(
                    supplyIfc,
                    GalacticraftSpaceReflectionSpec.MTD_GET_OXYGEN_STORED,
                    int.class,
                    ItemStack.class);

                Class<?> thermalIfc = Class.forName(GalacticraftSpaceReflectionSpec.THERMAL_IFC, false, cl);
                CompatExactMethod getThermalStr = CompatExactMethod
                    .virtual(thermalIfc, GalacticraftSpaceReflectionSpec.MTD_GET_THERMAL_STRENGTH, int.class);
                CompatExactMethod validForSlot = CompatExactMethod.virtual(
                    thermalIfc,
                    GalacticraftSpaceReflectionSpec.MTD_IS_VALID_FOR_SLOT,
                    boolean.class,
                    ItemStack.class,
                    int.class);

                Class<?> preCls = Class.forName(GalacticraftSpaceReflectionSpec.SUFFOCATION_PRE_CLASS, false, cl);
                Class<?> postCls = Class.forName(GalacticraftSpaceReflectionSpec.SUFFOCATION_POST_CLASS, false, cl);
                Constructor<?> preCtor = preCls.getConstructor(EntityLivingBase.class);
                Constructor<?> postCtor = postCls.getConstructor(EntityLivingBase.class);

                Class<?> dmgCls = Class.forName(GalacticraftSpaceReflectionSpec.DAMAGE_SOURCE_GC_CLASS, false, cl);
                Field oxDmg = dmgCls.getField(GalacticraftSpaceReflectionSpec.FLD_OXYGEN_SUFFOCATION);
                Field thDmg = dmgCls.getField(GalacticraftSpaceReflectionSpec.FLD_THERMAL_DAMAGE);

                Class<?> cfgCls = Class.forName(GalacticraftSpaceReflectionSpec.CONFIG_CORE_CLASS, false, cl);
                Field cooldownFld = cfgCls.getField(GalacticraftSpaceReflectionSpec.FLD_SUFFOCATION_COOLDOWN);
                Field dmgFld = cfgCls.getField(GalacticraftSpaceReflectionSpec.FLD_SUFFOCATION_DAMAGE);

                cachedApi = new Api(
                    wpIfc,
                    hasBreathable,
                    getThermalMod,
                    inBreathable,
                    drainSpacing,
                    noCombustion,
                    maskCls,
                    gearCls,
                    tankCls,
                    infCls,
                    supplyIfc,
                    discharge,
                    getOxStored,
                    thermalIfc,
                    getThermalStr,
                    validForSlot,
                    preCtor,
                    postCtor,
                    oxDmg,
                    thDmg,
                    cooldownFld,
                    dmgFld);
            } catch (Exception | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, GalacticraftSpaceIds.GUARD, failure);
                cachedApi = null;
            }
            resolved = true;
            return cachedApi;
        }
    }

    private static final class Api {

        final Class<?> worldProviderIfc;
        final CompatExactMethod hasBreathableAtmosphere;
        final CompatExactMethod getThermalModifier;
        final CompatExactMethod inBreathableAir;
        final CompatExactMethod getDrainSpacing;
        final CompatExactMethod noAtmosphericCombustion;
        final Class<?> maskClass;
        final Class<?> gearClass;
        final Class<?> tankClass;
        final Class<?> infiniteTankClass;
        final Class<?> oxygenSupplyIfc;
        final CompatExactMethod discharge;
        final CompatExactMethod getOxygenStored;
        final Class<?> thermalIfc;
        final CompatExactMethod getThermalStrength;
        final CompatExactMethod isValidForSlot;
        final Constructor<?> suffocationPreCtor;
        final Constructor<?> suffocationPostCtor;
        final Field oxygenSuffocationSource;
        final Field thermalSource;
        final Field suffocationCooldownField;
        final Field suffocationDamageField;

        Api(Class<?> worldProviderIfc, CompatExactMethod hasBreathableAtmosphere, CompatExactMethod getThermalModifier,
            CompatExactMethod inBreathableAir, CompatExactMethod getDrainSpacing,
            CompatExactMethod noAtmosphericCombustion, Class<?> maskClass, Class<?> gearClass, Class<?> tankClass,
            Class<?> infiniteTankClass, Class<?> oxygenSupplyIfc, CompatExactMethod discharge,
            CompatExactMethod getOxygenStored, Class<?> thermalIfc, CompatExactMethod getThermalStrength,
            CompatExactMethod isValidForSlot, Constructor<?> suffocationPreCtor, Constructor<?> suffocationPostCtor,
            Field oxygenSuffocationSource, Field thermalSource, Field suffocationCooldownField,
            Field suffocationDamageField) {
            this.worldProviderIfc = worldProviderIfc;
            this.hasBreathableAtmosphere = hasBreathableAtmosphere;
            this.getThermalModifier = getThermalModifier;
            this.inBreathableAir = inBreathableAir;
            this.getDrainSpacing = getDrainSpacing;
            this.noAtmosphericCombustion = noAtmosphericCombustion;
            this.maskClass = maskClass;
            this.gearClass = gearClass;
            this.tankClass = tankClass;
            this.infiniteTankClass = infiniteTankClass;
            this.oxygenSupplyIfc = oxygenSupplyIfc;
            this.discharge = discharge;
            this.getOxygenStored = getOxygenStored;
            this.thermalIfc = thermalIfc;
            this.getThermalStrength = getThermalStrength;
            this.isValidForSlot = isValidForSlot;
            this.suffocationPreCtor = suffocationPreCtor;
            this.suffocationPostCtor = suffocationPostCtor;
            this.oxygenSuffocationSource = oxygenSuffocationSource;
            this.thermalSource = thermalSource;
            this.suffocationCooldownField = suffocationCooldownField;
            this.suffocationDamageField = suffocationDamageField;
        }
    }
}
