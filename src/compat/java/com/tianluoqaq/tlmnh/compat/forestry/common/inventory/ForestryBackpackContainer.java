package com.tianluoqaq.tlmnh.compat.forestry.common.inventory;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.BackpackSlotMergeHelper;
import com.tianluoqaq.tlmnh.compat.CompatInventoryTuning;
import com.tianluoqaq.tlmnh.compat.forestry.ForestryCompat;

import cpw.mods.fml.relauncher.Side;

/**
 * 林业背包适配：
 * 不实例化 ItemInventoryBackpack（规避无玩家环境下 ItemLocation.UNKNOWN 触发 IncompatibleClassChangeError），
 * 直接通过 IBackpackDefinition 校验准入并按原生基 36 Slots 标签读写；锁定模式（damage=1）同时阻止自动存入与自动取用
 */
public final class ForestryBackpackContainer {

    private static volatile Api cachedApi;
    private static volatile boolean resolved;

    private ForestryBackpackContainer() {}

    public static boolean isSupported(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(ForestryCompat.MOD_ID)) return false;
        if (!IntegrationGuard.available(Side.SERVER, ForestryBackpackIds.FEATURE)) return false;
        Api api = api();
        return api != null && api.itemBackpackClass.isInstance(stack.getItem());
    }

    public static ItemStack insert(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (!isSupported(containerStack) || CompatServices.backpacks()
            .isSupportedBackpack(input)) {
            return input.copy();
        }
        if (containerStack.getItemDamage() == ForestryBackpackTuning.LOCKED_MODE_DAMAGE) {
            return input.copy();
        }
        Api api = api();
        if (api == null) return input.copy();
        try {
            Object definition = api.getDefinition.invoke(containerStack.getItem());
            if (definition == null || !(Boolean) api.isValidItem.invoke(definition, input)) {
                return input.copy();
            }
            int size = (Integer) api.getBackpackSize.invoke(containerStack.getItem());
            if (size <= 0) {
                return input.copy();
            }
            return insertNbt(containerStack, size, input, simulate);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, ForestryBackpackIds.FEATURE, failure);
            return input.copy();
        }
    }

    public static ItemStack extract(ItemStack containerStack, Predicate<ItemStack> filter,
        ToIntFunction<ItemStack> scorer, int maxCount, boolean simulate) {
        if (maxCount <= 0 || filter == null || !isSupported(containerStack)) return null;
        if (containerStack.getItemDamage() == ForestryBackpackTuning.LOCKED_MODE_DAMAGE) return null;
        Api api = api();
        if (api == null) return null;
        try {
            int size = (Integer) api.getBackpackSize.invoke(containerStack.getItem());
            if (size <= 0) return null;
            return extractNbt(containerStack, size, filter, scorer, maxCount, simulate);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, ForestryBackpackIds.FEATURE, failure);
            return null;
        }
    }

    public static boolean forEachStored(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (!isSupported(containerStack)) return false;
        Api api = api();
        if (api == null) return false;
        try {
            int size = (Integer) api.getBackpackSize.invoke(containerStack.getItem());
            forEachStoredNbt(containerStack, size, visitor);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, ForestryBackpackIds.FEATURE, failure);
            return false;
        }
    }

    public static boolean forEachExtractable(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (!isSupported(containerStack)) return false;
        if (containerStack.getItemDamage() == ForestryBackpackTuning.LOCKED_MODE_DAMAGE) return false;
        return forEachStored(containerStack, visitor);
    }

    /** 原生基 36 Slots + 旧版 Items 兼容读写入口（可供单测直接验证） */
    public static ItemStack insertNbt(ItemStack containerStack, int backpackSize, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (containerStack == null || backpackSize <= 0) return input.copy();
        if (containerStack.getItemDamage() == ForestryBackpackTuning.LOCKED_MODE_DAMAGE) {
            return input.copy();
        }
        ItemStack[] slots = readSlots(containerStack.getTagCompound(), backpackSize);
        BackpackSlotMergeHelper.MergeResult result = BackpackSlotMergeHelper
            .mergeIntoArray(slots, backpackSize, CompatInventoryTuning.DEFAULT_SLOT_STACK_LIMIT, input, simulate);
        if (!simulate && result.changed) {
            NBTTagCompound root = containerStack.getTagCompound();
            if (root == null) {
                root = new NBTTagCompound();
                containerStack.setTagCompound(root);
            }
            writeSlots(root, slots, backpackSize);
        }
        return result.remaining;
    }

    public static ItemStack extractNbt(ItemStack containerStack, int backpackSize, Predicate<ItemStack> filter,
        ToIntFunction<ItemStack> scorer, int maxCount, boolean simulate) {
        if (containerStack == null || containerStack.getTagCompound() == null
            || backpackSize <= 0
            || filter == null
            || maxCount <= 0) {
            return null;
        }
        if (containerStack.getItemDamage() == ForestryBackpackTuning.LOCKED_MODE_DAMAGE) {
            return null;
        }
        ItemStack[] slots = readSlots(containerStack.getTagCompound(), backpackSize);
        BackpackSlotMergeHelper.ExtractResult result = BackpackSlotMergeHelper
            .extractFromArray(slots, backpackSize, filter, scorer, maxCount, simulate);
        if (!simulate && result.changed) {
            writeSlots(containerStack.getTagCompound(), slots, backpackSize);
        }
        return result.extracted;
    }

    public static void forEachStoredNbt(ItemStack containerStack, int backpackSize, Consumer<ItemStack> visitor) {
        if (containerStack == null || backpackSize <= 0) return;
        ItemStack[] slots = readSlots(containerStack.getTagCompound(), backpackSize);
        for (int i = 0; i < backpackSize; i++) {
            ItemStack stored = slots[i];
            if (stored != null && stored.stackSize > 0 && visitor != null) {
                visitor.accept(stored.copy());
            }
        }
    }

    private static ItemStack[] readSlots(NBTTagCompound root, int size) {
        ItemStack[] slots = new ItemStack[size];
        if (root == null) return slots;
        if (root.hasKey(ForestryBackpackNbtKeys.SLOTS, ForestryBackpackTuning.NBT_COMPOUND_TAG_TYPE)) {
            NBTTagCompound slotMap = root.getCompoundTag(ForestryBackpackNbtKeys.SLOTS);
            for (int i = 0; i < size; i++) {
                String key = Integer.toString(i, ForestryBackpackTuning.SLOT_KEY_RADIX);
                if (slotMap.hasKey(key, ForestryBackpackTuning.NBT_COMPOUND_TAG_TYPE)) {
                    slots[i] = ItemStack.loadItemStackFromNBT(slotMap.getCompoundTag(key));
                }
            }
            return slots;
        }
        if (root.hasKey(ForestryBackpackNbtKeys.LEGACY_ITEMS)) {
            NBTTagList legacy = root
                .getTagList(ForestryBackpackNbtKeys.LEGACY_ITEMS, ForestryBackpackTuning.NBT_COMPOUND_TAG_TYPE);
            for (int i = 0; i < legacy.tagCount(); i++) {
                NBTTagCompound entry = legacy.getCompoundTagAt(i);
                int slot = entry.getByte(ForestryBackpackNbtKeys.LEGACY_SLOT)
                    & ForestryBackpackTuning.UNSIGNED_BYTE_MASK;
                if (slot >= 0 && slot < size) {
                    slots[slot] = ItemStack.loadItemStackFromNBT(entry);
                }
            }
        }
        return slots;
    }

    private static void writeSlots(NBTTagCompound root, ItemStack[] slots, int size) {
        if (root.getInteger(ForestryBackpackNbtKeys.UID) == 0) {
            int generated = ThreadLocalRandom.current()
                .nextInt();
            root.setInteger(
                ForestryBackpackNbtKeys.UID,
                generated == 0 ? ForestryBackpackTuning.FALLBACK_UID : generated);
        }
        NBTTagCompound slotMap = new NBTTagCompound();
        for (int i = 0; i < size; i++) {
            ItemStack stack = slots[i];
            if (stack != null && stack.stackSize > 0) {
                NBTTagCompound itemTag = new NBTTagCompound();
                stack.writeToNBT(itemTag);
                slotMap.setTag(Integer.toString(i, ForestryBackpackTuning.SLOT_KEY_RADIX), itemTag);
            }
        }
        root.setTag(ForestryBackpackNbtKeys.SLOTS, slotMap);
        if (root.hasKey(ForestryBackpackNbtKeys.LEGACY_ITEMS)) {
            root.removeTag(ForestryBackpackNbtKeys.LEGACY_ITEMS);
        }
    }

    private static Api api() {
        if (resolved) return cachedApi;
        synchronized (ForestryBackpackContainer.class) {
            if (resolved) return cachedApi;
            try {
                ClassLoader cl = ForestryBackpackContainer.class.getClassLoader();
                Class<?> itemCls = Class.forName(ForestryBackpackReflectionSpec.ITEM_BACKPACK_CLASS, false, cl);
                Class<?> defIfc = Class.forName(ForestryBackpackReflectionSpec.DEFINITION_INTERFACE, false, cl);
                Method getDef = itemCls.getMethod(ForestryBackpackReflectionSpec.METHOD_GET_DEFINITION);
                Method getSize = itemCls.getMethod(ForestryBackpackReflectionSpec.METHOD_GET_BACKPACK_SIZE);
                Method isValid = defIfc.getMethod(ForestryBackpackReflectionSpec.METHOD_IS_VALID_ITEM, ItemStack.class);
                cachedApi = new Api(itemCls, getDef, getSize, isValid);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, ForestryBackpackIds.FEATURE, failure);
                cachedApi = null;
            }
            resolved = true;
            return cachedApi;
        }
    }

    private static final class Api {

        final Class<?> itemBackpackClass;
        final Method getDefinition;
        final Method getBackpackSize;
        final Method isValidItem;

        Api(Class<?> itemBackpackClass, Method getDefinition, Method getBackpackSize, Method isValidItem) {
            this.itemBackpackClass = itemBackpackClass;
            this.getDefinition = getDefinition;
            this.getBackpackSize = getBackpackSize;
            this.isValidItem = isValidItem;
        }
    }
}
