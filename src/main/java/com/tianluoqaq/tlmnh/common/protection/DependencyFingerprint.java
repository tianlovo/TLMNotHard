package com.tianluoqaq.tlmnh.common.protection;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import cpw.mods.fml.common.Loader;

/** 按进程缓存已加载模组的源文件 SHA-256 指纹，供存档账本与特定提供方校验复用 */
public final class DependencyFingerprint {

    private static final Map<String, String> CACHE = new HashMap<>();

    private DependencyFingerprint() {}

    public static synchronized String mod(String id) {
        if (CACHE.containsKey(id)) return CACHE.get(id);
        var container = Loader.instance()
            .getIndexedModList()
            .get(id);
        if (container == null) return ProtectionIds.ID_ABSENT;
        var source = container.getSource();
        String fingerprint;
        if (source != null && source.isFile()) {
            try {
                fingerprint = source(source, container.getVersion());
            } catch (Exception failure) {
                IntegrationGuard.report(ProtectionIds.ID_DEPENDENCY + id, failure);
                fingerprint = ProtectionIds.ID_UNREADABLE_2;
            }
        } else fingerprint = ProtectionIds.ID_DEVELOPMENT_DIRECTORY + container.getVersion();
        CACHE.put(id, fingerprint);
        return fingerprint;
    }

    public static String source(File source, String version) throws Exception {
        return version + ProtectionIds.REVIEWED_ARTIFACT_REVIEWED + Sha256Hex.ofFile(source);
    }

    public static String item(String registryName) {
        int colon = registryName.indexOf(':');
        String owner = colon < 0 ? ProtectionIds.ID_MINECRAFT
            : registryName.substring(ProtectionTuning.SUBSTRING_START_OFFSET, colon);
        return owner.equals(ProtectionIds.ID_MINECRAFT) || owner.equals(ProtectionIds.ID_TLMNH) ? "" : mod(owner);
    }
}
