package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Collections;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.relauncher.Side;

/** 拆除先保管再交付；安全取消可重试，未知后继保留原始保管 */
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

    /** 提交已独立持有的物品；仍属于自有Tile的物品由MaidTileReturns确认源移除 */
    public static UUID queueOwnedItem(World world, ItemStack stack, double x, double y, double z) {
        if (world.isRemote || stack == null || stack.stackSize <= 0)
            throw new IllegalArgumentException("Server item required");
        var ledger = MaidLifecycleData.get(world);
        var id = ledger.queueReturnedFilm(ProtectedItemCodec.encode(stack), world.provider.dimensionId, x, y, z);
        MaidLifecycleDurability.ledger(world, ledger);
        return id;
    }

    public static void retryPending(World world) {
        if (!world.isRemote) MaidTileReturns.completePending(world);
        if (!world.isRemote) for (var id : MaidLifecycleData.get(world)
            .pendingReturnedFilms(world.provider.dimensionId)) attemptSafely(world, id);
    }

    private static void attemptSafely(World world, UUID id) {
        IntegrationGuard.call(Side.SERVER, ExternalFeature.ITEM_DELIVERY, () -> {
            attempt(world, id);
            return true;
        }, () -> false);
    }

    private static void attempt(World world, UUID id) {
        var ledger = MaidLifecycleData.get(world);
        var position = ledger.returnedFilmPosition(id);
        if (position == null
            || !world.blockExists((int) Math.floor(position.xCoord), 5, (int) Math.floor(position.zCoord))) return;
        var stored = ledger.reserveReturnedFilm(id);
        if (stored == null) return;
        MaidLifecycleDurability.Publication publication = null;
        boolean begun = false, delivered = false;
        try {
            var expected = ProtectedItemCodec.decode(stored);
            if (expected == null) throw new IllegalStateException("Empty escrowed item retained");
            MaidLifecycleDurability.requireEntities(world);
            ledger.beginReturnedFilm(id);
            begun = true;
            MaidLifecycleDurability.ledger(world, ledger);
            var drop = new EntityItem(
                world,
                position.xCoord,
                Math.max(0.1D, position.yCoord),
                position.zCoord,
                expected.copy());
            drop.delayBeforeCanPickup = 10;
            publication = new MaidLifecycleDurability.Publication(drop, expected);
            if (world.spawnEntityInWorld(drop) && MaidSpawnChecks.itemPresent(drop, expected, world)) {
                MaidLifecycleDurability.entities(world, Collections.singletonList(publication), true);
                delivered = true;
                ledger.finishReturnedFilm(id);
                MaidLifecycleDurability.ledger(world, ledger);
            }
        } finally {
            if (!delivered) {
                try {
                    if (publication != null) {
                        publication.discard(world);
                        MaidLifecycleDurability.entities(world, Collections.singletonList(publication), false);
                    }
                    if (begun) {
                        ledger.retryReturnedFilm(id);
                        MaidLifecycleDurability.ledger(world, ledger);
                    }
                } catch (Exception | LinkageError | AssertionError failure) {
                    report(id, failure);
                    ledger.holdReturnedFilm(id);
                    try {
                        MaidLifecycleDurability.ledger(world, ledger);
                    } catch (Exception | LinkageError | AssertionError persistenceFailure) {
                        report(id, persistenceFailure);
                    }
                } finally {
                    ledger.abandonReturnedFilm(id);
                }
            }
        }
    }

    private static void report(UUID id, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(Side.SERVER, "returned_material/" + id, failure);
    }
}
