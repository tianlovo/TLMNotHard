package com.tianluoqaq.tlmnh.compat.adventurebackpack.common.inventory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.BackpackSlotMergeHelper;
import com.tianluoqaq.tlmnh.compat.CompatInventoryTuning;
import com.tianluoqaq.tlmnh.compat.adventurebackpack.AdventureBackpackCompat;

import cpw.mods.fml.relauncher.Side;

/**
 * 冒险者背包内部容器适配：
 * 规避空 stackTagCompound 调 markDirty 的空指针，并严格限制在 0..47 普通存储格内合并写入
 */
public final class AdventureBackpackContainer {

    private static volatile Api cachedApi;
    private static volatile boolean resolved;

    private AdventureBackpackContainer() {}

    public static boolean isSupported(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(AdventureBackpackCompat.MOD_ID)) return false;
        if (!IntegrationGuard.available(Side.SERVER, AdventureBackpackInventoryIds.FEATURE)) return false;
        Api api = api();
        return api != null && api.itemClass.isInstance(stack.getItem());
    }

    public static ItemStack insert(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (!isSupported(containerStack) || CompatServices.backpacks()
            .isSupportedBackpack(input)) {
            return input.copy();
        }
        Api api = api();
        if (api == null) return input.copy();
        try {
            if (!(Boolean) api.isValidItem.invoke(null, input)) {
                return input.copy();
            }
            ItemStack target = simulate ? containerStack.copy() : containerStack;
            if (target.getTagCompound() == null) {
                target.setTagCompound(new NBTTagCompound());
            }
            IInventory inv = (IInventory) api.inventoryCtor.newInstance(target);
            BackpackSlotMergeHelper.MergeResult result = BackpackSlotMergeHelper
                .mergeIntoInventory(inv, AdventureBackpackTuning.STORAGE_SLOT_COUNT, input, null, simulate);
            if (!simulate && result.changed) {
                inv.markDirty();
            }
            return result.remaining;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, AdventureBackpackInventoryIds.FEATURE, failure);
            return input.copy();
        }
    }

    public static boolean forEachStored(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (!isSupported(containerStack)) return false;
        forEachStoredNbt(containerStack, visitor);
        return true;
    }

    /** 纯 NBT 读写入口（兼容离线单测校验 0..47 槽位边界与空根标签初始化） */
    public static ItemStack insertNbt(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (containerStack == null) return input.copy();
        ItemStack[] slots = readStorageSlots(containerStack.getTagCompound());
        BackpackSlotMergeHelper.MergeResult result = BackpackSlotMergeHelper.mergeIntoArray(
            slots,
            AdventureBackpackTuning.STORAGE_SLOT_COUNT,
            CompatInventoryTuning.DEFAULT_SLOT_STACK_LIMIT,
            input,
            simulate);
        if (!simulate && result.changed) {
            NBTTagCompound root = containerStack.getTagCompound();
            if (root == null) {
                root = new NBTTagCompound();
                containerStack.setTagCompound(root);
            }
            writeStorageSlots(root, slots);
        }
        return result.remaining;
    }

    public static void forEachStoredNbt(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (containerStack == null) return;
        ItemStack[] slots = readStorageSlots(containerStack.getTagCompound());
        for (int i = 0; i < AdventureBackpackTuning.STORAGE_SLOT_COUNT; i++) {
            ItemStack stored = slots[i];
            if (stored != null && stored.stackSize > 0 && visitor != null) {
                visitor.accept(stored.copy());
            }
        }
    }

    private static ItemStack[] readStorageSlots(NBTTagCompound root) {
        ItemStack[] slots = new ItemStack[AdventureBackpackTuning.STORAGE_SLOT_COUNT];
        if (root == null
            || !root.hasKey(AdventureBackpackNbtKeys.WEARABLE_DATA, AdventureBackpackTuning.NBT_COMPOUND_TAG_TYPE)) {
            return slots;
        }
        NBTTagCompound wearable = root.getCompoundTag(AdventureBackpackNbtKeys.WEARABLE_DATA);
        NBTTagList list = wearable
            .getTagList(AdventureBackpackNbtKeys.INVENTORY, AdventureBackpackTuning.NBT_COMPOUND_TAG_TYPE);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            int slot = entry.getByte(AdventureBackpackNbtKeys.SLOT) & AdventureBackpackTuning.UNSIGNED_BYTE_MASK;
            if (slot >= 0 && slot < AdventureBackpackTuning.STORAGE_SLOT_COUNT) {
                slots[slot] = ItemStack.loadItemStackFromNBT(entry);
            }
        }
        return slots;
    }

    private static void writeStorageSlots(NBTTagCompound root, ItemStack[] storageSlots) {
        NBTTagCompound wearable = root
            .hasKey(AdventureBackpackNbtKeys.WEARABLE_DATA, AdventureBackpackTuning.NBT_COMPOUND_TAG_TYPE)
                ? root.getCompoundTag(AdventureBackpackNbtKeys.WEARABLE_DATA)
                : new NBTTagCompound();
        NBTTagList existingList = wearable
            .getTagList(AdventureBackpackNbtKeys.INVENTORY, AdventureBackpackTuning.NBT_COMPOUND_TAG_TYPE);
        NBTTagList updatedList = new NBTTagList();

        for (int i = 0; i < AdventureBackpackTuning.STORAGE_SLOT_COUNT; i++) {
            ItemStack stack = storageSlots[i];
            if (stack != null && stack.stackSize > 0) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setByte(AdventureBackpackNbtKeys.SLOT, (byte) i);
                stack.writeToNBT(entry);
                updatedList.appendTag(entry);
            }
        }
        // 保留 48..53 工具与流体桶槽原样不动
        for (int i = 0; i < existingList.tagCount(); i++) {
            NBTTagCompound entry = existingList.getCompoundTagAt(i);
            int slot = entry.getByte(AdventureBackpackNbtKeys.SLOT) & AdventureBackpackTuning.UNSIGNED_BYTE_MASK;
            if (slot >= AdventureBackpackTuning.STORAGE_SLOT_COUNT) {
                updatedList.appendTag(entry.copy());
            }
        }
        wearable.setTag(AdventureBackpackNbtKeys.INVENTORY, updatedList);
        root.setTag(AdventureBackpackNbtKeys.WEARABLE_DATA, wearable);
    }

    private static Api api() {
        if (resolved) return cachedApi;
        synchronized (AdventureBackpackContainer.class) {
            if (resolved) return cachedApi;
            try {
                ClassLoader cl = AdventureBackpackContainer.class.getClassLoader();
                Class<?> itemCls = Class.forName(AdventureBackpackReflectionSpec.ITEM_CLASS, false, cl);
                Class<?> invCls = Class.forName(AdventureBackpackReflectionSpec.INVENTORY_CLASS, false, cl);
                Class<?> slotCls = Class.forName(AdventureBackpackReflectionSpec.SLOT_CLASS, false, cl);
                Constructor<?> invCtor = invCls.getConstructor(ItemStack.class);
                Method isValid = slotCls
                    .getMethod(AdventureBackpackReflectionSpec.METHOD_IS_VALID_ITEM, ItemStack.class);
                cachedApi = new Api(itemCls, invCtor, isValid);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, AdventureBackpackInventoryIds.FEATURE, failure);
                cachedApi = null;
            }
            resolved = true;
            return cachedApi;
        }
    }

    private static final class Api {

        final Class<?> itemClass;
        final Constructor<?> inventoryCtor;
        final Method isValidItem;

        Api(Class<?> itemClass, Constructor<?> inventoryCtor, Method isValidItem) {
            this.itemClass = itemClass;
            this.inventoryCtor = inventoryCtor;
            this.isValidItem = isValidItem;
        }
    }
}
