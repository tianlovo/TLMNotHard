/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/pojo/Converter.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
// To use this code, add the following Maven dependency to your project:
//
//
// com.fasterxml.jackson.core : jackson-databind : 2.9.0
// com.fasterxml.jackson.datatype : jackson-datatype-jsr310 : 2.9.0
//
// Import this package:
//
// import software.bernie.geckolib.file.geo.Converter;
//
// Then you can deserialize a JSON string with
//
// GeoModel data = Converter.fromJsonString(jsonString);

package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

public class Converter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
        .registerTypeAdapter(FormatVersion.class, new FormatVersion.Serializer())
        .registerTypeAdapter(UvUnion.class, new UvUnion.Serializer())
        .registerTypeAdapter(LocatorValue.class, new LocatorValue.Serializer())
        .registerTypeAdapter(PolysUnion.class, new PolysUnion.Serializer())
        .create();

    public static RawGeoModel fromJsonString(String json) {
        return GSON.fromJson(json, RawGeoModel.class);
    }

    public static RawGeoModel fromInputStream(InputStream stream) {
        return GSON.fromJson(new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8)), RawGeoModel.class);
    }
}
