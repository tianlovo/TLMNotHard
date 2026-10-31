package com.tianluoqaq.tlmnh.reference;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 只核选定原版组件与工具，不将资源摘要冒充变换后类定义或整套classpath身份 */
final class MoveReferenceIdentity {
    private static final String BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6";
    private static final String AGENT = "com.tianluoqaq.tlmnh.geometry.identity.GeometryDefinitionAgent";

    private MoveReferenceIdentity() {}

    static JsonObject capture() throws Exception {
        Path path = Path.of(System.getProperty("tlmnh.move.buildIdentity"));
        require(Files.isRegularFile(path) && Files.size(path) <= 4 * 1024 * 1024, "Move build manifest unavailable");
        byte[] bytes = Files.readAllBytes(path);
        var manifest = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        require(manifest.get("schema").getAsString().equals("move-reference-build-v1")
            && manifest.get("status").getAsString().equals("BASE_LOCK_VERIFIED")
            && manifest.get("upstreamCommit").getAsString().equals(BASE), "Move build identity not verified");
        var base = manifest.getAsJsonObject("baseReferenceBuildIdentity");
        require(base.get("schema").getAsString().equals("reference-build-identity-v1")
            && base.get("status").getAsString().equals("LOCK_VERIFIED")
            && base.get("upstreamCommit").getAsString().equals(BASE) && Runtime.version().feature() == 17,
            "Verified original Java 17 base required");
        require(manifest.get("contractSha256").getAsString().equals(sha(resource("contract.json")))
            && manifest.get("identityClassesSha256").getAsString().equals(sha(resource("identity-classes.json"))),
            "Move resources differ from verified build");
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        require(arguments.contains("-XX:+DisableAttachMechanism")
            && arguments.stream().filter(value -> value.startsWith("-javaagent:")).count() == 2
            && arguments.stream().noneMatch(value -> value.startsWith("-agentlib:") || value.startsWith("-agentpath:")),
            "Only base and move read-only observers permitted");
        for (var entry : Map.of("runtimeVersion", System.getProperty("java.runtime.version"),
            "vendor", System.getProperty("java.vendor"), "vmName", System.getProperty("java.vm.name")).entrySet())
            require(base.getAsJsonObject("java").get(entry.getKey()).getAsString().equals(entry.getValue()), "Reference JDK differs");
        Class<?> agent = Class.forName(AGENT, false, ClassLoader.getSystemClassLoader());
        var startup = (Map<?, ?>) agent.getMethod("startup").invoke(null);
        require(startup.get("manifestSha256").equals(sha(bytes))
            && startup.get("agentSha256").equals(manifest.get("definitionAgentSha256").getAsString()), "Move premain binding differs");
        Class<?> baseAgent = Class.forName("com.tianluoqaq.tlmnh.reference.identity.ReferenceIdentityAgent", false,
            ClassLoader.getSystemClassLoader());
        var baseStartup = (Map<?, ?>) baseAgent.getMethod("startup").invoke(null);
        require(baseStartup.get("buildIdentitySha256").equals(manifest.get("baseReferenceBuildIdentitySha256").getAsString())
            && baseStartup.get("agentSha256").equals(base.getAsJsonObject("identityAgent").get("sha256").getAsString()),
            "Original reference observer binding differs");
        require(sha(Files.readAllBytes(Path.of(baseAgent.getProtectionDomain().getCodeSource().getLocation().toURI())))
            .equals(base.getAsJsonObject("identityAgent").get("sha256").getAsString()), "Original observer JAR differs");
        var selected = JsonParser.parseString(new String(resource("identity-classes.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var classes = new JsonObject();
        for (var value : selected.getAsJsonArray("classes")) {
            String name = value.getAsString();
            Class<?> type = Class.forName(name, false, MoveReferenceIdentity.class.getClassLoader());
            var definition = (Map<?, ?>) agent.getMethod("identity", Class.class).invoke(null, type);
            byte[] raw;
            try (var stream = type.getResourceAsStream('/' + name.replace('.', '/') + ".class")) {
                require(stream != null, "Selected move class resource missing"); raw = stream.readAllBytes();
            }
            String resourceSha = sha(raw);
            require(resourceSha.equals(manifest.getAsJsonObject("expectedResources").getAsJsonObject(name)
                .get("resourceSha256").getAsString()), "Selected resource differs from actual build input");
            var item = new JsonObject();
            item.addProperty("resourceSha256", resourceSha);
            item.addProperty("definitionSha256", definition.get("sha256").toString());
            item.addProperty("definitionCount", ((Number) definition.get("count")).intValue());
            item.addProperty("stage", definition.get("stage").toString());
            item.addProperty("loaderClass", definition.get("loaderClass").toString());
            classes.add(name, item);
        }
        var result = new JsonObject();
        result.addProperty("schema", "move-reference-runtime-v1");
        result.addProperty("status", "VERIFIED_SELECTED_COMPONENT_CLASSES");
        result.addProperty("upstreamCommit", BASE);
        result.addProperty("buildIdentitySha256", sha(bytes));
        result.addProperty("baseReferenceBuildIdentitySha256", manifest.get("baseReferenceBuildIdentitySha256").getAsString());
        result.addProperty("agentSha256", startup.get("agentSha256").toString());
        result.add("classes", classes);
        return result;
    }

    static byte[] resource(String name) throws Exception {
        try (var stream = MoveReferenceIdentity.class.getResourceAsStream("/tlmnh-move/" + name)) {
            require(stream != null, "Move resource missing"); return stream.readAllBytes();
        }
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
