package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;

import com.tianluoqaq.tlmnh.client.render.MaidHeldItemTransforms.Profile;
import com.tianluoqaq.tlmnh.client.render.MaidHeldItemTransforms.Side;

/** 原生源常数的独立double精度landmark，不冒充原版运行G1或GPU绘制 */
public class MaidHeldItemTransformsTest {

    private static final float EPSILON = 2E-6F;
    // 行主序golden：直接从native RenderBiped/BG入口常数以独立double矩阵计算
    private static final double[] SHIELD = { .40053523878486785, -.1867726492063249, -.44194173824159216, .375,
        -.3296758022606207, -.5254041043829405, -.07674237748060803, .8671875, .34858340163491, -.2822968377541823,
        .4352276502000118, .0234375, 0, 0, 0, 1 };
    private static final double[] BOW = { .38904195129785163, .2105150555209157, .44153682918799186, -.0625,
        .43522765020001175, .10853011104183144, -.4352276502000118, .5625, .22326722247507855, -.5783853614989521,
        .07903873083489048, .375, 0, 0, 0, 1 };
    private static final double[] FLAT = { .17619236639735789, -.06412877687356291, -.3247595264191645, .1875,
        .30517413050601516, -.11107429977225892, .1875, .625, -.12825755374712577, -.35238473279471566, 0, -.125, 0, 0,
        0, 1 };

    @Test
    public void bedrockKeepsItsDistinctSourceOffsets() {
        assertPoint(MaidHeldItemTransforms.bedrockTail(Side.RIGHT, false), -.0625F, .525F, -.125F);
        assertPoint(MaidHeldItemTransforms.bedrockTail(Side.LEFT, false), .0625F, .525F, -.125F);
        assertPoint(MaidHeldItemTransforms.bedrockTail(Side.RIGHT, true), 0, .0625F, -.125F);
        assertPoint(MaidHeldItemTransforms.bedrockTail(Side.LEFT, true), 0, .0625F, -.125F);
    }

    @Test
    public void geckoAppliesModelBaseOnceBeforeItsOwnTail() {
        var matrix = MaidHeldItemTransforms.geckoModelBase()
            .mul(MaidHeldItemTransforms.geckoTail());
        assertPoint(matrix, 0, 1.5625F, -.1F);
        var vertical = matrix.transformDirection(new Vector3f(0, 1, 0));
        assertEquals(0, vertical.x, EPSILON);
        assertEquals(0, vertical.y, EPSILON);
        assertEquals(-1, vertical.z, EPSILON);
    }

    @Test
    public void shieldNativeBasisMatchesBgLandmarksWithoutGenericMirroring() {
        assertLandmarks(MaidHeldItemTransforms.nativeArmEntry(Side.LEFT, Profile.SHIELD_LEFT, false), SHIELD, false);
        var bridged = independentModernBasis(Side.LEFT)
            .mul(MaidHeldItemTransforms.bridge(Side.LEFT, Profile.SHIELD_LEFT, false));
        assertLandmarks(bridged, SHIELD, false);
        assertThrows(
            IllegalArgumentException.class,
            () -> MaidHeldItemTransforms.nativeArmEntry(Side.RIGHT, Profile.SHIELD_LEFT, false));
    }

    @Test
    public void bowAndFlatBridgesReachIndependentNativeLandmarks() {
        assertLandmarks(
            independentModernBasis(Side.RIGHT).mul(MaidHeldItemTransforms.bridge(Side.RIGHT, Profile.BOW, false)),
            BOW,
            false);
        assertLandmarks(
            independentModernBasis(Side.RIGHT).mul(MaidHeldItemTransforms.bridge(Side.RIGHT, Profile.FLAT, false)),
            FLAT,
            false);
    }

    @Test
    public void genericLeftProfileReflectsOnlyTheItemLocalX() {
        assertLandmarks(
            independentModernBasis(Side.LEFT).mul(MaidHeldItemTransforms.bridge(Side.LEFT, Profile.FLAT, false)),
            FLAT,
            true);
    }

    @Test
    public void nonFiniteMatricesAreNotAcceptedAsDrawableCoordinates() {
        assertFalse(MaidHeldItemTransforms.finite(new Matrix4f().m30(Float.NaN)));
        assertFalse(MaidHeldItemTransforms.finite(new Matrix4f().m11(Float.POSITIVE_INFINITY)));
    }

    private static Matrix4f independentModernBasis(Side side) {
        // 原生1.20.1 ItemInHandLayer标准Rx(-90),Ry180,hand offset的精确直角矩阵
        return new Matrix4f().set(
            new float[] { -1, 0, 0, 0, 0, 0, -1, 0, 0, -1, 0, 0, side == Side.RIGHT ? -.0625F : .0625F, .625F, -.125F,
                1 });
    }

    private static void assertPoint(Matrix4f matrix, float x, float y, float z) {
        var point = matrix.transformPosition(new Vector3f());
        assertEquals(x, point.x, EPSILON);
        assertEquals(y, point.y, EPSILON);
        assertEquals(z, point.z, EPSILON);
    }

    private static void assertLandmarks(Matrix4f actual, double[] rows, boolean mirrorX) {
        for (int basis = 0; basis < 4; basis++) {
            var input = new Vector3f(basis == 0 ? 1 : 0, basis == 1 ? 1 : 0, basis == 2 ? 1 : 0);
            var point = actual.transformPosition(input);
            for (int axis = 0; axis < 3; axis++) {
                double expected = rows[axis * 4 + 3] + (basis == 3 ? 0 : rows[axis * 4 + basis]);
                if (axis == 0 && mirrorX) expected = -expected;
                assertEquals("basis=" + basis + ", axis=" + axis, expected, point.get(axis), EPSILON);
            }
        }
    }
}
