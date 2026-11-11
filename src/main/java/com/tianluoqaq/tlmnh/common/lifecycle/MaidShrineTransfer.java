package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;

/** 普通放片和取片，与复活独立；未确认的跨文件转移只保留原始保管，不自动补发 */
public final class MaidShrineTransfer {

    private static final Set<MaidShrineTile> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());

    private MaidShrineTransfer() {}

    public static boolean transfer(EntityPlayer player, MaidShrineTile shrine, boolean insert) {
        if (player == null || shrine == null || !ACTIVE.add(shrine)) return false;
        MaidPlayerTransferSnapshot frame = null;
        MaidLifecycleData ledger = null;
        UUID id = null;
        NBTTagCompound context = null, before = null, after = null;
        ItemStack film = null;
        boolean busy = false, playerChanged = false, tileChanged = false, complete = false;
        int metadata = 0;
        try {
            if (!request(player, shrine)) return false;
            frame = new MaidPlayerTransferSnapshot(player);
            var held = player.getCurrentEquippedItem();
            if (held != frame.main[frame.selected]) return false;
            if (insert) {
                if (!MaidLifecycleItems.isFilm(held) || !shrine.beginInsertUse()) return false;
                busy = true;
                film = held.copy();
            } else {
                if (held != null) return false;
                film = shrine.beginUse();
                if (film == null) return false;
            }
            busy = true;
            before = snapshot(shrine);
            metadata = player.worldObj.getBlockMetadata(shrine.xCoord, shrine.yCoord, shrine.zCoord);
            after = (NBTTagCompound) before.copy();
            if (insert) after.setTag("StorageItem", ProtectedItemCodec.encode(film));
            else after.removeTag("StorageItem");
            var source = new NBTTagCompound();
            var playerSource = new NBTTagCompound();
            player.writeToNBT(playerSource);
            source.setTag("Player", playerSource.copy());
            source.setTag("Tile", before.copy());
            source.setInteger("Metadata", metadata);
            context = new NBTTagCompound();
            context.setString("Kind", insert ? "shrine_insert" : "shrine_extract");
            context.setTag("Source", source);
            ledger = MaidLifecycleData.get(player.worldObj);
            id = ledger.prepareReturnedFilm(
                ProtectedItemCodec.encode(film),
                player.dimension,
                shrine.xCoord + .5,
                shrine.yCoord + .5,
                shrine.zCoord + .5,
                context);
            MaidLifecycleDurability.ledger(player.worldObj, ledger);
            if (!request(player, shrine) || !frame.unchanged()) return false;
            MaidLifecycleDurability.checkShrine(shrine, before, metadata);
            if (insert) {
                playerChanged = true;
                frame.apply(frame.selected, null);
                frame.persist();
                if (!request(player, shrine) || !frame.applied()) return false;
                MaidLifecycleDurability.checkShrine(shrine, before, metadata);
                tileChanged = true;
                shrine.replaceUse(null, film);
                MaidLifecycleDurability.shrine(shrine, after, metadata);
            } else {
                tileChanged = true;
                shrine.replaceUse(film, null);
                MaidLifecycleDurability.shrine(shrine, after, metadata);
                if (!request(player, shrine) || !frame.unchanged()) return false;
                playerChanged = true;
                frame.apply(frame.selected, film);
                frame.persist();
            }
            if (!request(player, shrine) || !frame.applied()) return false;
            MaidLifecycleDurability.checkShrine(shrine, after, metadata);
            complete = true;
            ledger.finishPreparedReturn(id, context);
            MaidLifecycleDurability.ledger(player.worldObj, ledger);
            player.inventoryContainer.detectAndSendChanges();
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, shrine, failure);
            return false;
        } finally {
            if (id != null && !complete) {
                try {
                    // First retract the destination; never refund over a callback-owned successor.
                    if (insert && !frame.touched() && !frame.originalStored())
                        throw new IllegalStateException("Original player film changed before owned source removal");
                    if (insert && tileChanged) {
                        MaidLifecycleDurability.checkShrine(shrine, after, metadata);
                        shrine.replaceUse(film, null);
                        MaidLifecycleDurability.shrine(shrine, before, metadata);
                    }
                    if (playerChanged) frame.retract();
                    if (!insert && tileChanged) {
                        MaidLifecycleDurability.checkShrine(shrine, after, metadata);
                        shrine.replaceUse(null, film);
                        MaidLifecycleDurability.shrine(shrine, before, metadata);
                    }
                    if (!tileChanged) MaidLifecycleDurability.checkShrine(shrine, before, metadata);
                    ledger.finishPreparedReturn(id, context);
                    MaidLifecycleDurability.ledger(player.worldObj, ledger);
                } catch (Exception | LinkageError | AssertionError failure) {
                    report(player, shrine, failure);
                    if (ledger.returnedPhase(id) != null) ledger.holdReturnedFilm(id);
                    try {
                        MaidLifecycleDurability.ledger(frame.world, ledger);
                    } catch (Exception | LinkageError | AssertionError persistenceFailure) {
                        report(player, shrine, persistenceFailure);
                    }
                }
            }
            if (busy) try {
                shrine.finishUse(false);
            } catch (Exception | LinkageError | AssertionError failure) {
                report(player, shrine, failure);
            }
            ACTIVE.remove(shrine);
        }
    }

    private static boolean request(EntityPlayer player, MaidShrineTile shrine) {
        var world = shrine.getWorldObj();
        return MaidLifecycleService.canUse(player) && world == player.worldObj
            && !shrine.isInvalid()
            && !MaidTileReturns.isPrepared(shrine)
            && world.getChunkProvider()
                .chunkExists(shrine.xCoord >> 4, shrine.zCoord >> 4)
            && world.getTileEntity(shrine.xCoord, shrine.yCoord, shrine.zCoord) == shrine
            && world.getBlock(shrine.xCoord, shrine.yCoord, shrine.zCoord) == MaidResurrectionContent.SHRINE
            && player.getDistanceSq(shrine.xCoord + .5, shrine.yCoord + .5, shrine.zCoord + .5) <= 64;
    }

    public static NBTTagCompound snapshot(TileEntity tile) {
        var result = new NBTTagCompound();
        tile.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    private static void report(EntityPlayer player, MaidShrineTile shrine, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(
            Side.SERVER,
            "shrine_transfer/" + player.getUniqueID() + "/" + shrine.xCoord + "/" + shrine.yCoord + "/" + shrine.zCoord,
            failure,
            player.getUniqueID());
    }
}
