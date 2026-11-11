package com.tianluoqaq.tlmnh.client.development;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonObject;

/** 合成RGBA仅验证指标和拒绝规则，不是实际GPU绘制证据 */
public class GpuMatrixProbeTest {

    @Test
    public void stableNormalsSmallerFaultAndExactRecoveryAreAllRequired() {
        var samples = sequence();
        var result = assess(samples, true);
        assertTrue(
            result.get("passed")
                .getAsBoolean());
        assertEquals(
            "PASS_BOUNDED_GPU_EXPERIMENT",
            result.get("verdict")
                .getAsString());
        assertEquals(
            0,
            result.get("baselineMaxChangedPixels")
                .getAsInt());
        assertEquals(
            1536,
            result.get("faultOccupiedPixels")
                .getAsInt());
        samples.set(3, sample("fault-not-visible", 64, 96));
        result = assess(samples, true);
        assertFalse(
            result.get("passed")
                .getAsBoolean());
        assertFalse(
            result.get("matrixFaultDetected")
                .getAsBoolean());
    }

    @Test
    public void normalNoiseOrEmptyForegroundIsInconclusive() {
        var samples = sequence();
        samples.get(1).rgba[0] = 1;
        assertEquals(
            "INCONCLUSIVE",
            assess(samples, true).get("verdict")
                .getAsString());
        for (int i = 0; i < 3; i++) samples.set(i, sample("empty", 0, 0));
        assertEquals(
            "INCONCLUSIVE",
            assess(samples, true).get("verdict")
                .getAsString());
    }

    @Test
    public void failedGlRestorationOrChangedRecoveryCannotPass() {
        assertEquals(
            "INCONCLUSIVE",
            assess(sequence(), false).get("verdict")
                .getAsString());
        var samples = sequence();
        samples.set(4, sample("bad-recovery", 32, 48));
        var result = assess(samples, true);
        assertFalse(
            result.get("passed")
                .getAsBoolean());
        assertFalse(
            result.get("recoveryMatched")
                .getAsBoolean());
    }

    private static List<GpuMatrixProbe.Sample> sequence() {
        var result = new ArrayList<GpuMatrixProbe.Sample>();
        for (String name : new String[] { "normal0", "normal1", "normal2", "fault", "recovery" })
            result.add(sample(name, name.equals("fault") ? 32 : 64, name.equals("fault") ? 48 : 96));
        return result;
    }

    private static GpuMatrixProbe.Sample sample(String name, int width, int height) {
        byte[] rgba = new byte[256 * 256 * 4];
        for (int y = 128 - height / 2; y < 128 + height / 2; y++)
            for (int x = 128 - width / 2; x < 128 + width / 2; x++) {
                int offset = (y * 256 + x) * 4;
                rgba[offset] = (byte) 200;
                rgba[offset + 3] = (byte) 255;
            }
        return new GpuMatrixProbe.Sample(name, name.equals("fault") ? 0.5F : 1, rgba, "synthetic-stream", 4, 1);
    }

    private static JsonObject assess(List<GpuMatrixProbe.Sample> samples, boolean restored) {
        var result = new JsonObject();
        GpuMatrixProbe.assess(result, samples, restored);
        return result;
    }
}
