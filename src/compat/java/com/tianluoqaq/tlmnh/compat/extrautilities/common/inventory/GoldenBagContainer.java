package com.tianluoqaq.tlmnh.compat.extrautilities.common.inventory;

import java.util.function.Consumer;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.BackpackSlotMergeHelper;
import com.tianluoqaq.tlmnh.compat.CompatInventoryTuning;
import com.tianluoqaq.tlmnh.compat.extrautilities.ExtraUtilitiesCompat;

import cpw.mods.fml.relauncher.Side;

/** 黄金收纳袋（Golden Bag of Holding）适配：按根标签 items_0..items_53 直读写 54 格存储 */
public final class GoldenBagContainer {

    private static volatile Class<?> itemGoldenBagClass;
    private static volatile boolean resolved;

    private GoldenBagContainer() {}

    public static boolean isSupported(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(ExtraUtilitiesCompat.MOD_ID)) return false;
        if (!IntegrationGuard.available(Side.SERVER, GoldenBagIds.FEATURE)) return false;
        Class<?> cls = bagClass();
        return cls != null && cls.isInstance(stack.getItem());
    }

    public static ItemStack insert(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (!isSupported(containerStack) || CompatServices.backpacks()
            .isSupportedBackpack(input)) {
            return input.copy();
        }
        Class<?> cls = bagClass();
        if (cls != null && cls.isInstance(input.getItem())) {
            return input.copy();
        }
        return insertNbt(containerStack, input, simulate);
    }

    public static boolean forEachStored(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (!isSupported(containerStack)) return false;
        forEachStoredNbt(containerStack, visitor);
        return true;
    }

    public static ItemStack insertNbt(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (containerStack == null) return input.copy();
        ItemStack[] slots = readSlots(containerStack.getTagCompound());
        BackpackSlotMergeHelper.MergeResult result = BackpackSlotMergeHelper.mergeIntoArray(
            slots,
            GoldenBagTuning.SLOT_COUNT,
            CompatInventoryTuning.DEFAULT_SLOT_STACK_LIMIT,
            input,
            simulate);
        if (!simulate && result.changed) {
            NBTTagCompound root = containerStack.getTagCompound();
            if (root == null) {
                root = new NBTTagCompound();
                containerStack.setTagCompound(root);
            }
            writeSlots(root, slots);
        }
        return result.remaining;
    }

    public static void forEachStoredNbt(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (containerStack == null) return;
        ItemStack[] slots = readSlots(containerStack.getTagCompound());
        for (int i = 0; i < GoldenBagTuning.SLOT_COUNT; i++) {
            ItemStack stored = slots[i];
            if (stored != null && stored.stackSize > 0 && visitor != null) {
                visitor.accept(stored.copy());
            }
        }
    }

    private static ItemStack[] readSlots(NBTTagCompound root) {
        ItemStack[] slots = new ItemStack[GoldenBagTuning.SLOT_COUNT];
        if (root == null) return slots;
        for (int i = 0; i < GoldenBagTuning.SLOT_COUNT; i++) {
            String key = GoldenBagNbtKeys.SLOT_KEY_PREFIX + i;
            if (root.hasKey(key, GoldenBagTuning.NBT_COMPOUND_TAG_TYPE)) {
                slots[i] = ItemStack.loadItemStackFromNBT(root.getCompoundTag(key));
            }
        }
        return slots;
    }

    private static void writeSlots(NBTTagCompound root, ItemStack[] slots) {
        for (int i = 0; i < GoldenBagTuning.SLOT_COUNT; i++) {
            String key = GoldenBagNbtKeys.SLOT_KEY_PREFIX + i;
            ItemStack stack = slots[i];
            if (stack != null && stack.stackSize > 0) {
                NBTTagCompound itemTag = new NBTTagCompound();
                stack.writeToNBT(itemTag);
                root.setTag(key, itemTag);
            } else if (root.hasKey(key)) {
                root.removeTag(key);
            }
        }
    }

    private static Class<?> bagClass() {
        if (resolved) return itemGoldenBagClass;
        synchronized (GoldenBagContainer.class) {
            if (resolved) return itemGoldenBagClass;
            try {
                itemGoldenBagClass = Class.forName(
                    GoldenBagReflectionSpec.ITEM_GOLDEN_BAG_CLASS,
                    false,
                    GoldenBagContainer.class.getClassLoader());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, GoldenBagIds.FEATURE, failure);
                itemGoldenBagClass = null;
            }
            resolved = true;
            return itemGoldenBagClass;
        }
    }
}
