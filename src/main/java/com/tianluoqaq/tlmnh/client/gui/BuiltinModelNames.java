package com.tianluoqaq.tlmnh.client.gui;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 读取原模型包JSON语言，保留上游模型名称和资源包覆盖 */
@SideOnly(Side.CLIENT)
public final class BuiltinModelNames implements IResourceManagerReloadListener {

    public static final BuiltinModelNames INSTANCE = new BuiltinModelNames();
    private final Map<String, String> translations = new HashMap<>();
    private IResourceManager manager;
    private String language;
    private int revision;

    private BuiltinModelNames() {}

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        this.manager = manager;
        language = null;
        translations.clear();
        revision++;
    }

    public int getRevision() {
        ensureLanguage();
        return revision;
    }

    public String name(BuiltinModelCatalog.Choice choice) {
        ensureLanguage();
        String raw = choice.getName();
        if (raw.startsWith("{") && raw.endsWith("}")) {
            String key = raw.substring(1, raw.length() - 1);
            if (StatCollector.canTranslate(key)) return StatCollector.translateToLocal(key);
        }
        return resolve(raw, translations, choice.getId());
    }

    static String resolve(String raw, Map<String, String> translations, String fallback) {
        if (raw.startsWith("{") && raw.endsWith("}")) {
            String value = translations.get(raw.substring(1, raw.length() - 1));
            return value == null || value.isEmpty() ? fallback : value;
        }
        return raw.isEmpty() ? fallback : raw;
    }

    public String text(String raw) {
        ensureLanguage();
        String fallback = raw.startsWith("{") && raw.endsWith("}") ? raw.substring(1, raw.length() - 1) : raw;
        if (StatCollector.canTranslate(fallback)) return StatCollector.translateToLocal(fallback);
        return resolve(raw, translations, fallback);
    }

    private void ensureLanguage() {
        var minecraft = Minecraft.getMinecraft();
        String current = minecraft.gameSettings.language.toLowerCase(Locale.ROOT);
        if (current.equals(language)) return;
        if (manager == null) manager = minecraft.getResourceManager();
        translations.clear();
        readLanguage("en_us");
        if (!"en_us".equals(current)) readLanguage(current);
        language = current;
        revision++;
    }

    private void readLanguage(String code) {
        var packs = new LinkedHashSet<String>();
        for (var choice : BuiltinModelCatalog.choices()) packs.add(choice.getPackId());
        for (String pack : packs) {
            var location = new ResourceLocation(pack, "lang/" + code + ".json");
            try (var reader = new InputStreamReader(
                manager.getResource(location)
                    .getInputStream(),
                StandardCharsets.UTF_8)) {
                var json = new JsonParser().parse(reader)
                    .getAsJsonObject();
                for (var entry : json.entrySet()) {
                    if (entry.getValue()
                        .isJsonPrimitive()
                        && entry.getValue()
                            .getAsJsonPrimitive()
                            .isString())
                        translations.put(
                            entry.getKey(),
                            entry.getValue()
                                .getAsString());
                }
            } catch (IOException missingLanguage) {
                // 模型包不必提供所有语言，缺少时沿用英文或模型ID
            } catch (RuntimeException invalidLanguage) {
                TLMNotHard.LOG.warn("Cannot read builtin model names {}", location, invalidLanguage);
            }
        }
    }
}
