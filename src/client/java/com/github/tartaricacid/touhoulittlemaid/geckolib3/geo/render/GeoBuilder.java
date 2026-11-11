/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/render/GeoBuilder.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Bone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Cube;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.ModelProperties;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawBoneGroup;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoMesh;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.VectorUtils;

public class GeoBuilder implements IGeoBuilder {

    private static final IGeoBuilder DEFAULT_BUILDER = new GeoBuilder();

    public static IGeoBuilder getGeoBuilder() {
        return DEFAULT_BUILDER;
    }

    @Override
    public GeoModel constructGeoModel(RawGeometryTree geometryTree) {
        List<GeoBone> topLevelBones = new ArrayList<>();

        for (RawBoneGroup rawBone : geometryTree.topLevelBones.values()) {
            GeoBone bone = this.constructBone(rawBone, geometryTree.properties, 0);
            topLevelBones.add(bone);
        }

        return new GeoModel(topLevelBones, geometryTree.properties);
    }

    public GeoBone constructBone(RawBoneGroup bone, ModelProperties properties, int depth) {
        Bone rawBone = bone.selfBone;
        Vector3f rotation = VectorUtils.convertDoubleToFloat(VectorUtils.fromArray(rawBone.getRotation()));
        Vector3f pivot = VectorUtils.convertDoubleToFloat(VectorUtils.fromArray(rawBone.getPivot()));
        rotation.mul(-1, -1, 1);

        Cube[] cubes = rawBone.getCubes();
        GeoMesh.GeoMeshBuilder meshBuilder = new GeoMesh.GeoMeshBuilder(cubes == null ? 0 : cubes.length);
        if (cubes != null) {
            // 使用 For i 循环访问数组效率更高
            for (int i = 0; i < cubes.length; i++) {
                meshBuilder.addCube(
                    cubes[i],
                    properties,
                    rawBone.getInflate() == null ? 0 : rawBone.getInflate(),
                    rawBone.getMirror());
            }
        }
        GeoMesh mesh = meshBuilder.build();

        List<GeoBone> children = new ArrayList<>();
        for (RawBoneGroup child : bone.children.values()) {
            children.add(constructBone(child, properties, depth + 1));
        }

        GeoBone geoBone = new GeoBone(
            children,
            rawBone.getName(),
            new Vector3f(-pivot.x, pivot.y, pivot.z),
            new Vector3f(
                (float) Math.toRadians(rotation.x()),
                (float) Math.toRadians(rotation.y()),
                (float) Math.toRadians(rotation.z())),
            mesh,
            rawBone.getMirror(),
            rawBone.getInflate(),
            rawBone.getNeverRender(),
            rawBone.getReset());

        for (GeoBone child : children) {
            child.setParent(geoBone);
        }

        return geoBone;
    }
}
