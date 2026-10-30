package com.tianluoqaq.tlmnh.client.development;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import net.minecraft.util.ResourceLocation;

import org.junit.Test;

import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.LegacyVertexConsumer;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 比较器和同姿态几何重放检查；真实GL绑定和故障仍由客户端实验验证 */
public class RenderSubmissionTraceTest {

    @Test
    public void bothReviewedModelsReplayStableGeometryWithoutMutatingTheMesh() throws Exception {
        BuiltinModelCatalog.load();
        var models = new BuiltinMaidModels(RenderSubmissionTraceTest::resource);
        for (String id : new String[] { "geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid" }) {
            var model = models.create(BuiltinModelCatalog.get(id));
            var trace = new RenderSubmissionTrace(1, 1, 1, 1);
            model.renderGeometry(new LegacyPoseStack(), trace.expectedConsumer(), 0x00F000F0);
            model.renderGeometry(new LegacyPoseStack(), normalized(trace), 0x00F000F0);
            var result = trace.finish();
            assertTrue(
                id,
                result.get("matched")
                    .getAsBoolean());
            assertTrue(
                result.get("actualVertices")
                    .getAsInt() > 0);
            assertEquals(result.get("expectedSha256"), result.get("actualSha256"));
        }
    }

    @Test
    public void detectsUvOffsetAndDroppedGeometryFromActualArguments() {
        var changed = quad();
        for (int index = 0; index < 4; index++)
            changed.submitted(1, 2, 3, .5F, .25F, .75F, 1, .375F, .5F, 0, 240, 0, .6F, .8F);
        var result = changed.finish();
        assertFalse(
            result.get("matched")
                .getAsBoolean());
        assertEquals(
            "u",
            result.getAsJsonObject("firstDivergence")
                .get("field")
                .getAsString());
        assertEquals(
            .125,
            result.getAsJsonObject("firstDivergence")
                .get("actual")
                .getAsDouble()
                - result.getAsJsonObject("firstDivergence")
                    .get("expected")
                    .getAsDouble(),
            0);
        result = quad().finish();
        assertFalse(
            result.get("matched")
                .getAsBoolean());
        assertEquals(
            "vertexCount",
            result.getAsJsonObject("firstDivergence")
                .get("field")
                .getAsString());
        assertEquals(
            0,
            result.get("actualVertices")
                .getAsInt());
    }

    @Test
    public void emptyOrNonFiniteStreamsCannotPass() {
        assertFalse(
            new RenderSubmissionTrace(1, 1, 1, 1).finish()
                .get("matched")
                .getAsBoolean());
        assertThrows(IllegalArgumentException.class, () -> new RenderSubmissionTrace(Float.NaN, 1, 1, 1));
        var trace = new RenderSubmissionTrace(1, 1, 1, 1);
        assertThrows(
            IllegalArgumentException.class,
            () -> trace.expectedConsumer()
                .vertex(Float.NaN, 0, 0, 1, 1, 1, 1, 0, 0, 0, 240, 0, 0, 1));
    }

    private static RenderSubmissionTrace quad() {
        var trace = new RenderSubmissionTrace(.5F, .25F, .75F, 1);
        for (int index = 0; index < 4; index++) trace.expectedConsumer()
            .vertex(1, 2, 3, 1, 1, 1, 1, .25F, .5F, 0, 240, 0, 3, 4);
        return trace;
    }

    private static LegacyVertexConsumer normalized(RenderSubmissionTrace trace) {
        return (x, y, z, red, green, blue, alpha, u, v, overlay, light, nx, ny, nz) -> {
            double length = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
            if (length > 0) {
                nx = (float) (nx / length);
                ny = (float) (ny / length);
                nz = (float) (nz / length);
            }
            trace.submitted(x, y, z, red, green, blue, alpha, u, v, overlay, light, nx, ny, nz);
        };
    }

    private static java.io.InputStream resource(ResourceLocation location) {
        return RenderSubmissionTraceTest.class
            .getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath());
    }
}
