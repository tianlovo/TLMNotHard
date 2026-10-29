/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: com/github/tartaricacid/simplebedrockmodel/client/bedrock/pojo/BedrockModelPOJO.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 types and legacy matrix/vertex interfaces
 */
package com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo;

import javax.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

public class BedrockModelPOJO {

    @SerializedName("format_version")
    private String formatVersion;

    @SerializedName("geometry.model")
    @Nullable
    private GeometryModelLegacy geometryModelLegacy;

    @SerializedName("minecraft:geometry")
    @Nullable
    private GeometryModelNew[] geometryModelNew;

    public String getFormatVersion() {
        return formatVersion;
    }

    @Nullable
    public GeometryModelLegacy getGeometryModelLegacy() {
        return geometryModelLegacy;
    }

    @Nullable
    public GeometryModelNew getGeometryModelNew() {
        if (geometryModelNew == null || geometryModelNew.length == 0) {
            return null;
        }
        return geometryModelNew[0];
    }
}
