package com.tianluoqaq.tlmnh.common.sound;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 固定内置音包的共同索引，不在服务端解码配音或访问客户端资源管理器 */
public final class BuiltinSoundCatalog {

    private static final List<Pack> PACKS = load();

    private BuiltinSoundCatalog() {}

    private static List<Pack> load() {
        try (var stream = BuiltinSoundCatalog.class.getResourceAsStream("/assets/tlmnh/builtin-sounds.json")) {
            if (stream == null) throw new IllegalStateException("Missing builtin sound catalog");
            var root = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
            if (root.get("schema")
                .getAsInt() != 1) throw new IllegalStateException("Unknown sound catalog schema");
            var packs = new ArrayList<Pack>();
            for (var element : root.getAsJsonArray("packs")) packs.add(new Pack(element.getAsJsonObject()));
            return Collections.unmodifiableList(packs);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read sound catalog", error);
        }
    }

    public static List<Pack> packs() {
        return PACKS;
    }

    public static Pack get(String id) {
        for (var pack : PACKS) if (pack.id.equals(id)) return pack;
        return null;
    }

    public static final class Pack {

        public final String id, name, description, version, date, url, icon;
        public final List<String> authors;
        public final Map<String, Integer> events;

        private Pack(JsonObject json) {
            id = json.get("id")
                .getAsString();
            var info = json.getAsJsonObject("info");
            name = value(info, "pack_name");
            description = value(info, "description");
            version = value(info, "version");
            date = value(info, "date");
            url = value(info, "url");
            icon = value(info, "icon");
            var list = new ArrayList<String>();
            for (var author : info.getAsJsonArray("author")) list.add(author.getAsString());
            authors = Collections.unmodifiableList(list);
            var map = new LinkedHashMap<String, Integer>();
            for (var event : json.getAsJsonObject("events")
                .entrySet())
                map.put(
                    event.getKey(),
                    event.getValue()
                        .getAsInt());
            events = Collections.unmodifiableMap(map);
        }

        private static String value(JsonObject json, String key) {
            return json.has(key) ? json.get(key)
                .getAsString() : "";
        }
    }
}
