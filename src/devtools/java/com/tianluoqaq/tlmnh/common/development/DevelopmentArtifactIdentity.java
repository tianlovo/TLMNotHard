package com.tianluoqaq.tlmnh.common.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.protection.Sha256Hex;

/** 采集开发探针的实际 CodeSource 与资源身份，不拿资源字节冒充变换后的类定义 */
public final class DevelopmentArtifactIdentity {

    private DevelopmentArtifactIdentity() {}

    public static JsonObject capture(Class<?>... types) throws Exception {
        return captureWithAnchors(TLMNotHard.class, DevelopmentArtifactIdentity.class, types);
    }

    static JsonObject captureWithAnchors(Class<?> primaryAnchor, Class<?> devtoolsAnchor, Class<?>... types)
        throws Exception {
        if (primaryAnchor == null || devtoolsAnchor == null)
            throw new IllegalArgumentException("Primary and devtools anchor classes required");
        if (!DevelopmentFlags.isDevCommandsEnabled() || types == null || types.length == 0)
            throw new IllegalStateException("Explicit packaged development classes required");
        var identity = new JsonObject();
        var classes = new JsonObject();
        var bindings = new JsonObject();
        var distinct = new LinkedHashSet<Path>();
        for (Class<?> type : types) {
            require(type != null, "Explicit packaged development classes required");
            Path file = PackagedClassResourceResolver.artifactOf(type);
            distinct.add(file);
            require(distinct.size() <= 2, "Classes came from more than two artifacts; class=" + type.getName());
            var binding = PackagedClassResourceResolver.resolve(type, file);
            classes.addProperty(type.getName(), binding.sha256);
            var detail = new JsonObject();
            detail.addProperty("logicalEntry", binding.logicalEntry);
            detail.addProperty("physicalEntry", binding.physicalEntry);
            detail.addProperty("multiReleaseVersion", binding.multiReleaseVersion);
            bindings.add(type.getName(), detail);
        }
        Path primary = distinct.iterator()
            .next();
        Path pairedDevtools = null;
        if (distinct.size() == 2) {
            // 双包场景只放行“tlmnh 主包 + 当前 devtools 包”组合，并固定由主包承担 artifactFile/artifactSha256
            Path expectedPrimary = PackagedClassResourceResolver.artifactOf(primaryAnchor);
            Path expectedDevtools = PackagedClassResourceResolver.artifactOf(devtoolsAnchor);
            require(
                !expectedPrimary.equals(expectedDevtools),
                "Paired capture requires distinct primary and devtools artifacts");
            PackagedClassResourceResolver.resolve(primaryAnchor, expectedPrimary);
            PackagedClassResourceResolver.resolve(devtoolsAnchor, expectedDevtools);
            require(
                distinct.contains(expectedPrimary) && distinct.contains(expectedDevtools),
                "Classes came from unauthorized artifact pair");
            primary = expectedPrimary;
            pairedDevtools = expectedDevtools;
        }
        identity.addProperty(
            "artifactFile",
            primary.getFileName()
                .toString());
        identity.addProperty("artifactSha256", sha(Files.readAllBytes(primary)));
        if (pairedDevtools != null) {
            identity.addProperty(
                "devtoolsArtifactFile",
                pairedDevtools.getFileName()
                    .toString());
            identity.addProperty("devtoolsArtifactSha256", sha(Files.readAllBytes(pairedDevtools)));
        }
        identity.addProperty("javaSpecificationVersion", System.getProperty("java.specification.version"));
        identity.addProperty("transformedDefinitionBytesVerified", false);
        identity.add("classResources", classes);
        identity.add("classResourceBindings", bindings);
        return identity;
    }

    private static String sha(byte[] bytes) throws Exception {
        return Sha256Hex.ofBytes(bytes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
