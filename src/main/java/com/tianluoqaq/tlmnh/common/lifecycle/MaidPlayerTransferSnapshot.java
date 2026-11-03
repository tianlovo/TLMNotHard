package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

/** 单次本模组物品转移的原生玩家库存快照，撤回只触碰仍属于本次的那个槽 */
final class MaidPlayerTransferSnapshot {

    final EntityPlayer player;
    final World world;
    final InventoryPlayer inventory;
    final ItemStack[] main, armor;
    final int selected;
    final UUID identity;
    final boolean creative;
    final ItemStack cursor;
    final NBTTagList original;
    private NBTTagList expected;
    private int slot = -1;
    private ItemStack before, placed, placedSnapshot;

    MaidPlayerTransferSnapshot(EntityPlayer player) {
        this.player = player;
        world = player.worldObj;
        inventory = player.inventory;
        main = inventory.mainInventory;
        armor = inventory.armorInventory;
        selected = inventory.currentItem;
        identity = player.getUniqueID();
        creative = player.capabilities.isCreativeMode;
        cursor = ItemStack.copyItemStack(inventory.getItemStack());
        original = (NBTTagList) inventory.writeToNBT(new NBTTagList())
            .copy();
        MaidLifecycleDurability.requirePlayer(player);
        if (selected < 0 || selected >= main.length || !authorized())
            throw new IllegalStateException("Unknown authoritative player inventory");
    }

    boolean storage() {
        return player.worldObj == world && player.isEntityAlive()
            && identity.equals(player.getUniqueID())
            && player.inventory == inventory
            && inventory.mainInventory == main
            && inventory.armorInventory == armor;
    }

    boolean authorized() {
        return storage() && MaidLifecycleService.canUse(player)
            && inventory.currentItem == selected
            && player.capabilities.isCreativeMode == creative
            && ItemStack.areItemStacksEqual(cursor, inventory.getItemStack());
    }

    boolean unchanged() {
        return authorized() && originalStored();
    }

    boolean originalStored() {
        return storage() && original.equals(inventory.writeToNBT(new NBTTagList()));
    }

    NBTTagList plan(int target, ItemStack value) {
        if (target < 0 || target >= main.length || target > 255)
            throw new IllegalArgumentException("Unknown native transfer slot");
        var planning = new InventoryPlayer(player);
        if (planning.getClass() != inventory.getClass() || planning.mainInventory.length != main.length
            || planning.armorInventory.length != armor.length)
            throw new IllegalStateException("Unsupported native transfer inventory layout");
        planning.mainInventory = copy(main);
        planning.armorInventory = copy(armor);
        planning.currentItem = selected;
        planning.mainInventory[target] = ItemStack.copyItemStack(value);
        return (NBTTagList) planning.writeToNBT(new NBTTagList())
            .copy();
    }

    void apply(int target, ItemStack value) {
        expected = plan(target, value);
        before = ItemStack.copyItemStack(main[target]);
        placed = ItemStack.copyItemStack(value);
        placedSnapshot = ItemStack.copyItemStack(placed);
        if (!unchanged()) throw new IllegalStateException("Player source changed before transfer");
        slot = target;
        inventory.setInventorySlotContents(target, placed);
        inventory.markDirty();
        if (!applied()) throw new IllegalStateException("Unknown player transfer successor retained");
    }

    boolean applied() {
        return slot >= 0 && authorized()
            && main[slot] == placed
            && ItemStack.areItemStacksEqual(placed, placedSnapshot)
            && expected.equals(inventory.writeToNBT(new NBTTagList()));
    }

    boolean touched() {
        return slot >= 0;
    }

    void persist() {
        if (!applied()) throw new IllegalStateException("Player delivery changed before native save");
        MaidLifecycleDurability.player(player, expected, selected);
        if (!applied()) throw new IllegalStateException("Player delivery changed after native save");
    }

    void retract() {
        if (slot < 0) return;
        if (!storage() || main[slot] != placed || !ItemStack.areItemStacksEqual(placed, placedSnapshot))
            throw new IllegalStateException("Unknown player successor cannot be overwritten during retraction");
        inventory.setInventorySlotContents(slot, ItemStack.copyItemStack(before));
        inventory.markDirty();
        if (!ItemStack.areItemStacksEqual(main[slot], before))
            throw new IllegalStateException("Original transfer slot was rewritten during retraction");
        MaidLifecycleDurability.player(
            player,
            (NBTTagList) inventory.writeToNBT(new NBTTagList())
                .copy(),
            inventory.currentItem);
    }

    private static ItemStack[] copy(ItemStack[] source) {
        var result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = ItemStack.copyItemStack(source[i]);
        return result;
    }
}
