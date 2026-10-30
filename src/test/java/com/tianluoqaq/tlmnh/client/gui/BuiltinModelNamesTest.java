package com.tianluoqaq.tlmnh.client.gui;

import static org.junit.Assert.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;

import org.junit.Test;

import com.google.gson.JsonParser;

public class BuiltinModelNamesTest {

    @Test
    public void resolvesOriginalWinefoxNamesWithoutChangingTheirText() throws Exception {
        String path = "/assets/geckolib/lang/zh_cn.json";
        var translations = new HashMap<String, String>();
        try (var reader = new InputStreamReader(getClass().getResourceAsStream(path), StandardCharsets.UTF_8)) {
            for (var entry : new JsonParser().parse(reader)
                .getAsJsonObject()
                .entrySet())
                translations.put(
                    entry.getKey(),
                    entry.getValue()
                        .getAsString());
        }
        String key = "model.geckolib.winefox_hanfu.name";
        assertEquals(
            translations.get(key),
            BuiltinModelNames.resolve("{" + key + "}", translations, "geckolib:winefox_hanfu"));
    }

    @Test
    public void missingTranslationsFallBackToIdAndLiteralNamesRemainLiteral() {
        assertEquals(
            "geckolib:winefox_hanfu",
            BuiltinModelNames.resolve("{missing.key}", Collections.emptyMap(), "geckolib:winefox_hanfu"));
        assertEquals("Literal model", BuiltinModelNames.resolve("Literal model", Collections.emptyMap(), "fallback"));
    }
}
