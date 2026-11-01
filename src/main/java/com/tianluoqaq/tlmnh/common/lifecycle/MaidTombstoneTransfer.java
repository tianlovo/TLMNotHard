package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentTranslation;

/** 整批容量预检，潜行才允许溢出；被其他事件拒绝的掉落仍留在墓碑 */
public final class MaidTombstoneTransfer {

    private MaidTombstoneTransfer() {}

    public static boolean collect(MaidTombstoneEntity tombstone, EntityPlayer player) {
        if (!MaidLifecycleService.canUse(player) || tombstone.isDead
            || player.worldObj != tombstone.worldObj
            || player.getDistanceSqToEntity(tombstone) > 64.0D) return false;
        var ledger = MaidLifecycleData.get(player.worldObj);
        var token = tombstone.getToken();
        if (!player.getUniqueID()
            .equals(ledger.owner(token, MaidLifecycleData.Kind.FILM))) {
            MaidLifecycleService.tell(player, MaidLifecycleService.Result.WRONG_OWNER);
            return false;
        }
        var source = ledger.reserveTombstone(token);
        if (source == null) {
            if (ledger.tombstoneEmpty(token)) player.worldObj.removeEntity(tombstone);
            return false;
        }
        var original = copy(player.inventory.mainInventory);
        var originalFull = player.inventory.writeToNBT(new NBTTagList());
        var originalCursor = ItemStack.copyItemStack(player.inventory.getItemStack());
        int originalSelected = player.inventory.currentItem;
        var planned = copy(original);
        var overflow = new ArrayList<ItemStack>();
        var drops = new ArrayList<EntityItem>();
        var expectedDrops = new ArrayList<ItemStack>();
        boolean committed = false;
        try {
            for (int i = 0; i < source.tagCount(); i++) {
                var stack = com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec
                    .decode(source.getCompoundTagAt(i));
                if (stack == null) throw new IllegalStateException("Invalid tombstone stack");
                insert(planned, stack, player.inventory.getInventoryStackLimit());
                if (stack.stackSize > 0) overflow.add(stack);
            }
            if (!overflow.isEmpty() && !player.isSneaking()) {
                player.addChatMessage(new ChatComponentTranslation("message.tlmnh.lifecycle.inventory_full"));
                return false;
            }
            for (var stack : overflow) {
                var expected = stack.copy();
                var drop = new EntityItem(
                    player.worldObj,
                    player.posX,
                    player.posY + 0.5D,
                    player.posZ,
                    expected.copy());
                drop.delayBeforeCanPickup = 10;
                drops.add(drop);
                expectedDrops.add(expected);
                if (!player.worldObj.spawnEntityInWorld(drop)) {
                    MaidLifecycleService.tell(player, MaidLifecycleService.Result.SPAWN_REJECTED);
                    return false;
                }
            }
            for (int i = 0; i < drops.size(); i++) {
                if (!MaidSpawnChecks.itemPresent(drops.get(i), expectedDrops.get(i), tombstone.worldObj)) {
                    MaidLifecycleService.tell(player, MaidLifecycleService.Result.SPAWN_REJECTED);
                    return false;
                }
            }
            // 单一服务端线程中的事务：回调若改动本玩家库存，拒绝并恢复完整库存快照
            if (!originalFull.equals(player.inventory.writeToNBT(new NBTTagList()))
                || !ItemStack.areItemStacksEqual(originalCursor, player.inventory.getItemStack())
                || originalSelected != player.inventory.currentItem) return false;
            for (int slot = 0; slot < planned.length; slot++) player.inventory.mainInventory[slot] = planned[slot];
            ledger.finishTombstone(token, new NBTTagList());
            committed = true;
            player.inventory.markDirty();
            player.worldObj.removeEntity(tombstone);
            return true;
        } finally {
            if (!committed) {
                for (var drop : drops) MaidSpawnChecks.discard(drop);
                player.inventory.readFromNBT((NBTTagList) originalFull.copy());
                player.inventory.setItemStack(ItemStack.copyItemStack(originalCursor));
                player.inventory.currentItem = originalSelected;
                player.inventory.markDirty();
                ledger.rollback(token);
            }
        }
    }

    private static ItemStack[] copy(ItemStack[] source) {
        var result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].copy();
        return result;
    }

    private static void insert(ItemStack[] slots, ItemStack stack, int inventoryLimit) {
        for (var current : slots) {
            if (current == null || !current.isItemEqual(stack) || !ItemStack.areItemStackTagsEqual(current, stack))
                continue;
            int moved = Math.min(
                stack.stackSize,
                Math.max(0, Math.min(current.getMaxStackSize(), inventoryLimit) - current.stackSize));
            current.stackSize += moved;
            stack.stackSize -= moved;
        }
        for (int slot = 0; slot < slots.length && stack.stackSize > 0; slot++) {
            if (slots[slot] != null) continue;
            int moved = Math.min(stack.stackSize, Math.min(stack.getMaxStackSize(), inventoryLimit));
            slots[slot] = stack.copy();
            slots[slot].stackSize = moved;
            stack.stackSize -= moved;
        }
    }
}
