package com.tianluoqaq.tlmnh.common.sound;

import java.io.IOException;
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
    private static final List<String> EVENTS = Collections
        .unmodifiableList(new ArrayList<>(PACKS.get(MaidSoundTuning.GET_INITIAL_VALUE).events.keySet()));

    static {
        for (var pack : PACKS) if (!new ArrayList<>(pack.events.keySet()).equals(EVENTS))
            throw new IllegalStateException(MaidSoundDiagnostics.MSG_INCONSISTENT_BUILTIN_SOUND_EVENT_ORDER);
    }

    private BuiltinSoundCatalog() {}

    private static List<Pack> load() {
        try (var stream = BuiltinSoundCatalog.class.getResourceAsStream(MaidSoundIds.MODEL_JSON_BUILTIN_SOUNDS)) {
            if (stream == null) throw new IllegalStateException(MaidSoundDiagnostics.MSG_MISSING_BUILTIN_SOUND_CATALOG);
            var root = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
            if (root.get(MaidSoundIds.ID_SCHEMA)
                .getAsInt() != MaidSoundTuning.TARGET_GET_AS_INT_THRESHOLD)
                throw new IllegalStateException(MaidSoundDiagnostics.MSG_UNKNOWN_SOUND_CATALOG_SCHEMA);
            var packs = new ArrayList<Pack>();
            for (var element : root.getAsJsonArray(MaidSoundIds.ID_PACKS))
                packs.add(new Pack(element.getAsJsonObject()));
            return Collections.unmodifiableList(packs);
        } catch (IOException error) {
            throw new IllegalStateException(MaidSoundDiagnostics.MSG_CANNOT_READ_SOUND_CATALOG, error);
        }
    }

    public static List<Pack> packs() {
        return PACKS;
    }

    public static List<String> events() {
        return EVENTS;
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
            id = json.get(MaidSoundIds.ID_ID)
                .getAsString();
            var info = json.getAsJsonObject(MaidSoundIds.ID_INFO);
            name = value(info, MaidSoundIds.ID_PACK_NAME);
            description = value(info, MaidSoundIds.ID_DESCRIPTION);
            version = value(info, MaidSoundIds.ID_VERSION);
            date = value(info, MaidSoundIds.ID_DATE);
            url = value(info, MaidSoundIds.ID_URL);
            icon = value(info, MaidSoundIds.ID_ICON);
            var list = new ArrayList<String>();
            for (var author : info.getAsJsonArray(MaidSoundIds.ID_AUTHOR)) list.add(author.getAsString());
            authors = Collections.unmodifiableList(list);
            var map = new LinkedHashMap<String, Integer>();
            for (var event : json.getAsJsonObject(MaidSoundIds.ID_EVENTS)
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
