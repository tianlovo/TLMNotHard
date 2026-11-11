package com.tianluoqaq.tlmnh.common.protection;

/** 各外部联动特性的熔断单元定义；契约版本号仅在兼容性重验通过后递增 */
public enum ExternalFeature {

    PLAYER_OFFHAND("player_offhand", KnownModIds.BACKHAND),
    SHIELDS("shields", KnownModIds.BATTLEGEAR2, KnownModIds.TCONSTRUCT, KnownModIds.TINKERS_DEFENSE),
    NUTRITION("nutrition", KnownModIds.APPLE_CORE),
    GREGTECH_FOOD("gregtech_food", KnownModIds.GREGTECH),
    PROGRESSION_RECIPES("progression_recipes", KnownModIds.GREGTECH, KnownModIds.DREAMCRAFT),
    ANIMATED_DOORS("animated_doors", KnownModIds.MALISIS_DOORS, KnownModIds.MALISIS_CORE),
    VEHICLES("vehicles", KnownModIds.ET_FUTURUM),
    CABINET_SOUNDS("cabinet_sounds", KnownModIds.ET_FUTURUM),
    SHRINE_SOUNDS("shrine_sounds", KnownModIds.ET_FUTURUM),
    ARROW_GRAVITY("arrow_gravity", KnownModIds.GALACTICRAFT_CORE),
    ITEM_DELIVERY("item_delivery"),
    BEHAVIOR_RUNTIME("behavior_runtime"),
    CLIENT_RENDER_STATE("client_render_state", true, KnownModIds.ANGELICA),
    CLIENT_SLOT_ICONS("client_slot_icons", true, KnownModIds.BATTLEGEAR2),
    CLIENT_HEAD_ITEMS("client_head_items", true, KnownModIds.ET_FUTURUM),
    CLIENT_TOMBSTONE("client_tombstone", true),
    CLIENT_MAID_BED("client_maid_bed", true),
    CLIENT_SNACK_CABINET("client_snack_cabinet", true),
    CLIENT_SHRINE("client_shrine", true),
    CLIENT_BANNER("client_banner", true, KnownModIds.ET_FUTURUM),
    CLIENT_TOMBSTONE_DIMENSION_TEXTURE("client_tombstone_dimension_texture", true, KnownModIds.TWILIGHT_FOREST);

    public final String id;
    public final int contractRevision = 1;
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
        return "feature.tlmnh.protection." + id;
    }
}
