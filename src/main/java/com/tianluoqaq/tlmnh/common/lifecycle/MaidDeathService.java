/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: EntityMaid.dropEquipment and entity/item/EntityTombstone.java
 * TartaricAcid contributors, MIT, see licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

/** 主人女仆死亡时将十二槽与胶片单份移交墓碑，事件拒绝出生时保留重试记录 */
public final class MaidDeathService {

    private MaidDeathService() {}

    /** true表示已由墓碑保管，调用者不得再次执行原生掉落 */
    public static boolean preserve(EntityMaid maid) {
        if (maid == null || maid.worldObj.isRemote || !maid.isTamed()) return false;
        UUID owner;
        try {
            owner = UUID.fromString(maid.func_152113_b());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        var world = maid.worldObj;
        var ledger = MaidLifecycleData.get(world);
        var token = ledger.deathToken(maid.getUniqueID());
        if (token != null && ledger.delivered(token)) return true;
        var full = new NBTTagCompound();
        maid.writeToNBT(full);
        var identity = MaidFilmData.identity(full);
        if (token == null) token = ledger.newFilm(owner, maid.getUniqueID(), identity);
        var items = new NBTTagList();
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null) items.appendTag(com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec.encode(stack));
        }
        var recovery = inventory.recovery()
            .write();
        for (int index = 0; index < recovery.tagCount(); index++) items.appendTag(
            recovery.getCompoundTagAt(index)
                .copy());
        items.appendTag(
            MaidLifecycleItems.credential(MaidResurrectionContent.FILM, token, owner)
                .writeToNBT(new NBTTagCompound()));
        double x = Math.floor(maid.posX) + 0.5D;
        double y = Math.max(5.0D, Math.min(world.getHeight() - 2.0D, Math.floor(maid.posY)));
        double z = Math.floor(maid.posZ) + 0.5D;
        ledger.depositDeath(
            owner,
            maid.getUniqueID(),
            identity,
            items,
            world.provider.dimensionId,
            x,
            y,
            z,
            maid.getCustomNameTag());
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) inventory.setInventorySlotContents(slot, null);
        inventory.recovery()
            .clear();
        try {
            ensureTombstone(world, token);
        } catch (RuntimeException exception) {
            TLMNotHard.LOG.error("Tombstone publication failed; inventory remains in world data", exception);
        }
        return true;
    }

    public static void retryPending(World world) {
        if (world.isRemote) return;
        var ledger = MaidLifecycleData.get(world);
        for (var token : ledger.pendingTombstones(world.provider.dimensionId)) {
            try {
                ensureTombstone(world, token);
            } catch (RuntimeException exception) {
                TLMNotHard.LOG.error("Pending tombstone publication failed", exception);
            }
        }
    }

    private static void ensureTombstone(World world, UUID token) {
        var ledger = MaidLifecycleData.get(world);
        var position = ledger.tombstonePosition(token);
        if (position == null
            || !world.blockExists((int) Math.floor(position.xCoord), 5, (int) Math.floor(position.zCoord))) return;
        if (ledger.reserve(token, MaidLifecycleData.Kind.FILM) == null) return;
        MaidTombstoneEntity tombstone = null;
        boolean published = false;
        try {
            tombstone = new MaidTombstoneEntity(world);
            tombstone.initialize(token, ledger.owner(token, MaidLifecycleData.Kind.FILM), ledger.tombstoneName(token));
            tombstone.setPosition(position.xCoord, position.yCoord, position.zCoord);
            if (world.spawnEntityInWorld(tombstone) && MaidSpawnChecks.present(tombstone, world)) {
                ledger.markPublished(token);
                published = true;
            }
        } finally {
            if (!published) MaidSpawnChecks.discard(tombstone);
            ledger.rollback(token);
        }
    }
}
