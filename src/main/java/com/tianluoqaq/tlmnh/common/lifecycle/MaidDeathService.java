/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: EntityMaid.dropEquipment and entity/item/EntityTombstone.java
 * TartaricAcid contributors, MIT, see licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 死亡入站、原生野生掉落和墓碑handle；物品及经验仍由现有各自出口单次兑现 */
public final class MaidDeathService {

    private static final Set<EntityMaid> COMBAT_PENDING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<EntityMaid> WILD_CONFIRMED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<EntityMaid> WILD_PENDING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<String> PUBLICATION_FIELDS = new HashSet<>(
        Arrays.asList(
            MaidLifecycleNbtKeys.KEY_VERSION,
            MaidLifecycleNbtKeys.KEY_STAGE,
            MaidLifecycleNbtKeys.KEY_SNAPSHOT,
            MaidLifecycleNbtKeys.KEY_OBSERVED));

    private MaidDeathService() {}

    /** true表示本域已接管，失败也不得退回第二个原生装备出口 */
    public static boolean preserve(EntityMaid maid) {
        var owner = owner(maid);
        if (owner == null || maid.getHealth() > 0 && !MaidDeathIngress.blocks(maid)) return false;
        try {
            var ledger = MaidLifecycleData.get(maid.worldObj);
            var token = ledger.deathToken(maid.getUniqueID());
            if (token != null && ledger.delivered(token) && !ledger.pendingDeath(maid.getUniqueID())) return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(MaidLifecycleIds.ID_DEATH_INGRESS + maid.getUniqueID(), failure);
        }
        maid.getCombatCadence()
            .freezeForDeath();
        if (deferCombatDeath(maid)) return true;
        boolean owned = MaidDeathIngress.preserve(maid, owner);
        try {
            var ledger = MaidLifecycleData.get(maid.worldObj);
            var token = ledger.deathToken(maid.getUniqueID());
            if (token != null && ledger.delivered(token) && !ledger.pendingDeath(maid.getUniqueID()))
                ensureTombstoneSafely(maid.worldObj, token);
        } catch (Exception | LinkageError | AssertionError failure) {
            report(MaidLifecycleIds.ID_DEATH_INGRESS + maid.getUniqueID(), failure);
        }
        return owned;
    }

    private static UUID owner(EntityMaid maid) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote || !maid.isTamed()) return null;
        try {
            return UUID.fromString(maid.func_152113_b());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static boolean blocksSource(EntityMaid maid) {
        return maid != null && maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.COMBAT_DEFERRED)
            || MaidDeathIngress.blocks(maid)
            || maid != null && maid.worldObj != null
                && !maid.worldObj.isRemote
                && maid.getEntityData()
                    .hasKey(MaidLifecycleNbtKeys.WILD_TAG)
                && !WILD_CONFIRMED.contains(maid);
    }

    public static boolean canFinishDeath(EntityMaid maid, boolean nativeDead) {
        if ((nativeDead || maid.getHealth() <= 0) && deferCombatDeath(maid)) return false;
        completeDeferred(maid);
        if (maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.COMBAT_DEFERRED)) return false;
        if (maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.WILD_TAG)) return confirmWildHold(maid);
        boolean pending = MaidDeathIngress.blocks(maid);
        if (!nativeDead && !pending) return true;
        var owner = owner(maid);
        if (owner == null) return !pending;
        MaidDeathIngress.preserve(maid, owner);
        try {
            var ledger = MaidLifecycleData.get(maid.worldObj);
            var token = ledger.deathToken(maid.getUniqueID());
            return token != null && ledger.delivered(token)
                && !ledger.pendingDeath(maid.getUniqueID())
                && !MaidDeathIngress.blocks(maid);
        } catch (Exception | LinkageError | AssertionError failure) {
            report(MaidLifecycleIds.ID_DEATH_INGRESS + maid.getUniqueID(), failure);
            return false;
        }
    }

    public static int nativeDeathExperience(EntityMaid maid) {
        if (maid.getCombatHitReceipt()
            .blocksTransfer()
            || maid.getEntityData()
                .hasKey(MaidLifecycleNbtKeys.COMBAT_DEFERRED))
            return MaidLifecycleTuning.CORE_INITIAL_VALUE;
        if (maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.WILD_TAG) || MaidDeathIngress.blocks(maid))
            return MaidLifecycleTuning.CORE_INITIAL_VALUE;
        try {
            var ledger = MaidLifecycleData.get(maid.worldObj);
            var token = ledger.deathToken(maid.getUniqueID());
            if (token != null && ledger.delivered(token)) return MaidLifecycleTuning.CORE_INITIAL_VALUE;
            return MaidInteractionTransfer.availableExperience(maid);
        } catch (Exception | LinkageError | AssertionError failure) {
            report(MaidLifecycleIds.ID_DEATH_INGRESS + maid.getUniqueID(), failure);
            return MaidLifecycleTuning.CORE_INITIAL_VALUE;
        }
    }

    /** 只在原生dropEquipment调用；entityDropItem保留captureDrops与后续LivingDropsEvent的决定权 */
    public static void dropUnowned(EntityMaid maid) {
        if (owner(maid) != null) return;
        maid.getCombatCadence()
            .freezeForDeath();
        if (deferCombatDeath(maid)) return;
        NBTTagCompound source = null;
        try {
            boolean opaqueHold = false;
            for (Object key : maid.getEntityData()
                .func_150296_c()) if (((String) key).startsWith(MaidLifecycleNbtKeys.TAG_2)) opaqueHold = true;
            if (opaqueHold) MaidInteractionTransfer.preserveDeathBalance(maid);
            source = MaidDeathIngress.snapshot(maid);
            if (opaqueHold || !IntegrationGuard.available(Side.SERVER, ExternalFeature.ITEM_DELIVERY)) {
                holdWild(maid, source);
                if (!confirmWildHold(maid)) return;
                if (!IntegrationGuard.available(Side.SERVER, ExternalFeature.ITEM_DELIVERY)) return;
            }
            var inventory = maid.getMaidInventory();
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                var stack = inventory.getStackInSlot(slot);
                if (stack == null) continue;
                var expected = stack.copy();
                var drop = maid.entityDropItem(expected.copy(), MaidLifecycleTuning.CORE_INITIAL_VALUE);
                if (drop == null || !ItemStack.areItemStacksEqual(drop.getEntityItem(), expected)
                    || inventory.getStackInSlot(slot) != stack
                    || !ItemStack.areItemStacksEqual(stack, expected))
                    throw new IllegalStateException(
                        MaidLifecycleDiagnostics.MSG_UNKNOWN_NATIVE_WILD_EQUIPMENT_SUCCESSOR);
                inventory.setInventorySlotContents(slot, null);
            }
            while (inventory.recovery()
                .size() > 0) {
                var actual = inventory.recovery()
                    .firstForTransfer();
                var expected = actual.copy();
                var drop = maid.entityDropItem(expected.copy(), MaidLifecycleTuning.CORE_INITIAL_VALUE);
                if (drop == null || !ItemStack.areItemStacksEqual(drop.getEntityItem(), expected)
                    || !ItemStack.areItemStacksEqual(actual, expected)
                    || !inventory.recovery()
                        .takeFirstForTransfer(actual))
                    throw new IllegalStateException(
                        MaidLifecycleDiagnostics.MSG_UNKNOWN_NATIVE_WILD_RECOVERY_SUCCESSOR);
            }
        } catch (Exception | LinkageError | AssertionError failure) {
            report(MaidLifecycleIds.ID_WILD_DEATH + maid.getUniqueID(), failure);
            if (failure instanceof LinkageError)
                IntegrationGuard.trip(Side.SERVER, ExternalFeature.ITEM_DELIVERY, failure);
            try {
                if (source != null) {
                    holdWild(maid, source);
                    var marker = maid.getEntityData()
                        .getCompoundTag(MaidLifecycleNbtKeys.WILD_TAG);
                    if (marker.hasKey(MaidLifecycleNbtKeys.KEY_VERSION, Constants.NBT.TAG_INT)
                        && marker.getInteger(MaidLifecycleNbtKeys.KEY_VERSION)
                            == MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
                        && marker.hasKey(MaidLifecycleNbtKeys.KEY_ID, Constants.NBT.TAG_STRING)
                        && marker.hasKey(MaidLifecycleNbtKeys.KEY_RAW, Constants.NBT.TAG_COMPOUND)
                        && marker.func_150296_c()
                            .size() == MaidLifecycleTuning.TARGET_SIZE_THRESHOLD) {
                        var observed = MaidDeathIngress.snapshot(maid);
                        marker.setString(
                            MaidLifecycleNbtKeys.KEY_OBSERVED_ID,
                            UUID.randomUUID()
                                .toString());
                        marker.setTag(MaidLifecycleNbtKeys.KEY_OBSERVED_RAW, observed);
                        WILD_CONFIRMED.remove(maid);
                        confirmWildHold(maid);
                    }
                }
            } catch (Exception | LinkageError | AssertionError retentionFailure) {
                report(
                    MaidLifecycleIds.ID_WILD_DEATH + maid.getUniqueID() + MaidLifecycleIds.ID_RETENTION,
                    retentionFailure);
            }
            if (source == null && !maid.getEntityData()
                .hasKey(MaidLifecycleNbtKeys.WILD_TAG)) {
                var unreadable = new NBTTagCompound();
                unreadable
                    .setInteger(MaidLifecycleNbtKeys.KEY_VERSION, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
                unreadable.setString(MaidLifecycleNbtKeys.KEY_STAGE, MaidLifecycleNbtKeys.KEY_UNREADABLE);
                maid.getEntityData()
                    .setTag(MaidLifecycleNbtKeys.WILD_TAG, unreadable);
                WILD_PENDING.add(maid);
            }
        }
    }

    private static void holdWild(EntityMaid maid, NBTTagCompound source) {
        if (!maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.WILD_TAG)) {
            var marker = new NBTTagCompound();
            marker.setInteger(MaidLifecycleNbtKeys.KEY_VERSION, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
            marker.setString(
                MaidLifecycleNbtKeys.KEY_ID,
                UUID.randomUUID()
                    .toString());
            marker.setTag(MaidLifecycleNbtKeys.KEY_RAW, source.copy());
            maid.getEntityData()
                .setTag(MaidLifecycleNbtKeys.WILD_TAG, marker);
        }
        confirmWildHold(maid);
    }

    private static boolean confirmWildHold(EntityMaid maid) {
        if (WILD_CONFIRMED.contains(maid)) return true;
        try {
            var data = maid.getEntityData();
            if (!data.hasKey(MaidLifecycleNbtKeys.WILD_TAG, Constants.NBT.TAG_COMPOUND))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_WILD_DEATH_HOLD_MARKER);
            var marker = data.getCompoundTag(MaidLifecycleNbtKeys.WILD_TAG);
            boolean observed = marker.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED_ID)
                || marker.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED_RAW);
            if (marker.func_150296_c()
                .size()
                != (observed ? MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM
                    : MaidLifecycleTuning.TARGET_SIZE_THRESHOLD)
                || !marker.hasKey(MaidLifecycleNbtKeys.KEY_VERSION, Constants.NBT.TAG_INT)
                || marker.getInteger(MaidLifecycleNbtKeys.KEY_VERSION)
                    != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
                || !marker.hasKey(MaidLifecycleNbtKeys.KEY_ID, Constants.NBT.TAG_STRING)
                || !marker.hasKey(MaidLifecycleNbtKeys.KEY_RAW, Constants.NBT.TAG_COMPOUND)
                || marker.getCompoundTag(MaidLifecycleNbtKeys.KEY_RAW)
                    .hasNoTags())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_WILD_DEATH_HOLD_MARKER);
            if (observed && (!marker.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED_ID, Constants.NBT.TAG_STRING)
                || !marker.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED_RAW, Constants.NBT.TAG_COMPOUND)
                || marker.getCompoundTag(MaidLifecycleNbtKeys.KEY_OBSERVED_RAW)
                    .hasNoTags()))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_OBSERVED_WILD_DEATH_SUCCESSOR);
            var ledger = MaidLifecycleData.get(maid.worldObj);
            ledger.retainUnownedDeath(
                UUID.fromString(marker.getString(MaidLifecycleNbtKeys.KEY_ID)),
                marker.getCompoundTag(MaidLifecycleNbtKeys.KEY_RAW),
                maid.worldObj.provider.dimensionId,
                maid.posX,
                maid.posY,
                maid.posZ);
            if (observed) ledger.retainUnownedDeath(
                UUID.fromString(marker.getString(MaidLifecycleNbtKeys.KEY_OBSERVED_ID)),
                marker.getCompoundTag(MaidLifecycleNbtKeys.KEY_OBSERVED_RAW),
                maid.worldObj.provider.dimensionId,
                maid.posX,
                maid.posY,
                maid.posZ);
            MaidLifecycleDurability.ledger(maid.worldObj, ledger);
            WILD_CONFIRMED.add(maid);
            WILD_PENDING.remove(maid);
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            WILD_PENDING.add(maid);
            report(MaidLifecycleIds.ID_WILD_DEATH + maid.getUniqueID(), failure);
            return false;
        }
    }

    /** 同一攻击栈内只登记，不等待自己返回，也不编码尚未结算的装备 */
    private static boolean deferCombatDeath(EntityMaid maid) {
        if (!maid.getCombatHitReceipt()
            .blocksTransfer()) return false;
        maid.getCombatCadence()
            .freezeForDeath();
        if (!maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.COMBAT_DEFERRED)) {
            var marker = new NBTTagCompound();
            marker.setInteger(MaidLifecycleNbtKeys.KEY_VERSION, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
            maid.getEntityData()
                .setTag(MaidLifecycleNbtKeys.COMBAT_DEFERRED, marker);
        }
        COMBAT_PENDING.add(maid);
        return true;
    }

    /** 仅在窄调用关闭且无HELD时继续既有死亡出口，快照取结算后的真实库存 */
    public static void completeDeferred(EntityMaid maid) {
        if (maid == null || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.getEntityData()
                .hasKey(MaidLifecycleNbtKeys.COMBAT_DEFERRED))
            return;
        COMBAT_PENDING.add(maid);
        if (maid.getCombatHitReceipt()
            .blocksTransfer()) return;
        var raw = maid.getEntityData()
            .getTag(MaidLifecycleNbtKeys.COMBAT_DEFERRED);
        if (!(raw instanceof NBTTagCompound marker)
            || !marker.hasKey(MaidLifecycleNbtKeys.KEY_VERSION, Constants.NBT.TAG_INT)
            || marker.getInteger(MaidLifecycleNbtKeys.KEY_VERSION) != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
            || marker.func_150296_c()
                .size() != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
            || maid.getHealth() > 0) {
            report(
                MaidLifecycleIds.ID_COMBAT_DEATH + maid.getUniqueID(),
                new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_DELAYED_COMBAT_DEATH_RETAINED));
            return;
        }
        maid.getEntityData()
            .removeTag(MaidLifecycleNbtKeys.COMBAT_DEFERRED);
        COMBAT_PENDING.remove(maid);
        try {
            if (owner(maid) != null) preserve(maid);
            else dropUnowned(maid);
        } catch (Exception | LinkageError | AssertionError failure) {
            maid.getEntityData()
                .setTag(MaidLifecycleNbtKeys.COMBAT_DEFERRED, raw.copy());
            COMBAT_PENDING.add(maid);
            report(MaidLifecycleIds.ID_COMBAT_DEATH + maid.getUniqueID(), failure);
        }
    }

    public static void retryPending(World world) {
        if (world.isRemote) return;
        for (var maid : new ArrayList<>(COMBAT_PENDING)) if (maid.worldObj == world) completeDeferred(maid);
        MaidDeathIngress.retry(world);
        WILD_CONFIRMED.removeIf(maid -> maid.isDead);
        for (var maid : new ArrayList<>(WILD_PENDING)) if (maid.worldObj == world) confirmWildHold(maid);
        var ledger = MaidLifecycleData.get(world);
        for (var token : ledger.pendingTombstones(world.provider.dimensionId)) ensureTombstoneSafely(world, token);
        MaidDeathExperience.retryPending(world);
    }

    static void unload(World world) {
        MaidDeathIngress.unload(world);
        COMBAT_PENDING.removeIf(maid -> maid.worldObj == world);
        WILD_CONFIRMED.removeIf(maid -> maid.worldObj == world);
        WILD_PENDING.removeIf(maid -> maid.worldObj == world);
    }

    static boolean onJoin(EntityMaid maid) {
        if (maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.COMBAT_DEFERRED)) {
            COMBAT_PENDING.add(maid);
            return true;
        }
        // 重载的未闭合命中不允许构造普通死亡快照或发布墓碑
        if (maid.getCombatHitReceipt()
            .blocksTransfer() && maid.getHealth() <= 0) {
            deferCombatDeath(maid);
            return true;
        }
        try {
            if (maid.getEntityData()
                .hasKey(MaidLifecycleNbtKeys.WILD_TAG)) confirmWildHold(maid);
            return MaidDeathIngress.onJoin(maid);
        } catch (Exception | LinkageError | AssertionError failure) {
            report(MaidLifecycleIds.ID_DEATH_INGRESS + maid.getUniqueID(), failure);
            return true; // 账本不可读不能靠拒载删除仍持有原始数据的实体
        }
    }

    static void validatePublication(NBTTagCompound receipt, UUID token, UUID owner) {
        for (Object key : receipt.func_150296_c())
            if (!PUBLICATION_FIELDS.contains(key)) throw new IllegalStateException(
                MaidLifecycleDiagnostics.MSG_UNKNOWN_TOMBSTONE_PUBLICATION_FIELD_RETAINED + key);
        if (receipt.func_150296_c()
            .size()
            != PUBLICATION_FIELDS.size()
                - (receipt.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED) ? MaidLifecycleTuning.CORE_INITIAL_VALUE
                    : MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD)
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_VERSION, Constants.NBT.TAG_INT)
            || receipt.getInteger(MaidLifecycleNbtKeys.KEY_VERSION) != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_STAGE, Constants.NBT.TAG_STRING)
            || !(receipt.getString(MaidLifecycleNbtKeys.KEY_STAGE)
                .equals(MaidLifecycleNbtKeys.KEY_PREPARED)
                || receipt.getString(MaidLifecycleNbtKeys.KEY_STAGE)
                    .equals(MaidLifecycleNbtKeys.KEY_PUBLISHED)
                || receipt.getString(MaidLifecycleNbtKeys.KEY_STAGE)
                    .equals(MaidLifecycleNbtKeys.KEY_HELD))
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_SNAPSHOT, Constants.NBT.TAG_COMPOUND)
            || receipt.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED)
                && !receipt.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED, Constants.NBT.TAG_COMPOUND))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_TOMBSTONE_PUBLICATION_INTENT);
        var source = receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_SNAPSHOT);
        if (!source.hasKey(MaidLifecycleNbtKeys.KEY_UUID_MOST, Constants.NBT.TAG_LONG)
            || !source.hasKey(MaidLifecycleNbtKeys.KEY_UUID_LEAST, Constants.NBT.TAG_LONG)
            || !source.hasKey(MaidLifecycleNbtKeys.KEY_TOKEN, Constants.NBT.TAG_STRING)
            || !token.toString()
                .equals(source.getString(MaidLifecycleNbtKeys.KEY_TOKEN))
            || !source.hasKey(MaidLifecycleNbtKeys.KEY_OWNER_ID, Constants.NBT.TAG_STRING)
            || !owner.toString()
                .equals(source.getString(MaidLifecycleNbtKeys.KEY_OWNER_ID)))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_TOMBSTONE_INTENT_IDENTITY_DISAGREES);
    }

    private static void ensureTombstoneSafely(World world, UUID token) {
        if (!IntegrationGuard.available(Side.SERVER, ExternalFeature.ITEM_DELIVERY)) return;
        try {
            ensureTombstone(world, token);
        } catch (Exception | LinkageError | AssertionError failure) {
            report(MaidLifecycleIds.ID_TOMBSTONE_PUBLICATION + token, failure);
            if (failure instanceof LinkageError)
                IntegrationGuard.trip(Side.SERVER, ExternalFeature.ITEM_DELIVERY, failure);
        }
    }

    private static void ensureTombstone(World world, UUID token) {
        var ledger = MaidLifecycleData.get(world);
        var position = ledger.tombstonePosition(token);
        if (position == null || !world.getChunkProvider()
            .chunkExists(
                (int) Math.floor(position.xCoord) >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                (int) Math.floor(position.zCoord) >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
            || ledger.reserve(token, MaidLifecycleData.Kind.FILM) == null) return;
        MaidTombstoneEntity tombstone = null;
        MaidLifecycleDurability.Publication publication = null;
        NBTTagCompound receipt = null;
        boolean prepared = false, external = false, persisting = false, persisted = false, complete = false;
        try {
            receipt = ledger.tombstonePublication(token);
            if (!receipt.hasNoTags()) {
                if (!MaidLifecycleNbtKeys.KEY_PREPARED.equals(receipt.getString(MaidLifecycleNbtKeys.KEY_STAGE)))
                    return;
                var expected = receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_SNAPSHOT);
                var identity = new UUID(
                    expected.getLong(MaidLifecycleNbtKeys.KEY_UUID_MOST),
                    expected.getLong(MaidLifecycleNbtKeys.KEY_UUID_LEAST));
                for (Object entity : world.loadedEntityList)
                    if (entity instanceof MaidTombstoneEntity candidate && identity.equals(candidate.getUniqueID())) {
                        if (tombstone != null) throw new IllegalStateException(
                            MaidLifecycleDiagnostics.MSG_PENDING_TOMBSTONE_IDENTITY_IS_NOT);
                        tombstone = candidate;
                    }
                if (tombstone == null) return; // 未确认的跨重启目标绝不按同token另造handle
                ledger.resumeTombstonePublication(token, receipt);
                prepared = true;
                persisting = true; // 已存目标只允许确认；未知后继不能走新生对象的安全撤销分支
                publication = new MaidLifecycleDurability.Publication(tombstone, expected);
                publication.check(world, true);
                MaidLifecycleDurability.entities(world, Collections.singletonList(publication), true);
                persisted = true;
                ledger.finishTombstonePublication(world, token, receipt);
                complete = true;
                return;
            }
            tombstone = new MaidTombstoneEntity(world);
            tombstone.initialize(token, ledger.owner(token, MaidLifecycleData.Kind.FILM), ledger.tombstoneName(token));
            tombstone.setPosition(position.xCoord, position.yCoord, position.zCoord);
            var expected = MaidDeathIngress.snapshot(tombstone);
            publication = new MaidLifecycleDurability.Publication(tombstone, expected);
            receipt = new NBTTagCompound();
            receipt.setInteger(MaidLifecycleNbtKeys.KEY_VERSION, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
            receipt.setString(MaidLifecycleNbtKeys.KEY_STAGE, MaidLifecycleNbtKeys.KEY_PREPARED);
            receipt.setTag(MaidLifecycleNbtKeys.KEY_SNAPSHOT, expected);
            prepared = true;
            ledger.beginTombstonePublication(world, token, receipt);
            external = true;
            if (!world.spawnEntityInWorld(tombstone)) return;
            if (!publication.owned(world) || !MaidSpawnChecks.present(tombstone, world)) return;
            persisting = true;
            MaidLifecycleDurability.entities(world, Collections.singletonList(publication), true);
            persisted = true;
            ledger.finishTombstonePublication(world, token, receipt);
            complete = true;
        } finally {
            try {
                if (prepared && !complete && !persisted && persisting) {
                    ledger.holdTombstonePublication(token, MaidDeathIngress.snapshot(tombstone));
                    MaidLifecycleDurability.ledger(world, ledger);
                } else if (prepared && !complete && !persisted) {
                    if (external && publication != null) {
                        publication.discard(world);
                        MaidLifecycleDurability.entities(world, Collections.singletonList(publication), false);
                    }
                    ledger.cancelTombstonePublication(world, token, receipt);
                }
            } catch (Exception | LinkageError | AssertionError cleanup) {
                report(MaidLifecycleIds.ID_TOMBSTONE_PUBLICATION + token, cleanup);
                try {
                    NBTTagCompound observed = new NBTTagCompound();
                    if (tombstone != null) try {
                        observed = MaidDeathIngress.snapshot(tombstone);
                    } catch (Exception | LinkageError | AssertionError unreadable) {
                        report(
                            MaidLifecycleIds.ID_TOMBSTONE_PUBLICATION + token + MaidLifecycleIds.ID_SNAPSHOT,
                            unreadable);
                    }
                    ledger.holdTombstonePublication(token, observed);
                    MaidLifecycleDurability.ledger(world, ledger);
                } catch (Exception | LinkageError | AssertionError retentionFailure) {
                    report(
                        MaidLifecycleIds.ID_TOMBSTONE_PUBLICATION + token + MaidLifecycleIds.ID_RETENTION,
                        retentionFailure);
                }
            } finally {
                ledger.endTombstonePublication(token);
            }
        }
    }

    private static void report(String key, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(Side.SERVER, key, failure);
    }
}
