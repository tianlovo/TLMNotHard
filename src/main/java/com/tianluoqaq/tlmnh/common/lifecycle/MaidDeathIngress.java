package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.RecoveryStore;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;

/** 死亡源与现FILM账本共用同一份收据；先持久保管，再清源，未知后继不自动补偿 */
final class MaidDeathIngress {

    static final String TAG = "TLMNHDeathIngress";
    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList("Version", "Token", "Stage", "Source", "Cleared", "Items", "Experience", "ReadyAt", "Observed"));
    private static final Map<EntityMaid, Attempt> PENDING = new IdentityHashMap<>();

    private MaidDeathIngress() {}

    static boolean preserve(EntityMaid maid, UUID owner) {
        // 接受伤害后的原栈尚未结算，禁止提前编码源物品
        if (maid.getCombatHitReceipt()
            .blocksTransfer()) return true;
        try {
            var ledger = MaidLifecycleData.get(maid.worldObj);
            var token = ledger.deathToken(maid.getUniqueID());
            if (token != null && ledger.delivered(token) && !ledger.pendingDeath(maid.getUniqueID())) return true;
            var attempt = PENDING.get(maid);
            if (attempt == null) {
                attempt = new Attempt(maid, owner, ledger, token);
                PENDING.put(maid, attempt);
            }
            attempt.run();
        } catch (Exception | LinkageError | AssertionError failure) {
            report(maid, failure);
            if (!maid.getEntityData()
                .hasKey(TAG)) {
                // 未能取出完整快照时不编造空源；已知本域占位阻止尸体在重载后被原生清掉
                var unreadable = new NBTTagCompound();
                unreadable.setInteger("Version", 1);
                unreadable.setString("Stage", "UNREADABLE");
                unreadable.setString(
                    "Token",
                    UUID.randomUUID()
                        .toString());
                maid.getEntityData()
                    .setTag(TAG, unreadable);
            }
        }
        // 本域接管后不能退回原生随机装备出口，即使IO失败也保留原实体和完整原物
        return true;
    }

    static boolean blocks(EntityMaid maid) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return false;
        if (PENDING.containsKey(maid) || maid.getEntityData()
            .hasKey(TAG)) return true;
        try {
            return MaidLifecycleData.get(maid.worldObj)
                .pendingDeath(maid.getUniqueID());
        } catch (Exception | LinkageError | AssertionError failure) {
            report(maid, failure);
            return maid.isTamed() && maid.getHealth() <= 0;
        }
    }

    static void retry(World world) {
        for (var attempt : new ArrayList<>(PENDING.values())) if (attempt.world == world) attempt.run();
    }

    static void unload(World world) {
        PENDING.entrySet()
            .removeIf(entry -> entry.getKey().worldObj == world);
    }

    static boolean onJoin(EntityMaid maid) {
        var ledger = MaidLifecycleData.get(maid.worldObj);
        var token = ledger.deathToken(maid.getUniqueID());
        if (token != null && ledger.delivered(token) && !ledger.pendingDeath(maid.getUniqueID())) return false;
        if (!maid.getEntityData()
            .hasKey(TAG) && !ledger.pendingDeath(maid.getUniqueID())) return true;
        try {
            var owner = UUID.fromString(maid.func_152113_b());
            var attempt = new Attempt(maid, owner, ledger, token);
            if (attempt.state(false) == 0) {
                attempt.hold(
                    new IllegalStateException("Reloaded death source differs from both complete owned snapshots"));
                return true; // 保留实际未知实体，由持久marker/账本门冻结，不能取消后让原生存盘抹源
            }
            PENDING.put(maid, attempt);
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(maid, failure);
            if (token != null && !ledger.deathIngress(token)
                .hasNoTags()) try {
                    ledger.holdDeathIngress(token, snapshot(maid));
                    MaidLifecycleDurability.ledger(maid.worldObj, ledger);
                } catch (Exception | LinkageError | AssertionError retentionFailure) {
                    report(maid, retentionFailure);
                }
            return true;
        }
    }

    static NBTTagCompound snapshot(Entity entity) {
        var type = EntityList.getEntityString(entity);
        if (type == null) throw new IllegalStateException("Registered death entity type required");
        var result = new NBTTagCompound();
        result.setString("id", type);
        entity.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    static void validate(NBTTagCompound receipt, UUID token, UUID owner, UUID subject) {
        for (Object field : receipt.func_150296_c()) if (!FIELDS.contains(field))
            throw new IllegalStateException("Unknown death ingress field retained: " + field);
        if (receipt.func_150296_c()
            .size() != FIELDS.size() - (receipt.hasKey("Observed") ? 0 : 1) || !receipt.hasKey("Version", 3)
            || receipt.getInteger("Version") != 1
            || !receipt.hasKey("Token", 8)
            || !token.toString()
                .equals(receipt.getString("Token"))
            || !receipt.hasKey("Stage", 8)
            || !(receipt.getString("Stage")
                .equals("PREPARED")
                || receipt.getString("Stage")
                    .equals("COMMITTED")
                || receipt.getString("Stage")
                    .equals("HELD"))
            || !receipt.hasKey("Source", 10)
            || !receipt.hasKey("Cleared", 10)
            || !receipt.hasKey("Items", 9)
            || !receipt.hasKey("Experience", 3)
            || receipt.getInteger("Experience") < 0
            || !receipt.hasKey("ReadyAt", 4)
            || receipt.getLong("ReadyAt") < 0
            || receipt.hasKey("Observed") && !receipt.hasKey("Observed", 10))
            throw new IllegalStateException("Invalid death ingress receipt");
        var items = receipt.getTagList("Items", 10);
        if (items.tagCount() == 0 || items.tagCount() != ((NBTTagList) receipt.getTag("Items")).tagCount())
            throw new IllegalStateException("Invalid complete death materials");
        var source = receipt.getCompoundTag("Source");
        var cleared = receipt.getCompoundTag("Cleared");
        if (!source.hasKey("UUIDMost", 4) || !source.hasKey("UUIDLeast", 4)
            || !source.hasKey("OwnerUUID", 8)
            || !subject.equals(new UUID(source.getLong("UUIDMost"), source.getLong("UUIDLeast")))
            || !owner.toString()
                .equals(source.getString("OwnerUUID"))
            || !source.hasKey("MaidExperience", 3)
            || !source.hasKey("HealF", 5)
            || !Float.isFinite(source.getFloat("HealF"))
            || source.getFloat("HealF") > 0
            || source.getCompoundTag("ForgeData")
                .hasKey(TAG)
            || !cleared.equals(cleared(source)))
            throw new IllegalStateException("Death source identity or complete cleared snapshot disagrees");
    }

    private static NBTTagCompound cleared(NBTTagCompound source) {
        var result = (NBTTagCompound) source.copy();
        result.setTag("MaidInventory", new NBTTagList());
        result.setTag(RecoveryStore.TAG, new NBTTagList());
        result.setInteger("MaidExperience", 0);
        var equipment = new NBTTagList();
        for (int slot = 0; slot < 5; slot++) equipment.appendTag(new NBTTagCompound());
        result.setTag("Equipment", equipment);
        result.removeTag("TLMNHOffhand");
        return result;
    }

    private static void report(EntityMaid maid, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(Side.SERVER, "death_ingress/" + maid.getUniqueID(), failure);
    }

    private static final class Attempt {

        final EntityMaid maid;
        final World world;
        final MaidLifecycleData ledger;
        final UUID owner, subject, token;
        final int id;
        final Entity vehicle, passenger;
        final NBTTagCompound receipt, marker;
        boolean active;

        Attempt(EntityMaid maid, UUID owner, MaidLifecycleData ledger, UUID knownToken) {
            this.maid = maid;
            world = maid.worldObj;
            this.owner = owner;
            subject = maid.getUniqueID();
            id = maid.getEntityId();
            vehicle = maid.ridingEntity;
            passenger = maid.riddenByEntity;
            this.ledger = ledger;
            var data = maid.getEntityData();
            if (data.hasKey(TAG)) {
                if (!data.hasKey(TAG, 10)) throw new IllegalStateException("Unknown death source marker preserved");
                marker = data.getCompoundTag(TAG);
                token = UUID.fromString(marker.getString("Token"));
                receipt = (NBTTagCompound) marker.copy();
                validate(receipt, token, owner, subject);
                if (knownToken != null && !knownToken.equals(token))
                    throw new IllegalStateException("Conflicting ledger and entity death identities");
            } else {
                if (knownToken != null && !ledger.deathIngress(knownToken)
                    .hasNoTags())
                    throw new IllegalStateException("Persistent death source is missing its exact native marker");
                MaidInteractionTransfer.preserveDeathBalance(maid);
                var source = snapshot(maid);
                token = knownToken == null ? UUID.randomUUID() : knownToken;
                receipt = new NBTTagCompound();
                receipt.setInteger("Version", 1);
                receipt.setString("Token", token.toString());
                receipt.setString("Stage", "PREPARED");
                receipt.setTag("Source", source);
                receipt.setTag("Cleared", cleared(source));
                var items = new NBTTagList();
                for (int slot = 0; slot < maid.getMaidInventory()
                    .getSizeInventory(); slot++) {
                    var stack = maid.getMaidInventory()
                        .getStackInSlot(slot);
                    if (stack != null) items.appendTag(ProtectedItemCodec.encode(stack));
                }
                var recovery = maid.getMaidInventory()
                    .recovery()
                    .write();
                for (int i = 0; i < recovery.tagCount(); i++) items.appendTag(
                    recovery.getCompoundTagAt(i)
                        .copy());
                items.appendTag(
                    ProtectedItemCodec
                        .encode(MaidLifecycleItems.credential(MaidResurrectionContent.FILM, token, owner)));
                receipt.setTag("Items", items);
                receipt.setInteger(
                    "Experience",
                    world.getGameRules()
                        .getGameRuleBooleanValue("doMobLoot") ? MaidInteractionTransfer.availableExperience(maid) : 0);
                long now = Math.max(0, world.getTotalWorldTime());
                receipt.setLong("ReadyAt", now > Long.MAX_VALUE - 20 ? Long.MAX_VALUE : now + 20);
                validate(receipt, token, owner, subject);
                if (!source.equals(snapshot(maid)))
                    throw new IllegalStateException("Death source changed during capture");
                marker = (NBTTagCompound) receipt.copy();
                data.setTag(TAG, marker);
            }
        }

        boolean identity(boolean registered) {
            return maid.worldObj == world && !maid.isDead
                && maid.getEntityId() == id
                && subject.equals(maid.getUniqueID())
                && maid.isTamed()
                && owner.toString()
                    .equals(maid.func_152113_b())
                && maid.ridingEntity == vehicle
                && maid.riddenByEntity == passenger
                && maid.getEntityData()
                    .getTag(TAG) == marker
                && receipt.equals(marker)
                && (!registered || MaidSpawnChecks.present(maid, world));
        }

        NBTTagCompound base() {
            var full = snapshot(maid);
            full.getCompoundTag("ForgeData")
                .removeTag(TAG);
            return full;
        }

        int state(boolean registered) {
            if (!identity(registered)) return 0;
            var actual = base();
            // 空库存、零XP时两像本来相同，已确认无原物可扣，不能永远留在Source分支
            if (actual.equals(receipt.getCompoundTag("Cleared"))) return 2;
            return actual.equals(receipt.getCompoundTag("Source")) ? 1 : 0;
        }

        void run() {
            if (active || maid.getCombatHitReceipt()
                .blocksTransfer()) return;
            active = true;
            boolean begun = false;
            try {
                if (!"PREPARED".equals(receipt.getString("Stage"))) return;
                var existing = ledger.deathIngress(token);
                if (!existing.hasNoTags() && !existing.equals(receipt)) return;
                int current = state(true);
                if (current == 0)
                    throw new IllegalStateException("Death source no longer matches its full owned snapshots");
                var source = receipt.getCompoundTag("Source");
                var position = source.getTagList("Pos", 6);
                if (!source.hasKey("Dimension", 3) || source.getInteger("Dimension") != world.provider.dimensionId
                    || position.tagCount() != 3)
                    throw new IllegalStateException("Unknown death source world or position");
                ledger.prepareDeath(
                    token,
                    owner,
                    subject,
                    MaidFilmData.identity(source),
                    receipt.getTagList("Items", 10),
                    world.provider.dimensionId,
                    Math.floor(position.func_150309_d(0)) + .5,
                    Math.max(5, Math.min(world.getHeight() - 2, Math.floor(position.func_150309_d(1)))),
                    Math.floor(position.func_150309_d(2)) + .5,
                    source.getString("CustomName"),
                    receipt.getInteger("Experience"),
                    receipt.getLong("ReadyAt"),
                    receipt);
                ledger.beginDeathAttempt(token, receipt);
                begun = true;
                MaidLifecycleDurability.ledger(world, ledger);
                current = state(true);
                if (current == 0) throw new IllegalStateException("Death source changed during durable write");
                if (current == 1) clear();
                if (state(true) != 2) throw new IllegalStateException("Unknown source after death debit retained");
                var expected = snapshot(maid);
                MaidLifecycleDurability.entities(
                    world,
                    Collections.singletonList(new MaidLifecycleDurability.Publication(maid, expected, true)),
                    true);
                if (state(true) != 2)
                    throw new IllegalStateException("Death source changed during native confirmation");
                ledger.finishDeathAttempt(world, token, receipt);
                if (identity(true) && state(true) == 2) maid.getEntityData()
                    .removeTag(TAG);
                PENDING.remove(maid);
            } catch (Exception | LinkageError | AssertionError failure) {
                report(maid, failure);
                if (stateSafely() == 0) hold(failure);
            } finally {
                if (begun) ledger.endDeathAttempt(token);
                active = false;
            }
        }

        int stateSafely() {
            try {
                return state(true);
            } catch (Exception | LinkageError | AssertionError failure) {
                report(maid, failure);
                return 0;
            }
        }

        void clear() {
            var inventory = maid.getMaidInventory();
            var references = new ItemStack[inventory.getSizeInventory()];
            var values = new ItemStack[references.length];
            for (int slot = 0; slot < references.length; slot++) {
                references[slot] = inventory.getStackInSlot(slot);
                values[slot] = ItemStack.copyItemStack(references[slot]);
            }
            if (state(true) != 1) throw new IllegalStateException("Original death materials changed before debit");
            for (int slot = 0; slot < references.length; slot++) {
                if (!identity(true) || inventory.getStackInSlot(slot) != references[slot]
                    || !ItemStack.areItemStacksEqual(references[slot], values[slot]))
                    throw new IllegalStateException("Unknown death inventory successor was not cleared");
                inventory.setInventorySlotContents(slot, null);
            }
            if (!inventory.recovery()
                .write()
                .equals(
                    receipt.getCompoundTag("Source")
                        .getTagList(RecoveryStore.TAG, 10)))
                throw new IllegalStateException("Unknown recovery successor was not cleared");
            inventory.recovery()
                .clear();
            clearEquipmentModifiers(values);
            var beforeXp = (NBTTagCompound) receipt.getCompoundTag("Cleared")
                .copy();
            beforeXp.setInteger(
                "MaidExperience",
                receipt.getCompoundTag("Source")
                    .getInteger("MaidExperience"));
            if (!identity(true) || !base().equals(beforeXp))
                throw new IllegalStateException("Unknown death balance or identity was not cleared");
            maid.setExperience(0);
        }

        void clearEquipmentModifiers(ItemStack[] original) {
            // Native NBT temporarily excludes current equipment modifiers. After clearing the slots, remove those
            // exact old modifiers now instead of waiting for the next native equipment-cache update.
            for (int slot : new int[] { MaidInventory.MAIN_HAND, MaidInventory.ARMOR_START,
                MaidInventory.ARMOR_START + 1, MaidInventory.ARMOR_START + 2, MaidInventory.ARMOR_START + 3 }) {
                var stack = original[slot];
                if (stack == null) continue;
                for (Object raw : stack.getAttributeModifiers()
                    .entries()) {
                    var entry = (Map.Entry<?, ?>) raw;
                    if (!(entry.getKey() instanceof String name)
                        || !(entry.getValue() instanceof AttributeModifier expected))
                        throw new IllegalStateException("Unknown original equipment attribute retained");
                    if (!identity(true)) throw new IllegalStateException("Unknown death source during attribute debit");
                    var attribute = maid.getAttributeMap()
                        .getAttributeInstanceByName(name);
                    if (attribute == null) continue;
                    var current = attribute.getModifier(expected.getID());
                    if (current == null) continue;
                    if (!current.getName()
                        .equals(expected.getName()) || current.getOperation() != expected.getOperation()
                        || Double.doubleToLongBits(current.getAmount()) != Double.doubleToLongBits(expected.getAmount())
                        || current.isSaved() != expected.isSaved())
                        throw new IllegalStateException("Unknown equipment attribute successor retained");
                    attribute.removeModifier(current);
                }
            }
        }

        void hold(Throwable failure) {
            report(maid, failure);
            try {
                if (!ledger.deathIngress(token)
                    .hasNoTags()) {
                    ledger.holdDeathIngress(token, snapshot(maid));
                    MaidLifecycleDurability.ledger(world, ledger);
                }
            } catch (Exception | LinkageError | AssertionError retentionFailure) {
                report(maid, retentionFailure);
            }
        }
    }
}
