package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Collections;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;

/** 两种单次出生共用写前保全；重启只确认已有精确目标，绝不补造候选实体 */
final class MaidRedemptionDelivery {

    private MaidRedemptionDelivery() {}

    static boolean blocks(EntityMaid maid) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return false;
        try {
            var pending = MaidLifecycleData.get(maid.worldObj)
                .pendingMaid(maid.getUniqueID());
            if (pending.hasNoTags()) return false;
            if (maid.dimension != pending.getInteger("Dimension")
                || maid.worldObj.provider.dimensionId != pending.getInteger("Dimension")
                || !pending.getCompoundTag("Maid")
                    .equals(MaidLifecycleDurability.maidSnapshot(maid)))
                IntegrationGuard.report(
                    Side.SERVER,
                    "redemption/candidate/" + maid.getUniqueID(),
                    new IllegalStateException(
                        "Unsettled maid successor remains unavailable; its unknown NBT is not rewritten"),
                    UUID.fromString(pending.getString("Actor")));
            // 拒绝使用不等于取得删除/扣费授权；后者仍须完整快照一致
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, "redemption/candidate/" + maid.getUniqueID(), failure);
            return true;
        }
    }

    static MaidLifecycleService.Result publish(EntityPlayer player, MaidLifecycleData ledger, UUID token,
        EntityMaid maid, ItemStack source, MaidShrineTile shrine, int x, int y, int z) {
        var world = player.worldObj;
        MaidLifecycleDurability.Publication publication = null;
        boolean prepared = false, external = false, costs = false, complete = false;
        try {
            var origin = Source.capture(player, source, shrine, x, y, z);
            MaidLifecycleDurability.requirePlayer(player);
            MaidLifecycleDurability.requireEntities(world);
            CompatServices.lifecycleStorage()
                .prepareInitialSize(maid, player.getUniqueID());
            var expected = MaidLifecycleDurability.maidSnapshot(maid);
            publication = new MaidLifecycleDurability.Publication(maid, expected);
            var intent = origin.intent(expected);
            if (!origin.original()) return MaidLifecycleService.Result.DENIED;
            // 即使实际写出后读回失败，内存pending也必须保留，不能当作没有准备过
            prepared = true;
            ledger.prepareRedemption(world, token, intent);
            if (!origin.original()) return MaidLifecycleService.Result.DENIED;
            external = true;
            if (!world.spawnEntityInWorld(maid) || !maid.isEntityAlive() || !MaidSpawnChecks.present(maid, world))
                return MaidLifecycleService.Result.SPAWN_REJECTED;
            if (!publication.owned(world) || !origin.original()) return MaidLifecycleService.Result.DENIED;
            MaidLifecycleDurability.entities(world, Collections.singletonList(publication), true);
            if (!origin.original()) return MaidLifecycleService.Result.DENIED;
            ledger.beginRedemptionCosts(world, token);
            costs = true;
            origin.pay();
            publication.check(world, true);
            ledger.finishRedemption(world, token, true);
            complete = true;
            world.setEntityState(maid, (byte) 7);
            return MaidLifecycleService.Result.SUCCESS;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, token, failure);
            return MaidLifecycleService.Result.DENIED;
        } finally {
            if (prepared && !costs && !complete) {
                try {
                    if (external) {
                        publication.discard(world);
                        MaidLifecycleDurability.entities(world, Collections.singletonList(publication), false);
                    }
                    if (!ledger.redemption(token)
                        .hasNoTags()) ledger.finishRedemption(world, token, false);
                } catch (Exception | LinkageError | AssertionError failure) {
                    report(player, token, failure);
                }
            }
            ledger.endRedemption(token);
        }
    }

    static void resume(EntityPlayer player) {
        if (!MaidLifecycleService.canUse(player)) return;
        MaidLifecycleData ledger;
        try {
            ledger = MaidLifecycleData.get(player.worldObj);
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, null, failure);
            return;
        }
        for (var token : ledger.pendingRedemptions(player.getUniqueID())) resume(player, ledger, token);
    }

    static boolean resume(EntityPlayer player, MaidLifecycleData ledger, UUID token) {
        if (!ledger.resumeRedemption(token)) return false;
        MaidShrineTile claimed = null;
        try {
            var intent = ledger.redemption(token);
            MaidRedemptionRecord.validate(intent);
            if (!player.getUniqueID()
                .toString()
                .equals(intent.getString("Actor"))
                || player.worldObj.provider.dimensionId != intent.getInteger("Dimension")) return false;
            var world = player.worldObj;
            EntityMaid candidate = null;
            var identity = UUID.fromString(intent.getString("Candidate"));
            // 只读当前世界已经加载的实体，不以未发现推断世界中不存在该候选
            for (Object entity : world.loadedEntityList.toArray())
                if (entity instanceof EntityMaid maid && identity.equals(maid.getUniqueID())) {
                    if (candidate != null) throw new IllegalStateException("Duplicate pending maid identity");
                    candidate = maid;
                }
            if (candidate == null || !MaidSpawnChecks.present(candidate, world)) return false;
            var publication = new MaidLifecycleDurability.Publication(candidate, intent.getCompoundTag("Maid"));
            publication.check(world, true);
            MaidShrineTile shrine = null;
            if (intent.getBoolean("Shrine")) {
                int x = (int) Math.floor(intent.getDouble("X")), y = (int) Math.floor(intent.getDouble("Y")),
                    z = (int) Math.floor(intent.getDouble("Z"));
                if (!world.getChunkProvider()
                    .chunkExists(x >> 4, z >> 4) || !(world.getTileEntity(x, y, z) instanceof MaidShrineTile actual))
                    return false;
                shrine = actual;
                if (!shrine.beginRedemptionUse(shrine.getFilm())) return false;
                claimed = shrine;
            }
            var origin = new Source(player, intent, shrine);
            if (!origin.payable()) return false;
            MaidLifecycleDurability.requirePlayer(player);
            MaidLifecycleDurability.entities(world, Collections.singletonList(publication), true);
            ledger.beginRedemptionCosts(world, token);
            origin.pay();
            publication.check(world, true);
            ledger.finishRedemption(world, token, true);
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, token, failure);
            return false;
        } finally {
            if (claimed != null) claimed.finishUse(false);
            ledger.endRedemption(token);
        }
    }

    private static void report(EntityPlayer player, UUID token, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(
            Side.SERVER,
            "redemption/" + (token == null ? player.getUniqueID() : token),
            failure,
            player.getUniqueID());
    }

    private static final class Source {

        final EntityPlayer player;
        final World world;
        final MaidShrineTile shrine;
        final NBTTagCompound data;
        final InventoryPlayer inventory;
        final ItemStack[] main, armor;
        final ItemStack cursor;
        final ItemStack credential;
        final WorldProvider provider;

        Source(EntityPlayer player, NBTTagCompound data, MaidShrineTile shrine) {
            this.player = player;
            world = player.worldObj;
            provider = world.provider;
            this.shrine = shrine;
            this.data = (NBTTagCompound) data.copy();
            inventory = player.inventory;
            main = inventory.mainInventory;
            armor = inventory.armorInventory;
            cursor = data.getCompoundTag("Cursor")
                .hasNoTags() ? null : ProtectedItemCodec.decode(data.getCompoundTag("Cursor"));
            credential = shrine == null ? player.getCurrentEquippedItem() : null;
        }

        static Source capture(EntityPlayer player, ItemStack source, MaidShrineTile shrine, int x, int y, int z) {
            var data = new NBTTagCompound();
            data.setInteger("Version", 1);
            data.setString("Stage", "PREPARED");
            data.setString(
                "Actor",
                player.getUniqueID()
                    .toString());
            data.setInteger("Dimension", player.worldObj.provider.dimensionId);
            data.setDouble("X", x);
            data.setDouble("Y", y);
            data.setDouble("Z", z);
            data.setBoolean("Shrine", shrine != null);
            data.setInteger("Metadata", shrine == null ? 0 : player.worldObj.getBlockMetadata(x, y, z));
            data.setTag("Source", ProtectedItemCodec.encode(source));
            data.setTag(
                "Cursor",
                player.inventory.getItemStack() == null ? new NBTTagCompound()
                    : ProtectedItemCodec.encode(player.inventory.getItemStack()));
            var original = (NBTTagList) player.inventory.writeToNBT(new NBTTagList())
                .copy();
            data.setTag("Inventory", original);
            data.setInteger("Selected", player.inventory.currentItem);
            data.setFloat("Health", player.getHealth());
            data.setFloat("Maximum", player.getMaxHealth());
            data.setBoolean("Creative", player.capabilities.isCreativeMode);
            var tile = new NBTTagCompound();
            if (shrine != null) shrine.writeToNBT(tile);
            data.setTag("Tile", tile.copy());
            var resultTile = (NBTTagCompound) tile.copy();
            resultTile.removeTag("StorageItem");
            data.setTag("ResultTile", resultTile);
            var delivered = new NBTTagList();
            for (int i = 0; i < original.tagCount(); i++) {
                var item = (NBTTagCompound) original.getCompoundTagAt(i)
                    .copy();
                if (shrine == null && item.getByte("Slot") == player.inventory.currentItem) {
                    var empty = new ItemStack(MaidResurrectionContent.EMPTY_SLAB).writeToNBT(new NBTTagCompound());
                    empty.setByte("Slot", item.getByte("Slot"));
                    item = empty;
                }
                delivered.appendTag(item);
            }
            data.setTag("ResultInventory", delivered);
            return new Source(player, data, shrine);
        }

        NBTTagCompound intent(NBTTagCompound maid) {
            var result = (NBTTagCompound) data.copy();
            result.setString("Candidate", new UUID(maid.getLong("UUIDMost"), maid.getLong("UUIDLeast")).toString());
            result.setTag("Maid", maid.copy());
            MaidRedemptionRecord.validate(result);
            return result;
        }

        boolean context() {
            return MaidLifecycleService.canUse(player) && player.worldObj == world
                && world.provider == provider
                && player.dimension == data.getInteger("Dimension")
                && data.getString("Actor")
                    .equals(
                        player.getUniqueID()
                            .toString())
                && world.provider.dimensionId == data.getInteger("Dimension")
                && player.inventory == inventory
                && inventory.mainInventory == main
                && inventory.armorInventory == armor
                && inventory.currentItem == data.getInteger("Selected")
                && ItemStack.areItemStacksEqual(cursor, inventory.getItemStack())
                && player.capabilities.isCreativeMode == data.getBoolean("Creative")
                && player.getMaxHealth() == data.getFloat("Maximum")
                && player.getDistanceSq(
                    data.getDouble("X") + .5,
                    data.getDouble("Y") + (shrine == null ? 1 : .5),
                    data.getDouble("Z") + .5) <= 64;
        }

        boolean original() {
            if (!context() || player.getHealth() != data.getFloat("Health")
                || !data.getTagList("Inventory", Constants.NBT.TAG_COMPOUND)
                    .equals(inventory.writeToNBT(new NBTTagList())))
                return false;
            if (shrine != null) {
                MaidLifecycleDurability.checkShrine(shrine, data.getCompoundTag("Tile"), data.getInteger("Metadata"));
                return true;
            }
            var actual = player.getCurrentEquippedItem();
            return actual != null && actual == credential
                && ItemStack.areItemStacksEqual(actual, ProtectedItemCodec.decode(data.getCompoundTag("Source")))
                && MaidLifecycleService.releaseRequestAllowed(
                    player,
                    actual,
                    actual.copy(),
                    world,
                    inventory.currentItem,
                    (int) data.getDouble("X"),
                    (int) data.getDouble("Y"),
                    (int) data.getDouble("Z"))
                && context()
                && data.getTagList("Inventory", Constants.NBT.TAG_COMPOUND)
                    .equals(inventory.writeToNBT(new NBTTagList()));
        }

        boolean payable() {
            if (!context()) return false;
            var current = inventory.writeToNBT(new NBTTagList());
            if (!current.equals(data.getTagList("Inventory", Constants.NBT.TAG_COMPOUND))
                && !current.equals(data.getTagList("ResultInventory", Constants.NBT.TAG_COMPOUND))) return false;
            float health = player.getHealth();
            if (health != data.getFloat("Health") && health != paidHealth()) return false;
            if (shrine != null) {
                var actual = tile();
                if (!actual.equals(data.getCompoundTag("Tile")) && !actual.equals(data.getCompoundTag("ResultTile")))
                    return false;
                MaidLifecycleDurability.checkShrine(shrine, actual, data.getInteger("Metadata"));
            }
            return true;
        }

        float paidHealth() {
            return shrine != null && !data.getBoolean("Creative") ? .25F : data.getFloat("Health");
        }

        void pay() {
            if (!payable()) throw new IllegalStateException("Redemption source changed before costs");
            if (shrine == null) {
                if (!data.getTagList("ResultInventory", Constants.NBT.TAG_COMPOUND)
                    .equals(inventory.writeToNBT(new NBTTagList()))) {
                    var actual = player.getCurrentEquippedItem();
                    if (actual != credential || !ItemStack
                        .areItemStacksEqual(actual, ProtectedItemCodec.decode(data.getCompoundTag("Source"))))
                        throw new IllegalStateException("Unknown credential successor must remain intact");
                    inventory.setInventorySlotContents(
                        inventory.currentItem,
                        new ItemStack(MaidResurrectionContent.EMPTY_SLAB));
                }
            } else {
                if (data.getCompoundTag("Tile")
                    .equals(tile())) shrine.consumeUse(ProtectedItemCodec.decode(data.getCompoundTag("Source")));
                if (player.getHealth() != paidHealth()) player.setHealth(paidHealth());
            }
            inventory.markDirty();
            if (!settled()) throw new IllegalStateException("Redemption cost callback changed its exact destination");
            MaidLifecycleDurability.player(
                player,
                data.getTagList("ResultInventory", Constants.NBT.TAG_COMPOUND),
                inventory.currentItem,
                shrine == null ? null : paidHealth());
            if (shrine != null)
                MaidLifecycleDurability.shrine(shrine, data.getCompoundTag("ResultTile"), data.getInteger("Metadata"));
            if (!settled()) throw new IllegalStateException("Redemption changed during native cost save");
        }

        boolean settled() {
            return context() && player.getHealth() == paidHealth()
                && data.getTagList("ResultInventory", Constants.NBT.TAG_COMPOUND)
                    .equals(inventory.writeToNBT(new NBTTagList()))
                && (shrine == null || data.getCompoundTag("ResultTile")
                    .equals(tile()));
        }

        NBTTagCompound tile() {
            var result = new NBTTagCompound();
            shrine.writeToNBT(result);
            return result;
        }
    }
}
