package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.TLMNotHard;

/** 神龛拆除先保管再交付，掉落取消或回调破坏时可在原区块重试 */
public final class MaidFilmRecovery {

    private MaidFilmRecovery() {}

    public static UUID returnFromShrine(World world, ItemStack film, double x, double y, double z) {
        if (world.isRemote || !MaidLifecycleItems.isFilm(film))
            throw new IllegalArgumentException("Server film required");
        var ledger = MaidLifecycleData.get(world);
        var id = ledger.queueReturnedFilm(film.writeToNBT(new NBTTagCompound()), world.provider.dimensionId, x, y, z);
        attemptSafely(world, id);
        return id;
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
            var expected = ItemStack.loadItemStackFromNBT(stored);
            if (!MaidLifecycleItems.isFilm(expected)) throw new IllegalStateException("Invalid escrowed film");
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
