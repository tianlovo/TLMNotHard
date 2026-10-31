package com.tianluoqaq.tlmnh.common.development;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;

/** 核对实际RC2类来源和资源字节，不把class资源摘要冒充变换后类定义摘要 */
public final class MaidGeckoLibraryProbe {

    private static final String JAR_SHA = "9094ae67d01d0ad246f886f11ad557fc2e79c72cbf3feed83e1512a8ae90a74a";
    private static final String[] CLASSES = { "it.unimi.dsi.fastutil.HashCommon",
        "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap",
        "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap$MapIterator" };
    private static final String[] CLASS_SHA = { "3c7762f48ae724d5d805ab064cfce063801e71c73144601481754f95563915a2",
        "032031dd94d94f7def58503e24e1d96741290387c0413ed5384f8c403390d879",
        "3603d9b5327cee94f994a32a7e4b19581f3a736e4bf8cf2444d6665c289c73f6" };

    private MaidGeckoLibraryProbe() {}

    public static void run() {
        TLMNotHard.LOG.info("TLMNH_GECKO_LIBRARY_TEST_PASS {}", capture());
    }

    public static JsonObject capture() {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Development commands disabled");
        var report = new JsonObject();
        var classes = new JsonObject();
        try {
            for (int i = 0; i < CLASSES.length; i++) {
                var type = Class.forName(CLASSES[i], false, MaidGeckoLibraryProbe.class.getClassLoader());
                var source = type.getProtectionDomain()
                    .getCodeSource();
                if (source == null) throw new IllegalStateException("Missing actual library CodeSource");
                var file = Paths.get(
                    source.getLocation()
                        .toURI())
                    .toRealPath();
                if (!Files.isRegularFile(file) || !sha(Files.readAllBytes(file)).equals(JAR_SHA))
                    throw new IllegalStateException("Actual library differs from fixed RC2 fastutil 8.5.18");
                byte[] bytes;
                try (var input = type.getResourceAsStream('/' + CLASSES[i].replace('.', '/') + ".class")) {
                    if (input == null) throw new IllegalStateException("Missing actual library class resource");
                    var output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    bytes = output.toByteArray();
                }
                if (!sha(bytes).equals(CLASS_SHA[i]))
                    throw new IllegalStateException("Actual library class resource differs");
                int major = (bytes[6] & 255) * 256 + (bytes[7] & 255);
                if (major != 52) throw new IllegalStateException("Unexpected actual library class version");
                var entry = new JsonObject();
                entry.addProperty(
                    "codeSourceFile",
                    file.getFileName()
                        .toString());
                entry.addProperty("codeSourceJarSha256", JAR_SHA);
                entry.addProperty("resourceSha256", CLASS_SHA[i]);
                entry.addProperty("resourceMajor", major);
                entry.addProperty(
                    "loaderClass",
                    type.getClassLoader()
                        .getClass()
                        .getName());
                classes.add(CLASSES[i], entry);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot verify actual RC2 Gecko container library", error);
        }
        report.add("classes", classes);
        report.addProperty("runtimeDefinedBytesVerified", false);
        report.addProperty("geometryOrRenderingVerified", false);
        return report;
    }

    private static String sha(byte[] bytes) throws Exception {
        var output = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) output.append(String.format("%02x", value & 255));
        return output.toString();
    }
}
