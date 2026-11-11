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
        var stream = BuiltinModelCatalog.class.getResourceAsStream(BuiltinModelDataIds.MODEL_JSON_BUILTIN_MODELS);
        if (stream == null)
            throw new IllegalStateException(BuiltinModelDataDiagnostics.MSG_MISSING_TLMNH_BUILTIN_MODEL_CATALOG);
        var loaded = new LinkedHashMap<String, Choice>();
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = new JsonParser().parse(reader)
                .getAsJsonObject();
            if (root.get(BuiltinModelDataIds.ID_SCHEMA)
                .getAsInt() != BuiltinModelDataTuning.TARGET_GET_AS_INT_THRESHOLD)
                throw new IllegalStateException(BuiltinModelDataDiagnostics.MSG_UNKNOWN_BUILTIN_MODEL_SCHEMA);
            for (var packElement : root.getAsJsonArray(BuiltinModelDataIds.ID_PACKS)) {
                var pack = packElement.getAsJsonObject();
                String packId = pack.get(BuiltinModelDataIds.ID_ID)
                    .getAsString();
                for (var modelElement : pack.getAsJsonArray(BuiltinModelDataIds.ID_MODELS)) {
                    var choice = new Choice(packId, modelElement.getAsJsonObject());
                    if (loaded.put(choice.getId(), choice) != null) throw new IllegalStateException(
                        BuiltinModelDataDiagnostics.MSG_DUPLICATE_BUILTIN_MODEL_ID + choice.getId());
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException(BuiltinModelDataDiagnostics.MSG_CANNOT_READ_TLMNH_BUILTIN_MODEL, error);
        }
        if (!loaded.containsKey(BuiltinModelDataIds.ID_TOUHOU_LITTLE_MAID_HAKUREI_REIMU))
            throw new IllegalStateException(BuiltinModelDataDiagnostics.MSG_MISSING_TLM_DEFAULT_MAID_MODEL);
        long winefoxChoices = loaded.values()
            .stream()
            .filter(Choice::isWinefox)
            .count();
        if (loaded.size() != BuiltinModelDataTuning.TARGET_SIZE_THRESHOLD
            || winefoxChoices != BuiltinModelDataTuning.TARGET_WINEFOX_CHOICES_THRESHOLD)
            throw new IllegalStateException(BuiltinModelDataDiagnostics.MSG_UNEXPECTED_BUILTIN_MODEL_INVENTORY);
        BY_ID.putAll(loaded);
        choices = Collections.unmodifiableList(new ArrayList<>(loaded.values()));
        ModContext.LOG
            .info(BuiltinModelDataDiagnostics.MSG_TLMNH_MODEL_CATALOG_READY_CHOICES, choices.size(), winefoxChoices);
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
            this.id = json.get(BuiltinModelDataIds.ID_ID)
                .getAsString();
            this.baseId = json.get(BuiltinModelDataIds.ID_BASE_ID)
                .getAsString();
            this.packId = packId;
            this.name = json.get(BuiltinModelDataIds.ID_NAME)
                .getAsString();
            var source = json.getAsJsonObject(BuiltinModelDataIds.ID_SOURCE_INFO);
            var egg = source.getAsJsonObject(BuiltinModelDataIds.ID_EASTER_EGG);
            this.easterEggTag = egg != null && egg.has(BuiltinModelDataIds.ID_TAG) ? egg.get(BuiltinModelDataIds.ID_TAG)
                .getAsString() : "";
            this.easterEggEncrypted = egg != null && egg.has(BuiltinModelDataIds.ID_ENCRYPT)
                && egg.get(BuiltinModelDataIds.ID_ENCRYPT)
                    .getAsBoolean();
            this.preferredSoundPack = source.has(BuiltinModelDataIds.ID_USE_SOUND_PACK_ID)
                && !source.get(BuiltinModelDataIds.ID_USE_SOUND_PACK_ID)
                    .isJsonNull() ? source.get(BuiltinModelDataIds.ID_USE_SOUND_PACK_ID)
                        .getAsString() : "";
            this.showBackpack = !source.has(BuiltinModelDataIds.ID_SHOW_BACKPACK)
                || source.get(BuiltinModelDataIds.ID_SHOW_BACKPACK)
                    .getAsBoolean();
            this.showCustomHead = !source.has(BuiltinModelDataIds.ID_SHOW_CUSTOM_HEAD)
                || source.get(BuiltinModelDataIds.ID_SHOW_CUSTOM_HEAD)
                    .getAsBoolean();
            this.geometry = new ResourceLocation(
                json.get(BuiltinModelDataIds.ID_GEOMETRY)
                    .getAsString());
            this.texture = new ResourceLocation(
                json.get(BuiltinModelDataIds.ID_TEXTURE)
                    .getAsString());
            this.gecko = json.get(BuiltinModelDataIds.ID_GECKO)
                .getAsBoolean();
            this.entityScale = json.get(BuiltinModelDataIds.ID_ENTITY_SCALE)
                .getAsFloat();
            this.itemScale = json.get(BuiltinModelDataIds.ID_ITEM_SCALE)
                .getAsFloat();
            var animationList = new ArrayList<ResourceLocation>();
            for (var animation : json.getAsJsonArray(BuiltinModelDataIds.ID_ANIMATIONS))
                animationList.add(new ResourceLocation(animation.getAsString()));
            this.animations = Collections.unmodifiableList(animationList);
            var descriptionList = new ArrayList<String>();
            for (var line : json.getAsJsonArray(BuiltinModelDataIds.ID_DESCRIPTION))
                descriptionList.add(line.getAsString());
            this.description = Collections.unmodifiableList(descriptionList);
        }

        private Choice() {
            id = baseId = BuiltinModelDataIds.ID_TOUHOU_LITTLE_MAID_EASTER_EGG_MODEL;
            packId = BuiltinModelDataIds.ID_TOUHOU_LITTLE_MAID;
            name = BuiltinModelDataIds.ID_GUI_TOUHOU_LITTLE_MAID_MODEL_GUI_EASTER_EGG_NORMA;
            preferredSoundPack = easterEggTag = "";
            easterEggEncrypted = false;
            geometry = new ResourceLocation(BuiltinModelDataIds.MODEL_JSON_EASTER_EGG_MODEL);
            texture = new ResourceLocation(BuiltinModelDataIds.TEXTURE_EASTER_EGG_MODEL);
            animations = Collections.emptyList();
            description = Collections.emptyList();
            gecko = false;
            showBackpack = showCustomHead = true;
            entityScale = itemScale = BuiltinModelDataTuning.TARGET_GET_AS_INT_THRESHOLD;
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
            return baseId.contains(BuiltinModelDataIds.ID_WINEFOX) || baseId.contains(BuiltinModelDataIds.ID_WINE_FOX);
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
