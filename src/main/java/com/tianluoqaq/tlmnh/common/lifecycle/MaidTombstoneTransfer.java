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
        var inventory = player.inventory;
        var originalMain = inventory.mainInventory;
        var originalFull = inventory.writeToNBT(new NBTTagList());
        var originalCursor = ItemStack.copyItemStack(inventory.getItemStack());
        int originalSelected = inventory.currentItem;
        var planned = copy(originalMain);
        var overflow = new ArrayList<ItemStack>();
        var drops = new ArrayList<EntityItem>();
        var expectedDrops = new ArrayList<ItemStack>();
        var dropIdentities = new ArrayList<UUID>();
        var dropIds = new ArrayList<Integer>();
        var tombstoneIdentity = tombstone.getUniqueID();
        int tombstoneId = tombstone.getEntityId();
        boolean committed = false;
        try {
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
                com.tianluoqaq.tlmnh.common.protection.IntegrationGuard.report(
                    cpw.mods.fml.relauncher.Side.SERVER,
                    "player_inventory/tombstone",
                    failure,
                    player.getUniqueID());
                return false;
            }
            for (int i = 0; i < source.tagCount(); i++) {
                var stack = com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec
                    .decode(source.getCompoundTagAt(i));
                if (stack == null) throw new IllegalStateException("Invalid tombstone stack");
                try {
                    insert(planning, stack, player.inventory.getInventoryStackLimit());
                } catch (RuntimeException | LinkageError | AssertionError failure) {
                    com.tianluoqaq.tlmnh.common.protection.IntegrationGuard.report(
                        cpw.mods.fml.relauncher.Side.SERVER,
                        "player_inventory/tombstone",
                        failure,
                        player.getUniqueID());
                    return false;
                }
                if (stack.stackSize > 0) overflow.add(stack);
            }
            if (!overflow.isEmpty() && !player.isSneaking()) {
                player.addChatMessage(new ChatComponentTranslation("message.tlmnh.lifecycle.inventory_full"));
                return false;
            }
            for (var stack : overflow) {
                if (!requestCurrent(tombstone, player, world, ledger, token, owner, tombstoneIdentity, tombstoneId))
                    return false;
                var expected = stack.copy();
                var drop = new EntityItem(world, player.posX, player.posY + 0.5D, player.posZ, expected.copy());
                drop.delayBeforeCanPickup = 10;
                drops.add(drop);
                expectedDrops.add(expected);
                dropIdentities.add(drop.getUniqueID());
                dropIds.add(drop.getEntityId());
                if (!world.spawnEntityInWorld(drop)) {
                    MaidLifecycleService.tell(player, MaidLifecycleService.Result.SPAWN_REJECTED);
                    return false;
                }
            }
            for (int i = 0; i < drops.size(); i++) {
                var drop = drops.get(i);
                if (!MaidSpawnChecks.itemPresent(drop, expectedDrops.get(i), world)
                    || drop.getEntityId() != dropIds.get(i)
                    || !dropIdentities.get(i)
                        .equals(drop.getUniqueID())) {
                    MaidLifecycleService.tell(player, MaidLifecycleService.Result.SPAWN_REJECTED);
                    return false;
                }
            }
            // 提交前只改过独立计划，拒绝时绝不能用旧快照覆写回调的新物品或新库存
            if (!requestCurrent(tombstone, player, world, ledger, token, owner, tombstoneIdentity, tombstoneId)
                || player.inventory != inventory
                || inventory.mainInventory != originalMain
                || !originalFull.equals(inventory.writeToNBT(new NBTTagList()))
                || !ItemStack.areItemStacksEqual(originalCursor, inventory.getItemStack())
                || originalSelected != inventory.currentItem) return false;
            // 账本核原保管内容后，原生数组复制不再调用外部槽位回调
            ledger.finishTombstone(token, source, new NBTTagList());
            System.arraycopy(planned, 0, originalMain, 0, planned.length);
            committed = true;
            inventory.markDirty();
            world.removeEntity(tombstone);
            return true;
        } finally {
            if (!committed) {
                try {
                    for (int i = 0; i < drops.size(); i++) discardOwnedDrop(
                        drops.get(i),
                        expectedDrops.get(i),
                        world,
                        dropIdentities.get(i),
                        dropIds.get(i),
                        owner,
                        ledger,
                        token);
                } finally {
                    ledger.rollback(token);
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

    private static void discardOwnedDrop(EntityItem drop, ItemStack expected, World world, UUID identity, int id,
        UUID owner, MaidLifecycleData ledger, UUID token) {
        try {
            if (drop.worldObj != world || drop.getEntityId() != id
                || !identity.equals(drop.getUniqueID())
                || !ItemStack.areItemStacksEqual(expected, drop.getEntityItem())
                || world.getEntityByID(id) != null && world.getEntityByID(id) != drop)
                throw new IllegalStateException("Tombstone overflow changed ownership; leaving external state intact");
            world.removeEntity(drop);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            try {
                ledger.holdTombstone(token);
            } catch (Exception | LinkageError | AssertionError holdFailure) {
                IntegrationGuard.throwIfFatal(holdFailure);
                if (holdFailure != failure) failure.addSuppressed(holdFailure);
            }
            IntegrationGuard.report(Side.SERVER, "player_inventory/tombstone/" + token, failure, owner);
            IntegrationGuard.report(Side.SERVER, "player_inventory/tombstone_cleanup/" + identity, failure, owner);
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
