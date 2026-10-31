package com.tianluoqaq.tlmnh.common.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import com.google.gson.JsonObject;

/** 开发探针的实际CodeSource与资源身份，不把资源字节冒充变换后类定义 */
public final class DevelopmentArtifactIdentity {

    private DevelopmentArtifactIdentity() {}

    public static JsonObject capture(Class<?>... types) throws Exception {
        if (!Boolean.getBoolean("tlmnh.devCommands") || types.length == 0)
            throw new IllegalStateException("Explicit packaged development classes required");
        var identity = new JsonObject();
        var classes = new JsonObject();
        var bindings = new JsonObject();
        Path artifact = null;
        for (Class<?> type : types) {
            Path file = PackagedClassResourceResolver.artifactOf(type);
            if (artifact == null) artifact = file;
            require(artifact.equals(file), "Classes came from different artifacts; class=" + type.getName());
            var binding = PackagedClassResourceResolver.resolve(type, file);
            classes.addProperty(type.getName(), binding.sha256);
            var detail = new JsonObject();
            detail.addProperty("logicalEntry", binding.logicalEntry);
            detail.addProperty("physicalEntry", binding.physicalEntry);
            detail.addProperty("multiReleaseVersion", binding.multiReleaseVersion);
            bindings.add(type.getName(), detail);
        }
        identity.addProperty(
            "artifactFile",
            artifact.getFileName()
                .toString());
        identity.addProperty("artifactSha256", sha(Files.readAllBytes(artifact)));
        identity.addProperty("javaSpecificationVersion", System.getProperty("java.specification.version"));
        identity.addProperty("transformedDefinitionBytesVerified", false);
        identity.add("classResources", classes);
        identity.add("classResourceBindings", bindings);
        return identity;
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
