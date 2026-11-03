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

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 死亡入站、原生野生掉落和墓碑handle；物品及经验仍由现有各自出口单次兑现 */
public final class MaidDeathService {

    private static final String WILD_TAG = "TLMNHWildDeathHold";
    private static final Set<EntityMaid> WILD_CONFIRMED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<EntityMaid> WILD_PENDING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<String> PUBLICATION_FIELDS = new HashSet<>(
        Arrays.asList("Version", "Stage", "Snapshot", "Observed"));

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
            report("death_ingress/" + maid.getUniqueID(), failure);
        }
        boolean owned = MaidDeathIngress.preserve(maid, owner);
        try {
            var ledger = MaidLifecycleData.get(maid.worldObj);
            var token = ledger.deathToken(maid.getUniqueID());
            if (token != null && ledger.delivered(token) && !ledger.pendingDeath(maid.getUniqueID()))
                ensureTombstoneSafely(maid.worldObj, token);
        } catch (Exception | LinkageError | AssertionError failure) {
            report("death_ingress/" + maid.getUniqueID(), failure);
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
        return MaidDeathIngress.blocks(maid) || maid != null && maid.worldObj != null
            && !maid.worldObj.isRemote
            && maid.getEntityData()
                .hasKey(WILD_TAG)
            && !WILD_CONFIRMED.contains(maid);
    }

    public static boolean canFinishDeath(EntityMaid maid, boolean nativeDead) {
        if (maid.getEntityData()
            .hasKey(WILD_TAG)) return confirmWildHold(maid);
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
            report("death_ingress/" + maid.getUniqueID(), failure);
            return false;
        }
    }

    public static int nativeDeathExperience(EntityMaid maid) {
        if (maid.getEntityData()
            .hasKey(WILD_TAG) || MaidDeathIngress.blocks(maid)) return 0;
        try {
            var ledger = MaidLifecycleData.get(maid.worldObj);
            var token = ledger.deathToken(maid.getUniqueID());
            if (token != null && ledger.delivered(token)) return 0;
            return MaidInteractionTransfer.availableExperience(maid);
        } catch (Exception | LinkageError | AssertionError failure) {
            report("death_ingress/" + maid.getUniqueID(), failure);
            return 0;
        }
    }

    /** 只在原生dropEquipment调用；entityDropItem保留captureDrops与后续LivingDropsEvent的决定权 */
    public static void dropUnowned(EntityMaid maid) {
        if (owner(maid) != null) return;
        NBTTagCompound source = null;
        try {
            boolean opaqueHold = false;
            for (Object key : maid.getEntityData()
                .func_150296_c()) if (((String) key).startsWith(MaidInteractionTransfer.TAG)) opaqueHold = true;
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
                var drop = maid.entityDropItem(expected.copy(), 0);
                if (drop == null || !ItemStack.areItemStacksEqual(drop.getEntityItem(), expected)
                    || inventory.getStackInSlot(slot) != stack
                    || !ItemStack.areItemStacksEqual(stack, expected))
                    throw new IllegalStateException("Unknown native wild equipment successor retained");
                inventory.setInventorySlotContents(slot, null);
            }
            while (inventory.recovery()
                .size() > 0) {
                var actual = inventory.recovery()
                    .firstForTransfer();
                var expected = actual.copy();
                var drop = maid.entityDropItem(expected.copy(), 0);
                if (drop == null || !ItemStack.areItemStacksEqual(drop.getEntityItem(), expected)
                    || !ItemStack.areItemStacksEqual(actual, expected)
                    || !inventory.recovery()
                        .takeFirstForTransfer(actual))
                    throw new IllegalStateException("Unknown native wild recovery successor retained");
            }
        } catch (Exception | LinkageError | AssertionError failure) {
            report("wild_death/" + maid.getUniqueID(), failure);
            if (failure instanceof LinkageError)
                IntegrationGuard.trip(Side.SERVER, ExternalFeature.ITEM_DELIVERY, failure);
            try {
                if (source != null) {
                    holdWild(maid, source);
                    var marker = maid.getEntityData()
                        .getCompoundTag(WILD_TAG);
                    if (marker.hasKey("Version", 3) && marker.getInteger("Version") == 1
                        && marker.hasKey("Id", 8)
                        && marker.hasKey("Raw", 10)
                        && marker.func_150296_c()
                            .size() == 3) {
                        var observed = MaidDeathIngress.snapshot(maid);
                        marker.setString(
                            "ObservedId",
                            UUID.randomUUID()
                                .toString());
                        marker.setTag("ObservedRaw", observed);
                        WILD_CONFIRMED.remove(maid);
                        confirmWildHold(maid);
                    }
                }
            } catch (Exception | LinkageError | AssertionError retentionFailure) {
                report("wild_death/" + maid.getUniqueID() + "/retention", retentionFailure);
            }
            if (source == null && !maid.getEntityData()
                .hasKey(WILD_TAG)) {
                var unreadable = new NBTTagCompound();
                unreadable.setInteger("Version", 1);
                unreadable.setString("Stage", "UNREADABLE");
                maid.getEntityData()
                    .setTag(WILD_TAG, unreadable);
                WILD_PENDING.add(maid);
            }
        }
    }

    private static void holdWild(EntityMaid maid, NBTTagCompound source) {
        if (!maid.getEntityData()
            .hasKey(WILD_TAG)) {
            var marker = new NBTTagCompound();
            marker.setInteger("Version", 1);
            marker.setString(
                "Id",
                UUID.randomUUID()
                    .toString());
            marker.setTag("Raw", source.copy());
            maid.getEntityData()
                .setTag(WILD_TAG, marker);
        }
        confirmWildHold(maid);
    }

    private static boolean confirmWildHold(EntityMaid maid) {
        if (WILD_CONFIRMED.contains(maid)) return true;
        try {
            var data = maid.getEntityData();
            if (!data.hasKey(WILD_TAG, 10)) throw new IllegalStateException("Unknown wild death hold marker retained");
            var marker = data.getCompoundTag(WILD_TAG);
            boolean observed = marker.hasKey("ObservedId") || marker.hasKey("ObservedRaw");
            if (marker.func_150296_c()
                .size() != (observed ? 5 : 3) || !marker.hasKey("Version", 3)
                || marker.getInteger("Version") != 1
                || !marker.hasKey("Id", 8)
                || !marker.hasKey("Raw", 10)
                || marker.getCompoundTag("Raw")
                    .hasNoTags())
                throw new IllegalStateException("Invalid wild death hold marker retained");
            if (observed
                && (!marker.hasKey("ObservedId", 8) || !marker.hasKey("ObservedRaw", 10)
                    || marker.getCompoundTag("ObservedRaw")
                        .hasNoTags()))
                throw new IllegalStateException("Invalid observed wild death successor retained");
            var ledger = MaidLifecycleData.get(maid.worldObj);
            ledger.retainUnownedDeath(
                UUID.fromString(marker.getString("Id")),
                marker.getCompoundTag("Raw"),
                maid.worldObj.provider.dimensionId,
                maid.posX,
                maid.posY,
                maid.posZ);
            if (observed) ledger.retainUnownedDeath(
                UUID.fromString(marker.getString("ObservedId")),
                marker.getCompoundTag("ObservedRaw"),
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
            report("wild_death/" + maid.getUniqueID(), failure);
            return false;
        }
    }

    public static void retryPending(World world) {
        if (world.isRemote) return;
        MaidDeathIngress.retry(world);
        WILD_CONFIRMED.removeIf(maid -> maid.isDead);
        for (var maid : new ArrayList<>(WILD_PENDING)) if (maid.worldObj == world) confirmWildHold(maid);
        var ledger = MaidLifecycleData.get(world);
        for (var token : ledger.pendingTombstones(world.provider.dimensionId)) ensureTombstoneSafely(world, token);
        MaidDeathExperience.retryPending(world);
    }

    static void unload(World world) {
        MaidDeathIngress.unload(world);
        WILD_CONFIRMED.removeIf(maid -> maid.worldObj == world);
        WILD_PENDING.removeIf(maid -> maid.worldObj == world);
    }

    static boolean onJoin(EntityMaid maid) {
        try {
            if (maid.getEntityData()
                .hasKey(WILD_TAG)) confirmWildHold(maid);
            return MaidDeathIngress.onJoin(maid);
        } catch (Exception | LinkageError | AssertionError failure) {
            report("death_ingress/" + maid.getUniqueID(), failure);
            return true; // 账本不可读不能靠拒载删除仍持有原始数据的实体
        }
    }

    static void validatePublication(NBTTagCompound receipt, UUID token, UUID owner) {
        for (Object key : receipt.func_150296_c()) if (!PUBLICATION_FIELDS.contains(key))
            throw new IllegalStateException("Unknown tombstone publication field retained: " + key);
        if (receipt.func_150296_c()
            .size() != PUBLICATION_FIELDS.size() - (receipt.hasKey("Observed") ? 0 : 1) || !receipt.hasKey("Version", 3)
            || receipt.getInteger("Version") != 1
            || !receipt.hasKey("Stage", 8)
            || !(receipt.getString("Stage")
                .equals("PREPARED")
                || receipt.getString("Stage")
                    .equals("PUBLISHED")
                || receipt.getString("Stage")
                    .equals("HELD"))
            || !receipt.hasKey("Snapshot", 10)
            || receipt.hasKey("Observed") && !receipt.hasKey("Observed", 10))
            throw new IllegalStateException("Invalid tombstone publication intent");
        var source = receipt.getCompoundTag("Snapshot");
        if (!source.hasKey("UUIDMost", 4) || !source.hasKey("UUIDLeast", 4)
            || !source.hasKey("Token", 8)
            || !token.toString()
                .equals(source.getString("Token"))
            || !source.hasKey("OwnerId", 8)
            || !owner.toString()
                .equals(source.getString("OwnerId")))
            throw new IllegalStateException("Tombstone intent identity disagrees");
    }

    private static void ensureTombstoneSafely(World world, UUID token) {
        if (!IntegrationGuard.available(Side.SERVER, ExternalFeature.ITEM_DELIVERY)) return;
        try {
            ensureTombstone(world, token);
        } catch (Exception | LinkageError | AssertionError failure) {
            report("tombstone_publication/" + token, failure);
            if (failure instanceof LinkageError)
                IntegrationGuard.trip(Side.SERVER, ExternalFeature.ITEM_DELIVERY, failure);
        }
    }

    private static void ensureTombstone(World world, UUID token) {
        var ledger = MaidLifecycleData.get(world);
        var position = ledger.tombstonePosition(token);
        if (position == null || !world.getChunkProvider()
            .chunkExists((int) Math.floor(position.xCoord) >> 4, (int) Math.floor(position.zCoord) >> 4)
            || ledger.reserve(token, MaidLifecycleData.Kind.FILM) == null) return;
        MaidTombstoneEntity tombstone = null;
        MaidLifecycleDurability.Publication publication = null;
        NBTTagCompound receipt = null;
        boolean prepared = false, external = false, persisting = false, persisted = false, complete = false;
        try {
            receipt = ledger.tombstonePublication(token);
            if (!receipt.hasNoTags()) {
                if (!"PREPARED".equals(receipt.getString("Stage"))) return;
                var expected = receipt.getCompoundTag("Snapshot");
                var identity = new UUID(expected.getLong("UUIDMost"), expected.getLong("UUIDLeast"));
                for (Object entity : world.loadedEntityList)
                    if (entity instanceof MaidTombstoneEntity candidate && identity.equals(candidate.getUniqueID())) {
                        if (tombstone != null)
                            throw new IllegalStateException("Pending tombstone identity is not unique");
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
            receipt.setInteger("Version", 1);
            receipt.setString("Stage", "PREPARED");
            receipt.setTag("Snapshot", expected);
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
                report("tombstone_publication/" + token, cleanup);
                try {
                    NBTTagCompound observed = new NBTTagCompound();
                    if (tombstone != null) try {
                        observed = MaidDeathIngress.snapshot(tombstone);
                    } catch (Exception | LinkageError | AssertionError unreadable) {
                        report("tombstone_publication/" + token + "/snapshot", unreadable);
                    }
                    ledger.holdTombstonePublication(token, observed);
                    MaidLifecycleDurability.ledger(world, ledger);
                } catch (Exception | LinkageError | AssertionError retentionFailure) {
                    report("tombstone_publication/" + token + "/retention", retentionFailure);
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
