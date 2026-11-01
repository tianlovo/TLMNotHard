package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.TLMNotHard;

/** 神龛拆除先保管再交付，掉落取消或回调破坏时可在原区块重试 */
public final class MaidFilmRecovery {

    private MaidFilmRecovery() {}

    public static UUID returnFromShrine(World world, ItemStack film, double x, double y, double z) {
        if (world.isRemote || !MaidLifecycleItems.isFilm(film))
            throw new IllegalArgumentException("Server film required");
        return returnOwnedItem(world, film, x, y, z);
    }

    /** 本模组持有物品共用先保管后交付边界 */
    public static UUID returnOwnedItem(World world, ItemStack film, double x, double y, double z) {
        if (world.isRemote || film == null || film.stackSize <= 0)
            throw new IllegalArgumentException("Server item required");
        UUID id = queueOwnedItem(world, film, x, y, z);
        attemptSafely(world, id);
        return id;
    }

    /** 无世界生成回调，调用者可在返回后清空原槽，交付由既有世界tick推进 */
    public static UUID queueOwnedItem(World world, ItemStack stack, double x, double y, double z) {
        if (world.isRemote || stack == null || stack.stackSize <= 0)
            throw new IllegalArgumentException("Server item required");
        return MaidLifecycleData.get(world)
            .queueReturnedFilm(
                com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec.encode(stack),
                world.provider.dimensionId,
                x,
                y,
                z);
    }

    public static void retryPending(World world) {
        if (!world.isRemote) for (var id : MaidLifecycleData.get(world)
            .pendingReturnedFilms(world.provider.dimensionId)) attemptSafely(world, id);
    }

    private static void attemptSafely(World world, UUID id) {
        try {
            attempt(world, id);
        } catch (RuntimeException exception) {
            TLMNotHard.LOG.error("Shrine film delivery failed; retained in world data", exception);
        }
    }

    private static void attempt(World world, UUID id) {
        var ledger = MaidLifecycleData.get(world);
        var position = ledger.returnedFilmPosition(id);
        if (position == null
            || !world.blockExists((int) Math.floor(position.xCoord), 5, (int) Math.floor(position.zCoord))) return;
        var stored = ledger.reserveReturnedFilm(id);
        if (stored == null) return;
        EntityItem drop = null;
        boolean delivered = false;
        try {
            var expected = com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec.decode(stored);
            if (expected == null) throw new IllegalStateException("Empty escrowed item retained");
            drop = new EntityItem(
                world,
                position.xCoord,
                Math.max(0.1D, position.yCoord),
                position.zCoord,
                expected.copy());
            drop.delayBeforeCanPickup = 10;
            if (world.spawnEntityInWorld(drop) && MaidSpawnChecks.itemPresent(drop, expected, world)) {
                ledger.finishReturnedFilm(id);
                delivered = true;
            }
        } finally {
            if (!delivered) {
                MaidSpawnChecks.discard(drop);
                ledger.rollback(id);
            }
        }
    }
}
