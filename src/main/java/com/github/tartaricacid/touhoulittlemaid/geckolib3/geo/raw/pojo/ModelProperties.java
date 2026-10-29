/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/pojo/ModelProperties.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo;

import com.google.gson.annotations.SerializedName;

public class ModelProperties {

    @SerializedName("identifier")
    private String identifier;
    @SerializedName("texture_height")
    private double textureHeight;
    @SerializedName("texture_width")
    private double textureWidth;
    @SerializedName("visible_bounds_height")
    private double visibleBoundsHeight;
    @SerializedName("visible_bounds_width")
    private double visibleBoundsWidth;
    @SerializedName("visible_bounds_offset")
    private double[] visibleBoundsOffset;

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public Double getTextureHeight() {
        return textureHeight;
    }

    public void setTextureHeight(Double value) {
        this.textureHeight = value;
    }

    public Double getTextureWidth() {
        return textureWidth;
    }

    public void setTextureWidth(Double value) {
        this.textureWidth = value;
    }

    public Double getVisibleBoundsHeight() {
        return visibleBoundsHeight;
    }

    public void setVisibleBoundsHeight(Double value) {
        this.visibleBoundsHeight = value;
    }

    public Double getVisibleBoundsWidth() {
        return visibleBoundsWidth;
    }

    public void setVisibleBoundsWidth(Double value) {
        this.visibleBoundsWidth = value;
    }

    public double[] getVisibleBoundsOffset() {
        return visibleBoundsOffset;
    }

    public void setVisibleBoundsOffset(double[] value) {
        this.visibleBoundsOffset = value;
    }
}
