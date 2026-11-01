package com.tianluoqaq.tlmnh.common.protection;

import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import cpw.mods.fml.common.Loader;

/** 每进程只读一次依赖源文件摘要，同版本自研代码变更也会使摘要变化 */
public final class DependencyFingerprint {

    private static final Map<String, String> CACHE = new HashMap<>();

    private DependencyFingerprint() {}

    public static synchronized String mod(String id) {
        if (CACHE.containsKey(id)) return CACHE.get(id);
        var container = Loader.instance()
            .getIndexedModList()
            .get(id);
        if (container == null) return "absent";
        var source = container.getSource();
        String fingerprint;
        if (source != null && source.isFile()) {
            try {
                fingerprint = source(source, container.getVersion());
            } catch (Exception failure) {
                IntegrationGuard.report("dependency/" + id, failure);
                fingerprint = "unreadable";
            }
        } else fingerprint = "development-directory:" + container.getVersion();
        CACHE.put(id, fingerprint);
        return fingerprint;
    }

    public static String source(java.io.File source, String version) throws Exception {
        try (var input = new FileInputStream(source)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[65536];
            int count;
            while ((count = input.read(bytes)) >= 0) if (count > 0) digest.update(bytes, 0, count);
            var hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
            return version + ":sha256:" + hex;
        }
    }

    public static String item(String registryName) {
        int colon = registryName.indexOf(':');
        String owner = colon < 0 ? "minecraft" : registryName.substring(0, colon);
        return owner.equals("minecraft") || owner.equals("tlmnh") ? "" : mod(owner);
    }
}
