package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.BaubleContainer;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class InventoryExpansionTests {

    private InventoryExpansionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "inventory_expansion")
    public static void oldSixSlotSavePreservesEquipmentAndLastBackpackSlotIsUsable(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var maid = maid(f);
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < 6; slot++) inventory.setInventorySlotContents(slot, named(Items.apple, slot + 1));
        var hand = named(Items.iron_sword, 1);
        var offhand = named(Items.stick, 1);
        var boots = named(Items.iron_boots, 1);
        inventory.setInventorySlotContents(MaidInventoryLayout.MAIN_HAND, hand);
        inventory.setInventorySlotContents(MaidInventoryLayout.OFF_HAND, offhand);
        inventory.setInventorySlotContents(MaidInventoryLayout.ARMOR_START, boots);
        var saved = new NBTTagCompound();
        maid.writeToNBT(saved);
        h.assertTrue(
            saved.getTagList("MaidInventory", 10)
                .tagCount() == 6 && !saved.hasKey("MaidBaubles"),
            "Legacy-shaped snapshot gained an unrelated empty bauble field");
        var restored = new EntityMaid(h.getWorld());
        f.owned.add(restored);
        restored.readFromNBT(saved);
        for (int slot = 0; slot < 36; slot++) h.assertTrue(
            slot < 6 ? ItemStack.areItemStacksEqual(
                inventory.getStackInSlot(slot),
                restored.getMaidInventory()
                    .getStackInSlot(slot))
                : restored.getMaidInventory()
                    .getStackInSlot(slot) == null,
            "Legacy backpack item moved or was replaced");
        h.assertTrue(
            ItemStack.areItemStacksEqual(hand, restored.getHeldItem())
                && ItemStack.areItemStacksEqual(offhand, restored.getOffhandItem())
                && ItemStack.areItemStacksEqual(boots, restored.getEquipmentInSlot(1)),
            "Legacy equipment slot moved");
        inventory.setInventorySlotContents(MaidInventoryLayout.MAIN_HAND, null);
        inventory.setInventorySlotContents(MaidInventoryLayout.OFF_HAND, null);
        inventory.setInventorySlotContents(35, new ItemStack(Items.arrow, 7));
        h.assertTrue(MaidBowAttack.modernArrowSlot(maid) == 35, "Arrow lookup ignored new backpack rows");
        var menu = new EmptyBackpackContainer(f.owner.inventory, maid);
        int[] rowY = { 37, 59, 82, 100, 123, 141 };
        h.assertTrue(menu.inventorySlots.size() == 78, "Full backpack menu has an incorrect slot count");
        for (int slot = 0; slot < 36; slot++) {
            var actual = menu.getSlot(42 + slot);
            h.assertTrue(
                actual.getSlotIndex() == slot && actual.xDisplayPosition == 143 + 18 * (slot % 6)
                    && actual.yDisplayPosition == rowY[slot / 6],
                "Full backpack slot overlaps or maps to another item");
        }
        menu.onContainerClosed(f.owner);
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "inventory_expansion")
    public static void baubleTiersAndAllThirtyItemsSurviveNativeReload(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var maid = maid(f);
        int[] favor = { 0, 64, 192, 384 };
        int[] slots = { 10, 10, 20, 30 };
        for (int tier = 0; tier < favor.length; tier++) {
            maid.setFavorability(favor[tier]);
            var menu = new BaubleContainer(f.owner.inventory, maid);
            h.assertTrue(menu.inventorySlots.size() == 42 + slots[tier], "Bauble tier disagrees with favorability");
            h.assertTrue(
                menu.getSlot(42)
                    .isItemValid(new ItemStack(PilotMod.INVENTORY_BAUBLE))
                    && !menu.getSlot(42)
                        .isItemValid(new ItemStack(Items.gold_nugget)),
                "Unknown item admitted as a maid bauble");
            menu.onContainerClosed(f.owner);
        }
        for (int slot = 0; slot < 30; slot++) maid.getMaidBaubleInventory()
            .setInventorySlotContents(slot, named(PilotMod.INVENTORY_BAUBLE, 1));
        maid.setFavorability(0);
        var saved = new NBTTagCompound();
        maid.writeToNBT(saved);
        var restored = new EntityMaid(h.getWorld());
        f.owned.add(restored);
        restored.readFromNBT(saved);
        h.assertTrue(
            saved.getTagList("MaidBaubles", 10)
                .tagCount() == 30,
            "Locked rows were removed from save");
        for (int slot = 0; slot < 30; slot++) h.assertTrue(
            ItemStack.areItemStacksEqual(
                maid.getMaidBaubleInventory()
                    .getStackInSlot(slot),
                restored.getMaidBaubleInventory()
                    .getStackInSlot(slot)),
            "Bauble item or nested tag changed during native reload");
        for (int slot = 0; slot < 30; slot++) restored.getMaidBaubleInventory()
            .setInventorySlotContents(slot, null);
        restored.writeToNBT(saved);
        h.assertTrue(
            saved.hasKey("MaidBaubles", 9) && saved.getTagList("MaidBaubles", 10)
                .tagCount() == 0,
            "Removing the last bauble changed the persisted field presence");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "inventory_expansion")
    public static void duplicateAndInvalidSlotsRetainTheirOriginalPayloads(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var maid = maid(f);
        var inventory = maid.getMaidInventory();
        var entries = new NBTTagList();
        var first = ProtectedItemCodec.encode(named(Items.apple, 7));
        first.setByte("Slot", (byte) 35);
        entries.appendTag(first);
        entries.appendTag(first.copy());
        var outside = (NBTTagCompound) first.copy();
        outside.setByte("Slot", (byte) 127);
        entries.appendTag(outside);
        inventory.readBackpack(entries);
        h.assertTrue(
            inventory.getStackInSlot(35).stackSize == 7 && inventory.recovery()
                .size() == 2,
            "Duplicate or invalid backpack entry overwritten or discarded");
        var saved = new NBTTagCompound();
        maid.writeToNBT(saved);
        var baubles = new NBTTagList();
        var equipped = ProtectedItemCodec.encode(named(PilotMod.INVENTORY_BAUBLE, 1));
        equipped.setByte("Slot", (byte) 29);
        baubles.appendTag(equipped);
        baubles.appendTag(equipped.copy());
        var invalid = (NBTTagCompound) equipped.copy();
        invalid.setByte("Slot", (byte) 200);
        baubles.appendTag(invalid);
        saved.setTag("MaidBaubles", baubles);
        maid.readFromNBT(saved);
        h.assertTrue(
            maid.getMaidBaubleInventory()
                .getStackInSlot(29)
                .getItem() == PilotMod.INVENTORY_BAUBLE
                && inventory.recovery()
                    .size() == 4,
            "Invalid bauble slot displaced an item or lost its payload");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "inventory_expansion")
    public static void equippedBaubleEntersOneDeathReceiptAndStaysOutOfFilm(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var maid = maid(f);
        var stack = named(PilotMod.INVENTORY_BAUBLE, 1);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(29, stack);
        maid.setHealth(0);
        maid.onDeath(DamageSource.generic);
        var token = f.ledger()
            .deathToken(maid.getUniqueID());
        h.assertTrue(
            token != null && maid.getMaidBaubleInventory()
                .getStackInSlot(29) == null,
            "Death receipt did not clear the exact equipped bauble");
        var material = f.ledger()
            .reserveTombstone(token);
        h.assertTrue(material != null, "Death material unavailable");
        int copies = 0;
        for (int index = 0; index < material.tagCount(); index++) {
            var decoded = ProtectedItemCodec.decode(material.getCompoundTagAt(index));
            if (decoded != null && decoded.getItem() == PilotMod.INVENTORY_BAUBLE) {
                h.assertTrue(ItemStack.areItemStacksEqual(stack, decoded), "Death receipt changed bauble NBT");
                copies += decoded.stackSize;
            }
        }
        h.assertTrue(copies == 1, "Bauble missing or duplicated in death materials");
        f.ledger()
            .finishTombstone(token, material, new NBTTagList());
        var film = f.ledger()
            .reserve(token, MaidLifecycleData.Kind.FILM);
        h.assertTrue(film != null && !film.hasKey("MaidBaubles"), "Film retained a second bauble inventory");
        f.ledger()
            .rollback(token);
        MaidDeathService.preserve(maid);
        h.assertTrue(
            f.ledger()
                .tombstoneEmpty(token),
            "Death retry duplicated bauble materials");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 160, batch = "inventory_expansion")
    public static void fiveRealBackpackProvidersReceiveWorldPickupBeforeMaidSlots(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var maid = maid(f);
        var provider = CompatServices.backpacks();
        String[] classes = { "de.eydamos.backpack.item.ItemBackpackBase",
            "com.darkona.adventurebackpack.item.ItemAdventureBackpack", "forestry.storage.items.ItemBackpack",
            "com.rwtema.extrautils.item.ItemGoldenBag", "mrtjp.projectred.exploration.ItemBackpack" };
        for (String type : classes) {
            var bag = actualBackpack(type);
            var before = bag.copy();
            var input = new ItemStack(Items.iron_ingot, 17);
            h.assertTrue(provider.isSupportedBackpack(bag), "Real backpack provider not recognized: " + type);
            h.assertTrue(
                provider.insertIntoBackpack(bag, input, true) == null && ItemStack.areItemStacksEqual(before, bag)
                    && input.stackSize == 17,
                "Simulation changed the real backpack or its input: " + type);
            var inventory = maid.getMaidInventory();
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                inventory.setInventorySlotContents(slot, null);
            inventory.setInventorySlotContents(0, bag);
            maid.setPickupItems(true);
            var drop = new EntityItem(h.getWorld(), maid.posX, maid.posY, maid.posZ, input.copy());
            drop.delayBeforeCanPickup = 0;
            f.owned.add(drop);
            h.assertTrue(
                h.getWorld()
                    .spawnEntityInWorld(drop),
                "Pickup source could not register");
            h.assertTrue(maid.pickupItem(drop, false), "Actual world pickup failed: " + type);
            var contents = new ArrayList<ItemStack>();
            h.assertTrue(
                provider.forEachStoredStack(inventory.getStackInSlot(0), contents::add),
                "Real backpack contents unavailable: " + type);
            h.assertTrue(
                count(contents, Items.iron_ingot) == 17 && drop.isDead && inventory.getStackInSlot(1) == null,
                "Pickup did not prioritize actual backpack contents: " + type);
            var another = before.copy();
            h.assertTrue(
                inventory.insertAvailable(another, false) == null && inventory.getStackInSlot(1) != null
                    && inventory.getStackInSlot(1)
                        .getItem() == bag.getItem(),
                "Backpack inserted into another backpack: " + type);
        }
        h.succeed();
    }

    private static EntityMaid maid(LifecycleConservationTests.Fixture f) {
        var maid = new EntityMaid(f.helper.getWorld());
        f.owned.add(maid);
        f.helper.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Maid owner binding failed");
        f.helper.spawnEntity(maid, 6.5, 1, 4.5);
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setPickup(false);
        return maid;
    }

    private static ItemStack actualBackpack(String className) {
        try {
            var type = Class.forName(className, false, InventoryExpansionTests.class.getClassLoader());
            for (Object entry : Item.itemRegistry) {
                if (!(entry instanceof Item item) || !type.isInstance(item)) continue;
                var stack = new ItemStack(item);
                var candidate = stack.copy();
                if (CompatServices.backpacks()
                    .isSupportedBackpack(candidate)
                    && CompatServices.backpacks()
                        .insertIntoBackpack(candidate, new ItemStack(Items.iron_ingot), true) == null) {
                    if (!ItemStack.areItemStacksEqual(stack, candidate)) throw new AssertionError(
                        "Backpack selection simulation changed the original item: " + className);
                    return stack;
                }
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Actual backpack provider class unavailable: " + className, failure);
        }
        throw new IllegalStateException("Registered backpack accepting iron ingots unavailable: " + className);
    }

    private static ItemStack named(Item item, int count) {
        var stack = new ItemStack(item, count);
        var tag = new NBTTagCompound();
        tag.setString("name", "original nested item");
        var nested = new NBTTagCompound();
        nested.setIntArray("retained", new int[] { 3, 1, 4, 1, 5 });
        tag.setTag("deep", nested);
        stack.setTagCompound(tag);
        return stack;
    }

    private static int count(List<ItemStack> contents, Item item) {
        int total = 0;
        for (var stack : contents) if (stack.getItem() == item) total += stack.stackSize;
        return total;
    }

    public static final class BaubleItem extends Item implements IMaidBauble {

        BaubleItem() {
            setUnlocalizedName("qa_inventory_bauble");
            setMaxStackSize(1);
        }
    }
}
