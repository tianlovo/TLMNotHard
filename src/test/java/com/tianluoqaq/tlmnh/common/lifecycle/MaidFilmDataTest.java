package com.tianluoqaq.tlmnh.common.lifecycle;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class MaidFilmDataTest {

    @Test
    public void identityRetainsOwnerModelAndUnknownExtensionButNeverInventoryOrDeadState() {
        var source = new NBTTagCompound();
        source.setString("OwnerUUID", "owner");
        source.setString("ModelId", "geckolib:winefox_hanfu");
        source.setString("CustomName", "酒狐");
        source.setString("Equipment", "equipment");
        source.setString("MaidInventory", "backpack");
        source.setString("TLMNHOffhand", "offhand");
        source.setInteger("MaidExperience", 155);
        source.setInteger("Health", 0);
        source.setInteger("DeathTime", 20);
        source.setBoolean("Sitting", true);
        var extension = new NBTTagCompound();
        extension.setString("future", "retained");
        source.setTag("TLMNH", extension);
        var film = MaidFilmData.identity(source);
        assertEquals("owner", film.getString("OwnerUUID"));
        assertEquals("geckolib:winefox_hanfu", film.getString("ModelId"));
        assertEquals("酒狐", film.getString("CustomName"));
        assertEquals(
            "retained",
            film.getCompoundTag("TLMNH")
                .getString("future"));
        for (var key : new String[] { "Equipment", "MaidInventory", "TLMNHOffhand", "MaidExperience", "Health",
            "DeathTime" }) assertFalse(key, film.hasKey(key));
        assertFalse(film.getBoolean("Sitting"));
        assertTrue(source.hasKey("Equipment"));
        assertTrue(source.getBoolean("Sitting"));
    }

    @Test
    public void shrineHealthBoundaryUsesActualMaximumAndRejectsNonFiniteInput() {
        assertFalse(MaidLifecycleService.hasHealth(10.999F, 20, false));
        assertTrue(MaidLifecycleService.hasHealth(11, 20, false));
        assertTrue(MaidLifecycleService.hasHealth(41, 80, false));
        assertFalse(MaidLifecycleService.hasHealth(40, 80, false));
        assertTrue(MaidLifecycleService.hasHealth(1, 20, true));
        assertFalse(MaidLifecycleService.hasHealth(Float.NaN, 20, true));
        assertFalse(MaidLifecycleService.hasHealth(20, Float.POSITIVE_INFINITY, false));
    }
}
