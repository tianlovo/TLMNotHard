/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/tree/RawGeometryTree.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Bone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.MinecraftGeometry;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.ModelProperties;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.RawGeoModel;
import com.google.common.collect.Maps;

public class RawGeometryTree {

    public Map<String, RawBoneGroup> topLevelBones = new LinkedHashMap<>();
    public ModelProperties properties;

    public static RawGeometryTree parseHierarchy(RawGeoModel model) {
        RawGeometryTree hierarchy = new RawGeometryTree();
        MinecraftGeometry geometry = model.getMinecraftGeometry()[0];
        hierarchy.properties = geometry.getProperties();
        Map<String, RawBoneGroup> groups = new LinkedHashMap<>();
        for (Bone bone : geometry.getBones()) {
            String name = bone.getName();
            if (name == null || name.isEmpty() || groups.put(name, new RawBoneGroup(bone)) != null) {
                throw new IllegalArgumentException("Missing or duplicate Gecko bone name: " + name);
            }
        }
        for (RawBoneGroup group : groups.values()) {
            String parentName = group.selfBone.getParent();
            if (parentName == null) {
                hierarchy.topLevelBones.put(group.selfBone.getName(), group);
            } else {
                RawBoneGroup parent = groups.get(parentName);
                if (parent == null) throw new IllegalArgumentException("Missing Gecko parent bone: " + parentName);
                parent.children.put(group.selfBone.getName(), group);
            }
        }
        // 原循环对缺失父节点或环无法结束；在构造递归模型前明确拒绝这两种输入
        var resolved = new java.util.HashSet<RawBoneGroup>();
        for (RawBoneGroup group : groups.values()) {
            var chain = new java.util.HashSet<RawBoneGroup>();
            RawBoneGroup current = group;
            while (current != null && !resolved.contains(current)) {
                if (!chain.add(current)) throw new IllegalArgumentException("Cyclic Gecko bone hierarchy");
                current = groups.get(current.selfBone.getParent());
            }
            resolved.addAll(chain);
        }
        return hierarchy;
    }

    public static boolean hasParent(Bone bone) {
        return bone.getParent() != null;
    }

    public static RawBoneGroup getGroupFromHierarchy(RawGeometryTree hierarchy, String bone) {
        HashMap<String, RawBoneGroup> flatList = Maps.newHashMap();
        for (RawBoneGroup group : hierarchy.topLevelBones.values()) {
            flatList.put(group.selfBone.getName(), group);
            traverse(flatList, group);
        }
        return flatList.get(bone);
    }

    public static void traverse(HashMap<String, RawBoneGroup> flatList, RawBoneGroup group) {
        for (RawBoneGroup child : group.children.values()) {
            flatList.put(child.selfBone.getName(), child);
            traverse(flatList, child);
        }
    }
}
