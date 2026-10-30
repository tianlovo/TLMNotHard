package com.tianluoqaq.tlmnh.client.development;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.render.LegacyVertexConsumer;

/** 同姿态的几何重放与实际适配器参数对照，不作为上游几何或GPU成像的独立判据 */
public final class RenderSubmissionTrace {

    private static final int MAX_VERTICES = 65536;
    private static final String[] CHANNELS = { "x", "y", "z", "red", "green", "blue", "alpha", "u", "v", "normalX",
        "normalY", "normalZ" };
    private static final Gson GSON = new Gson();
    private final List<Vertex> expected = new ArrayList<>();
    private final MessageDigest expectedHash = digest(), actualHash = digest();
    private final float[] color;
    private int actualCount, differences;
    private JsonObject first;

    public RenderSubmissionTrace(float red, float green, float blue, float alpha) {
        color = new float[] { red, green, blue, alpha };
        for (float value : color) finite(value);
    }

    public LegacyVertexConsumer expectedConsumer() {
        return (x, y, z, red, green, blue, alpha, u, v, overlay, light, nx, ny, nz) -> {
            if (expected.size() == MAX_VERTICES) throw new IllegalStateException("Expected submission limit exceeded");
            double length = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
            if (length > 0) {
                nx = (float) (nx / length);
                ny = (float) (ny / length);
                nz = (float) (nz / length);
            }
            var vertex = new Vertex(
                new float[] { x, y, z, red * color[0], green * color[1], blue * color[2], alpha * color[3], u, v, nx,
                    ny, nz },
                overlay,
                light);
            expected.add(vertex);
            update(expectedHash, vertex);
        };
    }

    /** 参数必须与即将调用Tessellator的值相同，法线已归一化、颜色已乘当前GL颜色 */
    public void submitted(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
        int overlay, int light, float nx, float ny, float nz) {
        if (actualCount == MAX_VERTICES) throw new IllegalStateException("Actual submission limit exceeded");
        var vertex = new Vertex(new float[] { x, y, z, red, green, blue, alpha, u, v, nx, ny, nz }, overlay, light);
        update(actualHash, vertex);
        if (actualCount < expected.size()) {
            var before = expected.get(actualCount);
            for (int index = 0; index < CHANNELS.length; index++) if (before.values[index] != vertex.values[index])
                difference(CHANNELS[index], before.values[index], vertex.values[index]);
            if (before.overlay != overlay) difference("overlay", before.overlay, overlay);
            if (before.light != light) difference("lightmap", before.light, light);
        }
        actualCount++;
    }

    public JsonObject finish() {
        if (actualCount != expected.size()) difference("vertexCount", expected.size(), actualCount);
        var result = new JsonObject();
        result.addProperty("expectedVertices", expected.size());
        result.addProperty("actualVertices", actualCount);
        result.addProperty("expectedSha256", hex(expectedHash.digest()));
        result.addProperty("actualSha256", hex(actualHash.digest()));
        result.addProperty("differingFields", differences);
        result.addProperty("matched", !expected.isEmpty() && expected.size() % 4 == 0 && differences == 0);
        if (first != null) result.add("firstDivergence", first);
        if (!expected.isEmpty()) result.add("firstExpectedVertex", GSON.toJsonTree(expected.get(0)));
        return result;
    }

    private void difference(String field, double before, double after) {
        differences++;
        if (first == null) {
            first = new JsonObject();
            first.addProperty("vertex", actualCount);
            first.addProperty("field", field);
            first.addProperty("expected", before);
            first.addProperty("actual", after);
        }
    }

    private static void update(MessageDigest digest, Vertex vertex) {
        for (float value : vertex.values) update(digest, Float.floatToIntBits(value == 0 ? 0 : value));
        update(digest, vertex.overlay);
        update(digest, vertex.light);
    }

    private static void update(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String hex(byte[] bytes) {
        var text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 255));
        return text.toString();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite submission value");
    }

    private static final class Vertex {

        final float[] values;
        final int overlay, light;

        Vertex(float[] values, int overlay, int light) {
            for (float value : values) finite(value);
            this.values = values;
            this.overlay = overlay;
            this.light = light;
        }
    }
}
