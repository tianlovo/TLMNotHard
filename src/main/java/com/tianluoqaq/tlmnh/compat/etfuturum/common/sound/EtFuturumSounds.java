package com.tianluoqaq.tlmnh.compat.etfuturum.common.sound;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** EFR的现代声音资源域由其Reference维护，不复制Mojang音频或固化资源版本 */
public final class EtFuturumSounds {

    private static String namespace;

    private EtFuturumSounds() {}

    public static String barrel(boolean opening) {
        return IntegrationGuard.call(ExternalFeature.CABINET_SOUNDS, () -> {
            if (namespace == null) {
                Object value = Class.forName("ganymedes01.etfuturum.lib.Reference")
                    .getField("MCAssetVer")
                    .get(null);
                if (!(value instanceof String text) || !text.matches("[a-z0-9_.-]+"))
                    throw new IllegalStateException("EFR modern sound resource namespace changed");
                namespace = text;
            }
            return namespace + (opening ? ":block.barrel.open" : ":block.barrel.close");
        }, () -> opening ? "random.chestopen" : "random.chestclosed");
    }
}
