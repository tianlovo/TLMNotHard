package com.tianluoqaq.tlmnh.reference;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.BedrockModelUtil;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo.BedrockModelPOJO;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo.BedrockVersion;
import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.BedrockModel;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoRenderer;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Converter;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.GeoBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tianluoqaq.tlmnh.geometry.GeometrySnapshots;
import com.tianluoqaq.tlmnh.geometry.GeometryTrace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** 独立原生几何数学入口；不接管世界绘制，不修改实体或动画控制器 */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class GeometryReferenceProbe {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static Path root;
    private static boolean failed;

    private GeometryReferenceProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("tlmnh.geometryProbe") || event.phase != TickEvent.Phase.END || failed) return;
        try {
            if (root == null) {
                root = Minecraft.getInstance().gameDirectory.toPath().resolve("tlmnh-geometry-reference");
                for (String child : new String[] { "control", "responses", "captures" }) Files.createDirectories(root.resolve(child));
                TouhouLittleMaid.LOGGER.info("TLMNH_GEOMETRY_REFERENCE_READY contract=native-bind-geometry-v2");
            }
            try (var stream = Files.newDirectoryStream(root.resolve("control"), "*.json")) {
                var pending = new ArrayList<Path>();
                stream.forEach(pending::add);
                pending.sort(Path::compareTo);
                if (!pending.isEmpty()) handle(pending.get(0));
            }
        } catch (Exception failure) {
            failed = true;
            TouhouLittleMaid.LOGGER.error("TLMNH_GEOMETRY_REFERENCE_IO_FAILED", failure);
        }
    }

    private static void handle(Path path) throws Exception {
        var response = new JsonObject();
        String name = path.getFileName().toString();
        try {
            require(name.matches("[A-Za-z0-9_-]{1,100}\\.json") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) <= 16384, "Invalid geometry request");
            var request = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            var minecraft = Minecraft.getInstance();
            switch (request.get("command").getAsString()) {
                case "status" -> {
                    response.addProperty("worldLoaded", minecraft.level != null);
                    response.addProperty("spectator", minecraft.player != null && minecraft.player.isSpectator());
                    response.add("scene", ReferenceScene.status());
                    response.addProperty("contract", "native-bind-geometry-v2");
                }
                case "lab_create" -> response = ReferenceScene.createLab();
                case "lab_open" -> response = ReferenceScene.openLab();
                case "capture_bind" -> {
                    require(request.get("repetitions").getAsInt() == 3
                        && request.get("repetitions").getAsDouble() == 3, "Exactly three fresh builds required");
                    require(minecraft.player != null && minecraft.level != null && minecraft.player.isSpectator()
                        && minecraft.player.getAbilities().invulnerable, "Protected reference spectator required");
                    var ids = Set.copyOf(ModList.get().getMods().stream().map(info -> info.getModId()).toList());
                    require(ids.equals(Set.of("minecraft", "forge", "touhou_little_maid")), "Minimal original reference mods required");
                    var report = capture(request.get("model").getAsString());
                    Path destination = root.resolve("captures").resolve(name);
                    write(destination, report);
                    response.addProperty("capture", "captures/" + name);
                    response.addProperty("captureSha256", sha(Files.readAllBytes(destination)));
                    response.addProperty("verdict", "CAPTURED_NOT_COMPARED");
                }
                case "shutdown" -> minecraft.stop();
                default -> throw new IllegalArgumentException("Unsupported geometry command");
            }
            response.addProperty("status", "complete");
        } catch (Exception | LinkageError failure) {
            response.addProperty("status", "inconclusive");
            response.addProperty("error", failure.toString());
        }
        response.addProperty("request", name);
        write(root.resolve("responses").resolve(name), response);
        Files.delete(path);
    }

    private static JsonObject capture(String modelId) throws Exception {
        var spec = contract();
        require(spec.getAsJsonObject("models").has(modelId), "Unreviewed geometry model");
        var model = spec.getAsJsonObject("models").getAsJsonObject(modelId);
        boolean gecko = model.get("backend").getAsString().equals("gecko");
        var geometry = new ResourceLocation(model.get("geometry").getAsString());
        var texture = new ResourceLocation(model.get("texture").getAsString());
        var info = CustomPackLoader.MAID_MODELS.getInfo(modelId)
            .orElseThrow(() -> new IllegalStateException("Original model metadata is missing"));
        require(info.getModel().equals(geometry) && info.getTexture().equals(texture) && info.isGeckoModel() == gecko,
            "Loaded original model metadata differs from the reviewed contract");
        var result = new JsonObject();
        result.addProperty("schema", "geometry-reference-v2");
        result.addProperty("contract", "native-bind-geometry-v2");
        result.addProperty("model", modelId);
        result.addProperty("backend", gecko ? "gecko" : "bedrock");
        result.addProperty("coordinateSpace", "upstream-native-bind");
        result.addProperty("contractSha256", sha(resource("/tlmnh-geometry/contract.json")));
        result.addProperty("geometrySha256", sha(asset(geometry)));
        result.addProperty("textureSha256", sha(asset(texture)));
        result.addProperty("sourcePath", gecko ? "IGeoRenderer.renderRecursively" : "BedrockModel inherited AbstractBedrockEntityModel.renderToBuffer");
        result.addProperty("worldRendererVerified", false);
        result.addProperty("gpuVerified", false);
        result.addProperty("dynamicAnimationVerified", false);
        result.addProperty("materialAttributesCompared", false);
        result.addProperty("orderDependentRenderingVerified", false);
        result.add("coordinateContract", spec.get("coordinateContract"));
        var samples = new JsonArray();
        var renderer = new IGeoRenderer<Object>() {
            @Override public MultiBufferSource getCurrentRTB() { return null; }
            @Override public ResourceLocation getTextureLocation(Object ignored) { return texture; }
        };
        var tracedRenderer = new IGeoRenderer<Object>() {
            @Override public MultiBufferSource getCurrentRTB() { return null; }
            @Override public ResourceLocation getTextureLocation(Object ignored) { return texture; }
            @Override public void renderCubesOfBone(AnimatedGeoBone bone, PoseStack poses, VertexConsumer buffer,
                int light, int overlay, float red, float green, float blue, float alpha) {
                if (bone.isHidden() || bone.cubesAreHidden()) {
                    IGeoRenderer.super.renderCubesOfBone(bone, poses, buffer, light, overlay, red, green, blue, alpha);
                    return;
                }
                var trace = ((Sink) buffer).trace;
                require(trace != null, "Actual reference bone boundary has no trace sink");
                var mesh = bone.geoBone().cubes();
                int[] masks = new int[mesh.getCubeCount()];
                for (int i = 0; i < masks.length; i++) masks[i] = mesh.faces(i);
                trace.beginBone(bone.geoBone().name(), masks);
                try {
                    IGeoRenderer.super.renderCubesOfBone(bone, poses, buffer, light, overlay, red, green, blue, alpha);
                } finally {
                    trace.endBone();
                }
            }
        };
        for (int repetition = 0; repetition < 3; repetition++) {
            byte[] bytes = asset(geometry);
            require(sha(bytes).equals(result.get("geometrySha256").getAsString()), "Geometry resource changed");
            var sink = new Sink(gecko);
            var plain = new Sink(false);
            var sample = new JsonObject();
            sample.addProperty("repetition", repetition);
            sample.addProperty("freshNativeParse", true);
            float modelViewM32 = RenderSystem.getModelViewMatrix().m32();
            require(Float.isFinite(modelViewM32), "Unknown native normal branch");
            sample.addProperty("nativeNormalBranch", modelViewM32 == 0 ? "transformed-edge-cross" : "pose-normal-matrix");
            if (gecko) {
                var raw = Converter.fromInputStream(new ByteArrayInputStream(bytes));
                var built = GeoBuilder.getGeoBuilder().constructGeoModel(RawGeometryTree.parseHierarchy(raw));
                var nativeModel = new AnimatedGeoModel(built);
                var plainRaw = Converter.fromInputStream(new ByteArrayInputStream(bytes));
                var plainModel = new AnimatedGeoModel(GeoBuilder.getGeoBuilder().constructGeoModel(RawGeometryTree.parseHierarchy(plainRaw)));
                var before = GeometrySnapshots.gecko(nativeModel);
                require(before.equals(GeometrySnapshots.gecko(plainModel)), "Fresh original bind models differ");
                sample.add("bindTree", before);
                var poses = new PoseStack();
                for (var bone : nativeModel.topLevelBones()) tracedRenderer.renderRecursively(bone, poses, sink, 0x00f000f0, 0, 1, 1, 1, 1);
                var plainPoses = new PoseStack();
                for (var bone : plainModel.topLevelBones()) renderer.renderRecursively(bone, plainPoses, plain, 0x00f000f0, 0, 1, 1, 1, 1);
                require(before.equals(GeometrySnapshots.gecko(nativeModel)), "Native draw mutated bind pose");
                require(before.equals(GeometrySnapshots.gecko(plainModel)), "Plain native draw mutated bind pose");
                sink.trace.finish(sink.vertices.size());
                sink.trace.addTo(sample);
            } else {
                var pojo = BedrockModelUtil.GSON.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8), BedrockModelPOJO.class);
                var nativeModel = new BedrockModel<Mob>(pojo, BedrockVersion.getVersion(pojo));
                var plainPojo = BedrockModelUtil.GSON.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8), BedrockModelPOJO.class);
                var plainModel = new BedrockModel<Mob>(plainPojo, BedrockVersion.getVersion(plainPojo));
                var before = GeometrySnapshots.bedrock(nativeModel.getModelMap());
                require(before.equals(GeometrySnapshots.bedrock(plainModel.getModelMap())), "Fresh original Bedrock models differ");
                sample.add("bindTree", before);
                nativeModel.renderToBuffer(new PoseStack(), sink, 0x00f000f0, 0, 1, 1, 1, 1);
                plainModel.renderToBuffer(new PoseStack(), plain, 0x00f000f0, 0, 1, 1, 1, 1);
                require(before.equals(GeometrySnapshots.bedrock(nativeModel.getModelMap())), "Native draw mutated bind pose");
                require(before.equals(GeometrySnapshots.bedrock(plainModel.getModelMap())), "Plain native Bedrock draw mutated bind pose");
                sample.addProperty("mapping", "ordered-bedrock");
            }
            require(sink.vertices.size() > 0 && sink.vertices.size() % 4 == 0, "Empty/incomplete native quads");
            require(plain.vertices.equals(sink.vertices), "Reference tracing changed actual raw vertices");
            sample.add("vertices", sink.vertices);
            sample.add("plainVertices", plain.vertices);
            sample.addProperty("freshPlainParse", true);
            sample.addProperty("observerNeutral", true);
            samples.add(sample);
        }
        result.add("samples", samples);
        var firstSample = samples.get(0).getAsJsonObject();
        for (int i = 1; i < samples.size(); i++) {
            var next = samples.get(i).getAsJsonObject();
            require(firstSample.get("vertices").equals(next.get("vertices"))
                && firstSample.get("bindTree").equals(next.get("bindTree")), "Original raw A/A output is unstable");
            if (gecko) require(firstSample.get("vertexKeys").equals(next.get("vertexKeys"))
                && firstSample.get("boneFaces").equals(next.get("boneFaces")), "Original actual bone trace is unstable");
        }
        result.addProperty("rawAAStable", true);
        result.addProperty("observerNeutralityVerified", true);
        result.add("identity", identity());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject identity() throws Exception {
        Path path = Path.of(System.getProperty("tlmnh.geometry.buildIdentity")).toRealPath();
        var manifest = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        require("geometry-build-identity-v2".equals(manifest.get("schema").getAsString()), "Unsupported geometry identity");
        require(!Boolean.getBoolean("tlmnh.referenceProbe"), "Animation v1 profile must be inactive in this geometry run");
        var base = manifest.getAsJsonObject("baseReferenceBuildIdentity");
        require("LOCK_VERIFIED".equals(base.get("status").getAsString()), "Approved original build provenance required");
        require(Runtime.version().feature() == 17, "Geometry reference requires Java 17");
        var javaIdentity = new JsonObject();
        javaIdentity.addProperty("runtimeVersion", System.getProperty("java.runtime.version"));
        javaIdentity.addProperty("vendor", System.getProperty("java.vendor"));
        javaIdentity.addProperty("vmName", System.getProperty("java.vm.name"));
        require(javaIdentity.equals(base.getAsJsonObject("java")), "Geometry Java differs from approved build");
        var arguments = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        var diagnostic = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        require(arguments.contains("-XX:+DisableAttachMechanism") && diagnostic != null
            && "true".equals(diagnostic.getVMOption("DisableAttachMechanism").getValue()), "Dynamic attach must be disabled");
        require(arguments.stream().noneMatch(value -> value.startsWith("-agentlib:") || value.startsWith("-agentpath:")), "Unknown native observer");
        var expectedObservers = new java.util.HashMap<String, Set<String>>();
        expectedObservers.put(manifest.get("agentSha256").getAsString(), manifest.getAsJsonObject("compiledClasses").keySet());
        require(expectedObservers.put(base.getAsJsonObject("identityAgent").get("sha256").getAsString(),
            base.getAsJsonObject("compiledClasses").keySet()) == null, "Geometry and animation observer identities overlap");
        var observerArguments = arguments.stream().filter(value -> value.startsWith("-javaagent:")).toList();
        require(observerArguments.size() == 2, "Exactly the two independently approved passive observers required");
        for (String argument : observerArguments) {
            String[] parts = argument.substring("-javaagent:".length()).split("=", 2);
            require(parts.length == 2, "Observer selection missing");
            String digest = sha(Files.readAllBytes(Path.of(parts[0])));
            Set<String> expected = expectedObservers.remove(digest);
            String[] selected = parts[1].split(",");
            require(expected != null && selected.length == new java.util.HashSet<>(java.util.Arrays.asList(selected)).size()
                && expected.equals(new java.util.HashSet<>(java.util.Arrays.asList(selected))), "Unexpected observer JAR or selected classes");
        }
        require(expectedObservers.isEmpty(), "Missing approved passive observer");
        var originalAgent = Class.forName("com.tianluoqaq.tlmnh.reference.identity.ReferenceIdentityAgent", false, ClassLoader.getSystemClassLoader());
        var originalStartup = (Map<String, Object>) originalAgent.getMethod("startup").invoke(null);
        String baseDigest = manifest.get("baseReferenceBuildIdentitySha256").getAsString();
        var actualBase = JsonParser.parseString(Files.readString(
            Path.of(System.getProperty("tlmnh.reference.buildIdentity")), StandardCharsets.UTF_8)).getAsJsonObject();
        require(base.equals(actualBase), "Embedded original build provenance differs from the actual file");
        require(baseDigest.equals(originalStartup.get("buildIdentitySha256"))
            && baseDigest.equals(sha(Files.readAllBytes(Path.of(System.getProperty("tlmnh.reference.buildIdentity")))))
            && base.getAsJsonObject("identityAgent").get("sha256").getAsString().equals(originalStartup.get("agentSha256")),
            "Original build provenance changed after startup");
        var agent = Class.forName("com.tianluoqaq.tlmnh.geometry.identity.GeometryDefinitionAgent", true, ClassLoader.getSystemClassLoader());
        var startup = (Map<String, String>) agent.getMethod("startup").invoke(null);
        require(sha(Files.readAllBytes(path)).equals(startup.get("manifestSha256")), "Geometry identity changed after startup");
        require(startup.get("agentSha256").equals(manifest.get("agentSha256").getAsString()), "Geometry agent mismatch");
        var actual = new JsonObject();
        for (var entry : manifest.getAsJsonObject("compiledClasses").entrySet()) {
            var type = Class.forName(entry.getKey(), false, GeometryReferenceProbe.class.getClassLoader());
            var value = (Map<String, Object>) agent.getMethod("identity", Class.class).invoke(null, type);
            String resourceSha;
            try (var stream = type.getResourceAsStream('/' + entry.getKey().replace('.', '/') + ".class")) {
                require(stream != null, "Geometry loaded-class resource is missing: " + entry.getKey());
                resourceSha = sha(stream.readAllBytes());
            }
            require(entry.getValue().getAsString().equals(resourceSha), "Geometry class resource differs from compiled manifest: " + entry.getKey());
            require(value.get("sha256") instanceof String definitionSha && definitionSha.matches("[0-9a-f]{64}"),
                "Geometry first-definition digest is unavailable: " + entry.getKey());
            var evidence = GSON.toJsonTree(value).getAsJsonObject();
            evidence.addProperty("resourceSha256", resourceSha);
            actual.add(entry.getKey(), evidence);
        }
        var result = new JsonObject();
        result.add("build", manifest);
        result.add("actualFirstDefinitions", actual);
        result.addProperty("buildManifestSha256", startup.get("manifestSha256"));
        result.addProperty("runtimePolicy", "geometry-v2-two-passive-observers");
        result.addProperty("baseReferenceRuntimeVerified", false);
        result.addProperty("baseReferenceBuildIdentityAnchored", true);
        result.add("runtimeJava", javaIdentity);
        result.addProperty("scope", "selected compiled class resources and independently observed transformed first definitions; no loader-transformation or whole-classpath equivalence claim");
        return result;
    }

    private static JsonObject contract() throws Exception { return JsonParser.parseString(new String(resource("/tlmnh-geometry/contract.json"), StandardCharsets.UTF_8)).getAsJsonObject(); }
    private static byte[] asset(ResourceLocation location) throws Exception {
        Path folder = CustomPackLoader.PACK_FOLDER.toRealPath();
        Path pack;
        try (var stream = Files.list(folder)) {
            var entries = stream.filter(path -> Files.isDirectory(path) || path.getFileName().toString().endsWith(".zip")).toList();
            require(entries.size() == 1 && entries.get(0).getFileName().toString().equals("touhou_little_maid-1.0.0"),
                "The single original default model pack is required");
            pack = entries.get(0).toRealPath();
            require(pack.startsWith(folder), "Original model pack escapes its folder");
        }
        Path file = pack.resolve("assets").resolve(location.getNamespace()).resolve(location.getPath()).toRealPath();
        require(file.startsWith(pack) && Files.isRegularFile(file), "Invalid actual model pack asset");
        return Files.readAllBytes(file);
    }
    private static byte[] resource(String name) throws Exception {
        try (var stream = GeometryReferenceProbe.class.getResourceAsStream(name)) {
            require(stream != null, "Missing geometry tool resource");
            return stream.readAllBytes();
        }
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void write(Path path, JsonObject value) throws Exception {
        require(!Files.exists(path), "Refusing to overwrite geometry evidence");
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(value), StandardCharsets.UTF_8);
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 只记录原生 VertexConsumer 的真实输出，默认 bulk 方法仍由 Minecraft 实现 */
    private static final class Sink implements VertexConsumer {
        final JsonArray vertices = new JsonArray();
        final GeometryTrace trace;
        Sink(boolean traced) { trace = traced ? new GeometryTrace() : null; }
        double x, y, z;
        float nx, ny, nz, u, v;
        int fields;
        @Override public VertexConsumer vertex(double x, double y, double z) { this.x=x; this.y=y; this.z=z; fields|=1; return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { this.u=u; this.v=v; fields|=2; return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { nx=x; ny=y; nz=z; fields|=4; return this; }
        @Override public void endVertex() {
            require(fields == 7 && vertices.size() < 50000, "Incomplete/excessive native vertex");
            vertices.add(GeometrySnapshots.vector(x,y,z,nx,ny,nz,u,v));
            if (trace != null) trace.emittedVertex(vertices.size() - 1);
            fields=0;
        }
        @Override public void defaultColor(int r, int g, int b, int a) { throw new IllegalStateException("Unreviewed default vertex color path"); }
        @Override public void unsetDefaultColor() {}
    }
}
