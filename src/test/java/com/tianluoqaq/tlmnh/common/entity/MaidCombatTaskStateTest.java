package com.tianluoqaq.tlmnh.common.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class MaidCombatTaskStateTest {

    @Test
    public void oldRangedSaveHasOneProductionIdentityAndRetainsReferenceAndPreference() {
        var input = new NBTTagCompound();
        input.setString("MaidTask", MaidTask.RANGED_ATTACK.getId());
        var state = new MaidCombatTaskState();
        String raw = state.load(input);
        assertEquals(MaidTask.RANGED_ATTACK, MaidTask.fromId(raw));
        assertEquals(MaidTask.ATTACK, state.task(raw, true));
        assertEquals(MaidTask.RANGED_ATTACK, state.preferredTask());
        assertTrue(state.preferencePending());
        state.consumePreference();
        state.save(input, raw, true);
        assertEquals(MaidTask.ATTACK.getId(), input.getString("MaidTask"));
        var restored = new MaidCombatTaskState();
        String canonical = restored.load(input);
        assertEquals(MaidTask.ATTACK, restored.task(canonical, true));
        assertEquals(MaidTask.RANGED_ATTACK, restored.task(canonical, false));
        assertFalse(restored.preferencePending());
    }

    @Test
    public void unknownTaskAndFutureMigrationSurviveRepeatedSaveWithoutProjectionLoss() {
        var input = new NBTTagCompound();
        input.setString("MaidTask", "future_provider:future_task");
        var future = new NBTTagCompound();
        future.setInteger("Version", 99);
        future.setString("PrivateValue", "retain");
        var extension = new NBTTagCompound();
        extension.setTag("CombatTask", future);
        extension.setLong("Unrelated", 123);
        input.setTag("TLMNH", extension);
        var state = new MaidCombatTaskState();
        String raw = state.load(input);
        assertFalse(state.available());
        assertEquals(MaidTask.IDLE, state.task(raw, true));
        state.save(input, raw, true);
        var next = new MaidCombatTaskState();
        next.save(input, next.load(input), true);
        assertEquals("future_provider:future_task", input.getString("MaidTask"));
        assertEquals(
            future,
            input.getCompoundTag("TLMNH")
                .getCompoundTag("CombatTask"));
        assertEquals(
            123L,
            input.getCompoundTag("TLMNH")
                .getLong("Unrelated"));
    }

    @Test
    public void futureStateDoesNotOverwriteKnownRawTaskAndMalformedTaskKeepsItsType() {
        var input = new NBTTagCompound();
        input.setString("MaidTask", MaidTask.RANGED_ATTACK.getId());
        var extension = new NBTTagCompound();
        extension.setString("CombatTask", "future-format");
        input.setTag("TLMNH", extension);
        var state = new MaidCombatTaskState();
        state.save(input, state.load(input), true);
        assertEquals(MaidTask.RANGED_ATTACK.getId(), input.getString("MaidTask"));
        assertEquals(
            "future-format",
            input.getCompoundTag("TLMNH")
                .getString("CombatTask"));
        assertFalse(state.canSelect());
        state.selected(MaidTask.ATTACK);
        state.save(input, MaidTask.ATTACK.getId(), true);
        assertEquals(MaidTask.RANGED_ATTACK.getId(), input.getString("MaidTask"));
        input = new NBTTagCompound();
        input.setInteger("MaidTask", 47);
        state = new MaidCombatTaskState();
        state.save(input, state.load(input), true);
        assertTrue(input.hasKey("MaidTask", 3));
        assertEquals(47, input.getInteger("MaidTask"));
        assertFalse(state.available());
    }

    @Test
    public void freshReferenceSaveKeepsOldIdentityWithoutIntroducingProductionMigration() {
        var input = new NBTTagCompound();
        input.setString("MaidTask", MaidTask.RANGED_ATTACK.getId());
        var state = new MaidCombatTaskState();
        String raw = state.load(input);
        state.save(input, raw, false);
        assertEquals(MaidTask.RANGED_ATTACK.getId(), input.getString("MaidTask"));
        assertFalse(
            input.getCompoundTag("TLMNH")
                .hasKey("CombatTask"));
    }

    @Test
    public void referenceProjectionOfMigratedProductionInputDoesNotRewriteItsRawFragments() {
        var input = new NBTTagCompound();
        input.setString("MaidTask", MaidTask.RANGED_ATTACK.getId());
        var state = new MaidCombatTaskState();
        state.save(input, state.load(input), true);
        var expected = (NBTTagCompound) input.copy();
        var reference = new MaidCombatTaskState();
        String raw = reference.load(input);
        assertEquals(MaidTask.RANGED_ATTACK, reference.task(raw, false));
        reference.save(input, raw, false);
        assertEquals(expected, input);
    }

    @Test
    public void workTasksRoundTripWithoutOverwritingCombatPreference() {
        var state = new MaidCombatTaskState();
        state.selected(MaidTask.RANGED_ATTACK);
        assertEquals(MaidTask.RANGED_ATTACK, state.preferredTask());
        state.consumePreference();

        state.selected(MaidTask.MAINTENANCE_HATCH);
        assertEquals(MaidTask.RANGED_ATTACK, state.preferredTask());
        assertFalse(state.preferencePending());

        var saved = new NBTTagCompound();
        state.save(saved, MaidTask.MAINTENANCE_HATCH.getId(), true);
        assertEquals(MaidTask.MAINTENANCE_HATCH.getId(), saved.getString("MaidTask"));

        var reloaded = new MaidCombatTaskState();
        String loadedRaw = reloaded.load(saved);
        assertEquals(MaidTask.MAINTENANCE_HATCH, reloaded.task(loadedRaw, true));
        assertEquals(MaidTask.MAINTENANCE_HATCH, reloaded.task(loadedRaw, false));
        assertEquals(MaidTask.RANGED_ATTACK, reloaded.preferredTask());

        reloaded.selected(MaidTask.CROPSNH_BREEDING);
        var savedBreeding = new NBTTagCompound();
        reloaded.save(savedBreeding, MaidTask.CROPSNH_BREEDING.getId(), true);
        var reloadedBreeding = new MaidCombatTaskState();
        String breedingRaw = reloadedBreeding.load(savedBreeding);
        assertEquals(MaidTask.CROPSNH_BREEDING, reloadedBreeding.task(breedingRaw, true));
        assertEquals(MaidTask.CROPSNH_BREEDING, reloadedBreeding.task(breedingRaw, false));
        assertEquals(MaidTask.RANGED_ATTACK, reloadedBreeding.preferredTask());
    }
}
