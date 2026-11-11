package com.tianluoqaq.tlmnh.compat.backpack.common.inventory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.BackpackSlotMergeHelper;
import com.tianluoqaq.tlmnh.compat.CompatInventoryTuning;
import com.tianluoqaq.tlmnh.compat.backpack.BackpackCompat;

import cpw.mods.fml.relauncher.Side;

/**
 * Eydamos 背包外部存档适配：
 * 排除末影背包与正被玩家打开的会话，simulate 与只读遍历阶段对未初始化背包不生成 UID、不落盘
 */
public final class EydamosBackpackContainer {

    private static volatile Api cachedApi;
    private static volatile boolean resolved;

    private EydamosBackpackContainer() {}

    public static boolean isSupported(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(BackpackCompat.MOD_ID)) return false;
        if (!IntegrationGuard.available(Side.SERVER, EydamosBackpackIds.FEATURE)) return false;
        Api api = api();
        if (api == null || !api.itemBaseClass.isInstance(stack.getItem())) return false;
        try {
            return !(Boolean) api.isEnderBackpack.invoke(null, stack);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, EydamosBackpackIds.FEATURE, failure);
            return false;
        }
    }

    public static ItemStack insert(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (!isSupported(containerStack) || CompatServices.backpacks().isSupportedBackpack(input)) {
            return input.copy();
        }
        Api api = api();
        if (api == null) return input.copy();
        try {
            Slot validator = (Slot) api.slotCtor.newInstance(
                null,
                EydamosBackpackTuning.PROBE_SLOT_INDEX,
                EydamosBackpackTuning.VIRTUAL_SLOT_X,
                EydamosBackpackTuning.VIRTUAL_SLOT_Y);
            if (!validator.isItemValid(input)) {
                return input.copy();
            }
            if (!hasInitializedUid(containerStack)) {
                if (simulate) {
                    int emptySlots = (Integer) api.getSizeStatic.invoke(null, containerStack);
                    if (emptySlots <= 0) return input.copy();
                    ItemStack[] virtualSlots = new ItemStack[emptySlots];
                    return BackpackSlotMergeHelper.mergeIntoArray(
                        virtualSlots,
                        emptySlots,
                        CompatInventoryTuning.DEFAULT_SLOT_STACK_LIMIT,
                        input,
                        true).remaining;
                }
            }
            ItemStack target = simulate ? containerStack.copy() : containerStack;
            Object save = api.saveCtor.newInstance(target);
            String uid = (String) api.getUuid.invoke(save);
            if (uid != null && !uid.isEmpty() && (Boolean) api.isOpenedByOther.invoke(null, uid, null)) {
                return input.copy();
            }
            IInventory inv = (IInventory) api.inventoryCtor.newInstance(target.getDisplayName(), "");
            api.readFromNbt.invoke(inv, save);
            BackpackSlotMergeHelper.MergeResult result = BackpackSlotMergeHelper.mergeIntoInventory(
                inv,
                inv.getSizeInventory(),
                input,
                slot -> isSlotItemValid(api, inv, slot, input),
                simulate);
            if (!simulate && result.changed) {
                inv.markDirty();
                api.writeToNbt.invoke(inv, save);
                api.saveMethod.invoke(save);
            }
            return result.remaining;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, EydamosBackpackIds.FEATURE, failure);
            return input.copy();
        }
    }

    public static boolean forEachStored(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (!isSupported(containerStack)) return false;
        if (!hasInitializedUid(containerStack)) return true;
        Api api = api();
        if (api == null) return false;
        try {
            Object save = api.saveCtor.newInstance(containerStack);
            IInventory inv = (IInventory) api.inventoryCtor.newInstance(containerStack.getDisplayName(), "");
            api.readFromNbt.invoke(inv, save);
            int size = inv.getSizeInventory();
            for (int i = 0; i < size; i++) {
                ItemStack stored = inv.getStackInSlot(i);
                if (stored != null && stored.stackSize > 0 && visitor != null) {
                    visitor.accept(stored.copy());
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, EydamosBackpackIds.FEATURE, failure);
            return false;
        }
    }

    private static boolean hasInitializedUid(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && !tag.getString(EydamosBackpackNbtKeys.BACKPACK_UID).isEmpty();
    }

    private static boolean isSlotItemValid(Api api, IInventory inv, int slot, ItemStack input) {
        try {
            Slot slotInst = (Slot) api.slotCtor.newInstance(
                inv,
                slot,
                EydamosBackpackTuning.VIRTUAL_SLOT_X,
                EydamosBackpackTuning.VIRTUAL_SLOT_Y);
            return slotInst.isItemValid(input);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, EydamosBackpackIds.FEATURE, failure);
            return false;
        }
    }

    private static Api api() {
        if (resolved) return cachedApi;
        synchronized (EydamosBackpackContainer.class) {
            if (resolved) return cachedApi;
            try {
                ClassLoader cl = EydamosBackpackContainer.class.getClassLoader();
                Class<?> itemBase = Class.forName(EydamosBackpackReflectionSpec.ITEM_BASE_CLASS, false, cl);
                Class<?> utilCls = Class.forName(EydamosBackpackReflectionSpec.UTIL_CLASS, false, cl);
                Class<?> saveCls = Class.forName(EydamosBackpackReflectionSpec.SAVE_CLASS, false, cl);
                Class<?> invCls = Class.forName(EydamosBackpackReflectionSpec.INVENTORY_CLASS, false, cl);
                Class<?> slotCls = Class.forName(EydamosBackpackReflectionSpec.SLOT_CLASS, false, cl);
                Class<?> guiCls = Class.forName(EydamosBackpackReflectionSpec.GUI_HELPER_CLASS, false, cl);

                Method isEnder = utilCls.getMethod(EydamosBackpackReflectionSpec.METHOD_IS_ENDER, ItemStack.class);
                Constructor<?> saveCtor = saveCls.getConstructor(ItemStack.class);
                Method getUuid = saveCls.getMethod(EydamosBackpackReflectionSpec.METHOD_GET_UUID);
                Method getSizeStatic = saveCls.getDeclaredMethod(
                    EydamosBackpackReflectionSpec.METHOD_GET_SIZE,
                    ItemStack.class);
                getSizeStatic.setAccessible(true);
                Method saveMethod = saveCls.getMethod(EydamosBackpackReflectionSpec.METHOD_SAVE);
                Constructor<?> invCtor = invCls.getConstructor(String.class, String.class);
                Method readFromNbt = invCls.getMethod(EydamosBackpackReflectionSpec.METHOD_READ_FROM_NBT, saveCls);
                Method writeToNbt = invCls.getMethod(EydamosBackpackReflectionSpec.METHOD_WRITE_TO_NBT, saveCls);
                Constructor<?> slotCtor = slotCls.getConstructor(IInventory.class, int.class, int.class, int.class);
                Method isOpenedByOther = guiCls.getMethod(
                    EydamosBackpackReflectionSpec.METHOD_IS_OPENED_BY_OTHER,
                    String.class,
                    EntityPlayer.class);

                cachedApi = new Api(
                    itemBase,
                    isEnder,
                    saveCtor,
                    getUuid,
                    getSizeStatic,
                    saveMethod,
                    invCtor,
                    readFromNbt,
                    writeToNbt,
                    slotCtor,
                    isOpenedByOther);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, EydamosBackpackIds.FEATURE, failure);
                cachedApi = null;
            }
            resolved = true;
            return cachedApi;
        }
    }

    private static final class Api {

        final Class<?> itemBaseClass;
        final Method isEnderBackpack;
        final Constructor<?> saveCtor;
        final Method getUuid;
        final Method getSizeStatic;
        final Method saveMethod;
        final Constructor<?> inventoryCtor;
        final Method readFromNbt;
        final Method writeToNbt;
        final Constructor<?> slotCtor;
        final Method isOpenedByOther;

        Api(
            Class<?> itemBaseClass,
            Method isEnderBackpack,
            Constructor<?> saveCtor,
            Method getUuid,
            Method getSizeStatic,
            Method saveMethod,
            Constructor<?> inventoryCtor,
            Method readFromNbt,
            Method writeToNbt,
            Constructor<?> slotCtor,
            Method isOpenedByOther) {
            this.itemBaseClass = itemBaseClass;
            this.isEnderBackpack = isEnderBackpack;
            this.saveCtor = saveCtor;
            this.getUuid = getUuid;
            this.getSizeStatic = getSizeStatic;
            this.saveMethod = saveMethod;
            this.inventoryCtor = inventoryCtor;
            this.readFromNbt = readFromNbt;
            this.writeToNbt = writeToNbt;
            this.slotCtor = slotCtor;
            this.isOpenedByOther = isOpenedByOther;
        }
    }
}
