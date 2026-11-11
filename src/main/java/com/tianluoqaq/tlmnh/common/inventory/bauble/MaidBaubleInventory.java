package com.tianluoqaq.tlmnh.common.inventory.bauble;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

import cpw.mods.fml.relauncher.Side;

/** 饰品只有一份实体存储，未知条目进入既有恢复账本 */
public final class MaidBaubleInventory implements IInventory {

    private final EntityMaid maid;
    private final ItemStack[] items = new ItemStack[MaidBaubleLayout.SIZE];
    private boolean persistent;

    public MaidBaubleInventory(EntityMaid maid) {
        this.maid = maid;
    }

    @Override
    public int getSizeInventory() {
        return items.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        checkSlot(slot);
        return items[slot];
    }

    @Override
    public ItemStack decrStackSize(int slot, int count) {
        var stack = getStackInSlot(slot);
        if (stack == null || count <= 0) return null;
        if (count >= stack.stackSize) {
            setInventorySlotContents(slot, null);
            return stack;
        }
        var removed = stack.splitStack(count);
        markDirty();
        return removed;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        var stack = getStackInSlot(slot);
        setInventorySlotContents(slot, null);
        return stack;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        checkSlot(slot);
        var previous = items[slot];
        var admitted = ProtectedItemCodec.admit(stack, getInventoryStackLimit());
        items[slot] = admitted;
        persistent |= admitted != null;
        if (previous != admitted && maid.worldObj != null && !maid.worldObj.isRemote && maid.isEntityAlive()) {
            invoke(previous, (bauble, actual) -> bauble.onTakeOff(maid, actual));
            invoke(admitted, (bauble, actual) -> bauble.onPutOn(maid, actual));
        }
        markDirty();
    }

    public boolean containsItem(Item item) {
        return findFirst(stack -> stack.getItem() == item) != null;
    }

    public ItemStack findFirst(Predicate<ItemStack> predicate) {
        for (var stack : items) if (stack != null && predicate.test(stack)) return stack;
        return null;
    }

    public void fireEvent(BiConsumer<IMaidBauble, ItemStack> event) {
        for (int slot = 0; slot < items.length; slot++) invoke(items[slot], event);
    }

    private void invoke(ItemStack stack, BiConsumer<IMaidBauble, ItemStack> event) {
        var bauble = MaidBaubleRegistry.getBauble(stack);
        if (bauble == null) return;
        try {
            event.accept(bauble, stack);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(
                Side.SERVER,
                MaidBaubleIds.CALLBACK + stack.getItem()
                    .getClass()
                    .getName(),
                failure);
        }
    }

    public void write(NBTTagCompound tag) {
        if (persistent) tag.setTag(MaidBaubleNbtKeys.ITEMS, writeItems());
    }

    public NBTTagList writeItems() {
        var encoded = new NBTTagList();
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot] == null) continue;
            var entry = ProtectedItemCodec.encode(items[slot]);
            entry.setByte(MaidBaubleNbtKeys.SLOT, (byte) slot);
            encoded.appendTag(entry);
        }
        return encoded;
    }

    public void read(NBTTagCompound tag) {
        persistent = tag.hasKey(MaidBaubleNbtKeys.ITEMS);
        for (int slot = 0; slot < items.length; slot++) items[slot] = null;
        var recovery = maid.getMaidInventory()
            .recovery();
        recovery.retainInvalidList(tag, MaidBaubleNbtKeys.ITEMS);
        var encoded = tag.getTagList(MaidBaubleNbtKeys.ITEMS, Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < encoded.tagCount(); index++) {
            var entry = encoded.getCompoundTagAt(index);
            int slot = entry.getByte(MaidBaubleNbtKeys.SLOT) & MaidBaubleLayout.UNSIGNED_BYTE_MASK;
            if (!entry.hasKey(MaidBaubleNbtKeys.SLOT, Constants.NBT.TAG_BYTE) || slot >= items.length
                || items[slot] != null) {
                recovery.retain(entry);
                continue;
            }
            var stack = ProtectedItemCodec.decode(entry);
            items[slot] = stack == null ? ProtectedItemCodec.seal(entry, MaidBaubleIds.EMPTY_ENTRY) : stack;
        }
    }

    @Override
    public String getInventoryName() {
        return MaidBaubleIds.INVENTORY_NAME;
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return MaidBaubleLayout.STACK_LIMIT;
    }

    @Override
    public void markDirty() {}

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return maid.getMaidInventory()
            .isUseableByPlayer(player);
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        checkSlot(slot);
        return slot < MaidBaubleLayout.unlockedSlots(
            maid.getFavorabilityManager()
                .getLevel())
            && MaidBaubleRegistry.getBauble(stack) != null;
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= MaidBaubleLayout.SIZE)
            throw new IndexOutOfBoundsException(MaidBaubleDiagnostics.INVALID_SLOT + slot);
    }
}
