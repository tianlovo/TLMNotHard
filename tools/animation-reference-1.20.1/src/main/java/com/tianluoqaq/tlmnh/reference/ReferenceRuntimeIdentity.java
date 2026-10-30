package com.tianluoqaq.tlmnh.reference;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.management.HotSpotDiagnosticMXBean;

/** 采集实际白名单定义字节和构建身份，不将磁盘class资源冒充变换后的定义字节 */
public final class ReferenceRuntimeIdentity {

    private static final String BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6";
    private static final String AGENT = "com.tianluoqaq.tlmnh.reference.identity.ReferenceIdentityAgent";

    private ReferenceRuntimeIdentity() {}

    public static Evidence capture() {
        try {
            String configured = System.getProperty("tlmnh.reference.buildIdentity");
            require(configured != null, "Reference build manifest was not configured");
            Path path = Path.of(configured);
            require(Files.isRegularFile(path) && Files.size(path) <= 4 * 1024 * 1024,
                "Reference build manifest is unavailable or too large");
            byte[] bytes = Files.readAllBytes(path);
            String buildSha = hash(bytes);
            var build = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            require(text(build, "schema").equals("reference-build-identity-v1") && text(build, "status").equals("LOCK_VERIFIED"),
                "Reference build manifest has not passed the approved lock");
            require(text(build, "upstreamCommit").equals(BASE), "Reference upstream commit mismatch");
            String patchSha = digestField(build, "patchSha256");
            String probeSha = digestField(build, "probeSourcesSha256");
            digestField(build, "toolchainLockSha256");
            byte[] classListBytes;
            try (var stream = ReferenceRuntimeIdentity.class.getResourceAsStream("/tlmnh-reference/identity-classes.json")) {
                require(stream != null, "Reference identity class list resource is missing");
                classListBytes = stream.readAllBytes();
            }
            require(hash(classListBytes).equals(digestField(build, "identityClassesSha256")), "Reference class list changed after build");
            var spec = JsonParser.parseString(new String(classListBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            require(text(spec, "schema").equals("reference-identity-classes-v1"), "Unsupported reference class list");
            var selected = new TreeSet<String>();
            for (var value : spec.getAsJsonArray("classes")) require(selected.add(value.getAsString()), "Duplicate reference identity class");
            var compiled = build.getAsJsonObject("compiledClasses");
            require(!selected.isEmpty() && selected.equals(compiled.keySet()), "Incomplete compiled reference class manifest");
            var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
            require(arguments.stream().filter(value -> value.startsWith("-javaagent:")).count() == 1,
                "Exactly one reference definition observer agent is required");
            require(arguments.stream().noneMatch(value -> value.startsWith("-agentlib:") || value.startsWith("-agentpath:")),
                "Other startup native agents are outside the verified reference identity");
            var diagnostic = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            require(arguments.contains("-XX:+DisableAttachMechanism") && diagnostic != null
                && diagnostic.getVMOption("DisableAttachMechanism").getValue().equals("true"),
                "Reference dynamic attach must be disabled");
            var java = javaIdentity();
            require(Runtime.version().feature() == 17 && java.equals(build.getAsJsonObject("java")),
                "Reference runtime JDK differs from the verified build JDK");
            Class<?> agent = Class.forName(AGENT, false, ClassLoader.getSystemClassLoader());
            @SuppressWarnings("unchecked")
            var startup = (Map<String, Object>) agent.getMethod("startup").invoke(null);
            require(buildSha.equals(startup.get("buildIdentitySha256")), "Reference manifest changed after premain");
            String agentSha = hash(Files.readAllBytes(Path.of(agent.getProtectionDomain().getCodeSource().getLocation().toURI())));
            require(text(build.getAsJsonObject("identityAgent"), "className").equals(AGENT)
                && agentSha.equals(startup.get("agentSha256")) && agentSha.equals(digestField(build.getAsJsonObject("identityAgent"), "sha256")),
                "Reference definition observer differs from the verified build");
            var classes = new JsonObject();
            for (String name : selected) {
                Class<?> type = Class.forName(name, false, ReferenceRuntimeIdentity.class.getClassLoader());
                @SuppressWarnings("unchecked")
                var actual = (Map<String, Object>) agent.getMethod("identity", Class.class).invoke(null, type);
                String resourceSha;
                try (InputStream resource = type.getResourceAsStream('/' + name.replace('.', '/') + ".class")) {
                    require(resource != null, "Missing reference loaded-class resource: " + name);
                    resourceSha = hash(resource.readAllBytes());
                }
                require(resourceSha.equals(digestField(compiled, name)), "Reference class resource differs from compiled manifest: " + name);
                var entry = new JsonObject();
                entry.addProperty("definitionSha256", actual.get("definitionSha256").toString());
                entry.addProperty("resourceSha256", resourceSha);
                entry.addProperty("loaderClass", actual.get("loaderClass").toString());
                entry.addProperty("definitionCount", ((Number) actual.get("definitionCount")).intValue());
                entry.addProperty("definitionStage", actual.get("stage").toString());
                entry.add("codeSource", safeCodeSource(type));
                classes.add(name, entry);
            }
            var reference = new JsonObject();
            reference.addProperty("schema", "reference-build-identity-ref-v1");
            reference.addProperty("sha256", buildSha);
            var runtime = new JsonObject();
            runtime.addProperty("schema", "reference-runtime-identity-v1");
            runtime.addProperty("status", "VERIFIED");
            runtime.addProperty("buildIdentitySha256", buildSha);
            runtime.addProperty("upstreamCommit", BASE);
            runtime.addProperty("patchSha256", patchSha);
            runtime.addProperty("probeSourcesSha256", probeSha);
            runtime.addProperty("agentSha256", agentSha);
            runtime.addProperty("identityClassesSha256", hash(classListBytes));
            runtime.addProperty("captureScope", "selected-first-defined-classes; not whole-classpath-equivalence");
            runtime.addProperty("definitionObserver", "premain input after defining-loader transformations; sole startup agent; attach disabled");
            runtime.add("java", java);
            runtime.add("classes", classes);
            return new Evidence(reference, runtime);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Reference definition observer rejected a missing or repeated class");
        } catch (Exception error) {
            if (error instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Reference runtime identity could not be verified");
        }
    }

    public static JsonObject status() {
        try {
            return capture().runtimeIdentity();
        } catch (RuntimeException error) {
            var result = new JsonObject();
            result.addProperty("schema", "reference-runtime-identity-v1");
            result.addProperty("status", "UNVERIFIED");
            result.addProperty("error", error.getMessage());
            return result;
        }
    }

    private static JsonObject javaIdentity() {
        var result = new JsonObject();
        result.addProperty("runtimeVersion", System.getProperty("java.runtime.version"));
        result.addProperty("vendor", System.getProperty("java.vendor"));
        result.addProperty("vmName", System.getProperty("java.vm.name"));
        return result;
    }

    private static JsonObject safeCodeSource(Class<?> type) throws Exception {
        var origin = type.getProtectionDomain().getCodeSource();
        require(origin != null, "Missing reference class CodeSource");
        String protocol = origin.getLocation().getProtocol();
        if (protocol.equals("union")) {
            var result = new JsonObject();
            result.addProperty("kind", "modular");
            result.addProperty("protocol", protocol);
            return result;
        }
        require(protocol.equals("file"), "Unsupported reference class CodeSource protocol");
        var path = Path.of(origin.getLocation().toURI());
        var result = new JsonObject();
        if (Files.isRegularFile(path)) {
            result.addProperty("kind", "jar");
            result.addProperty("fileName", path.getFileName().toString());
            result.addProperty("sha256", hash(Files.readAllBytes(path)));
        } else {
            require(Files.isDirectory(path), "Reference class CodeSource is unavailable");
            result.addProperty("kind", "directory");
        }
        return result;
    }

    private static String digestField(JsonObject object, String key) {
        String value = text(object, key);
        require(value.matches("[0-9a-f]{64}"), "Invalid reference identity digest: " + key);
        return value;
    }

    private static String text(JsonObject object, String key) {
        require(object != null && object.has(key) && object.get(key).isJsonPrimitive()
            && object.getAsJsonPrimitive(key).isString(), "Missing reference identity field: " + key);
        return object.get(key).getAsString();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record Evidence(JsonObject buildIdentity, JsonObject runtimeIdentity) {}
}
