/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: com/github/tartaricacid/simplebedrockmodel/client/bedrock/pojo/GeometryModelNew.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 types and legacy matrix/vertex interfaces
 */
package com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo;

import com.google.gson.annotations.SerializedName;

public class GeometryModelNew {

    @SerializedName("description")
    private Description description;

    @SerializedName("bones")
    private BonesItem[] bones;

    public Description getDescription() {
        return description;
    }

    public BonesItem[] getBones() {
        return bones;
    }

    public void deco() {
        if (bones == null) {
            return;
        }
        for (BonesItem bonesItem : this.bones) {
            if (bonesItem.getCubes() == null) {
                continue;
            }
            for (CubesItem cubesItem : bonesItem.getCubes()) {
                if (!cubesItem.isHasMirror()) {
                    cubesItem.setMirror(bonesItem.isMirror());
                }
            }
        }
    }
}
