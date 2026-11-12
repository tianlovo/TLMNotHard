package com.tianluoqaq.tlmnh.compat.ic2.common.armor;

import java.lang.reflect.Field;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.tianluoqaq.tlmnh.common.life.armor.spi.SpecialArmorCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.CompatExactMethod;
import com.tianluoqaq.tlmnh.compat.ic2.Ic2Compat;
import com.tianluoqaq.tlmnh.compat.ic2.Ic2Ids;

import cpw.mods.fml.relauncher.Side;

/**
 * IC2 纳米套、量子套与全套橡胶防化服服务端特殊效果实现：
 * 覆盖水下自动补氧、解毒/消辐射/解凋零、锡罐自动喂食并返空罐、夜视耗电、量子护腿加速、防化服灭火与靴子高坠全免预判
 */
public final class Ic2SpecialArmorCompat implements SpecialArmorCompatProvider {

    public static final Ic2SpecialArmorCompat INSTANCE = new Ic2SpecialArmorCompat();

    private static volatile Api cachedApi;
    private static volatile boolean resolved;

    private Ic2SpecialArmorCompat() {}

    @Override
    public boolean tickSpecialArmor(EntityLivingBase maid, ItemStack boots, ItemStack legs, ItemStack chest,
        ItemStack helmet, ArmorSupportAccess access) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote || !isAvailable()) return false;
        Api a = api();
        if (a == null) return false;
        boolean equipmentMutated = false;
        try {
            Object mgr = a.managerField.get(null);
            if (mgr == null) return false;

            if (helmet != null && helmet.getItem() != null) {
                if (a.quantumSuitClass.isInstance(helmet.getItem())) {
                    equipmentMutated |= tickQuantumHelmet(maid, helmet, mgr, a, access);
                } else if (a.nanoSuitClass.isInstance(helmet.getItem())) {
                    equipmentMutated |= tickNightVisionIfEnabled(maid, helmet, mgr, a);
                }
            }

            if (legs != null && legs.getItem() != null
                && a.quantumSuitClass.isInstance(legs.getItem())
                && access != null
                && access.isGroundFastMoving()
                && canUse(mgr, a, legs, Ic2ArmorTuning.QUANTUM_LEGS_EU_COST)) {
                access.applyQuantumLegSpeedBoost();
                if (maid.ticksExisted % Ic2ArmorTuning.QUANTUM_LEGS_DRAIN_PERIOD_TICKS == 0) {
                    equipmentMutated |= useEnergy(mgr, a, legs, Ic2ArmorTuning.QUANTUM_LEGS_EU_COST, maid);
                }
            }

            if (chest != null && a.quantumSuitClass.isInstance(chest.getItem())) maid.extinguish();

            if ((Boolean) a.hasCompleteHazmat.invoke(null, maid)) {
                if (maid.isBurning()) {
                    maid.extinguish();
                }
                if (maid.handleLavaMovement() && !maid.isPotionActive(Potion.fireResistance)) {
                    maid.addPotionEffect(
                        new PotionEffect(
                            Potion.fireResistance.id,
                            Ic2ArmorTuning.HAZMAT_FIRE_RES_TICKS,
                            Ic2ArmorTuning.LEVEL_ZERO_AMPLIFIER,
                            true));
                }
            }
            return equipmentMutated;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, Ic2Ids.ARMOR_GUARD, failure);
            return equipmentMutated;
        }
    }

    @Override
    public boolean canBootsFullyAbsorbFall(EntityLivingBase maid, ItemStack boots, float fallDistance) {
        if (maid == null || boots == null || boots.getItem() == null || !isAvailable()) return false;
        if (fallDistance <= Ic2ArmorTuning.SAFE_FALL_FREE_BLOCKS) return true;
        Api a = api();
        if (a == null) return false;
        try {
            Object mgr = a.managerField.get(null);
            if (mgr == null) return false;
            double charge = (Double) a.getCharge.invoke(mgr, boots);
            if (a.quantumSuitClass.isInstance(boots.getItem())) {
                int costlyBlocks = Math
                    .max(Ic2ArmorTuning.ZERO_COST_BLOCKS, (int) fallDistance - Ic2ArmorTuning.QUANTUM_FREE_FALL_BLOCKS);
                return charge >= costlyBlocks * Ic2ArmorTuning.QUANTUM_ENERGY_PER_DAMAGE;
            }
            if (a.nanoSuitClass.isInstance(boots.getItem())) {
                int damage = (int) fallDistance - (int) Ic2ArmorTuning.SAFE_FALL_FREE_BLOCKS;
                return damage < Ic2ArmorTuning.NANO_FULL_FALL_DAMAGE_LIMIT && charge
                    >= Math.max(Ic2ArmorTuning.ZERO_COST_BLOCKS, damage) * Ic2ArmorTuning.NANO_ENERGY_PER_DAMAGE;
            }
            return false;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, Ic2Ids.ARMOR_GUARD, failure);
            return false;
        }
    }

    private static boolean tickQuantumHelmet(EntityLivingBase maid, ItemStack helmet, Object mgr, Api a,
        ArmorSupportAccess access) throws ReflectiveOperationException {
        boolean changed = false;
        if (maid.getAir() < Ic2ArmorTuning.LOW_AIR_THRESHOLD
            && canUse(mgr, a, helmet, Ic2ArmorTuning.AIR_REFILL_EU_COST)
            && useEnergy(mgr, a, helmet, Ic2ArmorTuning.AIR_REFILL_EU_COST, maid)) {
            maid.setAir(Math.min(Ic2ArmorTuning.MAX_AIR_SUPPLY, maid.getAir() + Ic2ArmorTuning.AIR_REFILL_AMOUNT));
            changed = true;
        }

        changed |= curePotionIfPowered(maid, helmet, mgr, a, Potion.poison.id, Ic2ArmorTuning.POISON_EU_BASE);
        changed |= curePotionIfPowered(maid, helmet, mgr, a, Potion.wither.id, Ic2ArmorTuning.WITHER_EU_BASE);
        Potion radiation = (Potion) a.radiationField.get(null);
        if (radiation != null) {
            changed |= curePotionIfPowered(maid, helmet, mgr, a, radiation.id, Ic2ArmorTuning.RADIATION_EU_BASE);
        }

        if (access != null && access.currentFood() < Ic2ArmorTuning.FULL_HUNGER_LEVEL
            && canUse(mgr, a, helmet, Ic2ArmorTuning.TIN_CAN_EU_COST)) {
            changed |= tryAutoFeedTinCans(maid, helmet, mgr, a, access);
        }

        changed |= tickNightVisionIfEnabled(maid, helmet, mgr, a);
        return changed;
    }

    private static boolean curePotionIfPowered(EntityLivingBase maid, ItemStack helmet, Object mgr, Api a, int potionId,
        double baseCost) throws ReflectiveOperationException {
        PotionEffect active = maid.getActivePotionEffect(Potion.potionTypes[potionId]);
        if (active == null) return false;
        double cost = baseCost * (active.getAmplifier() + 1);
        if (canUse(mgr, a, helmet, cost) && useEnergy(mgr, a, helmet, cost, maid)) {
            maid.removePotionEffect(potionId);
            return true;
        }
        return false;
    }

    private static boolean tryAutoFeedTinCans(EntityLivingBase maid, ItemStack helmet, Object mgr, Api a,
        ArmorSupportAccess access) throws ReflectiveOperationException {
        int deficit = Ic2ArmorTuning.FULL_HUNGER_LEVEL - access.currentFood();
        if (deficit <= 0) return false;
        ItemStack emptyTemplate = (ItemStack) a.emptyTinCanField.get(null);
        if (emptyTemplate == null || emptyTemplate.getItem() == null) return false;
        Item emptyItem = emptyTemplate.getItem();

        java.util.function.Predicate<ItemStack> isCan = stack -> stack != null && stack.stackSize > 0
            && a.tinCanClass.isInstance(stack.getItem());
        ItemStack preview = access.extractMatching(isCan, deficit, true);
        if (preview == null) return false;
        int eatCount = preview.stackSize;
        ItemStack returnedEmpty = new ItemStack(emptyItem, eatCount);
        // Reserve return space before charging or removing food; never discard an unplaceable can.
        ItemStack remainder = access.insertIntoMaidInventory(returnedEmpty, true);
        if (remainder != null && remainder.stackSize > 0) return false;
        if (!useEnergy(mgr, a, helmet, Ic2ArmorTuning.TIN_CAN_EU_COST, maid)) return false;
        ItemStack consumed = access.extractMatching(isCan, eatCount, false);
        if (consumed == null) return false;
        returnedEmpty.stackSize = consumed.stackSize;
        remainder = access.insertIntoMaidInventory(returnedEmpty, false);
        if (remainder != null) maid.entityDropItem(remainder, Ic2ArmorTuning.RETURN_DROP_HEIGHT);
        access.addFoodStats(consumed.stackSize, (float) consumed.stackSize);
        return true;
    }

    private static boolean tickNightVisionIfEnabled(EntityLivingBase maid, ItemStack helmet, Object mgr, Api a)
        throws ReflectiveOperationException {
        NBTTagCompound nbt = helmet.getTagCompound();
        if (nbt == null || !nbt.getBoolean(Ic2ArmorReflectionSpec.NBT_NIGHTVISION)) return false;
        if (!canUse(mgr, a, helmet, Ic2ArmorTuning.NIGHTVISION_EU_COST)) return false;
        if (!useEnergy(mgr, a, helmet, Ic2ArmorTuning.NIGHTVISION_EU_COST, maid)) return false;
        int x = net.minecraft.util.MathHelper.floor_double(maid.posX);
        int y = net.minecraft.util.MathHelper.floor_double(maid.posY);
        int z = net.minecraft.util.MathHelper.floor_double(maid.posZ);
        boolean bright = maid.worldObj.getBlockLightValue(x, y, z) > Ic2ArmorTuning.NIGHTVISION_BRIGHT_LIGHT;
        maid.removePotionEffect(bright ? Potion.nightVision.id : Potion.blindness.id);
        maid.addPotionEffect(
            new PotionEffect(
                bright ? Potion.blindness.id : Potion.nightVision.id,
                bright ? Ic2ArmorTuning.NIGHTVISION_BLINDNESS_TICKS : Ic2ArmorTuning.NIGHTVISION_DURATION_TICKS,
                Ic2ArmorTuning.LEVEL_ZERO_AMPLIFIER,
                true));
        return true;
    }

    private static boolean canUse(Object mgr, Api a, ItemStack stack, double amount)
        throws ReflectiveOperationException {
        return (Boolean) a.canUse.invoke(mgr, stack, amount);
    }

    private static boolean useEnergy(Object mgr, Api a, ItemStack stack, double amount, EntityLivingBase entity)
        throws ReflectiveOperationException {
        return (Boolean) a.use.invoke(mgr, stack, amount, entity);
    }

    private static boolean isAvailable() {
        return OptionalApi.isModLoaded(Ic2Compat.MOD_ID) && IntegrationGuard.available(Side.SERVER, Ic2Ids.ARMOR_GUARD);
    }

    private static Api api() {
        if (resolved) return cachedApi;
        synchronized (Ic2SpecialArmorCompat.class) {
            if (resolved) return cachedApi;
            try {
                ClassLoader cl = Ic2SpecialArmorCompat.class.getClassLoader();
                Class<?> electricItemCls = Class.forName(Ic2ArmorReflectionSpec.ELECTRIC_ITEM_CLASS, false, cl);
                Class<?> managerIfc = Class.forName(Ic2ArmorReflectionSpec.MANAGER_INTERFACE, false, cl);
                Field managerField = electricItemCls.getField(Ic2ArmorReflectionSpec.FLD_MANAGER);
                CompatExactMethod canUse = CompatExactMethod.virtual(
                    managerIfc,
                    Ic2ArmorReflectionSpec.MTD_CAN_USE,
                    boolean.class,
                    ItemStack.class,
                    double.class);
                CompatExactMethod getCharge = CompatExactMethod
                    .virtual(managerIfc, Ic2ArmorReflectionSpec.MTD_GET_CHARGE, double.class, ItemStack.class);
                CompatExactMethod use = CompatExactMethod.virtual(
                    managerIfc,
                    Ic2ArmorReflectionSpec.MTD_USE,
                    boolean.class,
                    ItemStack.class,
                    double.class,
                    EntityLivingBase.class);

                Class<?> nanoCls = Class.forName(Ic2ArmorReflectionSpec.NANO_SUIT_CLASS, false, cl);
                Class<?> quantumCls = Class.forName(Ic2ArmorReflectionSpec.QUANTUM_SUIT_CLASS, false, cl);
                Class<?> hazmatCls = Class.forName(Ic2ArmorReflectionSpec.HAZMAT_CLASS, false, cl);
                CompatExactMethod hasCompleteHazmat = CompatExactMethod.staticMethod(
                    hazmatCls,
                    Ic2ArmorReflectionSpec.MTD_HAS_COMPLETE_HAZMAT,
                    boolean.class,
                    EntityLivingBase.class);

                Class<?> ic2PotionCls = Class.forName(Ic2ArmorReflectionSpec.IC2_POTION_CLASS, false, cl);
                Field radiationField = ic2PotionCls.getField(Ic2ArmorReflectionSpec.FLD_RADIATION);

                Class<?> tinCanCls = Class.forName(Ic2ArmorReflectionSpec.TIN_CAN_CLASS, false, cl);
                Class<?> ic2ItemsCls = Class.forName(Ic2ArmorReflectionSpec.IC2_ITEMS_CLASS, false, cl);
                Field emptyTinCanField = ic2ItemsCls.getField(Ic2ArmorReflectionSpec.FLD_EMPTY_TIN_CAN);

                cachedApi = new Api(
                    managerField,
                    getCharge,
                    canUse,
                    use,
                    nanoCls,
                    quantumCls,
                    hasCompleteHazmat,
                    radiationField,
                    tinCanCls,
                    emptyTinCanField);
            } catch (Exception | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, Ic2Ids.ARMOR_GUARD, failure);
                cachedApi = null;
            }
            resolved = true;
            return cachedApi;
        }
    }

    private static final class Api {

        final Field managerField;
        final CompatExactMethod getCharge;
        final CompatExactMethod canUse;
        final CompatExactMethod use;
        final Class<?> nanoSuitClass;
        final Class<?> quantumSuitClass;
        final CompatExactMethod hasCompleteHazmat;
        final Field radiationField;
        final Class<?> tinCanClass;
        final Field emptyTinCanField;

        Api(Field managerField, CompatExactMethod getCharge, CompatExactMethod canUse, CompatExactMethod use,
            Class<?> nanoSuitClass, Class<?> quantumSuitClass, CompatExactMethod hasCompleteHazmat,
            Field radiationField, Class<?> tinCanClass, Field emptyTinCanField) {
            this.managerField = managerField;
            this.getCharge = getCharge;
            this.canUse = canUse;
            this.use = use;
            this.nanoSuitClass = nanoSuitClass;
            this.quantumSuitClass = quantumSuitClass;
            this.hasCompleteHazmat = hasCompleteHazmat;
            this.radiationField = radiationField;
            this.tinCanClass = tinCanClass;
            this.emptyTinCanField = emptyTinCanField;
        }
    }
}
