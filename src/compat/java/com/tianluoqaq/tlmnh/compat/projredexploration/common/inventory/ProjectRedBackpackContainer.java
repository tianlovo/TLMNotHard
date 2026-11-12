package com.tianluoqaq.tlmnh.compat.projredexploration.common.inventory;

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
import com.tianluoqaq.tlmnh.compat.projredexploration.ProjRedExplorationCompat;

import cpw.mods.fml.relauncher.Side;

/** ProjectRed 探索背包适配：按 baginv -> itemsCount(27) + items(index int) 协议直读写与取用 */
public final class ProjectRedBackpackContainer {

    private static volatile Class<?> itemBackpackClass;
    private static volatile boolean resolved;

    private ProjectRedBackpackContainer() {}

    public static boolean isSupported(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(ProjRedExplorationCompat.MOD_ID)) return false;
        if (!IntegrationGuard.available(Side.SERVER, ProjectRedBackpackIds.FEATURE)) return false;
        Class<?> cls = backpackClass();
        return cls != null && cls.isInstance(stack.getItem());
    }

    public static ItemStack insert(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (!isSupported(containerStack) || CompatServices.backpacks()
            .isSupportedBackpack(input)) {
            return input.copy();
        }
        Class<?> cls = backpackClass();
        if (cls != null && cls.isInstance(input.getItem())) {
            return input.copy();
        }
        return insertNbt(containerStack, input, simulate);
    }

    public static ItemStack extract(ItemStack containerStack, Predicate<ItemStack> filter,
        ToIntFunction<ItemStack> scorer, int maxCount, boolean simulate) {
        if (maxCount <= 0 || filter == null || !isSupported(containerStack)) return null;
        return extractNbt(containerStack, filter, scorer, maxCount, simulate);
    }

    public static boolean forEachStored(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (!isSupported(containerStack)) return false;
        forEachStoredNbt(containerStack, visitor);
        return true;
    }

    public static boolean forEachExtractable(ItemStack containerStack, Consumer<ItemStack> visitor) {
        return forEachStored(containerStack, visitor);
    }

    public static ItemStack insertNbt(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (containerStack == null) return input.copy();
        ItemStack[] slots = readSlots(containerStack.getTagCompound());
        BackpackSlotMergeHelper.MergeResult result = BackpackSlotMergeHelper.mergeIntoArray(
            slots,
            ProjectRedBackpackTuning.SLOT_COUNT,
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

    public static ItemStack extractNbt(ItemStack containerStack, Predicate<ItemStack> filter,
        ToIntFunction<ItemStack> scorer, int maxCount, boolean simulate) {
        if (containerStack == null || containerStack.getTagCompound() == null || filter == null || maxCount <= 0) {
            return null;
        }
        ItemStack[] slots = readSlots(containerStack.getTagCompound());
        BackpackSlotMergeHelper.ExtractResult result = BackpackSlotMergeHelper
            .extractFromArray(slots, ProjectRedBackpackTuning.SLOT_COUNT, filter, scorer, maxCount, simulate);
        if (!simulate && result.changed) {
            writeSlots(containerStack.getTagCompound(), slots);
        }
        return result.extracted;
    }

    public static void forEachStoredNbt(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (containerStack == null) return;
        ItemStack[] slots = readSlots(containerStack.getTagCompound());
        for (int i = 0; i < ProjectRedBackpackTuning.SLOT_COUNT; i++) {
            ItemStack stored = slots[i];
            if (stored != null && stored.stackSize > 0 && visitor != null) {
                visitor.accept(stored.copy());
            }
        }
    }

    private static ItemStack[] readSlots(NBTTagCompound root) {
        ItemStack[] slots = new ItemStack[ProjectRedBackpackTuning.SLOT_COUNT];
        if (root == null
            || !root.hasKey(ProjectRedBackpackNbtKeys.BAG_INV, ProjectRedBackpackTuning.NBT_COMPOUND_TAG_TYPE)) {
            return slots;
        }
        NBTTagCompound bagInv = root.getCompoundTag(ProjectRedBackpackNbtKeys.BAG_INV);
        NBTTagList list = bagInv
            .getTagList(ProjectRedBackpackNbtKeys.ITEMS, ProjectRedBackpackTuning.NBT_COMPOUND_TAG_TYPE);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            int index = entry.getInteger(ProjectRedBackpackNbtKeys.INDEX);
            if (index >= 0 && index < ProjectRedBackpackTuning.SLOT_COUNT) {
                slots[index] = ItemStack.loadItemStackFromNBT(entry);
            }
        }
        return slots;
    }

    private static void writeSlots(NBTTagCompound root, ItemStack[] slots) {
        NBTTagCompound bagInv = new NBTTagCompound();
        bagInv.setInteger(ProjectRedBackpackNbtKeys.ITEMS_COUNT, ProjectRedBackpackTuning.SLOT_COUNT);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < ProjectRedBackpackTuning.SLOT_COUNT; i++) {
            ItemStack stack = slots[i];
            if (stack != null && stack.stackSize > 0) {
                NBTTagCompound entry = new NBTTagCompound();
                stack.writeToNBT(entry);
                entry.setInteger(ProjectRedBackpackNbtKeys.INDEX, i);
                list.appendTag(entry);
            }
        }
        bagInv.setTag(ProjectRedBackpackNbtKeys.ITEMS, list);
        root.setTag(ProjectRedBackpackNbtKeys.BAG_INV, bagInv);
    }

    private static Class<?> backpackClass() {
        if (resolved) return itemBackpackClass;
        synchronized (ProjectRedBackpackContainer.class) {
            if (resolved) return itemBackpackClass;
            try {
                itemBackpackClass = Class.forName(
                    ProjectRedBackpackReflectionSpec.ITEM_BACKPACK_CLASS,
                    false,
                    ProjectRedBackpackContainer.class.getClassLoader());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, ProjectRedBackpackIds.FEATURE, failure);
                itemBackpackClass = null;
            }
            resolved = true;
            return itemBackpackClass;
        }
    }
}
