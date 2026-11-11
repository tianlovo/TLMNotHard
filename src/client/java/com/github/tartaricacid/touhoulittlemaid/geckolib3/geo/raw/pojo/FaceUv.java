/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/pojo/FaceUv.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo;

import com.google.gson.annotations.SerializedName;

public class FaceUv {

    @SerializedName("material_instance")
    private String materialInstance;
    @SerializedName("uv")
    private double[] uv;
    @SerializedName("uv_size")
    private double[] uvSize;

    public String getMaterialInstance() {
        return materialInstance;
    }

    public void setMaterialInstance(String value) {
        this.materialInstance = value;
    }

    public double[] getUv() {
        return uv;
    }

    public void setUv(double[] value) {
        this.uv = value;
    }

    public double[] getUvSize() {
        return uvSize;
    }

    public void setUvSize(double[] value) {
        this.uvSize = value;
    }
}
