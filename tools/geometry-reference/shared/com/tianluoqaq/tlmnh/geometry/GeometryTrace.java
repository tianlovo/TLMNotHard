package com.tianluoqaq.tlmnh.geometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** 仅在实际发射骨骼边界内标识输出，不推测 DFS，不计算坐标或预期几何 */
public final class GeometryTrace {
    private static final String[] FACES = { "down", "up", "north", "south", "west", "east" };
    public final JsonArray keys = new JsonArray();
    public final JsonObject boneFaces = new JsonObject();
    private String active;
    private int[] masks;
    private int cube, face, corner;

    public void beginBone(String name, int[] actualMasks) {
        require(active == null && name != null && !name.isEmpty() && !boneFaces.has(name), "Duplicate/nested actual bone boundary");
        active = name;
        masks = actualMasks.clone();
        cube = face = corner = 0;
        var values = new JsonArray();
        for (int mask : masks) {
            require(mask >= 0 && mask <= 127, "Unknown actual mesh face mask");
            values.add(new JsonPrimitive(mask));
        }
        boneFaces.add(name, values);
        advanceFace();
    }

    public void emittedVertex(int drawIndex) {
        require(active != null && cube < masks.length && drawIndex == keys.size(), "Unbound/excessive actual vertex");
        var key = new JsonObject();
        key.addProperty("bone", active);
        key.addProperty("cube", cube);
        key.addProperty("face", FACES[face]);
        key.addProperty("corner", corner);
        key.addProperty("drawIndex", drawIndex);
        keys.add(key);
        if (++corner == 4) {
            corner = 0;
            face++;
            advanceFace();
        }
    }

    private void advanceFace() {
        while (cube < masks.length) {
            while (face < 6 && (masks[cube] & (1 << face)) == 0) face++;
            if (face < 6) return;
            cube++;
            face = 0;
        }
    }

    public void endBone() {
        require(active != null && cube == masks.length && corner == 0, "Incomplete actual bone emission");
        active = null;
        masks = null;
    }

    public void finish(int vertices) {
        require(active == null && keys.size() == vertices, "Incomplete bone trace coverage");
    }

    public void addTo(JsonObject sample) {
        sample.addProperty("mapping", "actual-bone-cube-face-corner");
        sample.add("vertexKeys", keys);
        sample.add("boneFaces", boneFaces);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
