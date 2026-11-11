package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.relauncher.Side;

/** 整批容量预检，潜行才允许溢出；被其他事件拒绝的掉落仍留在墓碑 */
public final class MaidTombstoneTransfer {

    private MaidTombstoneTransfer() {}

    public static boolean collect(MaidTombstoneEntity tombstone, EntityPlayer player) {
        if (tombstone == null || player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        String key = "player_inventory/tombstone/" + tombstone.getToken();
        if (!IntegrationGuard.available(Side.SERVER, key)) return false;
        try {
            return collectOwned(tombstone, player);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, key, failure, player.getUniqueID());
            return false;
        }
    }

    private static boolean collectOwned(MaidTombstoneEntity tombstone, EntityPlayer player) {
        if (!MaidLifecycleService.canUse(player) || tombstone == null
            || tombstone.isDead
            || player.worldObj != tombstone.worldObj
            || player.getDistanceSqToEntity(tombstone) > 64.0D
            || !MaidSpawnChecks.present(tombstone, player.worldObj)) return false;
        var world = player.worldObj;
        var ledger = MaidLifecycleData.get(world);
        var token = tombstone.getToken();
        var owner = player.getUniqueID();
        if (!owner.equals(ledger.owner(token, MaidLifecycleData.Kind.FILM)) || !owner.equals(tombstone.getOwnerId())) {
            MaidLifecycleService.tell(player, MaidLifecycleService.Result.WRONG_OWNER);
            return false;
        }
        if (ledger.isTombstoneHeld(token)) {
            IntegrationGuard.report(
                Side.SERVER,
                "player_inventory/tombstone/" + token,
                new IllegalStateException("Tombstone claim remains held after an unconfirmed overflow cleanup"),
                owner);
            return false;
        }
        var source = ledger.reserveTombstone(token);
        if (source == null) {
            if (ledger.tombstoneEmpty(token)) player.worldObj.removeEntity(tombstone);
            return false;
        }
        ArrayList<MaidLifecycleDurability.Publication> publications = null;
        boolean begun = false, inventoryApplied = false, committed = false;
        try {
            publications = new ArrayList<>();
            var inventory = player.inventory;
            var originalMain = inventory.mainInventory;
            var originalFull = (NBTTagList) inventory.writeToNBT(new NBTTagList())
                .copy();
            var originalCursor = ItemStack.copyItemStack(inventory.getItemStack());
            int originalSelected = inventory.currentItem;
            var planned = copy(originalMain);
            var overflow = new ArrayList<ItemStack>();
            var tombstoneIdentity = tombstone.getUniqueID();
            int tombstoneId = tombstone.getEntityId();
            InventoryPlayer planning;
            try {
                // 原生构造补丁保留额外槽索引，空槽查询使用与实际玩家相同的运行时策略
                planning = new InventoryPlayer(player);
                if (planning.getClass() != player.inventory.getClass()
                    || planning.mainInventory.length != planned.length)
                    throw new IllegalStateException("Unsupported player inventory planning layout");
                planning.mainInventory = planned;
                planning.currentItem = originalSelected;
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                IntegrationGuard.report(Side.SERVER, "player_inventory/tombstone", failure, player.getUniqueID());
                return false;
            }
            for (int i = 0; i < source.tagCount(); i++) {
                var stack = ProtectedItemCodec.decode(source.getCompoundTagAt(i));
                if (stack == null) throw new IllegalStateException("Invalid tombstone stack");
                try {
                    insert(planning, stack, player.inventory.getInventoryStackLimit());
                } catch (RuntimeException | LinkageError | AssertionError failure) {
                    IntegrationGuard.report(Side.SERVER, "player_inventory/tombstone", failure, player.getUniqueID());
                    return false;
                }
                if (stack.stackSize > 0) overflow.add(stack);
            }
            if (!overflow.isEmpty() && !player.isSneaking()) {
                player.addChatMessage(new ChatComponentTranslation("message.tlmnh.lifecycle.inventory_full"));
                return false;
            }
            MaidLifecycleDurability.requirePlayer(player);
            if (!overflow.isEmpty()) MaidLifecycleDurability.requireEntities(world);
            ledger.beginTombstoneAttempt(world, token, source);
            begun = true;
            for (var stack : overflow) {
                if (!requestCurrent(tombstone, player, world, ledger, token, owner, tombstoneIdentity, tombstoneId))
                    return false;
                var expected = stack.copy();
                var drop = new EntityItem(world, player.posX, player.posY + 0.5D, player.posZ, expected.copy());
                drop.delayBeforeCanPickup = 10;
                publications.add(new MaidLifecycleDurability.Publication(drop, expected));
                if (!world.spawnEntityInWorld(drop)) {
                    MaidLifecycleService.tell(player, MaidLifecycleService.Result.SPAWN_REJECTED);
                    return false;
                }
            }
            for (var publication : publications) publication.check(world, true);
            // 提交前只改过独立计划，拒绝时绝不能用旧快照覆写回调的新物品或新库存
            if (!requestCurrent(tombstone, player, world, ledger, token, owner, tombstoneIdentity, tombstoneId)
                || player.inventory != inventory
                || inventory.mainInventory != originalMain
                || !originalFull.equals(inventory.writeToNBT(new NBTTagList()))
                || !ItemStack.areItemStacksEqual(originalCursor, inventory.getItemStack())
                || originalSelected != inventory.currentItem) return false;
            // 写前屏障已持久；复制后任何保存事件的不明状态都不能再覆写旧库存或重领原保管
            System.arraycopy(planned, 0, originalMain, 0, planned.length);
            inventoryApplied = true;
            var delivered = (NBTTagList) inventory.writeToNBT(new NBTTagList())
                .copy();
            inventory.markDirty();
            MaidLifecycleDurability.player(player, delivered, originalSelected);
            MaidLifecycleDurability.entities(world, publications, true);
            if (!requestCurrent(tombstone, player, world, ledger, token, owner, tombstoneIdentity, tombstoneId)
                || player.inventory != inventory
                || inventory.mainInventory != originalMain
                || !delivered.equals(inventory.writeToNBT(new NBTTagList()))
                || !ItemStack.areItemStacksEqual(originalCursor, inventory.getItemStack())
                || inventory.currentItem != originalSelected)
                throw new IllegalStateException("Native persistence callbacks changed the delivered claim");
            ledger.finishTombstoneAttempt(world, token, source);
            committed = true;
            world.removeEntity(tombstone);
            return true;
        } finally {
            if (!committed) {
                try {
                    if (begun && !inventoryApplied) retract(world, ledger, token, owner, publications);
                } finally {
                    ledger.abandonAttempt(token, false);
                }
            }
        }
    }

    private static boolean requestCurrent(MaidTombstoneEntity tombstone, EntityPlayer player, World world,
        MaidLifecycleData ledger, UUID token, UUID owner, UUID tombstoneIdentity, int tombstoneId) {
        return MaidLifecycleService.canUse(player) && player.worldObj == world
            && owner.equals(player.getUniqueID())
            && player.getDistanceSqToEntity(tombstone) <= 64.0D
            && MaidSpawnChecks.present(tombstone, world)
            && tombstone.getEntityId() == tombstoneId
            && tombstoneIdentity.equals(tombstone.getUniqueID())
            && token.equals(tombstone.getToken())
            && owner.equals(tombstone.getOwnerId())
            && owner.equals(ledger.owner(token, MaidLifecycleData.Kind.FILM));
    }

    private static void retract(World world, MaidLifecycleData ledger, UUID token, UUID owner,
        ArrayList<MaidLifecycleDurability.Publication> publications) {
        boolean safe = true;
        for (var publication : publications) {
            try {
                publication.discard(world);
            } catch (Exception | LinkageError | AssertionError failure) {
                IntegrationGuard.throwIfFatal(failure);
                safe = false;
                IntegrationGuard.report(Side.SERVER, "player_inventory/tombstone/" + token, failure, owner);
                IntegrationGuard
                    .report(Side.SERVER, "player_inventory/tombstone_cleanup/" + publication.identity, failure, owner);
            }
        }
        if (safe) {
            try {
                MaidLifecycleDurability.entities(world, publications, false);
                ledger.cancelAttempt(world, token, false);
            } catch (Exception | LinkageError | AssertionError failure) {
                IntegrationGuard.throwIfFatal(failure);
                IntegrationGuard.report(Side.SERVER, "player_inventory/tombstone/" + token, failure, owner);
            }
        }
    }

    private static ItemStack[] copy(ItemStack[] source) {
        var result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].copy();
        return result;
    }

    private static void insert(InventoryPlayer inventory, ItemStack stack, int inventoryLimit) {
        var slots = inventory.mainInventory;
        for (var current : slots) {
            if (current == null || !current.isItemEqual(stack) || !ItemStack.areItemStackTagsEqual(current, stack))
                continue;
            int moved = Math.min(
                stack.stackSize,
                Math.max(0, Math.min(current.getMaxStackSize(), inventoryLimit) - current.stackSize));
            current.stackSize += moved;
            stack.stackSize -= moved;
        }
        while (stack.stackSize > 0) {
            int slot = inventory.getFirstEmptyStack();
            if (slot < 0) return;
            if (slot >= slots.length || slots[slot] != null)
                throw new IllegalStateException("Native empty slot query returned an occupied or unknown slot");
            int moved = Math.min(stack.stackSize, Math.min(stack.getMaxStackSize(), inventoryLimit));
            slots[slot] = stack.copy();
            slots[slot].stackSize = moved;
            stack.stackSize -= moved;
        }
    }
}
