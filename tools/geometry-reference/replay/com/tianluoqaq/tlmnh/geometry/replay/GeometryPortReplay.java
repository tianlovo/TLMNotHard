package com.tianluoqaq.tlmnh.geometry.replay;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.LegacyVertexConsumer;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.geometry.GeometrySnapshots;

import net.minecraft.util.ResourceLocation;

/** 只在独立工具任务编译，通过生产几何路径重放，不进入分发 JAR */
public final class GeometryPortReplay {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private GeometryPortReplay() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected reference capture, new output, and none|mirror_x|uv_shift");
        Path input = Paths.get(args[0]).toRealPath();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        String fault = args[2];
        require(!Files.exists(output) && !input.equals(output), "Refusing to overwrite geometry evidence");
        require("none".equals(fault) || "mirror_x".equals(fault) || "uv_shift".equals(fault), "Unreviewed geometry fault");
        var reference = new JsonParser().parse(new String(Files.readAllBytes(input), StandardCharsets.UTF_8)).getAsJsonObject();
        require("geometry-reference-v1".equals(reference.get("schema").getAsString()), "Unsupported geometry capture");
        require("native-bind-geometry-v1".equals(reference.get("contract").getAsString()), "Unsupported bind contract");
        var contractBytes = resourceBytes("/tlmnh-geometry/contract.json");
        var contract = new JsonParser().parse(new String(contractBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        require(sha(contractBytes).equals(reference.get("contractSha256").getAsString()), "Different geometry contract");
        String id = reference.get("model").getAsString();
        require(contract.getAsJsonObject("models").has(id), "Unreviewed geometry model");
        BuiltinModelCatalog.load();
        var choice = BuiltinModelCatalog.get(id);
        require(choice != null && id.equals(choice.getId()), "Missing actual production model");
        var result = new JsonObject();
        result.addProperty("schema", "port-geometry-v1");
        result.addProperty("contract", "native-bind-geometry-v1");
        result.addProperty("contractSha256", sha(contractBytes));
        result.addProperty("referenceSha256", sha(Files.readAllBytes(input)));
        result.addProperty("model", id);
        result.addProperty("backend", choice.isGecko() ? "gecko" : "bedrock");
        result.addProperty("coordinateSpace", "port-modelbase-bind");
        result.add("coordinateContract", contract.get("coordinateContract"));
        result.addProperty("fault", fault);
        result.addProperty("geometrySha256", sha(bytes(resource(choice.getGeometry()))));
        result.addProperty("textureSha256", sha(bytes(resource(choice.getTexture()))));
        result.addProperty("worldRendererVerified", false);
        result.addProperty("gpuVerified", false);
        result.addProperty("dynamicAnimationVerified", false);
        result.addProperty("materialAttributesCompared", false);
        var samples = new JsonArray();
        for (int repetition = 0; repetition < 3; repetition++) {
            // New manager forces the production parser to run again, not just a mutable-pose clone
            var model = new BuiltinMaidModels(location -> verifiedResource(location, choice.getGeometry(),
                contract.getAsJsonObject("models").getAsJsonObject(id).get("geometrySha256").getAsString())).create(choice);
            var before = choice.isGecko() ? GeometrySnapshots.gecko(model.getGecko())
                : GeometrySnapshots.bedrock(model.getBedrock().getModelMap());
            var sink = new Sink("uv_shift".equals(fault));
            var poses = new LegacyPoseStack();
            if ("mirror_x".equals(fault)) poses.scale(-1, 1, 1);
            model.renderGeometry(poses, sink, 0x00f000f0);
            var after = choice.isGecko() ? GeometrySnapshots.gecko(model.getGecko())
                : GeometrySnapshots.bedrock(model.getBedrock().getModelMap());
            require(before.equals(after), "Production geometry draw mutated bind pose");
            require(sink.vertices.size() > 0 && sink.vertices.size() % 4 == 0, "Empty/incomplete production quads");
            var sample = new JsonObject();
            sample.addProperty("repetition", repetition);
            sample.addProperty("freshNativeParse", true);
            sample.add("bindTree", before);
            sample.add("vertices", sink.vertices);
            samples.add(sample);
        }
        result.add("samples", samples);
        result.add("identity", identity());
        Files.createDirectories(output.getParent());
        Files.write(output, GSON.toJson(result).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static JsonObject identity() throws Exception {
        Path file = Paths.get(System.getProperty("tlmnh.geometry.buildIdentity")).toRealPath();
        var build = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
        require("port-geometry-build-v1".equals(build.get("schema").getAsString()), "Unsupported port geometry identity");
        var arguments = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        var diagnostic = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        require("25".equals(System.getProperty("java.specification.version"))
            && arguments.contains("-XX:+DisableAttachMechanism") && diagnostic != null
            && "true".equals(diagnostic.getVMOption("DisableAttachMechanism").getValue()), "Port Java 25 with attach disabled required");
        require(arguments.stream().noneMatch(value -> value.startsWith("-agentlib:") || value.startsWith("-agentpath:")), "Unknown native observer");
        var observers = new java.util.ArrayList<String>();
        for (String argument : arguments) if (argument.startsWith("-javaagent:")) observers.add(argument);
        require(observers.size() == 1, "Exactly one port geometry observer required");
        String[] observer = observers.get(0).substring("-javaagent:".length()).split("=", 2);
        require(observer.length == 2 && sha(Files.readAllBytes(Paths.get(observer[0]))).equals(build.get("agentSha256").getAsString()), "Unexpected port observer JAR");
        var selected = java.util.Arrays.asList(observer[1].split(","));
        require(selected.size() == new java.util.HashSet<>(selected).size()
            && new java.util.HashSet<>(selected).equals(build.getAsJsonObject("compiledClasses").entrySet().stream()
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())), "Unexpected port observer class list");
        var agent = Class.forName("com.tianluoqaq.tlmnh.geometry.identity.GeometryDefinitionAgent", true, ClassLoader.getSystemClassLoader());
        var startup = (Map<String, String>) agent.getMethod("startup").invoke(null);
        require(sha(Files.readAllBytes(file)).equals(startup.get("manifestSha256")), "Port geometry identity changed after startup");
        require(startup.get("agentSha256").equals(build.get("agentSha256").getAsString()), "Unexpected geometry agent");
        var definitions = new JsonObject();
        for (var entry : build.getAsJsonObject("compiledClasses").entrySet()) {
            var type = Class.forName(entry.getKey(), false, GeometryPortReplay.class.getClassLoader());
            var actual = (Map<String, Object>) agent.getMethod("identity", Class.class).invoke(null, type);
            require(entry.getValue().getAsString().equals(actual.get("sha256")), "Defined production geometry differs: " + entry.getKey());
            definitions.add(entry.getKey(), GSON.toJsonTree(actual));
        }
        var result = new JsonObject();
        result.add("build", build);
        result.add("actualFirstDefinitions", definitions);
        result.addProperty("buildManifestSha256", startup.get("manifestSha256"));
        result.addProperty("runtimePolicy", "geometry-v1-single-passive-observer");
        result.addProperty("scope", "selected production geometry definitions only");
        return result;
    }

    private static InputStream resource(ResourceLocation location) throws java.io.IOException {
        var value = GeometryPortReplay.class.getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath());
        if (value == null) throw new java.io.IOException("Missing production asset: " + location);
        return value;
    }
    private static InputStream verifiedResource(ResourceLocation location, ResourceLocation geometry, String approvedSha)
        throws java.io.IOException {
        if (!location.equals(geometry)) return resource(location);
        byte[] actual = bytes(resource(location));
        try {
            require(sha(actual).equals(approvedSha), "Actual production parser geometry differs from approved bytes");
        } catch (Exception failure) {
            throw new java.io.IOException("Cannot verify the production parser input", failure);
        }
        return new ByteArrayInputStream(actual);
    }
    private static byte[] resourceBytes(String name) throws Exception {
        try (var input = GeometryPortReplay.class.getResourceAsStream(name)) {
            require(input != null, "Missing bind contract"); return bytes(input);
        }
    }
    private static byte[] bytes(InputStream input) throws java.io.IOException {
        try (input) {
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        var result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static final class Sink implements LegacyVertexConsumer {
        final JsonArray vertices = new JsonArray();
        final boolean shiftUv;
        Sink(boolean shiftUv) { this.shiftUv = shiftUv; }
        @Override public void vertex(float x, float y, float z, float red, float green, float blue, float alpha,
            float u, float v, int overlay, int light, float nx, float ny, float nz) {
            require(vertices.size() < 50000, "Excessive production vertices");
            vertices.add(GeometrySnapshots.vector(x,y,z,nx,ny,nz,shiftUv ? u + 0.125F : u,v));
        }
    }
}
