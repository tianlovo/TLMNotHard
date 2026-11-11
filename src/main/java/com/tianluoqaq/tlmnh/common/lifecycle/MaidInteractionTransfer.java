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

    public static final String TAG = "TLMNHInteractionTransfer";
    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            "Schema",
            "Run",
            "Kind",
            "Player",
            "Source",
            "OriginalMaid",
            "Inventory",
            "PaidInventory",
            "Slot",
            "XPBefore",
            "XPReserved",
            "Paid",
            "Output",
            "DeathBalances"));

    private MaidInteractionTransfer() {}

    /** 胶片只恢复本域的不透明保管记录，不替换其他模组的ForgeData */
    static void restoreFilmHold(EntityMaid maid, NBTTagCompound identity) {
        if (!identity.hasKey("ForgeData", Constants.NBT.TAG_COMPOUND)) return;
        var original = identity.getCompoundTag("ForgeData");
        var restored = maid.getEntityData();
        for (Object raw : original.func_150296_c()) {
            String key = (String) raw;
            if (key.equals(TAG) || key.startsWith(TAG + "_DeathBalance_")) {
                if (restored.hasKey(key))
                    throw new IllegalStateException("Interaction film hold destination is occupied");
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
        if (!data.hasKey(TAG)) return;
        var row = new NBTTagCompound();
        row.setInteger("CurrentExperience", maid.getExperience());
        row.setInteger("AvailableExperience", availableExperience(maid));
        row.setLong("WorldTick", maid.worldObj.getTotalWorldTime());
        if (data.hasKey(TAG, Constants.NBT.TAG_COMPOUND)) {
            var hold = data.getCompoundTag(TAG);
            try {
                validate(hold);
                hold.getTagList("DeathBalances", Constants.NBT.TAG_COMPOUND)
                    .appendTag(row);
                return;
            } catch (Exception | LinkageError | AssertionError failure) {
                IntegrationGuard.throwIfFatal(failure);
                IntegrationGuard.report(Side.SERVER, "interaction_transfer/" + maid.getUniqueID(), failure);
            }
        }
        // 未知原记录不改写；独立唯一子键只保管此次新余额，没有第二个兑现入口
        String key;
        do key = TAG + "_DeathBalance_" + UUID.randomUUID(); while (data.hasKey(key));
        data.setTag(key, row);
    }

    /** 未知格式不能变成可兑现余额；已扣除的已知XP不会再次从死亡余额扣除 */
    public static int availableExperience(EntityMaid maid) {
        int current = maid.getExperience();
        if (!maid.getEntityData()
            .hasKey(TAG)) return current;
        try {
            if (!maid.getEntityData()
                .hasKey(TAG, Constants.NBT.TAG_COMPOUND))
                throw new IllegalStateException("Unknown interaction hold encoding");
            var hold = maid.getEntityData()
                .getCompoundTag(TAG);
            validate(hold);
            int reserved = hold.getInteger("XPReserved"), before = hold.getInteger("XPBefore");
            if (reserved == 0) return current;
            if (hold.getBoolean("Paid")) return current == before - reserved ? current : 0;
            return current == before ? before - reserved : 0;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, "interaction_transfer/" + maid.getUniqueID(), failure);
            return 0;
        }
    }

    public static boolean publish(EntityMaid maid, EntityPlayer player, ItemStack source, int count, ItemStack output,
        int experience, Runnable effect) {
        Frame frame = null;
        MaidLifecycleDurability.Publication publication = null;
        boolean held = false, debited = false, external = false, effectStarted = false, complete = false;
        try {
            if (maid.getEntityData()
                .hasKey(TAG))
                throw new IllegalStateException(
                    "An existing interaction hold must remain intact before another payout");
            frame = new Frame(maid, player, source, count, output, experience);
            MaidLifecycleDurability.requirePlayer(player);
            MaidLifecycleDurability.requireEntities(frame.world);
            if (!frame.original()) return false;
            // originalMaid在挂锁前取得，避免把自身快照递归存入自身
            maid.getEntityData()
                .setTag(TAG, frame.hold);
            held = true;
            frame.expectedMaid = MaidLifecycleDurability.maidSnapshot(maid);
            frame.saveMaid();
            if (!frame.original()) return false;
            debited = true;
            frame.debit();
            MaidLifecycleDurability.player(player, frame.paidInventory, frame.selected);
            if (!frame.payable()) return false;
            var drop = new EntityItem(frame.world, player.posX, player.posY, player.posZ, output.copy());
            drop.delayBeforeCanPickup = 10;
            publication = new MaidLifecycleDurability.Publication(drop, output);
            external = true;
            if (!frame.world.spawnEntityInWorld(drop) || !MaidSpawnChecks.itemPresent(drop, output, frame.world)
                || !frame.payable()) return false;
            MaidLifecycleDurability.entities(frame.world, Collections.singletonList(publication), true);
            if (!frame.payable()) return false;
            effectStarted = true;
            if (experience > 0) maid.setExperience(frame.beforeExperience - experience);
            frame.hold.setBoolean("Paid", true);
            frame.expectedHold = (NBTTagCompound) frame.hold.copy();
            effect.run();
            if (!frame.paidSource())
                throw new IllegalStateException("Unknown source change after the native interaction effect");
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
                    if (maid.getExperience() != frame.beforeExperience)
                        throw new IllegalStateException("Unknown maid experience successor remains held");
                    if (!frame.expectedMaid.equals(MaidLifecycleDurability.maidSnapshot(maid)))
                        throw new IllegalStateException(
                            "Unknown maid state remains intact with its original interaction escrow");
                    frame.clearHold();
                } catch (Exception | LinkageError | AssertionError failure) {
                    report(maid, player, failure);
                }
            }
        }
    }

    private static void report(EntityMaid maid, EntityPlayer player, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard
            .report(Side.SERVER, "interaction_transfer/" + maid.getUniqueID(), failure, player.getUniqueID());
    }

    private static void validate(NBTTagCompound hold) {
        for (Object key : hold.func_150296_c()) if (!FIELDS.contains(key))
            throw new IllegalStateException("Unknown interaction hold field preserved: " + key);
        if (hold.func_150296_c()
            .size() != FIELDS.size() || !hold.hasKey("Schema", Constants.NBT.TAG_INT)
            || hold.getInteger("Schema") != 1
            || !hold.hasKey("Kind", Constants.NBT.TAG_STRING)
            || !(hold.getString("Kind")
                .equals("experience")
                || hold.getString("Kind")
                    .equals("container")))
            throw new IllegalStateException("Unknown interaction hold schema");
        for (String key : new String[] { "Run", "Player" }) {
            if (!hold.hasKey(key, Constants.NBT.TAG_STRING))
                throw new IllegalStateException("Invalid interaction hold identity");
            UUID.fromString(hold.getString(key));
        }
        for (String key : new String[] { "Source", "OriginalMaid", "Output" })
            if (!hold.hasKey(key, Constants.NBT.TAG_COMPOUND) || hold.getCompoundTag(key)
                .hasNoTags()) throw new IllegalStateException("Invalid interaction source snapshot");
        for (String key : new String[] { "Inventory", "PaidInventory", "DeathBalances" })
            if (!hold.hasKey(key, Constants.NBT.TAG_LIST)
                || ((NBTTagList) hold.getTag(key)).tagCount() != hold.getTagList(key, Constants.NBT.TAG_COMPOUND)
                    .tagCount())
                throw new IllegalStateException("Invalid interaction inventory snapshot");
        var balances = hold.getTagList("DeathBalances", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < balances.tagCount(); i++) {
            var row = balances.getCompoundTagAt(i);
            if (row.func_150296_c()
                .size() != 3 || !row.hasKey("CurrentExperience", Constants.NBT.TAG_INT)
                || !row.hasKey("AvailableExperience", Constants.NBT.TAG_INT)
                || !row.hasKey("WorldTick", Constants.NBT.TAG_LONG)
                || row.getInteger("CurrentExperience") < 0
                || row.getInteger("AvailableExperience") < 0
                || row.getInteger("AvailableExperience") > row.getInteger("CurrentExperience"))
                throw new IllegalStateException("Unknown interaction death balance remains preserved");
        }
        if (!hold.hasKey("Slot", Constants.NBT.TAG_INT) || hold.getInteger("Slot") < 0
            || !hold.hasKey("XPBefore", Constants.NBT.TAG_INT)
            || !hold.hasKey("XPReserved", Constants.NBT.TAG_INT)
            || hold.getInteger("XPBefore") < 0
            || hold.getInteger("XPReserved") < 0
            || hold.getInteger("XPReserved") > hold.getInteger("XPBefore")
            || hold.getString("Kind")
                .equals("experience") && hold.getInteger("XPReserved") == 0
            || hold.getString("Kind")
                .equals("container") && hold.getInteger("XPReserved") != 0
            || !hold.hasKey("Paid", Constants.NBT.TAG_BYTE)
            || hold.getByte("Paid") < 0
            || hold.getByte("Paid") > 1) throw new IllegalStateException("Invalid interaction hold balance");
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
                || main[selected] != source) throw new IllegalArgumentException("Invalid current interaction source");
            paid = creative ? source : source.stackSize == count ? null : source.copy();
            if (!creative && paid != null) paid.stackSize -= count;
            originalInventory = (NBTTagList) inventory.writeToNBT(new NBTTagList())
                .copy();
            paidInventory = new NBTTagList();
            for (int i = 0; i < originalInventory.tagCount(); i++) {
                var row = originalInventory.getCompoundTagAt(i);
                if ((row.getByte("Slot") & 255) == selected) {
                    if (paid != null) {
                        var replacement = (NBTTagCompound) paid.writeToNBT(new NBTTagCompound())
                            .copy();
                        replacement.setByte("Slot", row.getByte("Slot"));
                        paidInventory.appendTag(replacement);
                    }
                } else paidInventory.appendTag(row.copy());
            }
            var originalMaid = MaidLifecycleDurability.maidSnapshot(maid);
            hold.setInteger("Schema", 1);
            hold.setString(
                "Run",
                UUID.randomUUID()
                    .toString());
            hold.setString("Kind", experience > 0 ? "experience" : "container");
            hold.setString("Player", playerIdentity.toString());
            hold.setTag("Source", ProtectedItemCodec.encode(originalSource));
            hold.setTag("OriginalMaid", originalMaid);
            hold.setTag("Inventory", originalInventory.copy());
            hold.setTag("PaidInventory", paidInventory.copy());
            hold.setInteger("Slot", selected);
            hold.setInteger("XPBefore", beforeExperience);
            hold.setInteger("XPReserved", experience);
            hold.setBoolean("Paid", false);
            hold.setTag("Output", ProtectedItemCodec.encode(output));
            hold.setTag("DeathBalances", new NBTTagList());
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
                && maid.getExperience() == beforeExperience - hold.getInteger("XPReserved");
        }

        void debit() {
            if (!original()) throw new IllegalStateException("Interaction source changed before native debit");
            if (!creative) inventory.setInventorySlotContents(selected, paid);
            inventory.markDirty();
            if (!payable()) throw new IllegalStateException("Interaction debit callback changed its destination");
        }

        void refund() {
            if (!storage() || main[selected] != paid
                || !ItemStack.areItemStacksEqual(source, originalSource)
                || !ItemStack.areItemStacksEqual(paid, expectedPaid()))
                throw new IllegalStateException("Unknown player successor must remain intact with its original escrow");
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
                if ((row.getByte("Slot") & 255) == selected) return ItemStack.loadItemStackFromNBT(row);
            }
            return null;
        }

        boolean ownsHold() {
            return maid.worldObj == world && maid.getEntityId() == maidId
                && maidIdentity.equals(maid.getUniqueID())
                && world.getEntityByID(maidId) == maid
                && maid.getEntityData()
                    .getTag(TAG) == hold
                && expectedHold.equals(hold);
        }

        void saveMaid() {
            if (!ownsHold() || !MaidSpawnChecks.present(maid, world))
                throw new IllegalStateException("Interaction source maid changed identity or presence");
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
            if (!ownsHold()) throw new IllegalStateException("Unknown interaction hold successor must remain intact");
            var data = maid.getEntityData();
            data.removeTag(TAG);
            try {
                saveCurrentMaid();
            } catch (Exception | LinkageError | AssertionError failure) {
                if (maid.getEntityData() == data && !data.hasKey(TAG)) data.setTag(TAG, hold);
                throw failure;
            }
        }
    }
}
