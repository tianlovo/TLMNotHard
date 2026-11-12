package com.tianluoqaq.tlmnh.compat.etfuturum.common.fall;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.life.fall.spi.FallRescueBoatCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.CompatExactMethod;
import com.tianluoqaq.tlmnh.compat.etfuturum.EtFuturumCompat;

import cpw.mods.fml.relauncher.Side;

/**
 * Et Futurum 全木种新船与带箱船贴地自救生成及清理：
 * 贴地生成将船与女仆的 fallDistance 双清零以防陆地 > 3.0 碎船，离船时同步移除派生的 Seat 实体
 */
public final class EtFuturumBoatRescueCompat implements FallRescueBoatCompatProvider {

    public static final EtFuturumBoatRescueCompat INSTANCE = new EtFuturumBoatRescueCompat();

    private static volatile Api cachedApi;
    private static volatile boolean resolved;

    private EtFuturumBoatRescueCompat() {}

    @Override
    public boolean handlesVanillaBoatReplacement() {
        return OptionalApi.isModLoaded(EtFuturumCompat.MOD_ID);
    }

    @Override
    public boolean isCustomBoatItem(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(EtFuturumCompat.MOD_ID)
            || !IntegrationGuard.available(Side.SERVER, EtFuturumFallIds.GUARD)) {
            return false;
        }
        Api a = api();
        return a != null && a.itemBoatClass.isInstance(stack.getItem());
    }

    @Override
    public Entity spawnAndMountCustomBoat(World world, Entity maid, ItemStack boatStack, double x, double y, double z,
        float yaw) {
        if (world == null || world.isRemote
            || maid == null
            || boatStack == null
            || !(isCustomBoatItem(boatStack) || boatStack.getItem() == Items.boat && handlesVanillaBoatReplacement()))
            return null;
        Api a = api();
        if (a == null) return null;
        Entity createdBoat = null;
        boolean joined = false;
        try {
            Object item = boatStack.getItem();
            boolean vanilla = boatStack.getItem() == Items.boat;
            boolean isChest = !vanilla && a.isChestField.getBoolean(item);
            String domain = vanilla ? EtFuturumFallIds.VANILLA_DOMAIN : (String) a.domainField.get(item);
            String name = vanilla ? EtFuturumFallIds.VANILLA_BOAT_TYPE : (String) a.nameField.get(item);

            Entity boat = (Entity) (isChest ? a.chestBoatCtor.newInstance(world) : a.boatCtor.newInstance(world));
            createdBoat = boat;
            if (EntityList.getEntityString(boat) == null) return null;
            boat.setLocationAndAngles(x, y, z, yaw, EtFuturumFallTuning.ZERO_PITCH);
            boat.motionX = EtFuturumFallTuning.ZERO_MOTION;
            boat.motionY = EtFuturumFallTuning.ZERO_MOTION;
            boat.motionZ = EtFuturumFallTuning.ZERO_MOTION;
            boat.fallDistance = EtFuturumFallTuning.ZERO_FALL_DISTANCE;
            a.setBoatType.invoke(boat, domain, name);
            if (boatStack.hasDisplayName()) {
                a.setBoatName.invoke(boat, boatStack.getDisplayName());
            }
            if (!world.spawnEntityInWorld(boat)) {
                return null;
            }
            joined = true;
            maid.fallDistance = EtFuturumFallTuning.ZERO_FALL_DISTANCE;
            a.addToBoat.invoke(boat, maid);
            return boat;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, EtFuturumFallIds.GUARD, failure);
            // A successfully joined entity owns the consumed boat even if mounting fails.
            return joined ? createdBoat : null;
        }
    }

    @Override
    public boolean dismountAndDiscardCustomBoat(Entity maid, Entity boat) {
        if (boat == null || !OptionalApi.isModLoaded(EtFuturumCompat.MOD_ID)) return false;
        Api a = api();
        if (a == null || !a.entityBoatClass.isInstance(boat)) return false;
        try {
            if (boat instanceof net.minecraft.inventory.IInventory inventory) {
                for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                    if (inventory.getStackInSlot(slot) != null) return false;
            }
            Entity seat = (Entity) a.getSeat.invoke(boat);
            if (boat.riddenByEntity != null && boat.riddenByEntity != maid
                || seat != null && seat.riddenByEntity != null && seat.riddenByEntity != maid) return false;
            a.removePassengers.invoke(boat);
            if (maid != null && maid.ridingEntity != null) {
                maid.mountEntity(null);
            }
            if (seat != null && !seat.isDead) {
                seat.setDead();
            }
            if (!boat.isDead) {
                boat.setDead();
            }
            return true;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, EtFuturumFallIds.GUARD, failure);
            return false;
        }
    }

    private static Api api() {
        if (resolved) return cachedApi;
        synchronized (EtFuturumBoatRescueCompat.class) {
            if (resolved) return cachedApi;
            try {
                ClassLoader cl = EtFuturumBoatRescueCompat.class.getClassLoader();
                Class<?> itemCls = Class.forName(EtFuturumFallReflectionSpec.ITEM_NEW_BOAT_CLASS, false, cl);
                Class<?> boatCls = Class.forName(EtFuturumFallReflectionSpec.ENTITY_NEW_BOAT_CLASS, false, cl);
                Class<?> chestBoatCls = Class.forName(EtFuturumFallReflectionSpec.ENTITY_CHEST_BOAT_CLASS, false, cl);

                Field isChestFld = itemCls.getDeclaredField(EtFuturumFallReflectionSpec.FLD_IS_CHEST);
                Field domainFld = itemCls.getDeclaredField(EtFuturumFallReflectionSpec.FLD_DOMAIN);
                Field nameFld = itemCls.getDeclaredField(EtFuturumFallReflectionSpec.FLD_NAME);
                isChestFld.setAccessible(true);
                domainFld.setAccessible(true);
                nameFld.setAccessible(true);

                Constructor<?> boatCtor = boatCls.getConstructor(World.class);
                Constructor<?> chestBoatCtor = chestBoatCls.getConstructor(World.class);
                CompatExactMethod setBoatType = CompatExactMethod.virtual(
                    boatCls,
                    EtFuturumFallReflectionSpec.MTD_SET_BOAT_TYPE,
                    void.class,
                    String.class,
                    String.class);
                CompatExactMethod setBoatName = CompatExactMethod
                    .virtual(boatCls, EtFuturumFallReflectionSpec.MTD_SET_BOAT_NAME, void.class, String.class);
                CompatExactMethod addToBoat = CompatExactMethod
                    .virtual(boatCls, EtFuturumFallReflectionSpec.MTD_ADD_TO_BOAT, void.class, Entity.class);
                CompatExactMethod removePassengers = CompatExactMethod
                    .virtual(boatCls, EtFuturumFallReflectionSpec.MTD_REMOVE_PASSENGERS, void.class);
                CompatExactMethod getSeat = CompatExactMethod.virtual(
                    boatCls,
                    EtFuturumFallReflectionSpec.MTD_GET_SEAT,
                    Class.forName(EtFuturumFallReflectionSpec.ENTITY_BOAT_SEAT_CLASS, false, cl));

                cachedApi = new Api(
                    itemCls,
                    boatCls,
                    isChestFld,
                    domainFld,
                    nameFld,
                    boatCtor,
                    chestBoatCtor,
                    setBoatType,
                    setBoatName,
                    addToBoat,
                    removePassengers,
                    getSeat);
            } catch (Exception | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, EtFuturumFallIds.GUARD, failure);
                cachedApi = null;
            }
            resolved = true;
            return cachedApi;
        }
    }

    private static final class Api {

        final Class<?> itemBoatClass;
        final Class<?> entityBoatClass;
        final Field isChestField;
        final Field domainField;
        final Field nameField;
        final Constructor<?> boatCtor;
        final Constructor<?> chestBoatCtor;
        final CompatExactMethod setBoatType;
        final CompatExactMethod setBoatName;
        final CompatExactMethod addToBoat;
        final CompatExactMethod removePassengers;
        final CompatExactMethod getSeat;

        Api(Class<?> itemBoatClass, Class<?> entityBoatClass, Field isChestField, Field domainField, Field nameField,
            Constructor<?> boatCtor, Constructor<?> chestBoatCtor, CompatExactMethod setBoatType,
            CompatExactMethod setBoatName, CompatExactMethod addToBoat, CompatExactMethod removePassengers,
            CompatExactMethod getSeat) {
            this.itemBoatClass = itemBoatClass;
            this.entityBoatClass = entityBoatClass;
            this.isChestField = isChestField;
            this.domainField = domainField;
            this.nameField = nameField;
            this.boatCtor = boatCtor;
            this.chestBoatCtor = chestBoatCtor;
            this.setBoatType = setBoatType;
            this.setBoatName = setBoatName;
            this.addToBoat = addToBoat;
            this.removePassengers = removePassengers;
            this.getSeat = getSeat;
        }
    }
}
