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
import com.tianluoqaq.tlmnh.client.render.GeometryBoneObserver;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.geometry.GeometrySnapshots;
import com.tianluoqaq.tlmnh.geometry.GeometryTrace;

import net.minecraft.util.ResourceLocation;

/** 只在独立工具任务编译，通过生产几何路径重放，不进入分发 JAR */
public final class GeometryPortReplay {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private GeometryPortReplay() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected reference capture, new output, and none|mirror_x|uv_shift|corner_swap");
        Path input = Paths.get(args[0]).toRealPath();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        String fault = args[2];
        require(!Files.exists(output) && !input.equals(output), "Refusing to overwrite geometry evidence");
        require("none".equals(fault) || "mirror_x".equals(fault) || "uv_shift".equals(fault) || "corner_swap".equals(fault), "Unreviewed geometry fault");
        var reference = new JsonParser().parse(new String(Files.readAllBytes(input), StandardCharsets.UTF_8)).getAsJsonObject();
        require("geometry-reference-v2".equals(reference.get("schema").getAsString()), "Unsupported geometry capture");
        require("native-bind-geometry-v2".equals(reference.get("contract").getAsString()), "Unsupported bind contract");
        var contractBytes = resourceBytes("/tlmnh-geometry/contract.json");
        var contract = new JsonParser().parse(new String(contractBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        require(sha(contractBytes).equals(reference.get("contractSha256").getAsString()), "Different geometry contract");
        String id = reference.get("model").getAsString();
        require(contract.getAsJsonObject("models").has(id), "Unreviewed geometry model");
        BuiltinModelCatalog.load();
        var choice = BuiltinModelCatalog.get(id);
        require(choice != null && id.equals(choice.getId()), "Missing actual production model");
        var result = new JsonObject();
        result.addProperty("schema", "port-geometry-v2");
        result.addProperty("contract", "native-bind-geometry-v2");
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
        result.addProperty("orderDependentRenderingVerified", false);
        var samples = new JsonArray();
        for (int repetition = 0; repetition < 3; repetition++) {
            // New manager forces the production parser to run again, not just a mutable-pose clone
            var model = new BuiltinMaidModels(location -> verifiedResource(location, choice.getGeometry(),
                contract.getAsJsonObject("models").getAsJsonObject(id).get("geometrySha256").getAsString())).create(choice);
            var plainModel = new BuiltinMaidModels(location -> verifiedResource(location, choice.getGeometry(),
                contract.getAsJsonObject("models").getAsJsonObject(id).get("geometrySha256").getAsString())).create(choice);
            var before = choice.isGecko() ? GeometrySnapshots.gecko(model.getGecko())
                : GeometrySnapshots.bedrock(model.getBedrock().getModelMap());
            var plainBefore = choice.isGecko() ? GeometrySnapshots.gecko(plainModel.getGecko())
                : GeometrySnapshots.bedrock(plainModel.getBedrock().getModelMap());
            require(before.equals(plainBefore), "Fresh production models differ before observation");
            var sink = new Sink(fault, choice.isGecko());
            var plain = new PlainSink(fault);
            var poses = new LegacyPoseStack();
            if ("mirror_x".equals(fault)) poses.scale(-1, 1, 1);
            model.renderGeometry(poses, sink, 0x00f000f0);
            var plainPoses = new LegacyPoseStack();
            if ("mirror_x".equals(fault)) plainPoses.scale(-1, 1, 1);
            plainModel.renderGeometry(plainPoses, plain, 0x00f000f0);
            sink.finish();
            plain.finish();
            var after = choice.isGecko() ? GeometrySnapshots.gecko(model.getGecko())
                : GeometrySnapshots.bedrock(model.getBedrock().getModelMap());
            require(before.equals(after), "Production geometry draw mutated bind pose");
            var plainAfter = choice.isGecko() ? GeometrySnapshots.gecko(plainModel.getGecko())
                : GeometrySnapshots.bedrock(plainModel.getBedrock().getModelMap());
            require(plainBefore.equals(plainAfter), "Plain production draw mutated bind pose");
            require(sink.vertices.size() > 0 && sink.vertices.size() % 4 == 0, "Empty/incomplete production quads");
            require(sink.vertices.equals(plain.vertices), "Production observer changed actual raw vertices");
            require(!"corner_swap".equals(fault) || (sink.swapped && plain.swapped), "No actual face available for corner swap");
            var sample = new JsonObject();
            sample.addProperty("repetition", repetition);
            sample.addProperty("freshNativeParse", true);
            sample.add("bindTree", before);
            sample.add("vertices", sink.vertices);
            sample.add("plainVertices", plain.vertices);
            sample.addProperty("freshPlainParse", true);
            sample.addProperty("observerNeutral", true);
            sample.addProperty("cornerSwapApplied", sink.swapped);
            if (sink.trace != null) {
                sink.trace.finish(sink.vertices.size());
                sink.trace.addTo(sample);
            } else sample.addProperty("mapping", "ordered-bedrock");
            samples.add(sample);
        }
        result.add("samples", samples);
        var firstSample = samples.get(0).getAsJsonObject();
        for (int i = 1; i < samples.size(); i++) {
            var next = samples.get(i).getAsJsonObject();
            require(firstSample.get("vertices").equals(next.get("vertices"))
                && firstSample.get("bindTree").equals(next.get("bindTree")), "Production raw A/A output is unstable");
            if (choice.isGecko()) require(firstSample.get("vertexKeys").equals(next.get("vertexKeys"))
                && firstSample.get("boneFaces").equals(next.get("boneFaces")), "Production actual bone trace is unstable");
        }
        result.addProperty("rawAAStable", true);
        result.addProperty("observerNeutralityVerified", true);
        result.add("identity", identity());
        Files.createDirectories(output.getParent());
        Files.write(output, GSON.toJson(result).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static JsonObject identity() throws Exception {
        Path file = Paths.get(System.getProperty("tlmnh.geometry.buildIdentity")).toRealPath();
        var build = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
        require("port-geometry-build-v2".equals(build.get("schema").getAsString()), "Unsupported port geometry identity");
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
        String coordinate = "it.unimi.dsi:fastutil:8.5.18";
        require("resolved-fastutil-8.5.18-v1".equals(build.get("dependencyPolicy").getAsString()), "Reviewed fastutil dependency policy required");
        var artifacts = build.getAsJsonObject("externalArtifacts");
        require(artifacts.entrySet().size() == 1 && artifacts.has(coordinate), "Exactly one reviewed dependency artifact required");
        var artifact = artifacts.getAsJsonObject(coordinate);
        require("it.unimi.dsi".equals(artifact.get("group").getAsString()) && "fastutil".equals(artifact.get("name").getAsString())
            && "8.5.18".equals(artifact.get("version").getAsString()), "Wrong external dependency coordinates");
        Path dependencyJar = Paths.get(artifact.get("resolvedFile").getAsString()).toRealPath();
        String actualJarSha = sha(Files.readAllBytes(dependencyJar));
        require(Files.isRegularFile(dependencyJar) && dependencyJar.getFileName().toString().equals(artifact.get("fileName").getAsString())
            && actualJarSha.equals(artifact.get("sha256").getAsString()), "Resolved external artifact changed");
        var actualArtifact = new JsonObject();
        actualArtifact.addProperty("group", "it.unimi.dsi");
        actualArtifact.addProperty("name", "fastutil");
        actualArtifact.addProperty("version", "8.5.18");
        actualArtifact.addProperty("fileName", dependencyJar.getFileName().toString());
        actualArtifact.addProperty("resolvedFile", dependencyJar.toString());
        actualArtifact.addProperty("sha256", actualJarSha);
        var actualArtifacts = new JsonObject();
        actualArtifacts.add(coordinate, actualArtifact);
        var external = build.getAsJsonObject("externalClasses");
        var requiredExternal = new java.util.HashSet<>(java.util.Arrays.asList("it.unimi.dsi.fastutil.HashCommon",
            "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap", "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap$MapIterator"));
        require(external.entrySet().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(requiredExternal),
            "Missing/unreviewed external class selection");
        require(build.getAsJsonObject("compiledClasses").entrySet().stream().map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet()).containsAll(requiredExternal), "External classes missing from selected expected bytes");
        var definitions = new JsonObject();
        for (var entry : build.getAsJsonObject("compiledClasses").entrySet()) {
            var type = Class.forName(entry.getKey(), false, GeometryPortReplay.class.getClassLoader());
            var actual = (Map<String, Object>) agent.getMethod("identity", Class.class).invoke(null, type);
            require(entry.getValue().getAsString().equals(actual.get("sha256")), "Defined production geometry differs: " + entry.getKey());
            var evidence = GSON.toJsonTree(actual).getAsJsonObject();
            if (external.has(entry.getKey())) {
                var expected = external.getAsJsonObject(entry.getKey());
                require(coordinate.equals(expected.get("artifact").getAsString())
                    && entry.getValue().getAsString().equals(expected.get("sha256").getAsString()), "External expected bytes differ");
                var origin = dependencyOrigin(type, entry.getKey(), expected, dependencyJar);
                for (var value : origin.entrySet()) evidence.add(value.getKey(), value.getValue());
                evidence.addProperty("artifactSha256", actualJarSha);
            }
            definitions.add(entry.getKey(), evidence);
        }
        var result = new JsonObject();
        result.add("build", build);
        result.add("actualFirstDefinitions", definitions);
        result.addProperty("buildManifestSha256", startup.get("manifestSha256"));
        result.addProperty("runtimePolicy", "geometry-v2-single-passive-observer");
        result.addProperty("dependencyPolicy", "resolved-fastutil-8.5.18-v1");
        result.add("actualExternalArtifacts", actualArtifacts);
        result.addProperty("scope", "selected production definitions and resolved fastutil JAR origins only; not all runtime classes or live RC2 loading");
        return result;
    }

    private static JsonObject dependencyOrigin(Class<?> type, String name, JsonObject expected, Path jar) throws Exception {
        String entry = name.replace('.', '/') + ".class";
        require(entry.equals(expected.get("entry").getAsString()), "Wrong external class entry");
        var domain = type.getProtectionDomain();
        require(domain != null && domain.getCodeSource() != null, "External CodeSource is unavailable");
        var source = domain.getCodeSource().getLocation();
        require(source != null && "file".equals(source.getProtocol()), "External CodeSource is not a file JAR");
        Path codeSource = Paths.get(source.toURI()).toRealPath();
        require(codeSource.equals(jar), "External class did not come from the resolved dependency JAR: " + name);
        var resource = type.getResource('/' + entry);
        require(resource != null && "jar".equals(resource.getProtocol()), "External class resource is not a JAR entry");
        var opened = resource.openConnection();
        require(opened instanceof java.net.JarURLConnection, "External resource origin is unavailable");
        var connection = (java.net.JarURLConnection) opened;
        connection.setUseCaches(false);
        require("file".equals(connection.getJarFileURL().getProtocol()) && entry.equals(connection.getEntryName()), "Unexpected external resource entry");
        Path resourceJar = Paths.get(connection.getJarFileURL().toURI()).toRealPath();
        require(resourceJar.equals(jar), "External resource did not come from resolved dependency JAR: " + name);
        String resourceSha = sha(bytes(connection.getInputStream()));
        require(resourceSha.equals(expected.get("sha256").getAsString()), "External class resource differs from JAR entry: " + name);
        var result = new JsonObject();
        result.addProperty("artifact", expected.get("artifact").getAsString());
        result.addProperty("resourceSha256", resourceSha);
        result.addProperty("resourceEntry", entry);
        result.addProperty("codeSourceFile", codeSource.toString());
        result.addProperty("resourceArtifactFile", resourceJar.toString());
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

    private static class PlainSink implements LegacyVertexConsumer {
        final JsonArray vertices = new JsonArray();
        final String fault;
        final JsonArray[] face = new JsonArray[4];
        int pending;
        boolean swapped;
        PlainSink(String fault) { this.fault = fault; }
        @Override public void vertex(float x, float y, float z, float red, float green, float blue, float alpha,
            float u, float v, int overlay, int light, float nx, float ny, float nz) {
            require(vertices.size() < 50000, "Excessive production vertices");
            face[pending++] = GeometrySnapshots.vector(x,y,z,nx,ny,nz,"uv_shift".equals(fault) ? u + 0.125F : u,v);
            if (pending != 4) return;
            // The actual sink writes this face in the wrong corner order, before any report is saved
            if ("corner_swap".equals(fault) && !swapped && !face[0].equals(face[1])) {
                var value = face[0]; face[0] = face[1]; face[1] = value;
                swapped = true;
            }
            for (var value : face) {
                vertices.add(value);
                emitted(vertices.size() - 1);
            }
            pending = 0;
        }
        void emitted(int index) {}
        void finish() { require(pending == 0, "Incomplete production face"); }
    }

    private static final class Sink extends PlainSink implements GeometryBoneObserver {
        final GeometryTrace trace;
        Sink(String fault, boolean gecko) { super(fault); trace = gecko ? new GeometryTrace() : null; }
        @Override public void beginBone(String name, int[] actualMasks) {
            require(trace != null && pending == 0, "Unexpected production bone observation");
            trace.beginBone(name, actualMasks);
        }
        @Override public void endBone() {
            require(pending == 0, "Bone ended inside a face");
            trace.endBone();
        }
        @Override void emitted(int index) {
            if (trace != null) trace.emittedVertex(index);
        }
    }
}
