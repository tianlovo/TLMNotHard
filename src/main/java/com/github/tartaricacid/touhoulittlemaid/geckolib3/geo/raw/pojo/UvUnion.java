/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/pojo/UvUnion.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo;

import java.lang.reflect.Type;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

public class UvUnion {

    public double[] boxUVCoords;
    public UvFaces faceUV;
    public boolean isBoxUV;

    protected static class Serializer implements JsonSerializer<UvUnion>, JsonDeserializer<UvUnion> {

        @Override
        public UvUnion deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            UvUnion result = new UvUnion();
            if (json.isJsonArray()) {
                result.isBoxUV = true;
                result.boxUVCoords = context.deserialize(json, double[].class);
            } else if (json.isJsonObject()) {
                result.isBoxUV = false;
                result.faceUV = context.deserialize(json, UvFaces.class);
            }
            return result;
        }

        @Override
        public JsonElement serialize(UvUnion src, Type typeOfSrc, JsonSerializationContext context) {
            return src.isBoxUV ? context.serialize(src.boxUVCoords) : context.serialize(src.faceUV);
        }
    }
}
