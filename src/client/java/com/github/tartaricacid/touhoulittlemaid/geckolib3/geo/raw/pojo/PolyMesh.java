/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/pojo/PolyMesh.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo;

import com.google.gson.annotations.SerializedName;

public class PolyMesh {

    @SerializedName("normalized_uvs")
    private boolean normalizedUvs;
    @SerializedName("normals")
    private double[] normals;
    @SerializedName("polys")
    private PolysUnion polys;
    @SerializedName("positions")
    private double[] positions;
    @SerializedName("uvs")
    private double[] uvs;
}
