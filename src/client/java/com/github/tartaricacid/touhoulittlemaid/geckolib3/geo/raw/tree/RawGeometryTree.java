/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/tree/RawGeometryTree.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API; validate malformed hierarchy before the original reverse-pass attachment order
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Bone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.MinecraftGeometry;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.ModelProperties;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.RawGeoModel;
import com.google.common.collect.Maps;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

public class RawGeometryTree {

    public Map<String, RawBoneGroup> topLevelBones = new Object2ObjectOpenHashMap<>();
    public ModelProperties properties;

    public static RawGeometryTree parseHierarchy(RawGeoModel model) {
        RawGeometryTree hierarchy = new RawGeometryTree();
        MinecraftGeometry geometry = model.getMinecraftGeometry()[0];
        hierarchy.properties = geometry.getProperties();
        validate(geometry.getBones());
        List<Bone> bones = new ObjectArrayList<>(geometry.getBones());
        // 当前移植版允许空树；不要恢复原版对此输入的 get(-1)
        if (bones.isEmpty()) return hierarchy;
        int index = bones.size() - 1;
        while (true) {
            Bone bone = bones.get(index);
            if (!hasParent(bone)) {
                hierarchy.topLevelBones.put(bone.getName(), new RawBoneGroup(bone));
                bones.remove(bone);
            } else {
                RawBoneGroup parent = getGroupFromHierarchy(hierarchy, bone.getParent());
                if (parent != null) {
                    parent.children.put(bone.getName(), new RawBoneGroup(bone));
                    bones.remove(bone);
                }
            }
            if (index == 0) {
                index = bones.size() - 1;
                if (index == -1) break;
            } else index--;
        }
        return hierarchy;
    }

    /** 校验只做名字查找，不参与任何输出map的填充或迭代次序 */
    private static void validate(Bone[] bones) {
        Map<String, Bone> names = new LinkedHashMap<>();
        for (Bone bone : bones) {
            String name = bone == null ? null : bone.getName();
            if (name == null || name.isEmpty() || names.put(name, bone) != null)
                throw new IllegalArgumentException("Missing or duplicate Gecko bone name: " + name);
        }
        for (Bone bone : names.values()) {
            String parent = bone.getParent();
            if (parent != null && !names.containsKey(parent))
                throw new IllegalArgumentException("Missing Gecko parent bone: " + parent);
        }
        var resolved = new HashSet<String>();
        for (Bone bone : names.values()) {
            var chain = new HashSet<String>();
            Bone current = bone;
            while (current != null && !resolved.contains(current.getName())) {
                if (!chain.add(current.getName())) throw new IllegalArgumentException("Cyclic Gecko bone hierarchy");
                current = names.get(current.getParent());
            }
            resolved.addAll(chain);
        }
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
