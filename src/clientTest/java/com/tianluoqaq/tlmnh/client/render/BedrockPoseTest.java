package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.client.animation.script.GlWrapper;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 用实际几何输出核对每帧复位与整体坐标，避免坐姿、悬浮和附件变换串帧 */
public class BedrockPoseTest {

    private static final float EPSILON = 1.0E-5F;
    private static final String GEOMETRY = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64,"
        + "\"visible_bounds_width\":2,\"visible_bounds_height\":2,\"visible_bounds_offset\":[0,1,0]},"
        + "\"bones\":[{\"name\":\"root\",\"pivot\":[0,0,0]},{\"name\":\"head\",\"parent\":\"root\","
        + "\"pivot\":[1,10,3],\"rotation\":[10,20,30],\"cubes\":[{\"origin\":[1,2,3],\"size\":[2,4,6],"
        + "\"uv\":[0,0],\"rotation\":[17,-23,31],\"pivot\":[2,6,4]}]}]}]}";

    @BeforeClass
    public static void loadCatalog() {
        BuiltinModelCatalog.load();
    }

    @Test
    public void nextFrameRestoresNamedAndAnonymousBones() throws Exception {
        var model = model();
        var baseline = render(model);
        model.getBedrockPose()
            .animate(parts -> {
                for (var wrapper : parts.values()) {
                    var part = wrapper.getModelRenderer();
                    part.setPos(30, 40, 50);
                    part.xRot = part.yRot = part.zRot = 0.8F;
                    part.offsetX = part.offsetY = part.offsetZ = 0.25F;
                    part.xScale = part.yScale = part.zScale = 2;
                    part.additionalQuaternion.rotationY(0.5F);
                    part.illuminated = true;
                    part.visible = false;
                    for (var child : part.children) child.xRot += 0.7F;
                }
                GlWrapper.translate(3, 4, 5);
            });
        assertTrue(render(model).isEmpty());
        model.getBedrockPose()
            .animate(parts -> {});
        assertVertices(baseline, render(model));
    }

    @Test
    public void wholeModelTransformMatchesTheAttachmentTransform() throws Exception {
        var model = model();
        var before = render(model);
        model.getBedrockPose()
            .animate(parts -> {
                GlWrapper.translate(1, 2, 3);
                GlWrapper.rotate(90, 0, 1, 0);
                GlWrapper.scale(2, 3, 4);
            });
        var expected = new Matrix4f().translation(1, 2, 3)
            .rotateY((float) Math.PI / 2)
            .scale(2, 3, 4);
        var attachment = new LegacyPoseStack();
        model.getBedrockPose()
            .applyTransform(attachment);
        assertTrue(
            expected.equals(
                attachment.last()
                    .pose(),
                EPSILON));
        var after = render(model);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            var vertex = before.get(i);
            var position = new Vector3f(vertex[0], vertex[1], vertex[2]).mulPosition(expected);
            assertArrayEquals(
                new float[] { position.x, position.y, position.z },
                new float[] { after.get(i)[0], after.get(i)[1], after.get(i)[2] },
                EPSILON);
        }
    }

    @Test
    public void matrixPushAndPopKeepOnlyTheSurvivingTransform() throws Exception {
        var model = model();
        model.getBedrockPose()
            .animate(parts -> {
                GlWrapper.translate(1, 2, 3);
                GlWrapper.pushMatrix();
                GlWrapper.translate(20, 30, 40);
                GlWrapper.popMatrix();
                GlWrapper.scale(2, 2, 2);
            });
        var poses = new LegacyPoseStack();
        model.getBedrockPose()
            .applyTransform(poses);
        assertEquals(
            new Matrix4f().translation(1, 2, 3)
                .scale(2),
            poses.last()
                .pose());
    }

    @Test
    public void failureRestoresGeometryAndClearsTheBinding() throws Exception {
        var model = model();
        var baseline = render(model);
        assertThrows(
            IllegalStateException.class,
            () -> model.getBedrockPose()
                .animate(parts -> {
                    parts.get("head")
                        .setHidden(true);
                    GlWrapper.translate(20, 30, 40);
                    throw new IllegalStateException("Test animation failure");
                }));
        assertVertices(baseline, render(model));
        assertThrows(IllegalStateException.class, () -> GlWrapper.translate(0, 0, 0));
    }

    @Test
    public void nestedAnimationFailureRestoresTheOuterBinding() throws Exception {
        var outer = model();
        var inner = model();
        outer.getBedrockPose()
            .animate(parts -> {
                GlWrapper.translate(1, 0, 0);
                assertThrows(
                    IllegalStateException.class,
                    () -> inner.getBedrockPose()
                        .animate(nested -> {
                            GlWrapper.translate(20, 0, 0);
                            throw new IllegalStateException("Test nested animation failure");
                        }));
                GlWrapper.translate(3, 0, 0);
            });
        var outerPose = new LegacyPoseStack();
        var innerPose = new LegacyPoseStack();
        outer.getBedrockPose()
            .applyTransform(outerPose);
        inner.getBedrockPose()
            .applyTransform(innerPose);
        assertEquals(
            new Matrix4f().translation(4, 0, 0),
            outerPose.last()
                .pose());
        assertEquals(
            new Matrix4f(),
            innerPose.last()
                .pose());
        assertThrows(IllegalStateException.class, () -> GlWrapper.translate(0, 0, 0));
    }

    @Test
    public void twoModelsKeepTheirGlobalTransformsAndBoneChangesIndependent() throws Exception {
        var first = model();
        var second = model();
        var baseline = render(second);
        first.getBedrockPose()
            .animate(parts -> {
                parts.get("head")
                    .setHidden(true);
                GlWrapper.translate(5, 6, 7);
            });
        assertTrue(render(first).isEmpty());
        assertVertices(baseline, render(second));
        var untouched = new LegacyPoseStack();
        second.getBedrockPose()
            .applyTransform(untouched);
        assertEquals(
            new Matrix4f(),
            untouched.last()
                .pose());
    }

    private static BuiltinMaidModel model() throws Exception {
        var choice = BuiltinModelCatalog.choices()
            .stream()
            .filter(c -> !c.isGecko())
            .findFirst()
            .get();
        return new BuiltinMaidModels(location -> new ByteArrayInputStream(GEOMETRY.getBytes(StandardCharsets.UTF_8)))
            .create(choice);
    }

    private static List<float[]> render(BuiltinMaidModel model) {
        var vertices = new ArrayList<float[]>();
        model.renderGeometry(new LegacyPoseStack(), (x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz) -> {
            var vertex = new float[] { x, y, z, r, g, b, a, u, v, light, nx, ny, nz };
            for (float value : vertex) assertTrue(Float.isFinite(value));
            vertices.add(vertex);
        }, 0x00500060);
        return vertices;
    }

    private static void assertVertices(List<float[]> expected, List<float[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i), EPSILON);
    }
}
