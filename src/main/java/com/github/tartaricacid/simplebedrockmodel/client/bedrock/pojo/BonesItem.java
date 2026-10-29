/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: com/github/tartaricacid/simplebedrockmodel/client/bedrock/pojo/BonesItem.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 types and legacy matrix/vertex interfaces
 */
package com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo;

import javax.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

public class BonesItem {

    @SerializedName("cubes")
    private CubesItem[] cubes;

    @SerializedName("name")
    private String name;

    @SerializedName("pivot")
    private float[] pivot;

    @SerializedName("rotation")
    private float[] rotation;

    @SerializedName("parent")
    private String parent;

    @SerializedName("mirror")
    private boolean mirror = false;

    @Nullable
    public CubesItem[] getCubes() {
        return cubes;
    }

    public String getName() {
        return name;
    }

    public float[] getPivot() {
        return pivot;
    }

    public float[] getRotation() {
        return rotation;
    }

    public String getParent() {
        return parent;
    }

    public boolean isMirror() {
        return mirror;
    }
}
