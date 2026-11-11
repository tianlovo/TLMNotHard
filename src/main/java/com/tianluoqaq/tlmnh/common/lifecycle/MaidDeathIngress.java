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
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleNbtKeys;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidFilmData;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectionRecoveryNbtKeys;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;

/** 死亡源与现FILM账本共用同一份收据；先持久保管，再清源，未知后继不自动补偿 */
final class MaidDeathIngress {

    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            MaidLifecycleNbtKeys.KEY_VERSION,
            MaidLifecycleNbtKeys.KEY_TOKEN,
            MaidLifecycleNbtKeys.KEY_STAGE,
            MaidLifecycleNbtKeys.KEY_SOURCE,
            MaidLifecycleNbtKeys.KEY_CLEARED,
            MaidLifecycleNbtKeys.KEY_ITEMS,
            MaidLifecycleNbtKeys.KEY_EXPERIENCE,
            MaidLifecycleNbtKeys.KEY_READY_AT,
            MaidLifecycleNbtKeys.KEY_OBSERVED));
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
                .hasKey(MaidLifecycleNbtKeys.TAG)) {
                // 未能取出完整快照时不编造空源；已知本域占位阻止尸体在重载后被原生清掉
                var unreadable = new NBTTagCompound();
                unreadable
                    .setInteger(MaidLifecycleNbtKeys.KEY_VERSION, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
                unreadable.setString(MaidLifecycleNbtKeys.KEY_STAGE, MaidLifecycleNbtKeys.KEY_UNREADABLE);
                unreadable.setString(
                    MaidLifecycleNbtKeys.KEY_TOKEN,
                    UUID.randomUUID()
                        .toString());
                maid.getEntityData()
                    .setTag(MaidLifecycleNbtKeys.TAG, unreadable);
            }
        }
        // 本域接管后不能退回原生随机装备出口，即使IO失败也保留原实体和完整原物
        return true;
    }

    static boolean blocks(EntityMaid maid) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return false;
        if (PENDING.containsKey(maid) || maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.TAG)) return true;
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
            .hasKey(MaidLifecycleNbtKeys.TAG) && !ledger.pendingDeath(maid.getUniqueID())) return true;
        try {
            var owner = UUID.fromString(maid.func_152113_b());
            var attempt = new Attempt(maid, owner, ledger, token);
            if (attempt.state(false) == 0) {
                attempt
                    .hold(new IllegalStateException(MaidLifecycleDiagnostics.MSG_RELOADED_DEATH_SOURCE_DIFFERS_FROM));
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
        if (type == null)
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_REGISTERED_DEATH_ENTITY_TYPE_REQUIRED);
        var result = new NBTTagCompound();
        result.setString(MaidLifecycleIds.ID_ID, type);
        entity.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    static void validate(NBTTagCompound receipt, UUID token, UUID owner, UUID subject) {
        for (Object field : receipt.func_150296_c()) if (!FIELDS.contains(field))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_DEATH_INGRESS_FIELD_RETAINED + field);
        if (receipt.func_150296_c()
            .size()
            != FIELDS.size()
                - (receipt.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED) ? MaidLifecycleTuning.CORE_INITIAL_VALUE
                    : MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD)
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_VERSION, Constants.NBT.TAG_INT)
            || receipt.getInteger(MaidLifecycleNbtKeys.KEY_VERSION) != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_TOKEN, Constants.NBT.TAG_STRING)
            || !token.toString()
                .equals(receipt.getString(MaidLifecycleNbtKeys.KEY_TOKEN))
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_STAGE, Constants.NBT.TAG_STRING)
            || !(receipt.getString(MaidLifecycleNbtKeys.KEY_STAGE)
                .equals(MaidLifecycleNbtKeys.KEY_PREPARED)
                || receipt.getString(MaidLifecycleNbtKeys.KEY_STAGE)
                    .equals(MaidLifecycleNbtKeys.KEY_COMMITTED)
                || receipt.getString(MaidLifecycleNbtKeys.KEY_STAGE)
                    .equals(MaidLifecycleNbtKeys.KEY_HELD))
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_SOURCE, Constants.NBT.TAG_COMPOUND)
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_CLEARED, Constants.NBT.TAG_COMPOUND)
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_ITEMS, Constants.NBT.TAG_LIST)
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_EXPERIENCE, Constants.NBT.TAG_INT)
            || receipt.getInteger(MaidLifecycleNbtKeys.KEY_EXPERIENCE) < 0
            || !receipt.hasKey(MaidLifecycleNbtKeys.KEY_READY_AT, Constants.NBT.TAG_LONG)
            || receipt.getLong(MaidLifecycleNbtKeys.KEY_READY_AT) < 0
            || receipt.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED)
                && !receipt.hasKey(MaidLifecycleNbtKeys.KEY_OBSERVED, Constants.NBT.TAG_COMPOUND))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_DEATH_INGRESS_RECEIPT);
        var items = receipt.getTagList(MaidLifecycleNbtKeys.KEY_ITEMS, Constants.NBT.TAG_COMPOUND);
        if (items.tagCount() == 0
            || items.tagCount() != ((NBTTagList) receipt.getTag(MaidLifecycleNbtKeys.KEY_ITEMS)).tagCount())
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_COMPLETE_DEATH_MATERIALS);
        var source = receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_SOURCE);
        var cleared = receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_CLEARED);
        if (!source.hasKey(MaidLifecycleNbtKeys.KEY_UUID_MOST, Constants.NBT.TAG_LONG)
            || !source.hasKey(MaidLifecycleNbtKeys.KEY_UUID_LEAST, Constants.NBT.TAG_LONG)
            || !source.hasKey(MaidLifecycleNbtKeys.KEY_OWNER_UUID, Constants.NBT.TAG_STRING)
            || !subject.equals(
                new UUID(
                    source.getLong(MaidLifecycleNbtKeys.KEY_UUID_MOST),
                    source.getLong(MaidLifecycleNbtKeys.KEY_UUID_LEAST)))
            || !owner.toString()
                .equals(source.getString(MaidLifecycleNbtKeys.KEY_OWNER_UUID))
            || !source.hasKey(MaidLifecycleNbtKeys.KEY_MAID_EXPERIENCE, Constants.NBT.TAG_INT)
            || !source.hasKey(MaidLifecycleNbtKeys.KEY_HEAL_F, Constants.NBT.TAG_FLOAT)
            || !Float.isFinite(source.getFloat(MaidLifecycleNbtKeys.KEY_HEAL_F))
            || source.getFloat(MaidLifecycleNbtKeys.KEY_HEAL_F) > 0
            || source.getCompoundTag(MaidLifecycleNbtKeys.KEY_FORGE_DATA)
                .hasKey(MaidLifecycleNbtKeys.TAG)
            || !cleared.equals(cleared(source)))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_DEATH_SOURCE_IDENTITY_OR_COMPLETE);
    }

    private static NBTTagCompound cleared(NBTTagCompound source) {
        var result = (NBTTagCompound) source.copy();
        result.setTag(MaidLifecycleNbtKeys.KEY_MAID_INVENTORY, new NBTTagList());
        if (source.hasKey(MaidBaubleNbtKeys.ITEMS)) result.setTag(MaidBaubleNbtKeys.ITEMS, new NBTTagList());
        result.setTag(ProtectionRecoveryNbtKeys.TAG, new NBTTagList());
        result.setInteger(MaidLifecycleNbtKeys.KEY_MAID_EXPERIENCE, MaidLifecycleTuning.CORE_INITIAL_VALUE);
        var equipment = new NBTTagList();
        for (int slot = 0; slot < MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM; slot++)
            equipment.appendTag(new NBTTagCompound());
        result.setTag(MaidLifecycleNbtKeys.KEY_EQUIPMENT, equipment);
        result.removeTag(MaidLifecycleNbtKeys.KEY_TLMNH_OFFHAND);
        return result;
    }

    private static void report(EntityMaid maid, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(Side.SERVER, MaidLifecycleIds.ID_DEATH_INGRESS + maid.getUniqueID(), failure);
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
            if (data.hasKey(MaidLifecycleNbtKeys.TAG)) {
                if (!data.hasKey(MaidLifecycleNbtKeys.TAG, Constants.NBT.TAG_COMPOUND))
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_DEATH_SOURCE_MARKER_PRESERVED);
                marker = data.getCompoundTag(MaidLifecycleNbtKeys.TAG);
                token = UUID.fromString(marker.getString(MaidLifecycleNbtKeys.KEY_TOKEN));
                receipt = (NBTTagCompound) marker.copy();
                validate(receipt, token, owner, subject);
                if (knownToken != null && !knownToken.equals(token))
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_CONFLICTING_LEDGER_AND_ENTITY_DEATH);
            } else {
                if (knownToken != null && !ledger.deathIngress(knownToken)
                    .hasNoTags())
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_PERSISTENT_DEATH_SOURCE_IS_MISSING);
                MaidInteractionTransfer.preserveDeathBalance(maid);
                var source = snapshot(maid);
                token = knownToken == null ? UUID.randomUUID() : knownToken;
                receipt = new NBTTagCompound();
                receipt.setInteger(MaidLifecycleNbtKeys.KEY_VERSION, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
                receipt.setString(MaidLifecycleNbtKeys.KEY_TOKEN, token.toString());
                receipt.setString(MaidLifecycleNbtKeys.KEY_STAGE, MaidLifecycleNbtKeys.KEY_PREPARED);
                receipt.setTag(MaidLifecycleNbtKeys.KEY_SOURCE, source);
                receipt.setTag(MaidLifecycleNbtKeys.KEY_CLEARED, cleared(source));
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
                for (int slot = 0; slot < maid.getMaidBaubleInventory()
                    .getSizeInventory(); slot++) {
                    var stack = maid.getMaidBaubleInventory()
                        .getStackInSlot(slot);
                    if (stack != null) items.appendTag(ProtectedItemCodec.encode(stack));
                }
                for (int i = 0; i < recovery.tagCount(); i++) items.appendTag(
                    recovery.getCompoundTagAt(i)
                        .copy());
                items.appendTag(
                    ProtectedItemCodec
                        .encode(MaidLifecycleItems.credential(MaidResurrectionContent.FILM, token, owner)));
                receipt.setTag(MaidLifecycleNbtKeys.KEY_ITEMS, items);
                receipt.setInteger(
                    MaidLifecycleNbtKeys.KEY_EXPERIENCE,
                    world.getGameRules()
                        .getGameRuleBooleanValue(MaidLifecycleIds.ID_DOMOBLOOT)
                            ? MaidInteractionTransfer.availableExperience(maid)
                            : MaidLifecycleTuning.CORE_INITIAL_VALUE);
                long now = Math.max(MaidLifecycleTuning.CORE_INITIAL_VALUE, world.getTotalWorldTime());
                receipt.setLong(
                    MaidLifecycleNbtKeys.KEY_READY_AT,
                    now > Long.MAX_VALUE - MaidLifecycleTuning.CAPTURE_SERVICE_PUT_SECOND_PARAM ? Long.MAX_VALUE
                        : now + MaidLifecycleTuning.CAPTURE_SERVICE_PUT_SECOND_PARAM);
                validate(receipt, token, owner, subject);
                if (!source.equals(snapshot(maid)))
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_DEATH_SOURCE_CHANGED_DURING_CAPTURE);
                marker = (NBTTagCompound) receipt.copy();
                data.setTag(MaidLifecycleNbtKeys.TAG, marker);
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
                    .getTag(MaidLifecycleNbtKeys.TAG) == marker
                && receipt.equals(marker)
                && (!registered || MaidSpawnChecks.present(maid, world));
        }

        NBTTagCompound base() {
            var full = snapshot(maid);
            full.getCompoundTag(MaidLifecycleNbtKeys.KEY_FORGE_DATA)
                .removeTag(MaidLifecycleNbtKeys.TAG);
            return full;
        }

        int state(boolean registered) {
            if (!identity(registered)) return MaidLifecycleTuning.CORE_INITIAL_VALUE;
            var actual = base();
            // 空库存、零XP时两像本来相同，已确认无原物可扣，不能永远留在Source分支
            if (actual.equals(receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_CLEARED)))
                return MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM;
            return actual.equals(receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_SOURCE))
                ? MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
                : MaidLifecycleTuning.CORE_INITIAL_VALUE;
        }

        void run() {
            if (active || maid.getCombatHitReceipt()
                .blocksTransfer()) return;
            active = true;
            boolean begun = false;
            try {
                if (!MaidLifecycleNbtKeys.KEY_PREPARED.equals(receipt.getString(MaidLifecycleNbtKeys.KEY_STAGE)))
                    return;
                var existing = ledger.deathIngress(token);
                if (!existing.hasNoTags() && !existing.equals(receipt)) return;
                int current = state(true);
                if (current == 0)
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_DEATH_SOURCE_NO_LONGER_MATCHES);
                var source = receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_SOURCE);
                var position = source.getTagList(MaidLifecycleNbtKeys.KEY_POS, Constants.NBT.TAG_DOUBLE);
                if (!source.hasKey(MaidLifecycleNbtKeys.KEY_DIMENSION, Constants.NBT.TAG_INT)
                    || source.getInteger(MaidLifecycleNbtKeys.KEY_DIMENSION) != world.provider.dimensionId
                    || position.tagCount() != MaidLifecycleTuning.TARGET_SIZE_THRESHOLD)
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_DEATH_SOURCE_WORLD_OR);
                ledger.prepareDeath(
                    token,
                    owner,
                    subject,
                    MaidFilmData.identity(source),
                    receipt.getTagList(MaidLifecycleNbtKeys.KEY_ITEMS, Constants.NBT.TAG_COMPOUND),
                    world.provider.dimensionId,
                    Math.floor(position.func_150309_d(MaidLifecycleTuning.CORE_INITIAL_VALUE))
                        + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR,
                    Math.max(
                        MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM,
                        Math.min(
                            world.getHeight() - MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM,
                            Math.floor(position.func_150309_d(MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD)))),
                    Math.floor(position.func_150309_d(MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM))
                        + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR,
                    source.getString(MaidLifecycleNbtKeys.KEY_CUSTOM_NAME),
                    receipt.getInteger(MaidLifecycleNbtKeys.KEY_EXPERIENCE),
                    receipt.getLong(MaidLifecycleNbtKeys.KEY_READY_AT),
                    receipt);
                ledger.beginDeathAttempt(token, receipt);
                begun = true;
                MaidLifecycleDurability.ledger(world, ledger);
                current = state(true);
                if (current == 0)
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_DEATH_SOURCE_CHANGED_DURING_DURABLE);
                if (current == MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD) clear();
                if (state(true) != MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM)
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_SOURCE_AFTER_DEATH_DEBIT);
                var expected = snapshot(maid);
                MaidLifecycleDurability.entities(
                    world,
                    Collections.singletonList(new MaidLifecycleDurability.Publication(maid, expected, true)),
                    true);
                if (state(true) != MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM)
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_DEATH_SOURCE_CHANGED_DURING_NATIVE);
                ledger.finishDeathAttempt(world, token, receipt);
                if (identity(true) && state(true) == MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM) maid.getEntityData()
                    .removeTag(MaidLifecycleNbtKeys.TAG);
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
                return MaidLifecycleTuning.CORE_INITIAL_VALUE;
            }
        }

        void clear() {
            var inventory = maid.getMaidInventory();
            var baubles = maid.getMaidBaubleInventory();
            var baubleReferences = new ItemStack[baubles.getSizeInventory()];
            var baubleValues = new ItemStack[baubleReferences.length];
            for (int slot = 0; slot < baubleReferences.length; slot++) {
                baubleReferences[slot] = baubles.getStackInSlot(slot);
                baubleValues[slot] = ItemStack.copyItemStack(baubleReferences[slot]);
            }
            var references = new ItemStack[inventory.getSizeInventory()];
            var values = new ItemStack[references.length];
            for (int slot = 0; slot < references.length; slot++) {
                references[slot] = inventory.getStackInSlot(slot);
                values[slot] = ItemStack.copyItemStack(references[slot]);
            }
            if (state(true) != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD)
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_ORIGINAL_DEATH_MATERIALS_CHANGED_BEFORE);
            for (int slot = 0; slot < references.length; slot++) {
                if (!identity(true) || inventory.getStackInSlot(slot) != references[slot]
                    || !ItemStack.areItemStacksEqual(references[slot], values[slot]))
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_DEATH_INVENTORY_SUCCESSOR_WAS);
                inventory.setInventorySlotContents(slot, null);
            }
            for (int slot = 0; slot < baubleReferences.length; slot++) {
                if (!identity(true) || baubles.getStackInSlot(slot) != baubleReferences[slot]
                    || !ItemStack.areItemStacksEqual(baubleReferences[slot], baubleValues[slot]))
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_DEATH_INVENTORY_SUCCESSOR_WAS);
                baubles.setInventorySlotContents(slot, null);
            }
            if (!inventory.recovery()
                .write()
                .equals(
                    receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_SOURCE)
                        .getTagList(ProtectionRecoveryNbtKeys.TAG, Constants.NBT.TAG_COMPOUND)))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_RECOVERY_SUCCESSOR_WAS_NOT);
            inventory.recovery()
                .clear();
            clearEquipmentModifiers(values);
            var beforeXp = (NBTTagCompound) receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_CLEARED)
                .copy();
            beforeXp.setInteger(
                MaidLifecycleNbtKeys.KEY_MAID_EXPERIENCE,
                receipt.getCompoundTag(MaidLifecycleNbtKeys.KEY_SOURCE)
                    .getInteger(MaidLifecycleNbtKeys.KEY_MAID_EXPERIENCE));
            if (!identity(true) || !base().equals(beforeXp))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_DEATH_BALANCE_OR_IDENTITY);
            maid.setExperience(MaidLifecycleTuning.CORE_INITIAL_VALUE);
        }

        void clearEquipmentModifiers(ItemStack[] original) {
            // Native NBT temporarily excludes current equipment modifiers. After clearing the slots, remove those
            // exact old modifiers now instead of waiting for the next native equipment-cache update.
            for (int slot : new int[] { MaidInventoryLayout.MAIN_HAND, MaidInventoryLayout.ARMOR_START,
                MaidInventoryLayout.ARMOR_START + 1,
                MaidInventoryLayout.ARMOR_START + MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM,
                MaidInventoryLayout.ARMOR_START + MaidLifecycleTuning.TARGET_SIZE_THRESHOLD }) {
                var stack = original[slot];
                if (stack == null) continue;
                for (Object raw : stack.getAttributeModifiers()
                    .entries()) {
                    var entry = (Map.Entry<?, ?>) raw;
                    if (!(entry.getKey() instanceof String name)
                        || !(entry.getValue() instanceof AttributeModifier expected))
                        throw new IllegalStateException(
                            MaidLifecycleDiagnostics.MSG_UNKNOWN_ORIGINAL_EQUIPMENT_ATTRIBUTE_RETAINED);
                    if (!identity(true)) throw new IllegalStateException(
                        MaidLifecycleDiagnostics.MSG_UNKNOWN_DEATH_SOURCE_DURING_ATTRIBUTE);
                    var attribute = maid.getAttributeMap()
                        .getAttributeInstanceByName(name);
                    if (attribute == null) continue;
                    var current = attribute.getModifier(expected.getID());
                    if (current == null) continue;
                    if (!current.getName()
                        .equals(expected.getName()) || current.getOperation() != expected.getOperation()
                        || Double.doubleToLongBits(current.getAmount()) != Double.doubleToLongBits(expected.getAmount())
                        || current.isSaved() != expected.isSaved())
                        throw new IllegalStateException(
                            MaidLifecycleDiagnostics.MSG_UNKNOWN_EQUIPMENT_ATTRIBUTE_SUCCESSOR_RETAINED);
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
