package com.tianluoqaq.tlmnh.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;

/** 只序列化公开状态，不计算几何、父链矩阵或预期顶点 */
public final class GeometrySnapshots {
    private GeometrySnapshots() {}

    public static JsonArray vector(double... values) {
        var array = new JsonArray();
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite native geometry state");
            array.add(new JsonPrimitive(value));
        }
        return array;
    }

    public static JsonObject gecko(AnimatedGeoModel model) {
        var result = new JsonObject();
        var roots = new JsonArray();
        model.topLevelBones().forEach(bone -> roots.add(new JsonPrimitive(bone.geoBone().name())));
        result.add("roots", roots);
        var nodes = new JsonObject();
        new TreeMap<>(model.bones()).forEach((name, bone) -> {
            var node = new JsonObject();
            node.addProperty("parent", bone.geoBone().parent() == null ? null : bone.geoBone().parent().name());
            node.add("position", vector(bone.getPositionX(), bone.getPositionY(), bone.getPositionZ()));
            node.add("pivot", vector(bone.getPivotX(), bone.getPivotY(), bone.getPivotZ()));
            node.add("rotationRadians", vector(bone.getRotationX(), bone.getRotationY(), bone.getRotationZ()));
            node.add("scale", vector(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ()));
            node.addProperty("hidden", bone.isHidden());
            node.addProperty("cubesHidden", bone.cubesAreHidden());
            node.addProperty("childrenHidden", bone.childBonesAreHiddenToo());
            node.addProperty("cubeCount", bone.geoBone().cubes().getCubeCount());
            var children = new JsonArray();
            bone.children().forEach(child -> children.add(new JsonPrimitive(child.geoBone().name())));
            node.add("children", children);
            nodes.add(name, node);
        });
        result.add("nodes", nodes);
        return result;
    }

    public static JsonArray bedrock(Map<String, BedrockPart> named) {
        var names = new IdentityHashMap<BedrockPart, String>();
        named.forEach((name, part) -> {
            if (names.put(part, name) != null) throw new IllegalStateException("Duplicate named Bedrock part");
        });
        var roots = new ArrayList<BedrockPart>();
        named.values().forEach(part -> { if (part.parent == null) roots.add(part); });
        roots.sort(Comparator.comparing(names::get));
        var seen = new IdentityHashMap<BedrockPart, Boolean>();
        var result = new JsonArray();
        for (var root : roots) bedrock(root, names.get(root), names, seen, result);
        if (!seen.keySet().containsAll(named.values())) throw new IllegalStateException("Unreachable named Bedrock part");
        return result;
    }

    private static void bedrock(BedrockPart part, String path, IdentityHashMap<BedrockPart, String> names,
        IdentityHashMap<BedrockPart, Boolean> seen, JsonArray output) {
        if (seen.put(part, true) != null) throw new IllegalStateException("Repeated or cyclic Bedrock part");
        var node = new JsonObject();
        node.addProperty("path", path);
        node.addProperty("name", names.get(part));
        node.addProperty("cubeCount", part.cubes.size());
        node.addProperty("childCount", part.children.size());
        node.add("position", vector(part.x, part.y, part.z));
        node.add("offset", vector(part.offsetX, part.offsetY, part.offsetZ));
        node.add("rotationRadians", vector(part.xRot, part.yRot, part.zRot));
        node.add("scale", vector(part.xScale, part.yScale, part.zScale));
        var q = part.additionalQuaternion;
        node.add("additionalQuaternion", vector(q.x, q.y, q.z, q.w));
        node.addProperty("visible", part.visible);
        node.addProperty("illuminated", part.illuminated);
        node.addProperty("mirror", part.mirror);
        output.add(node);
        for (int i = 0; i < part.children.size(); i++)
            bedrock(part.children.get(i), path + "/" + i, names, seen, output);
    }
}
