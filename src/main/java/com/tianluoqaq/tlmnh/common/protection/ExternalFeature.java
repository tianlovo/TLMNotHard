package com.tianluoqaq.tlmnh.common.protection;

/** 每个外部边界有独立熔断状态；合同修订只能在重新验证兼容性后提高 */
public enum ExternalFeature {

    PLAYER_OFFHAND("player_offhand", "backhand"),
    SHIELDS("shields", "battlegear2", "TConstruct", "tinkersdefense"),
    NUTRITION("nutrition", "AppleCore"),
    GREGTECH_FOOD("gregtech_food", "gregtech"),
    ANIMATED_DOORS("animated_doors", "malisisdoors", "malisiscore"),
    VEHICLES("vehicles", "etfuturum"),
    ARROW_GRAVITY("arrow_gravity", "GalacticraftCore");

    public final String id;
    public final int contractRevision = 1;
    private final String[] dependencies;

    ExternalFeature(String id, String... dependencies) {
        this.id = id;
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
