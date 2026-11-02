package com.tianluoqaq.tlmnh.common.lifecycle;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class MaidLifecycleDataTest {

    @Test
    public void copiedInitialCredentialCannotRedeemTwiceEvenAfterSaveReload() {
        var owner = UUID.randomUUID();
        var data = new MaidLifecycleData("fixture");
        var token = data.initialToken(owner);
        assertEquals(token, data.initialToken(owner));
        assertNotEquals(token, data.initialToken(UUID.randomUUID()));
        assertNotNull(data.reserve(token, MaidLifecycleData.Kind.INITIAL));
        assertNull(data.reserve(token, MaidLifecycleData.Kind.INITIAL));
        data.rollback(token);
        assertNotNull(data.reserve(token, MaidLifecycleData.Kind.INITIAL));
        data.commit(token);
        var restored = roundtrip(data);
        assertEquals(owner, restored.owner(token, MaidLifecycleData.Kind.INITIAL));
        assertTrue(restored.delivered(token));
        assertNull(restored.reserve(token, MaidLifecycleData.Kind.INITIAL));
        assertNull(restored.reserve(token, MaidLifecycleData.Kind.FILM));
        assertEquals(token, restored.initialToken(owner));
    }

    @Test
    public void reservedFailureIsRetryableAndSnapshotCannotMutateCanonicalFilm() {
        var owner = UUID.randomUUID();
        var identity = new NBTTagCompound();
        identity.setString("OwnerUUID", owner.toString());
        identity.setString("ModelId", "geckolib:winefox_hanfu");
        var data = new MaidLifecycleData("fixture");
        var token = data.newFilm(owner, UUID.randomUUID(), identity);
        identity.setString("ModelId", "caller-mutation");
        var attempt = data.reserve(token, MaidLifecycleData.Kind.FILM);
        attempt.setString("ModelId", "attempt-mutation");
        data.rollback(token);
        var restored = roundtrip(data);
        assertEquals(
            "geckolib:winefox_hanfu",
            restored.reserve(token, MaidLifecycleData.Kind.FILM)
                .getString("ModelId"));
    }

    @Test
    public void tombstoneClaimAndFilmRedemptionAreIndependentSingleUseRights() {
        var owner = UUID.randomUUID();
        var dead = UUID.randomUUID();
        var data = new MaidLifecycleData("fixture");
        var inventory = new NBTTagList();
        var stack = new NBTTagCompound();
        stack.setString("fixture-stack", "only-one-copy");
        inventory.appendTag(stack);
        var token = data.depositDeath(owner, dead, new NBTTagCompound(), inventory, 0, 10, 5, 10, "winefox");
        assertEquals(
            token,
            data.depositDeath(owner, dead, new NBTTagCompound(), new NBTTagList(), 0, 0, 0, 0, "ignored"));
        assertEquals(
            1,
            data.pendingTombstones(0)
                .size());
        assertTrue(
            data.pendingTombstones(1)
                .isEmpty());
        data.markPublished(token);
        var restored = roundtrip(data);
        assertNotNull(restored.reserveTombstone(token));
        assertNull(restored.reserveTombstone(token));
        assertNull(restored.reserve(token, MaidLifecycleData.Kind.FILM));
        restored.finishTombstone(token, new NBTTagList());
        assertTrue(restored.tombstoneEmpty(token));
        assertNull(restored.reserveTombstone(token));
        assertNotNull(restored.reserve(token, MaidLifecycleData.Kind.FILM));
        restored.commit(token);
        assertNull(roundtrip(restored).reserve(token, MaidLifecycleData.Kind.FILM));
    }

    @Test
    public void unknownSchemaCannotBeOverwrittenWithAnEmptyLedger() {
        var data = new MaidLifecycleData("fixture");
        var invalid = new NBTTagCompound();
        invalid.setInteger("Schema", 7);
        assertThrows(IllegalStateException.class, () -> data.readFromNBT(invalid));
        assertThrows(IllegalStateException.class, () -> data.writeToNBT(new NBTTagCompound()));
    }

    @Test
    public void failedShrineDeliverySurvivesSaveReloadAndCannotBeRetriedAfterConfirmation() {
        var data = new MaidLifecycleData("fixture");
        var stack = new NBTTagCompound();
        stack.setString("fixture-film", "original-credential");
        var id = data.queueReturnedFilm(stack, 7, 10, 20, 30);
        var attempt = data.reserveReturnedFilm(id);
        assertNotNull(attempt);
        assertNull(data.reserveReturnedFilm(id));
        attempt.setString("fixture-film", "callback-mutation");
        data.rollback(id);
        var restored = roundtrip(data);
        assertEquals(java.util.Collections.singletonList(id), restored.pendingReturnedFilms(7));
        assertEquals(
            "original-credential",
            restored.reserveReturnedFilm(id)
                .getString("fixture-film"));
        restored.finishReturnedFilm(id);
        assertTrue(
            roundtrip(restored).pendingReturnedFilms(7)
                .isEmpty());
        assertNull(restored.reserveReturnedFilm(id));
    }

    private static MaidLifecycleData roundtrip(MaidLifecycleData original) {
        var tag = new NBTTagCompound();
        original.writeToNBT(tag);
        var restored = new MaidLifecycleData("fixture");
        restored.readFromNBT(tag);
        return restored;
    }

    @Test
    public void craftedCredentialsAreIndependentFromEachOtherAndTheOncePerOwnerGiftAfterReload() {
        var owner = UUID.randomUUID();
        var data = new MaidLifecycleData("fixture");
        var first = data.acquiredToken(owner);
        var second = data.acquiredToken(owner);
        var gift = data.initialToken(owner);
        assertNotEquals(first, second);
        assertNotEquals(first, gift);
        assertNotEquals(second, gift);
        assertEquals(MaidLifecycleData.Kind.ACQUIRED, data.initialKind(first));
        assertNull(data.reserve(first, MaidLifecycleData.Kind.INITIAL));
        assertNotNull(data.reserve(first, MaidLifecycleData.Kind.ACQUIRED));
        assertNull(data.reserve(first, MaidLifecycleData.Kind.ACQUIRED));
        data.commit(first);
        data.markDelivered(second);
        var restored = roundtrip(data);
        assertNull(restored.reserve(first, MaidLifecycleData.Kind.ACQUIRED));
        assertNotNull(restored.reserve(second, MaidLifecycleData.Kind.ACQUIRED));
        restored.rollback(second);
        assertEquals(owner, restored.owner(second, MaidLifecycleData.Kind.ACQUIRED));
        assertEquals(gift, restored.initialToken(owner));
        assertFalse(restored.delivered(gift));
        assertNotNull(restored.reserve(gift, MaidLifecycleData.Kind.INITIAL));
    }

    @Test
    public void legacySchemaOneMigratesGiftAndFilmRightsBeforeAddingCraftedCredentials() {
        var owner = UUID.randomUUID();
        var old = new MaidLifecycleData("fixture");
        var gift = old.initialToken(owner);
        var identity = new NBTTagCompound();
        identity.setString("ModelId", "geckolib:winefox_hanfu");
        var film = old.newFilm(owner, UUID.randomUUID(), identity);
        var legacy = legacy(old, 1);
        var restored = new MaidLifecycleData("fixture");
        restored.readFromNBT(legacy);
        assertEquals(gift, restored.initialToken(owner));
        assertEquals(owner, restored.owner(film, MaidLifecycleData.Kind.FILM));
        assertNull(restored.initialKind(film));
        assertEquals(
            "geckolib:winefox_hanfu",
            restored.reserve(film, MaidLifecycleData.Kind.FILM)
                .getString("ModelId"));
        restored.rollback(film);
        var acquired = restored.acquiredToken(owner);
        var current = new NBTTagCompound();
        restored.writeToNBT(current);
        assertEquals(6, current.getInteger("Schema"));
        assertEquals(MaidLifecycleData.Kind.ACQUIRED, roundtrip(restored).initialKind(acquired));
    }

    @Test
    public void newerAcquisitionKindsCannotBeSmuggledIntoLegacySchema() {
        var source = new MaidLifecycleData("fixture");
        source.acquiredToken(UUID.randomUUID());
        var tag = new NBTTagCompound();
        source.writeToNBT(tag);
        tag.setInteger("Schema", 1);
        var restored = new MaidLifecycleData("fixture");
        assertThrows(IllegalStateException.class, () -> restored.readFromNBT(tag));
        assertThrows(IllegalStateException.class, () -> restored.writeToNBT(new NBTTagCompound()));
    }

    @Test
    public void legacySchemaTwoKeepsCraftedAndDeathRightsWithoutInventingExperience() {
        var owner = UUID.randomUUID();
        var data = new MaidLifecycleData("fixture");
        var acquired = data.acquiredToken(owner);
        var film = data
            .depositDeath(owner, UUID.randomUUID(), new NBTTagCompound(), new NBTTagList(), 7, 1, 5, 2, "old");
        var restored = new MaidLifecycleData("fixture");
        restored.readFromNBT(legacy(data, 2));
        assertEquals(MaidLifecycleData.Kind.ACQUIRED, restored.initialKind(acquired));
        assertEquals(owner, restored.owner(film, MaidLifecycleData.Kind.FILM));
        assertEquals(0, restored.remainingDeathExperience(film));
        assertTrue(
            restored.pendingDeathExperience(7, Long.MAX_VALUE)
                .isEmpty());
        assertEquals(0, roundtrip(restored).remainingDeathExperience(film));
    }

    @Test
    public void fullIntExperienceRemainderSurvivesInventoryAndFilmRedemptionIndependently() {
        var data = new MaidLifecycleData("fixture");
        var items = new NBTTagList();
        items.appendTag(new NBTTagCompound());
        var token = data.depositDeath(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new NBTTagCompound(),
            items,
            7,
            1,
            5,
            2,
            "xp",
            Integer.MAX_VALUE,
            40);
        data.markPublished(token);
        assertTrue(
            data.pendingDeathExperience(7, 39)
                .isEmpty());
        assertEquals(java.util.Collections.singletonList(token), data.pendingDeathExperience(7, 40));
        assertEquals(Integer.MAX_VALUE, data.reserveDeathExperience(token));
        assertEquals(0, data.reserveDeathExperience(token));
        assertNull(data.reserveTombstone(token));
        assertThrows(IllegalStateException.class, () -> data.finishDeathExperience(token, Integer.MAX_VALUE - 1, 11));
        assertEquals(Integer.MAX_VALUE, data.remainingDeathExperience(token));
        data.finishDeathExperience(token, Integer.MAX_VALUE, 11);
        var remaining = roundtrip(data);
        assertEquals(Integer.MAX_VALUE - 11, remaining.remainingDeathExperience(token));
        assertNotNull(remaining.reserveTombstone(token));
        remaining.finishTombstone(token, items, new NBTTagList());
        assertNotNull(remaining.reserve(token, MaidLifecycleData.Kind.FILM));
        remaining.commit(token);
        var afterRevive = roundtrip(remaining);
        assertEquals(Integer.MAX_VALUE - 11, afterRevive.reserveDeathExperience(token));
        afterRevive.finishDeathExperience(token, Integer.MAX_VALUE - 11, Integer.MAX_VALUE - 11);
        assertEquals(0, roundtrip(afterRevive).remainingDeathExperience(token));
        assertEquals(0, afterRevive.reserveDeathExperience(token));
    }

    @Test
    public void malformedExperienceCannotTurnIntoAnEmptyOverwrittenLedger() {
        var source = new MaidLifecycleData("fixture");
        source.depositDeath(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new NBTTagCompound(),
            new NBTTagList(),
            0,
            1,
            5,
            2,
            "xp",
            53,
            40);
        for (boolean wrongType : new boolean[] { false, true }) {
            var tag = new NBTTagCompound();
            source.writeToNBT(tag);
            var entry = tag.getTagList("Entries", 10)
                .getCompoundTagAt(0);
            if (wrongType) entry.setString("DeathExperience", "53");
            else entry.setInteger("DeathExperience", -1);
            var restored = new MaidLifecycleData("fixture");
            assertThrows(IllegalStateException.class, () -> restored.readFromNBT(tag));
            assertThrows(IllegalStateException.class, () -> restored.writeToNBT(new NBTTagCompound()));
        }
    }

    @Test
    public void legacySchemaThreeKeepsExperienceWithoutInventingHolds() {
        var data = new MaidLifecycleData("fixture");
        var token = data.depositDeath(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new NBTTagCompound(),
            new NBTTagList(),
            0,
            1,
            5,
            2,
            "xp",
            53,
            40);
        var restored = new MaidLifecycleData("fixture");
        restored.readFromNBT(legacy(data, 3));
        assertEquals(53, restored.remainingDeathExperience(token));
        assertFalse(restored.isTombstoneHeld(token));
        assertFalse(restored.isDeathExperienceHeld(token));
        assertEquals(java.util.Collections.singletonList(token), restored.pendingDeathExperience(0, 40));
    }

    @Test
    public void tombstoneHoldSurvivesReloadWithoutConsumingItemsExperienceOrFilm() {
        var data = new MaidLifecycleData("fixture");
        var items = new NBTTagList();
        var original = new NBTTagCompound();
        original.setString("Opaque", "original-nine-items");
        items.appendTag(original);
        var token = data.depositDeath(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new NBTTagCompound(),
            items,
            0,
            1,
            5,
            2,
            "held",
            53,
            40);
        data.markPublished(token);
        assertNotNull(data.reserveTombstone(token));
        data.holdTombstone(token);
        data.rollback(token);
        var restored = roundtrip(data);
        assertTrue(restored.isTombstoneHeld(token));
        assertFalse(restored.isDeathExperienceHeld(token));
        assertNull(restored.reserveTombstone(token));
        assertFalse(restored.tombstoneEmpty(token));
        var saved = new NBTTagCompound();
        restored.writeToNBT(saved);
        assertEquals(
            items,
            saved.getTagList("Entries", 10)
                .getCompoundTagAt(0)
                .getTagList("TombstoneItems", 10));
        assertEquals(53, restored.reserveDeathExperience(token));
        restored.finishDeathExperience(token, 53, 53);
        assertNotNull(restored.reserve(token, MaidLifecycleData.Kind.FILM));
        restored.commit(token);
        assertTrue(roundtrip(restored).isTombstoneHeld(token));
    }

    @Test
    public void experienceHoldSurvivesReloadWithoutBlockingTombstoneOrFilm() {
        var data = new MaidLifecycleData("fixture");
        var items = new NBTTagList();
        items.appendTag(new NBTTagCompound());
        var token = data.depositDeath(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new NBTTagCompound(),
            items,
            0,
            1,
            5,
            2,
            "held",
            53,
            40);
        data.markPublished(token);
        assertEquals(53, data.reserveDeathExperience(token));
        data.holdDeathExperience(token);
        data.rollback(token);
        var restored = roundtrip(data);
        assertTrue(restored.isDeathExperienceHeld(token));
        assertFalse(restored.isTombstoneHeld(token));
        assertEquals(53, restored.remainingDeathExperience(token));
        assertTrue(
            restored.pendingDeathExperience(0, Long.MAX_VALUE)
                .isEmpty());
        assertEquals(0, restored.reserveDeathExperience(token));
        assertNotNull(restored.reserveTombstone(token));
        restored.finishTombstone(token, items, new NBTTagList());
        assertNotNull(restored.reserve(token, MaidLifecycleData.Kind.FILM));
        restored.commit(token);
        assertEquals(53, roundtrip(restored).remainingDeathExperience(token));
        assertTrue(roundtrip(restored).isDeathExperienceHeld(token));
    }

    @Test
    public void malformedOrMissingSchemaFourHoldsCannotBeOverwritten() {
        var data = new MaidLifecycleData("fixture");
        data.depositDeath(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new NBTTagCompound(),
            new NBTTagList(),
            0,
            1,
            5,
            2,
            "held");
        for (int mutation = 0; mutation < 3; mutation++) {
            var saved = new NBTTagCompound();
            data.writeToNBT(saved);
            var entry = saved.getTagList("Entries", 10)
                .getCompoundTagAt(0);
            if (mutation == 0) entry.setString("TombstoneHeld", "true");
            else if (mutation == 1) entry.setByte("ExperienceHeld", (byte) 2);
            else entry.removeTag("TombstoneHeld");
            var restored = new MaidLifecycleData("fixture");
            assertThrows(IllegalStateException.class, () -> restored.readFromNBT(saved));
            assertThrows(IllegalStateException.class, () -> restored.writeToNBT(new NBTTagCompound()));
        }
    }

    private static NBTTagCompound legacy(MaidLifecycleData data, int schema) {
        var tag = new NBTTagCompound();
        data.writeToNBT(tag);
        tag.setInteger("Schema", schema);
        var entries = tag.getTagList("Entries", 10);
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            if (schema < 6) entry.removeTag("Redemption");
            if (schema < 3) {
                entry.removeTag("DeathExperience");
                entry.removeTag("ExperienceReadyAt");
            }
            if (schema < 4) {
                entry.removeTag("TombstoneHeld");
                entry.removeTag("ExperienceHeld");
            }
            for (String key : new String[] { "AcquisitionPhase", "AcquisitionSource", "AcquisitionReceipt",
                "AcquisitionCredential" }) entry.removeTag(key);
        }
        return tag;
    }

    @Test
    public void schemaFourPreservesBothDeathHoldsAndBalancesWhenAddingAcquisitionIntents() {
        var data = new MaidLifecycleData("fixture");
        var items = new NBTTagList();
        var original = new NBTTagCompound();
        original.setString("ForeignItemPayload", "unchanged");
        items.appendTag(original);
        var token = data.depositDeath(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new NBTTagCompound(),
            items,
            7,
            1,
            5,
            2,
            "held",
            53,
            40);
        data.markPublished(token);
        data.holdTombstone(token);
        data.holdDeathExperience(token);
        var restored = new MaidLifecycleData("fixture");
        restored.readFromNBT(legacy(data, 4));
        assertTrue(restored.isTombstoneHeld(token));
        assertTrue(restored.isDeathExperienceHeld(token));
        assertEquals(53, restored.remainingDeathExperience(token));
        assertNull(restored.reserveTombstone(token));
        assertNotNull(restored.reserve(token, MaidLifecycleData.Kind.FILM));
        restored.rollback(token);
        var saved = new NBTTagCompound();
        restored.writeToNBT(saved);
        assertEquals(6, saved.getInteger("Schema"));
        assertEquals(
            items,
            saved.getTagList("Entries", 10)
                .getCompoundTagAt(0)
                .getTagList("TombstoneItems", 10));
    }

    @Test
    public void pendingGiftKeepsOneOwnerTokenAndOpaqueCredentialWithoutEnablingRedemption() {
        var data = new MaidLifecycleData("fixture");
        var owner = UUID.randomUUID();
        var token = data.initialToken(owner);
        var saved = new NBTTagCompound();
        data.writeToNBT(saved);
        var entry = saved.getTagList("Entries", 10)
            .getCompoundTagAt(0);
        var raw = new NBTTagCompound();
        raw.setString("ForeignNestedItemData", "preserved");
        entry.setString("AcquisitionPhase", "ISSUING");
        entry.setTag("AcquisitionCredential", raw);
        var restored = new MaidLifecycleData("fixture");
        restored.readFromNBT(saved);
        assertEquals(token, restored.initialToken(owner));
        assertEquals(MaidLifecycleData.AcquisitionPhase.ISSUING, restored.acquisitionPhase(token));
        assertFalse(restored.delivered(token));
        assertNull(restored.reserve(token, MaidLifecycleData.Kind.INITIAL));
        assertThrows(IllegalStateException.class, () -> restored.markDelivered(token));
        var copy = restored.acquisitionCredential(token);
        copy.setString("ForeignNestedItemData", "caller-change");
        assertEquals(raw, roundtrip(restored).acquisitionCredential(token));
    }

    @Test
    public void unknownOwnedFieldsAndMalformedAcquisitionStateCannotBeSilentlyRewritten() {
        for (int mutation = 0; mutation < 4; mutation++) {
            var data = new MaidLifecycleData("fixture");
            data.initialToken(UUID.randomUUID());
            var saved = new NBTTagCompound();
            data.writeToNBT(saved);
            var entry = saved.getTagList("Entries", 10)
                .getCompoundTagAt(0);
            if (mutation == 0) saved.setString("FutureOwnedRule", "preserve-original-file");
            else if (mutation == 1) entry.setString("FutureOwnedRule", "preserve-original-entry");
            else if (mutation == 2) entry.removeTag("AcquisitionPhase");
            else entry.setString("AcquisitionPhase", "FUTURE_STATE");
            var restored = new MaidLifecycleData("fixture");
            Class<? extends RuntimeException> expected = mutation == 3 ? IllegalArgumentException.class
                : IllegalStateException.class;
            assertThrows(expected, () -> restored.readFromNBT(saved));
            assertThrows(IllegalStateException.class, () -> restored.writeToNBT(new NBTTagCompound()));
        }
    }

    @Test
    public void malformedSingleUseFlagsCannotReenableAConsumedCredentialOrOverwriteItsEncoding() {
        for (String field : new String[] { "Consumed", "Delivered", "Published" }) {
            var data = new MaidLifecycleData("fixture");
            var token = data.initialToken(UUID.randomUUID());
            assertNotNull(data.reserve(token, MaidLifecycleData.Kind.INITIAL));
            data.commit(token);
            var saved = new NBTTagCompound();
            data.writeToNBT(saved);
            saved.getTagList("Entries", 10)
                .getCompoundTagAt(0)
                .setString(field, "true");
            var restored = new MaidLifecycleData("fixture");
            assertThrows(IllegalStateException.class, () -> restored.readFromNBT(saved));
            assertThrows(IllegalStateException.class, () -> restored.writeToNBT(new NBTTagCompound()));
        }
    }

    @Test
    public void schemaFiveAcquisitionRightsMigrateWithoutInventingAnOutstandingBirth() {
        var old = new MaidLifecycleData("fixture");
        var token = old.initialToken(UUID.randomUUID());
        var saved = new NBTTagCompound();
        old.writeToNBT(saved);
        saved.setInteger("Schema", 5);
        saved.getTagList("Entries", 10)
            .getCompoundTagAt(0)
            .removeTag("Redemption");
        var restored = new MaidLifecycleData("fixture");
        restored.readFromNBT(saved);
        assertTrue(
            restored.redemption(token)
                .hasNoTags());
        assertNotNull(restored.reserve(token, MaidLifecycleData.Kind.INITIAL));
        restored.rollback(token);
        var upgraded = new NBTTagCompound();
        restored.writeToNBT(upgraded);
        assertEquals(6, upgraded.getInteger("Schema"));
    }

    @Test
    public void unknownOrMalformedRedemptionCannotBeSilentlyClearedOnRead() {
        for (int mutation = 0; mutation < 3; mutation++) {
            var old = new MaidLifecycleData("fixture");
            old.initialToken(UUID.randomUUID());
            var saved = new NBTTagCompound();
            old.writeToNBT(saved);
            var entry = saved.getTagList("Entries", 10)
                .getCompoundTagAt(0);
            if (mutation == 0) entry.removeTag("Redemption");
            else if (mutation == 1) entry.setString("Redemption", "future-format");
            else entry.getCompoundTag("Redemption")
                .setString("FutureField", "must-remain");
            var restored = new MaidLifecycleData("fixture");
            assertThrows(IllegalStateException.class, () -> restored.readFromNBT(saved));
            assertThrows(IllegalStateException.class, () -> restored.writeToNBT(new NBTTagCompound()));
        }
    }
}
