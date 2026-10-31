package com.tianluoqaq.tlmnh.common.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.google.gson.JsonParser;

public class BuiltinSoundCatalogTest {

    @Test
    public void nativeEventsPreserveTheOriginalCountsAndReuse() throws Exception {
        assertEquals(
            2,
            BuiltinSoundCatalog.packs()
                .size());
        assertEquals(
            "littlemaid_peco",
            BuiltinSoundCatalog.packs()
                .get(0).id);
        for (var pack : BuiltinSoundCatalog.packs()) {
            assertEquals(31, pack.events.size());
            try (var stream = getClass().getResourceAsStream("/assets/" + pack.id + "/sounds.json")) {
                var nativeEvents = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                for (var event : pack.events.entrySet()) assertEquals(
                    event.getValue()
                        .intValue(),
                    nativeEvents.getAsJsonObject(event.getKey())
                        .getAsJsonArray("sounds")
                        .size());
                var attacks = nativeEvents.getAsJsonObject("maid.mode.attack")
                    .getAsJsonArray("sounds");
                var ranged = nativeEvents.getAsJsonObject("maid.mode.range_attack")
                    .getAsJsonArray("sounds");
                var rangedFiles = new java.util.ArrayList<com.google.gson.JsonElement>();
                for (var sound : ranged) rangedFiles.add(sound);
                for (var attack : attacks) assertTrue(rangedFiles.contains(attack));
            }
        }
        assertEquals(
            43,
            BuiltinSoundCatalog.get("littlemaid_peco").events.get("maid.mode.range_attack")
                .intValue());
        assertEquals(
            106,
            BuiltinSoundCatalog.get("littlemaid_peco").events.get("maid.mode.torch")
                .intValue());
        assertEquals(
            0,
            BuiltinSoundCatalog.get("littlemaid_peco").events.get("maid.credit")
                .intValue());
        assertEquals(
            1,
            BuiltinSoundCatalog.get("touhou_little_maid").events.get("maid.credit")
                .intValue());
        assertNull(BuiltinSoundCatalog.get("untrusted:pack"));
    }
}
