package com.tianluoqaq.tlmnh.common.life;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.life.MaidHomeSettings.Point;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

public class MaidHomeSettingsTest {

    @Test
    public void wirePreservesAllCentersDimensionAndStrictBlockSphere() {
        var state = new MaidHomeSettings(
            true,
            true,
            -7,
            new Point(-9, 180, 3),
            new Point(1, 180, 2),
            new Point(5, 180, 7));
        var decoded = MaidHomeSettings.decode(state.encode());
        assertEquals(state.encode(), decoded.encode());
        assertEquals(-9, decoded.center(Activity.WORK).x);
        assertEquals(1, decoded.center(Activity.IDLE).x);
        assertEquals(7, decoded.center(Activity.REST).z);
        assertTrue(decoded.work.containsBlock(-4, 180, 3, 6));
        assertFalse(decoded.work.containsBlock(-3, 180, 3, 6));
        assertFalse(decoded.work.containsBlock(-9, 186, 3, 6));
    }

    @Test
    public void invalidOrFutureWireCannotBecomeEnabledSettings() {
        for (var encoded : new String[] { "", "2;1;1;0;0,0,0;0,0,0;0,0,0", "1;1;2;0;0,0,0;0,0,0;0,0,0",
            "1;1;1;0;0,-1,0;0,0,0;0,0,0", "1;1;1;0;2147483647,0,0;0,0,0;0,0,0" }) {
            try {
                MaidHomeSettings.decode(encoded);
                fail("Invalid home settings accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }
}
