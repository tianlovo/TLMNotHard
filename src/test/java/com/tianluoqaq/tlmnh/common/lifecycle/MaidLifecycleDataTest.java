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
        invalid.setInteger("Schema", 2);
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
}
