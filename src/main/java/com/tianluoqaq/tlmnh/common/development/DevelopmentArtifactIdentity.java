package com.tianluoqaq.tlmnh.common.development;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.jar.JarFile;

import com.google.gson.JsonObject;

/** 开发探针的实际CodeSource与资源身份，不把资源字节冒充变换后类定义 */
public final class DevelopmentArtifactIdentity {

    private DevelopmentArtifactIdentity() {}

    public static JsonObject capture(Class<?>... types) throws Exception {
        if (!Boolean.getBoolean("tlmnh.devCommands") || types.length == 0)
            throw new IllegalStateException("Explicit packaged development classes required");
        var identity = new JsonObject();
        var classes = new JsonObject();
        Path artifact = null;
        for (Class<?> type : types) {
            var source = type.getProtectionDomain()
                .getCodeSource();
            require(source != null, "Missing actual class CodeSource");
            var location = source.getLocation();
            if ("jar".equals(location.getProtocol())) {
                var connection = location.openConnection();
                require(connection instanceof JarURLConnection, "Unsupported JAR CodeSource");
                location = ((JarURLConnection) connection).getJarFileURL();
            }
            require("file".equals(location.getProtocol()), "Local artifact required");
            Path file = Paths.get(location.toURI())
                .toRealPath();
            require(Files.isRegularFile(file), "Packaged artifact required");
            if (artifact == null) artifact = file;
            require(artifact.equals(file), "Classes came from different artifacts");
            String entryName = type.getName()
                .replace('.', '/') + ".class";
            try (var jar = new JarFile(file.toFile()); var input = type.getResourceAsStream('/' + entryName)) {
                var entry = jar.getJarEntry(entryName);
                require(entry != null && input != null, "Missing actual class resource");
                try (var packed = jar.getInputStream(entry)) {
                    String resourceSha = sha(readBytes(input));
                    require(resourceSha.equals(sha(readBytes(packed))), "Class resource differs from CodeSource");
                    classes.addProperty(type.getName(), resourceSha);
                }
            }
        }
        identity.addProperty(
            "artifactFile",
            artifact.getFileName()
                .toString());
        identity.addProperty("artifactSha256", sha(Files.readAllBytes(artifact)));
        identity.addProperty("javaSpecificationVersion", System.getProperty("java.specification.version"));
        identity.addProperty("transformedDefinitionBytesVerified", false);
        identity.add("classResources", classes);
        return identity;
    }

    private static byte[] readBytes(InputStream input) throws Exception {
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        var result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
