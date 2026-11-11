package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.compat.adventurebackpack.common.inventory.AdventureBackpackContainer;
import com.tianluoqaq.tlmnh.compat.extrautilities.common.inventory.GoldenBagContainer;
import com.tianluoqaq.tlmnh.compat.forestry.common.inventory.ForestryBackpackContainer;
import com.tianluoqaq.tlmnh.compat.projredexploration.common.inventory.ProjectRedBackpackContainer;

/** 校验嵌套背包协议（Forestry 基 36、GoldenBag items_N、ProjectRed index、AdventureBackpack 48 槽上限与 simulate 纯净性） */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class BackpackAdaptersNbtTests {

    private static final Item bagItem = Items.leather;
    private static final Item ingotItem = Items.iron_ingot;
    private static final Item cobbleItem = Items.stick;

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "backpack_protocols")
    public static void forestryUsesBase36SlotKeysRespectsLockedModeAndSimulatePurity(GameTestHelper h) {
        ItemStack bag = new ItemStack(bagItem, 1, 0);
        ItemStack simLeft = ForestryBackpackContainer.insertNbt(bag, 15, new ItemStack(ingotItem, 40), true);
        assertNull(simLeft);
        assertNull("simulate=true must not initialize stackTagCompound", bag.getTagCompound());

        // 填充第 11 槽（基 36 键应为 "b"）验证基 36 读写与非零 UID
        for (int i = 0; i < 11; i++) {
            assertNull(ForestryBackpackContainer.insertNbt(bag, 15, new ItemStack(cobbleItem, 64), false));
        }
        assertNull(ForestryBackpackContainer.insertNbt(bag, 15, new ItemStack(ingotItem, 25), false));
        NBTTagCompound root = bag.getTagCompound();
        assertNotNull(root);
        assertTrue(root.getInteger("UID") != 0);
        NBTTagCompound slots = root.getCompoundTag("Slots");
        assertTrue("Slot 11 must be stored under base-36 key 'b'", slots.hasKey("b", 10));

        // 锁定模式（damage == 1）拒绝自动存入，但仍允许只读遍历供热锭免烫扫描
        ItemStack lockedBag = bag.copy();
        lockedBag.setItemDamage(1);
        ItemStack rejected = ForestryBackpackContainer.insertNbt(lockedBag, 15, new ItemStack(ingotItem, 10), false);
        assertNotNull(rejected);
        assertEquals(10, rejected.stackSize);

        List<ItemStack> visited = new ArrayList<>();
        ForestryBackpackContainer.forEachStoredNbt(lockedBag, 15, visited::add);
        assertEquals(12, visited.size());
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "backpack_protocols")
    public static void goldenBagStoresRootSlotCompoundsAndMergesExistingStacks(GameTestHelper h) {
        ItemStack bag = new ItemStack(bagItem);
        assertNull(GoldenBagContainer.insertNbt(bag, new ItemStack(ingotItem, 50), false));
        assertNull(GoldenBagContainer.insertNbt(bag, new ItemStack(ingotItem, 30), false));

        NBTTagCompound root = bag.getTagCompound();
        assertNotNull(root);
        assertTrue(root.hasKey("items_0", 10));
        assertTrue(root.hasKey("items_1", 10));
        assertEquals(64, ItemStack.loadItemStackFromNBT(root.getCompoundTag("items_0")).stackSize);
        assertEquals(16, ItemStack.loadItemStackFromNBT(root.getCompoundTag("items_1")).stackSize);
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "backpack_protocols")
    public static void projectRedWritesBagInvWithIntegerIndexAnd27SlotCap(GameTestHelper h) {
        ItemStack bag = new ItemStack(bagItem);
        for (int i = 0; i < 27; i++) {
            assertNull(ProjectRedBackpackContainer.insertNbt(bag, new ItemStack(cobbleItem, 64), false));
        }
        ItemStack overflow = ProjectRedBackpackContainer.insertNbt(bag, new ItemStack(ingotItem, 5), false);
        assertNotNull(overflow);
        assertEquals(5, overflow.stackSize);

        NBTTagCompound bagInv = bag.getTagCompound()
            .getCompoundTag("baginv");
        assertEquals(27, bagInv.getInteger("itemsCount"));
        NBTTagList items = bagInv.getTagList("items", 10);
        assertEquals(27, items.tagCount());
        assertEquals(
            26,
            items.getCompoundTagAt(26)
                .getInteger("index"));
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "backpack_protocols")
    public static void adventureBackpackCapsGeneralStorageAt48AndPreservesToolSlots(GameTestHelper h) {
        ItemStack bag = new ItemStack(bagItem);
        // 先放入 1 个槽位 48 的模拟工具条目，确认后续普通存入不会覆盖 48..53
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound wearable = new NBTTagCompound();
        NBTTagList invList = new NBTTagList();
        NBTTagCompound toolEntry = new NBTTagCompound();
        new ItemStack(ingotItem, 1).writeToNBT(toolEntry);
        toolEntry.setByte("Slot", (byte) 48);
        invList.appendTag(toolEntry);
        wearable.setTag("inventory", invList);
        root.setTag("wearableData", wearable);
        bag.setTagCompound(root);

        for (int i = 0; i < 48; i++) {
            assertNull(AdventureBackpackContainer.insertNbt(bag, new ItemStack(cobbleItem, 64), false));
        }
        ItemStack overflow = AdventureBackpackContainer.insertNbt(bag, new ItemStack(cobbleItem, 8), false);
        assertNotNull(overflow);
        assertEquals(8, overflow.stackSize);

        NBTTagList afterList = bag.getTagCompound()
            .getCompoundTag("wearableData")
            .getTagList("inventory", 10);
        assertEquals("48 storage slots + preserved slot 48 tool entry", 49, afterList.tagCount());
        h.succeed();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertTrue(boolean condition) {
        assertTrue(condition, "Expected true");
    }

    private static void assertTrue(String message, boolean condition) {
        assertTrue(condition, message);
    }

    private static void assertNull(Object actual) {
        assertTrue(actual == null, "Expected null");
    }

    private static void assertNull(String message, Object actual) {
        assertTrue(actual == null, message);
    }

    private static void assertNotNull(Object actual) {
        assertTrue(actual != null, "Expected non-null");
    }

    private static void assertEquals(int expected, int actual) {
        assertEquals("Integer mismatch", expected, actual);
    }

    private static void assertEquals(String message, int expected, int actual) {
        assertTrue(expected == actual, message + " expected=" + expected + " actual=" + actual);
    }
}
