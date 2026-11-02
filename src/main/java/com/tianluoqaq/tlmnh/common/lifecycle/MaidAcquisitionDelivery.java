package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;

/** 获取权与实际玩家交付分别确认；只凭准确物理收据恢复，不按旧槽位或相同原材料猜认 */
final class MaidAcquisitionDelivery {

    private static final String RECEIPT = "TLMNHAcquisitionReceipt";

    private MaidAcquisitionDelivery() {}

    static boolean gift(EntityPlayer player) {
        var ledger = ledger(player);
        if (ledger == null) return false;
        var token = ledger.initialToken(player.getUniqueID());
        if (ledger.delivered(token) || !ledger.beginAcquisition(token)) return false;
        try {
            int existing = credentialSlot(player, ledger, token);
            if (existing >= 0) {
                acknowledge(player, ledger, token, existing);
                return false;
            }
            if (ledger.acquisitionPhase(token) != MaidLifecycleData.AcquisitionPhase.NONE) return false;
            int slot = player.inventory.getFirstEmptyStack();
            if (slot < 0 || slot >= player.inventory.mainInventory.length
                || player.inventory.mainInventory[slot] != null) return false;
            var before = new Snapshot(player);
            var bound = MaidLifecycleItems.credential(MaidResurrectionContent.INITIAL_SLAB, token, before.owner);
            var expected = bound.copy();
            if (!before.unchanged() || player.inventory.mainInventory[slot] != null) return false;
            MaidLifecycleDurability.requirePlayer(player);
            ledger.prepareAcquisition(
                before.world,
                token,
                new NBTTagCompound(),
                new NBTTagCompound(),
                ProtectedItemCodec.encode(bound));
            if (!before.unchanged() || player.inventory.mainInventory[slot] != null) return false;
            player.inventory.setInventorySlotContents(slot, bound);
            if (!before.replaced(slot, bound, expected)) return false;
            player.inventory.markDirty();
            persistReplacement(before, slot, bound, expected);
            ledger.finishAcquisition(before.world, token);
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, token, failure);
            return false;
        } finally {
            ledger.endAcquisition(token);
        }
    }

    /** 登录仅确认已经存在的准确凭证/核心收据，不在未知去向时补造物品 */
    static void resume(EntityPlayer player) {
        if (!MaidLifecycleService.canUse(player)) return;
        var ledger = ledger(player);
        if (ledger == null) return;
        for (int slot = 0; slot < player.inventory.mainInventory.length; slot++) {
            var stack = player.inventory.mainInventory[slot];
            var token = receiptToken(stack);
            if (token != null) {
                core(player, stack, slot, false, 0, 0, 0);
                continue;
            }
            token = MaidLifecycleItems.token(stack);
            if (!credential(player, ledger, stack, token) || ledger.delivered(token) || !ledger.beginAcquisition(token))
                continue;
            try {
                acknowledge(player, ledger, token, slot);
            } catch (Exception | LinkageError | AssertionError failure) {
                report(player, token, failure);
            } finally {
                ledger.endAcquisition(token);
            }
        }
    }

    static ItemStack core(EntityPlayer player, ItemStack source, int slot, boolean clicked, int x, int y, int z) {
        if (!MaidLifecycleService.canUse(player) || source == null
            || source.stackSize != 1
            || source.getItem() != MaidResurrectionContent.MAID_CORE
            || slot < 0
            || slot >= player.inventory.mainInventory.length
            || player.inventory.mainInventory[slot] != source) return null;
        var ledger = ledger(player);
        if (ledger == null) return null;
        UUID token = null;
        boolean acquired = false;
        try {
            var before = new Snapshot(player);
            var expectedSource = source.copy();
            if (!allowed(before, slot, source, expectedSource, clicked, x, y, z)) return null;
            MaidLifecycleDurability.requirePlayer(player);
            token = receiptToken(source);
            if (source.hasTagCompound() && source.getTagCompound()
                .hasKey(RECEIPT)) {
                if (token == null || ledger.initialKind(token) != MaidLifecycleData.Kind.ACQUIRED
                    || !before.owner.equals(ledger.owner(token, MaidLifecycleData.Kind.ACQUIRED))
                    || ledger.delivered(token)
                    || ledger.acquisitionPhase(token) == MaidLifecycleData.AcquisitionPhase.NONE
                    || !ItemStack
                        .areItemStacksEqual(source, ProtectedItemCodec.decode(ledger.acquisitionReceipt(token))))
                    return null;
            } else {
                // 未标记的原材料永不认领旧PREPARED，即使同槽/同NBT也只能付出这一份新材料开新权利
                token = ledger.acquiredToken(before.owner);
            }
            if (!ledger.beginAcquisition(token)) return null;
            acquired = true;
            if (ledger.acquisitionPhase(token) == MaidLifecycleData.AcquisitionPhase.NONE) {
                var stamped = source.copy();
                var tag = stamped.hasTagCompound() ? (NBTTagCompound) stamped.getTagCompound()
                    .copy() : new NBTTagCompound();
                var receipt = new NBTTagCompound();
                receipt.setInteger("Schema", 1);
                receipt.setString("Token", token.toString());
                receipt.setString("Owner", before.owner.toString());
                tag.setTag(RECEIPT, receipt);
                stamped.setTagCompound(tag);
                var bound = MaidLifecycleItems.credential(MaidResurrectionContent.INITIAL_SLAB, token, before.owner);
                if (source.hasTagCompound()) {
                    var payload = (NBTTagCompound) source.getTagCompound()
                        .copy();
                    payload.setString("TLMNHToken", token.toString());
                    payload.setString("InitMaidOwner", before.owner.toString());
                    bound.setTagCompound(payload);
                }
                ledger.prepareAcquisition(
                    before.world,
                    token,
                    ProtectedItemCodec.encode(source),
                    ProtectedItemCodec.encode(stamped),
                    ProtectedItemCodec.encode(bound));
                if (!allowed(before, slot, source, expectedSource, clicked, x, y, z)) return null;
                var expectedStamp = stamped.copy();
                player.inventory.setInventorySlotContents(slot, stamped);
                if (!before.replaced(slot, stamped, expectedStamp)) return null;
                player.inventory.markDirty();
                persistReplacement(before, slot, stamped, expectedStamp);
                source = stamped;
            } else {
                // 准确收据已存在仍先确认真实player.dat，不能让仅在活库存中的收据假装已落盘
                persistReplacement(before, slot, source, expectedSource);
            }
            var stampedBefore = new Snapshot(player);
            var expectedStamp = source.copy();
            if (!allowed(stampedBefore, slot, source, expectedStamp, clicked, x, y, z)) return null;
            var result = ProtectedItemCodec.decode(ledger.acquisitionCredential(token));
            if (!credential(player, ledger, result, token))
                throw new IllegalStateException("Invalid stored acquisition output");
            var expectedResult = result.copy();
            ledger.issueAcquisition(stampedBefore.world, token);
            if (!allowed(stampedBefore, slot, source, expectedStamp, clicked, x, y, z)) return null;
            player.inventory.setInventorySlotContents(slot, result);
            if (!stampedBefore.replaced(slot, result, expectedResult)) return null;
            player.inventory.markDirty();
            persistReplacement(stampedBefore, slot, result, expectedResult);
            ledger.finishAcquisition(stampedBefore.world, token);
            return result;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, token, failure);
            return null;
        } finally {
            if (acquired) ledger.endAcquisition(token);
        }
    }

    private static void acknowledge(EntityPlayer player, MaidLifecycleData ledger, UUID token, int slot) {
        var before = new Snapshot(player);
        var stack = player.inventory.mainInventory[slot];
        if (!credential(player, ledger, stack, token)) return;
        var expected = stack.copy();
        MaidLifecycleDurability.requirePlayer(player);
        if (ledger.acquisitionPhase(token) == MaidLifecycleData.AcquisitionPhase.NONE
            && ledger.initialKind(token) == MaidLifecycleData.Kind.ACQUIRED) {
            // 旧版已存在的实体凭证不再制造原材料收据，只确认当前完整玩家库存和原有这一次权利
            persistReplacement(before, slot, stack, expected);
            ledger.markDelivered(token);
            MaidLifecycleDurability.ledger(before.world, ledger);
            return;
        }
        if (ledger.acquisitionPhase(token) == MaidLifecycleData.AcquisitionPhase.NONE) ledger.prepareAcquisition(
            before.world,
            token,
            new NBTTagCompound(),
            new NBTTagCompound(),
            ProtectedItemCodec.encode(stack));
        else ledger.issueAcquisition(before.world, token);
        persistReplacement(before, slot, stack, expected);
        ledger.finishAcquisition(before.world, token);
    }

    private static int credentialSlot(EntityPlayer player, MaidLifecycleData ledger, UUID token) {
        for (int slot = 0; slot < player.inventory.mainInventory.length; slot++)
            if (credential(player, ledger, player.inventory.mainInventory[slot], token)) return slot;
        return -1;
    }

    private static boolean credential(EntityPlayer player, MaidLifecycleData ledger, ItemStack stack, UUID token) {
        var kind = ledger.initialKind(token);
        boolean identity = token != null && kind != null
            && stack != null
            && stack.stackSize == 1
            && stack.getItem() == MaidResurrectionContent.INITIAL_SLAB
            && token.equals(MaidLifecycleItems.token(stack))
            && player.getUniqueID()
                .equals(ledger.owner(token, kind))
            && stack.hasTagCompound()
            && player.getUniqueID()
                .toString()
                .equals(
                    stack.getTagCompound()
                        .getString("InitMaidOwner"));
        return identity && (ledger.acquisitionPhase(token) == MaidLifecycleData.AcquisitionPhase.NONE
            || ItemStack.areItemStacksEqual(stack, ProtectedItemCodec.decode(ledger.acquisitionCredential(token))));
    }

    private static UUID receiptToken(ItemStack stack) {
        if (stack == null || stack.stackSize != 1
            || stack.getItem() != MaidResurrectionContent.MAID_CORE
            || !stack.hasTagCompound()
            || !stack.getTagCompound()
                .hasKey(RECEIPT, 10))
            return null;
        var receipt = stack.getTagCompound()
            .getCompoundTag(RECEIPT);
        if (!receipt.hasKey("Schema", 3) || receipt.getInteger("Schema") != 1
            || !receipt.hasKey("Token", 8)
            || !receipt.hasKey("Owner", 8)
            || receipt.func_150296_c()
                .size() != 3)
            return null;
        try {
            UUID.fromString(receipt.getString("Owner"));
            return UUID.fromString(receipt.getString("Token"));
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static boolean allowed(Snapshot before, int slot, ItemStack stack, ItemStack expected, boolean clicked,
        int x, int y, int z) {
        return before.replaced(slot, stack, expected)
            && (!clicked || MaidLifecycleService
                .releaseRequestAllowed(before.player, stack, expected, before.world, slot, x, y, z))
            && before.replaced(slot, stack, expected);
    }

    private static void persistReplacement(Snapshot before, int slot, ItemStack result, ItemStack expected) {
        if (!before.replaced(slot, result, expected))
            throw new IllegalStateException("Acquisition callback changed inventory");
        var delivered = (NBTTagList) before.inventory.writeToNBT(new NBTTagList())
            .copy();
        MaidLifecycleDurability.player(before.player, delivered, before.selected);
        if (!before.replaced(slot, result, expected)
            || !delivered.equals(before.inventory.writeToNBT(new NBTTagList())))
            throw new IllegalStateException("Native save callback changed acquisition delivery");
    }

    private static void report(EntityPlayer player, UUID token, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(
            Side.SERVER,
            "acquisition/" + (token == null ? player.getUniqueID() : token),
            failure,
            player.getUniqueID());
    }

    private static MaidLifecycleData ledger(EntityPlayer player) {
        try {
            return MaidLifecycleData.get(player.worldObj);
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, null, failure);
            return null;
        }
    }

    private static final class Snapshot {

        final EntityPlayer player;
        final World world;
        final UUID owner;
        final InventoryPlayer inventory;
        final ItemStack[] main, armor, originalMain, originalArmor;
        final ItemStack cursor;
        final int selected;
        final NBTTagList full;

        Snapshot(EntityPlayer player) {
            this.player = player;
            world = player.worldObj;
            owner = player.getUniqueID();
            inventory = player.inventory;
            main = inventory.mainInventory;
            armor = inventory.armorInventory;
            selected = inventory.currentItem;
            cursor = ItemStack.copyItemStack(inventory.getItemStack());
            full = (NBTTagList) inventory.writeToNBT(new NBTTagList())
                .copy();
            originalMain = copy(main);
            originalArmor = copy(armor);
        }

        boolean current() {
            return MaidLifecycleService.canUse(player) && player.worldObj == world
                && owner.equals(player.getUniqueID())
                && player.inventory == inventory
                && inventory.mainInventory == main
                && inventory.armorInventory == armor
                && inventory.currentItem == selected
                && ItemStack.areItemStacksEqual(cursor, inventory.getItemStack());
        }

        boolean unchanged() {
            return current() && full.equals(inventory.writeToNBT(new NBTTagList()));
        }

        boolean replaced(int slot, ItemStack expectedObject, ItemStack expectedStack) {
            if (!current() || main[slot] != expectedObject || !ItemStack.areItemStacksEqual(main[slot], expectedStack))
                return false;
            for (int i = 0; i < main.length; i++)
                if (i != slot && !ItemStack.areItemStacksEqual(main[i], originalMain[i])) return false;
            for (int i = 0; i < armor.length; i++)
                if (!ItemStack.areItemStacksEqual(armor[i], originalArmor[i])) return false;
            return true;
        }

        private static ItemStack[] copy(ItemStack[] source) {
            var result = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) result[i] = ItemStack.copyItemStack(source[i]);
            return result;
        }
    }
}
