package com.tianluoqaq.tlmnh.common.life;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

public class MaidBedLeaseTest {

    private static final UUID FIRST = new UUID(0, 1), SECOND = new UUID(0, 2);

    @Test
    public void anotherMaidCannotClaimOrReleaseAnActiveBed() {
        var lease = new MaidBedLease();
        assertTrue(lease.claim(FIRST, 100));
        assertFalse(lease.claim(SECOND, 101));
        assertFalse(lease.release(SECOND));
        assertTrue(lease.isOwnedBy(FIRST, 159));
    }

    @Test
    public void refreshExtendsLeaseAndLateOldCancellationCannotReleaseNewOwner() {
        var lease = new MaidBedLease();
        assertTrue(lease.claim(FIRST, 100));
        assertTrue(lease.claim(FIRST, 150));
        assertFalse(lease.available(SECOND, 209));
        assertTrue(lease.claim(SECOND, 210));
        assertFalse(lease.release(FIRST));
        assertTrue(lease.isOwnedBy(SECOND, 211));
    }

    @Test
    public void unloadClearImmediatelyReleasesAndNullCannotOccupy() {
        var lease = new MaidBedLease();
        assertFalse(lease.claim(null, 0));
        assertTrue(lease.claim(FIRST, 0));
        lease.clear();
        assertTrue(lease.claim(SECOND, 1));
        assertTrue(lease.release(SECOND));
        assertFalse(lease.isOwnedBy(SECOND, 2));
    }
}
