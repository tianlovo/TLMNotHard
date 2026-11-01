package com.tianluoqaq.tlmnh.common.ai;

import static org.junit.Assert.*;

import org.junit.Test;

public class MaidWaterPhysicsTest {

    @Test
    public void liftAndHorizontalInputShareOneNormalization() {
        var input = MaidWaterPhysics.acceleration(1, 1, true, 0);
        assertEquals((double) .02F, Math.sqrt(input.x * input.x + input.y * input.y + input.z * input.z), 1e-10);
        assertTrue(input.y < .01);
        var rotated = MaidWaterPhysics.acceleration(0, 1, false, 90);
        assertTrue(rotated.x < 0);
        assertEquals(0, rotated.z, 1e-9);
    }

    @Test
    public void upwardBuoyancySurvivesModernGravityAndOldThresholdBoundary() {
        var input = MaidWaterPhysics.acceleration(0, 0, true, 0);
        double velocity = MaidWaterPhysics.fallingVelocity(input.y * .8F, .08, true, false);
        assertEquals(.003, velocity, 1e-9);
        assertTrue(velocity > 0 && velocity < .005);
        assertEquals(-.003, MaidWaterPhysics.fallingVelocity(.001, .01, true, false), 0);
        assertEquals(.01, MaidWaterPhysics.fallingVelocity(.01, .08, false, true), 0);
    }
}
