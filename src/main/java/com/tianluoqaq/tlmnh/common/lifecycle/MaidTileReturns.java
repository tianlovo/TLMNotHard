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

import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

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
        if (!PREPARING.add(tile))
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_REENTRANT_SOURCE_TILE_TRANSFER_REFUSED);
        Prepared prepared = null;
        try {
            prepared = new Prepared(tile);
            for (var stack : stacks) {
                if (stack == null || stack.stackSize <= 0) throw new IllegalArgumentException(
                    MaidLifecyclePart3Diagnostics.MSG_COMPLETE_SOURCE_STACK_REQUIRED);
                prepared.ids.add(
                    prepared.ledger.prepareReturnedFilm(
                        ProtectedItemCodec.encode(stack),
                        prepared.world.provider.dimensionId,
                        tile.xCoord + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR,
                        tile.yCoord + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR,
                        tile.zCoord + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR,
                        prepared.context));
            }
            if (!prepared.ids.isEmpty()) MaidLifecycleDurability.ledger(prepared.world, prepared.ledger);
            prepared.checkBefore();
            if (prepared.world.getBlock(prepared.x, prepared.y, prepared.z) != prepared.block
                || prepared.world.getBlockMetadata(prepared.x, prepared.y, prepared.z) != prepared.metadata)
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_ORIGINAL_TILE_BLOCK_CHANGED_WHILE);
            PENDING.put(tile, prepared);
        } catch (Exception | LinkageError | AssertionError failure) {
            if (prepared != null) prepared.retain(failure);
            else report(tile, failure);
            // This stops our own clear. It is not a cancellation API for arbitrary external block removal.
            throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_CANNOT_CONFIRM_ORIGINAL_TILE_ESCROW,
                failure);
        } finally {
            PREPARING.remove(tile);
        }
    }

    public static void beforeClear(TileEntity tile) {
        var prepared = PENDING.get(tile);
        if (prepared == null)
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_MISSING_ORIGINAL_TILE_ESCROW);
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
        if (prepared == null || prepared.ids.isEmpty())
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_NO_ESCROWED_TILE_MATERIAL);
        return prepared.ids.get(MaidLifecycleTuning.CORE_INITIAL_VALUE);
    }

    public static void cleared(TileEntity tile, NBTTagCompound planned) {
        var prepared = PENDING.get(tile);
        if (prepared == null)
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_MISSING_ORIGINAL_CLEARED_TILE_ESCROW);
        try {
            prepared.checkIdentity();
            if (!planned.equals(MaidShrineTransfer.snapshot(tile)))
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_UNKNOWN_TILE_CONTENTS_AFTER_REMOVAL);
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
                    .chunkExists(
                        prepared.x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                        prepared.z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT))
                    continue;
                if (world.getTileEntity(prepared.x, prepared.y, prepared.z) == prepared.tile
                    && world.getBlock(prepared.x, prepared.y, prepared.z) == prepared.block) {
                    if (prepared.after != null && !prepared.after.equals(MaidShrineTransfer.snapshot(prepared.tile)))
                        throw new IllegalStateException(
                            MaidLifecyclePart3Diagnostics.MSG_HELD_SOURCE_TILE_CHANGED_BEFORE);
                    continue;
                }
                if (prepared.after == null) throw new IllegalStateException(
                    MaidLifecyclePart3Diagnostics.MSG_SOURCE_DISAPPEARED_BEFORE_OWNED_CLEAR);
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
        IntegrationGuard.report(
            Side.SERVER,
            MaidLifecycleIds.ID_TILE_RETURN + tile.xCoord
                + MaidLifecycleIds.PATH_SLASH_SEPARATOR
                + tile.yCoord
                + MaidLifecycleIds.PATH_SLASH_SEPARATOR
                + tile.zCoord,
            failure);
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
            if (!(tile instanceof MaidShrineTile || tile instanceof TileEntitySnackCabinet
                || tile instanceof TileEntityMaidBed))
                throw new IllegalArgumentException(MaidLifecyclePart3Diagnostics.MSG_ONLY_OWNED_MAID_CONTENT_TILES);
            world = tile.getWorldObj();
            x = tile.xCoord;
            y = tile.yCoord;
            z = tile.zCoord;
            if (world == null || world.isRemote
                || !world.getChunkProvider()
                    .chunkExists(
                        x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                        z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT))
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_LOADED_ORIGINAL_SOURCE_TILE_REQUIRED);
            MaidLifecycleDurability.requireEntities(world);
            block = world.getBlock(x, y, z);
            metadata = world.getBlockMetadata(x, y, z);
            before = MaidShrineTransfer.snapshot(tile);
            ledger = MaidLifecycleData.get(world);
            var source = new NBTTagCompound();
            source.setTag(MaidLifecyclePart2NbtKeys.KEY_TILE, before.copy());
            source.setString(MaidLifecyclePart2NbtKeys.KEY_BLOCK, Block.blockRegistry.getNameForObject(block));
            source.setInteger(MaidLifecyclePart2NbtKeys.KEY_METADATA, metadata);
            context = new NBTTagCompound();
            context.setString(MaidLifecyclePart2NbtKeys.KEY_KIND, MaidLifecycleIds.ID_TILE_BREAK);
            context.setTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE, source);
            checkBefore();
        }

        void checkIdentity() {
            if (tile.getWorldObj() != world || tile.xCoord != x
                || tile.yCoord != y
                || tile.zCoord != z
                || tile.isInvalid()
                || !world.getChunkProvider()
                    .chunkExists(
                        x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                        z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
                || world.getTileEntity(x, y, z) != tile)
                throw new IllegalStateException(
                    MaidLifecyclePart3Diagnostics.MSG_ORIGINAL_TILE_SOURCE_IDENTITY_CHANGED);
        }

        void checkBefore() {
            checkIdentity();
            if (!before.equals(MaidShrineTransfer.snapshot(tile))) throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_ORIGINAL_TILE_CONTENTS_CHANGED_BEFORE);
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
