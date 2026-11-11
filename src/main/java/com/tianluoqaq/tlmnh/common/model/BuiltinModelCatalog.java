package com.tianluoqaq.tlmnh.common.model;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;

/** 两端共用的内置模型白名单，不访问客户端渲染类，也不扫描外部模型包 */
public final class BuiltinModelCatalog {

    private static final Map<String, Choice> BY_ID = new LinkedHashMap<>();
    private static List<Choice> choices = Collections.emptyList();
    private static final Choice EASTER_EGG_PLACEHOLDER = new Choice();

    private BuiltinModelCatalog() {}

    public static synchronized void load() {
        if (!BY_ID.isEmpty()) return;
        var stream = BuiltinModelCatalog.class.getResourceAsStream("/assets/tlmnh/builtin-models.json");
        if (stream == null) throw new IllegalStateException("Missing TLMNH builtin model catalog");
        var loaded = new LinkedHashMap<String, Choice>();
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = new JsonParser().parse(reader)
                .getAsJsonObject();
            if (root.get("schema")
                .getAsInt() != 1) throw new IllegalStateException("Unknown builtin model schema");
            for (var packElement : root.getAsJsonArray("packs")) {
                var pack = packElement.getAsJsonObject();
                String packId = pack.get("id")
                    .getAsString();
                for (var modelElement : pack.getAsJsonArray("models")) {
                    var choice = new Choice(packId, modelElement.getAsJsonObject());
                    if (loaded.put(choice.getId(), choice) != null)
                        throw new IllegalStateException("Duplicate builtin model ID: " + choice.getId());
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read TLMNH builtin model catalog", error);
        }
        if (!loaded.containsKey("touhou_little_maid:hakurei_reimu"))
            throw new IllegalStateException("Missing TLM default maid model");
        long winefoxChoices = loaded.values()
            .stream()
            .filter(Choice::isWinefox)
            .count();
        if (loaded.size() != 254 || winefoxChoices != 35)
            throw new IllegalStateException("Unexpected builtin model inventory");
        BY_ID.putAll(loaded);
        choices = Collections.unmodifiableList(new ArrayList<>(loaded.values()));
        ModContext.LOG.info("TLMNH_MODEL_CATALOG_READY choices={} winefox={}", choices.size(), winefoxChoices);
    }

    public static boolean contains(String id) {
        return BY_ID.containsKey(id);
    }

    public static Choice get(String id) {
        return BY_ID.get(id);
    }

    public static List<Choice> choices() {
        return choices;
    }

    /** 仅供客户端目录预览；不加入可选择索引或服务端模型白名单 */
    public static Choice easterEggPlaceholder() {
        return EASTER_EGG_PLACEHOLDER;
    }

    /** 显示文本保留上游翻译引用，客户端负责解析相应模型包语言资源 */
    public static final class Choice {

        private final String id;
        private final String baseId;
        private final String packId;
        private final String name;
        private final String preferredSoundPack;
        private final String easterEggTag;
        private final boolean easterEggEncrypted;
        private final List<String> description;
        private final ResourceLocation geometry;
        private final ResourceLocation texture;
        private final List<ResourceLocation> animations;
        private final boolean gecko;
        private final boolean showBackpack;
        private final boolean showCustomHead;
        private final float entityScale;
        private final float itemScale;

        private Choice(String packId, JsonObject json) {
            this.id = json.get("id")
                .getAsString();
            this.baseId = json.get("base_id")
                .getAsString();
            this.packId = packId;
            this.name = json.get("name")
                .getAsString();
            var source = json.getAsJsonObject("source_info");
            var egg = source.getAsJsonObject("easter_egg");
            this.easterEggTag = egg != null && egg.has("tag") ? egg.get("tag")
                .getAsString() : "";
            this.easterEggEncrypted = egg != null && egg.has("encrypt")
                && egg.get("encrypt")
                    .getAsBoolean();
            this.preferredSoundPack = source.has("use_sound_pack_id") && !source.get("use_sound_pack_id")
                .isJsonNull() ? source.get("use_sound_pack_id")
                    .getAsString() : "";
            this.showBackpack = !source.has("show_backpack") || source.get("show_backpack")
                .getAsBoolean();
            this.showCustomHead = !source.has("show_custom_head") || source.get("show_custom_head")
                .getAsBoolean();
            this.geometry = new ResourceLocation(
                json.get("geometry")
                    .getAsString());
            this.texture = new ResourceLocation(
                json.get("texture")
                    .getAsString());
            this.gecko = json.get("gecko")
                .getAsBoolean();
            this.entityScale = json.get("entity_scale")
                .getAsFloat();
            this.itemScale = json.get("item_scale")
                .getAsFloat();
            var animationList = new ArrayList<ResourceLocation>();
            for (var animation : json.getAsJsonArray("animations"))
                animationList.add(new ResourceLocation(animation.getAsString()));
            this.animations = Collections.unmodifiableList(animationList);
            var descriptionList = new ArrayList<String>();
            for (var line : json.getAsJsonArray("description")) descriptionList.add(line.getAsString());
            this.description = Collections.unmodifiableList(descriptionList);
        }

        private Choice() {
            id = baseId = "touhou_little_maid:easter_egg_model";
            packId = "touhou_little_maid";
            name = "{gui.touhou_little_maid.model_gui.easter_egg.normal}";
            preferredSoundPack = easterEggTag = "";
            easterEggEncrypted = false;
            geometry = new ResourceLocation("touhou_little_maid:models/bedrock/entity/easter_egg_model.json");
            texture = new ResourceLocation("touhou_little_maid:textures/bedrock/entity/easter_egg_model.png");
            animations = Collections.emptyList();
            description = Collections.emptyList();
            gecko = false;
            showBackpack = showCustomHead = true;
            entityScale = itemScale = 1;
        }

        public String getId() {
            return id;
        }

        public String getBaseId() {
            return baseId;
        }

        public String getPackId() {
            return packId;
        }

        public String getName() {
            return name;
        }

        public List<String> getDescription() {
            return description;
        }

        public ResourceLocation getGeometry() {
            return geometry;
        }

        public ResourceLocation getTexture() {
            return texture;
        }

        public List<ResourceLocation> getAnimations() {
            return animations;
        }

        public boolean isGecko() {
            return gecko;
        }

        public float getEntityScale() {
            return entityScale;
        }

        public float getItemScale() {
            return itemScale;
        }

        public boolean isWinefox() {
            return baseId.contains("winefox") || baseId.contains("wine_fox");
        }

        public boolean isShowBackpack() {
            return showBackpack;
        }

        public boolean isShowCustomHead() {
            return showCustomHead;
        }

        public String getPreferredSoundPack() {
            return preferredSoundPack;
        }

        public boolean isEasterEgg() {
            return !easterEggTag.trim()
                .isEmpty();
        }

        public String getEasterEggTag() {
            return easterEggTag;
        }

        public boolean isEasterEggEncrypted() {
            return easterEggEncrypted;
        }
    }
}
