package com.tianluoqaq.tlmnh.compat;

import static org.junit.Assert.*;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

/** 纯合并与按条件取用逻辑的快速回归，注册物品的存档协议场景由 HorizonQA 执行 */
public class BackpackAdaptersNbtTest {

    @Test
    public void simulationReportsCapacityWithoutMutatingSourceOrSlots() {
        var item = new Item();
        var existing = new ItemStack(item, 60);
        var input = new ItemStack(item, 10);
        var slots = new ItemStack[] { existing, null };
        var result = BackpackSlotMergeHelper.mergeIntoArray(slots, 2, 64, input, true);
        assertNull(result.remaining);
        assertTrue(result.changed);
        assertSame(existing, slots[0]);
        assertEquals(60, slots[0].stackSize);
        assertNull(slots[1]);
        assertEquals(10, input.stackSize);
    }

    @Test
    public void mergesMatchingTagsWithinDeclaredStorageBoundary() {
        var item = new Item();
        var occupied = new ItemStack(item, 60);
        var input = new ItemStack(item, 10);
        var tag = new NBTTagCompound();
        tag.setString("different", "preserved");
        occupied.setTagCompound(tag);
        var slots = new ItemStack[] { occupied, null, null };
        var result = BackpackSlotMergeHelper.mergeIntoArray(slots, 2, 64, input, false);
        assertNull(result.remaining);
        assertTrue(result.changed);
        assertSame(occupied, slots[0]);
        assertEquals(60, slots[0].stackSize);
        assertEquals(10, slots[1].stackSize);
        assertNull(slots[2]);
        var full = BackpackSlotMergeHelper.mergeIntoArray(slots, 1, 64, input, false);
        assertNotNull(full.remaining);
        assertEquals(10, full.remaining.stackSize);
        assertFalse(full.changed);
    }

    @Test
    public void extractSelectsHighestScoreOrMergesMatchingStacksWithoutSimulateMutation() {
        var seedItem = new Item();
        var weak = new ItemStack(seedItem, 2);
        var weakTag = new NBTTagCompound();
        weakTag.setInteger("score", 3);
        weak.setTagCompound(weakTag);

        var strong = new ItemStack(seedItem, 1);
        var strongTag = new NBTTagCompound();
        strongTag.setInteger("score", 31);
        strong.setTagCompound(strongTag);

        var slots = new ItemStack[] { weak, strong };
        var preview = BackpackSlotMergeHelper.extractFromArray(
            slots,
            2,
            s -> s.getItem() == seedItem,
            s -> s.getTagCompound()
                .getInteger("score"),
            1,
            true);
        assertNotNull(preview.extracted);
        assertEquals(
            31,
            preview.extracted.getTagCompound()
                .getInteger("score"));
        assertSame(strong, slots[1]);
        assertEquals(1, slots[1].stackSize);

        var taken = BackpackSlotMergeHelper.extractFromArray(
            slots,
            2,
            s -> s.getItem() == seedItem,
            s -> s.getTagCompound()
                .getInteger("score"),
            1,
            false);
        assertNotNull(taken.extracted);
        assertEquals(
            31,
            taken.extracted.getTagCompound()
                .getInteger("score"));
        assertNull(slots[1]);
        assertEquals(2, slots[0].stackSize);

        var stickItem = new Item();
        var splitSlots = new ItemStack[] { new ItemStack(stickItem, 1), new ItemStack(stickItem, 1) };
        var mergedSticks = BackpackSlotMergeHelper
            .extractFromArray(splitSlots, 2, s -> s.getItem() == stickItem, null, 2, false);
        assertNotNull(mergedSticks.extracted);
        assertEquals(2, mergedSticks.extracted.stackSize);
        assertNull(splitSlots[0]);
        assertNull(splitSlots[1]);
    }
}
