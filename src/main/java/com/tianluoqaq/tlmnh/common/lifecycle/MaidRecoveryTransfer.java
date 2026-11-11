package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectionRecoveryNbtKeys;
import com.tianluoqaq.tlmnh.common.protection.recovery.RecoveryStore;

import cpw.mods.fml.relauncher.Side;

/** 保管区只从确切原NPC/柜子向原生空槽转移，未知后继保留原始保管而不再自动兑付 */
public final class MaidRecoveryTransfer {

    private static final Set<RecoveryStore> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());

    private MaidRecoveryTransfer() {}

    public static boolean recover(RecoveryStore store, EntityPlayer player, EntityMaid maid) {
        return recover(store, player, maid, null);
    }

    public static boolean recover(RecoveryStore store, EntityPlayer player, TileEntitySnackCabinet cabinet) {
        return recover(store, player, null, cabinet);
    }

    private static boolean recover(RecoveryStore store, EntityPlayer player, EntityMaid maid,
        TileEntitySnackCabinet cabinet) {
        if (store == null || player == null || !ACTIVE.add(store)) return false;
        Source source = null;
        MaidPlayerTransferSnapshot frame = null;
        MaidLifecycleData ledger = null;
        NBTTagCompound context = null, before = null, after = null;
        NBTTagList remaining = null;
        ItemStack item = null;
        UUID id = null;
        boolean removed = false, playerChanged = false, complete = false;
        try {
            source = new Source(store, player, maid, cabinet);
            if (!source.authorized()) return false;
            var actual = store.firstForTransfer();
            if (actual == null) return false;
            item = actual.copy();
            before = source.snapshot();
            frame = new MaidPlayerTransferSnapshot(player);
            int slot = player.inventory.getFirstEmptyStack();
            if (slot < 0) return false;
            if (slot >= frame.main.length || frame.main[slot] != null)
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_NATIVE_RECOVERY_EMPTY_SLOT_QUERY);
            if (!source.matches(before) || !frame.unchanged()
                || store.firstForTransfer() != actual
                || !ItemStack.areItemStacksEqual(actual, item)) return false;
            var rows = store.write();
            remaining = new NBTTagList();
            for (int i = 1; i < rows.tagCount(); i++) remaining.appendTag(
                rows.getCompoundTagAt(i)
                    .copy());
            after = (NBTTagCompound) before.copy();
            after.setTag(ProtectionRecoveryNbtKeys.TAG, remaining.copy());
            var rawSource = new NBTTagCompound();
            rawSource.setTag(MaidLifecyclePart2NbtKeys.KEY_OWNER, before.copy());
            var rawPlayer = new NBTTagCompound();
            player.writeToNBT(rawPlayer);
            rawSource.setTag(MaidLifecyclePart2NbtKeys.KEY_PLAYER, rawPlayer.copy());
            rawSource.setInteger(MaidLifecyclePart2NbtKeys.KEY_DESTINATION_SLOT, slot);
            context = new NBTTagCompound();
            context.setString(MaidLifecyclePart2NbtKeys.KEY_KIND, MaidLifecycleIds.ID_RECOVERY_PLAYER);
            context.setTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE, rawSource);
            ledger = MaidLifecycleData.get(source.world);
            id = ledger.prepareReturnedFilm(
                ProtectedItemCodec.encode(item),
                source.world.provider.dimensionId,
                source.x,
                source.y,
                source.z,
                context);
            MaidLifecycleDurability.ledger(source.world, ledger);
            if (!source.authorized() || !source.matches(before)
                || !frame.unchanged()
                || store.firstForTransfer() != actual
                || !ItemStack.areItemStacksEqual(actual, item)) return false;
            removed = true;
            if (!store.takeFirstForTransfer(actual)) throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_RECOVERY_SOURCE_CHANGED_DURING_REMOVAL);
            source.markDirty();
            source.persist(after);
            if (!source.authorized() || !frame.unchanged()) return false;
            playerChanged = true;
            frame.apply(slot, item);
            frame.persist();
            if (!source.authorized() || !source.matches(after) || !frame.applied()) return false;
            complete = true;
            ledger.finishPreparedReturn(id, context);
            MaidLifecycleDurability.ledger(source.world, ledger);
            player.inventoryContainer.detectAndSendChanges();
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, failure);
            return false;
        } finally {
            if (id != null && !complete) {
                try {
                    if (playerChanged) frame.retract();
                    if (removed) {
                        if (!source.matches(after)) throw new IllegalStateException(
                            MaidLifecyclePart3Diagnostics.MSG_UNKNOWN_RECOVERY_SOURCE_SUCCESSOR_RETAINED);
                        store.restoreFirstForTransfer(item, remaining);
                        source.markDirty();
                        source.persist(before);
                    } else if (!source.matches(before)) throw new IllegalStateException(
                        MaidLifecyclePart3Diagnostics.MSG_ORIGINAL_RECOVERY_SOURCE_CHANGED);
                    ledger.finishPreparedReturn(id, context);
                    MaidLifecycleDurability.ledger(source.world, ledger);
                } catch (Exception | LinkageError | AssertionError failure) {
                    report(player, failure);
                    if (ledger.returnedPhase(id) != null) ledger.holdReturnedFilm(id);
                    try {
                        MaidLifecycleDurability.ledger(frame.world, ledger);
                    } catch (Exception | LinkageError | AssertionError persistenceFailure) {
                        report(player, persistenceFailure);
                    }
                }
            }
            ACTIVE.remove(store);
        }
    }

    private static void report(EntityPlayer player, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(
            Side.SERVER,
            MaidLifecycleIds.ID_PLAYER_INVENTORY_RECOVERY + player.getUniqueID(),
            failure,
            player.getUniqueID());
    }

    private static final class Source {

        final RecoveryStore store;
        final EntityPlayer player;
        final EntityMaid maid;
        final TileEntitySnackCabinet cabinet;
        final World world;
        final UUID identity;
        final int entityId;
        final int blockX, blockY, blockZ, metadata;
        final Block block;
        final double x, y, z;

        Source(RecoveryStore store, EntityPlayer player, EntityMaid maid, TileEntitySnackCabinet cabinet) {
            this.store = store;
            this.player = player;
            this.maid = maid;
            this.cabinet = cabinet;
            world = maid == null ? cabinet.getWorldObj() : maid.worldObj;
            identity = maid == null ? null : maid.getUniqueID();
            entityId = maid == null ? MaidLifecycleTuning.UNSET_OR_NOT_FOUND : maid.getEntityId();
            blockX = maid == null ? cabinet.xCoord : MaidLifecycleTuning.CORE_INITIAL_VALUE;
            blockY = maid == null ? cabinet.yCoord : MaidLifecycleTuning.CORE_INITIAL_VALUE;
            blockZ = maid == null ? cabinet.zCoord : MaidLifecycleTuning.CORE_INITIAL_VALUE;
            if (world == null || world.isRemote
                || maid == null && !world.getChunkProvider()
                    .chunkExists(
                        blockX >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                        blockZ >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT))
                throw new IllegalStateException(
                    MaidLifecyclePart3Diagnostics.MSG_LOADED_ORIGINAL_RECOVERY_SOURCE_REQUIRED);
            block = maid == null ? world.getBlock(blockX, blockY, blockZ) : null;
            metadata = maid == null ? world.getBlockMetadata(blockX, blockY, blockZ)
                : MaidLifecycleTuning.CORE_INITIAL_VALUE;
            x = maid == null ? cabinet.xCoord + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR : maid.posX;
            y = maid == null ? cabinet.yCoord + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR : maid.posY;
            z = maid == null ? cabinet.zCoord + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR : maid.posZ;
        }

        boolean owns() {
            if (world == null || world.isRemote) return false;
            if (maid != null) return maid.worldObj == world && identity.equals(maid.getUniqueID())
                && maid.getEntityId() == entityId
                && MaidSpawnChecks.present(maid, world)
                && maid.getMaidInventory()
                    .recovery() == store
                && !MaidLifecycleService.isPendingRedemption(maid);
            return cabinet.getWorldObj() == world && !cabinet.isInvalid()
                && cabinet.recovery() == store
                && cabinet.xCoord == blockX
                && cabinet.yCoord == blockY
                && cabinet.zCoord == blockZ
                && world.getChunkProvider()
                    .chunkExists(
                        blockX >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                        blockZ >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
                && world.getBlock(blockX, blockY, blockZ) == block
                && world.getBlockMetadata(blockX, blockY, blockZ) == metadata
                && world.getTileEntity(blockX, blockY, blockZ) == cabinet
                && !MaidTileReturns.isPrepared(cabinet);
        }

        boolean authorized() {
            return owns() && MaidLifecycleService.canUse(player)
                && (maid == null ? cabinet.isUseableByPlayer(player)
                    : maid.getMaidInventory()
                        .isUseableByPlayer(player));
        }

        NBTTagCompound snapshot() {
            return maid == null ? MaidShrineTransfer.snapshot(cabinet) : MaidLifecycleDurability.maidSnapshot(maid);
        }

        boolean matches(NBTTagCompound expected) {
            return owns() && expected.equals(snapshot());
        }

        void markDirty() {
            if (maid == null) cabinet.markDirty();
            else maid.getMaidInventory()
                .markDirty();
        }

        void persist(NBTTagCompound expected) {
            if (!matches(expected)) throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_ORIGINAL_RECOVERY_SOURCE_CHANGED_BEFORE);
            if (maid == null) MaidLifecycleDurability.tile(cabinet, expected);
            else MaidLifecycleDurability.entities(
                world,
                Collections.singletonList(new MaidLifecycleDurability.Publication(maid, expected)),
                true);
            if (!matches(expected)) throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_ORIGINAL_RECOVERY_SOURCE_CHANGED_AFTER);
        }
    }
}
