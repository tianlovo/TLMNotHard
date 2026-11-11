package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

public class LegacyPoseStackTest {

    private static final float EPSILON = 1.0E-5F;

    @Test
    public void transformsLocalCoordinatesInModelOrder() {
        var stack = new LegacyPoseStack();
        stack.translate(2, 3, 4);
        stack.mulPose(new Quaternionf().rotationZ((float) Math.PI / 2));
        stack.scale(2, 3, 4);
        assertVector(
            new Vector3f(2, 5, 4),
            new Vector3f(1, 0, 0).mulPosition(
                stack.last()
                    .pose()));
    }

    @Test
    public void nestedPoseRestoresBothMatrices() {
        var stack = new LegacyPoseStack();
        stack.translate(1, 2, 3);
        stack.mulPose(new Quaternionf().rotationY(0.7F));
        var originalPose = new Matrix4f(
            stack.last()
                .pose());
        var originalNormal = new Matrix3f(
            stack.last()
                .normal());
        stack.pushPose();
        stack.translate(20, 30, 40);
        stack.scale(-2, 3, 4);
        stack.popPose();
        assertEquals(
            originalPose,
            stack.last()
                .pose());
        assertEquals(
            originalNormal,
            stack.last()
                .normal());
    }

    @Test(expected = IllegalStateException.class)
    public void cannotRemoveRootPose() {
        new LegacyPoseStack().popPose();
    }

    @Test
    public void normalsFollowInverseTransposeThroughNestedNonUniformAndNegativeScale() {
        var stack = new LegacyPoseStack();
        stack.mulPose(new Quaternionf().rotationXYZ(0.2F, -0.3F, 0.4F));
        stack.scale(-2, 3, 4);
        stack.mulPose(new Quaternionf().rotationXYZ(-0.4F, 0.5F, 0.1F));
        stack.scale(5, -2, 0.5F);
        var expectedMatrix = stack.last()
            .pose()
            .normal(new Matrix3f());
        var input = new Vector3f(1, 2, -3).normalize();
        assertVector(
            new Vector3f(input).mul(expectedMatrix)
                .normalize(),
            new Vector3f(input).mul(
                stack.last()
                    .normal())
                .normalize());
    }

    @Test
    public void collapsedAxisRetainsFinitePlaneNormal() {
        var stack = new LegacyPoseStack();
        stack.scale(0, -2, 3);
        var normal = new Vector3f(1, 0, 0).mul(
            stack.last()
                .normal())
            .normalize();
        assertVector(new Vector3f(1, 0, 0), normal);
        assertTrue(
            stack.last()
                .normal()
                .isFinite());
        assertTrue(
            stack.last()
                .pose()
                .isFinite());
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }

    @Test
    public void repeatedSmallUniformScalesDoNotEraseNormals() {
        var stack = new LegacyPoseStack();
        for (int i = 0; i < 40; i++) stack.scale(0.1F, 0.1F, 0.1F);
        assertVector(
            new Vector3f(0, 1, 0),
            new Vector3f(0, 1, 0).mul(
                stack.last()
                    .normal())
                .normalize());
        var tiny = new LegacyPoseStack();
        tiny.scale(1.0E-30F, 1.0E-30F, 1.0E-30F);
        assertVector(
            new Vector3f(1, 0, 0),
            new Vector3f(1, 0, 0).mul(
                tiny.last()
                    .normal())
                .normalize());
    }
}
