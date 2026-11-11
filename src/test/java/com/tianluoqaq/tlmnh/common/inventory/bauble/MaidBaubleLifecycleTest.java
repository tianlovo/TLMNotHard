package com.tianluoqaq.tlmnh.common.inventory.bauble;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidFilmData;

public class MaidBaubleLifecycleTest {

    @Test
    public void clearedDeathImagePreservesTheAbsenceOfNewKeysInOldReceipts() throws Exception {
        var old = new NBTTagCompound();
        old.setTag("MaidInventory", new NBTTagList());
        assertFalse(cleared(old).hasKey("MaidBaubles"));
        var withBaubles = new NBTTagCompound();
        var items = new NBTTagList();
        items.appendTag(new NBTTagCompound());
        withBaubles.setTag("MaidBaubles", items);
        var result = cleared(withBaubles);
        assertTrue(result.hasKey("MaidBaubles", 9));
        assertEquals(
            0,
            result.getTagList("MaidBaubles", 10)
                .tagCount());
        assertEquals(
            1,
            withBaubles.getTagList("MaidBaubles", 10)
                .tagCount());
    }

    @Test
    public void filmKeepsIdentityWhileBaublesRemainInTheSingleDeathDelivery() {
        var source = new NBTTagCompound();
        source.setString("OwnerUUID", "owner");
        source.setString("ModelId", "winefox");
        source.setTag("MaidBaubles", new NBTTagList());
        var identity = MaidFilmData.identity(source);
        assertEquals("owner", identity.getString("OwnerUUID"));
        assertEquals("winefox", identity.getString("ModelId"));
        assertFalse(identity.hasKey("MaidBaubles"));
        assertTrue(source.hasKey("MaidBaubles"));
    }

    private static NBTTagCompound cleared(NBTTagCompound source) throws Exception {
        var type = Class.forName("com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathIngress");
        var method = type.getDeclaredMethod("cleared", NBTTagCompound.class);
        method.setAccessible(true);
        return (NBTTagCompound) method.invoke(null, source);
    }
}
