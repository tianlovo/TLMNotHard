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
            if (maid.dimension != pending.getInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION)
                || maid.worldObj.provider.dimensionId != pending.getInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION)
                || !pending.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_MAID)
                    .equals(MaidLifecycleDurability.maidSnapshot(maid)))
                IntegrationGuard.report(
                    Side.SERVER,
                    MaidLifecycleIds.ID_REDEMPTION_CANDIDATE + maid.getUniqueID(),
                    new IllegalStateException(
                        MaidLifecyclePart3Diagnostics.MSG_UNSETTLED_MAID_SUCCESSOR_REMAINS_UNAVAILABLE),
                    UUID.fromString(pending.getString(MaidLifecyclePart2NbtKeys.KEY_ACTOR)));
            // 拒绝使用不等于取得删除/扣费授权；后者仍须完整快照一致
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard
                .report(Side.SERVER, MaidLifecycleIds.ID_REDEMPTION_CANDIDATE + maid.getUniqueID(), failure);
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
            world.setEntityState(maid, (byte) MaidLifecycleTuning.MAX_SCHEMA_THRESHOLD_2);
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
                .equals(intent.getString(MaidLifecyclePart2NbtKeys.KEY_ACTOR))
                || player.worldObj.provider.dimensionId != intent.getInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION))
                return false;
            var world = player.worldObj;
            EntityMaid candidate = null;
            var identity = UUID.fromString(intent.getString(MaidLifecyclePart2NbtKeys.KEY_CANDIDATE));
            // 只读当前世界已经加载的实体，不以未发现推断世界中不存在该候选
            for (Object entity : world.loadedEntityList.toArray())
                if (entity instanceof EntityMaid maid && identity.equals(maid.getUniqueID())) {
                    if (candidate != null) throw new IllegalStateException(
                        MaidLifecyclePart3Diagnostics.MSG_DUPLICATE_PENDING_MAID_IDENTITY);
                    candidate = maid;
                }
            if (candidate == null || !MaidSpawnChecks.present(candidate, world)) return false;
            var publication = new MaidLifecycleDurability.Publication(
                candidate,
                intent.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_MAID));
            publication.check(world, true);
            MaidShrineTile shrine = null;
            if (intent.getBoolean(MaidLifecyclePart2NbtKeys.KEY_SHRINE)) {
                int x = (int) Math.floor(intent.getDouble(MaidLifecyclePart2NbtKeys.KEY_X)),
                    y = (int) Math.floor(intent.getDouble(MaidLifecyclePart2NbtKeys.KEY_Y)),
                    z = (int) Math.floor(intent.getDouble(MaidLifecyclePart2NbtKeys.KEY_Z));
                if (!world.getChunkProvider()
                    .chunkExists(
                        x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                        z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
                    || !(world.getTileEntity(x, y, z) instanceof MaidShrineTile actual)) return false;
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
            MaidLifecycleIds.ID_REDEMPTION + (token == null ? player.getUniqueID() : token),
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
            cursor = data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_CURSOR)
                .hasNoTags() ? null
                    : ProtectedItemCodec.decode(data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_CURSOR));
            credential = shrine == null ? player.getCurrentEquippedItem() : null;
        }

        static Source capture(EntityPlayer player, ItemStack source, MaidShrineTile shrine, int x, int y, int z) {
            var data = new NBTTagCompound();
            data.setInteger(MaidLifecyclePart2NbtKeys.KEY_VERSION, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
            data.setString(MaidLifecyclePart2NbtKeys.KEY_STAGE, MaidLifecyclePart2NbtKeys.KEY_PREPARED);
            data.setString(
                MaidLifecyclePart2NbtKeys.KEY_ACTOR,
                player.getUniqueID()
                    .toString());
            data.setInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION, player.worldObj.provider.dimensionId);
            data.setDouble(MaidLifecyclePart2NbtKeys.KEY_X, x);
            data.setDouble(MaidLifecyclePart2NbtKeys.KEY_Y, y);
            data.setDouble(MaidLifecyclePart2NbtKeys.KEY_Z, z);
            data.setBoolean(MaidLifecyclePart2NbtKeys.KEY_SHRINE, shrine != null);
            data.setInteger(
                MaidLifecyclePart2NbtKeys.KEY_METADATA,
                shrine == null ? MaidLifecycleTuning.CORE_INITIAL_VALUE : player.worldObj.getBlockMetadata(x, y, z));
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE, ProtectedItemCodec.encode(source));
            data.setTag(
                MaidLifecyclePart2NbtKeys.KEY_CURSOR,
                player.inventory.getItemStack() == null ? new NBTTagCompound()
                    : ProtectedItemCodec.encode(player.inventory.getItemStack()));
            var original = (NBTTagList) player.inventory.writeToNBT(new NBTTagList())
                .copy();
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_INVENTORY, original);
            data.setInteger(MaidLifecyclePart2NbtKeys.KEY_SELECTED, player.inventory.currentItem);
            data.setFloat(MaidLifecyclePart2NbtKeys.KEY_HEALTH, player.getHealth());
            data.setFloat(MaidLifecyclePart2NbtKeys.KEY_MAXIMUM, player.getMaxHealth());
            data.setBoolean(MaidLifecyclePart2NbtKeys.KEY_CREATIVE, player.capabilities.isCreativeMode);
            var tile = new NBTTagCompound();
            if (shrine != null) shrine.writeToNBT(tile);
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_TILE, tile.copy());
            var resultTile = (NBTTagCompound) tile.copy();
            resultTile.removeTag(MaidLifecyclePart2NbtKeys.KEY_STORAGE_ITEM);
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_RESULT_TILE, resultTile);
            var delivered = new NBTTagList();
            for (int i = 0; i < original.tagCount(); i++) {
                var item = (NBTTagCompound) original.getCompoundTagAt(i)
                    .copy();
                if (shrine == null
                    && item.getByte(MaidLifecyclePart2NbtKeys.KEY_SLOT) == player.inventory.currentItem) {
                    var empty = new ItemStack(MaidResurrectionContent.EMPTY_SLAB).writeToNBT(new NBTTagCompound());
                    empty.setByte(MaidLifecyclePart2NbtKeys.KEY_SLOT, item.getByte(MaidLifecyclePart2NbtKeys.KEY_SLOT));
                    item = empty;
                }
                delivered.appendTag(item);
            }
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_RESULT_INVENTORY, delivered);
            return new Source(player, data, shrine);
        }

        NBTTagCompound intent(NBTTagCompound maid) {
            var result = (NBTTagCompound) data.copy();
            result.setString(
                MaidLifecyclePart2NbtKeys.KEY_CANDIDATE,
                new UUID(
                    maid.getLong(MaidLifecyclePart2NbtKeys.KEY_UUID_MOST),
                    maid.getLong(MaidLifecyclePart2NbtKeys.KEY_UUID_LEAST)).toString());
            result.setTag(MaidLifecyclePart2NbtKeys.KEY_MAID, maid.copy());
            MaidRedemptionRecord.validate(result);
            return result;
        }

        boolean context() {
            return MaidLifecycleService.canUse(player) && player.worldObj == world
                && world.provider == provider
                && player.dimension == data.getInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION)
                && data.getString(MaidLifecyclePart2NbtKeys.KEY_ACTOR)
                    .equals(
                        player.getUniqueID()
                            .toString())
                && world.provider.dimensionId == data.getInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION)
                && player.inventory == inventory
                && inventory.mainInventory == main
                && inventory.armorInventory == armor
                && inventory.currentItem == data.getInteger(MaidLifecyclePart2NbtKeys.KEY_SELECTED)
                && ItemStack.areItemStacksEqual(cursor, inventory.getItemStack())
                && player.capabilities.isCreativeMode == data.getBoolean(MaidLifecyclePart2NbtKeys.KEY_CREATIVE)
                && player.getMaxHealth() == data.getFloat(MaidLifecyclePart2NbtKeys.KEY_MAXIMUM)
                && player.getDistanceSq(
                    data.getDouble(MaidLifecyclePart2NbtKeys.KEY_X) + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR,
                    data.getDouble(MaidLifecyclePart2NbtKeys.KEY_Y)
                        + (shrine == null ? MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
                            : MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR),
                    data.getDouble(MaidLifecyclePart2NbtKeys.KEY_Z) + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR)
                    <= MaidLifecycleTuning.CAPTURE_SERVICE_ADMITTED_PARAM;
        }

        boolean original() {
            if (!context() || player.getHealth() != data.getFloat(MaidLifecyclePart2NbtKeys.KEY_HEALTH)
                || !data.getTagList(MaidLifecyclePart2NbtKeys.KEY_INVENTORY, Constants.NBT.TAG_COMPOUND)
                    .equals(inventory.writeToNBT(new NBTTagList())))
                return false;
            if (shrine != null) {
                MaidLifecycleDurability.checkShrine(
                    shrine,
                    data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_TILE),
                    data.getInteger(MaidLifecyclePart2NbtKeys.KEY_METADATA));
                return true;
            }
            var actual = player.getCurrentEquippedItem();
            return actual != null && actual == credential
                && ItemStack.areItemStacksEqual(
                    actual,
                    ProtectedItemCodec.decode(data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE)))
                && MaidLifecycleService.releaseRequestAllowed(
                    player,
                    actual,
                    actual.copy(),
                    world,
                    inventory.currentItem,
                    (int) data.getDouble(MaidLifecyclePart2NbtKeys.KEY_X),
                    (int) data.getDouble(MaidLifecyclePart2NbtKeys.KEY_Y),
                    (int) data.getDouble(MaidLifecyclePart2NbtKeys.KEY_Z))
                && context()
                && data.getTagList(MaidLifecyclePart2NbtKeys.KEY_INVENTORY, Constants.NBT.TAG_COMPOUND)
                    .equals(inventory.writeToNBT(new NBTTagList()));
        }

        boolean payable() {
            if (!context()) return false;
            var current = inventory.writeToNBT(new NBTTagList());
            if (!current.equals(data.getTagList(MaidLifecyclePart2NbtKeys.KEY_INVENTORY, Constants.NBT.TAG_COMPOUND))
                && !current.equals(
                    data.getTagList(MaidLifecyclePart2NbtKeys.KEY_RESULT_INVENTORY, Constants.NBT.TAG_COMPOUND)))
                return false;
            float health = player.getHealth();
            if (health != data.getFloat(MaidLifecyclePart2NbtKeys.KEY_HEALTH) && health != paidHealth()) return false;
            if (shrine != null) {
                var actual = tile();
                if (!actual.equals(data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_TILE))
                    && !actual.equals(data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_RESULT_TILE))) return false;
                MaidLifecycleDurability
                    .checkShrine(shrine, actual, data.getInteger(MaidLifecyclePart2NbtKeys.KEY_METADATA));
            }
            return true;
        }

        float paidHealth() {
            return shrine != null && !data.getBoolean(MaidLifecyclePart2NbtKeys.KEY_CREATIVE)
                ? MaidLifecycleTuning.REDEMPTION_DELIVERY_PAID_HEALTH_FACTOR
                : data.getFloat(MaidLifecyclePart2NbtKeys.KEY_HEALTH);
        }

        void pay() {
            if (!payable()) throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_REDEMPTION_SOURCE_CHANGED_BEFORE_COSTS);
            if (shrine == null) {
                if (!data.getTagList(MaidLifecyclePart2NbtKeys.KEY_RESULT_INVENTORY, Constants.NBT.TAG_COMPOUND)
                    .equals(inventory.writeToNBT(new NBTTagList()))) {
                    var actual = player.getCurrentEquippedItem();
                    if (actual != credential || !ItemStack.areItemStacksEqual(
                        actual,
                        ProtectedItemCodec.decode(data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE))))
                        throw new IllegalStateException(
                            MaidLifecyclePart3Diagnostics.MSG_UNKNOWN_CREDENTIAL_SUCCESSOR_MUST_REMAIN);
                    inventory.setInventorySlotContents(
                        inventory.currentItem,
                        new ItemStack(MaidResurrectionContent.EMPTY_SLAB));
                }
            } else {
                if (data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_TILE)
                    .equals(tile()))
                    shrine.consumeUse(
                        ProtectedItemCodec.decode(data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE)));
                if (player.getHealth() != paidHealth()) player.setHealth(paidHealth());
            }
            inventory.markDirty();
            if (!settled())
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_REDEMPTION_COST_CALLBACK_CHANGED_ITS);
            MaidLifecycleDurability.player(
                player,
                data.getTagList(MaidLifecyclePart2NbtKeys.KEY_RESULT_INVENTORY, Constants.NBT.TAG_COMPOUND),
                inventory.currentItem,
                shrine == null ? null : paidHealth());
            if (shrine != null) MaidLifecycleDurability.shrine(
                shrine,
                data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_RESULT_TILE),
                data.getInteger(MaidLifecyclePart2NbtKeys.KEY_METADATA));
            if (!settled()) throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_REDEMPTION_CHANGED_DURING_NATIVE_COST);
        }

        boolean settled() {
            return context() && player.getHealth() == paidHealth()
                && data.getTagList(MaidLifecyclePart2NbtKeys.KEY_RESULT_INVENTORY, Constants.NBT.TAG_COMPOUND)
                    .equals(inventory.writeToNBT(new NBTTagList()))
                && (shrine == null || data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_RESULT_TILE)
                    .equals(tile()));
        }

        NBTTagCompound tile() {
            var result = new NBTTagCompound();
            shrine.writeToNBT(result);
            return result;
        }
    }
}
