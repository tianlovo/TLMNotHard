package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import net.minecraft.entity.Entity;

import org.junit.Test;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.BedrockModelUtil;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubeBox;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubePerFace;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo.BedrockModelPOJO;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo.FaceUVsItem;
import com.google.gson.JsonParser;

/** 仅检验 CPU 几何输出，不创建 GL 上下文，也不替代游戏内视觉验收 */
public class BedrockGeometryTest {

    private static final float EPSILON = 1.0E-5F;
    private static final String NEW_PREFIX = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64,"
        + "\"visible_bounds_width\":2,\"visible_bounds_height\":2,\"visible_bounds_offset\":[0,1,0]},\"bones\":";

    @Test
    public void boxEmitsSixFacesWithExpectedCoordinatesAndUvs() {
        var part = new BedrockPart();
        part.cubes.add(new BedrockCubeBox(0, 0, 0, 0, 0, 16, 16, 16, 0, false, 64, 64));
        var output = render(part);
        assertEquals(24, output.vertices.size());
        assertVertex(output.vertices.get(0), 1, 0, 1, 0.5F, 0);
        assertVertex(output.vertices.get(1), 0, 0, 1, 0.25F, 0);
        assertVertex(output.vertices.get(2), 0, 0, 0, 0.25F, 0.25F);
        assertVertex(output.vertices.get(3), 1, 0, 0, 0.5F, 0.25F);
        assertEquals(-1, output.vertices.get(0)[9], EPSILON);
    }

    @Test
    public void mirroredBoxReversesTextureWithoutMovingGeometry() {
        var part = new BedrockPart();
        part.cubes.add(new BedrockCubeBox(0, 0, 0, 0, 0, 16, 16, 16, 0, true, 64, 64));
        var output = render(part);
        assertVertex(output.vertices.get(0), 1, 0, 1, 0.25F, 0);
        assertVertex(output.vertices.get(1), 0, 0, 1, 0.5F, 0);
    }

    @Test
    public void perFaceUvRotationSkipsMissingFacesAndFlipsVerticalDirection() {
        var faces = BedrockModelUtil.GSON
            .fromJson("{\"up\":{\"uv\":[16,8],\"uv_size\":[16,8],\"uv_rotation\":90}}", FaceUVsItem.class);
        var part = new BedrockPart();
        part.cubes.add(new BedrockCubePerFace(0, 0, 0, 16, 16, 16, 0, 64, 64, faces));
        var output = render(part);
        assertEquals(4, output.vertices.size());
        assertVertex(output.vertices.get(0), 1, 0, 1, 0.25F, 0.125F);
        assertVertex(output.vertices.get(1), 0, 0, 1, 0.25F, 0.25F);
        assertVertex(output.vertices.get(2), 0, 0, 0, 0.5F, 0.25F);
        assertVertex(output.vertices.get(3), 1, 0, 0, 0.5F, 0.125F);
    }

    @Test
    public void zeroThicknessPlaneStillEmitsFiniteVertices() {
        var part = new BedrockPart();
        part.cubes.add(new BedrockCubeBox(0, 0, 0, 0, 0, 0, 16, 16, 0, false, 64, 64));
        assertEquals(24, render(part).vertices.size());
    }

    @Test
    public void hierarchyAndIndependentCubeRotationComposeAroundTheirPivots() throws Exception {
        var model = model(
            NEW_PREFIX + "[{\"name\":\"root\",\"pivot\":[16,24,0],\"rotation\":[0,0,90]},"
                + "{\"name\":\"child\",\"parent\":\"root\",\"pivot\":[32,24,0],\"cubes\":["
                + "{\"origin\":[32,8,0],\"size\":[16,16,16],\"uv\":[0,0],"
                + "\"rotation\":[0,0,90],\"pivot\":[32,24,0]}]}]}]}");
        var output = new RecordingConsumer();
        model.renderToBuffer(new LegacyPoseStack(), output, 0x00F000F0, 0, 0.2F, 0.3F, 0.4F, 0.5F);
        assertEquals(24, output.vertices.size());
        assertVertex(output.vertices.get(0), 0, 1, 1, 0.5F, 0);
        assertVertex(output.vertices.get(1), 1, 1, 1, 0.25F, 0);
        assertEquals(0.2F, output.vertices.get(0)[5], EPSILON);
        assertEquals(0.3F, output.vertices.get(0)[6], EPSILON);
        assertEquals(0.4F, output.vertices.get(0)[7], EPSILON);
        assertEquals(0.5F, output.vertices.get(0)[11], EPSILON);
    }

    @Test
    public void mirrorInheritanceHonorsExplicitCubeOverride() throws Exception {
        var model = model(
            NEW_PREFIX + "[{\"name\":\"body\",\"pivot\":[0,24,0],\"mirror\":true,\"cubes\":["
                + "{\"origin\":[0,8,0],\"size\":[16,16,16],\"uv\":[0,0]},"
                + "{\"origin\":[16,8,0],\"size\":[16,16,16],\"uv\":[0,0],\"mirror\":false}]}]}]}");
        var output = new RecordingConsumer();
        model.renderToBuffer(new LegacyPoseStack(), output, 0, 0, 1, 1, 1, 1);
        assertEquals(48, output.vertices.size());
        assertEquals(0.25F, output.vertices.get(0)[3], EPSILON);
        assertEquals(0.5F, output.vertices.get(24)[3], EPSILON);
    }

    @Test
    public void all205BuiltinBedrockGeometriesEmitFiniteVertices() throws Exception {
        var stream = getClass().getResourceAsStream("/assets/tlmnh/builtin-models.json");
        assertNotNull(stream);
        var geometries = new HashSet<String>();
        long vertices = 0;
        int winefox = 0;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var catalog = new JsonParser().parse(reader)
                .getAsJsonObject();
            for (var pack : catalog.getAsJsonArray("packs")) {
                for (var entry : pack.getAsJsonObject()
                    .getAsJsonArray("models")) {
                    var choice = entry.getAsJsonObject();
                    if (choice.get("gecko")
                        .getAsBoolean()) continue;
                    String geometry = choice.get("geometry")
                        .getAsString();
                    if (!geometries.add(geometry)) continue;
                    String resourcePath = "/assets/" + geometry.replace(':', '/');
                    var modelStream = getClass().getResourceAsStream(resourcePath);
                    assertNotNull(resourcePath, modelStream);
                    BedrockModelPOJO pojo;
                    try (var modelReader = new InputStreamReader(modelStream, StandardCharsets.UTF_8)) {
                        pojo = BedrockModelUtil.GSON.fromJson(modelReader, BedrockModelPOJO.class);
                    }
                    var model = new StaticModel(pojo);
                    var output = new RecordingConsumer();
                    model.renderToBuffer(new LegacyPoseStack(), output, 0x00F000F0, 0, 1, 1, 1, 1);
                    assertTrue(geometry + " has no geometry", output.vertices.size() > 0);
                    assertEquals(geometry + " has incomplete quads", 0, output.vertices.size() % 4);
                    assertTrue(geometry + " has no model bounds", model.getRenderBoundingBox() != null);
                    vertices += output.vertices.size();
                    if (geometry.contains("wine_fox")) winefox++;
                }
            }
        }
        assertEquals(205, geometries.size());
        assertEquals(2, winefox);
        System.out.println(
            "TLMNH_BEDROCK_GEOMETRY_PASS models=" + geometries.size()
                + " winefox="
                + winefox
                + " vertices="
                + vertices
                + " java="
                + System.getProperty("java.version")
                + " scope=cpu-geometry-only");
    }

    private static StaticModel model(String json) throws Exception {
        return new StaticModel(BedrockModelUtil.GSON.fromJson(json, BedrockModelPOJO.class));
    }

    private static RecordingConsumer render(BedrockPart part) {
        var output = new RecordingConsumer();
        part.render(new LegacyPoseStack(), output, 0x00F000F0, 0);
        return output;
    }

    private static void assertVertex(float[] vertex, float x, float y, float z, float u, float v) {
        assertEquals(x, vertex[0], EPSILON);
        assertEquals(y, vertex[1], EPSILON);
        assertEquals(z, vertex[2], EPSILON);
        assertEquals(u, vertex[3], EPSILON);
        assertEquals(v, vertex[4], EPSILON);
    }

    private static final class StaticModel extends AbstractBedrockEntityModel<Entity> {

        private StaticModel(BedrockModelPOJO pojo) throws Exception {
            super(pojo);
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
            float headPitch) {}
    }

    private static final class RecordingConsumer implements LegacyVertexConsumer {

        private final List<float[]> vertices = new ArrayList<>();

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
            int overlay, int lightmap, float normalX, float normalY, float normalZ) {
            float[] vertex = { x, y, z, u, v, red, green, blue, normalX, normalY, normalZ, alpha };
            for (float component : vertex) {
                assertTrue("Non-finite vertex component", Float.isFinite(component));
            }
            vertices.add(vertex);
        }
    }
}
