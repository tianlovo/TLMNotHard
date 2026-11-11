package com.tianluoqaq.tlmnh.common.inventory;

import static org.junit.Assert.*;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleRegistry;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidMainLayout;

public class MaidInventoryExpansionTest {

    @Test
    public void backpackEquipmentAndMenuRangesRemainDisjoint() {
        assertEquals(36, MaidInventoryLayout.BACKPACK_SIZE);
        assertEquals(36, MaidInventoryLayout.MAIN_HAND);
        assertEquals(37, MaidInventoryLayout.OFF_HAND);
        assertEquals(38, MaidInventoryLayout.ARMOR_START);
        assertEquals(42, MaidInventoryLayout.TOTAL_SIZE);
        assertEquals(78, MaidMainLayout.BACKPACK_END);
        int[] rowY = { 37, 59, 82, 100, 123, 141 };
        for (int row = 0; row < rowY.length; row++) assertEquals(rowY[row], MaidMainLayout.backpackY(row));
    }

    @Test
    public void accessoryTiersRequireAnExplicitMaidInterfaceOrRegistration() {
        assertEquals(10, MaidBaubleLayout.unlockedSlots(0));
        assertEquals(10, MaidBaubleLayout.unlockedSlots(1));
        assertEquals(20, MaidBaubleLayout.unlockedSlots(2));
        assertEquals(30, MaidBaubleLayout.unlockedSlots(3));
        assertNull(MaidBaubleRegistry.getBauble(new ItemStack(new Item())));
        assertNotNull(MaidBaubleRegistry.getBauble(new ItemStack(new TestBauble())));
        var item = new Item();
        var adapter = new IMaidBauble() {};
        MaidBaubleRegistry.register(item, adapter);
        assertSame(adapter, MaidBaubleRegistry.getBauble(new ItemStack(item)));
    }

    private static final class TestBauble extends Item implements IMaidBauble {
    }
}
