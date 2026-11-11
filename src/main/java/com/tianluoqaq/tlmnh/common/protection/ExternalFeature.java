package com.tianluoqaq.tlmnh.common.protection;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;

/** 各外部联动特性的熔断单元定义；契约版本号仅在兼容性重验通过后递增 */
public enum ExternalFeature {

    PLAYER_OFFHAND(ProtectionIds.ID_PLAYER_OFFHAND, KnownModIds.BACKHAND),
    SHIELDS(ProtectionIds.ID_SHIELDS, KnownModIds.BATTLEGEAR2, KnownModIds.TCONSTRUCT, KnownModIds.TINKERS_DEFENSE),
    NUTRITION(ProtectionIds.ID_NUTRITION, KnownModIds.APPLE_CORE),
    GREGTECH_FOOD(ProtectionIds.ID_GREGTECH_FOOD, KnownModIds.GREGTECH),
    PROGRESSION_RECIPES(ProtectionIds.ID_PROGRESSION_RECIPES, KnownModIds.GREGTECH, KnownModIds.DREAMCRAFT),
    ANIMATED_DOORS(ProtectionIds.ID_ANIMATED_DOORS, KnownModIds.MALISIS_DOORS, KnownModIds.MALISIS_CORE),
    VEHICLES(ProtectionIds.ID_VEHICLES, KnownModIds.ET_FUTURUM),
    CABINET_SOUNDS(ProtectionIds.ID_CABINET_SOUNDS, KnownModIds.ET_FUTURUM),
    SHRINE_SOUNDS(ProtectionIds.ID_SHRINE_SOUNDS, KnownModIds.ET_FUTURUM),
    ARROW_GRAVITY(ProtectionIds.ID_ARROW_GRAVITY, KnownModIds.GALACTICRAFT_CORE),
    ITEM_DELIVERY(ProtectionIds.ID_ITEM_DELIVERY),
    BEHAVIOR_RUNTIME(ProtectionIds.ID_BEHAVIOR_RUNTIME),
    CLIENT_RENDER_STATE(ProtectionIds.ID_CLIENT_RENDER_STATE, true, KnownModIds.ANGELICA),
    CLIENT_SLOT_ICONS(ProtectionIds.ID_CLIENT_SLOT_ICONS, true, KnownModIds.BATTLEGEAR2),
    CLIENT_HEAD_ITEMS(ProtectionIds.ID_CLIENT_HEAD_ITEMS, true, KnownModIds.ET_FUTURUM),
    CLIENT_TOMBSTONE(ProtectionIds.ID_CLIENT_TOMBSTONE, true),
    CLIENT_MAID_BED(ProtectionIds.ID_CLIENT_MAID_BED, true),
    CLIENT_SNACK_CABINET(ProtectionIds.ID_CLIENT_SNACK_CABINET, true),
    CLIENT_SHRINE(ProtectionIds.ID_CLIENT_SHRINE, true),
    CLIENT_BANNER(ProtectionIds.ID_CLIENT_BANNER, true, KnownModIds.ET_FUTURUM),
    CLIENT_TOMBSTONE_DIMENSION_TEXTURE(ProtectionIds.ID_CLIENT_TOMBSTONE_DIMENSION_TEXTURE, true,
        KnownModIds.TWILIGHT_FOREST);

    public final String id;
    public final int contractRevision = ProtectionTuning.COMPATIBILITY_LEDGER_CHECK_UNIT_STEP;
    public final boolean clientOnly;
    private final String[] dependencies;

    ExternalFeature(String id, String... dependencies) {
        this(id, false, dependencies);
    }

    ExternalFeature(String id, boolean clientOnly, String... dependencies) {
        this.id = id;
        this.clientOnly = clientOnly;
        this.dependencies = dependencies;
    }

    public String fingerprint() {
        var result = new StringBuilder();
        for (var dependency : dependencies) result.append(dependency)
            .append('=')
            .append(DependencyFingerprint.mod(dependency))
            .append(';');
        return result.toString();
    }

    public String translationKey() {
        return ProtectionIds.I18N_FEATURE_TLMNH_PROTECTION + id;
    }
}
