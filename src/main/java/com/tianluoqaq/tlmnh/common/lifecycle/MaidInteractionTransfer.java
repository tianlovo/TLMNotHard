package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.interaction.MaidInteractions;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

import cpw.mods.fml.relauncher.Side;

/** 活体经验瓶与满包容器出站；锁只属于原实体，胶片保留同一份不透明保管记录 */
public final class MaidInteractionTransfer {

    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            MaidLifecycleNbtKeys.KEY_SCHEMA,
            MaidLifecycleNbtKeys.KEY_RUN,
            MaidLifecycleNbtKeys.KEY_KIND,
            MaidLifecycleNbtKeys.KEY_PLAYER,
            MaidLifecycleNbtKeys.KEY_SOURCE,
            MaidLifecycleNbtKeys.KEY_ORIGINAL_MAID,
            MaidLifecycleNbtKeys.KEY_INVENTORY,
            MaidLifecycleNbtKeys.KEY_PAID_INVENTORY,
            MaidLifecycleNbtKeys.KEY_SLOT,
            MaidLifecycleNbtKeys.KEY_XP_BEFORE,
            MaidLifecycleNbtKeys.KEY_XP_RESERVED,
            MaidLifecycleNbtKeys.KEY_PAID,
            MaidLifecycleNbtKeys.KEY_OUTPUT,
            MaidLifecycleNbtKeys.KEY_DEATH_BALANCES));

    private MaidInteractionTransfer() {}

    /** 胶片只恢复本域的不透明保管记录，不替换其他模组的ForgeData */
    static void restoreFilmHold(EntityMaid maid, NBTTagCompound identity) {
        if (!identity.hasKey(MaidLifecycleNbtKeys.KEY_FORGE_DATA, Constants.NBT.TAG_COMPOUND)) return;
        var original = identity.getCompoundTag(MaidLifecycleNbtKeys.KEY_FORGE_DATA);
        var restored = maid.getEntityData();
        for (Object raw : original.func_150296_c()) {
            String key = (String) raw;
            if (key.equals(MaidLifecycleNbtKeys.TAG_2)
                || key.startsWith(MaidLifecycleNbtKeys.TAG_2 + MaidLifecycleIds.ID_DEATHBALANCE)) {
                if (restored.hasKey(key))
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INTERACTION_FILM_HOLD_DESTINATION_IS);
                restored.setTag(
                    key,
                    original.getTag(key)
                        .copy());
            }
        }
    }

    /** 剥离胶片顶层经验之前保留当前完整余额，包含持有期间新增的XP */
    public static void preserveDeathBalance(EntityMaid maid) {
        var data = maid.getEntityData();
        if (!data.hasKey(MaidLifecycleNbtKeys.TAG_2)) return;
        var row = new NBTTagCompound();
        row.setInteger(MaidLifecycleNbtKeys.KEY_CURRENT_EXPERIENCE, maid.getExperience());
        row.setInteger(MaidLifecycleNbtKeys.KEY_AVAILABLE_EXPERIENCE, availableExperience(maid));
        row.setLong(MaidLifecycleNbtKeys.KEY_WORLD_TICK, maid.worldObj.getTotalWorldTime());
        if (data.hasKey(MaidLifecycleNbtKeys.TAG_2, Constants.NBT.TAG_COMPOUND)) {
            var hold = data.getCompoundTag(MaidLifecycleNbtKeys.TAG_2);
            try {
                validate(hold);
                hold.getTagList(MaidLifecycleNbtKeys.KEY_DEATH_BALANCES, Constants.NBT.TAG_COMPOUND)
                    .appendTag(row);
                return;
            } catch (Exception | LinkageError | AssertionError failure) {
                IntegrationGuard.throwIfFatal(failure);
                IntegrationGuard
                    .report(Side.SERVER, MaidLifecycleIds.ID_INTERACTION_TRANSFER + maid.getUniqueID(), failure);
            }
        }
        // 未知原记录不改写；独立唯一子键只保管此次新余额，没有第二个兑现入口
        String key;
        do key = MaidLifecycleNbtKeys.TAG_2 + MaidLifecycleIds.ID_DEATHBALANCE + UUID.randomUUID();
        while (data.hasKey(key));
        data.setTag(key, row);
    }

    /** 未知格式不能变成可兑现余额；已扣除的已知XP不会再次从死亡余额扣除 */
    public static int availableExperience(EntityMaid maid) {
        int current = maid.getExperience();
        if (!maid.getEntityData()
            .hasKey(MaidLifecycleNbtKeys.TAG_2)) return current;
        try {
            if (!maid.getEntityData()
                .hasKey(MaidLifecycleNbtKeys.TAG_2, Constants.NBT.TAG_COMPOUND))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_INTERACTION_HOLD_ENCODING);
            var hold = maid.getEntityData()
                .getCompoundTag(MaidLifecycleNbtKeys.TAG_2);
            validate(hold);
            int reserved = hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_RESERVED),
                before = hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_BEFORE);
            if (reserved == 0) return current;
            if (hold.getBoolean(MaidLifecycleNbtKeys.KEY_PAID))
                return current == before - reserved ? current : MaidLifecycleTuning.CORE_INITIAL_VALUE;
            return current == before ? before - reserved : MaidLifecycleTuning.CORE_INITIAL_VALUE;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard
                .report(Side.SERVER, MaidLifecycleIds.ID_INTERACTION_TRANSFER + maid.getUniqueID(), failure);
            return MaidLifecycleTuning.CORE_INITIAL_VALUE;
        }
    }

    public static boolean publish(EntityMaid maid, EntityPlayer player, ItemStack source, int count, ItemStack output,
        int experience, Runnable effect) {
        Frame frame = null;
        MaidLifecycleDurability.Publication publication = null;
        boolean held = false, debited = false, external = false, effectStarted = false, complete = false;
        try {
            if (maid.getEntityData()
                .hasKey(MaidLifecycleNbtKeys.TAG_2))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_AN_EXISTING_INTERACTION_HOLD_MUST);
            frame = new Frame(maid, player, source, count, output, experience);
            MaidLifecycleDurability.requirePlayer(player);
            MaidLifecycleDurability.requireEntities(frame.world);
            if (!frame.original()) return false;
            // originalMaid在挂锁前取得，避免把自身快照递归存入自身
            maid.getEntityData()
                .setTag(MaidLifecycleNbtKeys.TAG_2, frame.hold);
            held = true;
            frame.expectedMaid = MaidLifecycleDurability.maidSnapshot(maid);
            frame.saveMaid();
            if (!frame.original()) return false;
            debited = true;
            frame.debit();
            MaidLifecycleDurability.player(player, frame.paidInventory, frame.selected);
            if (!frame.payable()) return false;
            var drop = new EntityItem(frame.world, player.posX, player.posY, player.posZ, output.copy());
            drop.delayBeforeCanPickup = MaidLifecycleTuning.FILM_RECOVERY_DELAY_BEFORE_CAN_PICKUP;
            publication = new MaidLifecycleDurability.Publication(drop, output);
            external = true;
            if (!frame.world.spawnEntityInWorld(drop) || !MaidSpawnChecks.itemPresent(drop, output, frame.world)
                || !frame.payable()) return false;
            MaidLifecycleDurability.entities(frame.world, Collections.singletonList(publication), true);
            if (!frame.payable()) return false;
            effectStarted = true;
            if (experience > 0) maid.setExperience(frame.beforeExperience - experience);
            frame.hold.setBoolean(MaidLifecycleNbtKeys.KEY_PAID, true);
            frame.expectedHold = (NBTTagCompound) frame.hold.copy();
            effect.run();
            if (!frame.paidSource())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_SOURCE_CHANGE_AFTER_THE);
            frame.saveMaid();
            publication.check(frame.world, true);
            frame.clearHold();
            complete = true;
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(maid, player, failure);
            return false;
        } finally {
            if (held && !complete && !effectStarted) {
                try {
                    if (external) {
                        publication.discard(frame.world);
                        MaidLifecycleDurability.entities(frame.world, Collections.singletonList(publication), false);
                    }
                    if (debited) frame.refund();
                    if (maid.getExperience() != frame.beforeExperience) throw new IllegalStateException(
                        MaidLifecycleDiagnostics.MSG_UNKNOWN_MAID_EXPERIENCE_SUCCESSOR_REMAINS);
                    if (!frame.expectedMaid.equals(MaidLifecycleDurability.maidSnapshot(maid)))
                        throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_MAID_STATE_REMAINS_INTACT);
                    frame.clearHold();
                } catch (Exception | LinkageError | AssertionError failure) {
                    report(maid, player, failure);
                }
            }
        }
    }

    private static void report(EntityMaid maid, EntityPlayer player, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(
            Side.SERVER,
            MaidLifecycleIds.ID_INTERACTION_TRANSFER + maid.getUniqueID(),
            failure,
            player.getUniqueID());
    }

    private static void validate(NBTTagCompound hold) {
        for (Object key : hold.func_150296_c()) if (!FIELDS.contains(key)) throw new IllegalStateException(
            MaidLifecycleDiagnostics.MSG_UNKNOWN_INTERACTION_HOLD_FIELD_PRESERVED + key);
        if (hold.func_150296_c()
            .size() != FIELDS.size() || !hold.hasKey(MaidLifecycleNbtKeys.KEY_SCHEMA, Constants.NBT.TAG_INT)
            || hold.getInteger(MaidLifecycleNbtKeys.KEY_SCHEMA) != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
            || !hold.hasKey(MaidLifecycleNbtKeys.KEY_KIND, Constants.NBT.TAG_STRING)
            || !(hold.getString(MaidLifecycleNbtKeys.KEY_KIND)
                .equals(MaidLifecycleIds.ID_EXPERIENCE)
                || hold.getString(MaidLifecycleNbtKeys.KEY_KIND)
                    .equals(MaidLifecycleIds.ID_CONTAINER)))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_INTERACTION_HOLD_SCHEMA);
        for (String key : new String[] { MaidLifecycleNbtKeys.KEY_RUN, MaidLifecycleNbtKeys.KEY_PLAYER }) {
            if (!hold.hasKey(key, Constants.NBT.TAG_STRING))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_INTERACTION_HOLD_IDENTITY);
            UUID.fromString(hold.getString(key));
        }
        for (String key : new String[] { MaidLifecycleNbtKeys.KEY_SOURCE, MaidLifecycleNbtKeys.KEY_ORIGINAL_MAID,
            MaidLifecycleNbtKeys.KEY_OUTPUT })
            if (!hold.hasKey(key, Constants.NBT.TAG_COMPOUND) || hold.getCompoundTag(key)
                .hasNoTags())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_INTERACTION_SOURCE_SNAPSHOT);
        for (String key : new String[] { MaidLifecycleNbtKeys.KEY_INVENTORY, MaidLifecycleNbtKeys.KEY_PAID_INVENTORY,
            MaidLifecycleNbtKeys.KEY_DEATH_BALANCES })
            if (!hold.hasKey(key, Constants.NBT.TAG_LIST)
                || ((NBTTagList) hold.getTag(key)).tagCount() != hold.getTagList(key, Constants.NBT.TAG_COMPOUND)
                    .tagCount())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_INTERACTION_INVENTORY_SNAPSHOT);
        var balances = hold.getTagList(MaidLifecycleNbtKeys.KEY_DEATH_BALANCES, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < balances.tagCount(); i++) {
            var row = balances.getCompoundTagAt(i);
            if (row.func_150296_c()
                .size() != MaidLifecycleTuning.TARGET_SIZE_THRESHOLD
                || !row.hasKey(MaidLifecycleNbtKeys.KEY_CURRENT_EXPERIENCE, Constants.NBT.TAG_INT)
                || !row.hasKey(MaidLifecycleNbtKeys.KEY_AVAILABLE_EXPERIENCE, Constants.NBT.TAG_INT)
                || !row.hasKey(MaidLifecycleNbtKeys.KEY_WORLD_TICK, Constants.NBT.TAG_LONG)
                || row.getInteger(MaidLifecycleNbtKeys.KEY_CURRENT_EXPERIENCE) < 0
                || row.getInteger(MaidLifecycleNbtKeys.KEY_AVAILABLE_EXPERIENCE) < 0
                || row.getInteger(MaidLifecycleNbtKeys.KEY_AVAILABLE_EXPERIENCE)
                    > row.getInteger(MaidLifecycleNbtKeys.KEY_CURRENT_EXPERIENCE))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_INTERACTION_DEATH_BALANCE_REMAINS);
        }
        if (!hold.hasKey(MaidLifecycleNbtKeys.KEY_SLOT, Constants.NBT.TAG_INT)
            || hold.getInteger(MaidLifecycleNbtKeys.KEY_SLOT) < 0
            || !hold.hasKey(MaidLifecycleNbtKeys.KEY_XP_BEFORE, Constants.NBT.TAG_INT)
            || !hold.hasKey(MaidLifecycleNbtKeys.KEY_XP_RESERVED, Constants.NBT.TAG_INT)
            || hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_BEFORE) < 0
            || hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_RESERVED) < 0
            || hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_RESERVED)
                > hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_BEFORE)
            || hold.getString(MaidLifecycleNbtKeys.KEY_KIND)
                .equals(MaidLifecycleIds.ID_EXPERIENCE) && hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_RESERVED) == 0
            || hold.getString(MaidLifecycleNbtKeys.KEY_KIND)
                .equals(MaidLifecycleIds.ID_CONTAINER) && hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_RESERVED) != 0
            || !hold.hasKey(MaidLifecycleNbtKeys.KEY_PAID, Constants.NBT.TAG_BYTE)
            || hold.getByte(MaidLifecycleNbtKeys.KEY_PAID) < 0
            || hold.getByte(MaidLifecycleNbtKeys.KEY_PAID) > MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD)
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_INTERACTION_HOLD_BALANCE);
    }

    private static final class Frame {

        final EntityMaid maid;
        final EntityPlayer player;
        final World world;
        final UUID maidIdentity, playerIdentity;
        final int maidId, selected, beforeExperience;
        final String owner;
        final boolean creative;
        final InventoryPlayer inventory;
        final ItemStack[] main, armor;
        final ItemStack source, originalSource, paid, cursor;
        final NBTTagList originalInventory, paidInventory;
        final NBTTagCompound hold = new NBTTagCompound();
        NBTTagCompound expectedMaid, expectedHold;

        Frame(EntityMaid maid, EntityPlayer player, ItemStack source, int count, ItemStack output, int experience) {
            this.maid = maid;
            this.player = player;
            world = maid.worldObj;
            maidIdentity = maid.getUniqueID();
            playerIdentity = player.getUniqueID();
            maidId = maid.getEntityId();
            owner = maid.func_152113_b();
            inventory = player.inventory;
            main = inventory.mainInventory;
            armor = inventory.armorInventory;
            selected = inventory.currentItem;
            creative = player.capabilities.isCreativeMode;
            this.source = source;
            originalSource = source.copy();
            cursor = ItemStack.copyItemStack(inventory.getItemStack());
            beforeExperience = maid.getExperience();
            if (count <= 0 || count > source.stackSize
                || experience < 0
                || experience > beforeExperience
                || selected < 0
                || selected >= main.length
                || main[selected] != source)
                throw new IllegalArgumentException(MaidLifecycleDiagnostics.MSG_INVALID_CURRENT_INTERACTION_SOURCE);
            paid = creative ? source : source.stackSize == count ? null : source.copy();
            if (!creative && paid != null) paid.stackSize -= count;
            originalInventory = (NBTTagList) inventory.writeToNBT(new NBTTagList())
                .copy();
            paidInventory = new NBTTagList();
            for (int i = 0; i < originalInventory.tagCount(); i++) {
                var row = originalInventory.getCompoundTagAt(i);
                if ((row.getByte(MaidLifecycleNbtKeys.KEY_SLOT) & MaidLifecycleTuning.UNSIGNED_BYTE_MASK) == selected) {
                    if (paid != null) {
                        var replacement = (NBTTagCompound) paid.writeToNBT(new NBTTagCompound())
                            .copy();
                        replacement.setByte(MaidLifecycleNbtKeys.KEY_SLOT, row.getByte(MaidLifecycleNbtKeys.KEY_SLOT));
                        paidInventory.appendTag(replacement);
                    }
                } else paidInventory.appendTag(row.copy());
            }
            var originalMaid = MaidLifecycleDurability.maidSnapshot(maid);
            hold.setInteger(MaidLifecycleNbtKeys.KEY_SCHEMA, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
            hold.setString(
                MaidLifecycleNbtKeys.KEY_RUN,
                UUID.randomUUID()
                    .toString());
            hold.setString(
                MaidLifecycleNbtKeys.KEY_KIND,
                experience > 0 ? MaidLifecycleIds.ID_EXPERIENCE : MaidLifecycleIds.ID_CONTAINER);
            hold.setString(MaidLifecycleNbtKeys.KEY_PLAYER, playerIdentity.toString());
            hold.setTag(MaidLifecycleNbtKeys.KEY_SOURCE, ProtectedItemCodec.encode(originalSource));
            hold.setTag(MaidLifecycleNbtKeys.KEY_ORIGINAL_MAID, originalMaid);
            hold.setTag(MaidLifecycleNbtKeys.KEY_INVENTORY, originalInventory.copy());
            hold.setTag(MaidLifecycleNbtKeys.KEY_PAID_INVENTORY, paidInventory.copy());
            hold.setInteger(MaidLifecycleNbtKeys.KEY_SLOT, selected);
            hold.setInteger(MaidLifecycleNbtKeys.KEY_XP_BEFORE, beforeExperience);
            hold.setInteger(MaidLifecycleNbtKeys.KEY_XP_RESERVED, experience);
            hold.setBoolean(MaidLifecycleNbtKeys.KEY_PAID, false);
            hold.setTag(MaidLifecycleNbtKeys.KEY_OUTPUT, ProtectedItemCodec.encode(output));
            hold.setTag(MaidLifecycleNbtKeys.KEY_DEATH_BALANCES, new NBTTagList());
            validate(hold);
            expectedHold = (NBTTagCompound) hold.copy();
        }

        boolean storage() {
            return player.isEntityAlive() && player.worldObj == world
                && playerIdentity.equals(player.getUniqueID())
                && player.inventory == inventory
                && inventory.mainInventory == main
                && inventory.armorInventory == armor;
        }

        boolean authorized() {
            return storage() && MaidInteractions.canInteract(maid, player)
                && maid.isOwner(player)
                && owner.equals(maid.func_152113_b())
                && player.capabilities.isCreativeMode == creative
                && inventory.currentItem == selected
                && MaidInteractions.isMainHand(player)
                && ItemStack.areItemStacksEqual(cursor, inventory.getItemStack());
        }

        boolean original() {
            return authorized() && main[selected] == source
                && ItemStack.areItemStacksEqual(source, originalSource)
                && originalInventory.equals(inventory.writeToNBT(new NBTTagList()))
                && maid.getExperience() == beforeExperience
                && (expectedMaid == null || expectedMaid.equals(MaidLifecycleDurability.maidSnapshot(maid)));
        }

        boolean payable() {
            return authorized() && main[selected] == paid
                && paidInventory.equals(inventory.writeToNBT(new NBTTagList()))
                && ItemStack.areItemStacksEqual(source, originalSource)
                && ownsHold()
                && expectedMaid.equals(MaidLifecycleDurability.maidSnapshot(maid));
        }

        boolean paidSource() {
            return authorized() && main[selected] == paid
                && paidInventory.equals(inventory.writeToNBT(new NBTTagList()))
                && ownsHold()
                && ItemStack.areItemStacksEqual(source, originalSource)
                && maid.getExperience() == beforeExperience - hold.getInteger(MaidLifecycleNbtKeys.KEY_XP_RESERVED);
        }

        void debit() {
            if (!original())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INTERACTION_SOURCE_CHANGED_BEFORE_NATIVE);
            if (!creative) inventory.setInventorySlotContents(selected, paid);
            inventory.markDirty();
            if (!payable())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INTERACTION_DEBIT_CALLBACK_CHANGED_ITS);
        }

        void refund() {
            if (!storage() || main[selected] != paid
                || !ItemStack.areItemStacksEqual(source, originalSource)
                || !ItemStack.areItemStacksEqual(paid, expectedPaid()))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_PLAYER_SUCCESSOR_MUST_REMAIN);
            if (!creative) inventory.setInventorySlotContents(selected, originalSource.copy());
            inventory.markDirty();
            MaidLifecycleDurability.player(
                player,
                (NBTTagList) inventory.writeToNBT(new NBTTagList())
                    .copy(),
                inventory.currentItem);
        }

        ItemStack expectedPaid() {
            if (paid == null) return null;
            for (int i = 0; i < paidInventory.tagCount(); i++) {
                var row = paidInventory.getCompoundTagAt(i);
                if ((row.getByte(MaidLifecycleNbtKeys.KEY_SLOT) & MaidLifecycleTuning.UNSIGNED_BYTE_MASK) == selected)
                    return ItemStack.loadItemStackFromNBT(row);
            }
            return null;
        }

        boolean ownsHold() {
            return maid.worldObj == world && maid.getEntityId() == maidId
                && maidIdentity.equals(maid.getUniqueID())
                && world.getEntityByID(maidId) == maid
                && maid.getEntityData()
                    .getTag(MaidLifecycleNbtKeys.TAG_2) == hold
                && expectedHold.equals(hold);
        }

        void saveMaid() {
            if (!ownsHold() || !MaidSpawnChecks.present(maid, world))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INTERACTION_SOURCE_MAID_CHANGED_IDENTITY);
            saveCurrentMaid();
        }

        void saveCurrentMaid() {
            var snapshot = MaidLifecycleDurability.maidSnapshot(maid);
            MaidLifecycleDurability.entities(
                world,
                Collections.singletonList(new MaidLifecycleDurability.Publication(maid, snapshot)),
                true);
        }

        void clearHold() {
            if (!ownsHold())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_INTERACTION_HOLD_SUCCESSOR_MUST);
            var data = maid.getEntityData();
            data.removeTag(MaidLifecycleNbtKeys.TAG_2);
            try {
                saveCurrentMaid();
            } catch (Exception | LinkageError | AssertionError failure) {
                if (maid.getEntityData() == data && !data.hasKey(MaidLifecycleNbtKeys.TAG_2))
                    data.setTag(MaidLifecycleNbtKeys.TAG_2, hold);
                throw failure;
            }
        }
    }
}
