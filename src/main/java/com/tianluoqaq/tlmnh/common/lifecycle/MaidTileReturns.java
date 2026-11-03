package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.relauncher.Side;

/** 自有Tile拆除的源保管；跨重启的PREPARED不会按相同坐标或NBT猜认原物 */
public final class MaidTileReturns {

    private static final Map<TileEntity, Prepared> PENDING = new IdentityHashMap<>();
    private static final Set<TileEntity> PREPARING = Collections.newSetFromMap(new IdentityHashMap<>());

    private MaidTileReturns() {}

    public static boolean isPrepared(TileEntity tile) {
        return PENDING.containsKey(tile) || PREPARING.contains(tile);
    }

    public static void prepare(TileEntity tile, List<ItemStack> stacks) {
        if (PENDING.containsKey(tile)) return;
        if (!PREPARING.add(tile)) throw new IllegalStateException("Reentrant source tile transfer refused");
        Prepared prepared = null;
        try {
            prepared = new Prepared(tile);
            for (var stack : stacks) {
                if (stack == null || stack.stackSize <= 0)
                    throw new IllegalArgumentException("Complete source stack required");
                prepared.ids.add(
                    prepared.ledger.prepareReturnedFilm(
                        ProtectedItemCodec.encode(stack),
                        prepared.world.provider.dimensionId,
                        tile.xCoord + .5,
                        tile.yCoord + .5,
                        tile.zCoord + .5,
                        prepared.context));
            }
            if (!prepared.ids.isEmpty()) MaidLifecycleDurability.ledger(prepared.world, prepared.ledger);
            prepared.checkBefore();
            if (prepared.world.getBlock(prepared.x, prepared.y, prepared.z) != prepared.block
                || prepared.world.getBlockMetadata(prepared.x, prepared.y, prepared.z) != prepared.metadata)
                throw new IllegalStateException("Original tile block changed while preparing source escrow");
            PENDING.put(tile, prepared);
        } catch (Exception | LinkageError | AssertionError failure) {
            if (prepared != null) prepared.retain(failure);
            else report(tile, failure);
            // This stops our own clear. It is not a cancellation API for arbitrary external block removal.
            throw new IllegalStateException(
                "Cannot confirm original tile escrow; source must remain retained",
                failure);
        } finally {
            PREPARING.remove(tile);
        }
    }

    public static void beforeClear(TileEntity tile) {
        var prepared = PENDING.get(tile);
        if (prepared == null) throw new IllegalStateException("Missing original tile escrow");
        try {
            prepared.checkBefore();
        } catch (Exception | LinkageError | AssertionError failure) {
            prepared.retain(failure);
            PENDING.remove(tile);
            throw failure;
        }
    }

    public static UUID firstId(TileEntity tile) {
        var prepared = PENDING.get(tile);
        if (prepared == null || prepared.ids.isEmpty()) throw new IllegalStateException("No escrowed tile material");
        return prepared.ids.get(0);
    }

    public static void cleared(TileEntity tile, NBTTagCompound planned) {
        var prepared = PENDING.get(tile);
        if (prepared == null) throw new IllegalStateException("Missing original cleared tile escrow");
        try {
            prepared.checkIdentity();
            if (!planned.equals(MaidShrineTransfer.snapshot(tile)))
                throw new IllegalStateException("Unknown tile contents after removal callback retained");
            prepared.after = (NBTTagCompound) planned.copy();
        } catch (Exception | LinkageError | AssertionError failure) {
            prepared.retain(failure);
            PENDING.remove(tile);
            throw failure;
        }
    }

    public static void completePending(World world) {
        if (world.isRemote) return;
        for (var prepared : new ArrayList<>(PENDING.values())) {
            if (prepared.world != world || prepared.finalizing) continue;
            prepared.finalizing = true;
            try {
                if (!world.getChunkProvider()
                    .chunkExists(prepared.x >> 4, prepared.z >> 4)) continue;
                if (world.getTileEntity(prepared.x, prepared.y, prepared.z) == prepared.tile
                    && world.getBlock(prepared.x, prepared.y, prepared.z) == prepared.block) {
                    if (prepared.after != null && !prepared.after.equals(MaidShrineTransfer.snapshot(prepared.tile)))
                        throw new IllegalStateException("Held source tile changed before its removal");
                    continue;
                }
                if (prepared.after == null)
                    throw new IllegalStateException("Source disappeared before owned clear was confirmed");
                MaidLifecycleDurability.absentTile(world, prepared.x, prepared.y, prepared.z, prepared.block);
                for (var id : prepared.ids) prepared.ledger.readyPreparedReturn(id, prepared.context);
                if (!prepared.ids.isEmpty()) MaidLifecycleDurability.ledger(world, prepared.ledger);
                PENDING.remove(prepared.tile);
            } catch (Exception | LinkageError | AssertionError failure) {
                prepared.retain(failure);
                PENDING.remove(prepared.tile);
            } finally {
                prepared.finalizing = false;
            }
        }
    }

    public static void abandon(World world) {
        for (var prepared : new ArrayList<>(PENDING.values())) if (prepared.world == world) {
            for (var id : prepared.ids) prepared.ledger.abandonReturnedFilm(id);
            PENDING.remove(prepared.tile);
        }
    }

    private static void report(TileEntity tile, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard
            .report(Side.SERVER, "tile_return/" + tile.xCoord + "/" + tile.yCoord + "/" + tile.zCoord, failure);
    }

    private static final class Prepared {

        final TileEntity tile;
        final World world;
        final Block block;
        final int x, y, z, metadata;
        final NBTTagCompound before, context;
        final MaidLifecycleData ledger;
        final List<UUID> ids = new ArrayList<>();
        NBTTagCompound after;
        boolean finalizing;

        Prepared(TileEntity tile) {
            this.tile = tile;
            if (!(tile instanceof MaidShrineTile
                || tile instanceof com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet
                || tile instanceof com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed))
                throw new IllegalArgumentException("Only owned maid content tiles can stage returns");
            world = tile.getWorldObj();
            x = tile.xCoord;
            y = tile.yCoord;
            z = tile.zCoord;
            if (world == null || world.isRemote
                || !world.getChunkProvider()
                    .chunkExists(x >> 4, z >> 4))
                throw new IllegalStateException("Loaded original source tile required");
            MaidLifecycleDurability.requireEntities(world);
            block = world.getBlock(x, y, z);
            metadata = world.getBlockMetadata(x, y, z);
            before = MaidShrineTransfer.snapshot(tile);
            ledger = MaidLifecycleData.get(world);
            var source = new NBTTagCompound();
            source.setTag("Tile", before.copy());
            source.setString("Block", Block.blockRegistry.getNameForObject(block));
            source.setInteger("Metadata", metadata);
            context = new NBTTagCompound();
            context.setString("Kind", "tile_break");
            context.setTag("Source", source);
            checkBefore();
        }

        void checkIdentity() {
            if (tile.getWorldObj() != world || tile.xCoord != x
                || tile.yCoord != y
                || tile.zCoord != z
                || tile.isInvalid()
                || !world.getChunkProvider()
                    .chunkExists(x >> 4, z >> 4)
                || world.getTileEntity(x, y, z) != tile)
                throw new IllegalStateException("Original tile source identity changed");
        }

        void checkBefore() {
            checkIdentity();
            if (!before.equals(MaidShrineTransfer.snapshot(tile)))
                throw new IllegalStateException("Original tile contents changed before owned clear");
        }

        void retain(Throwable failure) {
            report(tile, failure);
            try {
                var observed = MaidShrineTransfer.snapshot(tile);
                for (var id : ids) if (ledger.returnedPhase(id) != null) {
                    ledger.observeReturnedSource(id, context, observed);
                    ledger.holdReturnedFilm(id);
                }
                if (!ids.isEmpty()) MaidLifecycleDurability.ledger(world, ledger);
            } catch (Exception | LinkageError | AssertionError retentionFailure) {
                report(tile, retentionFailure);
                for (var id : ids) ledger.abandonReturnedFilm(id);
            }
        }
    }
}
