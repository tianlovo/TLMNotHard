package com.tianluoqaq.tlmnh.client.gui;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 固定目录的原包信息与服务端全局索引对应，不从外部包构造可提交索引 */
@SideOnly(Side.CLIENT)
public final class BuiltinMaidPacks {

    private static List<Pack> packs;

    private BuiltinMaidPacks() {}

    public static List<Pack> packs() {
        if (packs != null) return packs;
        var result = new ArrayList<Pack>();
        var stream = BuiltinMaidPacks.class.getResourceAsStream("/assets/tlmnh/builtin-models.json");
        if (stream == null) throw new IllegalStateException("Missing builtin pack metadata");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var json = new JsonParser().parse(reader)
                .getAsJsonObject();
            for (var value : json.getAsJsonArray("packs")) result.add(new Pack(value.getAsJsonObject()));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read builtin pack metadata", error);
        }
        packs = Collections.unmodifiableList(result);
        return packs;
    }

    public static final class Pack {

        public final String id, name, version, date;
        public final ResourceLocation icon;
        public final List<String> authors, description;
        public final List<Integer> modelIndices;

        private Pack(JsonObject json) {
            id = json.get("id")
                .getAsString();
            name = json.get("pack_name")
                .getAsString();
            version = json.get("version")
                .getAsString();
            date = json.get("date")
                .getAsString();
            icon = new ResourceLocation(
                json.get("icon")
                    .getAsString());
            authors = strings(json, "author");
            description = strings(json, "description");
            var indices = new ArrayList<Integer>();
            var choices = BuiltinModelCatalog.choices();
            for (int i = 0; i < choices.size(); i++) if (choices.get(i)
                .getPackId()
                .equals(id)) indices.add(i);
            if (indices.isEmpty()) throw new IllegalStateException("Empty builtin model pack: " + id);
            modelIndices = Collections.unmodifiableList(indices);
        }

        private static List<String> strings(JsonObject json, String key) {
            var result = new ArrayList<String>();
            for (var item : json.getAsJsonArray(key)) result.add(item.getAsString());
            return Collections.unmodifiableList(result);
        }
    }
}
