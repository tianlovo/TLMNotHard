/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/pojo/LocatorValue.java
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

public class LocatorValue {

    public LocatorClass locatorClassValue;
    public double[] doubleArrayValue;

    protected static class Serializer implements JsonSerializer<LocatorValue>, JsonDeserializer<LocatorValue> {

        @Override
        public LocatorValue deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            LocatorValue result = new LocatorValue();
            if (json.isJsonArray()) {
                result.doubleArrayValue = context.deserialize(json, double[].class);
            } else if (json.isJsonObject()) {
                result.locatorClassValue = context.deserialize(json, LocatorClass.class);
            }
            return result;
        }

        @Override
        public JsonElement serialize(LocatorValue src, Type typeOfSrc, JsonSerializationContext context) {
            return src.locatorClassValue != null ? context.serialize(src.locatorClassValue)
                : context.serialize(src.doubleArrayValue);
        }
    }
}
