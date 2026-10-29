/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/pojo/PolysEnum.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo;

import java.io.IOException;

public enum PolysEnum {

    // PolysEnum
    QUAD_LIST,
    TRI_LIST;

    public static PolysEnum forValue(String value) throws IOException {
        if ("quad_list".equals(value)) {
            return QUAD_LIST;
        }
        if ("tri_list".equals(value)) {
            return TRI_LIST;
        }
        throw new IOException("Cannot deserialize PolysEnum");
    }

    public String toValue() {
        return switch (this) {
            case QUAD_LIST -> "quad_list";
            case TRI_LIST -> "tri_list";
        };
    }
}
