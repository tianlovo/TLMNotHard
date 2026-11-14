package com.tianluoqaq.tlmnh.compat.botania.development.shedding;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Public resource APIs observe the selected physical entry without defining the provider class. */
public final class BotaniaProviderResource {

    public static final String PROVIDER = "vazkii.botania.common.core.handler.SheddingHandler";
    public static final String LOGICAL_ENTRY = "vazkii/botania/common/core/handler/SheddingHandler.class";
    public static final String SELECTED_ENTRY = "META-INF/versions/21/" + LOGICAL_ENTRY;
    public static final String SELECTED_SHA = "dcd7aeb28d5c70ce90addbf1e5ea8ff3aa3211bec30f255324fd9f114d5a3353";
    public static final String BASE_SHA = "d08c39f90c184884586a73d90c19324e9aee18bddfb6fc81ca10df9dcb0acd9f";
    public static final String JAR_SHA = "43d34830b1c32384f124c0e18fa66e848454dd3d6e585c1e7b5a9f143c364c5e";
    public static final String LOADER_JAR_SHA = "4a772fa88485448ecd8b6a82b426523b2058b6e901d2d766e8d9f62113fde73e";
    public static final String LOADER_ENTRY = "net/minecraft/launchwrapper/LaunchClassLoader.class";
    public static final String LOADER_SHA = "4a45120f1a2e82e92486c264db11dddcfa3571c505778dda552c9c9ee6e59ed3";

    private BotaniaProviderResource() {}

    public static void requireRuntimeInputs() {
        check("25".equals(System.getProperty("java.specification.version")), "JAVA25_REQUIRED");
        check("21".equals(System.getProperty("jdk.util.jar.version")), "JAR_VERSION21_REQUIRED");
        String enabled = System.getProperty("jdk.util.jar.enableMultiRelease");
        check(enabled == null || "true".equals(enabled), "MULTI_RELEASE_OVERRIDE_REJECTED");
    }

    public static Path sourceFile(URL location) throws Exception {
        check(location != null, "SOURCE_URL_NULL");
        if ("jar".equals(location.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) location.openConnection();
            connection.setUseCaches(false);
            location = connection.getJarFileURL();
        }
        check(
            "file".equals(location.getProtocol()) && location.getQuery() == null && location.getRef() == null,
            "SOURCE_URL_NOT_LOCAL_FILE");
        Path result = Paths.get(location.toURI())
            .toRealPath();
        check(Files.isRegularFile(result), "SOURCE_NOT_REGULAR_FILE");
        return result;
    }

    public static void verifyRuntimeLoader(URLClassLoader loader) throws Exception {
        requireRuntimeInputs();
        check(
            loader.getClass()
                .getName()
                .equals("net.minecraft.launchwrapper.LaunchClassLoader"),
            "RFB_LOADER_CLASS_MISMATCH");
        Path jar = sourceFile(
            loader.getClass()
                .getProtectionDomain()
                .getCodeSource()
                .getLocation());
        check(loaderJarMatches(jar), "RFB_LOADER_JAR_HASH_MISMATCH");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            check(
                LOADER_SHA.equals(hash(read(zip.getInputStream(zip.getEntry(LOADER_ENTRY))))),
                "RFB_LOADER_ENTRY_HASH_MISMATCH");
        }
    }

    private static boolean loaderJarMatches(Path file) throws Exception {
        return LOADER_JAR_SHA.equals(hash(Files.readAllBytes(file)));
    }

    /** This also supports ordinary URLClassLoader checks, while runtime callers separately pin RFB. */
    public static Selection inspect(URL resource, Path expectedJar) throws Exception {
        requireRuntimeInputs();
        check(resource != null, "LATE_RESOURCE_NULL");
        check("jar".equals(resource.getProtocol()), "LATE_RESOURCE_NOT_JAR");
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.setUseCaches(false);
        try (java.util.jar.JarFile resourceJar = connection.getJarFile()) {
            Path actualJar = sourceFile(connection.getJarFileURL());
            check(actualJar.equals(expectedJar.toRealPath()), "LATE_RESOURCE_JAR_PATH_MISMATCH");
            check(JAR_SHA.equals(hash(Files.readAllBytes(actualJar))), "LATE_RESOURCE_JAR_HASH_MISMATCH");
            JarEntry entry = connection.getJarEntry();
            check(entry != null, "LATE_RESOURCE_ENTRY_NULL");
            String realName = (String) JarEntry.class.getMethod("getRealName")
                .invoke(entry);
            check(
                SELECTED_ENTRY.equals(connection.getEntryName()) || SELECTED_ENTRY.equals(realName),
                "LATE_RESOURCE_PHYSICAL_ENTRY_MISMATCH actual=" + connection.getEntryName() + " real=" + realName);
            byte[] selected;
            try (InputStream input = connection.getInputStream()) {
                selected = read(input);
            }
            check(
                selected.length == 7687 && selected.length >= 8
                    && (selected[6] & 255) == 0
                    && (selected[7] & 255) == 65,
                "LATE_RESOURCE_RAW_MAJOR_OR_SIZE_MISMATCH");
            check(SELECTED_SHA.equals(hash(selected)), "LATE_RESOURCE_SELECTED_HASH_MISMATCH");
            try (ZipFile zip = new ZipFile(actualJar.toFile())) {
                Manifest manifest;
                try (InputStream input = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))) {
                    manifest = new Manifest(input);
                }
                check(
                    "true".equals(
                        manifest.getMainAttributes()
                            .getValue("Multi-Release"))
                        && "1.3.5".equals(
                            manifest.getMainAttributes()
                                .getValue("JvmDowngrader-Version")),
                    "LATE_RESOURCE_MULTI_RELEASE_MANIFEST_MISMATCH");
                try (InputStream input = zip.getInputStream(zip.getEntry(SELECTED_ENTRY))) {
                    check(SELECTED_SHA.equals(hash(read(input))), "SELECTED_PHYSICAL_ZIP_ENTRY_MISMATCH");
                }
                try (InputStream input = zip.getInputStream(zip.getEntry(LOGICAL_ENTRY))) {
                    check(BASE_SHA.equals(hash(read(input))), "HISTORICAL_BASE_ENTRY_MISMATCH");
                }
            }
            return new Selection(actualJar, resource.toExternalForm(), realName, selected);
        }
    }

    public static byte[] read(InputStream input) throws Exception {
        check(input != null, "RESOURCE_STREAM_NULL");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[8192];
        for (int n; (n = input.read(block)) >= 0;) {
            check(output.size() + n <= 1048576, "RESOURCE_TOO_LARGE");
            output.write(block, 0, n);
        }
        return output.toByteArray();
    }

    public static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    public static final class Selection {

        public final Path jar;
        public final String resourceUrl;
        public final String realEntry;
        public final byte[] rawBytes;

        Selection(Path jar, String resourceUrl, String realEntry, byte[] rawBytes) {
            this.jar = jar;
            this.resourceUrl = resourceUrl;
            this.realEntry = realEntry;
            this.rawBytes = rawBytes.clone();
        }
    }
}
