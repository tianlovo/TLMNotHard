package com.tianluoqaq.tlmnh.common.development;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** 绑定实际Class资源URL与同一CodeSource的物理条目，不推测MR版本或变换后定义 */
public final class PackagedClassResourceResolver {

    private PackagedClassResourceResolver() {}

    public static final class Binding {

        public final String logicalEntry, physicalEntry, sha256;
        public final int multiReleaseVersion;

        private Binding(String logicalEntry, String physicalEntry, int multiReleaseVersion, byte[] bytes)
            throws Exception {
            this.logicalEntry = logicalEntry;
            this.physicalEntry = physicalEntry;
            this.multiReleaseVersion = multiReleaseVersion;
            var result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256")
                .digest(bytes)) {
                result.append(Character.forDigit((value & 255) >>> 4, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            sha256 = result.toString();
        }
    }

    public static Path artifactOf(Class<?> type) {
        if (type == null) throw new IllegalArgumentException("Packaged class required");
        String logical = type.getName()
            .replace('.', '/') + ".class";
        try {
            var source = type.getProtectionDomain()
                .getCodeSource();
            require(source != null, type, logical, "<unresolved>", "Class CodeSource missing");
            Path artifact = localArtifact(source.getLocation());
            require(Files.isRegularFile(artifact), type, logical, "<unresolved>", "Packaged artifact missing");
            return artifact;
        } catch (ResourceIdentityFailure failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            throw failure(
                type,
                logical,
                "<unresolved>",
                "CodeSource resolution failed (" + failure.getClass()
                    .getSimpleName() + ")");
        }
    }

    public static Binding resolve(Class<?> type, Path expectedArtifact) {
        if (type == null || expectedArtifact == null)
            throw new IllegalArgumentException("Packaged class and artifact required");
        String logical = type.getName()
            .replace('.', '/') + ".class";
        String physical = "<unresolved>";
        try {
            Path expected = expectedArtifact.toRealPath();
            require(Files.isRegularFile(expected), type, logical, physical, "Packaged artifact missing");
            require(
                artifactOf(type).equals(expected),
                type,
                logical,
                physical,
                "Class CodeSource differs from expected artifact");
            URL resource = type.getResource('/' + logical);
            require(resource != null, type, logical, physical, "Class resource URL missing");
            var opened = resource.openConnection();
            require(
                opened instanceof JarURLConnection,
                type,
                logical,
                physical,
                "Class resource URL is not a JAR connection");
            var connection = (JarURLConnection) opened;
            connection.setUseCaches(false);
            require(
                localArtifact(connection.getJarFileURL()).equals(expected),
                type,
                logical,
                physical,
                "Resource URL shadows Class CodeSource");
            try (JarFile resourceJar = connection.getJarFile(); var packed = new ZipFile(expected.toFile())) {
                require(
                    Paths.get(resourceJar.getName())
                        .toRealPath()
                        .equals(expected),
                    type,
                    logical,
                    physical,
                    "Resource connection opened a different artifact");
                JarEntry selected = connection.getJarEntry();
                require(selected != null, type, logical, physical, "Selected resource entry missing");
                // 运行于Java25，通过反射保留Java8链接面；不以entryName或最高版本代替实际realName
                Object resolved = JarEntry.class.getMethod("getRealName")
                    .invoke(selected);
                require(resolved instanceof String, type, logical, physical, "Selected physical entry unavailable");
                physical = (String) resolved;
                int version = entryVersion(type, logical, physical, packed);
                var entry = packed.getEntry(physical);
                require(
                    entry != null && !entry.isDirectory(),
                    type,
                    logical,
                    physical,
                    "Physical entry missing from CodeSource");
                try (var actual = connection.getInputStream(); var saved = packed.getInputStream(entry)) {
                    byte[] resourceBytes = read(actual), packedBytes = read(saved);
                    require(
                        Arrays.equals(resourceBytes, packedBytes),
                        type,
                        logical,
                        physical,
                        "Class resource bytes differ from physical CodeSource entry");
                    return new Binding(logical, physical, version, resourceBytes);
                }
            }
        } catch (ResourceIdentityFailure failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            // 底层URL/文件异常可能带本机路径，只输出类型与相对条目
            throw failure(
                type,
                logical,
                physical,
                "Resource resolution failed (" + failure.getClass()
                    .getSimpleName() + ")");
        }
    }

    private static int entryVersion(Class<?> type, String logical, String physical, ZipFile artifact) throws Exception {
        if (logical.equals(physical)) return 0;
        String prefix = "META-INF/versions/";
        require(physical.startsWith(prefix), type, logical, physical, "Physical entry does not name this class");
        int slash = physical.indexOf('/', prefix.length());
        require(
            slash > prefix.length() && physical.substring(slash + 1)
                .equals(logical),
            type,
            logical,
            physical,
            "Physical MR class path differs");
        String versionText = physical.substring(prefix.length(), slash);
        require(versionText.matches("[1-9][0-9]*"), type, logical, physical, "Invalid physical MR version");
        int version = Integer.parseInt(versionText);
        String javaVersion = System.getProperty("java.specification.version");
        int javaMajor = Integer.parseInt(javaVersion.startsWith("1.") ? javaVersion.substring(2) : javaVersion);
        require(
            version >= 9 && version <= javaMajor,
            type,
            logical,
            physical,
            "Physical MR version outside actual Java runtime");
        var manifestEntry = artifact.getEntry("META-INF/MANIFEST.MF");
        require(manifestEntry != null, type, logical, physical, "MR entry has no manifest");
        try (var input = artifact.getInputStream(manifestEntry)) {
            var manifest = new Manifest(input);
            require(
                "true".equalsIgnoreCase(
                    manifest.getMainAttributes()
                        .getValue("Multi-Release")),
                type,
                logical,
                physical,
                "Manifest does not permit MR resources");
        }
        return version;
    }

    private static Path localArtifact(URL location) throws Exception {
        if ("jar".equals(location.getProtocol())) {
            var connection = location.openConnection();
            if (!(connection instanceof JarURLConnection))
                throw new IllegalArgumentException("Unsupported CodeSource JAR URL");
            location = ((JarURLConnection) connection).getJarFileURL();
        }
        if (!"file".equals(location.getProtocol())) throw new IllegalArgumentException("Local artifact required");
        return Paths.get(location.toURI())
            .toRealPath();
    }

    private static byte[] read(InputStream input) throws Exception {
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }

    private static void require(boolean condition, Class<?> type, String logical, String physical, String reason) {
        if (!condition) throw failure(type, logical, physical, reason);
    }

    private static ResourceIdentityFailure failure(Class<?> type, String logical, String physical, String reason) {
        String safePhysical = physical.matches("[A-Za-z0-9_$/.-]+") && !physical.startsWith("/")
            && !physical.contains("..") ? physical : "<unresolved>";
        return new ResourceIdentityFailure(
            reason + "; class=" + type.getName() + "; logical=" + logical + "; physical=" + safePhysical);
    }

    private static final class ResourceIdentityFailure extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        ResourceIdentityFailure(String message) {
            super(message);
        }
    }
}
