package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.joml.Matrix4f;
import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoRenderer;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Converter;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.FormatVersion;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.GeoBuilder;
import com.google.gson.JsonParser;

/** 检查 Gecko 的几何与骨骼状态，未接动画时间轴，也不创建 GL 上下文 */
public class GeckoGeometryTest {

    private static final float EPSILON = 1.0E-5F;
    private static final IGeoRenderer<Object> RENDERER = new IGeoRenderer<>() {};
    private static final String PREFIX = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},\"bones\":";
    private static final String CUBE = "{\"origin\":[0,0,0],\"size\":[16,16,16],\"uv\":[0,0]}";

    @Test
    public void boxUsesGeckoCoordinatesAndUvLayout() {
        var model = model("[{\"name\":\"root\",\"cubes\":[" + CUBE + "]}]");
        var output = render(model);
        assertEquals(24, output.count);
        assertVertex(output.samples.get(0), 0, 0, 1, 0.5F, 0);
        assertVertex(output.samples.get(1), -1, 0, 1, 0.75F, 0);
        assertEquals(-1, output.samples.get(0)[6], EPSILON);
    }

    @Test
    public void boneMirrorCanBeOverriddenPerCube() {
        var mirrored = model("[{\"name\":\"root\",\"mirror\":true,\"cubes\":[" + CUBE + "]}]");
        assertVertex(render(mirrored).samples.get(0), -1, 0, 1, 0.5F, 0);
        var explicitFalse = CUBE.replace("\"uv\"", "\"mirror\":false,\"uv\"");
        var overridden = model("[{\"name\":\"root\",\"mirror\":true,\"cubes\":[" + explicitFalse + "]}]");
        assertVertex(render(overridden).samples.get(0), 0, 0, 1, 0.5F, 0);
    }

    @Test
    public void perFaceUvKeepsMissingFacesAbsent() {
        var cube = "{\"origin\":[0,0,0],\"size\":[16,16,16],\"uv\":{"
            + "\"down\":{\"uv\":[8,16],\"uv_size\":[16,-8]}}}";
        var output = render(model("[{\"name\":\"root\",\"cubes\":[" + cube + "]}]"));
        assertEquals(4, output.count);
        assertVertex(output.samples.get(0), 0, 0, 1, 0.125F, 0.125F);
        assertVertex(output.samples.get(1), -1, 0, 1, 0.375F, 0.125F);
        assertVertex(output.samples.get(2), -1, 0, 0, 0.375F, 0.25F);
    }

    @Test
    public void boneAndIndependentCubeRotationBothApply() {
        var cube = "{\"origin\":[16,0,0],\"size\":[16,16,16],\"uv\":[0,0],\"rotation\":[0,0,90]}";
        var output = render(model("[{\"name\":\"root\",\"rotation\":[0,0,90],\"cubes\":[" + cube + "]}]"));
        assertVertex(output.samples.get(0), 1, 0, 1, 0.5F, 0);
        assertVertex(output.samples.get(1), 2, 0, 1, 0.75F, 0);
    }

    @Test
    public void geometryCanBeSharedWhileAnimatedStateRemainsIndependent() {
        var first = model("[{\"name\":\"root\",\"cubes\":[" + CUBE + "]}]");
        var second = new AnimatedGeoModel(first.geoModel());
        var bone = first.bones()
            .get("root");
        var saved = new BoneTopLevelSnapshot(bone);
        bone.setPositionX(8);
        bone.setRotationY(0.5F);
        bone.setScale(2, 3, 4);
        assertSame(first.geoModel(), second.geoModel());
        assertVertex(render(second).samples.get(0), 0, 0, 1, 0.5F, 0);
        assertEquals(
            0,
            second.bones()
                .get("root")
                .getPositionX(),
            EPSILON);
        saved.commit();
        assertVertex(render(first).samples.get(0), 0, 0, 1, 0.5F, 0);
        assertEquals(1, bone.getScaleY(), EPSILON);
    }

    @Test
    public void visibilitySeparatesOwnCubesFromChildren() {
        var model = model(
            "[{\"name\":\"root\",\"cubes\":[" + CUBE
                + "]},"
                + "{\"name\":\"child\",\"parent\":\"root\",\"cubes\":["
                + CUBE
                + "]}]");
        assertEquals(48, render(model).count);
        var bone = model.bones()
            .get("root");
        bone.setHidden(true, false);
        assertEquals(24, render(model).count);
        bone.setHidden(true, true);
        assertEquals(0, render(model).count);
        bone.setHidden(false, false);
        bone.setCubesHidden(true);
        assertEquals(24, render(model).count);
    }

    @Test
    public void singleCollapsedAxisKeepsFiniteLightingAndTwoCollapsedAxesHideBranch() {
        var model = model("[{\"name\":\"root\",\"cubes\":[" + CUBE + "]}]");
        var bone = model.bones()
            .get("root");
        bone.setScaleX(0);
        var output = render(model);
        assertEquals(24, output.count);
        assertTrue(
            output.samples.stream()
                .anyMatch(vertex -> Math.abs(vertex[5]) > 0.5F));
        bone.setScaleY(0);
        assertEquals(0, render(model).count);
    }

    @Test
    public void renderFailureRestoresMatrixStack() {
        var model = model("[{\"name\":\"root\",\"pivot\":[16,16,16],\"rotation\":[0,0,90],\"cubes\":[" + CUBE + "]}]");
        var stack = new LegacyPoseStack();
        var root = stack.last();
        LegacyVertexConsumer failing = (x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz) -> {
            throw new IllegalStateException("probe");
        };
        try {
            RENDERER.render(model, stack, failing, 0, 0, 1, 1, 1, 1);
            fail("Expected vertex failure");
        } catch (IllegalStateException expected) {
            assertEquals("probe", expected.getMessage());
        }
        assertSame(root, stack.last());
        assertEquals(
            new Matrix4f(),
            stack.last()
                .pose());
    }

    @Test(timeout = 1000)
    public void invalidHierarchyFailsWithoutLooping() {
        expectInvalid("[{\"name\":\"root\",\"parent\":\"missing\"}]");
        expectInvalid("[{\"name\":\"a\",\"parent\":\"b\"},{\"name\":\"b\",\"parent\":\"a\"}]");
        expectInvalid("[{\"name\":\"same\"},{\"name\":\"same\"}]");
        assertEquals(
            0,
            model("[]").bones()
                .size());
    }

    @Test
    public void handLocatorKeepsParentFirstOrderWithChildListedBeforeParent() {
        var model = model(
            "[{\"name\":\"LeftHandLocator\",\"parent\":\"LeftArm\"},"
                + "{\"name\":\"LeftArm\",\"parent\":\"root\"},{\"name\":\"root\"}]");
        assertEquals(
            3,
            model.leftHandBones()
                .size());
        assertEquals(
            "root",
            model.leftHandBones()
                .get(0)
                .getName());
        assertEquals(
            "LeftArm",
            model.leftHandBones()
                .get(1)
                .getName());
        assertEquals(
            "LeftHandLocator",
            model.leftHandBones()
                .get(2)
                .getName());
    }

    @Test
    public void all26BuiltinGeckoGeometriesEmitFiniteVertices() throws Exception {
        var stream = getClass().getResourceAsStream("/assets/tlmnh/builtin-models.json");
        assertNotNull(stream);
        var geometries = new HashSet<String>();
        var winefoxBases = new HashSet<String>();
        int winefoxChoices = 0;
        long vertices = 0;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var catalog = new JsonParser().parse(reader)
                .getAsJsonObject();
            for (var pack : catalog.getAsJsonArray("packs")) {
                for (var entry : pack.getAsJsonObject()
                    .getAsJsonArray("models")) {
                    var choice = entry.getAsJsonObject();
                    if (!choice.get("gecko")
                        .getAsBoolean()) continue;
                    String baseId = choice.get("base_id")
                        .getAsString();
                    if (baseId.contains("winefox") || baseId.contains("wine_fox")) {
                        winefoxBases.add(baseId);
                        winefoxChoices++;
                    }
                    String geometry = choice.get("geometry")
                        .getAsString();
                    if (!geometries.add(geometry)) continue;
                    try (var modelStream = getClass().getResourceAsStream("/assets/" + geometry.replace(':', '/'))) {
                        assertNotNull(geometry, modelStream);
                        var raw = Converter.fromInputStream(modelStream);
                        assertEquals(geometry, FormatVersion.NEW, raw.getFormatVersion());
                        var tree = RawGeometryTree.parseHierarchy(raw);
                        var model = new AnimatedGeoModel(
                            GeoBuilder.getGeoBuilder()
                                .constructGeoModel(tree));
                        var output = render(model);
                        assertTrue(geometry + " has no vertices", output.count > 0);
                        assertEquals(geometry + " has incomplete quads", 0, output.count % 4);
                        assertEquals(
                            geometry + " lost bones",
                            raw.getMinecraftGeometry()[0].getBones().length,
                            model.bones()
                                .size());
                        vertices += output.count;
                    }
                }
            }
        }
        assertEquals(26, geometries.size());
        assertEquals(19, winefoxBases.size());
        assertEquals(33, winefoxChoices);
        System.out.println(
            "TLMNH_GECKO_GEOMETRY_PASS geometries=" + geometries.size()
                + " winefoxBases="
                + winefoxBases.size()
                + " winefoxChoices="
                + winefoxChoices
                + " vertices="
                + vertices
                + " java="
                + System.getProperty("java.version")
                + " gson="
                + JsonParser.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                + " scope=cpu-geometry-and-bones-only");
    }

    private static void expectInvalid(String bones) {
        try {
            model(bones);
            fail("Expected invalid hierarchy");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static AnimatedGeoModel model(String bones) {
        var raw = Converter.fromJsonString(PREFIX + bones + "}]}");
        return new AnimatedGeoModel(
            GeoBuilder.getGeoBuilder()
                .constructGeoModel(RawGeometryTree.parseHierarchy(raw)));
    }

    private static RecordingConsumer render(AnimatedGeoModel model) {
        var output = new RecordingConsumer();
        RENDERER.render(model, new LegacyPoseStack(), output, 0x00F000F0, 0, 0.2F, 0.3F, 0.4F, 0.5F);
        return output;
    }

    private static void assertVertex(float[] vertex, float x, float y, float z, float u, float v) {
        assertEquals(x, vertex[0], EPSILON);
        assertEquals(y, vertex[1], EPSILON);
        assertEquals(z, vertex[2], EPSILON);
        assertEquals(u, vertex[3], EPSILON);
        assertEquals(v, vertex[4], EPSILON);
    }

    private static final class RecordingConsumer implements LegacyVertexConsumer {

        private final List<float[]> samples = new ArrayList<>();
        private int count;

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
            int overlay, int lightmap, float nx, float ny, float nz) {
            for (float value : new float[] { x, y, z, u, v, nx, ny, nz, red, green, blue, alpha }) {
                assertTrue("Non-finite Gecko vertex", Float.isFinite(value));
            }
            assertEquals(0.2F, red, EPSILON);
            assertEquals(0.5F, alpha, EPSILON);
            if (samples.size() < 128) samples.add(new float[] { x, y, z, u, v, nx, ny, nz });
            count++;
        }
    }
}
