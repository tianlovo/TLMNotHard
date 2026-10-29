/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/pojo/PolysUnion.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo;

import java.io.IOException;
import java.lang.reflect.Type;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

public class PolysUnion {

    public double[][][] doubleArrayArrayArrayValue;
    public PolysEnum enumValue;

    protected static class Serializer implements JsonSerializer<PolysUnion>, JsonDeserializer<PolysUnion> {

        @Override
        public PolysUnion deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            PolysUnion result = new PolysUnion();
            if (json.isJsonArray()) {
                result.doubleArrayArrayArrayValue = context.deserialize(json, double[][][].class);
            } else if (json.isJsonPrimitive()) {
                try {
                    result.enumValue = PolysEnum.forValue(json.getAsString());
                } catch (IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            return result;
        }

        @Override
        public JsonElement serialize(PolysUnion src, Type typeOfSrc, JsonSerializationContext context) {
            return src.doubleArrayArrayArrayValue != null ? context.serialize(src.doubleArrayArrayArrayValue)
                : new JsonPrimitive(src.enumValue.toValue());
        }
    }
}
